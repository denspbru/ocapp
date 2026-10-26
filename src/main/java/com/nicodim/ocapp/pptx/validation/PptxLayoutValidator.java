package com.nicodim.ocapp.pptx.validation;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

/** Deterministic bounded validation over a serialized/reopened presentation. */
public final class PptxLayoutValidator {
    private PptxLayoutValidator() { }

    public static LayoutValidationReport validate(XMLSlideShow show, LayoutValidationPolicy policy, LayoutValidationContext context) {
        Objects.requireNonNull(show, "show"); Objects.requireNonNull(policy, "policy"); Objects.requireNonNull(context, "context");
        Accumulator out = new Accumulator(policy, context);
        Dimension page = show.getPageSize();
        if (page == null || page.width < 72 || page.height < 72 || page.width > 14_400 || page.height > 14_400) {
            out.add(LayoutDiagnosticCode.OUT_OF_SLIDE, LayoutSeverity.ERROR, 0, -1, LayoutShapeDescriptor.of(LayoutRole.OTHER), -1, 72, -1);
            return out.report(show.getSlides().size());
        }
        for (int slideIndex = 0; slideIndex < show.getSlides().size(); slideIndex++) {
            checkInterrupted();
            var slide = show.getSlides().get(slideIndex);
            out.totalArea += (double) page.width * page.height;
            List<Candidate> candidates = new ArrayList<>();
            boolean primary = false;
            for (int ordinal = 0; ordinal < slide.getShapes().size(); ordinal++) {
                checkInterrupted();
                if (out.shapeCount >= policy.maxShapes()) { out.truncate(slideIndex); break; }
                XSLFShape shape = slide.getShapes().get(ordinal); out.shapeCount++;
                LayoutShapeDescriptor descriptor = descriptor(context, slideIndex, shape);
                out.role(descriptor.role());
                Rectangle2D anchor = safeAnchor(shape);
                if (!finite(anchor) || anchor.getWidth() < 0 || anchor.getHeight() < 0) {
                    out.add(LayoutDiagnosticCode.OUT_OF_SLIDE, LayoutSeverity.ERROR, slideIndex, ordinal, descriptor, -1, policy.geometryTolerancePoints(), -1);
                    continue;
                }
                if (descriptor.role() == LayoutRole.FALLBACK_CROP)
                    out.fallbackArea += Math.min(anchor.getWidth() * anchor.getHeight(), (double) page.width * page.height);
                double excursion = excursion(anchor, page);
                if (excursion > policy.geometryTolerancePoints())
                    out.add(LayoutDiagnosticCode.OUT_OF_SLIDE, LayoutSeverity.ERROR, slideIndex, ordinal, descriptor, excursion, policy.geometryTolerancePoints(), -1);
                primary |= descriptor.role().primaryContent() && paints(shape);
                if (shape instanceof XSLFTextShape text) inspectText(text, anchor, descriptor, slideIndex, ordinal, out);
                if (shape instanceof XSLFTable table) inspectTableText(table, descriptor, slideIndex, ordinal, out);
                if (shape instanceof XSLFPictureShape picture) inspectPicture(picture, anchor, descriptor, slideIndex, ordinal, out);
                if (anchor.getWidth() > 0 && anchor.getHeight() > 0 && overlapCandidate(shape, descriptor.role()))
                    candidates.add(new Candidate(ordinal, shape, new Rectangle2D.Double(anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight()), descriptor));
            }
            if (!primary) out.add(LayoutDiagnosticCode.EMPTY_SLIDE, LayoutSeverity.WARN, slideIndex, -1,
                LayoutShapeDescriptor.of(LayoutRole.OTHER), 0, 1, -1);
            inspectOverlaps(candidates, slideIndex, out);
        }
        return out.report(show.getSlides().size());
    }

