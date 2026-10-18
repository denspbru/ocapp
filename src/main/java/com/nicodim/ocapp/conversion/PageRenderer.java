package com.nicodim.ocapp.conversion;

import java.net.URI;

@FunctionalInterface
public interface PageRenderer {
    byte[] render(URI uri, OutputFormat format);

    /** Performs format-specific local validation under conversion capacity, before URL or network work. */
    default void preflight(OutputFormat format) { }

    default byte[] render(URI uri, OutputFormat format, RenderContext context) {
        return render(uri, format);
    }
}
