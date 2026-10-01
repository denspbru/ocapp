package com.nicodim.ocapp.pagemodel;
public record Bounds(double x, double y, double width, double height) {
    public double right() { return x + width; }
    public double bottom() { return y + height; }
}
