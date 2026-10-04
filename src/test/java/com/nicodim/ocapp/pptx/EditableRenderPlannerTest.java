package com.nicodim.ocapp.pptx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.pagemodel.AssetReference;
import com.nicodim.ocapp.pagemodel.AuthoringHints;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.CaptureMetadata;
import com.nicodim.ocapp.pagemodel.ComputedStyle;
import com.nicodim.ocapp.pagemodel.DocumentMetadata;
import com.nicodim.ocapp.pagemodel.LinkReference;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.PageGeometry;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagemodel.TextRun;
import com.nicodim.ocapp.pagemodel.TransformSummary;
import com.nicodim.ocapp.pagination.BreakRationale;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.pagination.SlideViewport;
import com.nicodim.ocapp.pagination.SourceBounds;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditableRenderPlannerTest {
    private final EditableRenderPlanner planner = new EditableRenderPlanner();

    @Test void classifiesSimpleTextAndEmbeddedRasterAsNativeDeterministically() {
        AssetReference image = new AssetReference("asset", AssetReference.Kind.IMAGE,
            URI.create("data:image/png;base64,iVBORw0KGgo="), "image/png", 8, true);
        PageBlock text = block("text", BlockType.TEXT, "", List.of(), new Bounds(10, 5, 40, 10),
            List.of(new TextRun("hello", style())), List.of(new LinkReference("hello", URI.create("https://example.org/a"))), List.of(image), false, List.of());
        PageBlock picture = block("image", BlockType.IMAGE, "", List.of(), new Bounds(60, 5, 20, 20),
            List.of(), List.of(), List.of(image), false, List.of());
        PageModel model = model(List.of(text, picture));

        List<RenderItem> first = planner.plan(model, slice(), 10);
        assertThat(first).containsExactlyElementsOf(planner.plan(model, slice(), 10));
        assertThat(first).hasSize(2);
        assertThat(first).anySatisfy(item -> {
            assertThat(item).isInstanceOf(RenderItem.NativeText.class);
            RenderItem.NativeText nativeText = (RenderItem.NativeText) item;
            assertThat(nativeText.spans().getFirst().fontSizePoints()).isEqualTo(12);
            assertThat(nativeText.spans().getFirst().hyperlink()).hasToString("https://example.org/a");
        });
        assertThat(first).anyMatch(RenderItem.NativeImage.class::isInstance);
    }

    @Test void splitNativeCandidateUsesExactScreenshotFragmentWithoutDuplicatingContent() {
        PageBlock text = block("text", BlockType.TEXT, "", List.of(), new Bounds(0, 20, 40, 10),
            List.of(new TextRun("crosses boundary", style())), List.of(), List.of(), false, List.of());
        SlideSlice partial = new SlideSlice(0, new SourceBounds(0, 0, 100, 25), new SlideViewport(720, 180),
            BreakRationale.FALLBACK, List.of());
        assertThat(planner.plan(model(List.of(text)), partial, 10)).containsExactly(
            new RenderItem.ScreenshotCrop(new RenderItem.Rect(0, 20, 40, 5)));
    }

    @Test void stripsActiveHyperlinkSchemesWithoutRasterizingText() {
        PageBlock text = block("text", BlockType.TEXT, "", List.of(), new Bounds(0, 0, 40, 10),
            List.of(new TextRun("unsafe", style())), List.of(new LinkReference("unsafe", URI.create("javascript:alert(1)"))),
            List.of(), false, List.of());
        RenderItem.NativeText item = (RenderItem.NativeText) planner.plan(model(List.of(text)), slice(), 10).getFirst();
        assertThat(item.spans().getFirst().hyperlink()).isNull();
    }

    @Test void groupsOverlapAndUnsupportedSemanticAreaIntoCropAndSuppressesDescendants() {
        PageBlock list = block("list", BlockType.LIST, "", List.of("child"), new Bounds(0, 0, 50, 30),
            List.of(), List.of(), List.of(), false, List.of());
        PageBlock child = block("child", BlockType.TEXT, "list", List.of(), new Bounds(5, 5, 30, 10),
            List.of(new TextRun("list item", style())), List.of(), List.of(), false, List.of());
        PageBlock overlapA = block("a", BlockType.TEXT, "", List.of(), new Bounds(60, 0, 20, 20),
            List.of(new TextRun("a", style())), List.of(), List.of(), false, List.of("b"));
        PageBlock overlapB = block("b", BlockType.TEXT, "", List.of(), new Bounds(70, 10, 20, 20),
            List.of(new TextRun("b", style())), List.of(), List.of(), false, List.of("a"));

        List<RenderItem> items = planner.plan(model(List.of(list, child, overlapA, overlapB)), slice(), 10);
        assertThat(items).allMatch(RenderItem.ScreenshotCrop.class::isInstance).hasSize(2);
        assertThat(items).extracting(RenderItem::bounds).contains(
            new RenderItem.Rect(0, 0, 50, 30), new RenderItem.Rect(60, 0, 30, 30));
    }

    @Test void transformedAndUnsupportedColorFallBackAndLimitCollapsesBeforePoi() {
        ComputedStyle invalid = new ComputedStyle("block", "static", "visible", "visible", "color(display-p3 1 0 0)",
            "transparent", "Arial", 16, 400, "normal", "left", 19.2, 1, 0, false, false);
        PageBlock invalidText = new PageBlock("invalid", BlockType.TEXT, "", List.of(), 0, 0, 0,
            new Bounds(0, 0, 20, 10), null, TransformSummary.none(), List.of(), invalid,
            List.of(new TextRun("x", invalid)), List.of(), null, null, List.of(), AuthoringHints.none(), List.of());
        PageBlock transformed = block("turn", BlockType.TEXT, "", List.of(), new Bounds(30, 0, 20, 10),
            List.of(new TextRun("turn", style())), List.of(), List.of(), true, List.of());
        PageBlock simple = block("simple", BlockType.TEXT, "", List.of(), new Bounds(60, 0, 20, 10),
            List.of(new TextRun("simple", style())), List.of(), List.of(), false, List.of());
        PageModel model = model(List.of(invalidText, transformed, simple));

        assertThat(planner.plan(model, slice(), 10)).filteredOn(RenderItem.ScreenshotCrop.class::isInstance).hasSize(2);
        assertThat(planner.plan(model, slice(), 1)).containsExactly(
            new RenderItem.ScreenshotCrop(new RenderItem.Rect(0, 0, 100, 50)));
        assertThatThrownBy(() -> planner.plan(model, slice(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void parsesSupportedCssColorsAndRejectsAmbiguousValues() {
        assertThat(CssColors.parse(null)).isNull();
        assertThat(CssColors.parse("black")).isEqualTo(java.awt.Color.BLACK);
        assertThat(CssColors.parse("#abc")).isEqualTo(new java.awt.Color(170, 187, 204));
        assertThat(CssColors.parse("#0a141e")).isEqualTo(new java.awt.Color(10, 20, 30));
        assertThat(CssColors.parse("rgb(1, 2, 3)")).isEqualTo(new java.awt.Color(1, 2, 3));
        assertThat(CssColors.parse("rgba(1,2,3,0.5)").getAlpha()).isEqualTo(128);
        assertThat(CssColors.parse("rgb(300,0,0)")).isNull();
        assertThat(CssColors.parse("rgba(0,0,0,2)")).isNull();
        assertThat(CssColors.parse("#nope")).isNull();
        assertThat(CssColors.supported("transparent")).isTrue();
    }

    @Test void renderRectValidatesEveryCoordinateAndProvidesGeometryOperations() {
        for (double[] value : List.of(new double[]{Double.NaN,0,1,1}, new double[]{0,Double.NaN,1,1},
            new double[]{0,0,Double.NaN,1}, new double[]{0,0,1,Double.NaN}, new double[]{0,0,0,1}, new double[]{0,0,1,0})) {
            assertThatThrownBy(() -> new RenderItem.Rect(value[0], value[1], value[2], value[3]))
                .isInstanceOf(IllegalArgumentException.class);
        }
        RenderItem.Rect first = new RenderItem.Rect(0, 0, 10, 10), second = new RenderItem.Rect(5, 5, 10, 10);
        assertThat(first.intersects(second)).isTrue();
        assertThat(first.intersects(new RenderItem.Rect(20, 20, 1, 1))).isFalse();
        assertThat(first.union(second)).isEqualTo(new RenderItem.Rect(0, 0, 15, 15));
        assertThat(first.right()).isEqualTo(10); assertThat(first.bottom()).isEqualTo(10);
    }

    @Test void coordinateMapperUsesUniformCssScaleAndRejectsOutOfSlideBounds() {
        CssCoordinateMapper mapper = new CssCoordinateMapper(slice());
        assertThat(mapper.pointsPerCssPixel()).isEqualTo(7.2);
        assertThat(mapper.map(new RenderItem.Rect(10, 5, 20, 10)))
            .satisfies(rect -> assertThat(List.of(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight()))
                .containsExactly(72d, 36d, 144d, 72d));
        assertThatThrownBy(() -> mapper.map(new RenderItem.Rect(0, 45, 10, 10))).isInstanceOf(IllegalArgumentException.class);
    }

    static PageModel model(List<PageBlock> blocks) {
        return new PageModel(PageModel.SCHEMA_VERSION, new DocumentMetadata(URI.create("https://example.org"), "", ""),
            new CaptureMetadata("Chrome", "en", "UTC", 1, "complete", List.of()),
            new PageGeometry(100, 50, new Bounds(0, 0, 100, 50), 0, 0), blocks,
            blocks.stream().filter(b -> b.parentId().isEmpty()).map(PageBlock::id).toList(),
            blocks.stream().flatMap(b -> b.assets().stream()).distinct().toList(), List.of());
    }

    static SlideSlice slice() {
        return new SlideSlice(0, new SourceBounds(0, 0, 100, 50), new SlideViewport(720, 360),
            BreakRationale.DOCUMENT_END, List.of());
    }

    static ComputedStyle style() {
        return new ComputedStyle("block", "static", "visible", "visible", "rgb(10, 20, 30)",
            "transparent", "Arial", 16, 400, "normal", "left", 19.2, 1, 0, false, false);
    }

    static PageBlock block(String id, BlockType type, String parent, List<String> children, Bounds bounds,
                           List<TextRun> runs, List<LinkReference> links, List<AssetReference> assets,
                           boolean transformed, List<String> overlaps) {
        return new PageBlock(id, type, parent, children, parent.isEmpty() ? 0 : 1, 0, Integer.parseInt(id.replaceAll("\\D", "").isEmpty() ? "0" : id.replaceAll("\\D", "")),
            bounds, null, transformed ? new TransformSummary(true, "matrix(1,0,0,1,0,0)", 0, 1, 1) : TransformSummary.none(),
            overlaps, style(), runs, links, null, null, assets, AuthoringHints.none(), List.of());
    }
}
