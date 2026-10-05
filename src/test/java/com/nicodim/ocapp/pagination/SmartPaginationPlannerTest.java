package com.nicodim.ocapp.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.AuthoringHints;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.CaptureMetadata;
import com.nicodim.ocapp.pagemodel.ComputedStyle;
import com.nicodim.ocapp.pagemodel.DocumentMetadata;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.PageGeometry;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagemodel.PageModelValidator;
import com.nicodim.ocapp.pagemodel.TableSemantics;
import com.nicodim.ocapp.pagemodel.TransformSummary;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SmartPaginationPlannerTest {
    private final SmartPaginationPlanner planner = new SmartPaginationPlanner();
    private final PaginationOptions options = new PaginationOptions(960, 540, 120, 20);

    @Test void prioritizesExplicitWhitespaceAndBoundariesWithoutOrphaningHeadings() {
        List<PageBlock> blocks = List.of(
            block("intro", BlockType.TEXT, 40, 300, 0, false, "", 16, 400, null, false),
            block("heading", BlockType.TEXT, 780, 60, 1, false, "", 30, 700, null, false),
            block("paragraph", BlockType.TEXT, 850, 180, 2, false, "", 16, 400, null, false),
            block("explicit", BlockType.CONTAINER, 1420, 200, 3, false, AuthoringHints.BREAK_BEFORE, 16, 400, null, false));
        PaginationPlan plan = planner.plan(model(1600, 2400, blocks, 1.0, 0), options);
        assertThat(plan.slices()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(plan.slices().get(0).source().bottom()).isEqualTo(560);
        assertThat(plan.slices()).noneMatch(s -> s.source().bottom() > 780 && s.source().bottom() < 1030);
        assertThat(plan.slices()).anyMatch(s -> s.source().bottom() == 1420 && s.breakRationale() == BreakRationale.EXPLICIT_HINT);
        assertCoverage(plan);
        assertThat(planner.plan(model(1600, 2400, blocks, 1.0, 0), options)).isEqualTo(plan);
    }

    @Test void keepsFittingImagesTablesRowsChartsAndKeepTogetherBlocksIntact() {
        List<PageBlock> blocks = List.of(
            block("image", BlockType.IMAGE, 600, 250, 0, false, "", 16, 400, null, false),
            block("table", BlockType.TABLE, 880, 500, 1, false, "", 16, 400, null, false),
            block("chart", BlockType.CHART, 1450, 300, 2, false, "", 16, 400, null, false),
            block("nested", BlockType.CONTAINER, 1800, 450, 3, true, "", 16, 400, null, false));
        PaginationPlan plan = planner.plan(model(1600, 2600, blocks, 1.25, 37.5), options);
        for (SlideSlice slice : plan.slices()) {
            double cut = slice.source().bottom();
            if (cut >= plan.sourceHeight()) continue;
            assertThat(blocks).noneMatch(b -> b.bounds().height() <= 900 && b.bounds().y() < cut && b.bounds().bottom() > cut);
        }
        assertCoverage(plan);
    }

    @Test void handlesClippingTransformsOverlapVisualOrderAndOversizedBlocks() {
        PageBlock clipped = block("clip", BlockType.IMAGE, 500, 1200, 2, true, "", 16, 400,
            new Bounds(0, 600, 1600, 300), true);
        PageBlock overlap = withOverlap(block("overlap", BlockType.CONTAINER, 650, 100, 0, false, "", 16, 400, null, false), "clip");
        PageBlock oversized = block("large-table", BlockType.TABLE, 900, 1300, 1, false, "", 16, 400, null, false);
        PaginationPlan plan = planner.plan(model(1600, 2500, List.of(clipped, overlap, oversized), 1.5, 0.5), options);
        assertThat(plan.warnings()).extracting(PaginationWarning::code).contains("OVERSIZED_BLOCK_SPLIT");
        assertThat(plan.slices()).allMatch(s -> s.source().height() > 0);
        assertCoverage(plan);
    }

    @Test void protectsEveryAtomicContentTypeAndConservativeComplexContainers() {
        for (BlockType type : BlockType.values()) {
            boolean complexContainer = type == BlockType.CONTAINER;
            PageBlock crossing = block("content", type, 800, 200, 0, complexContainer, "", 16, 400,
                null, complexContainer);
            PaginationPlan plan = planner.plan(model(1600, 1800, List.of(crossing), 2, 0), options);
            assertThat(plan.slices()).noneMatch(s -> s.source().bottom() > 800 && s.source().bottom() < 1000);
            assertCoverage(plan);
        }
    }

    @Test void prioritizesExplicitBreaksAndWarnsWhenTheyAreTooClose() {
        List<PageBlock> blocks = List.of(
            block("early", BlockType.CONTAINER, 50, 20, 0, false, AuthoringHints.BREAK_BEFORE, 16, 400, null, false),
            block("explicit", BlockType.CONTAINER, 400, 20, 1, false, AuthoringHints.BREAK_BEFORE, 16, 400, null, false),
            block("later", BlockType.CONTAINER, 850, 20, 2, false, "", 16, 400, null, false));
        PaginationPlan plan = planner.plan(model(1600, 1800, blocks, 1, 0), options);
        assertThat(plan.slices().getFirst().source().bottom()).isEqualTo(400);
        assertThat(plan.slices().getFirst().breakRationale()).isEqualTo(BreakRationale.EXPLICIT_HINT);
        assertThat(plan.warnings()).extracting(PaginationWarning::code).containsExactly("EXPLICIT_BREAK_TOO_CLOSE");
    }

    @Test void avoidsAlmostEmptyTailSlides() {
        PaginationPlan plan = planner.plan(model(1600, 970, List.of(), 1, 0), options);
        assertThat(plan.slices()).singleElement().satisfies(slice -> assertThat(slice.source().height()).isEqualTo(970));
        PaginationPlan longer = planner.plan(model(1600, 2770, List.of(), 1, 0), options);
        assertThat(longer.slices()).allMatch(slice -> slice.source().height() >= options.minimumSliceHeight());
        assertCoverage(longer);
    }

    @Test void supportsEmptyMinimalAndFractionalGeometry() {
        PaginationPlan empty = planner.plan(model(1000.5, 400.25, List.of(), 1.25, 0.5), options);
        assertThat(empty.slices()).singleElement().satisfies(s -> {
            assertThat(s.index()).isZero();
            assertThat(s.breakRationale()).isEqualTo(BreakRationale.DOCUMENT_END);
            assertThat(s.viewport()).isEqualTo(new SlideViewport(960, 540));
            assertThat(s.warnings()).isEmpty();
        });
        assertCoverage(empty);
    }

    @Test void failsMaxSlidesAndInvalidInputWithStableCodes() {
        assertThatThrownBy(() -> planner.plan(model(1600, 10_000, List.of(), 1, 0), new PaginationOptions(960, 540, 120, 2)))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_MAX_SLIDES_EXCEEDED");
        assertThatThrownBy(() -> planner.plan(null, options)).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("PAGINATION_INVALID");
        assertThatThrownBy(() -> new PaginationOptions(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(model(100, 100, List.of(), 1, 0), new PaginationOptions(1, 1, 101, 1)))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PAGINATION_INVALID");
    }

    @Test void randomBoundedBlockSetsAlwaysProduceMonotonicExactCoverage() {
        Random random = new Random(12);
        for (int sample = 0; sample < 150; sample++) {
            double width = 600 + random.nextDouble() * 1400;
            double height = 200 + random.nextDouble() * 7000;
            List<PageBlock> blocks = new ArrayList<>();
            for (int i = 0; i < random.nextInt(40); i++) {
                double y = random.nextDouble() * Math.max(1, height - 1);
                double h = Math.min(height - y, 1 + random.nextDouble() * 900);
                BlockType type = BlockType.values()[random.nextInt(BlockType.values().length)];
                blocks.add(block("b" + i, type, y, h, i, random.nextBoolean(), i % 17 == 0 ? AuthoringHints.BREAK_BEFORE : "",
                    12 + random.nextInt(20), random.nextBoolean() ? 400 : 700, null, random.nextBoolean()));
            }
            PaginationPlan plan = planner.plan(model(width, height, blocks, 1 + random.nextDouble(), random.nextDouble() * 100),
                new PaginationOptions(960, 540, 25, 100));
            assertCoverage(plan);
        }
    }

    private static void assertCoverage(PaginationPlan plan) {
        assertThat(plan.slices()).isNotEmpty();
        double cursor = 0;
        for (int i = 0; i < plan.slices().size(); i++) {
            SlideSlice slice = plan.slices().get(i);
            assertThat(slice.index()).isEqualTo(i);
            assertThat(slice.source().y()).isCloseTo(cursor, org.assertj.core.data.Offset.offset(0.000_001));
            assertThat(slice.source().width()).isEqualTo(plan.sourceWidth());
            assertThat(slice.source().height()).isPositive();
            cursor = slice.source().bottom();
        }
        assertThat(cursor).isCloseTo(plan.sourceHeight(), org.assertj.core.data.Offset.offset(0.000_001));
    }

    private static PageModel model(double width, double height, List<PageBlock> blocks, double dpr, double scrollY) {
        PageModel model = new PageModel(PageModel.SCHEMA_VERSION,
            new DocumentMetadata(URI.create("https://example.test"), "", ""),
            new CaptureMetadata("", "", "", dpr, "complete", List.of()),
            new PageGeometry(width, height, new Bounds(0, scrollY, width, Math.min(height, 900)), 0, scrollY),
            blocks, blocks.stream().map(PageBlock::id).toList(), List.of(), List.of());
        return PageModelValidator.validate(model, new ConverterProperties().getPageModel());
    }

    private static PageBlock withOverlap(PageBlock block, String overlapId) {
        return new PageBlock(block.id(), block.type(), block.parentId(), block.childIds(), block.depth(),
            block.domOrder(), block.visualOrder(), block.bounds(), block.clipBounds(), block.transform(),
            List.of(overlapId), block.style(), block.textRuns(), block.links(), block.list(), block.table(),
            block.assets(), block.hints(), block.warnings());
    }

    private static PageBlock block(String id, BlockType type, double y, double height, int order, boolean keep,
                                   String slide, double fontSize, int fontWeight, Bounds clip, boolean transformed) {
        ComputedStyle style = new ComputedStyle("block", order % 3 == 0 ? "absolute" : "static", "visible", "visible",
            "black", "transparent", "sans", fontSize, fontWeight, "normal", "start", fontSize * 1.2, 1, order,
            type == BlockType.CONTAINER, false);
        return new PageBlock(id, type, "", List.of(), 0, order, order, new Bounds(0, y, 1600, height), clip,
            transformed ? new TransformSummary(true, "matrix(1,0,0,1,0,0)", 0, 1, 1) : TransformSummary.none(),
            List.of(), style, List.of(), List.of(), null, null, List.of(),
            new AuthoringHints(keep, slide, "", "", "", AuthoringHints.Render.AUTO), List.of());
    }
}
