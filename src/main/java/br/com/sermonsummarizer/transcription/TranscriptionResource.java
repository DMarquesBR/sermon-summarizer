package br.com.sermonsummarizer.transcription;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Path("/api/transcription-jobs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TranscriptionResource {
    @Inject TranscriptionJobs jobs;
    @Inject GroqTranscriber transcriber;

    @POST
    public Response submit(Request request) {
        if (request == null) throw new BadRequestException("Informe a URL do vídeo.");
        if (!transcriber.isConfigured()) return Response.status(503).entity(new Error("GROQ_API_KEY não configurada.")).build();
        try {
            YoutubeUrl video = YoutubeUrl.parse(request.url());
            UUID id = jobs.submit(video, request.startSeconds(), request.endSeconds(), request.language());
            URI location = URI.create("/api/transcription-jobs/" + id);
            return Response.accepted(new Submitted(id, location.toString())).location(location).build();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        } catch (RejectedExecutionException exception) {
            return Response.status(429).entity(new Error("Muitos jobs aguardando processamento.")).build();
        }
    }

    @GET @Path("/{id}")
    public TranscriptionJobs.Job get(@PathParam("id") UUID id) {
        var job = jobs.get(id);
        if (job == null) throw new NotFoundException("Job não encontrado.");
        return job;
    }

    public record Request(String url, Long startSeconds, Long endSeconds, String language) {}
    public record Submitted(UUID jobId, String statusUrl) {}
    public record Error(String message) {}
}
