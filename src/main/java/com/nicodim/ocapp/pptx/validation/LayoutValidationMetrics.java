package com.nicodim.ocapp.pptx.validation;

/** Fixed-field aggregate metrics; no user-controlled labels are accepted. */
public record LayoutValidationMetrics(int schemaVersion, LayoutMode mode, int slideCount, int shapeCount,
        int nativeTextCount, int nativeTableCount, int nativeImageCount, int nativeGraphicCount,
        int fallbackCount, int visualCount, int backgroundCount, int templatePlaceholderCount,
        int semanticTitleCount, int logoCount, int footerCount, int slideNumberCount,
        double fallbackAreaRatio, int forcedBreakCount, int paginationWarningCount,
        int warningCount, int errorCount, int outOfSlideCount, int textOverflowCount,
        int unintendedOverlapCount, int tinyFontCount, int emptySlideCount, int lowResImageCount,
        int brokenReferenceCount, int measurementUnavailableCount, int diagnosticsTruncatedCount,
        double minimumFontPoints, double minimumRasterDpi, double maximumUnintendedOverlapPoints,
        int diagnosticCount, int suppressedDiagnosticCount, boolean truncated) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public LayoutValidationMetrics {
        if (schemaVersion != 1 || mode == null || slideCount < 0 || shapeCount < 0 || warningCount < 0 || errorCount < 0
            || diagnosticCount < 0 || suppressedDiagnosticCount < 0 || !Double.isFinite(fallbackAreaRatio)
            || fallbackAreaRatio < 0 || fallbackAreaRatio > 1 || !Double.isFinite(minimumFontPoints)
            || !Double.isFinite(minimumRasterDpi) || !Double.isFinite(maximumUnintendedOverlapPoints))
            throw new IllegalArgumentException("Invalid validation metrics");
    }
}
