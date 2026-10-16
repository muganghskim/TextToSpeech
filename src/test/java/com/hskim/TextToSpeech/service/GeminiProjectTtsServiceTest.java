package com.hskim.TextToSpeech.service;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiProjectTtsServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    void sendsAllThreeCharactersInOneTwoVoiceRequestAndMarksTimingEstimated() throws Exception {
        GeminiTtsClient gemini = mock(GeminiTtsClient.class);
        when(gemini.configured()).thenReturn(true);
        when(gemini.synthesizeDialogue(anyList(), eq("Orus"), eq("Kore")))
                .thenReturn(new byte[24_000 * 2 * 10]);
        ObjectMapper mapper = JsonMapper.builder().build();
        SrtWriter srtWriter = new SrtWriter();
        TtsService files = new TtsService(mock(SpeechSynthesizer.class), new SubtitleSegmenter(),
                srtWriter, outputDirectory.toString(), "en-US-Neural2-F");
        GeminiProjectTtsService service = new GeminiProjectTtsService(
                gemini, new SubtitleSegmenter(), srtWriter, files, mapper);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"project":{"fps":30},
                 "tts":{"languageCode":"ko-KR","voiceStyle":"calm-horror-storytelling",
                        "provider":"GOOGLE_CHIRP3_HD",
                        "voiceMap":{"daughter":"ko-KR-Chirp3-HD-Kore",
                                    "father":"ko-KR-Chirp3-HD-Orus",
                                    "mother":"ko-KR-Chirp3-HD-Gacrux"}},
                 "scenes":[
                    {"id":"scene-02","order":2,"ttsSegments":[
                       {"speakerId":"mother","text":"괜찮니?"}]},
                    {"id":"scene-01","order":1,"ttsSegments":[
                       {"speakerId":"daughter","text":"아버지."},
                       {"speakerId":"father","text":"그래."}]}
                 ]}
                """);

        assertThat(service.workload(input).totalSegments()).isEqualTo(3);
        VideoProjectTtsResult result = service.synthesize(input, progress -> { });

        assertThat(result.actualTotalDurationSec()).isEqualTo(10.0);
        assertThat(result.scenes()).hasSize(2);
        assertThat(result.scenes().get(0).audioFile()).isNull();
        assertThat(result.scenes().get(0).timingSource()).isEqualTo("ESTIMATED_FROM_TOTAL_AUDIO");
        assertThat(Files.exists(Path.of(result.audioFile()))).isTrue();
        assertThat(Files.readString(Path.of(result.subtitleFile()))).contains("아버지.");
        JsonNode timed = mapper.readTree(Path.of(result.timedProjectFile()).toFile());
        assertThat(timed.path("audioTiming").path("apiRequestCount").asInt()).isEqualTo(1);
        assertThat(timed.path("audioTiming").path("reviewRequired").asBoolean()).isTrue();
        assertThat(timed.path("scenes").get(0).path("timing").has("actualDurationSec")).isFalse();
        assertThat(timed.path("scenes").get(0).path("ttsSegments").get(0)
                .path("timing").has("actualDurationSec")).isFalse();
        assertThat(timed.path("tts").path("geminiVoicesResolved").path("female").asString())
                .isEqualTo("Kore");
        assertThat(timed.path("scenes").get(0).path("audio").path("file").isNull()).isTrue();
        assertThat(input.path("tts").path("provider").asString()).isEqualTo("GOOGLE_CHIRP3_HD");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GeminiTtsClient.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(gemini, times(1)).synthesizeDialogue(turns.capture(), eq("Orus"), eq("Kore"));
        assertThat(turns.getValue()).extracting(GeminiTtsClient.Turn::speaker)
                .containsExactly("female", "male", "female");
        assertThat(turns.getValue()).extracting(GeminiTtsClient.Turn::text)
                .containsExactly("아버지.", "그래.", "괜찮니?");
        assertThat(turns.getValue()).extracting(GeminiTtsClient.Turn::style)
                .containsOnly("calm horror storytelling");
    }

    @Test
    void requiresExplicitMappingForUnknownCharacter() throws Exception {
        GeminiTtsClient gemini = mock(GeminiTtsClient.class);
        when(gemini.configured()).thenReturn(true);
        ObjectMapper mapper = JsonMapper.builder().build();
        TtsService files = new TtsService(mock(SpeechSynthesizer.class), new SubtitleSegmenter(),
                new SrtWriter(), outputDirectory.toString(), "en-US-Neural2-F");
        GeminiProjectTtsService service = new GeminiProjectTtsService(
                gemini, new SubtitleSegmenter(), new SrtWriter(), files, mapper);
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"scenes":[{"ttsSegments":[{"speakerId":"narrator","text":"Hello."}]}]}
                """);

        assertThatThrownBy(() -> service.workload(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("geminiSpeakerMap.narrator");
        ((ObjectNode) input.withObject("tts").withObject("geminiSpeakerMap")).put("narrator", "female");
        assertThat(service.workload(input).totalSegments()).isEqualTo(1);
    }
}
