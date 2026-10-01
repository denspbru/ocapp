package com.nicodim.ocapp.pagemodel;
public record ListSemantics(boolean ordered, int start, int level, String marker) { public ListSemantics { marker = marker == null ? "" : marker; } }
