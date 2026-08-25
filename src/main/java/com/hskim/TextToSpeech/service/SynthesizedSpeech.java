package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.SubtitleCue;

import java.util.List;

record SynthesizedSpeech(
        byte[] audio,
        List<SubtitleCue> cues,
        double durationSeconds,
        boolean exactTiming) {
}
