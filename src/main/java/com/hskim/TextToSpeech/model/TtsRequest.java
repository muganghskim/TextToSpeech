package com.hskim.TextToSpeech.model;

import java.util.List;

public record TtsRequest(
        String text,
        String languageCode,
        String voiceName,
        Double speakingRate,
        Double pitch,
        Double sentencePauseMsMin,
        Double sentencePauseMsMax,
        List<Double> sentencePauseMs) {

    public TtsRequest {
        sentencePauseMs = sentencePauseMs == null ? List.of() : List.copyOf(sentencePauseMs);
    }

    public TtsRequest(
            String text,
            String languageCode,
            String voiceName,
            Double speakingRate,
            Double pitch) {
        this(text, languageCode, voiceName, speakingRate, pitch, null, null, null);
    }

    public TtsRequest(
            String text,
            String languageCode,
            String voiceName,
            Double speakingRate,
            Double pitch,
            Double sentencePauseMsMin,
            Double sentencePauseMsMax) {
        this(text, languageCode, voiceName, speakingRate, pitch,
                sentencePauseMsMin, sentencePauseMsMax, null);
    }

    public String effectiveLanguageCode() {
        return languageCode == null || languageCode.isBlank() ? "en-US" : languageCode.strip();
    }

    public double effectiveSpeakingRate() {
        return speakingRate == null ? 1.0 : speakingRate;
    }

    public double effectivePitch() {
        return pitch == null ? 0.0 : pitch;
    }

    public double effectiveSentencePauseMsMin() {
        return sentencePauseMsMin == null ? 0.0 : sentencePauseMsMin;
    }

    public double effectiveSentencePauseMsMax() {
        return sentencePauseMsMax == null ? effectiveSentencePauseMsMin() : sentencePauseMsMax;
    }

    public double effectiveSentencePauseMs() {
        return (effectiveSentencePauseMsMin() + effectiveSentencePauseMsMax()) / 2.0;
    }

    public double effectiveSentencePauseMs(int sentenceIndex) {
        if (sentenceIndex >= 0 && sentenceIndex < sentencePauseMs.size()) {
            return sentencePauseMs.get(sentenceIndex);
        }
        return effectiveSentencePauseMs();
    }
}
