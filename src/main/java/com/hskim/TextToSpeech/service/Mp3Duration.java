package com.hskim.TextToSpeech.service;

import java.util.OptionalDouble;

/**
 * Reads the playback duration directly from MPEG audio frames. Google Cloud TTS returns
 * CBR MP3 data, whose encoded duration can be slightly longer than the final SSML mark
 * because an MP3 frame has a fixed number of samples.
 */
final class Mp3Duration {

    private Mp3Duration() {
    }

    static OptionalDouble readSeconds(byte[] audio) {
        if (audio == null || audio.length < 4) {
            return OptionalDouble.empty();
        }

        int offset = skipId3v2Tag(audio);
        double seconds = 0.0;
        int frameCount = 0;

        while (offset + 4 <= audio.length) {
            Frame frame = readFrame(audio, offset);
            if (frame == null) {
                // A valid Google TTS response is a consecutive MP3 frame stream. Treat
                // unknown bytes as a malformed file rather than guessing its duration.
                return OptionalDouble.empty();
            }
            if (offset + frame.lengthBytes() > audio.length) {
                return OptionalDouble.empty();
            }

            seconds += (double) frame.samplesPerFrame() / frame.sampleRate();
            frameCount++;
            offset += frame.lengthBytes();
        }

        return frameCount == 0 || offset != audio.length
                ? OptionalDouble.empty()
                : OptionalDouble.of(seconds);
    }

    private static int skipId3v2Tag(byte[] audio) {
        if (audio.length < 10 || audio[0] != 'I' || audio[1] != 'D' || audio[2] != '3') {
            return 0;
        }
        int tagSize = (audio[6] & 0x7f) << 21
                | (audio[7] & 0x7f) << 14
                | (audio[8] & 0x7f) << 7
                | (audio[9] & 0x7f);
        int footerSize = (audio[5] & 0x10) == 0 ? 0 : 10;
        return Math.min(audio.length, 10 + tagSize + footerSize);
    }

    private static Frame readFrame(byte[] audio, int offset) {
        int header = (audio[offset] & 0xff) << 24
                | (audio[offset + 1] & 0xff) << 16
                | (audio[offset + 2] & 0xff) << 8
                | (audio[offset + 3] & 0xff);
        if ((header & 0xffe00000) != 0xffe00000) {
            return null;
        }

        int versionBits = header >>> 19 & 0b11;
        int layerBits = header >>> 17 & 0b11;
        int bitrateIndex = header >>> 12 & 0b1111;
        int sampleRateIndex = header >>> 10 & 0b11;
        int padding = header >>> 9 & 0b1;
        if (versionBits == 0b01 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 0b1111
                || sampleRateIndex == 0b11) {
            return null;
        }

        int sampleRate = sampleRate(versionBits, sampleRateIndex);
        int bitrateKbps = bitrateKbps(versionBits, layerBits, bitrateIndex);
        if (sampleRate == 0 || bitrateKbps == 0) {
            return null;
        }

        int samplesPerFrame;
        int lengthBytes;
        if (layerBits == 0b11) { // Layer I
            samplesPerFrame = 384;
            lengthBytes = (12 * bitrateKbps * 1_000 / sampleRate + padding) * 4;
        } else if (layerBits == 0b01 && versionBits != 0b11) { // MPEG-2/2.5 Layer III
            samplesPerFrame = 576;
            lengthBytes = 72 * bitrateKbps * 1_000 / sampleRate + padding;
        } else {
            samplesPerFrame = 1_152;
            lengthBytes = 144 * bitrateKbps * 1_000 / sampleRate + padding;
        }
        return lengthBytes < 4 ? null : new Frame(sampleRate, samplesPerFrame, lengthBytes);
    }

    private static int sampleRate(int versionBits, int index) {
        int[] rates = switch (versionBits) {
            case 0b11 -> new int[]{44_100, 48_000, 32_000};
            case 0b10 -> new int[]{22_050, 24_000, 16_000};
            case 0b00 -> new int[]{11_025, 12_000, 8_000};
            default -> new int[0];
        };
        return index < rates.length ? rates[index] : 0;
    }

    private static int bitrateKbps(int versionBits, int layerBits, int index) {
        int[] table;
        if (layerBits == 0b11) {
            table = versionBits == 0b11
                    ? new int[]{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}
                    : new int[]{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};
        } else if (layerBits == 0b10) {
            table = versionBits == 0b11
                    ? new int[]{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384}
                    : new int[]{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        } else { // Layer III
            table = versionBits == 0b11
                    ? new int[]{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
                    : new int[]{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        }
        return index < table.length ? table[index] : 0;
    }

    private record Frame(int sampleRate, int samplesPerFrame, int lengthBytes) {
    }
}
