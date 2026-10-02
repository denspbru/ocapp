package com.nicodim.ocapp.pagination;

public record PaginationOptions(double slideWidthPoints, double slideHeightPoints,
                                double minimumSliceHeight, int maxSlides) {
    public PaginationOptions {
        if (!positive(slideWidthPoints) || !positive(slideHeightPoints) || !positive(minimumSliceHeight) || maxSlides < 1)
            throw new IllegalArgumentException("Pagination options must be positive and finite");
    }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
