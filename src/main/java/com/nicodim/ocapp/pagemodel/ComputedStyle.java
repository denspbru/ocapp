package com.nicodim.ocapp.pagemodel;
public record ComputedStyle(String display, String position, String overflowX, String overflowY, String color,
                            String backgroundColor, String fontFamily, double fontSize, int fontWeight,
                            String fontStyle, String textAlign, double lineHeight, double opacity, int zIndex,
                            boolean flexContainer, boolean gridContainer, CssBorder border) {
    public ComputedStyle { display=safe(display); position=safe(position); overflowX=safe(overflowX); overflowY=safe(overflowY); color=safe(color); backgroundColor=safe(backgroundColor); fontFamily=safe(fontFamily); fontStyle=safe(fontStyle); textAlign=safe(textAlign); border=border == null ? CssBorder.none() : border; }
    public ComputedStyle(String display, String position, String overflowX, String overflowY, String color,
                         String backgroundColor, String fontFamily, double fontSize, int fontWeight,
                         String fontStyle, String textAlign, double lineHeight, double opacity, int zIndex,
                         boolean flexContainer, boolean gridContainer) {
        this(display, position, overflowX, overflowY, color, backgroundColor, fontFamily, fontSize, fontWeight,
            fontStyle, textAlign, lineHeight, opacity, zIndex, flexContainer, gridContainer, CssBorder.none());
    }
    private static String safe(String v) { return v == null ? "" : v; }
}
