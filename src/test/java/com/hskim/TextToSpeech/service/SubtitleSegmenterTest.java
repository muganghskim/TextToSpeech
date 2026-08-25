package com.hskim.TextToSpeech.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleSegmenterTest {

    private final SubtitleSegmenter segmenter = new SubtitleSegmenter();

    @Test
    void segmentsEnglishSentencesAndSkipsBlankLines() {
        List<String> cues = segmenter.segment(
                "This is the first sentence. This is the second sentence!\n\nThis is the last line.",
                "en-US");

        assertThat(cues).containsExactly(
                "This is the first sentence.",
                "This is the second sentence!",
                "This is the last line.");
    }

    @Test
    void wrapsLongCaptions() {
        String text = "This sentence is much longer than the recommended subtitle line length and should be wrapped into multiple readable subtitle cues.";

        assertThat(segmenter.segment(text, "en-US"))
                .hasSizeGreaterThan(1)
                .allSatisfy(cue -> assertThat(cue.length())
                        .isLessThanOrEqualTo(SubtitleSegmenter.MAX_CUE_LENGTH));
    }
}
