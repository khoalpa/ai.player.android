package com.aiplayer.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YoutubeCaptionParser {
    private static final Pattern TEXT_PATTERN = Pattern.compile(
            "<text\\b([^>]*)>(.*?)</text>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private YoutubeCaptionParser() {
    }

    static List<TranscriptCue> parseTimedText(String xml) {
        List<TranscriptCue> cues = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return cues;
        }
        Matcher matcher = TEXT_PATTERN.matcher(xml);
        while (matcher.find()) {
            String attrs = matcher.group(1);
            double start = numberAttr(attrs, "start", -1);
            double dur = numberAttr(attrs, "dur", 3);
            if (start < 0) {
                continue;
            }
            String text = cleanCaptionText(matcher.group(2));
            if (!text.isEmpty()) {
                cues.add(new TranscriptCue((long) (start * 1000), (long) ((start + dur) * 1000), text));
            }
        }
        return cues;
    }

    private static String cleanCaptionText(String rawText) {
        String text = rawText == null ? "" : rawText;
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("<[^>]+>", "");
        return decodeEntities(text)
                .replace('\u00A0', ' ')
                .trim();
    }

    private static String decodeEntities(String text) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '&') {
                output.append(ch);
                continue;
            }
            int semicolon = text.indexOf(';', i + 1);
            if (semicolon < 0) {
                output.append(ch);
                continue;
            }
            String entity = text.substring(i + 1, semicolon);
            String decoded = decodeEntity(entity);
            if (decoded == null) {
                output.append(ch);
                continue;
            }
            output.append(decoded);
            i = semicolon;
        }
        return output.toString();
    }

    private static String decodeEntity(String entity) {
        String value = entity == null ? "" : entity.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.charAt(0) == '#') {
            try {
                int radix = value.length() > 1 && Character.toLowerCase(value.charAt(1)) == 'x' ? 16 : 10;
                String number = radix == 16 ? value.substring(2) : value.substring(1);
                return new String(Character.toChars(Integer.parseInt(number, radix)));
            } catch (Exception ex) {
                return null;
            }
        }
        switch (value.toLowerCase(Locale.US)) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
            case "#39":
                return "'";
            case "nbsp":
                return " ";
            default:
                return null;
        }
    }

    private static double numberAttr(String attrs, String name, double fallback) {
        Pattern pattern = Pattern.compile(name + "=\"([0-9.]+)\"");
        Matcher matcher = pattern.matcher(attrs);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
