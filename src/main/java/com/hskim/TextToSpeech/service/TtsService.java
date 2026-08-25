package com.hskim.TextToSpeech.service;

import com.google.cloud.texttospeech.v1beta1.AudioConfig;
import com.google.cloud.texttospeech.v1beta1.AudioEncoding;
import com.google.cloud.texttospeech.v1beta1.SynthesisInput;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1beta1.Timepoint;
import com.google.cloud.texttospeech.v1beta1.VoiceSelectionParams;
import com.hskim.TextToSpeech.model.SubtitleCue;
import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.TtsResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TtsService {

    private static final int GOOGLE_TTS_INPUT_LIMIT = 5_000;
    private static final Pattern SAFE_FILENAME = Pattern.compile("[0-9a-f-]+\\.(mp3|srt)");

    private final SpeechSynthesizer speechSynthesizer;
    private final SubtitleSegmenter subtitleSegmenter;
    private final SrtWriter srtWriter;
    private final Path outputDirectory;
    private final String defaultEnglishVoice;

    public TtsService(
            SpeechSynthesizer speechSynthesizer,
            SubtitleSegmenter subtitleSegmenter,
            SrtWriter srtWriter,
            @Value("${tts.output-directory:output}") String outputDirectory,
            @Value("${tts.default-english-voice:en-US-Neural2-F}") String defaultEnglishVoice) {
        this.speechSynthesizer = speechSynthesizer;
        this.subtitleSegmenter = subtitleSegmenter;
        this.srtWriter = srtWriter;
        this.outputDirectory = Path.of(outputDirectory).toAbsolutePath().normalize();
        this.defaultEnglishVoice = defaultEnglishVoice.strip();
    }

    public TtsResult convertTextToAudio(TtsRequest request) throws IOException {
        validate(request);

        String languageCode = request.effectiveLanguageCode();
        List<String> cueTexts = subtitleSegmenter.segment(request.text(), languageCode);
        String ssml = buildSsml(cueTexts);
        if (ssml.getBytes(StandardCharsets.UTF_8).length > GOOGLE_TTS_INPUT_LIMIT) {
            throw new IllegalArgumentException(
                    "The SSML input exceeds Google TTS's 5,000-byte request limit.");
        }

        SynthesizeSpeechResponse response = speechSynthesizer.synthesize(buildRequest(request, languageCode, ssml));
        List<SubtitleCue> cues = createTimedCues(
                cueTexts, response.getTimepointsList(), request.effectiveSpeakingRate());

        Files.createDirectories(outputDirectory);
        String id = UUID.randomUUID().toString();
        Path audioFile = outputDirectory.resolve(id + ".mp3");
        Path subtitleFile = outputDirectory.resolve(id + ".srt");
        Files.write(audioFile, response.getAudioContent().toByteArray());
        try {
            Files.writeString(subtitleFile, srtWriter.write(cues), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Files.deleteIfExists(audioFile);
            throw exception;
        }

        return new TtsResult(
                id,
                audioFile.toString(),
                subtitleFile.toString(),
                "/text/files/" + audioFile.getFileName(),
                "/text/files/" + subtitleFile.getFileName(),
                cues.size());
    }

    public Path resolveOutputFile(String filename) throws IOException {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Invalid output filename.");
        }
        Path file = outputDirectory.resolve(filename).normalize();
        if (!file.startsWith(outputDirectory) || !Files.isRegularFile(file)) {
            throw new FileNotFoundException("The requested output file was not found.");
        }
        return file;
    }

    private void validate(TtsRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text must not be blank.");
        }
        double rate = request.effectiveSpeakingRate();
        if (rate < 0.25 || rate > 2.0) {
            throw new IllegalArgumentException("speakingRate must be between 0.25 and 2.0.");
        }
        double pitch = request.effectivePitch();
        if (pitch < -20.0 || pitch > 20.0) {
            throw new IllegalArgumentException("pitch must be between -20.0 and 20.0.");
        }
    }

    private SynthesizeSpeechRequest buildRequest(TtsRequest request, String languageCode, String ssml) {
        VoiceSelectionParams.Builder voice = VoiceSelectionParams.newBuilder()
                .setLanguageCode(languageCode);
        if (request.voiceName() != null && !request.voiceName().isBlank()) {
            voice.setName(request.voiceName().strip());
        } else if (languageCode.equalsIgnoreCase("en-US") && !defaultEnglishVoice.isBlank()) {
            voice.setName(defaultEnglishVoice);
        }

        return SynthesizeSpeechRequest.newBuilder()
                .setInput(SynthesisInput.newBuilder().setSsml(ssml).build())
                .setVoice(voice.build())
                .setAudioConfig(AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.MP3)
                        .setSpeakingRate(request.effectiveSpeakingRate())
                        .setPitch(request.effectivePitch())
                        .build())
                .addEnableTimePointing(SynthesizeSpeechRequest.TimepointType.SSML_MARK)
                .build();
    }

    private String buildSsml(List<String> cueTexts) {
        StringBuilder ssml = new StringBuilder("<speak>");
        for (int index = 0; index < cueTexts.size(); index++) {
            if (index > 0) {
                ssml.append("<mark name=\"cue-").append(index + 1).append("\"/>");
            }
            ssml.append(escapeXml(cueTexts.get(index))).append(' ');
        }
        // The tiny trailing break ensures audio exists after the final timing mark.
        ssml.append("<mark name=\"cue-end\"/><break time=\"1ms\"/></speak>");
        return ssml.toString();
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private List<SubtitleCue> createTimedCues(
            List<String> texts,
            List<Timepoint> timepoints,
            double speakingRate) {
        Map<String, Double> offsets = new HashMap<>();
        for (Timepoint timepoint : timepoints) {
            offsets.put(timepoint.getMarkName(), timepoint.getTimeSeconds());
        }

        double estimatedTotal = estimateDuration(texts, speakingRate);
        double total = offsets.getOrDefault("cue-end", estimatedTotal);
        if (total <= 0.0) {
            total = estimatedTotal;
        }

        int totalCharacters = texts.stream().mapToInt(String::length).sum();
        int elapsedCharacters = 0;
        double[] starts = new double[texts.size() + 1];
        starts[0] = 0.0;
        for (int index = 1; index < texts.size(); index++) {
            elapsedCharacters += texts.get(index - 1).length();
            double fallback = total * elapsedCharacters / Math.max(1, totalCharacters);
            starts[index] = offsets.getOrDefault("cue-" + (index + 1), fallback);
            starts[index] = Math.max(starts[index - 1], Math.min(starts[index], total));
        }
        starts[texts.size()] = Math.max(starts[texts.size() - 1], total);

        List<SubtitleCue> cues = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            cues.add(new SubtitleCue(index + 1, texts.get(index), starts[index], starts[index + 1]));
        }
        return List.copyOf(cues);
    }

    private double estimateDuration(List<String> texts, double speakingRate) {
        int characters = texts.stream().mapToInt(String::length).sum();
        return Math.max(0.8, characters / (6.0 * speakingRate));
    }
}
