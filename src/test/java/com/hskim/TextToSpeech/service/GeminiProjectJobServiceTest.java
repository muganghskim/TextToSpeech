package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.Project4JobStatus;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiProjectJobServiceTest {

    @Test
    void returnsJobIdImmediatelyAndMakesResultAvailableByStatusUrl() throws Exception {
        GeminiProjectTtsService synthesis = mock(GeminiProjectTtsService.class);
        when(synthesis.workload(any())).thenReturn(new ChirpProjectTtsService.Workload(1, 1));
        VideoProjectTtsResult result = mock(VideoProjectTtsResult.class);
        when(synthesis.synthesize(any(), any())).thenAnswer(call -> {
            ChirpProjectTtsService.ProgressListener listener = call.getArgument(1);
            listener.onProgress(new ChirpProjectTtsService.Progress(
                    "COMPLETED", "scene-01", 1, 1, 1, 1, 1, 1));
            return result;
        });
        GeminiProjectJobService jobs = new GeminiProjectJobService(synthesis);
        try {
            Project4JobStatus accepted = jobs.submit(JsonMapper.builder().build().createObjectNode());
            assertThat(accepted.jobId()).isNotBlank();
            assertThat(accepted.statusUrl()).isEqualTo("/text/synthesize-project4/jobs/" + accepted.jobId());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Project4JobStatus status;
            do {
                status = jobs.status(accepted.jobId());
                if (status.status().equals("COMPLETED")) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertThat(status.status()).isEqualTo("COMPLETED");
            assertThat(status.progressPercent()).isEqualTo(100);
            assertThat(status.result()).isSameAs(result);
        } finally {
            jobs.close();
        }
    }
}
