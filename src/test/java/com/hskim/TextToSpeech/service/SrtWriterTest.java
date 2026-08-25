package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.SubtitleCue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SrtWriterTest {

    @Test
    void writesStandardSrtTimestamps() {
        String srt = new SrtWriter().write(List.of(
                new SubtitleCue(1, "Hello.", 0.0, 1.234),
                new SubtitleCue(2, "Welcome.", 1.234, 65.007)));

        assertThat(srt).isEqualTo("""
                1
                00:00:00,000 --> 00:00:01,234
                Hello.

                2
                00:00:01,234 --> 00:01:05,007
                Welcome.

                """);
    }
}
