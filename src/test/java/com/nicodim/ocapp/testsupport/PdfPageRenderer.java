package com.nicodim.ocapp.testsupport;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

public final class PdfPageRenderer {
    public static final float DPI = 144f;
    public static final int MAX_PAGES = 32;
    public static final long MAX_PIXELS_PER_PAGE = 40_000_000L;

    private PdfPageRenderer() { }

    public static List<Path> render(Path pdf, Path outputDirectory, String prefix) throws IOException {
        Files.createDirectories(outputDirectory);
        List<Path> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int count = document.getNumberOfPages();
            if (count < 1 || count > MAX_PAGES) throw new IOException("PDF page count outside bounds: " + count);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < count; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, DPI, ImageType.RGB);
                long pixels = (long) image.getWidth() * image.getHeight();
                if (pixels > MAX_PIXELS_PER_PAGE) throw new IOException("Rendered page exceeds pixel bound: " + pixels);
                Path target = outputDirectory.resolve(prefix + "-page-" + (page + 1) + ".png");
                if (!ImageIO.write(image, "png", target.toFile())) throw new IOException("PNG writer unavailable");
                pages.add(target);
            }
        }
        return List.copyOf(pages);
    }
}
