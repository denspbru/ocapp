package com.nicodim.ocapp.pptx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.AuthoringHints;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.CaptureMetadata;
import com.nicodim.ocapp.pagemodel.ComputedStyle;
import com.nicodim.ocapp.pagemodel.DocumentMetadata;
import com.nicodim.ocapp.pagemodel.DomPageExtractor;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.PageGeometry;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagemodel.ModelWarning;
import com.nicodim.ocapp.pagemodel.PageModelValidator;
import com.nicodim.ocapp.pagemodel.TextRun;
import com.nicodim.ocapp.pagemodel.TransformSummary;
import com.nicodim.ocapp.pagination.BreakRationale;
import com.nicodim.ocapp.pagination.PaginationOptions;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.pagination.SlideViewport;
import com.nicodim.ocapp.pagination.SmartPaginationPlanner;
import com.nicodim.ocapp.pagination.SourceBounds;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

class M6AuthoringHintsTest {
    private static final ComputedStyle STYLE = new ComputedStyle("block", "static", "visible", "visible",
        "black", "transparent", "Arial", 16, 400, "normal", "left", 19.2, 1, 0, false, false);

    @Test void contractVersionIsStable() {
        assertThat(AuthoringHints.CONTRACT_VERSION).isEqualTo("1");
    }

    @Test void slideAndKeepTogetherOverrideHeuristicsDeterministically() {
        PageModel page = model(100, 220, List.of(
            block("a", 0, new Bounds(0, 0, 100, 80), hints(false, "", "", "", "", AuthoringHints.Render.AUTO), false, "a"),
            block("b", 1, new Bounds(0, 100, 100, 20), hints(true, AuthoringHints.BREAK_BEFORE, "", "", "", AuthoringHints.Render.AUTO), false, "b"),
            block("c", 2, new Bounds(0, 125, 100, 50), AuthoringHints.none(), false, "c")));
        SmartPaginationPlanner planner = new SmartPaginationPlanner();
        PaginationPlan first = planner.plan(page, new PaginationOptions(100, 100, 10, 10));
        assertThat(first).isEqualTo(planner.plan(page, new PaginationOptions(100, 100, 10, 10)));
        assertThat(first.slices().getFirst().source().bottom()).isEqualTo(100);
        assertThat(first.slices().getFirst().breakRationale()).isEqualTo(BreakRationale.EXPLICIT_HINT);
    }

    @Test void explicitSlideBreakWinsOverAutomaticSmallRemainderCollapseWithoutChangingNoHintBehavior() {
        PaginationOptions options = new PaginationOptions(100, 100, 10, 10);
        PageBlock hinted = block("hinted", 0, new Bounds(0, 50, 100, 20),
            hints(false, AuthoringHints.BREAK_BEFORE, "", "", "", AuthoringHints.Render.AUTO), false, "hinted");
        PageBlock automatic = block("automatic", 0, new Bounds(0, 50, 100, 20), AuthoringHints.none(), false, "automatic");
        PaginationPlan explicit = new SmartPaginationPlanner().plan(model(100, 105, List.of(hinted)), options);
        PaginationPlan noHint = new SmartPaginationPlanner().plan(model(100, 105, List.of(automatic)), options);
        assertThat(explicit.slices()).hasSize(2);
        assertThat(explicit.slices().getFirst().source().bottom()).isEqualTo(50);
        assertThat(explicit.slices().getFirst().breakRationale()).isEqualTo(BreakRationale.EXPLICIT_HINT);
        assertThat(noHint.slices()).hasSize(1);
        assertThat(noHint.slices().getFirst().source().bottom()).isEqualTo(105);
    }

    @Test void explicitSlideBreakWinsOverConflictingAncestorKeepTogether() {
        PageBlock child = block("child", 1, new Bounds(0, 80, 50, 20),
            hints(false, AuthoringHints.BREAK_BEFORE, "", "", "", AuthoringHints.Render.AUTO), false, "child");
        PageBlock parent = new PageBlock("parent", BlockType.CONTAINER, "", List.of("child"), 0, 0, 0,
            new Bounds(0, 0, 100, 180), null, TransformSummary.none(), List.of(), STYLE, List.of(), List.of(),
            null, null, List.of(), hints(true, "", "", "", "", AuthoringHints.Render.AUTO), List.of());
        child = new PageBlock(child.id(), child.type(), "parent", child.childIds(), 1, child.domOrder(), child.visualOrder(),
            child.bounds(), null, child.transform(), child.overlapIds(), child.style(), child.textRuns(), child.links(),
            null, null, List.of(), child.hints(), List.of());
        PaginationPlan plan = new SmartPaginationPlanner().plan(model(100, 200, List.of(parent, child)),
            new PaginationOptions(100, 100, 10, 10));
        assertThat(plan.slices().getFirst().source().bottom()).isEqualTo(80);
        assertThat(plan.slices().getFirst().breakRationale()).isEqualTo(BreakRationale.EXPLICIT_HINT);
    }

