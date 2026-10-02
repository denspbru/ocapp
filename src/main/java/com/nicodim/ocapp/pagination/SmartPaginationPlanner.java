package com.nicodim.ocapp.pagination;

import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.support.ConversionException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Pure deterministic PageModel -> screenshot slice planning. */
public final class SmartPaginationPlanner {
    private static final double EPSILON = 0.000_001;

    public PaginationPlan plan(PageModel model, PaginationOptions options) {
        if (model == null || options == null || model.geometry() == null
            || !positive(model.geometry().width()) || !positive(model.geometry().height())
            || !positive(options.slideWidthPoints()) || !positive(options.slideHeightPoints())
            || !positive(options.minimumSliceHeight()) || options.maxSlides() < 1) {
            throw invalid("Pagination input is invalid");
        }
        double width = model.geometry().width(), height = model.geometry().height();
        double idealHeight = width * options.slideHeightPoints() / options.slideWidthPoints();
        if (!positive(idealHeight) || options.minimumSliceHeight() > idealHeight + EPSILON)
            throw invalid("Pagination geometry is invalid");

        List<PageBlock> blocks = model.blocks().stream()
            .filter(b -> b.bounds().bottom() > 0 && b.bounds().y() < height)
            .sorted(Comparator.comparingInt(PageBlock::visualOrder).thenComparingInt(PageBlock::domOrder))
            .toList();
        List<Candidate> candidates = candidates(blocks, height, options.minimumSliceHeight());
        List<SlideSlice> slices = new ArrayList<>();
        List<PaginationWarning> warnings = new ArrayList<>();
        Set<Double> warnedExplicit = new HashSet<>();
        double start = 0;
        while (start < height - EPSILON) {
            if (slices.size() >= options.maxSlides()) throw maxSlides();
            double ideal = Math.min(height, start + idealHeight);
            Choice choice = choose(start, ideal, height, options.minimumSliceHeight(), candidates, blocks);
            double end = choice.end();
            if (!(end > start + EPSILON) || end > height + EPSILON) throw invalid("Pagination produced an invalid slice");
            int index = slices.size();
            List<PaginationWarning> sliceWarnings = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (candidate.rationale() == BreakRationale.EXPLICIT_HINT && candidate.position() > start + EPSILON
                    && candidate.position() < Math.min(height, start + options.minimumSliceHeight()) - EPSILON
                    && warnedExplicit.add(candidate.position())) {
                    PaginationWarning warning = new PaginationWarning("EXPLICIT_BREAK_TOO_CLOSE", index);
                    warnings.add(warning); sliceWarnings.add(warning);
                }
            }
            if (choice.forced()) {
                PaginationWarning warning = new PaginationWarning("OVERSIZED_BLOCK_SPLIT", index);
                warnings.add(warning); sliceWarnings.add(warning);
            }
            BreakRationale rationale = end >= height - EPSILON ? BreakRationale.DOCUMENT_END : choice.rationale();
            slices.add(new SlideSlice(index, new SourceBounds(0, start, width, end - start),
                new SlideViewport(options.slideWidthPoints(), options.slideHeightPoints()), rationale, sliceWarnings));
            start = end;
        }
        validate(width, height, slices, options.maxSlides());
        return new PaginationPlan(width, height, slices, warnings);
    }

    private static List<Candidate> candidates(List<PageBlock> blocks, double height, double minimum) {
        List<Candidate> result = new ArrayList<>();
        List<PageBlock> vertical = blocks.stream().sorted(Comparator.comparingDouble(b -> b.bounds().y())).toList();
        for (PageBlock block : blocks) {
            Bounds visible = block.clipBounds() == null ? block.bounds() : block.clipBounds();
            double top = clamp(visible.y(), 0, height), bottom = clamp(visible.bottom(), 0, height);
            if (!block.hints().slide().isBlank() && top > 0) result.add(new Candidate(top, BreakRationale.EXPLICIT_HINT, 0));
            if (top > 0) result.add(new Candidate(top, BreakRationale.BLOCK_BOUNDARY, 2));
            if (bottom < height) result.add(new Candidate(bottom, BreakRationale.BLOCK_BOUNDARY, 2));
        }
        for (int i = 1; i < vertical.size(); i++) {
            double prior = vertical.get(i - 1).bounds().bottom(), next = vertical.get(i).bounds().y();
            if (next - prior >= minimum / 4.0) result.add(new Candidate((prior + next) / 2.0, BreakRationale.WHITESPACE, 1));
        }
        return result.stream().filter(c -> c.position() > 0 && c.position() < height)
            .sorted(Comparator.comparingInt(Candidate::priority).thenComparingDouble(Candidate::position)).toList();
    }

    private static Choice choose(double start, double ideal, double height, double minimum,
                                 List<Candidate> candidates, List<PageBlock> blocks) {
        if (ideal >= height - EPSILON || height - ideal < minimum - EPSILON)
            return new Choice(height, BreakRationale.DOCUMENT_END, false);
        for (int priority = 0; priority <= 2; priority++) {
            Candidate best = null;
            for (Candidate candidate : candidates) {
                if (candidate.priority() != priority || candidate.position() < start + minimum - EPSILON
                    || candidate.position() > ideal + EPSILON || height - candidate.position() < minimum - EPSILON
                    || splitsProtected(candidate.position(), blocks, ideal - start)) continue;
                if (best == null || candidate.position() > best.position()) best = candidate;
            }
            if (best != null) return new Choice(best.position(), best.rationale(), false);
        }
        boolean oversized = blocks.stream().anyMatch(b -> intrinsicallyProtected(b)
            && visibleBottom(b) - visibleTop(b) > ideal - start + EPSILON
            && visibleTop(b) < ideal - EPSILON && visibleBottom(b) > ideal + EPSILON);
        return new Choice(ideal, BreakRationale.FALLBACK, oversized);
    }

    private static boolean splitsProtected(double y, List<PageBlock> blocks, double capacity) {
        if (blocks.stream().anyMatch(b -> protectedBlock(b, capacity)
            && visibleTop(b) < y - EPSILON && visibleBottom(b) > y + EPSILON)) return true;
        List<PageBlock> dom = blocks.stream().sorted(Comparator.comparingInt(PageBlock::domOrder)).toList();
        for (int i = 0; i + 1 < dom.size(); i++) {
            PageBlock heading = dom.get(i), following = dom.get(i + 1);
            if (headingLike(heading) && visibleBottom(following) - visibleTop(heading) <= capacity + EPSILON
                && visibleTop(heading) < y - EPSILON && visibleBottom(following) > y + EPSILON) return true;
        }
        return false;
    }

    private static boolean headingLike(PageBlock block) {
        return block.type() == BlockType.TEXT && (block.style().fontWeight() >= 600 || block.style().fontSize() >= 24);
     }

    private static boolean protectedBlock(PageBlock block, double capacity) {
        double blockHeight = visibleBottom(block) - visibleTop(block);
        return blockHeight <= capacity + EPSILON && intrinsicallyProtected(block);
    }

    private static boolean intrinsicallyProtected(PageBlock block) {
        return block.hints().keepTogether() || block.type() != BlockType.CONTAINER
            || block.transform().transformed() || !block.overlapIds().isEmpty();
    }

    private static double visibleTop(PageBlock block) { return (block.clipBounds() == null ? block.bounds() : block.clipBounds()).y(); }
    private static double visibleBottom(PageBlock block) { return (block.clipBounds() == null ? block.bounds() : block.clipBounds()).bottom(); }

    private static void validate(double width, double height, List<SlideSlice> slices, int maxSlides) {
        if (slices.isEmpty() || slices.size() > maxSlides) throw invalid("Pagination produced no usable slices");
        double cursor = 0;
        for (int i = 0; i < slices.size(); i++) {
            SlideSlice slice = slices.get(i);
            if (slice.index() != i || Math.abs(slice.source().x()) > EPSILON
                || Math.abs(slice.source().width() - width) > EPSILON
                || Math.abs(slice.source().y() - cursor) > EPSILON || !positive(slice.source().height())) {
                throw invalid("Pagination coverage is invalid");
            }
            cursor = slice.source().bottom();
        }
        if (Math.abs(cursor - height) > EPSILON) throw invalid("Pagination coverage is incomplete");
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static ConversionException invalid(String message) { return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGINATION_INVALID", message); }
    private static ConversionException maxSlides() { return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "PPTX_MAX_SLIDES_EXCEEDED", "Page requires more slides than configured"); }
    private record Candidate(double position, BreakRationale rationale, int priority) { }
    private record Choice(double end, BreakRationale rationale, boolean forced) { }
}
