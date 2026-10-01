package com.nicodim.ocapp.pagemodel;
public record TransformSummary(boolean transformed, String matrix, double rotationDegrees, double scaleX, double scaleY) {
    public TransformSummary { matrix = matrix == null ? "none" : matrix; }
    public static TransformSummary none() { return new TransformSummary(false,"none",0,1,1); }
}
