package br.com.sermonsummarizer.summary;

import br.com.sermonsummarizer.transcription.VideoMetadata;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Path("/api/generation-jobs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GenerationResource {
    @Inject GenerationJobs jobs;

    @POST
    @Operation(summary = "Iniciar a geração de um resumo para WhatsApp")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Job de geração criado", content = @Content(schema = @Schema(implementation = Submitted.class))),
            @APIResponse(responseCode = "400", description = "Transcrição ausente ou entrada acima do limite", content = @Content(schema = @Schema(implementation = Failure.class))),
            @APIResponse(responseCode = "429", description = "Fila de geração cheia", content = @Content(schema = @Schema(implementation = Failure.class))),
            @APIResponse(responseCode = "503", description = "Chave DeepSeek não configurada", content = @Content(schema = @Schema(implementation = Failure.class)))
    })
    public Response submit(Request request) {
        if (request == null) return Response.status(400).entity(new Failure("INVALID_INPUT", "Informe o texto da transcrição.", false)).build();
        try {
            jobs.validateInput(request.transcriptText(), request.video(), request.customPrompt());
        } catch (IllegalArgumentException exception) {
            return Response.status(400).entity(new Failure("INVALID_INPUT", exception.getMessage(), false)).build();
        }
        if (!jobs.isConfigured()) {
            return Response.status(503).entity(new Failure("PROVIDER_NOT_CONFIGURED", "DEEPSEEK_API_KEY não configurada.", false)).build();
        }
        try {
            UUID id = jobs.submit(request.transcriptText(), request.video(), request.customPrompt());
            URI location = URI.create("/api/generation-jobs/" + id);
            return Response.accepted(new Submitted(id, location.toString())).location(location).build();
        } catch (IllegalArgumentException exception) {
            return Response.status(400).entity(new Failure("INVALID_INPUT", exception.getMessage(), false)).build();
        } catch (RejectedExecutionException exception) {
            return Response.status(429).entity(new Failure("GENERATION_QUEUE_FULL", "Muitos jobs aguardando geração.", true)).build();
        }
    }

    @GET @Path("/{id}")
    @Operation(summary = "Consultar o estado de um job de geração")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Estado e resultado do job", content = @Content(schema = @Schema(implementation = GenerationJobs.Job.class))),
            @APIResponse(responseCode = "404", description = "Job não encontrado", content = @Content(schema = @Schema(implementation = Failure.class)))
    })
    public Response get(@PathParam("id") UUID id) {
        GenerationJobs.Job job = jobs.get(id);
        if (job == null) return Response.status(404).entity(new Failure("JOB_NOT_FOUND", "Job de geração não encontrado.", false)).build();
        return Response.ok(job).build();
    }

    public record Request(String transcriptText, VideoMetadata video, String customPrompt) {}
    public record Submitted(UUID jobId, String statusUrl) {}
    public record Failure(String code, String message, boolean retryable) {}
}
