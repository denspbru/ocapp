package com.nicodim.ocapp.pagination;

/** CSS-pixel source rectangle; independent from browser and presentation libraries. */
public record SourceBounds(double x, double y, double width, double height) {
    public SourceBounds {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
            || x < 0 || y < 0 || width <= 0 || height <= 0 || !Double.isFinite(x + width) || !Double.isFinite(y + height))
            throw new IllegalArgumentException("Source bounds must be finite and positive");
    }
    public double bottom() { return y + height; }
}
