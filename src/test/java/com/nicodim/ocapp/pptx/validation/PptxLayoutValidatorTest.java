package com.nicodim.ocapp.pptx.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

class PptxLayoutValidatorTest {
    @Test void preservesPointZeroTwoBoundaryAndBlocksOnlyBeyondIt() throws Exception {
        try (var show = show()) {
            var slide = show.createSlide();
            var shape = slide.createAutoShape();
            shape.setAnchor(new Rectangle2D.Double(-.02, 10, 20, 20));
            assertThat(validate(show).diagnostics()).extracting(LayoutDiagnostic::code)
                .doesNotContain(LayoutDiagnosticCode.OUT_OF_SLIDE);
            shape.setAnchor(new Rectangle2D.Double(-.0201, 10, 20, 20));
            var report = validate(show);
            assertThat(report.blocking()).isTrue();
            assertThat(report.diagnostics()).extracting(LayoutDiagnostic::publicCode)
                .contains("PPTX_LAYOUT_OUT_OF_SLIDE");
        }
    }

    @Test void invalidPageGeometryIsBlockingAndContentFree() throws Exception {
        try (var show = new XMLSlideShow()) {
            show.setPageSize(new Dimension(0, 540));
            var report = validate(show);
            assertThat(report.blocking()).isTrue();
            assertThat(report.diagnostics()).singleElement().satisfies(d -> {
                assertThat(d.code()).isEqualTo(LayoutDiagnosticCode.OUT_OF_SLIDE);
                assertThat(d.slideIndex()).isZero();
                assertThat(d.actual()).isEqualTo(-1);
            });
        }
    }

