package com.nicodim.ocapp.pptx.validation;

import java.util.List;
import java.util.Objects;

public record LayoutValidationReport(int schemaVersion, List<LayoutDiagnostic> diagnostics, LayoutValidationMetrics metrics) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public LayoutValidationReport {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported report version");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(metrics, "metrics");
    }
    public boolean blocking() { return metrics.errorCount() > 0; }
    public boolean valid() { return !blocking(); }
}
