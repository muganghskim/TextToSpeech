package com.hskim.TextToSpeech.model;

public record SceneAudioResult(
        String sceneId,
        int order,
        String audioFile,
        String audioUrl,
        double startSec,
        double endSec,
        double actualDurationSec,
        int startFrame,
        int endFrameExclusive,
        int durationInFrames,
        double differenceFromEstimateSec,
        String timingSource,
        String reviewStatus) {
}
