package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.Project3JobStatus;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChirpProjectJobServiceTest {

    @Test
    void returnsImmediatelyAndExposesProgressAndOutput() throws Exception {
        ChirpProjectTtsService synthesis = mock(ChirpProjectTtsService.class);
        when(synthesis.workload(any())).thenReturn(new ChirpProjectTtsService.Workload(1, 2));
        CountDownLatch firstSegment = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VideoProjectTtsResult result = mock(VideoProjectTtsResult.class);
        when(synthesis.synthesize(any(), any())).thenAnswer(call -> {
            ChirpProjectTtsService.ProgressListener listener = call.getArgument(1);
            listener.onProgress(new ChirpProjectTtsService.Progress(
                    "COMPLETED", "scene-01", 1, 1, 1, 2, 1, 2));
            firstSegment.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test timeout");
            }
            listener.onProgress(new ChirpProjectTtsService.Progress(
                    "COMPLETED", "scene-01", 1, 1, 2, 2, 2, 2));
            return result;
        });

        ChirpProjectJobService jobs = new ChirpProjectJobService(synthesis);
        try {
            ObjectNode input = JsonMapper.builder().build().createObjectNode();
            Project3JobStatus accepted = jobs.submit(input);
            assertThat(accepted.jobId()).isNotBlank();
            assertThat(accepted.statusUrl()).endsWith(accepted.jobId());
            assertThat(firstSegment.await(5, TimeUnit.SECONDS)).isTrue();
            Project3JobStatus running = jobs.status(accepted.jobId());
            assertThat(running.status()).isEqualTo("RUNNING");
            assertThat(running.completedSegments()).isEqualTo(1);
            assertThat(running.progressPercent()).isEqualTo(50);
            assertThat(running.currentSceneId()).isEqualTo("scene-01");
            release.countDown();
            Project3JobStatus completed = awaitStatus(jobs, accepted.jobId(), "COMPLETED");
            assertThat(completed.progressPercent()).isEqualTo(100);
            assertThat(completed.result()).isSameAs(result);
            assertThat(completed.finishedAt()).isNotNull();
        } finally {
            release.countDown();
            jobs.close();
        }
    }

    @Test
    void reportsFailureWithoutLeavingJobRunning() throws Exception {
        ChirpProjectTtsService synthesis = mock(ChirpProjectTtsService.class);
        when(synthesis.workload(any())).thenReturn(new ChirpProjectTtsService.Workload(1, 1));
        when(synthesis.synthesize(any(), any())).thenThrow(new IOException("Google unavailable"));
        ChirpProjectJobService jobs = new ChirpProjectJobService(synthesis);
        try {
            Project3JobStatus accepted = jobs.submit(JsonMapper.builder().build().createObjectNode());
            Project3JobStatus failed = awaitStatus(jobs, accepted.jobId(), "FAILED");
            assertThat(failed.error()).contains("server log");
            assertThat(failed.result()).isNull();
            assertThat(failed.finishedAt()).isNotNull();
        } finally {
            jobs.close();
        }
    }

    private static Project3JobStatus awaitStatus(ChirpProjectJobService jobs, String id, String state)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Project3JobStatus status = jobs.status(id);
            if (status.status().equals(state)) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Job never reached " + state);
    }
}
