package com.nicodim.ocapp.pptx.validation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.xslf.usermodel.XSLFShape;

/** Immutable renderer-to-validator bridge keyed by stable POI shape IDs. */
public final class LayoutValidationContext {
    public static final int SCHEMA_VERSION = 1;
    private final LayoutMode mode;
    private final int forcedBreakCount;
    private final int paginationWarningCount;
    private final Map<Key, LayoutShapeDescriptor> descriptors;
    private final Set<Pair> intendedOverlaps;

    private LayoutValidationContext(Builder builder) {
        mode = builder.mode; forcedBreakCount = builder.forcedBreakCount; paginationWarningCount = builder.paginationWarningCount;
        descriptors = Map.copyOf(builder.descriptors); intendedOverlaps = Set.copyOf(builder.intendedOverlaps);
    }
    public static Builder builder(LayoutMode mode) { return new Builder(mode); }
    public LayoutMode mode() { return mode; }
    public int forcedBreakCount() { return forcedBreakCount; }
    public int paginationWarningCount() { return paginationWarningCount; }
    public LayoutShapeDescriptor descriptor(int slideIndex, XSLFShape shape) { return descriptors.get(new Key(slideIndex, shape.getShapeId())); }
    public boolean intended(int slideIndex, XSLFShape first, XSLFShape second) {
        return intendedOverlaps.contains(Pair.of(slideIndex, first.getShapeId(), second.getShapeId()));
    }

    public static final class Builder {
        private final LayoutMode mode;
        private int forcedBreakCount, paginationWarningCount;
        private final Map<Key, LayoutShapeDescriptor> descriptors = new HashMap<>();
        private final Set<Pair> intendedOverlaps = new HashSet<>();
        private Builder(LayoutMode mode) { this.mode = Objects.requireNonNull(mode, "mode"); }
        public Builder pagination(int forcedBreaks, int warnings) {
            if (forcedBreaks < 0 || warnings < 0) throw new IllegalArgumentException("Pagination metrics cannot be negative");
            forcedBreakCount = forcedBreaks; paginationWarningCount = warnings; return this;
        }
        public Builder register(int slideIndex, XSLFShape shape, LayoutRole role) {
            if (slideIndex < 0) throw new IllegalArgumentException("Slide index cannot be negative");
            descriptors.put(new Key(slideIndex, Objects.requireNonNull(shape, "shape").getShapeId()), LayoutShapeDescriptor.of(role)); return this;
        }
        public Builder intendedOverlap(int slideIndex, XSLFShape first, XSLFShape second) {
            intendedOverlaps.add(Pair.of(slideIndex, first.getShapeId(), second.getShapeId())); return this;
        }
        public LayoutValidationContext build() { return new LayoutValidationContext(this); }
    }
    private record Key(int slide, int shapeId) { }
    private record Pair(int slide, int low, int high) {
        static Pair of(int slide, int first, int second) { return new Pair(slide, Math.min(first, second), Math.max(first, second)); }
    }
}
