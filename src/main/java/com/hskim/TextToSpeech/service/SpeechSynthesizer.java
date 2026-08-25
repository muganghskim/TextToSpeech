package com.hskim.TextToSpeech.service;

import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;

import java.io.IOException;

public interface SpeechSynthesizer {
    SynthesizeSpeechResponse synthesize(SynthesizeSpeechRequest request) throws IOException;
}
