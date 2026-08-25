package com.hskim.TextToSpeech.model;

import java.util.List;

public record VideoProjectTtsResult(
        String id,
        String projectId,
        String audioFile,
        String subtitleFile,
        String timedProjectFile,
        String audioUrl,
        String subtitleUrl,
        String timedProjectUrl,
        double actualTotalDurationSec,
        int fps,
        int totalFrames,
        int cueCount,
        List<SceneAudioResult> scenes) {
}
