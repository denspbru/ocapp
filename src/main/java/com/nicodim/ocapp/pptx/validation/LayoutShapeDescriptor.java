package com.nicodim.ocapp.pptx.validation;

import java.util.Objects;

public record LayoutShapeDescriptor(LayoutRole role, LayoutProvenance provenance) {
    public static final int SCHEMA_VERSION = 1;
    public LayoutShapeDescriptor { Objects.requireNonNull(role, "role"); Objects.requireNonNull(provenance, "provenance"); }
    public static LayoutShapeDescriptor of(LayoutRole role) { return new LayoutShapeDescriptor(role, provenance(role)); }
    private static LayoutProvenance provenance(LayoutRole role) {
        return switch (role) {
            case NATIVE_TEXT, NATIVE_TABLE, NATIVE_IMAGE, NATIVE_GRAPHIC, BACKGROUND, SEMANTIC_TITLE -> LayoutProvenance.NATIVE;
            case FALLBACK_CROP -> LayoutProvenance.FALLBACK;
            case VISUAL_SLICE -> LayoutProvenance.VISUAL;
            case LOGO, FOOTER, SLIDE_NUMBER -> LayoutProvenance.BRANDING;
            case TEMPLATE_PLACEHOLDER -> LayoutProvenance.TEMPLATE;
            default -> LayoutProvenance.UNKNOWN;
        };
    }
}
