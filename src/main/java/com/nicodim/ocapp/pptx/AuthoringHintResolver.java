package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Pure deterministic resolution of v1 slide metadata after pagination. */
public final class AuthoringHintResolver {
    public Resolution resolve(PageModel model, PaginationPlan plan) {
        List<SlideHints> slides = new ArrayList<>(plan.slices().size());
        List<HintWarning> warnings = new ArrayList<>();
        List<PageBlock> ordered = model.blocks().stream()
            .sorted(Comparator.comparingInt(PageBlock::domOrder).thenComparing(PageBlock::id)).toList();
        for (SlideSlice slice : plan.slices()) {
            List<PageBlock> members = ordered.stream().filter(block -> belongsTo(block, slice)).toList();
            String title = first(members, block -> block.hints().title(), "data-pptx-title", slice.index(), warnings);
            String notes = first(members, block -> block.hints().notes(), "data-pptx-notes", slice.index(), warnings);
            String layout = first(members, block -> block.hints().layout(), "data-pptx-layout", slice.index(), warnings);
            slides.add(new SlideHints(title, notes, layout));
        }
        return new Resolution(slides, warnings);
    }

    private static boolean belongsTo(PageBlock block, SlideSlice slice) {
        double top = block.bounds().y();
        return top >= slice.source().y() && top < slice.source().bottom();
    }

    private static String first(List<PageBlock> blocks, Function<PageBlock,String> value,
                                String attribute, int slide, List<HintWarning> warnings) {
        String selected = "";
        for (PageBlock block : blocks) {
            String candidate = value.apply(block);
            if (candidate.isEmpty()) continue;
            if (selected.isEmpty()) selected = candidate;
            else if (!selected.equals(candidate)) {
                warnings.add(new HintWarning("PPTX_HINT_CONFLICT", slide, attribute));
                break;
            }
        }
        return selected;
    }

    public record Resolution(List<SlideHints> slides, List<HintWarning> warnings) {
        public Resolution { slides = List.copyOf(slides); warnings = List.copyOf(warnings); }
    }
    public record SlideHints(String title, String notes, String layout) {
        public SlideHints { title = safe(title); notes = safe(notes); layout = safe(layout); }
        private static String safe(String value) { return value == null ? "" : value; }
    }
    public record HintWarning(String code, int slideIndex, String attribute) { }
}
