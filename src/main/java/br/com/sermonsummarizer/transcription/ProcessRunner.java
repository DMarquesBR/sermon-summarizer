package br.com.sermonsummarizer.transcription;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ProcessRunner {
    @ConfigProperty(name = "sermon.process-timeout-seconds") long timeoutSeconds;

    public String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("Processo externo excedeu o tempo limite de " + Duration.ofSeconds(timeoutSeconds));
            }
            reader.join();
            String message = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("Processo externo falhou (código " + process.exitValue() + "): " + message);
            return message;
        } catch (InterruptedException exception) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static void drain(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (output.size() < 8192) output.write(chunk, 0, Math.min(count, 8192 - output.size()));
            }
        } catch (IOException ignored) {
            // O código de saída do processo informa a falha ao chamador.
        }
    }
}
