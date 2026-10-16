package com.hskim.TextToSpeech.model;

import java.time.Instant;

public record Project4JobStatus(
        String jobId,
        String status,
        String statusUrl,
        int totalScenes,
        int totalSegments,
        int completedSegments,
        int progressPercent,
        String currentSceneId,
        int currentSceneNumber,
        int currentSegmentNumber,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        VideoProjectTtsResult result,
        String error) {
}