    @Test void imageWinsOverNativeDescendantsWhileNativeRemainsSafetySubordinate() {
        PageBlock child = block("child", 1, new Bounds(10, 10, 30, 10),
            hints(false, "", "", "", "", AuthoringHints.Render.NATIVE), false, "child");
        PageBlock parent = new PageBlock("parent", BlockType.CONTAINER, "", List.of("child"), 0, 0, 0,
            new Bounds(0, 0, 50, 30), null, TransformSummary.none(), List.of(), STYLE, List.of(), List.of(),
            null, null, List.of(), hints(false, "", "", "", "", AuthoringHints.Render.IMAGE), List.of());
        child = new PageBlock(child.id(), child.type(), "parent", child.childIds(), 1, child.domOrder(), child.visualOrder(),
            child.bounds(), null, child.transform(), child.overlapIds(), child.style(), child.textRuns(), child.links(),
            null, null, List.of(), child.hints(), List.of());
        assertThat(new EditableRenderPlanner().plan(model(100, 50, List.of(parent, child)), slice(), 10))
            .containsExactly(new RenderItem.ScreenshotCrop(new RenderItem.Rect(0, 0, 50, 30)));

        PageBlock unsafeNative = block("unsafe", 0, new Bounds(-5, 0, 30, 10),
            hints(false, "", "", "", "", AuthoringHints.Render.NATIVE), true, "unsafe");
        assertThat(new EditableRenderPlanner().plan(model(100, 50, List.of(unsafeNative)), slice(), 10))
            .containsExactly(new RenderItem.ScreenshotCrop(new RenderItem.Rect(0, 0, 25, 10)));
    }

    @Test void resolvesTitleNotesLayoutByDomOrderAndEmitsBoundedConflictWarnings() {
        PageModel page = model(100, 50, List.of(
            block("first", 0, new Bounds(0, 0, 40, 10), hints(false, "", "First", "Speaker", "title-only", AuthoringHints.Render.AUTO), false, "one"),
            block("second", 1, new Bounds(0, 15, 40, 10), hints(false, "", "Second", "Other", "blank", AuthoringHints.Render.AUTO), false, "two")));
        PaginationPlan plan = new PaginationPlan(100, 50, List.of(slice()), List.of());
        var first = new AuthoringHintResolver().resolve(page, plan);
        assertThat(first).isEqualTo(new AuthoringHintResolver().resolve(page, plan));
        assertThat(first.slides().getFirst()).isEqualTo(new AuthoringHintResolver.SlideHints("First", "Speaker", "title-only"));
        assertThat(first.warnings()).extracting(AuthoringHintResolver.HintWarning::attribute)
            .containsExactly("data-pptx-title", "data-pptx-notes", "data-pptx-layout");
        assertThat(first.warnings()).allSatisfy(warning -> assertThat(warning.toString()).doesNotContain("First", "Second", "Speaker", "Other"));
    }

