package com.nicodim.ocapp.pagemodel;

import java.util.List;

/** Computed CSS border sides retained so unsupported borders can fail closed to pixels. */
public record CssBorder(Side top, Side right, Side bottom, Side left) {
    public CssBorder {
        top = safe(top); right = safe(right); bottom = safe(bottom); left = safe(left);
    }

    public record Side(double widthPixels, String color, String style) {
        public Side { color = color == null ? "" : color; style = style == null ? "" : style; }
        public boolean visible() {
            return widthPixels > 0 && !"none".equalsIgnoreCase(style) && !"hidden".equalsIgnoreCase(style);
        }
    }

    public boolean visible() { return sides().stream().anyMatch(Side::visible); }
    public boolean uniform() { return top.equals(right) && top.equals(bottom) && top.equals(left); }
    public List<Side> sides() { return List.of(top, right, bottom, left); }
    public static CssBorder none() {
        Side side = new Side(0, "transparent", "none");
        return new CssBorder(side, side, side, side);
    }
    private static Side safe(Side side) { return side == null ? noneSide() : side; }
    private static Side noneSide() { return new Side(0, "transparent", "none"); }
}
