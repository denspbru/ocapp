package com.nicodim.ocapp.pagination;

import java.util.List;

public record SlideSlice(int index, SourceBounds source, SlideViewport viewport,
                         BreakRationale breakRationale, List<PaginationWarning> warnings) {
    public SlideSlice {
        if (index < 0 || source == null || viewport == null || breakRationale == null)
            throw new IllegalArgumentException("Slide slice fields are invalid");
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (warnings.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Slide warnings contain null");
    }
}
