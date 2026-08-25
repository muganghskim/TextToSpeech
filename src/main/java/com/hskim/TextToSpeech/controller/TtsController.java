package com.hskim.TextToSpeech.controller;

import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.TtsResult;
import com.hskim.TextToSpeech.model.VideoProjectTtsResult;
import com.hskim.TextToSpeech.service.TtsService;
import com.hskim.TextToSpeech.service.VideoProjectTtsService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/text")
@CrossOrigin("*")
public class TtsController {

    private final TtsService ttsService;
    private final VideoProjectTtsService videoProjectTtsService;

    public TtsController(TtsService ttsService, VideoProjectTtsService videoProjectTtsService) {
        this.ttsService = ttsService;
        this.videoProjectTtsService = videoProjectTtsService;
    }

    @PostMapping("/convert-to-audio")
    public TtsResult convertToAudio(
            @RequestBody String text,
            @RequestParam(defaultValue = "en-US") String languageCode,
            @RequestParam(required = false) String voiceName,
            @RequestParam(defaultValue = "1.0") double speakingRate,
            @RequestParam(defaultValue = "0.0") double pitch) throws IOException {
        return ttsService.convertTextToAudio(
                new TtsRequest(text, languageCode, voiceName, speakingRate, pitch));
    }

    @PostMapping(value = "/synthesize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TtsResult synthesize(@RequestBody TtsRequest request) throws IOException {
        return ttsService.convertTextToAudio(request);
    }

    @PostMapping(value = "/synthesize-project", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VideoProjectTtsResult synthesizeProject(@RequestBody ObjectNode request) throws IOException {
        return videoProjectTtsService.synthesize(request);
    }

    @GetMapping("/files/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
        Path file = ttsService.resolveOutputFile(filename);
        MediaType mediaType;
        if (filename.endsWith(".srt")) {
            mediaType = MediaType.parseMediaType("application/x-subrip");
        } else if (filename.endsWith(".json")) {
            mediaType = MediaType.APPLICATION_JSON;
        } else {
            mediaType = MediaType.parseMediaType("audio/mpeg");
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(file));
    }
}