    @Test void noHintPagePreservesAutomaticPlanAndRendering() throws Exception {
        PageBlock automatic = block("auto", 0, new Bounds(0, 0, 40, 10), AuthoringHints.none(), false, "automatic");
        PageBlock explicitAuto = block("explicit", 0, new Bounds(0, 0, 40, 10),
            hints(false, "", "", "", "", AuthoringHints.Render.AUTO), false, "automatic");
        EditableRenderPlanner planner = new EditableRenderPlanner();
        PageModel page = model(100, 50, List.of(automatic));
        assertThat(planner.plan(page, slice(), 10))
            .isEqualTo(planner.plan(model(100, 50, List.of(explicitAuto)), slice(), 10));
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().setSlideWidthInches(10); properties.getPptx().setSlideHeightInches(5);
        byte[] bytes = new HybridPptxRenderer(properties).render(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB),
            page, new PaginationPlan(100, 50, List.of(slice()), List.of()));
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(show.getProperties().getCustomProperties().getProperty("ocapp.authoring-hints.contract")).isNull();
            assertThat(show.getSlides().getFirst().getSlideName()).doesNotStartWith("ocapp-layout:");
        }
    }

    @Test void validatorRejectsUnnormalizedHintValuesWithStableContentFreeError() {
        PageModel bad = model(100, 50, List.of(block("bad", 0, new Bounds(0, 0, 40, 10),
            hints(false, "new-slide", "", "", "template-secret", AuthoringHints.Render.AUTO), false, "bad")));
        assertThatThrownBy(() -> PageModelValidator.validate(bad, new ConverterProperties().getPageModel()))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PAGEMODEL_INVALID");
        assertThatThrownBy(() -> PageModelValidator.validate(bad, new ConverterProperties().getPageModel()))
            .hasMessageNotContaining("new-slide").hasMessageNotContaining("template-secret");
    }

    @Test void poiReopenPreservesTitleNotesLayoutAndContractMetadata() throws Exception {
        PageBlock content = block("content", 0, new Bounds(0, 15, 40, 10),
            hints(false, "", "Semantic title", "Private speaker note", "title-only", AuthoringHints.Render.AUTO), false, "body");
        PageModel base = model(100, 50, List.of(content));
        PageModel page = new PageModel(base.schemaVersion(), base.document(), base.capture(), base.geometry(), base.blocks(),
            base.rootIds(), base.assets(), List.of(new ModelWarning("PPTX_HINT_INVALID", "", "data-pptx-render")));
        PaginationPlan plan = new PaginationPlan(100, 50, List.of(slice()), List.of());
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().setSlideWidthInches(10); properties.getPptx().setSlideHeightInches(5);
        byte[] bytes = new HybridPptxRenderer(properties).render(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), page, plan);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            var slide = show.getSlides().getFirst();
            assertThat(slide.getTitle()).isEqualTo("Semantic title");
            assertThat(slide.getSlideName()).isEqualTo("ocapp-layout:title-only");
            assertThat(slide.getNotes()).isNotNull();
            assertThat(slide.getNotes().getTextParagraphs().stream().flatMap(List::stream)
                .flatMap(paragraph -> paragraph.getTextRuns().stream()).map(run -> run.getRawText()).toList())
                .contains("Private speaker note");
            assertThat(show.getProperties().getCustomProperties().getProperty("ocapp.authoring-hints.contract").getLpwstr())
                .isEqualTo("1");
            assertThat(show.getProperties().getCustomProperties().getProperty("ocapp.authoring-hints.warning.0").getLpwstr())
                .isEqualTo("PPTX_HINT_INVALID:data-pptx-render");
            assertThat(slide.getShapes()).filteredOn(XSLFTextBox.class::isInstance).hasSize(2);
        }
    }

    private static AuthoringHints hints(boolean keep, String slide, String title, String notes, String layout, AuthoringHints.Render render) {
        return new AuthoringHints(keep, slide, title, notes, layout, render);
    }

    private static PageBlock block(String id, int order, Bounds bounds, AuthoringHints hints, boolean transformed, String text) {
        return new PageBlock(id, BlockType.TEXT, "", List.of(), 0, order, order, bounds, null,
            transformed ? new TransformSummary(true, "matrix(1,0,0,1,0,0)", 0, 1, 1) : TransformSummary.none(),
            List.of(), STYLE, List.of(new TextRun(text, STYLE)), List.of(), null, null, List.of(), hints, List.of());
    }

    private static PageModel model(double width, double height, List<PageBlock> blocks) {
        return new PageModel(PageModel.SCHEMA_VERSION, new DocumentMetadata(URI.create("https://example.test"), "", ""),
            new CaptureMetadata("Chrome", "en", "UTC", 1, "complete", List.of()),
            new PageGeometry(width, height, new Bounds(0, 0, width, height), 0, 0), blocks,
            blocks.stream().filter(block -> block.parentId().isEmpty()).map(PageBlock::id).toList(), List.of(), List.of());
    }

    private static SlideSlice slice() {
        return new SlideSlice(0, new SourceBounds(0, 0, 100, 50), new SlideViewport(720, 360), BreakRationale.DOCUMENT_END, List.of());
    }
}
