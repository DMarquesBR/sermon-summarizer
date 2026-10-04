package br.com.sermonsummarizer.transcription;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TranscriptionResourceTest {
    @Test void returnsVideoAlongsideTranscript() throws Exception {
        var directories = installFakes("{\"title\":\"Pregação\",\"description\":\"Descrição\\nSegunda linha\"}", false);
        String statusUrl = submit();
        awaitFinished(statusUrl);
        given().get(statusUrl).then().statusCode(200)
                .body("videoId", equalTo("dQw4w9WgXcQ"))
                .body("status", equalTo("COMPLETED"))
                .body("video.title", equalTo("Pregação"))
                .body("video.description", equalTo("Descrição\nSegunda linha"))
                .body("video.size()", equalTo(2))
                .body("transcript.text", equalTo("Transcrição de teste"))
                .body("transcript.segments[0].startSeconds", equalTo(1.0f));
        assertFalse(Files.exists(directories.getFirst()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid", "{\"title\":\" \"}"})
    void completesWithoutReadableMetadata(String json) throws Exception {
        var directories = installFakes(json, false);
        String statusUrl = submit();
        awaitFinished(statusUrl);
        given().get(statusUrl).then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("video", nullValue())
                .body("transcript.text", equalTo("Transcrição de teste"));
        assertFalse(Files.exists(directories.getFirst()));
    }

    @Test void retainsMetadataWhenTranscriptionFails() throws Exception {
        var directories = installFakes("{\"title\":\"Pregação\"}", true);
        String statusUrl = submit();
        awaitFinished(statusUrl);
        given().get(statusUrl).then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("video.title", equalTo("Pregação"))
                .body("video.description", equalTo(""))
                .body("error", equalTo("Falha de transcrição simulada"));
        assertFalse(Files.exists(directories.getFirst()));
    }

    private List<Path> installFakes(String json, boolean failTranscription) {
        var directories = new java.util.concurrent.CopyOnWriteArrayList<Path>();
        QuarkusMock.installMockForType(new ProcessRunner() {
            @Override public String run(List<String> command) throws IOException {
                if (command.contains("--write-info-json")) {
                    Path directory = Path.of(command.get(command.indexOf("-o") + 1)).getParent();
                    directories.add(directory);
                    if (json != null) Files.writeString(directory.resolve("source.info.json"), json);
                    Files.writeString(directory.resolve("source.wav"), "media");
                    return "";
                }
                if (command.contains("format=duration")) return "3";
                if (command.contains("libmp3lame")) {
                    Files.writeString(Path.of(command.getLast()), "audio");
                    return "";
                }
                throw new AssertionError("Unexpected command: " + command);
            }
        }, ProcessRunner.class);
        QuarkusMock.installMockForType(new GroqTranscriber() {
            @Override public boolean isConfigured() { return true; }
            @Override public Transcript transcribe(Path audio, long offsetSeconds) throws IOException {
                if (failTranscription) throw new IOException("Falha de transcrição simulada");
                return new Transcript("Transcrição de teste", List.of(new Segment(offsetSeconds, offsetSeconds + 1, "Transcrição de teste")));
            }
        }, GroqTranscriber.class);
        return directories;
    }

    private String submit() {
        return given().contentType("application/json")
                .body("{\"url\":\"https://youtu.be/dQw4w9WgXcQ\",\"startSeconds\":1,\"endSeconds\":2}")
                .post("/api/transcription-jobs").then().statusCode(202)
                .body("jobId", notNullValue()).header("Location", startsWith("http"))
                .extract().path("statusUrl");
    }

    private void awaitFinished(String statusUrl) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var response = given().get(statusUrl).then().statusCode(200).extract().jsonPath();
            if (response.getString("finishedAt") != null) return;
            Thread.sleep(10);
        }
        fail("Job não finalizou dentro do tempo limite");
    }
}
