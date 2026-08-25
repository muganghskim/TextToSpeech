package com.hskim.TextToSpeech.service;

import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1beta1.Timepoint;
import com.google.protobuf.ByteString;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoProjectTtsServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    void createsSceneAudioAndUpdatesADeepCopyWithActualTimings() throws Exception {
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(synthesizer.synthesize(any()))
                .thenReturn(response("audio-1", 2.0))
                .thenReturn(response("audio-2", 3.0));
        SrtWriter srtWriter = new SrtWriter();
        TtsService ttsService = new TtsService(
                synthesizer,
                new SubtitleSegmenter(),
                srtWriter,
                outputDirectory.toString(),
                "en-US-Neural2-F");
        ObjectMapper objectMapper = JsonMapper.builder().build();
        VideoProjectTtsService service = new VideoProjectTtsService(
                ttsService, srtWriter, objectMapper);

        ObjectNode input = (ObjectNode) objectMapper.readTree("""
                {
                  "customMetadata": {"preserveMe": true},
                  "project": {"id": "project-id", "language": "en-GB", "fps": 30},
                  "videoStrategy": {"targetLengthSec": 10},
                  "tts": {"languageCode": "en-US", "speakingRate": 1.02},
                  "scenes": [
                    {"id": "scene-2", "order": 2, "estimatedDurationSec": 5.0,
                     "narration": "Second scene."},
                    {"id": "scene-1", "order": 1, "estimatedDurationSec": 2.1,
                     "narration": "First scene."}
                  ],
                  "youtube": {"chapters": [
                    {"sceneId": "scene-2", "title": "Second chapter"}
                  ]}
                }
                """);

        VideoProjectTtsResult result = service.synthesize(input);

        assertThat(result.projectId()).isEqualTo("project-id");
        assertThat(result.actualTotalDurationSec()).isEqualTo(5.0);
        assertThat(result.totalFrames()).isEqualTo(150);
        assertThat(result.scenes()).extracting(scene -> scene.sceneId())
                .containsExactly("scene-1", "scene-2");
        assertThat(result.scenes().get(0).reviewStatus()).isEqualTo("OK");
        assertThat(result.scenes().get(1).reviewStatus()).isEqualTo("REVIEW");
        assertThat(result.scenes().get(1).startFrame()).isEqualTo(60);
        assertThat(result.scenes().get(1).durationInFrames()).isEqualTo(90);

        assertThat(Files.readString(Path.of(result.audioFile()), StandardCharsets.UTF_8))
                .isEqualTo("audio-1audio-2");
        assertThat(Files.readString(Path.of(result.subtitleFile())))
                .contains("00:00:00,000 --> 00:00:02,000")
                .contains("00:00:02,000 --> 00:00:05,000");
        assertThat(result.scenes()).allSatisfy(scene ->
                assertThat(Files.isRegularFile(Path.of(scene.audioFile()))).isTrue());

        JsonNode timed = objectMapper.readTree(Path.of(result.timedProjectFile()).toFile());
        assertThat(timed.path("customMetadata").path("preserveMe").booleanValue()).isTrue();
        assertThat(timed.path("scenes").get(0).path("id").asString()).isEqualTo("scene-1");
        assertThat(timed.path("scenes").get(1).path("timing").path("startSec").asDouble())
                .isEqualTo(2.0);
        assertThat(timed.path("scenes").get(1).path("timing").path("reviewStatus").asString())
                .isEqualTo("REVIEW");
        assertThat(timed.path("audioTiming").path("reviewSceneCount").asInt()).isEqualTo(1);
        assertThat(timed.path("youtube").path("chapters").get(0).path("timestamp").asString())
                .isEqualTo("0:02");
        assertThat(input.path("scenes").get(0).has("timing")).isFalse();

        ArgumentCaptor<SynthesizeSpeechRequest> requests =
                ArgumentCaptor.forClass(SynthesizeSpeechRequest.class);
        verify(synthesizer, times(2)).synthesize(requests.capture());
        assertThat(requests.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.getVoice().getLanguageCode()).isEqualTo("en-US");
                    assertThat(request.getVoice().getName()).isEqualTo("en-US-Neural2-F");
                    assertThat(request.getAudioConfig().getSpeakingRate()).isEqualTo(1.02);
                });
    }

    private SynthesizeSpeechResponse response(String audio, double durationSeconds) {
        return SynthesizeSpeechResponse.newBuilder()
                .setAudioContent(ByteString.copyFromUtf8(audio))
                .addTimepoints(Timepoint.newBuilder()
                        .setMarkName("cue-end")
                        .setTimeSeconds(durationSeconds))
                .build();
    }
}
