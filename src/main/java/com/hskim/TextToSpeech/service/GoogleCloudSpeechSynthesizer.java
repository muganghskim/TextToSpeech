package com.hskim.TextToSpeech.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1beta1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1beta1.TextToSpeechSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class GoogleCloudSpeechSynthesizer implements SpeechSynthesizer {

    private final String credentialsPath;

    public GoogleCloudSpeechSynthesizer(
            @Value("${google.cloud.credentials.path:}") String credentialsPath) {
        this.credentialsPath = credentialsPath.strip();
    }

    @Override
    public SynthesizeSpeechResponse synthesize(SynthesizeSpeechRequest request) throws IOException {
        try (TextToSpeechClient client = TextToSpeechClient.create(createSettings())) {
            return client.synthesizeSpeech(request);
        }
    }

    private TextToSpeechSettings createSettings() throws IOException {
        TextToSpeechSettings.Builder settings = TextToSpeechSettings.newBuilder();
        if (credentialsPath.isBlank()) {
            // Fall back to Application Default Credentials, such as `gcloud auth application-default login`.
            return settings.build();
        }

        Path credentialsFile = Path.of(credentialsPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(credentialsFile)) {
            throw new FileNotFoundException("Google credentials file was not found.");
        }
        try (InputStream input = Files.newInputStream(credentialsFile)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(input);
            return settings
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
        }
    }
}
