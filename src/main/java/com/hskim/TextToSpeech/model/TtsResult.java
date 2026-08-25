package com.hskim.TextToSpeech.model;

public record TtsResult(
        String id,
        String audioFile,
        String subtitleFile,
        String audioUrl,
        String subtitleUrl,
        int cueCount) {
}
