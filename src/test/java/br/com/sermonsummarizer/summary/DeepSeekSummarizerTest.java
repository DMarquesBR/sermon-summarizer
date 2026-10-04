package br.com.sermonsummarizer.summary;

import br.com.sermonsummarizer.transcription.VideoMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekSummarizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private DeepSeekSummarizer summarizer;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(mapper.readTree(exchange.getRequestBody()));
            Reply reply = replies.remove();
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();

        summarizer = new DeepSeekSummarizer();
        summarizer.mapper = mapper;
        summarizer.prompts = new SermonSummaryPrompts();
        summarizer.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        summarizer.model = "deepseek-flash-test";
        summarizer.apiKey = Optional.of("test-key");
    }

    @AfterEach void tearDown() { server.stop(0); }

    @Test void sendsDefaultPromptAndSourceSeparatelyAndCountsUnicodeCodePoints() throws Exception {
        replies.add(ok("😀 *fé*", "stop", 19, 7));

        var result = summarizer.summarize(new VideoMetadata("Título", "Descrição"), "Transcrição inteira", null);

        assertEquals(6, result.characterCount());
        assertEquals("DEFAULT", result.promptSource());
        assertEquals("deepseek-flash-test", result.model());
        assertEquals(new DeepSeekSummarizer.Usage(19, 7, 26), result.usage());
        JsonNode request = requests.getFirst();
        assertEquals("Bearer test-key", authorizationHeaders.getFirst());
        assertEquals("deepseek-flash-test", request.path("model").asText());
        assertEquals(1500, request.path("max_tokens").asInt());
        assertEquals(0.3, request.path("temperature").asDouble());
        assertFalse(request.path("stream").asBoolean());
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertTrue(request.path("messages").get(1).path("content").asText().contains("tópicos principais"));
        assertTrue(request.path("messages").get(2).path("content").asText().contains("Transcrição inteira"));
        assertTrue(request.path("messages").get(2).path("content").asText().contains("Descrição"));
        assertEquals(5, DeepSeekSummarizer.characterCount("😀 *x*"));
    }

    @Test void customPromptReplacesOnlyDefaultStructure() throws Exception {
        replies.add(ok("Resumo", "stop", 10, 2));

        var result = summarizer.summarize(null, "Transcrição", "Use duas frases.");

        assertEquals("CUSTOM", result.promptSource());
        assertEquals("Use duas frases.", requests.getFirst().path("messages").get(1).path("content").asText().replace("INSTRUÇÕES DE ORGANIZAÇÃO DO RESUMO (instruções do usuário):\n", ""));
        assertTrue(requests.getFirst().path("messages").get(0).path("content").asText().contains("Não invente nomes"));
    }

    @Test void blankCustomPromptsUseTheDefaultInstructions() throws Exception {
        SermonSummaryPrompts promptBuilder = new SermonSummaryPrompts();
        assertEquals(promptBuilder.defaultInstructions(), promptBuilder.instructions(null));
        assertEquals(promptBuilder.defaultInstructions(), promptBuilder.instructions(""));
        assertEquals(promptBuilder.defaultInstructions(), promptBuilder.instructions("  \n  "));
    }

    @Test void condensesLongAnswerOnceAndSumsUsage() throws Exception {
        replies.add(ok("a".repeat(2001), "stop", 10, 20));
        replies.add(ok("😀".repeat(2000), "stop", 30, 40));

        var result = summarizer.summarize(null, "Fonte preservada", "Estrutura própria");

        assertEquals(2000, result.characterCount());
        assertEquals(2, requests.size());
        assertEquals(new DeepSeekSummarizer.Usage(40, 60, 100), result.usage());
        assertTrue(requests.get(1).path("messages").get(2).path("content").asText().contains("Fonte preservada"));
        assertTrue(requests.get(1).path("messages").get(4).path("content").asText().contains("no máximo 2000"));
    }

    @Test void failsInsteadOfTruncatingWhenCondensedAnswerStillExceedsLimit() throws Exception {
        replies.add(ok("a".repeat(2001), "stop", 1, 1));
        replies.add(ok("b".repeat(2001), "stop", 1, 1));

        var exception = assertThrows(DeepSeekSummarizer.ProviderException.class,
                () -> summarizer.summarize(null, "Fonte", null));

        assertEquals("SUMMARY_TOO_LONG", exception.code());
        assertEquals(2, requests.size());
    }

    @Test void rejectsProviderOutputCutOffByTokenLimit() {
        replies.add(ok("Resumo cortado", "length", 1, 1500));

        var exception = assertThrows(DeepSeekSummarizer.ProviderException.class,
                () -> summarizer.summarize(null, "Fonte", null));

        assertEquals("PROVIDER_INCOMPLETE_RESPONSE", exception.code());
    }

    @ParameterizedTest
    @CsvSource({"401,PROVIDER_AUTHENTICATION_FAILED", "402,PROVIDER_INSUFFICIENT_BALANCE",
            "429,PROVIDER_RATE_LIMITED", "503,PROVIDER_UNAVAILABLE"})
    void mapsProviderErrorsWithoutReturningRawBody(int status, String expectedCode) {
        replies.add(new Reply(status, "secret provider response"));

        var exception = assertThrows(DeepSeekSummarizer.ProviderException.class,
                () -> summarizer.summarize(null, "Fonte", null));

        assertEquals(expectedCode, exception.code());
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test void rejectsEmptyProviderContent() {
        replies.add(ok("   ", "stop", 1, 1));

        var exception = assertThrows(DeepSeekSummarizer.ProviderException.class,
                () -> summarizer.summarize(null, "Fonte", null));

        assertEquals("PROVIDER_INVALID_RESPONSE", exception.code());
    }

    private static Reply ok(String content, String finishReason, long inputTokens, long outputTokens) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"" + escaped
                + "\"},\"finish_reason\":\"" + finishReason + "\"}],\"usage\":{\"prompt_tokens\":"
                + inputTokens + ",\"completion_tokens\":" + outputTokens + "}}");
    }

    private record Reply(int status, String body) {}
}
