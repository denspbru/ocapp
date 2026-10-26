package com.nicodim.ocapp.testsupport;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class VisualImageComparator {
    public static final long MAX_PIXELS = 40_000_000L;
    private VisualImageComparator() { }

    public static Comparison compare(Path approved, Path candidate, Path targetRoot, Path artifactDirectory,
                                     int channelTolerance, double allowedChangedRatio) throws IOException {
        Path root = targetRoot.toAbsolutePath().normalize();
        Path actual = candidate.toAbsolutePath().normalize();
        Path artifacts = artifactDirectory.toAbsolutePath().normalize();
        if (!actual.startsWith(root) || !artifacts.startsWith(root))
            throw new IOException("Candidates and diff artifacts must stay under target: " + root);
        BufferedImage expected = read(approved, "approved baseline");
        BufferedImage observed = read(candidate, "candidate");
        if (expected.getWidth() != observed.getWidth() || expected.getHeight() != observed.getHeight())
            return writeDimensionFailure(expected, observed, artifacts);
        long pixels = (long) expected.getWidth() * expected.getHeight();
        if (pixels > MAX_PIXELS) throw new IOException("Comparison exceeds pixel bound: " + pixels);
        BufferedImage diff = new BufferedImage(expected.getWidth(), expected.getHeight(), BufferedImage.TYPE_INT_RGB);
        long changed = 0;
        int maximumDelta = 0;
        for (int y = 0; y < expected.getHeight(); y++) for (int x = 0; x < expected.getWidth(); x++) {
            int e = expected.getRGB(x, y), o = observed.getRGB(x, y);
            int delta = Math.max(Math.max(Math.abs(((e >>> 16) & 255) - ((o >>> 16) & 255)),
                Math.abs(((e >>> 8) & 255) - ((o >>> 8) & 255))), Math.abs((e & 255) - (o & 255)));
            maximumDelta = Math.max(maximumDelta, delta);
            boolean differs = delta > channelTolerance;
            if (differs) changed++;
            int shade = differs ? Math.max(64, delta) : 0;
            diff.setRGB(x, y, new Color(shade, 0, 0).getRGB());
        }
        double ratio = changed / (double) pixels;
        boolean passed = ratio <= allowedChangedRatio;
        Path diffPath = artifacts.resolve("diff.png"), metadata = artifacts.resolve("comparison.properties");
        Files.createDirectories(artifacts);
        if (!ImageIO.write(diff, "png", diffPath.toFile())) throw new IOException("PNG writer unavailable");
        String report = "format=1\nwidth=" + expected.getWidth() + "\nheight=" + expected.getHeight()
            + "\nchanged.pixels=" + changed + "\nchanged.pixel.ratio=" + Double.toString(ratio)
            + "\nmaximum.channel.delta=" + maximumDelta + "\nchannel.tolerance=" + channelTolerance
            + "\nallowed.changed.pixel.ratio=" + Double.toString(allowedChangedRatio) + "\npassed=" + passed + "\n";
        Files.writeString(metadata, report, StandardCharsets.UTF_8);
        return new Comparison(passed, changed, pixels, ratio, maximumDelta, diffPath, metadata);
    }

    private static Comparison writeDimensionFailure(BufferedImage expected, BufferedImage actual, Path artifacts) throws IOException {
        Files.createDirectories(artifacts);
        Path metadata = artifacts.resolve("comparison.properties");
        Files.writeString(metadata, "format=1\nexpected.width=" + expected.getWidth() + "\nexpected.height=" + expected.getHeight()
            + "\nactual.width=" + actual.getWidth() + "\nactual.height=" + actual.getHeight() + "\npassed=false\n", StandardCharsets.UTF_8);
        return new Comparison(false, -1, -1, 1d, 255, null, metadata);
    }

    private static BufferedImage read(Path file, String role) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException(role + " is missing: " + file);
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IOException(role + " is not a supported image: " + file);
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels < 1 || pixels > MAX_PIXELS) throw new IOException(role + " dimensions outside bounds: " + pixels);
        return image;
    }

    public record Comparison(boolean passed, long changedPixels, long totalPixels, double changedRatio,
                             int maximumChannelDelta, Path diff, Path metadata) { }
}
