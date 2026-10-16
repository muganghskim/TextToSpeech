package com.hskim.TextToSpeech.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiTtsClientTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void buildsOfficialInteractionsTtsRequestWithoutPuttingStyleInTranscript() {
        JsonNode body = GeminiTtsClient.requestBody(mapper, "안녕하세요.", "Kore", "calm horror storytelling");

        assertThat(body.path("model").asString()).isEqualTo("gemini-3.8-flash-tts");
        assertThat(body.path("input").get(0).path("content").get(0).path("text").asString())
                .isEqualTo("안녕하세요.");
        assertThat(body.path("input").get(0).path("content").get(0)
                .path("annotations").get(0).path("style").asString())
                .isEqualTo("calm horror storytelling");
        assertThat(body.path("response_format").path("mime_type").asString()).isEqualTo("audio/l16");
        assertThat(body.path("generation_config").path("speech_config").get(0)
                .path("voice").asString()).isEqualTo("Kore");
    }

    @Test
    void decodesAudioFromModelOutputStep() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 0, 2, 0});
        JsonNode response = mapper.readTree("""
                {"steps":[{"type":"model_output","content":[{"type":"audio","data":"%s"}]}]}
                """.formatted(encoded));

        assertThat(GeminiTtsClient.decodeAudio(response)).containsExactly(1, 0, 2, 0);
        assertThatThrownBy(() -> GeminiTtsClient.decodeAudio(mapper.readTree("{}")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("did not contain audio");
    }

    @Test
    void buildsOneMultiSpeakerRequestForManyTurns() {
        JsonNode body = GeminiTtsClient.dialogueRequestBody(mapper, List.of(
                new GeminiTtsClient.Turn("female", "딸의 말.", "calm"),
                new GeminiTtsClient.Turn("male", "아버지의 말.", "low and tense"),
                new GeminiTtsClient.Turn("female", "엄마의 말.", "calm")), "Orus", "Kore");

        assertThat(body.path("input").get(0).path("content").size()).isEqualTo(3);
        assertThat(body.path("input").get(0).path("content").get(1)
                .path("annotations").get(0).path("speaker").asString()).isEqualTo("male");
        assertThat(body.path("generation_config").path("speech_config")
                .path("speakers").size()).isEqualTo(2);
        assertThat(body.path("generation_config").path("speech_config")
                .path("speakers").get(0).path("voice").asString()).isEqualTo("Orus");
    }
}
