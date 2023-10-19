package com.hskim.TextToSpeech.util;

import com.google.api.gax.core.CredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class GoogleCloudCredentialsProvider implements CredentialsProvider {

    private static final String CREDENTIALS_FILE_PATH = "resources/static/lucid-volt-370402-baf73142fe48.json";

    @Override
    public GoogleCredentials getCredentials() throws IOException {
        ClassPathResource resource = new ClassPathResource(CREDENTIALS_FILE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return GoogleCredentials.fromStream(inputStream);
        }
    }
}
