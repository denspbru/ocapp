package com.nicodim.ocapp.pagemodel;

import java.util.Set;

/** Normalized OCApp authoring-hints contract v1. Invalid source values never reach this value type. */
public record AuthoringHints(boolean keepTogether, String slide, String title, String notes, String layout, Render render) {
    public static final String CONTRACT_VERSION = "1";
    public static final String BREAK_BEFORE = "break-before";
    public static final Set<String> LAYOUTS = Set.of("", "blank", "title-only");

    public enum Render { AUTO, IMAGE, NATIVE }

    public AuthoringHints {
        slide = safe(slide);
        title = safe(title);
        notes = safe(notes);
        layout = safe(layout);
        render = render == null ? Render.AUTO : render;
    }

    public boolean breakBefore() { return BREAK_BEFORE.equals(slide); }

    private static String safe(String value) { return value == null ? "" : value; }
    public static AuthoringHints none() { return new AuthoringHints(false, "", "", "", "", Render.AUTO); }
}
