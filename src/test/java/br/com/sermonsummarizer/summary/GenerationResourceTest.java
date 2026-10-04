package br.com.sermonsummarizer.summary;

import br.com.sermonsummarizer.transcription.VideoMetadata;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GenerationResourceTest {
    @Inject GenerationJobs jobs;

    @Test void publishesGenerationEndpointsInOpenApi() {
        given().get("/q/openapi?format=json").then().statusCode(200)
                .body(containsString("/api/generation-jobs"))
                .body(containsString("transcriptText"))
                .body(containsString("customPrompt"));
    }

    @Test void submitsAndReturnsCompletedSummary() throws Exception {
        installSummarizer(new DeepSeekSummarizer() {
            @Override public boolean isConfigured() { return true; }
            @Override public Summary summarize(VideoMetadata video, String transcript, String prompt) {
                assertEquals("Transcrição", transcript);
                assertEquals("Sermão", video.title());
                assertNull(prompt);
                return new Summary("😀 *Resumo*", 10, "deepseek-flash", "DEFAULT", new Usage(20, 8, 28));
            }
        });

        String statusUrl = submit("Transcrição", "Sermão", null);
        awaitFinished(statusUrl);

        given().get(statusUrl).then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("promptSource", equalTo("DEFAULT"))
                .body("result.text", equalTo("😀 *Resumo*"))
                .body("result.characterCount", equalTo(10))
                .body("result.usage.totalTokens", equalTo(28));
    }

    @Test void rejectsBlankAndOversizedInput() {
        installSummarizer(configuredFake());

        given().contentType("application/json").body("{\"transcriptText\":\"  \"}")
                .post("/api/generation-jobs").then().statusCode(400)
                .body("code", equalTo("INVALID_INPUT"));
        given().contentType("application/json").body("{\"transcriptText\":\"" + "x".repeat(200001) + "\"}")
                .post("/api/generation-jobs").then().statusCode(400)
                .body("code", equalTo("INVALID_INPUT"));
    }

    @Test void acceptsTranscriptionAtTheUnicodeCharacterLimit() throws Exception {
        installSummarizer(new DeepSeekSummarizer() {
            @Override public boolean isConfigured() { return true; }
            @Override public Summary summarize(VideoMetadata video, String transcript, String prompt) {
                return new Summary("Resumo", 6, "deepseek-flash", "DEFAULT", new Usage(1, 1, 2));
            }
        });

        String transcript = "😀".repeat(200000);
        String statusUrl = submit(transcript, null, null);
        awaitFinished(statusUrl);
        given().get(statusUrl).then().statusCode(200).body("status", equalTo("COMPLETED"));
    }

    @Test void reportsMissingCredentialAndUnknownJobAsStableErrors() {
        installSummarizer(new DeepSeekSummarizer() {
            @Override public boolean isConfigured() { return false; }
        });

        given().contentType("application/json").body("{\"transcriptText\":\"Transcrição válida\"}")
                .post("/api/generation-jobs").then().statusCode(503)
                .body("code", equalTo("PROVIDER_NOT_CONFIGURED"))
                .body("retryable", equalTo(false));
        given().get("/api/generation-jobs/" + UUID.randomUUID()).then().statusCode(404)
                .body("code", equalTo("JOB_NOT_FOUND"));
    }

    @Test void reportsProviderFailureOnJobStatus() throws Exception {
        installSummarizer(new DeepSeekSummarizer() {
            @Override public boolean isConfigured() { return true; }
            @Override public Summary summarize(VideoMetadata video, String transcript, String prompt)
                    throws ProviderException {
                throw new ProviderException("PROVIDER_RATE_LIMITED", "Limite temporário.", true);
            }
        });

        String statusUrl = submit("Transcrição", null, "");
        awaitFinished(statusUrl);
        given().get(statusUrl).then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("error.code", equalTo("PROVIDER_RATE_LIMITED"))
                .body("error.retryable", equalTo(true));
    }

    @Test void removesFinishedJobsAfterAnHourOnNextSubmission() throws Exception {
        installSummarizer(new DeepSeekSummarizer() {
            @Override public boolean isConfigured() { return true; }
            @Override public Summary summarize(VideoMetadata video, String transcript, String prompt) {
                return new Summary("Resumo", 6, "deepseek-flash", "DEFAULT", new Usage(1, 1, 2));
            }
        });

        String statusUrl = submit("Transcrição", null, null);
        awaitFinished(statusUrl);
        UUID id = UUID.fromString(statusUrl.substring(statusUrl.lastIndexOf('/') + 1));
        GenerationJobs.Job job = jobs.get(id);
        assertNotNull(job);
        job.finishedAt = Instant.now().minusSeconds(3601);
        submit("Outra transcrição", null, null);

        given().get(statusUrl).then().statusCode(404).body("code", equalTo("JOB_NOT_FOUND"));
    }

    @Test void rejectsWhenWorkerPoolAndQueueAreFull() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        installSummarizer(new DeepSeekSummarizer() {
            @Override public boolean isConfigured() { return true; }
            @Override public Summary summarize(VideoMetadata video, String transcript, String prompt)
                    throws ProviderException {
                workersStarted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new ProviderException("TEST_TIMEOUT", "Timeout", true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException("TEST_INTERRUPTED", "Interrompido", true);
                }
                return new Summary("Resumo", 6, "deepseek-flash", "DEFAULT", new Usage(1, 1, 2));
            }
        });

        try {
            for (int i = 0; i < 2; i++) submit("Transcrição " + i, null, null);
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) {
                given().contentType("application/json").body("{\"transcriptText\":\"Na fila\"}")
                        .post("/api/generation-jobs").then().statusCode(202);
            }
            given().contentType("application/json").body("{\"transcriptText\":\"Fila cheia\"}")
                    .post("/api/generation-jobs").then().statusCode(429)
                    .body("code", equalTo("GENERATION_QUEUE_FULL"));
        } finally {
            release.countDown();
        }
    }

    private static DeepSeekSummarizer configuredFake() {
        return new DeepSeekSummarizer() { @Override public boolean isConfigured() { return true; } };
    }

    private void installSummarizer(DeepSeekSummarizer fake) { QuarkusMock.installMockForType(fake, DeepSeekSummarizer.class); }

    private String submit(String transcript, String title, String prompt) {
        var video = title == null ? null : new VideoMetadata(title, "Descrição");
        return given().contentType("application/json")
                .body(new GenerationResource.Request(transcript, video, prompt))
                .post("/api/generation-jobs").then().statusCode(202)
                .body("jobId", notNullValue()).header("Location", startsWith("http"))
                .extract().path("statusUrl");
    }

    private void awaitFinished(String statusUrl) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var response = given().get(statusUrl).then().statusCode(anyOf(is(200), is(404))).extract().response();
            if (response.statusCode() == 200 && response.jsonPath().getString("finishedAt") != null) return;
            Thread.sleep(10);
        }
        fail("Job de geração não finalizou dentro do tempo limite");
    }

}
