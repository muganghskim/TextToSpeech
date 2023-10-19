package com.hskim.TextToSpeech.service;

import com.google.api.gax.core.CredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
//import com.google.cloud.speech.v1.*;
import com.google.cloud.texttospeech.v1.*;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@Service
public class TtsService {

    private final CredentialsProvider credentialsProvider;

    public TtsService(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
    }

    public String convertTextToAudio(String text) throws IOException {
        // Google Cloud 인증 정보 로드
        GoogleCredentials credentials = (GoogleCredentials) credentialsProvider.getCredentials();

        // SpeechClient 초기화
        try (TextToSpeechClient speechClient = TextToSpeechClient.create(TextToSpeechSettings.newBuilder().setCredentialsProvider((CredentialsProvider) credentials).build())) {
            // Text to Speech 변환 요청 생성
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();
            VoiceSelectionParams voice =
                    VoiceSelectionParams.newBuilder()
                            .setLanguageCode("en-US")  // 원하는 언어 코드로 변경 가능
                            .build();
            AudioConfig audioConfig =
                    AudioConfig.newBuilder()
                            .setAudioEncoding(AudioEncoding.MP3)
                            .build();

            // Text to Speech 변환 요청 전송 및 응답 처리
            SynthesizeSpeechResponse response =
                    speechClient.synthesizeSpeech(input, voice, audioConfig);

            byte[] audioContent = response.getAudioContent().toByteArray();

            String audioFilePath = saveAudioToFile(audioContent);

            return audioFilePath;
        }
    }

    private String saveAudioToFile(byte[] audioContent){
        // 오디오 데이터를 파일로 저장하는 로직 구현
        String audioFilePath = "resources/tts.mp3";

        try (OutputStream outputStream = new FileOutputStream(audioFilePath)) {
            outputStream.write(audioContent);
        } catch (IOException e) {
            // 예외 처리 로직 작성
        }

        return audioFilePath;
    }
}
