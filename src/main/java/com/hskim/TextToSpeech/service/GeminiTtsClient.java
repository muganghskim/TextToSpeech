package com.hskim.TextToSpeech.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
public class GeminiTtsClient {

    static final String MODEL = "gemini-3.8-flash-tts";
    private static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions");

    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GeminiTtsClient(@Value("${GEMINI_API_KEY:}") String apiKey, ObjectMapper mapper) {
        this.apiKey = apiKey.strip();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    public byte[] synthesize(String transcript, String voice, String style) throws IOException {
        return send(requestBody(mapper, transcript, voice, style));
    }

    public byte[] synthesizeDialogue(List<Turn> turns, String maleVoice, String femaleVoice) throws IOException {
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("Gemini dialogue requires at least one turn.");
        }
        return send(dialogueRequestBody(mapper, turns, maleVoice, femaleVoice));
    }

    private byte[] send(ObjectNode body) throws IOException {
        if (!configured()) {
            throw new GeminiConfigurationException("GEMINI_API_KEY is not set. Restart the server after setting it.");
        }
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(10))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Gemini TTS request was interrupted.", failure);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Gemini TTS returned HTTP " + response.statusCode()
                    + (response.statusCode() == 429 ? " (free-tier rate limit or quota exceeded)." : "."));
        }
        return decodeAudio(mapper.readTree(response.body()));
    }

    static ObjectNode requestBody(ObjectMapper mapper, String transcript, String voice, String style) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        ArrayNode input = body.putArray("input");
        ObjectNode turn = input.addObject();
        turn.put("type", "user_input");
        ObjectNode content = turn.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", transcript);
        if (style != null && !style.isBlank()) {
            ObjectNode annotation = content.putArray("annotations").addObject();
            annotation.put("type", "speech_metadata");
            annotation.put("style", style);
        }
        ObjectNode format = body.putObject("response_format");
        format.put("type", "audio");
        format.put("mime_type", "audio/l16");
        format.put("sample_rate", 24_000);
        body.putObject("generation_config").putArray("speech_config")
                .addObject().put("voice", voice);
        return body;
    }

    static ObjectNode dialogueRequestBody(ObjectMapper mapper, List<Turn> turns,
                                          String maleVoice, String femaleVoice) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        ObjectNode input = body.putArray("input").addObject();
        input.put("type", "user_input");
        ArrayNode content = input.putArray("content");
        for (Turn turn : turns) {
            if (!turn.speaker().equals("male") && !turn.speaker().equals("female")) {
                throw new IllegalArgumentException("Gemini speaker must be male or female.");
            }
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", turn.text());
            ObjectNode annotation = item.putArray("annotations").addObject();
            annotation.put("type", "speech_metadata");
            annotation.put("speaker", turn.speaker());
            if (turn.style() != null && !turn.style().isBlank()) {
                annotation.put("style", turn.style());
            }
        }
        ObjectNode format = body.putObject("response_format");
        format.put("type", "audio");
        format.put("mime_type", "audio/l16");
        format.put("sample_rate", 24_000);
        ObjectNode speech = body.putObject("generation_config").putObject("speech_config");
        speech.put("mode", "conversational");
        ArrayNode speakers = speech.putArray("speakers");
        speakers.addObject().put("speaker", "male").put("voice", maleVoice);
        speakers.addObject().put("speaker", "female").put("voice", femaleVoice);
        return body;
    }

    public record Turn(String speaker, String text, String style) { }

    static byte[] decodeAudio(JsonNode response) throws IOException {
        JsonNode steps = response.path("steps");
        byte[] lastAudio = null;
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                if (!"model_output".equals(step.path("type").asString())) {
                    continue;
                }
                JsonNode contents = step.path("content");
                if (!contents.isArray()) {
                    continue;
                }
                for (JsonNode content : contents) {
                    if (!"audio".equals(content.path("type").asString())) {
                        continue;
                    }
                    String encoded = content.path("data").asString();
                    try {
                        byte[] audio = Base64.getDecoder().decode(encoded);
                        if (audio.length >= 4 && audio[0] == 'R' && audio[1] == 'I'
                                && audio[2] == 'F' && audio[3] == 'F') {
                            audio = PcmWav.read(audio).pcm();
                        }
                        if (audio.length == 0 || (audio.length & 1) != 0) {
                            throw new IOException("Gemini TTS returned invalid 24 kHz PCM16 audio.");
                        }
                        lastAudio = audio;
                    } catch (IllegalArgumentException failure) {
                        throw new IOException("Gemini TTS returned invalid base64 audio.", failure);
                    }
                }
            }
        }
        if (lastAudio == null) {
            throw new IOException("Gemini TTS response did not contain audio.");
        }
        return lastAudio;
    }
}
