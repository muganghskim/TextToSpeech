package com.hskim.TextToSpeech.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A small PCM WAV reader/writer so separately synthesized voices can be joined without re-encoding. */
final class PcmWav {

    private static final int SAMPLE_RATE = 24_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private final byte[] pcm;

    private PcmWav(byte[] pcm) {
        this.pcm = pcm;
    }

    static PcmWav read(byte[] wav) throws IOException {
        if (wav.length < 44 || !ascii(wav, 0, "RIFF") || !ascii(wav, 8, "WAVE")) {
            throw new IOException("Chirp 3 HD did not return a WAV file.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 12;
        boolean validFormat = false;
        byte[] pcm = null;
        while (offset <= wav.length - 8) {
            long chunkSize = Integer.toUnsignedLong(buffer.getInt(offset + 4));
            long end = offset + 8L + chunkSize;
            if (end > wav.length) {
                throw new IOException("Chirp 3 HD returned a truncated WAV file.");
            }
            if (ascii(wav, offset, "fmt ")) {
                if (chunkSize < 16) {
                    throw new IOException("Chirp 3 HD returned an invalid WAV format.");
                }
                validFormat = Short.toUnsignedInt(buffer.getShort(offset + 8)) == 1
                        && Short.toUnsignedInt(buffer.getShort(offset + 10)) == CHANNELS
                        && buffer.getInt(offset + 12) == SAMPLE_RATE
                        && Short.toUnsignedInt(buffer.getShort(offset + 22)) == BITS_PER_SAMPLE;
            } else if (ascii(wav, offset, "data")) {
                pcm = new byte[(int) chunkSize];
                System.arraycopy(wav, offset + 8, pcm, 0, pcm.length);
            }
            offset = (int) (end + (chunkSize & 1));
        }
        if (!validFormat || pcm == null || pcm.length == 0 || pcm.length % 2 != 0) {
            throw new IOException("Chirp 3 HD returned an unsupported WAV format; expected 24 kHz mono PCM16.");
        }
        return new PcmWav(pcm);
    }

    static PcmWav fromPcm(byte[] pcm) {
        return new PcmWav(pcm);
    }

    byte[] pcm() {
        return pcm;
    }

    double durationSeconds() {
        return (double) pcm.length / (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
    }

    byte[] toBytes() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(44 + pcm.length);
        writeAscii(output, "RIFF");
        writeInt(output, 36 + pcm.length);
        writeAscii(output, "WAVEfmt ");
        writeInt(output, 16);
        writeShort(output, 1);
        writeShort(output, CHANNELS);
        writeInt(output, SAMPLE_RATE);
        writeInt(output, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        writeShort(output, CHANNELS * BITS_PER_SAMPLE / 8);
        writeShort(output, BITS_PER_SAMPLE);
        writeAscii(output, "data");
        writeInt(output, pcm.length);
        output.writeBytes(pcm);
        return output.toByteArray();
    }

    private static boolean ascii(byte[] bytes, int offset, String text) {
        if (offset + text.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (bytes[offset + index] != (byte) text.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static void writeAscii(ByteArrayOutputStream output, String text) {
        output.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }
}
