package com.hskim.TextToSpeech.controller;

import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.TtsResult;
import com.hskim.TextToSpeech.service.TtsService;
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

@RestController
@RequestMapping("/text")
@CrossOrigin("*")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
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

    @GetMapping("/files/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
        Path file = ttsService.resolveOutputFile(filename);
        MediaType mediaType = filename.endsWith(".srt")
                ? MediaType.parseMediaType("application/x-subrip")
                : MediaType.parseMediaType("audio/mpeg");

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(file));
    }
}
