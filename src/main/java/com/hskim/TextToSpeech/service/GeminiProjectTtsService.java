package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.SceneAudioResult;
import com.hskim.TextToSpeech.model.SubtitleCue;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
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

/** One Gemini request for the entire project; turn and scene times are estimates. */
@Service
public class GeminiProjectTtsService {

    private static final Logger log = LoggerFactory.getLogger(GeminiProjectTtsService.class);
    private static final String ESTIMATED = "ESTIMATED_FROM_TOTAL_AUDIO";

    private final GeminiTtsClient client;
    private final SubtitleSegmenter segmenter;
    private final SrtWriter srtWriter;
    private final TtsService files;
    private final ObjectMapper mapper;

    public GeminiProjectTtsService(GeminiTtsClient client, SubtitleSegmenter segmenter,
                                   SrtWriter srtWriter, TtsService files, ObjectMapper mapper) {
        this.client = client;
        this.segmenter = segmenter;
        this.srtWriter = srtWriter;
        this.files = files;
        this.mapper = mapper;
    }

    public ChirpProjectTtsService.Workload workload(ObjectNode input) {
        if (!client.configured()) {
            throw new GeminiConfigurationException("GEMINI_API_KEY is not set. Restart the server after setting it.");
        }
        Plan plan = plan(input);
        return new ChirpProjectTtsService.Workload(plan.scenes().size(), plan.segments().size());
    }

    public VideoProjectTtsResult synthesize(ObjectNode input,
                                             ChirpProjectTtsService.ProgressListener listener) throws IOException {
        Plan plan = plan(input);
        List<GeminiTtsClient.Turn> turns = plan.segments().stream()
                .map(segment -> new GeminiTtsClient.Turn(segment.gender(), segment.text(), segment.style()))
                .toList();
        Scene first = plan.scenes().get(0);
        listener.onProgress(new ChirpProjectTtsService.Progress("STARTED", first.id(), 1,
                plan.scenes().size(), 1, first.segments().size(), 0, turns.size()));
        log.info("project4 single Gemini request scenes={} turns={} maleVoice={} femaleVoice={}",
                plan.scenes().size(), turns.size(), plan.maleVoice(), plan.femaleVoice());
        byte[] pcm = client.synthesizeDialogue(turns, plan.maleVoice(), plan.femaleVoice());
        if (pcm.length == 0 || (pcm.length & 1) != 0) {
            throw new IOException("Gemini TTS returned invalid 24 kHz PCM16 audio.");
        }
        Scene last = plan.scenes().get(plan.scenes().size() - 1);
        listener.onProgress(new ChirpProjectTtsService.Progress("COMPLETED", last.id(),
                plan.scenes().size(), plan.scenes().size(), last.segments().size(),
                last.segments().size(), turns.size(), turns.size()));
        return writeResults(plan, pcm);
    }

