package br.com.sermonsummarizer.transcription;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YoutubeAudioExtractorTest {
    @Test void extractsAndDeletesTemporaryAudio() throws Exception {
        Path wav = Files.createTempFile("sermon-source-", ".wav");
        byte[] samples = new byte[16000 * 2 * 3];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(samples),
                new AudioFormat(16000, 16, 1, true, false), 16000 * 3)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        var processes = new ProcessRunner() {
            @Override public String run(List<String> command) throws java.io.IOException, InterruptedException {
                if (command.getFirst().equals("fake-yt-dlp")) {
                    String pattern = command.get(command.indexOf("-o") + 1);
                    assertTrue(command.contains("--write-info-json"));
                    Files.writeString(Path.of(pattern).getParent().resolve("source.info.json"),
                            "{\"title\":\"Pregação\",\"description\":\"Descrição do vídeo\"}");
                    Files.copy(wav, Path.of(pattern.replace("%(ext)s", "wav")));
                    return "";
                }
                return super.run(command);
            }
        };
        processes.timeoutSeconds = 30;
        var extractor = new YoutubeAudioExtractor();
        extractor.processes = processes;
        extractor.mapper = new ObjectMapper();
        extractor.downloader = "fake-yt-dlp";
        extractor.ffmpeg = "ffmpeg";
        extractor.ffprobe = "ffprobe";
        extractor.maxClipSeconds = 5400;
        extractor.maxDownloadBytes = 1_000_000;
        var states = new ArrayList<String>();
        Path audio;
        Path directory;
        try (var result = extractor.extract(YoutubeUrl.parse("https://youtu.be/dQw4w9WgXcQ"), 1L, 2L, states::add)) {
            audio = result.file();
            directory = result.directory();
            assertEquals(new VideoMetadata("Pregação", "Descrição do vídeo"), result.video());
            assertTrue(Files.size(audio) > 0);
            double actualDuration = Double.parseDouble(processes.run(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", audio.toString())).trim());
            assertTrue(actualDuration > 0.9 && actualDuration < 1.2, "O áudio deve conter apenas o trecho pedido.");
            assertEquals(1, result.offsetSeconds());
            assertEquals(List.of("DOWNLOADING", "EXTRACTING"), states);
        } finally {
            Files.deleteIfExists(wav);
        }
        assertFalse(Files.exists(audio));
        assertFalse(Files.exists(directory));
    }

    @Test void preservesFullDescription() throws Exception {
        String description = "Descrição 🙏\nSegunda linha\n".repeat(1000);
        String json = new ObjectMapper().writeValueAsString(new VideoMetadata("Título com acentuação", description));
        try (var audio = fakeExtractor(json).extract(YoutubeUrl.parse("https://youtu.be/dQw4w9WgXcQ"), null, null, ignored -> {})) {
            assertEquals(new VideoMetadata("Título com acentuação", description), audio.video());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\"Título\"}", "{\"title\":\"Título\",\"description\":null}"})
    void defaultsMissingDescriptionToEmpty(String json) throws Exception {
        try (var audio = fakeExtractor(json).extract(YoutubeUrl.parse("https://youtu.be/dQw4w9WgXcQ"), null, null, ignored -> {})) {
            assertEquals(new VideoMetadata("Título", ""), audio.video());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid", "", "null", "{}", "{\"title\":null}", "{\"title\":\"  \"}", "{\"title\":42}"})
    void toleratesUnavailableMetadata(String json) throws Exception {
        Path directory;
        try (var audio = fakeExtractor(json).extract(YoutubeUrl.parse("https://youtu.be/dQw4w9WgXcQ"), null, null, ignored -> {})) {
            directory = audio.directory();
            assertNull(audio.video());
            assertTrue(Files.size(audio.file()) > 0);
        }
        assertFalse(Files.exists(directory));
    }

    @Test void cleansMetadataWhenExtractionFails() throws Exception {
        var extractor = fakeExtractor("{\"title\":\"Título\"}");
        var delegate = extractor.processes;
        var directories = new ArrayList<Path>();
        extractor.processes = new ProcessRunner() {
            @Override public String run(List<String> command) throws java.io.IOException, InterruptedException {
                if (command.getFirst().equals("fake-yt-dlp")) {
                    directories.add(Path.of(command.get(command.indexOf("-o") + 1)).getParent());
                }
                if (command.getFirst().equals("fake-ffmpeg")) throw new java.io.IOException("Extraction failed");
                return delegate.run(command);
            }
        };
        assertThrows(java.io.IOException.class, () -> extractor.extract(
                YoutubeUrl.parse("https://youtu.be/dQw4w9WgXcQ"), null, null, ignored -> {}));
        assertEquals(1, directories.size());
        assertFalse(Files.exists(directories.getFirst()));
    }

    private YoutubeAudioExtractor fakeExtractor(String json) {
        var extractor = new YoutubeAudioExtractor();
        extractor.mapper = new ObjectMapper();
        extractor.downloader = "fake-yt-dlp";
        extractor.ffmpeg = "fake-ffmpeg";
        extractor.ffprobe = "fake-ffprobe";
        extractor.maxClipSeconds = 5400;
        extractor.maxDownloadBytes = 1_000_000;
        extractor.processes = new ProcessRunner() {
            @Override public String run(List<String> command) throws java.io.IOException {
                switch (command.getFirst()) {
                    case "fake-yt-dlp" -> {
                        Path directory = Path.of(command.get(command.indexOf("-o") + 1)).getParent();
                        if (json != null) Files.writeString(directory.resolve("source.info.json"), json);
                        Files.writeString(directory.resolve("source.wav.part"), "partial");
                        Files.writeString(directory.resolve("source.wav"), "media");
                    }
                    case "fake-ffprobe" -> {
                        assertEquals("source.wav", Path.of(command.getLast()).getFileName().toString());
                        return "3";
                    }
                    case "fake-ffmpeg" -> Files.writeString(Path.of(command.getLast()), "audio");
                    default -> throw new AssertionError("Unexpected command: " + command);
                }
                return "";
            }
        };
        return extractor;
    }
}
