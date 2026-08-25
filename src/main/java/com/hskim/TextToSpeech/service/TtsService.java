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

import java.io.ByteArrayOutputStream;
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
    private static final Pattern SAFE_FILENAME = Pattern.compile(
            "[0-9a-f-]+(?:-scene-[0-9]{3}|-timed)?\\.(mp3|srt|json)");

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
        SynthesizedSpeech synthesis = synthesize(request);

        String id = UUID.randomUUID().toString();
        Path audioFile = writeBinaryOutput(id + ".mp3", synthesis.audio());
        Path subtitleFile;
        try {
            subtitleFile = writeTextOutput(id + ".srt", srtWriter.write(synthesis.cues()));
        } catch (IOException exception) {
            Files.deleteIfExists(audioFile);
            throw exception;
        }

        return new TtsResult(
                id,
                audioFile.toString(),
                subtitleFile.toString(),
                outputUrl(audioFile),
                outputUrl(subtitleFile),
                synthesis.cues().size());
    }

    SynthesizedSpeech synthesize(TtsRequest request) throws IOException {
        validate(request);

        String languageCode = request.effectiveLanguageCode();
        List<String> cueTexts = subtitleSegmenter.segment(request.text(), languageCode);
        List<List<String>> cueBatches = partitionCues(cueTexts);
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        List<SubtitleCue> cues = new ArrayList<>();
        double timelineOffset = 0.0;
        boolean exactTiming = true;

        for (List<String> cueBatch : cueBatches) {
            String ssml = buildSsml(cueBatch);
            SynthesizeSpeechResponse response = speechSynthesizer.synthesize(
                    buildRequest(request, languageCode, ssml));
            audio.writeBytes(response.getAudioContent().toByteArray());
            exactTiming &= response.getTimepointsList().stream()
                    .anyMatch(timepoint -> "cue-end".equals(timepoint.getMarkName()));

            List<SubtitleCue> batchCues = createTimedCues(
                    cueBatch, response.getTimepointsList(), request.effectiveSpeakingRate());
            for (SubtitleCue cue : batchCues) {
                cues.add(new SubtitleCue(
                        cues.size() + 1,
                        cue.text(),
                        cue.startSeconds() + timelineOffset,
                        cue.endSeconds() + timelineOffset));
            }
            timelineOffset = cues.get(cues.size() - 1).endSeconds();
        }

        return new SynthesizedSpeech(
                audio.toByteArray(), List.copyOf(cues), timelineOffset, exactTiming);
    }

    Path writeBinaryOutput(String filename, byte[] content) throws IOException {
        Path file = generatedOutputPath(filename);
        Files.write(file, content);
        return file;
    }

    Path writeTextOutput(String filename, String content) throws IOException {
        Path file = generatedOutputPath(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    String outputUrl(Path file) {
        return "/text/files/" + file.getFileName();
    }

    Path outputPath(String filename) {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Invalid output filename.");
        }
        return outputDirectory.resolve(filename).normalize();
    }

    String effectiveVoiceName(TtsRequest request) {
        if (request.voiceName() != null && !request.voiceName().isBlank()) {
            return request.voiceName().strip();
        }
        if (request.effectiveLanguageCode().equalsIgnoreCase("en-US")) {
            return defaultEnglishVoice;
        }
        return "";
    }

    private Path generatedOutputPath(String filename) throws IOException {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Invalid output filename.");
        }
        Files.createDirectories(outputDirectory);
        return outputPath(filename);
    }

    private List<List<String>> partitionCues(List<String> cueTexts) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String cueText : cueTexts) {
            List<String> candidate = new ArrayList<>(current);
            candidate.add(cueText);
            if (!current.isEmpty() && ssmlSize(candidate) > GOOGLE_TTS_INPUT_LIMIT) {
                batches.add(List.copyOf(current));
                current.clear();
            }
            current.add(cueText);
            if (ssmlSize(current) > GOOGLE_TTS_INPUT_LIMIT) {
                throw new IllegalArgumentException(
                        "A subtitle cue exceeds Google TTS's 5,000-byte request limit.");
            }
        }

        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return List.copyOf(batches);
    }

    private int ssmlSize(List<String> cueTexts) {
        return buildSsml(cueTexts).getBytes(StandardCharsets.UTF_8).length;
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
        String voiceName = effectiveVoiceName(request);
        if (!voiceName.isBlank()) {
            voice.setName(voiceName);
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
