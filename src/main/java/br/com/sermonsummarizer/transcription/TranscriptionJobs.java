package br.com.sermonsummarizer.transcription;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

@ApplicationScoped
public class TranscriptionJobs {
    @Inject YoutubeAudioExtractor extractor;
    @Inject GroqTranscriber transcriber;
    @ConfigProperty(name = "sermon.workers") int workers;
    @ConfigProperty(name = "sermon.max-clip-seconds") long maxClipSeconds;
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private ThreadPoolExecutor executor;

    @PostConstruct void initialize() {
        int count = Math.max(1, workers);
        executor = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), Thread.ofVirtual().factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public UUID submit(YoutubeUrl url, Long start, Long end) {
        if (start != null && start < 0 || end != null && end <= (start == null ? 0 : start)) {
            throw new IllegalArgumentException("Intervalo inválido.");
        }
        if (end != null && end - (start == null ? 0 : start) > maxClipSeconds) {
            throw new IllegalArgumentException("Trecho excede o limite de duração.");
        }
        jobs.entrySet().removeIf(entry -> entry.getValue().finishedAt != null && entry.getValue().finishedAt.isBefore(Instant.now().minusSeconds(3600)));
        UUID id = UUID.randomUUID();
        jobs.put(id, new Job(id, url.videoId(), Instant.now()));
        try {
            executor.submit(() -> run(jobs.get(id), url, start, end));
        } catch (RejectedExecutionException exception) {
            jobs.remove(id);
            throw exception;
        }
        return id;
    }

    public Job get(UUID id) { return jobs.get(id); }

    private void run(Job job, YoutubeUrl url, Long start, Long end) {
        try {
            try (var audio = extractor.extract(url, start, end, progress -> job.status = progress)) {
                job.status = "TRANSCRIBING";
                job.transcript = transcriber.transcribe(audio.file(), audio.offsetSeconds());
                job.status = "COMPLETED";
            }
        } catch (Exception exception) {
            job.error = exception.getMessage();
            job.status = "FAILED";
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    @PreDestroy void shutdown() { if (executor != null) executor.shutdownNow(); }

    public static final class Job {
        public final UUID id;
        public final String videoId;
        public final Instant createdAt;
        public volatile String status = "QUEUED";
        public volatile Instant finishedAt;
        public volatile GroqTranscriber.Transcript transcript;
        public volatile String error;

        private Job(UUID id, String videoId, Instant createdAt) {
            this.id = id; this.videoId = videoId; this.createdAt = createdAt;
        }
    }
}
