package com.nicodim.ocapp.pagemodel;
import java.net.URI;
public record AssetReference(String id, Kind kind, URI uri, String mediaType, long estimatedBytes, boolean embedded) {
    public enum Kind { IMAGE, SVG, CANVAS, CHART, OTHER }
    public AssetReference { mediaType = mediaType == null ? "" : mediaType; }
}
