package com.hskim.TextToSpeech.service;

import com.hskim.TextToSpeech.model.SubtitleCue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class SrtWriter {

    public String write(List<SubtitleCue> cues) {
        StringBuilder srt = new StringBuilder();
        for (SubtitleCue cue : cues) {
            srt.append(cue.index()).append('\n')
                    .append(formatTimestamp(cue.startSeconds()))
                    .append(" --> ")
                    .append(formatTimestamp(cue.endSeconds())).append('\n')
                    .append(cue.text()).append("\n\n");
        }
        return srt.toString();
    }

    String formatTimestamp(double seconds) {
        long millis = Math.max(0L, Math.round(seconds * 1_000.0));
        long hours = millis / 3_600_000;
        millis %= 3_600_000;
        long minutes = millis / 60_000;
        millis %= 60_000;
        long wholeSeconds = millis / 1_000;
        long milliseconds = millis % 1_000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                hours, minutes, wholeSeconds, milliseconds);
    }
}
