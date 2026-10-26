package com.nicodim.ocapp.pptx.validation;

/** Stable internal codes; public metadata adds the PPTX_LAYOUT_ prefix. */
public enum LayoutDiagnosticCode {
    OUT_OF_SLIDE,
    TEXT_OVERFLOW,
    UNINTENDED_OVERLAP,
    TINY_FONT,
    EMPTY_SLIDE,
    LOW_RES_IMAGE,
    BROKEN_REFERENCE,
    MEASUREMENT_UNAVAILABLE,
    DIAGNOSTICS_TRUNCATED;

    public static final int SCHEMA_VERSION = 1;
    public String publicCode() { return "PPTX_LAYOUT_" + name(); }
}
