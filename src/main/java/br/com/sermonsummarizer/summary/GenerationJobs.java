package br.com.sermonsummarizer.summary;

import br.com.sermonsummarizer.transcription.VideoMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class GenerationJobs {
    @Inject DeepSeekSummarizer summarizer;
    @ConfigProperty(name = "sermon.workers") int workers;
    @ConfigProperty(name = "sermon.summary.max-input-characters") int maxInputCharacters;

    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private ThreadPoolExecutor executor;

    @PostConstruct void initialize() {
        int count = Math.max(1, workers);
        executor = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), Thread.ofVirtual().factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean isConfigured() { return summarizer.isConfigured(); }

    public UUID submit(String transcriptText, VideoMetadata video, String customPrompt) {
        validateInput(transcriptText, video, customPrompt);

        Instant expiry = Instant.now().minusSeconds(3600);
        jobs.entrySet().removeIf(entry -> entry.getValue().finishedAt != null && entry.getValue().finishedAt.isBefore(expiry));
        UUID id = UUID.randomUUID();
        Job job = new Job(id, Instant.now(), customPrompt == null || customPrompt.isBlank() ? "DEFAULT" : "CUSTOM");
        jobs.put(id, job);
        try {
            executor.submit(() -> run(job, transcriptText, video, customPrompt));
        } catch (RejectedExecutionException exception) {
            jobs.remove(id);
            throw exception;
        }
        return id;
    }

    public void validateInput(String transcriptText, VideoMetadata video, String customPrompt) {
        if (transcriptText == null || transcriptText.isBlank()) throw new IllegalArgumentException("A transcrição não pode estar vazia.");
        long totalCharacters = DeepSeekSummarizer.characterCount(transcriptText)
                + characterCount(video == null ? null : video.title())
                + characterCount(video == null ? null : video.description())
                + characterCount(customPrompt);
        if (totalCharacters > maxInputCharacters) {
            throw new IllegalArgumentException("O conteúdo total excede o limite de " + maxInputCharacters + " caracteres.");
        }
    }

    public Job get(UUID id) { return jobs.get(id); }

    private void run(Job job, String transcriptText, VideoMetadata video, String customPrompt) {
        job.status = "GENERATING";
        try {
            job.result = summarizer.summarize(video, transcriptText, customPrompt);
            job.status = "COMPLETED";
        } catch (DeepSeekSummarizer.ProviderException exception) {
            job.error = new Error(exception.code(), exception.getMessage(), exception.retryable());
            job.status = "FAILED";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.error = new Error("GENERATION_INTERRUPTED", "A geração do resumo foi interrompida.", true);
            job.status = "FAILED";
        } catch (RuntimeException exception) {
            job.error = new Error("GENERATION_FAILED", "Não foi possível gerar o resumo.", true);
            job.status = "FAILED";
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    private static int characterCount(String value) { return value == null ? 0 : DeepSeekSummarizer.characterCount(value); }

    @PreDestroy void shutdown() { if (executor != null) executor.shutdownNow(); }

    public static final class Job {
        public final UUID id;
        public final Instant createdAt;
        public final String promptSource;
        public volatile String status = "QUEUED";
        public volatile Instant finishedAt;
        public volatile DeepSeekSummarizer.Summary result;
        public volatile Error error;

        private Job(UUID id, Instant createdAt, String promptSource) {
            this.id = id;
            this.createdAt = createdAt;
            this.promptSource = promptSource;
        }
    }

    public record Error(String code, String message, boolean retryable) {}
}