    private VideoProjectTtsResult writeResults(Plan plan, byte[] pcm) throws IOException {
        String id = UUID.randomUUID().toString();
        String audioName = id + ".wav";
        String subtitleName = id + ".srt";
        String timedName = id + "-timed.json";
        long totalSamples = pcm.length / 2L;
        long totalWeight = plan.segments().stream().mapToLong(segment -> weight(segment.text())).sum();
        long consumedWeight = 0;
        long startSample = 0;
        List<SubtitleCue> cues = new ArrayList<>();
        List<SceneAudioResult> sceneResults = new ArrayList<>();
        Map<String, Double> sceneStarts = new LinkedHashMap<>();
        ArrayNode orderedScenes = mapper.createArrayNode();
        int processed = 0;

        for (Scene scene : plan.scenes()) {
            long sceneStartSample = startSample;
            sceneStarts.put(scene.id(), seconds(sceneStartSample));
            for (Segment segment : scene.segments()) {
                consumedWeight += weight(segment.text());
                processed++;
                long endSample = processed == plan.segments().size() ? totalSamples
                        : Math.round(totalSamples * (double) consumedWeight / totalWeight);
                double start = seconds(startSample);
                double end = seconds(endSample);
                ObjectNode timing = mapper.createObjectNode();
                timing.put("startSec", round(start));
                timing.put("endSec", round(end));
                timing.put("estimatedDurationSecFromAudio", round(end - start));
                timing.put("source", ESTIMATED);
                timing.put("subtitleTimingSource", ESTIMATED);
                segment.node().set("timing", timing);
                segment.node().put("geminiSpeaker", segment.gender());
                segment.node().put("voiceName", segment.gender().equals("male")
                        ? plan.maleVoice() : plan.femaleVoice());
                addEstimatedCues(cues, segment.text(), plan.language(), start, end);
                startSample = endSample;
            }
            double start = seconds(sceneStartSample);
            double end = seconds(startSample);
            int startFrame = (int) Math.round(start * plan.fps());
            int endFrame = (int) Math.round(end * plan.fps());
            ObjectNode timing = scene.node().path("timing") instanceof ObjectNode existing
                    ? existing : mapper.createObjectNode();
            timing.put("startSec", round(start));
            timing.put("endSec", round(end));
            timing.put("durationSec", round(end - start));
            timing.put("estimatedDurationSecFromAudio", round(end - start));
            timing.put("startFrame", startFrame);
            timing.put("endFrameExclusive", endFrame);
            timing.put("durationFrames", endFrame - startFrame);
            timing.put("source", ESTIMATED);
            timing.put("reviewStatus", "REVIEW_ESTIMATED_TIMING");
            scene.node().set("timing", timing);
            ObjectNode audio = mapper.createObjectNode();
            audio.putNull("file");
            audio.putNull("downloadUrl");
            audio.put("reason", "Scene boundaries are estimates; no scene WAV was cut from the combined audio.");
            scene.node().set("audio", audio);
            orderedScenes.add(scene.node());
            sceneResults.add(new SceneAudioResult(scene.id(), scene.order(), null, null,
                    round(start), round(end), round(end - start), startFrame, endFrame,
                    endFrame - startFrame, 0.0, ESTIMATED, "REVIEW_ESTIMATED_TIMING"));
        }

        ObjectNode project = plan.project();
        project.set("scenes", orderedScenes);
        double total = seconds(totalSamples);
        writeChapterTimings(project, sceneStarts);
        writeProjectTiming(plan, id, audioName, subtitleName, timedName, total, cues.size());

        List<Path> written = new ArrayList<>();
        try {
            Path combined = files.writeBinaryOutput(audioName, PcmWav.fromPcm(pcm).toBytes());
            written.add(combined);
            Path srt = files.writeTextOutput(subtitleName, srtWriter.write(cues));
            written.add(srt);
            Path timed = files.writeTextOutput(timedName,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(project));
            written.add(timed);
            return new VideoProjectTtsResult(id, plan.projectId(), combined.toString(),
                    srt.toString(), timed.toString(), files.outputUrl(combined), files.outputUrl(srt),
                    files.outputUrl(timed), round(total), plan.fps(),
                    (int) Math.round(total * plan.fps()), cues.size(), List.copyOf(sceneResults));
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

    private Plan plan(ObjectNode input) {
        if (input == null) {
            throw new IllegalArgumentException("The video project JSON body is required.");
        }
        ObjectNode project = input.deepCopy();
        JsonNode tts = project.path("tts");
        JsonNode settings = project.path("project");
        String language = first(text(tts, "languageCode"), text(settings, "language"), "ko-KR");
        String projectId = first(text(settings, "id"), "video-project");
        int fps = settings.path("fps").isIntegralNumber() ? settings.path("fps").asInt() : 30;
        if (fps < 1 || fps > 240) {
            throw new IllegalArgumentException("project.fps must be between 1 and 240.");
        }
        JsonNode sceneNode = project.path("scenes");
        if (!(sceneNode instanceof ArrayNode array) || array.isEmpty()) {
            throw new IllegalArgumentException("scenes must contain at least one scene.");
        }
        JsonNode speakerMap = tts.path("geminiSpeakerMap");
        List<Scene> scenes = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof ObjectNode scene)) {
                throw new IllegalArgumentException("Every scenes item must be an object.");
            }
            int order = scene.path("order").isIntegralNumber() ? scene.path("order").asInt() : index + 1;
            String sceneId = first(text(scene, "id"), "scene-" + String.format(Locale.ROOT, "%03d", order));
            scene.put("id", sceneId);
            JsonNode segmentNode = scene.path("ttsSegments");
            if (!(segmentNode instanceof ArrayNode segments) || segments.isEmpty()) {
                throw new IllegalArgumentException(sceneId + " requires a non-empty ttsSegments array.");
            }
            List<Segment> parsed = new ArrayList<>();
            for (JsonNode value : segments) {
                if (!(value instanceof ObjectNode segment)) {
                    throw new IllegalArgumentException(sceneId + ".ttsSegments must contain objects.");
                }
                String speakerId = text(segment, "speakerId");
                String transcript = text(segment, "text");
                if (speakerId == null || transcript == null) {
                    throw new IllegalArgumentException(sceneId + " contains a blank speakerId or text.");
                }
                String gender = gender(speakerId, speakerMap);
                String style = first(text(segment, "style"), text(tts, "voiceStyle"));
                parsed.add(new Segment(segment, speakerId, gender, transcript,
                        style == null ? "" : style.replace('-', ' ')));
            }
            scenes.add(new Scene(scene, sceneId, order, index, List.copyOf(parsed)));
        }
        scenes.sort(Comparator.comparingInt(Scene::order).thenComparingInt(Scene::index));
        List<Segment> allSegments = scenes.stream().flatMap(scene -> scene.segments().stream()).toList();
        JsonNode voices = tts.path("geminiVoices");
        JsonNode perCharacter = tts.path("geminiVoiceMap") instanceof ObjectNode
                ? tts.path("geminiVoiceMap") : tts.path("voiceMap");
        String male = first(text(voices, "male"), voiceFor(perCharacter, allSegments, "male"), "Orus");
        String female = first(text(voices, "female"), voiceFor(perCharacter, allSegments, "female"), "Kore");
        if (!validPrebuiltVoice(male) || !validPrebuiltVoice(female)) {
            throw new IllegalArgumentException("tts.geminiVoices must contain two prebuilt Gemini voice names.");
        }
        return new Plan(project, List.copyOf(scenes), allSegments, projectId,
                language, fps, male, female);
    }

    private static String gender(String speakerId, JsonNode overrides) {
        String configured = text(overrides, speakerId);
        String value = configured == null ? switch (speakerId.toLowerCase(Locale.ROOT)) {
            case "father", "dad", "son", "brother", "husband", "male" -> "male";
            case "mother", "mom", "daughter", "sister", "wife", "female" -> "female";
            default -> null;
        } : configured.toLowerCase(Locale.ROOT);
        if (!"male".equals(value) && !"female".equals(value)) {
            throw new IllegalArgumentException("Set tts.geminiSpeakerMap." + speakerId + " to male or female.");
        }
        return value;
    }

    private static String voiceFor(JsonNode voiceMap, List<Segment> segments, String gender) {
        if (gender.equals("female")) {
            String daughter = voiceName(text(voiceMap, "daughter"));
            if (daughter != null) {
                return daughter;
            }
        } else {
            String father = voiceName(text(voiceMap, "father"));
            if (father != null) {
                return father;
            }
        }
        for (Segment segment : segments) {
            if (segment.gender().equals(gender)) {
                String value = voiceName(text(voiceMap, segment.speakerId()));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String voiceName(String value) {
        if (value == null) {
            return null;
        }
        int marker = value.indexOf("-Chirp3-HD-");
        return marker < 0 ? value : value.substring(marker + "-Chirp3-HD-".length());
    }

    private static boolean validPrebuiltVoice(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9-]{1,60}")
                && !value.startsWith("voice_");
    }

    private void addEstimatedCues(List<SubtitleCue> cues, String text, String language,
                                  double start, double end) {
        List<String> parts = segmenter.segment(text, language);
        int total = parts.stream().mapToInt(String::length).sum();
        if (total == 0) {
            return;
        }
        int consumed = 0;
        for (int index = 0; index < parts.size(); index++) {
            double cueStart = start + (end - start) * consumed / total;
            consumed += parts.get(index).length();
            double cueEnd = index == parts.size() - 1 ? end : start + (end - start) * consumed / total;
            cues.add(new SubtitleCue(cues.size() + 1, parts.get(index), cueStart, cueEnd));
        }
    }

    private void writeProjectTiming(Plan plan, String id, String audioName, String subtitleName,
                                    String timedName, double duration, int cueCount) {
        ObjectNode project = plan.project();
        ObjectNode timing = mapper.createObjectNode();
        timing.put("outputId", id);
        timing.put("projectId", plan.projectId());
        timing.put("generatedAt", Instant.now().toString());
        timing.put("provider", "GEMINI_3_8_FLASH_TTS");
        timing.put("audioEncoding", "LINEAR16_WAV");
        timing.put("actualTotalDurationSec", round(duration));
        timing.put("fps", plan.fps());
        timing.put("totalFrames", (int) Math.round(duration * plan.fps()));
        timing.put("sceneCount", plan.scenes().size());
        timing.put("segmentCount", plan.segments().size());
        timing.put("apiRequestCount", 1);
        timing.put("cueCount", cueCount);
        timing.put("sceneTimingSource", ESTIMATED);
        timing.put("subtitleTimingSource", ESTIMATED);
        timing.put("reviewRequired", true);
        timing.put("timingNote", "Only total audio duration is measured; segment, scene, and subtitle times are estimates.");
        project.set("audioTiming", timing);
        ObjectNode outputs = mapper.createObjectNode();
        outputs.put("combinedAudioFile", audioName);
        outputs.put("combinedAudioUrl", "/text/files/" + audioName);
        outputs.put("subtitleFile", subtitleName);
        outputs.put("subtitleUrl", "/text/files/" + subtitleName);
        outputs.put("timedProjectFile", timedName);
        outputs.put("timedProjectUrl", "/text/files/" + timedName);
        project.set("generatedOutputs", outputs);
        if (project.path("tts") instanceof ObjectNode tts) {
            tts.put("provider", "GEMINI_3_8_FLASH_TTS");
            ObjectNode voices = tts.putObject("geminiVoicesResolved");
            voices.put("male", plan.maleVoice());
            voices.put("female", plan.femaleVoice());
        }
        if (project.path("videoStrategy") instanceof ObjectNode strategy) {
            strategy.put("actualLengthSec", round(duration));
            strategy.put("actualLengthFrames", (int) Math.round(duration * plan.fps()));
        }
    }

    private void writeChapterTimings(ObjectNode project, Map<String, Double> sceneStarts) {
        if (!(project.path("youtube").path("chapters") instanceof ArrayNode chapters)) {
            return;
        }
        for (JsonNode value : chapters) {
            if (!(value instanceof ObjectNode chapter)) {
                continue;
            }
            Double start = sceneStarts.get(text(chapter, "sceneId"));
            if (start == null) {
                continue;
            }
            long seconds = Math.round(start);
            chapter.put("startSec", round(start));
            chapter.put("timestampTimingSource", ESTIMATED);
            chapter.put("timestamp", seconds >= 3_600
                    ? String.format(Locale.ROOT, "%d:%02d:%02d",
                            seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
                    : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        }
    }

    private static long weight(String text) {
        return Math.max(1, text.codePoints().filter(Character::isLetterOrDigit).count());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank()
                ? value.asString().strip() : null;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static double seconds(long samples) {
        return samples / 24_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record Plan(ObjectNode project, List<Scene> scenes, List<Segment> segments,
                        String projectId, String language, int fps, String maleVoice, String femaleVoice) { }
    private record Scene(ObjectNode node, String id, int order, int index, List<Segment> segments) { }
    private record Segment(ObjectNode node, String speakerId, String gender, String text, String style) { }
}
