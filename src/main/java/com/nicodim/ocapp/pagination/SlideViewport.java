package com.nicodim.ocapp.pagination;

/** Target slide viewport in points. */
public record SlideViewport(double widthPoints, double heightPoints) {
    public SlideViewport {
        if (!Double.isFinite(widthPoints) || !Double.isFinite(heightPoints) || widthPoints <= 0 || heightPoints <= 0)
            throw new IllegalArgumentException("Slide viewport must be finite and positive");
    }
}
