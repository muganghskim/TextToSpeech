package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.TtsResult;
import com.hskim.TextToSpeech.model.VideoProjectRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class VideoProjectTtsService {

    private final TtsService ttsService;

    public VideoProjectTtsService(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    public TtsResult synthesize(VideoProjectRequest request) throws IOException {
        if (request == null || request.scenes() == null || request.scenes().isEmpty()) {
            throw new IllegalArgumentException("scenes must contain at least one scene.");
        }

        List<VideoProjectRequest.Scene> scenes = request.scenes().stream()
                .filter(Objects::nonNull)
                .filter(scene -> scene.narration() != null && !scene.narration().isBlank())
                .sorted(Comparator.comparing(
                        VideoProjectRequest.Scene::order,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (scenes.isEmpty()) {
            throw new IllegalArgumentException("scenes must contain at least one non-blank narration.");
        }

        VideoProjectRequest.TtsSettings settings = request.tts();
        String languageCode = firstNonBlank(
                settings == null ? null : settings.languageCode(),
                request.project() == null ? null : request.project().language(),
                "en-US");
        String narration = scenes.stream()
                .map(scene -> scene.narration().strip())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();

        return ttsService.convertTextToAudio(new TtsRequest(
                narration,
                languageCode,
                settings == null ? null : settings.voiceName(),
                settings == null ? null : settings.speakingRate(),
                settings == null ? null : settings.pitch()));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        throw new IllegalStateException("No default value was provided.");
    }
}
