package com.nicodim.ocapp.pagination;

/** Stable, renderer-independent reason for a vertical screenshot boundary. */
public enum BreakRationale {
    EXPLICIT_HINT,
    WHITESPACE,
    BLOCK_BOUNDARY,
    FALLBACK,
    DOCUMENT_END
}
