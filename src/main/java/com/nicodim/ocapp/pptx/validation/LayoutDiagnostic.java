package com.nicodim.ocapp.pptx.validation;

import java.util.Objects;

/** Content-free diagnostic. relatedShapeOrdinal is -1 when not pairwise. */
public record LayoutDiagnostic(int schemaVersion, LayoutDiagnosticCode code, LayoutSeverity severity,
                               int slideIndex, int shapeOrdinal, LayoutRole role, LayoutProvenance provenance,
                               double actual, double threshold, int relatedShapeOrdinal) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public LayoutDiagnostic {
        if (schemaVersion != CURRENT_SCHEMA_VERSION || slideIndex < 0 || shapeOrdinal < -1 || relatedShapeOrdinal < -1)
            throw new IllegalArgumentException("Invalid diagnostic fields");
        Objects.requireNonNull(code, "code"); Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(role, "role"); Objects.requireNonNull(provenance, "provenance");
        if (!Double.isFinite(actual) || !Double.isFinite(threshold)) throw new IllegalArgumentException("Diagnostic measurements must be finite");
    }
    public String publicCode() { return code.publicCode(); }
}
