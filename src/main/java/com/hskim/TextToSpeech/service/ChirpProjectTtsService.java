package com.hskim.TextToSpeech.service;

import com.google.cloud.texttospeech.v1beta1.AudioConfig;
import com.google.cloud.texttospeech.v1beta1.AudioEncoding;
import com.google.cloud.texttospeech.v1beta1.SynthesisInput;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.VoiceSelectionParams;
import com.hskim.TextToSpeech.model.SceneAudioResult;
import com.hskim.TextToSpeech.model.SubtitleCue;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ChirpProjectTtsService {

    private static final int MAX_INPUT_BYTES = 5_000;
    private static final int SPLIT_TEXT_BYTES = 3_500;

    private final SpeechSynthesizer synthesizer;
    private final SubtitleSegmenter segmenter;
    private final SrtWriter srtWriter;
    private final TtsService files;
    private final ObjectMapper mapper;

    public ChirpProjectTtsService(
            SpeechSynthesizer synthesizer,
            SubtitleSegmenter segmenter,
            SrtWriter srtWriter,
            TtsService files,
            ObjectMapper mapper) {
        this.synthesizer = synthesizer;
        this.segmenter = segmenter;
        this.srtWriter = srtWriter;
        this.files = files;
        this.mapper = mapper;
    }

    public VideoProjectTtsResult synthesize(ObjectNode input) throws IOException {
        return synthesize(input, progress -> { });
    }

    public Workload workload(ObjectNode input) {
        if (input == null) {
            throw new IllegalArgumentException("The video project JSON body is required.");
        }
        ObjectNode copy = input.deepCopy();
        List<Scene> parsed = scenes(copy, config(copy));
        int segments = parsed.stream().mapToInt(scene -> scene.segments().size()).sum();
        if (segments == 0) {
            throw new IllegalArgumentException("At least one scene must contain ttsSegments.");
        }
        return new Workload(parsed.size(), segments);
    }

    public VideoProjectTtsResult synthesize(ObjectNode input, ProgressListener listener) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("The video project JSON body is required.");
        }
        ObjectNode project = input.deepCopy();
        Config config = config(project);
        List<Scene> scenes = scenes(project, config);
        if (scenes.stream().noneMatch(scene -> !scene.segments().isEmpty())) {
            throw new IllegalArgumentException("At least one scene must contain ttsSegments.");
        }

        String id = UUID.randomUUID().toString();
        String audioName = id + ".wav";
        String subtitleName = id + ".srt";
        String timedName = id + "-timed.json";
        ByteArrayOutputStream combinedPcm = new ByteArrayOutputStream();
        List<SubtitleCue> cues = new ArrayList<>();
        List<SceneAudioResult> sceneResults = new ArrayList<>();
        List<PendingAudio> sceneAudio = new ArrayList<>();
        ArrayNode orderedScenes = mapper.createArrayNode();
        Map<String, Double> sceneStartTimes = new LinkedHashMap<>();
        int elapsedSamples = 0;
        int sceneNumber = 0;
        int completedSegments = 0;
        int totalSegments = scenes.stream().mapToInt(scene -> scene.segments().size()).sum();

        for (Scene scene : scenes) {
            sceneNumber++;
            int segmentNumber = 0;
            int sceneStartSamples = elapsedSamples;
            ByteArrayOutputStream scenePcm = new ByteArrayOutputStream();
            int sentenceIndex = 0;

            for (Segment segment : scene.segments()) {
                segmentNumber++;
                listener.onProgress(new Progress("STARTED", scene.id(), sceneNumber, scenes.size(),
                        segmentNumber, scene.segments().size(), completedSegments, totalSegments));
                int segmentStartSamples = elapsedSamples;
                for (String part : splitLongSegment(segment.text(), config.languageCode())) {
                    String ssml = ssml(part, config.pitch(), scene.pauseMs(),
                            config.defaultPauseMs(), sentenceIndex);
                    sentenceIndex += sentenceEndCount(part, config.languageCode());
                    if (ssml.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                        throw new IllegalArgumentException("A ttsSegments item exceeds Google's 5,000-byte SSML limit.");
                    }
                    PcmWav wav = PcmWav.read(synthesizer.synthesize(request(ssml, config, segment.voiceName()))
                            .getAudioContent().toByteArray());
                    scenePcm.writeBytes(wav.pcm());
                    combinedPcm.writeBytes(wav.pcm());
                    elapsedSamples += wav.pcm().length / 2;
                }
                double start = seconds(segmentStartSamples);
                double end = seconds(elapsedSamples);
                writeSegmentTiming(segment.node(), start, end, segment.voiceName());
                addEstimatedCues(cues, segment.text(), config.languageCode(), start, end);
                completedSegments++;
                listener.onProgress(new Progress("COMPLETED", scene.id(), sceneNumber, scenes.size(),
                        segmentNumber, scene.segments().size(), completedSegments, totalSegments));
            }

            double start = seconds(sceneStartSamples);
            double end = seconds(elapsedSamples);
            sceneStartTimes.put(scene.id(), start);
            int startFrame = (int) Math.round(start * config.fps());
            int endFrame = (int) Math.round(end * config.fps());
            String sceneName = scene.segments().isEmpty()
                    ? null : id + "-scene-" + String.format(Locale.ROOT, "%03d", sceneNumber) + ".wav";
            if (sceneName != null) {
                sceneAudio.add(new PendingAudio(sceneName, PcmWav.fromPcm(scenePcm.toByteArray()).toBytes()));
            }
            writeSceneTiming(scene.node(), start, end, startFrame, endFrame, sceneName);
            orderedScenes.add(scene.node());
            sceneResults.add(new SceneAudioResult(
                    scene.id(), scene.order(),
                    sceneName == null ? null : files.outputPath(sceneName).toString(),
                    sceneName == null ? null : "/text/files/" + sceneName,
                    round(start), round(end), round(end - start), startFrame, endFrame,
                    endFrame - startFrame, 0.0, "PCM_SAMPLE_COUNT",
                    sceneName == null ? "REVIEW_NO_NARRATION" : "OK"));
        }

        project.set("scenes", orderedScenes);
        double total = seconds(elapsedSamples);
        writeChapterTimings(project, sceneStartTimes);
        writeProjectTiming(project, config, id, audioName, subtitleName, timedName,
                total, sceneResults.size(), cues.size());

        List<Path> written = new ArrayList<>();
        try {
            for (PendingAudio audio : sceneAudio) {
                written.add(files.writeBinaryOutput(audio.name(), audio.content()));
            }
            Path combined = files.writeBinaryOutput(audioName,
                    PcmWav.fromPcm(combinedPcm.toByteArray()).toBytes());
            written.add(combined);
            Path srt = files.writeTextOutput(subtitleName, srtWriter.write(cues));
            written.add(srt);
            Path timed = files.writeTextOutput(timedName,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(project));
            written.add(timed);
            return new VideoProjectTtsResult(id, config.projectId(), combined.toString(),
                    srt.toString(), timed.toString(), files.outputUrl(combined), files.outputUrl(srt),
                    files.outputUrl(timed), round(total), config.fps(),
                    (int) Math.round(total * config.fps()), cues.size(), List.copyOf(sceneResults));
        } catch (IOException | RuntimeException failure) {
            for (Path path : written) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private Config config(ObjectNode project) {
        JsonNode tts = project.path("tts");
        JsonNode projectSettings = project.path("project");
        String language = text(tts, "languageCode");
        if (language == null) {
            language = text(projectSettings, "language");
        }
        if (language == null) {
            language = "ko-KR";
        }
        String selectedLanguage = language;
        JsonNode mapNode = tts.path("voiceMap");
        if (!(mapNode instanceof ObjectNode voices) || voices.isEmpty()) {
            throw new IllegalArgumentException("tts.voiceMap must map speaker IDs to Chirp 3 HD voice names.");
        }
        Map<String, String> voiceMap = new LinkedHashMap<>();
        voices.properties().forEach(entry -> {
            String speaker = entry.getKey();
            JsonNode value = entry.getValue();
            if (!value.isString() || !value.asString().startsWith(selectedLanguage + "-Chirp3-HD-")) {
                throw new IllegalArgumentException("Invalid Chirp 3 HD voice for speaker: " + speaker);
            }
            voiceMap.put(speaker, value.asString());
        });
        int fps = intValue(projectSettings, "fps", 30);
        double rate = number(tts, "speakingRate", 1.0);
        double pitch = number(tts, "pitch", 0.0);
        double pauseMin = number(tts, "sentencePauseMsMin", 0.0);
        double pauseMax = number(tts, "sentencePauseMsMax", pauseMin);
        if (fps < 1 || fps > 240 || !Double.isFinite(rate) || rate < 0.25 || rate > 2.0
                || !Double.isFinite(pitch) || pitch < -20 || pitch > 20
                || !validPause(pauseMin) || !validPause(pauseMax) || pauseMax < pauseMin) {
            throw new IllegalArgumentException("Invalid project.fps or tts rate, pitch, or pause settings.");
        }
        String projectId = text(projectSettings, "id");
        if (projectId == null) {
            projectId = "video-project";
        }
        return new Config(projectId, language, Map.copyOf(voiceMap), fps, rate, pitch,
                (pauseMin + pauseMax) / 2.0);
    }

    private List<Scene> scenes(ObjectNode project, Config config) {
        JsonNode node = project.get("scenes");
        if (!(node instanceof ArrayNode array) || array.isEmpty()) {
            throw new IllegalArgumentException("scenes must contain at least one scene.");
        }
        List<Scene> scenes = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof ObjectNode scene)) {
                throw new IllegalArgumentException("Every scenes item must be an object.");
            }
            int order = intValue(scene, "order", index + 1);
            String sceneId = text(scene, "id");
            if (sceneId == null) {
                sceneId = "scene-" + String.format(Locale.ROOT, "%03d", order);
                scene.put("id", sceneId);
            }
            JsonNode segmentsNode = scene.get("ttsSegments");
            if (!(segmentsNode instanceof ArrayNode segments) || segments.isEmpty()) {
                throw new IllegalArgumentException(sceneId + " requires a non-empty ttsSegments array.");
            }
            List<Double> pauses = new ArrayList<>();
            JsonNode pausesNode = scene.get("sentencePauseMs");
            if (pausesNode != null && !pausesNode.isNull()) {
                if (!(pausesNode instanceof ArrayNode pauseArray)) {
                    throw new IllegalArgumentException(sceneId + ".sentencePauseMs must be an array.");
                }
                for (JsonNode pause : pauseArray) {
                    if (!pause.isNumber() || !validPause(pause.asDouble())) {
                        throw new IllegalArgumentException(sceneId + ".sentencePauseMs must contain 0–5000 ms values.");
                    }
                    pauses.add(pause.asDouble());
                }
            }
            List<Segment> parsed = new ArrayList<>();
            for (JsonNode segmentNode : segments) {
                if (!(segmentNode instanceof ObjectNode segment)) {
                    throw new IllegalArgumentException(sceneId + ".ttsSegments must contain objects.");
                }
                String speaker = text(segment, "speakerId");
                String speech = text(segment, "text");
                if (speaker == null || speech == null || !config.voiceMap().containsKey(speaker)) {
                    throw new IllegalArgumentException(sceneId + " contains a blank segment or an unmapped speakerId: " + speaker);
                }
                parsed.add(new Segment(segment, speech, config.voiceMap().get(speaker)));
            }
            scenes.add(new Scene(scene, sceneId, order, index, List.copyOf(parsed), List.copyOf(pauses)));
        }
        scenes.sort(Comparator.comparingInt(Scene::order).thenComparingInt(Scene::originalIndex));
        return List.copyOf(scenes);
    }

    private SynthesizeSpeechRequest request(String ssml, Config config, String voiceName) {
        return SynthesizeSpeechRequest.newBuilder()
                .setInput(SynthesisInput.newBuilder().setSsml(ssml))
                .setVoice(VoiceSelectionParams.newBuilder()
                        .setLanguageCode(config.languageCode()).setName(voiceName))
                .setAudioConfig(AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.LINEAR16)
                        .setSampleRateHertz(24_000)
                        .setSpeakingRate(config.rate()))
                .build();
    }

    private String ssml(String text, double pitch, List<Double> pauses,
                        double defaultPause, int sentenceOffset) {
        StringBuilder result = new StringBuilder("<speak>");
        if (pitch != 0) {
            result.append("<prosody pitch=\"")
                    .append(String.format(Locale.ROOT, "%+.1fst", pitch)).append("\">");
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        int pauseIndex = sentenceOffset;
        int start = 0;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (".!?。！？".indexOf(character) < 0) {
                continue;
            }
            result.append(escape(normalized.substring(start, index + 1)));
            double pause = pauseIndex < pauses.size() ? pauses.get(pauseIndex) : defaultPause;
            pauseIndex++;
            if (pause > 0) {
                result.append("<break time=\"").append(Math.round(pause)).append("ms\"/>");
            }
            start = index + 1;
        }
        result.append(escape(normalized.substring(start)));
        if (pitch != 0) {
            result.append("</prosody>");
        }
        return result.append("</speak>").toString();
    }

    private int sentenceEndCount(String text, String language) {
        int count = 0;
        for (char character : text.toCharArray()) {
            if (".!?。！？".indexOf(character) >= 0) {
                count++;
            }
        }
        return count;
    }

    private List<String> splitLongSegment(String text, String language) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= SPLIT_TEXT_BYTES) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String cue : segmenter.segment(text, language)) {
            if (current.length() > 0
                    && (current.length() + cue.length() + 1) * 4 > SPLIT_TEXT_BYTES) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(cue);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return List.copyOf(parts);
    }

    private void addEstimatedCues(List<SubtitleCue> result, String text, String language,
                                  double start, double end) {
        List<String> parts = segmenter.segment(text, language);
        if (parts.isEmpty()) {
            return;
        }
        int total = parts.stream().mapToInt(String::length).sum();
        int consumed = 0;
        for (int index = 0; index < parts.size(); index++) {
            double cueStart = start + (end - start) * consumed / total;
            consumed += parts.get(index).length();
            double cueEnd = index == parts.size() - 1 ? end : start + (end - start) * consumed / total;
            result.add(new SubtitleCue(result.size() + 1, parts.get(index), cueStart, cueEnd));
        }
    }

    private void writeSegmentTiming(ObjectNode segment, double start, double end, String voiceName) {
        ObjectNode timing = mapper.createObjectNode();
        timing.put("startSec", round(start));
        timing.put("endSec", round(end));
        timing.put("actualDurationSec", round(end - start));
        timing.put("source", "PCM_SAMPLE_COUNT");
        timing.put("subtitleTimingSource", "ESTIMATED_WITHIN_SEGMENT");
        segment.set("timing", timing);
        segment.put("voiceName", voiceName);
    }

    private void writeSceneTiming(ObjectNode scene, double start, double end,
                                  int startFrame, int endFrame, String audioName) {
        ObjectNode timing = scene.get("timing") instanceof ObjectNode existing
                ? existing : mapper.createObjectNode();
        timing.put("startSec", round(start));
        timing.put("endSec", round(end));
        timing.put("durationSec", round(end - start));
        timing.put("actualDurationSec", round(end - start));
        timing.put("startFrame", startFrame);
        timing.put("endFrameExclusive", endFrame);
        timing.put("durationFrames", endFrame - startFrame);
        timing.put("source", "PCM_SAMPLE_COUNT");
        scene.set("timing", timing);
        ObjectNode audio = mapper.createObjectNode();
        audio.put("file", audioName);
        audio.put("downloadUrl", audioName == null ? null : "/text/files/" + audioName);
        scene.set("audio", audio);
    }

    private void writeProjectTiming(ObjectNode project, Config config, String id,
                                    String audioName, String subtitleName, String timedName,
                                    double duration, int sceneCount, int cueCount) {
        ObjectNode timing = mapper.createObjectNode();
        timing.put("outputId", id);
        timing.put("projectId", config.projectId());
        timing.put("generatedAt", Instant.now().toString());
        timing.put("provider", "GOOGLE_CHIRP3_HD");
        timing.put("audioEncoding", "LINEAR16_WAV");
        timing.put("actualTotalDurationSec", round(duration));
        timing.put("fps", config.fps());
        timing.put("totalFrames", (int) Math.round(duration * config.fps()));
        timing.put("sceneCount", sceneCount);
        timing.put("cueCount", cueCount);
        timing.put("sceneTimingSource", "PCM_SAMPLE_COUNT");
        timing.put("subtitleTimingSource", "ESTIMATED_WITHIN_SEGMENT");
        timing.put("speakingRate", config.rate());
        timing.put("pitch", config.pitch());
        project.set("audioTiming", timing);
        ObjectNode output = mapper.createObjectNode();
        output.put("combinedAudioFile", audioName);
        output.put("combinedAudioUrl", "/text/files/" + audioName);
        output.put("subtitleFile", subtitleName);
        output.put("subtitleUrl", "/text/files/" + subtitleName);
        output.put("timedProjectFile", timedName);
        output.put("timedProjectUrl", "/text/files/" + timedName);
        project.set("generatedOutputs", output);
        if (project.get("videoStrategy") instanceof ObjectNode strategy) {
            strategy.put("actualLengthSec", round(duration));
            strategy.put("actualLengthFrames", (int) Math.round(duration * config.fps()));
        }
    }

    private void writeChapterTimings(ObjectNode project, Map<String, Double> sceneStartTimes) {
        if (!(project.path("youtube").path("chapters") instanceof ArrayNode chapters)) {
            return;
        }
        for (JsonNode value : chapters) {
            if (!(value instanceof ObjectNode chapter)) {
                continue;
            }
            Double start = sceneStartTimes.get(text(chapter, "sceneId"));
            if (start == null) {
                continue;
            }
            long seconds = Math.round(start);
            chapter.put("startSec", round(start));
            chapter.put("timestamp", seconds >= 3_600
                    ? String.format(Locale.ROOT, "%d:%02d:%02d",
                            seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
                    : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank()
                ? value.asString().strip() : null;
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isIntegralNumber() ? value.asInt() : fallback;
    }

    private static double number(JsonNode node, String field, double fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    private static boolean validPause(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 5_000;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static double seconds(int samples) {
        return samples / 24_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record Config(String projectId, String languageCode, Map<String, String> voiceMap,
                          int fps, double rate, double pitch, double defaultPauseMs) { }
    private record Scene(ObjectNode node, String id, int order, int originalIndex,
                         List<Segment> segments, List<Double> pauseMs) { }
    private record Segment(ObjectNode node, String text, String voiceName) { }
    private record PendingAudio(String name, byte[] content) { }

    public record Workload(int totalScenes, int totalSegments) { }
    public record Progress(String phase, String sceneId, int sceneNumber, int totalScenes,
                           int segmentNumber, int segmentsInScene,
                           int completedSegments, int totalSegments) { }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(Progress progress);
    }
}
