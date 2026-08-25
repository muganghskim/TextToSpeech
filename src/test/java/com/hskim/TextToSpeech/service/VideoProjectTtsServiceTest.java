package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.TtsResult;
import com.hskim.TextToSpeech.model.VideoProjectRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoProjectTtsServiceTest {

    @Test
    void combinesNarrationInSceneOrderAndAppliesProjectTtsSettings() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        TtsResult expected = new TtsResult("id", "audio", "srt", "/audio", "/srt", 2);
        when(ttsService.convertTextToAudio(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        VideoProjectTtsService service = new VideoProjectTtsService(ttsService);

        VideoProjectRequest request = new VideoProjectRequest(
                new VideoProjectRequest.Project("project-id", "en-GB"),
                new VideoProjectRequest.TtsSettings("en-US", null, 1.02, null),
                List.of(
                        new VideoProjectRequest.Scene("scene-2", 2, "Second scene."),
                        new VideoProjectRequest.Scene("scene-empty", 3, "  "),
                        new VideoProjectRequest.Scene("scene-1", 1, " First scene. ")));

        assertThat(service.synthesize(request)).isSameAs(expected);

        ArgumentCaptor<TtsRequest> captor = ArgumentCaptor.forClass(TtsRequest.class);
        verify(ttsService).convertTextToAudio(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new TtsRequest(
                "First scene.\n\nSecond scene.", "en-US", null, 1.02, null));
    }
}
