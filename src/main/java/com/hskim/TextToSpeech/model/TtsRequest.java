package com.hskim.TextToSpeech.model;

public record TtsRequest(
        String text,
        String languageCode,
        String voiceName,
        Double speakingRate,
        Double pitch,
        Double sentencePauseMsMin,
        Double sentencePauseMsMax) {

    public TtsRequest(
            String text,
            String languageCode,
            String voiceName,
            Double speakingRate,
            Double pitch) {
        this(text, languageCode, voiceName, speakingRate, pitch, null, null);
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
}
