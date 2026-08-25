package com.hskim.TextToSpeech.model;

public record TtsRequest(
        String text,
        String languageCode,
        String voiceName,
        Double speakingRate,
        Double pitch) {

    public String effectiveLanguageCode() {
        return languageCode == null || languageCode.isBlank() ? "en-US" : languageCode.strip();
    }

    public double effectiveSpeakingRate() {
        return speakingRate == null ? 1.0 : speakingRate;
    }

    public double effectivePitch() {
        return pitch == null ? 0.0 : pitch;
    }
}
