package com.hskim.TextToSpeech.service;

import java.util.Arrays;

/** Conservatively trims only excess trailing quiet PCM16 samples at a speaker change. */
final class PcmSilenceTrimmer {

    private static final int SAMPLE_RATE = 24_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int FRAME_SAMPLES = 240; // 10 ms
    private static final int QUIET_PEAK = 128;

    private PcmSilenceTrimmer() { }

    static Result trimExcessTrailingSilence(byte[] pcm, double targetMs) {
        int quietBytes = 0;
        int end = pcm.length;
        while (end > 0) {
            int start = Math.max(0, end - FRAME_SAMPLES * BYTES_PER_SAMPLE);
            if (!isQuiet(pcm, start, end)) {
                break;
            }
            quietBytes += end - start;
            end = start;
        }

        // Entirely silent audio has no speech boundary to protect; leave it untouched.
        if (end == 0) {
            return new Result(pcm, 0, 0);
        }
        int targetBytes = (int) Math.round(targetMs * SAMPLE_RATE / 1_000.0) * BYTES_PER_SAMPLE;
        int removedBytes = Math.max(0, quietBytes - targetBytes);
        byte[] result = removedBytes == 0 ? pcm : Arrays.copyOf(pcm, pcm.length - removedBytes);
        return new Result(result, millis(quietBytes), millis(removedBytes));
    }

    private static boolean isQuiet(byte[] pcm, int start, int end) {
        for (int index = start; index < end; index += BYTES_PER_SAMPLE) {
            int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            if (Math.abs(sample) > QUIET_PEAK) {
                return false;
            }
        }
        return true;
    }

    private static double millis(int byteCount) {
        return byteCount * 1_000.0 / (SAMPLE_RATE * BYTES_PER_SAMPLE);
    }

    record Result(byte[] pcm, double detectedMs, double removedMs) { }
}
