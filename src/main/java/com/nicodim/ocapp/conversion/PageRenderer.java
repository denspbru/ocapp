package com.nicodim.ocapp.conversion;

import java.net.URI;

@FunctionalInterface
public interface PageRenderer {
    byte[] render(URI uri, OutputFormat format);

    default byte[] render(URI uri, OutputFormat format, RenderContext context) {
        return render(uri, format);
    }
}
