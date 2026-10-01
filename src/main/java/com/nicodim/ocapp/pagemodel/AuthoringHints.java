package com.nicodim.ocapp.pagemodel;
public record AuthoringHints(boolean keepTogether, String slide, String title, String notes, String layout, Render render) {
    public enum Render { AUTO, IMAGE, NATIVE }
    public AuthoringHints { slide=safe(slide); title=safe(title); notes=safe(notes); layout=safe(layout); render=render == null ? Render.AUTO : render; }
    private static String safe(String v) { return v == null ? "" : v; }
    public static AuthoringHints none() { return new AuthoringHints(false,"","","","",Render.AUTO); }
}
