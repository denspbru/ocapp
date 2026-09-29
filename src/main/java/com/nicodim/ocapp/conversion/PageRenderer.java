package com.nicodim.ocapp.conversion;

import java.net.URI;

@FunctionalInterface
public interface PageRenderer {
    byte[] render(URI uri, OutputFormat format);
}
