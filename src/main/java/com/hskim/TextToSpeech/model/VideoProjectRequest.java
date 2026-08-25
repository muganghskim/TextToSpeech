package com.hskim.TextToSpeech.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoProjectRequest(
        Project project,
        TtsSettings tts,
        List<Scene> scenes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(String id, String language) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TtsSettings(
            String languageCode,
            String voiceName,
            Double speakingRate,
            Double pitch) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scene(String id, Integer order, String narration) {
    }
}
