package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.SceneAudioResult;
import com.hskim.TextToSpeech.model.SubtitleCue;
import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VideoProjectTtsService {

    private static final double REVIEW_THRESHOLD_SECONDS = 0.5;
    private static final double REVIEW_THRESHOLD_PERCENT = 10.0;

    private final TtsService ttsService;
    private final SrtWriter srtWriter;
    private final ObjectMapper objectMapper;

    public VideoProjectTtsService(
            TtsService ttsService,
            SrtWriter srtWriter,
            ObjectMapper objectMapper) {
        this.ttsService = ttsService;
        this.srtWriter = srtWriter;
        this.objectMapper = objectMapper;
    }

    public VideoProjectTtsResult synthesize(ObjectNode request) throws IOException {
        ObjectNode timedProject = request == null ? null : request.deepCopy();
        if (timedProject == null) {
            throw new IllegalArgumentException("The video project JSON body is required.");
        }

        List<SceneWork> scenes = readScenes(timedProject);
        if (scenes.stream().noneMatch(scene -> !scene.narration().isBlank())) {
            throw new IllegalArgumentException("scenes must contain at least one non-blank narration.");
        }

        int fps = readFps(timedProject);
        TtsRequest baseRequest = readTtsRequest(timedProject);
        String projectId = textAt(timedProject.path("project"), "id");
        if (projectId == null) {
            projectId = "video-project";
        }

        String outputId = UUID.randomUUID().toString();
        String combinedAudioName = outputId + ".mp3";
        String subtitleName = outputId + ".srt";
        String timedProjectName = outputId + "-timed.json";
        ByteArrayOutputStream combinedAudio = new ByteArrayOutputStream();
        List<SubtitleCue> combinedCues = new ArrayList<>();
        List<SceneAudioResult> sceneResults = new ArrayList<>();
        List<PendingAudio> pendingAudio = new ArrayList<>();
        Map<String, Double> sceneStartTimes = new HashMap<>();

        double timeline = 0.0;
        int currentFrame = 0;
        int sceneNumber = 0;
        ArrayNode orderedScenes = objectMapper.createArrayNode();

        for (SceneWork scene : scenes) {
            sceneNumber++;
            double startSec = timeline;
            int startFrame = currentFrame;
            String sceneAudioName = null;
            String sceneAudioUrl = null;
            SynthesizedSpeech speech = null;

            if (!scene.narration().isBlank()) {
                speech = ttsService.synthesize(new TtsRequest(
                        scene.narration(),
                        baseRequest.languageCode(),
                        baseRequest.voiceName(),
                        baseRequest.speakingRate(),
                        baseRequest.pitch()));
                sceneAudioName = outputId + "-scene-" + String.format("%03d", sceneNumber) + ".mp3";
                sceneAudioUrl = "/text/files/" + sceneAudioName;
                pendingAudio.add(new PendingAudio(sceneAudioName, speech.audio()));
                combinedAudio.writeBytes(speech.audio());

                for (SubtitleCue cue : speech.cues()) {
                    combinedCues.add(new SubtitleCue(
                            combinedCues.size() + 1,
                            cue.text(),
                            cue.startSeconds() + timeline,
                            cue.endSeconds() + timeline));
                }
                timeline += speech.durationSeconds();
            }

            double endSec = timeline;
            int endFrame = Math.max(startFrame, (int) Math.round(endSec * fps));
            if (speech != null && speech.durationSeconds() > 0.0 && endFrame == startFrame) {
                endFrame++;
            }
            currentFrame = endFrame;

            double actualDuration = endSec - startSec;
            double difference = actualDuration - scene.estimatedDurationSec();
            String timingSource = speech == null || !speech.exactTiming()
                    ? "ESTIMATED_FALLBACK"
                    : "GOOGLE_SSML_MARK";
            String reviewStatus = reviewStatus(scene, speech, difference);
            sceneStartTimes.put(scene.id(), startSec);

            writeSceneTiming(
                    scene.node(),
                    startSec,
                    endSec,
                    actualDuration,
                    startFrame,
                    endFrame,
                    difference,
                    scene.estimatedDurationSec(),
                    timingSource,
                    reviewStatus,
                    sceneAudioName,
                    sceneAudioUrl);
            orderedScenes.add(scene.node());

            sceneResults.add(new SceneAudioResult(
                    scene.id(),
                    scene.order(),
                    sceneAudioName == null ? null : ttsService.outputPath(sceneAudioName).toString(),
                    sceneAudioUrl,
                    roundMillis(startSec),
                    roundMillis(endSec),
                    roundMillis(actualDuration),
                    startFrame,
                    endFrame,
                    endFrame - startFrame,
                    roundMillis(difference),
                    timingSource,
                    reviewStatus));
        }

        timedProject.set("scenes", orderedScenes);
        writeProjectTiming(
                timedProject,
                baseRequest,
                projectId,
                outputId,
                combinedAudioName,
                subtitleName,
                timedProjectName,
                timeline,
                fps,
                currentFrame,
                sceneResults);
        writeChapterTimings(timedProject, sceneStartTimes);

        List<Path> writtenFiles = new ArrayList<>();
        try {
            for (PendingAudio audio : pendingAudio) {
                writtenFiles.add(ttsService.writeBinaryOutput(audio.filename(), audio.content()));
            }
            Path combinedAudioFile = ttsService.writeBinaryOutput(
                    combinedAudioName, combinedAudio.toByteArray());
            writtenFiles.add(combinedAudioFile);
            Path subtitleFile = ttsService.writeTextOutput(
                    subtitleName, srtWriter.write(combinedCues));
            writtenFiles.add(subtitleFile);
            Path timedProjectFile = ttsService.writeTextOutput(
                    timedProjectName,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(timedProject));
            writtenFiles.add(timedProjectFile);

            return new VideoProjectTtsResult(
                    outputId,
                    projectId,
                    combinedAudioFile.toString(),
                    subtitleFile.toString(),
                    timedProjectFile.toString(),
                    ttsService.outputUrl(combinedAudioFile),
                    ttsService.outputUrl(subtitleFile),
                    ttsService.outputUrl(timedProjectFile),
                    roundMillis(timeline),
                    fps,
                    currentFrame,
                    combinedCues.size(),
                    List.copyOf(sceneResults));
        } catch (IOException | RuntimeException exception) {
            deleteWrittenFiles(writtenFiles, exception);
            throw exception;
        }
    }

    private List<SceneWork> readScenes(ObjectNode project) {
        JsonNode scenesNode = project.get("scenes");
        if (!(scenesNode instanceof ArrayNode sceneArray) || sceneArray.isEmpty()) {
            throw new IllegalArgumentException("scenes must contain at least one scene.");
        }

        List<SceneWork> scenes = new ArrayList<>();
        for (int index = 0; index < sceneArray.size(); index++) {
            JsonNode value = sceneArray.get(index);
            if (!(value instanceof ObjectNode scene)) {
                throw new IllegalArgumentException("Every scenes item must be a JSON object.");
            }
            int order = integerAt(scene, "order", index + 1);
            String sceneId = textAt(scene, "id");
            if (sceneId == null) {
                sceneId = "scene-" + String.format("%03d", order);
                scene.put("id", sceneId);
            }
            String narration = textAt(scene, "narration");
            double estimatedDuration = doubleAt(scene, "estimatedDurationSec", 0.0);
            scenes.add(new SceneWork(
                    scene,
                    sceneId,
                    order,
                    index,
                    narration == null ? "" : narration.strip(),
                    estimatedDuration));
        }

        scenes.sort(Comparator.comparingInt(SceneWork::order)
                .thenComparingInt(SceneWork::originalIndex));
        return List.copyOf(scenes);
    }

    private int readFps(ObjectNode project) {
        int fps = integerAt(project.path("project"), "fps", 30);
        if (fps < 1 || fps > 240) {
            throw new IllegalArgumentException("project.fps must be between 1 and 240.");
        }
        return fps;
    }

    private TtsRequest readTtsRequest(ObjectNode project) {
        JsonNode tts = project.path("tts");
        String languageCode = firstNonBlank(
                textAt(tts, "languageCode"),
                textAt(project.path("project"), "language"),
                "en-US");
        return new TtsRequest(
                "project-placeholder",
                languageCode,
                textAt(tts, "voiceName"),
                nullableDoubleAt(tts, "speakingRate"),
                nullableDoubleAt(tts, "pitch"));
    }

    private void writeSceneTiming(
            ObjectNode scene,
            double startSec,
            double endSec,
            double actualDuration,
            int startFrame,
            int endFrame,
            double difference,
            double estimatedDuration,
            String timingSource,
            String reviewStatus,
            String sceneAudioName,
            String sceneAudioUrl) {
        ObjectNode timing = objectMapper.createObjectNode();
        timing.put("startSec", roundMillis(startSec));
        timing.put("endSec", roundMillis(endSec));
        timing.put("actualDurationSec", roundMillis(actualDuration));
        timing.put("startFrame", startFrame);
        timing.put("endFrameExclusive", endFrame);
        timing.put("durationInFrames", endFrame - startFrame);
        timing.put("differenceFromEstimateSec", roundMillis(difference));
        timing.put("timingSource", timingSource);
        if (estimatedDuration > 0.0) {
            timing.put("differenceFromEstimatePercent", roundMillis(difference / estimatedDuration * 100.0));
        } else {
            timing.putNull("differenceFromEstimatePercent");
        }
        timing.put("reviewStatus", reviewStatus);
        scene.set("timing", timing);

        ObjectNode audio = objectMapper.createObjectNode();
        if (sceneAudioName == null) {
            audio.putNull("file");
            audio.putNull("downloadUrl");
        } else {
            audio.put("file", sceneAudioName);
            audio.put("downloadUrl", sceneAudioUrl);
        }
        scene.set("audio", audio);
    }

    private void writeProjectTiming(
            ObjectNode project,
            TtsRequest request,
            String projectId,
            String outputId,
            String combinedAudioName,
            String subtitleName,
            String timedProjectName,
            double totalDuration,
            int fps,
            int totalFrames,
            List<SceneAudioResult> sceneResults) {
        ObjectNode timing = objectMapper.createObjectNode();
        timing.put("outputId", outputId);
        timing.put("projectId", projectId);
        timing.put("generatedAt", Instant.now().toString());
        timing.put("actualTotalDurationSec", roundMillis(totalDuration));
        timing.put("fps", fps);
        timing.put("totalFrames", totalFrames);
        timing.put("languageCode", request.effectiveLanguageCode());
        timing.put("voiceName", ttsService.effectiveVoiceName(request));
        timing.put("speakingRate", request.effectiveSpeakingRate());
        timing.put("pitch", request.effectivePitch());
        timing.put("reviewThresholdSeconds", REVIEW_THRESHOLD_SECONDS);
        timing.put("reviewThresholdPercent", REVIEW_THRESHOLD_PERCENT);
        timing.put("reviewSceneCount", sceneResults.stream()
                .filter(scene -> !"OK".equals(scene.reviewStatus()))
                .count());
        timing.put("estimatedTimingSceneCount", sceneResults.stream()
                .filter(scene -> "ESTIMATED_FALLBACK".equals(scene.timingSource()))
                .count());
        project.set("audioTiming", timing);

        ObjectNode outputs = objectMapper.createObjectNode();
        outputs.put("combinedAudioFile", combinedAudioName);
        outputs.put("combinedAudioUrl", "/text/files/" + combinedAudioName);
        outputs.put("subtitleFile", subtitleName);
        outputs.put("subtitleUrl", "/text/files/" + subtitleName);
        outputs.put("timedProjectFile", timedProjectName);
        outputs.put("timedProjectUrl", "/text/files/" + timedProjectName);
        project.set("generatedOutputs", outputs);

        JsonNode strategyNode = project.get("videoStrategy");
        if (strategyNode instanceof ObjectNode strategy) {
            strategy.put("actualLengthSec", roundMillis(totalDuration));
            strategy.put("actualLengthFrames", totalFrames);
        }
    }

    private void writeChapterTimings(ObjectNode project, Map<String, Double> sceneStartTimes) {
        JsonNode chaptersNode = project.path("youtube").path("chapters");
        if (!(chaptersNode instanceof ArrayNode chapters)) {
            return;
        }
        for (JsonNode value : chapters) {
            if (!(value instanceof ObjectNode chapter)) {
                continue;
            }
            String sceneId = textAt(chapter, "sceneId");
            Double startSec = sceneStartTimes.get(sceneId);
            if (startSec != null) {
                chapter.put("startSec", roundMillis(startSec));
                chapter.put("timestamp", chapterTimestamp(startSec));
            }
        }
    }

    private String reviewStatus(
            SceneWork scene,
            SynthesizedSpeech speech,
            double difference) {
        if (scene.narration().isBlank()) {
            return "REVIEW_NO_NARRATION";
        }
        if (speech == null || !speech.exactTiming()) {
            return "REVIEW_ESTIMATED_TIMING";
        }
        if (scene.estimatedDurationSec() <= 0.0) {
            return "REVIEW_NO_ESTIMATE";
        }
        double allowedDifference = Math.max(
                REVIEW_THRESHOLD_SECONDS,
                scene.estimatedDurationSec() * REVIEW_THRESHOLD_PERCENT / 100.0);
        return Math.abs(difference) > allowedDifference ? "REVIEW" : "OK";
    }

    private void deleteWrittenFiles(List<Path> files, Exception original) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private String chapterTimestamp(double seconds) {
        long totalSeconds = Math.max(0L, Math.round(seconds));
        long hours = totalSeconds / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long remainingSeconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        throw new IllegalStateException("No default value was provided.");
    }

    private String textAt(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isString() || value.asString().isBlank()
                ? null
                : value.asString().strip();
    }

    private int integerAt(JsonNode node, String field, int defaultValue) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isIntegralNumber() ? value.asInt() : defaultValue;
    }

    private double doubleAt(JsonNode node, String field, double defaultValue) {
        Double value = nullableDoubleAt(node, field);
        return value == null ? defaultValue : value;
    }

    private Double nullableDoubleAt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private double roundMillis(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record SceneWork(
            ObjectNode node,
            String id,
            int order,
            int originalIndex,
            String narration,
            double estimatedDurationSec) {
    }

    private record PendingAudio(String filename, byte[] content) {
    }
}
