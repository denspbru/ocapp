package com.nicodim.ocapp.pptx.validation;

/** Immutable versioned thresholds and scan budgets. */
public record LayoutValidationPolicy(int schemaVersion, int maxDiagnostics, int maxShapes, int maxTextShapes,
        int maxImages, long maxOverlapComparisons, double geometryTolerancePoints,
        double overflowTolerancePoints, double overflowToleranceRatio, double bodyFontPoints,
        double footerFontPoints, double rasterDpi,
        double overlapAreaPoints, double overlapRatio) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public LayoutValidationPolicy {
        if (schemaVersion != CURRENT_SCHEMA_VERSION || maxDiagnostics < 1 || maxShapes < 1 || maxTextShapes < 1
            || maxImages < 1 || maxOverlapComparisons < 0) throw new IllegalArgumentException("Invalid validation budgets");
        double[] values = {geometryTolerancePoints, overflowTolerancePoints, overflowToleranceRatio, bodyFontPoints,
            footerFontPoints, rasterDpi, overlapAreaPoints, overlapRatio};
        for (double value : values) if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid validation threshold");
        if (overflowToleranceRatio > 1 || overlapRatio > 1 || footerFontPoints > bodyFontPoints)
            throw new IllegalArgumentException("Invalid validation threshold relationship");
    }
    public static LayoutValidationPolicy standard() {
        return new LayoutValidationPolicy(1, 200, 10_000, 5_000, 2_000, 100_000, .02, 1, .02, 8, 6, 96, 4, .05);
    }
}
