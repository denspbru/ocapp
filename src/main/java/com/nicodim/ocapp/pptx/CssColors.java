package com.nicodim.ocapp.pptx;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CssColors {
    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*([0-9.]+))?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Color> NAMED = Map.of("black", Color.BLACK, "white", Color.WHITE,
        "red", Color.RED, "green", new Color(0, 128, 0), "blue", Color.BLUE, "gray", Color.GRAY,
        "grey", Color.GRAY, "yellow", Color.YELLOW, "transparent", new Color(0, 0, 0, 0));

    private CssColors() { }

    static Color parse(String value) {
        if (value == null) return null;
        String text = value.trim().toLowerCase(Locale.ROOT);
        Color named = NAMED.get(text);
        if (named != null) return named;
        try {
            if (text.matches("#[0-9a-f]{3}")) return new Color(Integer.parseInt("" + text.charAt(1) + text.charAt(1), 16),
                Integer.parseInt("" + text.charAt(2) + text.charAt(2), 16), Integer.parseInt("" + text.charAt(3) + text.charAt(3), 16));
            if (text.matches("#[0-9a-f]{6}")) return new Color(Integer.parseInt(text.substring(1), 16));
            Matcher matcher = RGB.matcher(text);
            if (matcher.matches()) {
                int r = Integer.parseInt(matcher.group(1)), g = Integer.parseInt(matcher.group(2)), b = Integer.parseInt(matcher.group(3));
                if (r > 255 || g > 255 || b > 255) return null;
                int alpha = matcher.group(4) == null ? 255 : (int) Math.round(Double.parseDouble(matcher.group(4)) * 255);
                if (alpha < 0 || alpha > 255) return null;
                return new Color(r, g, b, alpha);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    static boolean supported(String value) { return parse(value) != null; }
}