    private static void inspectText(XSLFTextShape text, Rectangle2D anchor, LayoutShapeDescriptor descriptor,
                                    int slide, int ordinal, Accumulator out) {
        if (++out.textScans > out.policy.maxTextShapes()) { out.truncate(slide); return; }
        String value;
        try { value = text.getText(); } catch (RuntimeException ex) { value = ""; }
        if (value == null || value.isBlank() || descriptor.role() == LayoutRole.TEMPLATE_PLACEHOLDER) return;
        boolean resolved = false;
        for (var paragraph : text.getTextParagraphs()) for (var run : paragraph.getTextRuns()) {
            Double size;
            try { size = run.getFontSize(); } catch (RuntimeException ex) { size = null; }
            if (size == null || !Double.isFinite(size) || size <= 0) continue;
            resolved = true; out.minimumFont = Math.min(out.minimumFont, size);
            double threshold = footer(descriptor.role()) ? out.policy.footerFontPoints() : out.policy.bodyFontPoints();
            if (size < threshold) out.add(LayoutDiagnosticCode.TINY_FONT, LayoutSeverity.WARN,
                slide, ordinal, descriptor, size, threshold, -1);
        }
        try {
            double required = text.getTextHeight();
            double excess = required - anchor.getHeight();
            double ratio = excess / Math.max(1d, anchor.getHeight());
            if (!Double.isFinite(required)) throw new IllegalStateException();
            if (excess > out.policy.overflowTolerancePoints() && ratio > out.policy.overflowToleranceRatio())
                out.add(LayoutDiagnosticCode.TEXT_OVERFLOW, LayoutSeverity.WARN, slide, ordinal, descriptor,
                    excess, Math.max(out.policy.overflowTolerancePoints(), anchor.getHeight() * out.policy.overflowToleranceRatio()), -1);
        } catch (RuntimeException ex) {
            out.add(LayoutDiagnosticCode.MEASUREMENT_UNAVAILABLE, LayoutSeverity.WARN, slide, ordinal, descriptor, resolved ? 1 : 0, 1, -1);
        }
    }

    private static void inspectTableText(XSLFTable table, LayoutShapeDescriptor descriptor,
                                         int slide, int ordinal, Accumulator out) {
        for (int row = 0; row < table.getNumberOfRows(); row++) {
            var cells = table.getRows().get(row).getCells();
            for (int column = 0; column < cells.size(); column++) {
                checkInterrupted();
                XSLFTableCell cell = cells.get(column);
                inspectText(cell, tableCellBounds(table, cell, row, column), descriptor, slide, ordinal, out);
                if (out.textScans >= out.policy.maxTextShapes()) return;
            }
        }
    }

