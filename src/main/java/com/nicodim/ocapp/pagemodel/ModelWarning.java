package com.nicodim.ocapp.pagemodel;
public record ModelWarning(String code, String blockId, String detail) {
    public ModelWarning { code=code == null ? "UNKNOWN" : code; blockId=blockId == null ? "" : blockId; detail=detail == null ? "" : detail; }
}
