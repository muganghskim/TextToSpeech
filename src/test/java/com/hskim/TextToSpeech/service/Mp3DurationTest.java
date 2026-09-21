package com.hskim.TextToSpeech.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Mp3DurationTest {

    @Test
    void readsMpeg2Layer3FrameDuration() {
        byte[] audio = mpeg2Layer3Frames(5);

        assertThat(Mp3Duration.readSeconds(audio)).hasValue(0.12);
    }

    private byte[] mpeg2Layer3Frames(int count) {
        byte[] audio = new byte[count * 192];
        for (int index = 0; index < count; index++) {
            int offset = index * 192;
            audio[offset] = (byte) 0xff;
            audio[offset + 1] = (byte) 0xf3;
            audio[offset + 2] = (byte) 0x84;
            audio[offset + 3] = 0;
        }
        return audio;
    }
}
