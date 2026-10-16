package com.hskim.TextToSpeech.service;

import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.protobuf.ByteString;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChirpProjectTtsServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    void usesVoiceMapAndMeasuredWavLengthsForScenesAndSubtitles() throws Exception {
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        byte[] oneSecond = PcmWav.fromPcm(new byte[48_000]).toBytes();
        when(synthesizer.synthesize(any())).thenReturn(SynthesizeSpeechResponse.newBuilder()
                .setAudioContent(ByteString.copyFrom(oneSecond)).build());
        ObjectMapper mapper = JsonMapper.builder().build();
        SrtWriter srtWriter = new SrtWriter();
        TtsService files = new TtsService(synthesizer, new SubtitleSegmenter(), srtWriter,
                outputDirectory.toString(), "en-US-Neural2-F");
        ChirpProjectTtsService service = new ChirpProjectTtsService(
                synthesizer, new SubtitleSegmenter(), srtWriter, files, mapper);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {
                  "project": {"language": "ko-KR", "fps": 30},
                  "story": {"workingTitle": "Family"},
                  "youtube": {"chapters": [{"sceneId": "scene-02", "title": "Next"}]},
                  "tts": {"provider": "GOOGLE_CHIRP3_HD", "speakingRate": 0.88,
                          "pitch": -3, "sentencePauseMsMin": 250, "sentencePauseMsMax": 400,
                          "voiceMap": {"daughter": "ko-KR-Chirp3-HD-Kore",
                                       "father": "ko-KR-Chirp3-HD-Orus"}},
                  "scenes": [
                    {"id": "scene-02", "order": 2, "narration": "다음 장면.",
                     "ttsSegments": [{"speakerId": "daughter", "type": "NARRATION", "text": "다음 장면."}]},
                    {"id": "scene-01", "order": 1, "narration": "아버지가 말했습니다. 그래.",
                     "sentencePauseMs": [270, 300],
                     "ttsSegments": [
                       {"speakerId": "daughter", "type": "NARRATION", "text": "아버지가 말했습니다."},
                       {"speakerId": "father", "type": "DIALOGUE", "text": "그래."}
                     ]}
                  ]
                }
                """);

        List<ChirpProjectTtsService.Progress> progress = new ArrayList<>();
        VideoProjectTtsResult result = service.synthesize(input, progress::add);

        assertThat(progress).extracting(ChirpProjectTtsService.Progress::phase)
                .containsExactly("STARTED", "COMPLETED", "STARTED", "COMPLETED",
                        "STARTED", "COMPLETED");
        assertThat(progress.get(0).completedSegments()).isZero();
        assertThat(progress.get(5).completedSegments()).isEqualTo(3);
        assertThat(progress.get(5).totalSegments()).isEqualTo(3);

        assertThat(result.audioFile()).endsWith(".wav");
        assertThat(PcmWav.read(Files.readAllBytes(Path.of(result.audioFile()))).durationSeconds())
                .isEqualTo(3.0);
        assertThat(result.scenes()).extracting(scene -> scene.sceneId())
                .containsExactly("scene-01", "scene-02");
        assertThat(result.scenes()).extracting(scene -> scene.actualDurationSec())
                .containsExactly(2.0, 1.0);
        assertThat(Files.readString(Path.of(result.subtitleFile())))
                .contains("00:00:01,000 --> 00:00:02,000")
                .contains("00:00:02,000 --> 00:00:03,000");
        JsonNode timed = mapper.readTree(Path.of(result.timedProjectFile()).toFile());
        assertThat(timed.path("story").path("workingTitle").asString()).isEqualTo("Family");
        assertThat(timed.path("scenes").get(0).path("timing").path("durationSec").asDouble())
                .isEqualTo(2.0);
        assertThat(timed.path("scenes").get(0).path("ttsSegments").get(1)
                .path("timing").path("startSec").asDouble()).isEqualTo(1.0);
        assertThat(timed.path("audioTiming").path("subtitleTimingSource").asString())
                .isEqualTo("ESTIMATED_WITHIN_SEGMENT");
        assertThat(timed.path("youtube").path("chapters").get(0).path("timestamp").asString())
                .isEqualTo("0:02");
        assertThat(input.path("scenes").get(0).path("ttsSegments").get(0).has("voiceName"))
                .isFalse();

        ArgumentCaptor<SynthesizeSpeechRequest> requests =
                ArgumentCaptor.forClass(SynthesizeSpeechRequest.class);
        verify(synthesizer, times(3)).synthesize(requests.capture());
        assertThat(requests.getAllValues()).extracting(request -> request.getVoice().getName())
                .containsExactly("ko-KR-Chirp3-HD-Kore", "ko-KR-Chirp3-HD-Orus",
                        "ko-KR-Chirp3-HD-Kore");
        assertThat(requests.getAllValues().get(0).getInput().getSsml())
                .contains("<prosody pitch=\"-3.0st\">")
                .contains("<break time=\"270ms\"/>");
        assertThat(requests.getAllValues().get(1).getInput().getSsml())
                .contains("<break time=\"300ms\"/>");
    }

    @Test
    void rejectsUnknownSpeakerBeforeBillingAnySynthesis() throws Exception {
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        SrtWriter srtWriter = new SrtWriter();
        TtsService files = new TtsService(synthesizer, new SubtitleSegmenter(), srtWriter,
                outputDirectory.toString(), "en-US-Neural2-F");
        ChirpProjectTtsService service = new ChirpProjectTtsService(
                synthesizer, new SubtitleSegmenter(), srtWriter, files, mapper);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"tts": {"voiceMap": {"daughter": "ko-KR-Chirp3-HD-Kore"}},
                 "scenes": [{"id": "scene-1", "ttsSegments":
                   [{"speakerId": "father", "text": "안녕."}]}]}
                """);

        assertThatThrownBy(() -> service.synthesize(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unmapped speakerId");
        verify(synthesizer, never()).synthesize(any());
    }
}
