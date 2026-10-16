package com.hskim.TextToSpeech.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PcmSilenceTrimmerTest {

    @Test
    void preservesSilenceWhenItIsShorterThanTarget() {
        byte[] pcm = voicedThenSilent(500, 200);
        PcmSilenceTrimmer.Result result = PcmSilenceTrimmer.trimExcessTrailingSilence(pcm, 325);
        assertThat(result.pcm()).isSameAs(pcm);
        assertThat(result.detectedMs()).isEqualTo(200);
        assertThat(result.removedMs()).isZero();
    }

    @Test
    void trimsOnlyTheExcessAndLeavesVoicedSamplesUntouched() {
        byte[] pcm = voicedThenSilent(500, 600);
        PcmSilenceTrimmer.Result result = PcmSilenceTrimmer.trimExcessTrailingSilence(pcm, 325);
        assertThat(result.detectedMs()).isEqualTo(600);
        assertThat(result.removedMs()).isEqualTo(275);
        assertThat(result.pcm().length).isEqualTo(825 * 48);
        assertThat(result.pcm()[0]).isEqualTo(pcm[0]);
    }

    @Test
    void doesNotTrimEntirelySilentAudioWithoutASpeechBoundary() {
        byte[] pcm = new byte[48_000];
        PcmSilenceTrimmer.Result result = PcmSilenceTrimmer.trimExcessTrailingSilence(pcm, 325);
        assertThat(result.pcm()).isSameAs(pcm);
        assertThat(result.removedMs()).isZero();
    }

    private static byte[] voicedThenSilent(int voiceMs, int silenceMs) {
        byte[] pcm = new byte[(voiceMs + silenceMs) * 48];
        for (int sample = 0; sample < voiceMs * 24; sample++) {
            pcm[sample * 2] = (byte) 0xe8;
            pcm[sample * 2 + 1] = 0x03;
        }
        return pcm;
    }
}
