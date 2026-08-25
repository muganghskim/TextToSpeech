package com.hskim.TextToSpeech.service;

import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1beta1.Timepoint;
import com.google.protobuf.ByteString;
import com.hskim.TextToSpeech.model.TtsRequest;
import com.hskim.TextToSpeech.model.TtsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    @TempDir
    Path outputDirectory;

    @Test
    void createsMp3AndSrtUsingGoogleTimepoints() throws Exception {
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(synthesizer.synthesize(any())).thenReturn(
                SynthesizeSpeechResponse.newBuilder()
                        .setAudioContent(ByteString.copyFromUtf8("fake-mp3"))
                        .addTimepoints(Timepoint.newBuilder().setMarkName("cue-2").setTimeSeconds(1.25))
                        .addTimepoints(Timepoint.newBuilder().setMarkName("cue-end").setTimeSeconds(2.75))
                        .build());
        TtsService service = new TtsService(
                synthesizer,
                new SubtitleSegmenter(),
                new SrtWriter(),
                outputDirectory.toString(),
                "en-US-Neural2-F");

        TtsResult result = service.convertTextToAudio(
                new TtsRequest(
                        "This is the first sentence. This is the second sentence.",
                        "en-US",
                        null,
                        1.0,
                        0.0));

        assertThat(Files.readString(Path.of(result.audioFile()), StandardCharsets.UTF_8))
                .isEqualTo("fake-mp3");
        assertThat(Files.readString(Path.of(result.subtitleFile()))).isEqualTo("""
                1
                00:00:00,000 --> 00:00:01,250
                This is the first sentence.

                2
                00:00:01,250 --> 00:00:02,750
                This is the second sentence.

                """);

        ArgumentCaptor<SynthesizeSpeechRequest> captor =
                ArgumentCaptor.forClass(SynthesizeSpeechRequest.class);
        verify(synthesizer).synthesize(captor.capture());
        SynthesizeSpeechRequest sent = captor.getValue();
        assertThat(sent.getInput().getSsml())
                .contains("<mark name=\"cue-2\"/>", "<mark name=\"cue-end\"/>");
        assertThat(sent.getVoice().getName()).isEqualTo("en-US-Neural2-F");
        assertThat(sent.getEnableTimePointingList())
                .containsExactly(SynthesizeSpeechRequest.TimepointType.SSML_MARK);
    }
}
