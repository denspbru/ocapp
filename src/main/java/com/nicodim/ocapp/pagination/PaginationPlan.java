package com.nicodim.ocapp.pagination;

import java.util.List;

public record PaginationPlan(double sourceWidth, double sourceHeight, List<SlideSlice> slices,
                             List<PaginationWarning> warnings) {
    public PaginationPlan {
        slices = List.copyOf(slices == null ? List.of() : slices);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (!Double.isFinite(sourceWidth) || !Double.isFinite(sourceHeight) || sourceWidth <= 0 || sourceHeight <= 0
            || slices.isEmpty() || warnings.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Pagination plan fields are invalid");
        double cursor = 0;
        for (int i = 0; i < slices.size(); i++) {
            SlideSlice slice = slices.get(i);
            if (slice == null || slice.index() != i || Math.abs(slice.source().x()) > 0.000_001
                || Math.abs(slice.source().width() - sourceWidth) > 0.000_001
                || Math.abs(slice.source().y() - cursor) > 0.000_001)
                throw new IllegalArgumentException("Pagination plan coverage is invalid");
            cursor = slice.source().bottom();
        }
        if (Math.abs(cursor - sourceHeight) > 0.000_001) throw new IllegalArgumentException("Pagination plan coverage is incomplete");
    }
}
