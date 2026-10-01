package br.com.sermonsummarizer.transcription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YoutubeUrlTest {
    @Test void acceptsKnownVideoForms() {
        for (String url : new String[] {
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30",
                "https://youtu.be/dQw4w9WgXcQ",
                "https://youtube.com/shorts/dQw4w9WgXcQ",
                "https://m.youtube.com/live/dQw4w9WgXcQ"
        }) {
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YoutubeUrl.parse(url).canonicalUrl());
        }
    }

    @Test void rejectsOtherHostsAndMalformedIds() {
        for (String url : new String[] {
                "https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ",
                "http://youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=bad",
                "https://www.youtube.com/playlist?list=abc"
        }) assertThrows(IllegalArgumentException.class, () -> YoutubeUrl.parse(url));
    }
}
