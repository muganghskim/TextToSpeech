package com.hskim.TextToSpeech.service;

import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class SubtitleSegmenter {

    static final int MAX_CUE_LENGTH = 42;

    public List<String> segment(String text, String languageCode) {
        Locale locale = Locale.forLanguageTag(languageCode);
        List<String> cues = new ArrayList<>();

        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String normalized = line.strip().replaceAll("\\s+", " ");
            if (normalized.isEmpty()) {
                continue;
            }

            BreakIterator iterator = BreakIterator.getSentenceInstance(locale);
            iterator.setText(normalized);
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                wrap(normalized.substring(start, end).strip(), cues);
            }
        }

        return List.copyOf(cues);
    }

    private void wrap(String sentence, List<String> cues) {
        String remaining = sentence;
        while (remaining.length() > MAX_CUE_LENGTH) {
            int split = bestSplit(remaining, MAX_CUE_LENGTH);
            cues.add(remaining.substring(0, split).strip());
            remaining = remaining.substring(split).strip();
        }
        if (!remaining.isEmpty()) {
            cues.add(remaining);
        }
    }

    private int bestSplit(String value, int preferred) {
        for (int index = preferred; index >= preferred / 2; index--) {
            char character = value.charAt(index - 1);
            if (Character.isWhitespace(character) || isPunctuation(character)) {
                return index;
            }
        }
        return preferred;
    }

    private boolean isPunctuation(char character) {
        return ",.;:!?、。！？；：".indexOf(character) >= 0;
    }
}
