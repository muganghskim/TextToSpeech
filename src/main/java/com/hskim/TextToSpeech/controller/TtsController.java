package com.hskim.TextToSpeech.controller;

import com.hskim.TextToSpeech.service.TtsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/text")
@CrossOrigin("*")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping("/convert-to-audio")
    public ResponseEntity<String> convertToAudio(@RequestBody String text) throws IOException {
        // 서비스에게 텍스트 데이터 전달하여 음성 변환 수행 요청
        String audioFilePath = ttsService.convertTextToAudio(text);

        // 생성된 오디오 파일 경로를 클라이언트에 반환
        return ResponseEntity.ok(audioFilePath);
    }
}

