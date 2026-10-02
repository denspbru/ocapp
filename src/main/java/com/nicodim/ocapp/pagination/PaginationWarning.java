package com.nicodim.ocapp.pagination;

/** Warning codes are deliberately content-free so they are safe to expose in diagnostics. */
public record PaginationWarning(String code, int slideIndex) {
    public PaginationWarning {
        if (code == null || code.isBlank() || slideIndex < 0)
            throw new IllegalArgumentException("Pagination warning fields are invalid");
    }
}
