package br.com.sermonsummarizer.transcription;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@ApplicationScoped
public class YoutubeAudioExtractor {
    @Inject ProcessRunner processes;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "sermon.yt-dlp.path") String downloader;
    @ConfigProperty(name = "sermon.ffmpeg.path") String ffmpeg;
    @ConfigProperty(name = "sermon.ffprobe.path") String ffprobe;
    @ConfigProperty(name = "sermon.max-clip-seconds") long maxClipSeconds;
    @ConfigProperty(name = "sermon.max-download-bytes") long maxDownloadBytes;

    public ExtractedAudio extract(YoutubeUrl video, Long startSeconds, Long endSeconds, Consumer<String> progress) throws IOException, InterruptedException {
        long start = startSeconds == null ? 0 : startSeconds;
        if (start < 0 || (endSeconds != null && endSeconds <= start)) throw new IllegalArgumentException("Intervalo inválido.");
        if (endSeconds != null && endSeconds - start > maxClipSeconds) throw new IllegalArgumentException("Trecho excede o limite de duração.");

        Path directory = Files.createTempDirectory("sermon-audio-");
        try {
            progress.accept("DOWNLOADING");
            processes.run(List.of(downloader, "--ignore-config", "--js-runtimes", "node", "--no-playlist", "--no-progress", "--no-warnings",
                    "--write-info-json", "--max-filesize", Long.toString(maxDownloadBytes), "-f", "bestaudio/best",
                    "-o", directory.resolve("source.%(ext)s").toString(), "--", video.canonicalUrl()));
            Path source;
            try (var files = Files.list(directory)) {
                source = files.filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("source.") && !name.endsWith(".info.json")
                            && !name.endsWith(".part") && !name.endsWith(".ytdl")
                            && !name.contains(".part-") && Files.isRegularFile(path);
                })
                        .findFirst().orElseThrow(() -> new IOException("Download não produziu arquivo de mídia."));
            }
            if (Files.size(source) > maxDownloadBytes) throw new IOException("Download excede o limite de tamanho.");
            String durationOutput = processes.run(List.of(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", source.toString()));
            double duration;
            try { duration = Double.parseDouble(durationOutput.trim()); }
            catch (NumberFormatException exception) { throw new IOException("Não foi possível medir a duração do vídeo.", exception); }
            if (!Double.isFinite(duration) || duration <= 0 || start >= duration) throw new IllegalArgumentException("Trecho fora da duração do vídeo.");
            if (endSeconds != null && endSeconds > duration + 0.5) throw new IllegalArgumentException("Fim do trecho ultrapassa a duração do vídeo.");
            double end = endSeconds == null ? duration : Math.min(endSeconds, duration);
            if (end - start > maxClipSeconds) throw new IllegalArgumentException("Trecho excede o limite de duração.");

            progress.accept("EXTRACTING");
            Path audio = directory.resolve("audio.mp3");
            List<String> command = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y"));
            if (start > 0) { command.add("-ss"); command.add(Long.toString(start)); }
            command.addAll(List.of("-i", source.toString()));
            if (endSeconds != null) { command.add("-t"); command.add(String.format(Locale.ROOT, "%.3f", end - start)); }
            command.addAll(List.of("-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "32k", audio.toString()));
            processes.run(command);
            if (!Files.isRegularFile(audio) || Files.size(audio) == 0) throw new IOException("Áudio extraído está vazio.");
            if (Files.size(audio) > 25_000_000) throw new IOException("Áudio extraído excede 25 MB; selecione um trecho menor.");
            return new ExtractedAudio(directory, audio, start, end - start, readMetadata(directory.resolve("source.info.json")));
        } catch (IOException | InterruptedException | RuntimeException exception) {
            deleteDirectory(directory);
            throw exception;
        }
    }

    private VideoMetadata readMetadata(Path file) {
        try {
            var root = mapper.readTree(file.toFile());
            if (root == null || !root.path("title").isTextual() || root.path("title").asText().isBlank()) return null;
            var description = root.path("description");
            return new VideoMetadata(root.path("title").asText(), description.isTextual() ? description.asText() : "");
        } catch (IOException exception) {
            return null;
        }
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public record ExtractedAudio(Path directory, Path file, long offsetSeconds, double durationSeconds, VideoMetadata video) implements AutoCloseable {
        @Override public void close() throws IOException { deleteDirectory(directory); }
    }
}
