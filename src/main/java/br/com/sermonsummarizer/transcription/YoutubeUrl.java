package br.com.sermonsummarizer.transcription;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

public record YoutubeUrl(String videoId, String canonicalUrl) {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public static YoutubeUrl parse(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IllegalArgumentException("Use uma URL HTTPS pública do YouTube.");
            }
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null) throw new IllegalArgumentException("URL do YouTube inválida.");
            host = host.toLowerCase();
            String id;
            if (host.equals("youtu.be")) {
                id = firstSegment(path);
            } else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")) {
                if (path.equals("/watch")) {
                    id = Arrays.stream((uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&"))
                            .filter(part -> part.startsWith("v="))
                            .map(part -> URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8))
                            .findFirst().orElse("");
                } else if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
                    id = path.split("/")[2];
                } else {
                    throw new IllegalArgumentException("Formato de URL do YouTube não aceito.");
                }
            } else {
                throw new IllegalArgumentException("A URL deve ser do YouTube.");
            }
            if (!VIDEO_ID.matcher(id).matches()) throw new IllegalArgumentException("ID de vídeo inválido.");
            return new YoutubeUrl(id, "https://www.youtube.com/watch?v=" + id);
        } catch (NullPointerException | IndexOutOfBoundsException | java.lang.IllegalArgumentException exception) {
            if (exception instanceof IllegalArgumentException && exception.getMessage() != null && !exception.getMessage().startsWith("Expected")) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("URL do YouTube inválida.", exception);
        }
    }

    private static String firstSegment(String path) {
        String[] parts = path.split("/");
        return parts.length > 1 ? parts[1] : "";
    }
}