    @Test void detectsOverflowTinyFontLowResolutionOverlapAndEmptySlidesAsBoundedWarnings() throws Exception {
        try (var show = show()) {
            var slide = show.createSlide();
            var text = slide.createTextBox();
            text.setAnchor(new Rectangle2D.Double(10, 10, 80, 4));
            text.setText("private page text").setFontSize(36d);
            var tiny = slide.createTextBox(); tiny.setText("small").setFontSize(3d);
            tiny.setAnchor(new Rectangle2D.Double(10, 30, 50, 20));
            var picture = slide.createPicture(show.addPicture(png(2, 2), PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(100, 10, 144, 144));
            var overlap = slide.createTextBox(); overlap.setText("other").setFontSize(12d);
            overlap.setAnchor(new Rectangle2D.Double(120, 10, 80, 80));
            show.createSlide();
            var report = validate(show);
            assertThat(report.diagnostics()).extracting(LayoutDiagnostic::code)
                .contains(LayoutDiagnosticCode.TEXT_OVERFLOW, LayoutDiagnosticCode.TINY_FONT,
                    LayoutDiagnosticCode.LOW_RES_IMAGE, LayoutDiagnosticCode.UNINTENDED_OVERLAP,
                    LayoutDiagnosticCode.EMPTY_SLIDE);
            assertThat(report.diagnostics()).allSatisfy(d -> assertThat(d.toString()).doesNotContain("private page text"));
            assertThat(report.metrics().minimumFontPoints()).isEqualTo(3);
            assertThat(report.metrics().minimumRasterDpi()).isPositive().isLessThan(96);
            assertThat(report.metrics().slideCount()).isEqualTo(2);
            var fallbackContext = LayoutValidationContext.builder(LayoutMode.EDITABLE)
                .register(0, picture, LayoutRole.FALLBACK_CROP).build();
            var fallbackReport = PptxLayoutValidator.validate(show, LayoutValidationPolicy.standard(), fallbackContext);
            assertThat(fallbackReport.metrics().fallbackCount()).isOne();
            assertThat(fallbackReport.metrics().fallbackAreaRatio()).isPositive().isLessThanOrEqualTo(1);
        }
    }

    @Test void roleContextSuppressesIntentionalOverlapAndCountsRendererMetrics() throws Exception {
        try (var show = show()) {
            var slide = show.createSlide();
            var visual = slide.createPicture(show.addPicture(png(32, 18), PictureType.PNG));
            visual.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));
            var logo = slide.createPicture(show.addPicture(png(8, 4), PictureType.PNG));
            logo.setAnchor(new Rectangle2D.Double(10, 10, 40, 20));
            var fallback = slide.createPicture(show.addPicture(png(16, 9), PictureType.PNG));
            fallback.setAnchor(new Rectangle2D.Double(200, 120, 180, 100));
            var nativeText = slide.createTextBox(); nativeText.setText("covered native shape").setFontSize(12d);
            nativeText.setAnchor(new Rectangle2D.Double(220, 140, 120, 40));
            var builder = LayoutValidationContext.builder(LayoutMode.VISUAL)
                .pagination(2, 1).register(0, visual, LayoutRole.VISUAL_SLICE).register(0, logo, LayoutRole.LOGO)
                .register(0, fallback, LayoutRole.FALLBACK_CROP).register(0, nativeText, LayoutRole.NATIVE_TEXT)
                .intendedOverlap(0, fallback, nativeText);
            var report = PptxLayoutValidator.validate(show, LayoutValidationPolicy.standard(), builder.build());
            assertThat(report.diagnostics()).extracting(LayoutDiagnostic::code)
                .doesNotContain(LayoutDiagnosticCode.UNINTENDED_OVERLAP, LayoutDiagnosticCode.EMPTY_SLIDE);
            assertThat(report.metrics().mode()).isEqualTo(LayoutMode.VISUAL);
            assertThat(report.metrics().visualCount()).isOne();
            assertThat(report.metrics().logoCount()).isOne();
            assertThat(report.metrics().fallbackCount()).isOne();
            assertThat(report.metrics().nativeTextCount()).isOne();
            assertThat(report.metrics().forcedBreakCount()).isEqualTo(2);
            assertThat(report.metrics().paginationWarningCount()).isOne();
        }
    }

    @Test void validatesTableCellTextWithoutTreatingCellsAsOverlappingShapes() throws Exception {
        try (var show = show()) {
            var slide = show.createSlide();
            var table = slide.createTable(1, 2);
            table.setAnchor(new Rectangle2D.Double(20, 20, 240, 12));
            table.setColumnWidth(0, 120); table.setColumnWidth(1, 120);
            table.getCell(0, 0).setText("tiny table text").setFontSize(3d);
            table.getCell(0, 1).setText("overflowing\ntable\ntext\nwith\nmultiple\nlines").setFontSize(36d);
            table.setRowHeight(0, 12);
            var report = PptxLayoutValidator.validate(show, LayoutValidationPolicy.standard(),
                LayoutValidationContext.builder(LayoutMode.EDITABLE).register(0, table, LayoutRole.NATIVE_TABLE).build());
            assertThat(report.diagnostics()).extracting(LayoutDiagnostic::code)
                .contains(LayoutDiagnosticCode.TINY_FONT, LayoutDiagnosticCode.TEXT_OVERFLOW)
                .doesNotContain(LayoutDiagnosticCode.UNINTENDED_OVERLAP);
            assertThat(report.diagnostics()).filteredOn(d -> d.code() == LayoutDiagnosticCode.TINY_FONT)
                .allSatisfy(d -> {
                    assertThat(d.shapeOrdinal()).isZero();
                    assertThat(d.role()).isEqualTo(LayoutRole.NATIVE_TABLE);
                    assertThat(d.provenance()).isEqualTo(LayoutProvenance.NATIVE);
                });
            assertThat(report.metrics().nativeTableCount()).isOne();
            assertThat(report.metrics().minimumFontPoints()).isEqualTo(3);
        }
    }

    @Test void stableShapeIdRoleAndProvenanceSurviveSerializationAndReopen() throws Exception {
        byte[] bytes;
        LayoutValidationContext context;
        try (var show = show(); var output = new ByteArrayOutputStream()) {
            var slide = show.createSlide();
            var fallback = slide.createPicture(show.addPicture(png(2, 2), PictureType.PNG));
            fallback.setAnchor(new Rectangle2D.Double(20, 20, 144, 144));
            context = LayoutValidationContext.builder(LayoutMode.EDITABLE)
                .register(0, fallback, LayoutRole.FALLBACK_CROP).build();
            show.write(output); bytes = output.toByteArray();
        }
        try (var reopened = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            var report = PptxLayoutValidator.validate(reopened, LayoutValidationPolicy.standard(), context);
            assertThat(report.metrics().fallbackCount()).isOne();
            assertThat(report.metrics().nativeImageCount()).isZero();
            assertThat(report.diagnostics()).filteredOn(d -> d.code() == LayoutDiagnosticCode.LOW_RES_IMAGE)
                .singleElement().satisfies(d -> {
                    assertThat(d.role()).isEqualTo(LayoutRole.FALLBACK_CROP);
                    assertThat(d.provenance()).isEqualTo(LayoutProvenance.FALLBACK);
                });
        }
    }

    @Test void shapeDiagnosticAndComparisonBudgetsTruncateDeterministically() throws Exception {
        try (var show = show()) {
            var slide = show.createSlide();
            for (int i = 0; i < 8; i++) {
                var shape = slide.createTextBox(); shape.setText("x").setFontSize(12d);
                shape.setAnchor(new Rectangle2D.Double(10, 10, 20, 20));
            }
            var policy = new LayoutValidationPolicy(1, 3, 4, 4, 4, 1, .02, 1, .02, 8, 6, 96, 4, .05);
            var first = PptxLayoutValidator.validate(show, policy, context());
            var second = PptxLayoutValidator.validate(show, policy, context());
            assertThat(first).isEqualTo(second);
            assertThat(first.metrics().shapeCount()).isEqualTo(4);
            assertThat(first.metrics().truncated()).isTrue();
            assertThat(first.diagnostics()).hasSizeLessThanOrEqualTo(3);
            assertThat(first.diagnostics()).extracting(LayoutDiagnostic::code)
                .contains(LayoutDiagnosticCode.DIAGNOSTICS_TRUNCATED);
        }
    }

    @Test void interruptionIsPromptAndPreservesInterruptFlag() throws Exception {
        try (var show = show()) {
            show.createSlide().createAutoShape().setAnchor(new Rectangle2D.Double(1, 1, 10, 10));
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> validate(show)).isInstanceOf(CancellationException.class)
                    .hasMessage("PPTX layout validation interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    @Test void policyReportDiagnosticContextAndDescriptorsValidateAndRemainImmutable() throws Exception {
        assertThat(LayoutValidationPolicy.standard().schemaVersion()).isEqualTo(1);
        assertThat(LayoutDiagnosticCode.TEXT_OVERFLOW.publicCode()).isEqualTo("PPTX_LAYOUT_TEXT_OVERFLOW");
        assertThat(LayoutRole.NATIVE_TEXT.primaryContent()).isTrue();
        assertThat(LayoutRole.FOOTER.primaryContent()).isFalse();
        assertThat(LayoutShapeDescriptor.of(LayoutRole.FALLBACK_CROP).provenance()).isEqualTo(LayoutProvenance.FALLBACK);
        assertThatThrownBy(() -> new LayoutValidationPolicy(2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LayoutValidationContext.builder(LayoutMode.EDITABLE).pagination(-1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LayoutDiagnostic(1, LayoutDiagnosticCode.EMPTY_SLIDE, LayoutSeverity.WARN,
            -1, -1, LayoutRole.OTHER, LayoutProvenance.UNKNOWN, 0, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        try (var show = show()) {
            var text = show.createSlide().createTextBox(); text.setText("x").setFontSize(12d);
            text.setAnchor(new Rectangle2D.Double(1, 1, 20, 20));
            var report = validate(show);
            var source = new ArrayList<LayoutDiagnostic>();
            source.add(new LayoutDiagnostic(1, LayoutDiagnosticCode.EMPTY_SLIDE, LayoutSeverity.WARN,
                0, -1, LayoutRole.OTHER, LayoutProvenance.UNKNOWN, 0, 1, -1));
            var copied = new LayoutValidationReport(1, source, report.metrics()); source.clear();
            assertThat(copied.diagnostics()).hasSize(1);
            assertThatThrownBy(() -> copied.diagnostics().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(copied.valid()).isTrue();
        }
    }

    @Test void templatePlaceholderAndSpeakerNotesDoNotCreateVisibleContentOrMetrics() throws Exception {
        try (var show = show()) {
            var slide = show.createSlide();
            var placeholder = slide.createTextBox(); placeholder.setPlaceholder(org.apache.poi.sl.usermodel.Placeholder.BODY);
            placeholder.setAnchor(new Rectangle2D.Double(10, 10, 100, 100));
            var notes = show.getNotesSlide(slide);
            var noteBody = notes.createTextBox(); noteBody.setPlaceholder(org.apache.poi.sl.usermodel.Placeholder.BODY);
            noteBody.setText("private speaker notes");
            var report = validate(show);
            assertThat(report.diagnostics()).extracting(LayoutDiagnostic::code).contains(LayoutDiagnosticCode.EMPTY_SLIDE);
            assertThat(report.diagnostics()).allSatisfy(d -> assertThat(d.toString()).doesNotContain("private speaker notes"));
            assertThat(report.metrics().shapeCount()).isOne();
            assertThat(report.metrics().templatePlaceholderCount()).isOne();
            assertThat(report.metrics().nativeTextCount()).isZero();
        }
    }

    private static LayoutValidationReport validate(XMLSlideShow show) {
        return PptxLayoutValidator.validate(show, LayoutValidationPolicy.standard(), context());
    }
    private static LayoutValidationContext context() {
        return LayoutValidationContext.builder(LayoutMode.EDITABLE).build();
    }
    private static XMLSlideShow show() {
        var show = new XMLSlideShow(); show.setPageSize(new Dimension(720, 540)); return show;
    }
    private static byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out); return out.toByteArray();
    }
}