    private static Rectangle2D tableCellBounds(XSLFTable table, XSLFTableCell cell, int row, int column) {
        try {
            double width = 0, height = 0;
            int lastColumn = Math.min(table.getNumberOfColumns(), column + Math.max(1, cell.getGridSpan()));
            int lastRow = Math.min(table.getNumberOfRows(), row + Math.max(1, cell.getRowSpan()));
            for (int current = column; current < lastColumn; current++) width += table.getColumnWidth(current);
            for (int current = row; current < lastRow; current++) height += table.getRowHeight(current);
            return new Rectangle2D.Double(0, 0, width, height);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void inspectPicture(XSLFPictureShape picture, Rectangle2D anchor, LayoutShapeDescriptor descriptor,
                                       int slide, int ordinal, Accumulator out) {
        if (++out.imageScans > out.policy.maxImages()) { out.truncate(slide); return; }
        try {
            var data = picture.getPictureData();
            if (data == null || data.getData() == null) {
                out.add(LayoutDiagnosticCode.BROKEN_REFERENCE, LayoutSeverity.ERROR, slide, ordinal, descriptor, 0, 1, -1); return;
            }
            if (data.getType() == PictureData.PictureType.SVG) return;
            Dimension pixels = data.getImageDimensionInPixels();
            if (pixels == null || pixels.width < 1 || pixels.height < 1 || anchor.getWidth() <= 0 || anchor.getHeight() <= 0) {
                out.add(LayoutDiagnosticCode.MEASUREMENT_UNAVAILABLE, LayoutSeverity.WARN, slide, ordinal, descriptor, 0, 1, -1); return;
            }
            double dpi = Math.min(pixels.width * 72d / anchor.getWidth(), pixels.height * 72d / anchor.getHeight());
            out.minimumRaster = Math.min(out.minimumRaster, dpi);
            if (dpi < out.policy.rasterDpi())
                out.add(LayoutDiagnosticCode.LOW_RES_IMAGE, LayoutSeverity.WARN,
                    slide, ordinal, descriptor, dpi, out.policy.rasterDpi(), -1);
        } catch (RuntimeException ex) {
            out.add(LayoutDiagnosticCode.BROKEN_REFERENCE, LayoutSeverity.ERROR, slide, ordinal, descriptor, 0, 1, -1);
        }
    }

    private static void inspectOverlaps(List<Candidate> candidates, int slide, Accumulator out) {
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.anchor.getX()).thenComparingInt(Candidate::ordinal));
        for (int i = 0; i < candidates.size(); i++) {
            Candidate first = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                checkInterrupted(); Candidate second = candidates.get(j);
                if (second.anchor.getX() >= first.anchor.getMaxX()) break;
                if (out.comparisons++ >= out.policy.maxOverlapComparisons()) { out.truncate(slide); return; }
                if (intentional(first, second, slide, out.context)) continue;
                double width = Math.min(first.anchor.getMaxX(), second.anchor.getMaxX()) - Math.max(first.anchor.getX(), second.anchor.getX());
                double height = Math.min(first.anchor.getMaxY(), second.anchor.getMaxY()) - Math.max(first.anchor.getY(), second.anchor.getY());
                if (width <= 0 || height <= 0) continue;
                double area = width * height;
                double smaller = Math.min(first.anchor.getWidth() * first.anchor.getHeight(), second.anchor.getWidth() * second.anchor.getHeight());
                double ratio = smaller <= 0 ? 0 : area / smaller;
                if (area >= out.policy.overlapAreaPoints() && ratio >= out.policy.overlapRatio()) {
                    out.maximumOverlap = Math.max(out.maximumOverlap, area);
                    out.add(LayoutDiagnosticCode.UNINTENDED_OVERLAP, LayoutSeverity.WARN, slide, first.ordinal,
                        first.descriptor, area, out.policy.overlapAreaPoints(), second.ordinal);
                }
            }
        }
    }

    private static boolean intentional(Candidate a, Candidate b, int slide, LayoutValidationContext context) {
        LayoutRole ar = a.descriptor.role(), br = b.descriptor.role();
        if (ar == LayoutRole.BACKGROUND || br == LayoutRole.BACKGROUND || ar == LayoutRole.TEMPLATE_PLACEHOLDER || br == LayoutRole.TEMPLATE_PLACEHOLDER) return true;
        if ((ar == LayoutRole.VISUAL_SLICE || br == LayoutRole.VISUAL_SLICE)
            && (branding(ar) || branding(br))) return true;
        return context.intended(slide, a.shape, b.shape);
    }
    private static boolean branding(LayoutRole role) { return role == LayoutRole.LOGO || role == LayoutRole.FOOTER || role == LayoutRole.SLIDE_NUMBER; }
    private static boolean footer(LayoutRole role) { return role == LayoutRole.FOOTER || role == LayoutRole.SLIDE_NUMBER; }
    private static boolean overlapCandidate(XSLFShape shape, LayoutRole role) { return role != LayoutRole.TEMPLATE_PLACEHOLDER && paints(shape); }
    private static boolean paints(XSLFShape shape) {
        if (shape instanceof XSLFTextShape text) try { return text.getText() != null && !text.getText().isBlank(); } catch (RuntimeException ex) { return false; }
        return true;
    }
    private static LayoutShapeDescriptor descriptor(LayoutValidationContext context, int slide, XSLFShape shape) {
        LayoutShapeDescriptor value = context.descriptor(slide, shape); if (value != null) return value;
        if (shape.isPlaceholder()) return LayoutShapeDescriptor.of(LayoutRole.TEMPLATE_PLACEHOLDER);
        if (shape instanceof XSLFTable) return LayoutShapeDescriptor.of(LayoutRole.NATIVE_TABLE);
        if (shape instanceof XSLFPictureShape)
            return LayoutShapeDescriptor.of(context.mode() == LayoutMode.VISUAL ? LayoutRole.VISUAL_SLICE : LayoutRole.NATIVE_IMAGE);
        if (shape instanceof XSLFTextShape text) {
            Placeholder p = text.getTextType();
            if (p == Placeholder.TITLE || p == Placeholder.CENTERED_TITLE) return LayoutShapeDescriptor.of(LayoutRole.SEMANTIC_TITLE);
            return LayoutShapeDescriptor.of(LayoutRole.NATIVE_TEXT);
        }
        return LayoutShapeDescriptor.of(LayoutRole.OTHER);
    }
    private static Rectangle2D safeAnchor(XSLFShape shape) { try { return shape.getAnchor(); } catch (RuntimeException ex) { return null; } }
    private static boolean finite(Rectangle2D r) { return r != null && Double.isFinite(r.getX()) && Double.isFinite(r.getY()) && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight()); }
    private static double excursion(Rectangle2D r, Dimension page) { return Math.max(0, Math.max(Math.max(-r.getX(), -r.getY()), Math.max(r.getMaxX() - page.width, r.getMaxY() - page.height))); }
    private static void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("PPTX layout validation interrupted"); }
    private record Candidate(int ordinal, XSLFShape shape, Rectangle2D anchor, LayoutShapeDescriptor descriptor) { }

    private static final class Accumulator {
        final LayoutValidationPolicy policy; final LayoutValidationContext context; final List<LayoutDiagnostic> diagnostics = new ArrayList<>();
        int shapeCount, textScans, imageScans, warningCount, errorCount, suppressed, forcedTruncation;
        int nativeText, nativeTable, nativeImage, nativeGraphic, fallback, visual, background, placeholder, title, logo, footer, number;
        long comparisons; double fallbackArea, totalArea, minimumFont = Double.POSITIVE_INFINITY, minimumRaster = Double.POSITIVE_INFINITY, maximumOverlap;
        final int[] codes = new int[LayoutDiagnosticCode.values().length];
        Accumulator(LayoutValidationPolicy policy, LayoutValidationContext context) { this.policy = policy; this.context = context; }
        void role(LayoutRole role) { switch (role) { case NATIVE_TEXT -> nativeText++; case NATIVE_TABLE -> nativeTable++; case NATIVE_IMAGE -> nativeImage++; case NATIVE_GRAPHIC -> nativeGraphic++; case FALLBACK_CROP -> fallback++; case VISUAL_SLICE -> visual++; case BACKGROUND -> background++; case TEMPLATE_PLACEHOLDER -> placeholder++; case SEMANTIC_TITLE -> title++; case LOGO -> logo++; case FOOTER -> footer++; case SLIDE_NUMBER -> number++; default -> { } } }
        void add(LayoutDiagnosticCode code, LayoutSeverity severity, int slide, int shape, LayoutShapeDescriptor descriptor, double actual, double threshold, int related) {
            codes[code.ordinal()]++; if (severity == LayoutSeverity.ERROR) errorCount++; else warningCount++;
            if (diagnostics.size() < policy.maxDiagnostics() - 1 || code == LayoutDiagnosticCode.DIAGNOSTICS_TRUNCATED)
                diagnostics.add(new LayoutDiagnostic(1, code, severity, slide, shape, descriptor.role(), descriptor.provenance(), actual, threshold, related));
            else suppressed++;
        }
        void truncate(int slide) { if (forcedTruncation++ == 0) add(LayoutDiagnosticCode.DIAGNOSTICS_TRUNCATED, LayoutSeverity.WARN, slide, -1, LayoutShapeDescriptor.of(LayoutRole.OTHER), 1, 0, -1); }
        LayoutValidationReport report(int slides) {
            boolean truncated = forcedTruncation > 0 || suppressed > 0;
            if (suppressed > 0 && forcedTruncation == 0) truncate(Math.max(0, slides - 1));
            double ratio = totalArea <= 0 ? 0 : Math.min(1, fallbackArea / totalArea);
            LayoutValidationMetrics m = new LayoutValidationMetrics(1, context.mode(), slides, shapeCount,
                nativeText, nativeTable, nativeImage, nativeGraphic, fallback, visual, background, placeholder, title, logo, footer, number,
                ratio, context.forcedBreakCount(), context.paginationWarningCount(), warningCount, errorCount,
                codes[0], codes[1], codes[2], codes[3], codes[4], codes[5], codes[6], codes[7], codes[8],
                Double.isInfinite(minimumFont) ? 0 : minimumFont, Double.isInfinite(minimumRaster) ? 0 : minimumRaster,
                maximumOverlap, diagnostics.size(), suppressed, truncated);
            return new LayoutValidationReport(1, diagnostics, m);
        }
    }
}
