package br.com.sermonsummarizer.transcription;

import org.junit.jupiter.api.Test;

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
                    Files.copy(wav, Path.of(pattern.replace("%(ext)s", "wav")));
                    return "";
                }
                return super.run(command);
            }
        };
        processes.timeoutSeconds = 30;
        var extractor = new YoutubeAudioExtractor();
        extractor.processes = processes;
        extractor.downloader = "fake-yt-dlp";
        extractor.ffmpeg = "ffmpeg";
        extractor.ffprobe = "ffprobe";
        extractor.maxClipSeconds = 5400;
        extractor.maxDownloadBytes = 1_000_000;
        var states = new ArrayList<String>();
        Path audio;
        try (var result = extractor.extract(YoutubeUrl.parse("https://youtu.be/dQw4w9WgXcQ"), 1L, 2L, states::add)) {
            audio = result.file();
            assertTrue(Files.size(audio) > 0);
            double actualDuration = Double.parseDouble(processes.run(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", audio.toString())).trim());
            assertTrue(actualDuration > 0.9 && actualDuration < 1.2, "O áudio deve conter apenas o trecho pedido.");
            assertEquals(1, result.offsetSeconds());
            assertEquals(List.of("DOWNLOADING", "EXTRACTING"), states);
        } finally {
            Files.deleteIfExists(wav);
        }
        assertFalse(Files.exists(audio));
    }
}
