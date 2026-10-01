package com.nicodim.ocapp.pagemodel;
public record TextRun(String text, ComputedStyle style) { public TextRun { text = text == null ? "" : text; } }
