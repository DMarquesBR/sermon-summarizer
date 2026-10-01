package br.com.sermonsummarizer.transcription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class GroqTranscriber {
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "sermon.groq.url") String endpoint;
    @ConfigProperty(name = "sermon.groq.api-key") Optional<String> apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public boolean isConfigured() { return apiKey.isPresent() && !apiKey.orElseThrow().isBlank(); }

    public Transcript transcribe(Path audio, long offsetSeconds) throws IOException, InterruptedException {
        if (!isConfigured()) throw new IllegalStateException("GROQ_API_KEY não configurada.");
        String boundary = "sermon-" + UUID.randomUUID();
        var parts = new ArrayList<HttpRequest.BodyPublisher>();
        field(parts, boundary, "model", "whisper-large-v3-turbo");
        field(parts, boundary, "response_format", "verbose_json");
        field(parts, boundary, "timestamp_granularities[]", "segment");
        field(parts, boundary, "language", "pt");
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n"));
        parts.add(HttpRequest.BodyPublishers.ofFile(audio));
        parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + apiKey.orElseThrow())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("Groq retornou HTTP " + response.statusCode() + ".");
        JsonNode root = mapper.readTree(response.body());
        String text = root.path("text").asText("");
        if (text.isBlank()) throw new IOException("Groq devolveu transcrição vazia.");
        List<Segment> segments = new ArrayList<>();
        for (JsonNode segment : root.path("segments")) {
            segments.add(new Segment(segment.path("start").asDouble() + offsetSeconds,
                    segment.path("end").asDouble() + offsetSeconds, segment.path("text").asText()));
        }
        return new Transcript(text, List.copyOf(segments));
    }

    private static void field(List<HttpRequest.BodyPublisher> parts, String boundary, String name, String value) {
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n", StandardCharsets.UTF_8));
    }

    public record Segment(double startSeconds, double endSeconds, String text) {}
    public record Transcript(String text, List<Segment> segments) {}
}
