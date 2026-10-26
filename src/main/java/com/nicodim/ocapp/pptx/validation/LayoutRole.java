package com.nicodim.ocapp.pptx.validation;

/** Stable content-free roles used for validation and aggregate metrics. */
public enum LayoutRole {
    NATIVE_TEXT, NATIVE_TABLE, NATIVE_IMAGE, NATIVE_GRAPHIC,
    FALLBACK_CROP, VISUAL_SLICE, BACKGROUND, TEMPLATE_PLACEHOLDER,
    SEMANTIC_TITLE, LOGO, FOOTER, SLIDE_NUMBER, OTHER;
    public static final int SCHEMA_VERSION = 1;
    public boolean primaryContent() {
        return switch (this) {
            case NATIVE_TEXT, NATIVE_TABLE, NATIVE_IMAGE, NATIVE_GRAPHIC,
                 FALLBACK_CROP, VISUAL_SLICE, SEMANTIC_TITLE -> true;
            default -> false;
        };
    }
}
