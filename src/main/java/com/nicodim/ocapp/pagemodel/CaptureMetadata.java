package com.nicodim.ocapp.pagemodel;
import java.util.List;
public record CaptureMetadata(String userAgent, String locale, String timezone, double deviceScaleFactor,
                              String readiness, List<String> observations) {
    public CaptureMetadata { userAgent = safe(userAgent); locale = safe(locale); timezone = safe(timezone); readiness = safe(readiness); observations = List.copyOf(observations == null ? List.of() : observations); }
    private static String safe(String value) { return value == null ? "" : value; }
}
