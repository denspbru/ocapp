package com.nicodim.ocapp.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public record VisualBaselineManifest(String fixture, String rendererVersion, int dpi, int pageCount,
                                     int channelTolerance, double changedPixelRatio,
                                     String baselinePrefix, Properties properties) {
    public static VisualBaselineManifest load(InputStream input) throws IOException {
        if (input == null) throw new IOException("Visual baseline manifest is missing");
        Properties values = new Properties();
        try (input) { values.load(input); }
        require(values, "format", "1");
        String fixture = required(values, "fixture");
        String renderer = required(values, "renderer.version");
        int dpi = integer(values, "dpi", 1, 1200);
        int pages = integer(values, "pages", 1, PdfPageRenderer.MAX_PAGES);
        int tolerance = integer(values, "channel.tolerance", 0, 255);
        double ratio;
        try { ratio = Double.parseDouble(required(values, "changed.pixel.ratio")); }
        catch (NumberFormatException ex) { throw new IOException("Invalid changed.pixel.ratio", ex); }
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) throw new IOException("changed.pixel.ratio outside [0,1]");
        String prefix = required(values, "baseline.prefix");
        for (int page = 1; page <= pages; page++) required(values, "page." + page + ".sha256");
        return new VisualBaselineManifest(fixture, renderer, dpi, pages, tolerance, ratio, prefix, values);
    }

    public String expectedSha256(int page) throws IOException { return required(properties, "page." + page + ".sha256"); }
    public String baselineResource(int page) { return baselinePrefix + "-page-" + page + ".png"; }

    public void verifyBaseline(int page, Path baseline) throws IOException {
        if (!Files.isRegularFile(baseline)) throw new IOException("Approved baseline is missing: " + baseline);
        String actual = sha256(baseline);
        String expected = expectedSha256(page);
        if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("Approved baseline checksum mismatch for page " + page + ": expected=" + expected + " actual=" + actual);
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static int integer(Properties values, String key, int minimum, int maximum) throws IOException {
        int value;
        try { value = Integer.parseInt(required(values, key)); }
        catch (NumberFormatException ex) { throw new IOException("Invalid integer: " + key, ex); }
        if (value < minimum || value > maximum) throw new IOException(key + " outside bounds");
        return value;
    }
    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IOException("Visual baseline manifest field is missing: " + key);
        return value;
    }
    private static void require(Properties values, String key, String expected) throws IOException {
        String actual = required(values, key);
        if (!expected.equals(actual)) throw new IOException("Unsupported " + key + ": " + actual);
    }
}
