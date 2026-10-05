package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.pagemodel.AssetReference;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.ComputedStyle;
import com.nicodim.ocapp.pagemodel.LinkReference;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagemodel.TextRun;
import com.nicodim.ocapp.pagemodel.TableSemantics;
import com.nicodim.ocapp.pagination.SlideSlice;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure deterministic M3 classifier. It emits editable text and embedded raster images only when
 * their browser geometry/style is unambiguous. Complex semantic blocks (including M4 lists and
 * tables), transforms, clipping, overlap groups and unsupported styles become localized crops.
 * A crop suppresses every native item under the same pixels, preventing loss and double drawing.
 */
public final class EditableRenderPlanner {
    private static final Set<String> NATIVE_RASTER_MEDIA =
        Set.of("image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp");
    private static final Set<BlockType> LOCALIZED_FALLBACK_TYPES =
        Set.of(BlockType.SVG, BlockType.CANVAS, BlockType.CHART, BlockType.FALLBACK);
    private static final double EPSILON = 0.000_001;

    public List<RenderItem> plan(PageModel model, SlideSlice slice, int maxItemsPerSlide) {
        return plan(model, slice, maxItemsPerSlide, 500, 12, 18);
    }

    public List<RenderItem> plan(PageModel model, SlideSlice slice, int maxItemsPerSlide,
                                 int maxNativeTableCells, int maxNativeTableColumns, double minNativeColumnPoints) {
        if (maxItemsPerSlide < 1) throw new IllegalArgumentException("maxItemsPerSlide must be positive");
        double top = slice.source().y(), bottom = slice.source().bottom();
        List<PageBlock> blocks = model.blocks().stream().filter(b -> overlapsSlice(b, top, bottom))
            .sorted(Comparator.comparingInt(PageBlock::visualOrder).thenComparingInt(PageBlock::domOrder)).toList();
        Map<String, PageBlock> byId = new HashMap<>();
        for (PageBlock block : blocks) byId.put(block.id(), block);

        Set<String> nativeCompositeRoots = new HashSet<>();
        for (PageBlock block : blocks) {
            if ((block.type() == BlockType.LIST || block.type() == BlockType.TABLE)
                && !requiresLocalizedFallback(block, byId)
                && !tablePolicyFallback(block, slice, maxNativeTableCells, maxNativeTableColumns, minNativeColumnPoints))
                nativeCompositeRoots.add(block.id());
        }
        List<RenderItem.Rect> fallback = new ArrayList<>();
        Set<String> fallbackRoots = new HashSet<>();
        for (PageBlock block : blocks) {
            if (hasCompositeAncestor(block, byId, nativeCompositeRoots)) continue;
            if (!requiresLocalizedFallback(block, byId) && !tablePolicyFallback(block, slice, maxNativeTableCells,
                maxNativeTableColumns, minNativeColumnPoints)) continue;
            if (hasFallbackAncestor(block, byId, fallbackRoots)) continue;
            RenderItem.Rect clipped = clippedBounds(block, top, bottom);
            if (clipped != null) {
                fallbackRoots.add(block.id());
                addMerged(fallback, clipped);
            }
        }

        List<RenderItem> nativeItems = new ArrayList<>();
        Set<String> classified = new HashSet<>(fallbackRoots);
        Set<String> nativeRoots = new HashSet<>();
        for (PageBlock block : blocks) {
            if (hasFallbackAncestor(block, byId, fallbackRoots) || hasCompositeAncestor(block, byId, nativeRoots)) continue;
            RenderItem nativeItem = toNative(block, slice, maxNativeTableCells, maxNativeTableColumns, minNativeColumnPoints, byId);
            if (nativeItem == null) continue;
            boolean fragmentable = nativeItem instanceof RenderItem.NativeBackground || nativeItem instanceof RenderItem.NativeTable;
            if (!fragmentable && (block.bounds().y() < top - EPSILON || block.bounds().bottom() > bottom + EPSILON)) continue;
            RenderItem.Rect clipped = fragmentable ? clip(nativeItem.bounds(), top, bottom) : nativeItem.bounds();
            if (clipped == null || (!(nativeItem instanceof RenderItem.NativeBackground) && overlapsAny(clipped, fallback))) continue;
            nativeItems.add(withBounds(nativeItem, clipped));
            classified.add(block.id());
            if (nativeItem instanceof RenderItem.NativeTable || nativeItem instanceof RenderItem.NativeListItem) nativeRoots.add(block.id());
        }

        // Unsupported leaves and unsupported assets still need pixels. Containers with separately
        // classified descendants are structural and are intentionally not duplicated as crops.
        for (int i = blocks.size() - 1; i >= 0; i--) {
            PageBlock block = blocks.get(i);
            if (classified.contains(block.id()) || hasCompositeAncestor(block, byId, nativeRoots)
                || hasClassifiedDescendant(block, byId, classified)) continue;
            RenderItem.Rect clipped = clippedBounds(block, top, bottom);
            if (clipped == null || containedByAny(clipped, fallback)) continue;
            addMerged(fallback, clipped);
            classified.add(block.id());
        }

        // A newly added leaf crop may cover a native item. Remove it rather than drawing content twice.
        nativeItems.removeIf(item -> !(item instanceof RenderItem.NativeBackground) && overlapsAny(item.bounds(), fallback));
        List<RenderItem> result = new ArrayList<>(fallback.size() + nativeItems.size());
        nativeItems.stream().filter(RenderItem.NativeBackground.class::isInstance).forEach(result::add);
        fallback.stream().sorted(Comparator.comparingDouble(RenderItem.Rect::y).thenComparingDouble(RenderItem.Rect::x))
            .map(RenderItem.ScreenshotCrop::new).forEach(result::add);
        nativeItems.stream().filter(item -> !(item instanceof RenderItem.NativeBackground)).forEach(result::add);
        if (result.size() > maxItemsPerSlide) {
            return List.of(new RenderItem.ScreenshotCrop(new RenderItem.Rect(0, top, model.geometry().width(), bottom - top)));
        }
        return List.copyOf(result);
    }

    private static boolean requiresLocalizedFallback(PageBlock block, Map<String, PageBlock> byId) {
        if (LOCALIZED_FALLBACK_TYPES.contains(block.type()) || block.transform().transformed()
            || !block.overlapIds().isEmpty() || block.clipBounds() != null) return true;
        ComputedStyle style = block.style();
        if (style == null || !Double.isFinite(style.opacity()) || Math.abs(style.opacity() - 1) > EPSILON) return true;
        java.awt.Color background = CssColors.parse(style.backgroundColor());
        if (background == null || (background.getAlpha() != 0 && background.getAlpha() != 255)) return true;
        if (block.type() == BlockType.TEXT) return !supportedText(block);
        if (block.type() == BlockType.LIST) return !supportedList(block);
        if (block.type() == BlockType.TABLE) return !supportedTableStyle(block);
        if (block.type() == BlockType.IMAGE) return nativeImageAsset(block) == null;
        return false;
    }

    private static boolean supportedText(PageBlock block) {
        if (block.textRuns().isEmpty()) return false;
        if (!supportedAlignment(block.style().textAlign()) || !Double.isFinite(block.style().lineHeight())
            || block.style().lineHeight() <= 0 || !CssColors.supported(block.style().backgroundColor())) return false;
        for (TextRun run : block.textRuns()) {
            ComputedStyle style = run.style();
            if (style == null || !Double.isFinite(style.fontSize()) || style.fontSize() <= 0
                || style.fontWeight() < 1 || style.fontWeight() > 1000 || !CssColors.supported(style.color())) return false;
        }
        return block.textRuns().stream().anyMatch(run -> !run.text().isBlank());
    }

    private static boolean supportedAlignment(String value) {
        String align = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return Set.of("", "start", "left", "center", "right", "end", "justify").contains(align);
    }

    private static RenderItem toNative(PageBlock block, SlideSlice slice, int maxCells, int maxColumns,
                                       double minColumnPoints, Map<String, PageBlock> byId) {
        if (requiresLocalizedFallback(block, byId)) return null;
        if (block.type() == BlockType.TEXT || block.type() == BlockType.LIST) {
            URI defaultLink = block.links().size() == 1 ? block.links().getFirst().target() : null;
            List<RenderItem.TextSpan> spans = textSpans(block, defaultLink);
            if (spans.isEmpty()) return null;
            if (block.type() == BlockType.LIST) {
                var list = block.list();
                return new RenderItem.NativeListItem(toRect(block.bounds()), list.ordered(), list.start(), list.level(),
                    list.marker(), block.style().textAlign(), block.style().lineHeight(), spans);
            }
            return new RenderItem.NativeText(toRect(block.bounds()), block.style().textAlign(), block.style().lineHeight(),
                block.style().backgroundColor(), spans);
        }
        if (block.type() == BlockType.TABLE) return nativeTable(block, slice, maxCells, maxColumns, minColumnPoints);
        if (block.type() == BlockType.CONTAINER) {
            java.awt.Color background = CssColors.parse(block.style().backgroundColor());
            if (background != null && background.getAlpha() == 255) {
                return new RenderItem.NativeBackground(toRect(block.bounds()), block.style().backgroundColor());
            }
            return null;
        }
        AssetReference asset = nativeImageAsset(block);
        return asset == null ? null : new RenderItem.NativeImage(toRect(block.bounds()), asset);
    }

    private static List<RenderItem.TextSpan> textSpans(PageBlock block, URI defaultLink) {
        List<RenderItem.TextSpan> spans = new ArrayList<>();
        for (TextRun run : block.textRuns()) {
            if (run.text().isBlank()) continue;
            URI link = linkFor(run.text(), block.links(), defaultLink);
            spans.add(new RenderItem.TextSpan(run.text(), run.style().fontFamily(), run.style().fontSize() * 72d / 96d,
                run.style().fontWeight(), "italic".equalsIgnoreCase(run.style().fontStyle()), run.style().color(), link));
        }
        return spans;
    }

    private static boolean supportedList(PageBlock block) {
        if (block.list() == null || block.list().level() > 8 || !supportedText(block)) return false;
        String marker = block.list().marker().toLowerCase(Locale.ROOT);
        return marker.isBlank() || Set.of("disc", "circle", "square", "decimal", "decimal-leading-zero",
            "lower-alpha", "upper-alpha", "lower-roman", "upper-roman", "none").contains(marker);
    }

    private static boolean supportedTableStyle(PageBlock block) {
        TableSemantics table = block.table();
        if (table == null || table.rows() < 1 || table.columns() < 1 || table.cells().isEmpty()) return false;
        for (TableSemantics.Cell cell : table.cells()) {
            if (!CssColors.supported(cell.fillColor()) || !CssColors.supported(cell.textColor())
                || !supportedAlignment(cell.textAlign()) || cell.fontSize() <= 0) return false;
            for (TableSemantics.Border border : List.of(cell.top(), cell.right(), cell.bottom(), cell.left()))
                if (border.widthPixels() > 0 && (!CssColors.supported(border.color())
                    || !Set.of("solid", "dashed", "dotted", "double", "none", "hidden").contains(border.style().toLowerCase(Locale.ROOT)))) return false;
        }
        return true;
    }

    private static boolean tablePolicyFallback(PageBlock block, SlideSlice slice, int maxCells, int maxColumns, double minColumnPoints) {
        if (block.type() != BlockType.TABLE || block.table() == null) return false;
        double mappedWidth = block.bounds().width() * slice.viewport().widthPoints() / slice.source().width();
        boolean paginated = block.bounds().y() < slice.source().y() - EPSILON || block.bounds().bottom() > slice.source().bottom() + EPSILON;
        return block.table().cells().size() > maxCells || block.table().columns() > maxColumns
            || mappedWidth / block.table().columns() < minColumnPoints
            || paginated && block.table().cells().stream().anyMatch(c -> c.rowSpan() > 1);
    }

    private static RenderItem nativeTable(PageBlock block, SlideSlice slice, int maxCells, int maxColumns, double minColumnPoints) {
        TableSemantics table = block.table();
        if (tablePolicyFallback(block, slice, maxCells, maxColumns, minColumnPoints)) return null;
        List<Double> heights = table.rowHeights().size() == table.rows() ? table.rowHeights()
            : java.util.Collections.nCopies(table.rows(), block.bounds().height() / table.rows());
        List<Integer> rows = new ArrayList<>();
        for (int row = 0; row < table.headerRows(); row++) rows.add(row);
        double y = block.bounds().y();
        for (int row = 0; row < table.rows(); row++) {
            double h = heights.get(row), center = y + h / 2;
            if (row >= table.headerRows() && center >= slice.source().y() - EPSILON && center < slice.source().bottom() + EPSILON) rows.add(row);
            y += h;
        }
        if (rows.size() == table.headerRows()) return null;
        double sourceHeight = rows.stream().mapToDouble(heights::get).sum();
        double top = Math.max(block.bounds().y(), slice.source().y());
        double available = slice.source().bottom() - top;
        if (available <= EPSILON) return null;
        return new RenderItem.NativeTable(new RenderItem.Rect(block.bounds().x(), top, block.bounds().width(), Math.min(sourceHeight, available)), table, rows);
    }

    private static URI linkFor(String text, List<LinkReference> links, URI defaultLink) {
        for (LinkReference link : links) if ((link.text().equals(text) || link.text().trim().equals(text.trim()))
            && safeHyperlink(link.target())) return link.target();
        return safeHyperlink(defaultLink) ? defaultLink : null;
    }

    private static boolean safeHyperlink(URI target) {
        if (target == null || target.getScheme() == null) return false;
        return Set.of("http", "https", "mailto").contains(target.getScheme().toLowerCase(Locale.ROOT));
    }

    private static AssetReference nativeImageAsset(PageBlock block) {
        if (block.type() != BlockType.IMAGE) return null;
        return block.assets().stream().filter(a -> a.kind() == AssetReference.Kind.IMAGE && a.embedded()
            && a.uri() != null && "data".equalsIgnoreCase(a.uri().getScheme())
            && a.uri().toString().substring(0, Math.min(a.uri().toString().length(), 256)).toLowerCase(Locale.ROOT).contains(";base64,")
            && NATIVE_RASTER_MEDIA.contains(a.mediaType().toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    private static boolean hasFallbackAncestor(PageBlock block, Map<String, PageBlock> byId, Set<String> roots) {
        String parent = block.parentId();
        for (int i = 0; !parent.isEmpty() && i <= byId.size(); i++) {
            if (roots.contains(parent)) return true;
            PageBlock value = byId.get(parent);
            if (value == null) return false;
            parent = value.parentId();
        }
        return false;
    }

    private static boolean hasCompositeAncestor(PageBlock block, Map<String, PageBlock> byId, Set<String> roots) {
        String parent = block.parentId();
        for (int i = 0; !parent.isEmpty() && i <= byId.size(); i++) {
            if (roots.contains(parent)) {
                PageBlock root = byId.get(parent);
                return root == null || root.type() == BlockType.TABLE || block.type() != BlockType.LIST;
            }
            PageBlock value = byId.get(parent);
            if (value == null) return false;
            parent = value.parentId();
        }
        return false;
    }

    private static boolean hasClassifiedDescendant(PageBlock block, Map<String, PageBlock> byId, Set<String> classified) {
        ArrayList<String> pending = new ArrayList<>(block.childIds());
        for (int i = 0; i < pending.size() && i <= byId.size(); i++) {
            String id = pending.get(i);
            if (classified.contains(id)) return true;
            PageBlock child = byId.get(id);
            if (child != null) pending.addAll(child.childIds());
        }
        return false;
    }

    private static RenderItem.Rect clippedBounds(PageBlock block, double top, double bottom) {
        Bounds bounds = block.clipBounds() == null ? block.bounds() : block.clipBounds();
        return clip(toRect(bounds), top, bottom);
    }

    private static void addMerged(List<RenderItem.Rect> values, RenderItem.Rect candidate) {
        for (int i = 0; i < values.size();) {
            RenderItem.Rect existing = values.get(i);
            if (intersectsOrTouches(candidate, existing)) {
                candidate = candidate.union(existing);
                values.remove(i);
                i = 0;
            } else i++;
        }
        values.add(candidate);
    }

    private static boolean intersectsOrTouches(RenderItem.Rect a, RenderItem.Rect b) {
        return a.x() <= b.right() + EPSILON && a.right() + EPSILON >= b.x()
            && a.y() <= b.bottom() + EPSILON && a.bottom() + EPSILON >= b.y();
    }

    private static boolean overlapsSlice(PageBlock block, double top, double bottom) {
        return block.bounds().bottom() > top + EPSILON && block.bounds().y() < bottom - EPSILON;
    }
    private static RenderItem withBounds(RenderItem item, RenderItem.Rect bounds) {
        return switch (item) {
            case RenderItem.NativeText t -> new RenderItem.NativeText(bounds, t.textAlign(), t.lineHeightPixels(), t.backgroundColorCss(), t.spans());
            case RenderItem.NativeBackground background -> new RenderItem.NativeBackground(bounds, background.colorCss());
            case RenderItem.NativeImage i -> new RenderItem.NativeImage(bounds, i.asset());
            case RenderItem.NativeListItem l -> new RenderItem.NativeListItem(bounds, l.ordered(), l.start(), l.level(), l.marker(), l.textAlign(), l.lineHeightPixels(), l.spans());
            case RenderItem.NativeTable t -> new RenderItem.NativeTable(bounds, t.table(), t.rows());
            case RenderItem.ScreenshotCrop ignored -> new RenderItem.ScreenshotCrop(bounds);
        };
    }
    private static RenderItem.Rect clip(RenderItem.Rect rect, double top, double bottom) {
        double y = Math.max(rect.y(), top), end = Math.min(rect.bottom(), bottom);
        return end <= y + EPSILON ? null : new RenderItem.Rect(rect.x(), y, rect.width(), end - y);
    }
    private static boolean overlapsAny(RenderItem.Rect rect, List<RenderItem.Rect> values) {
        return values.stream().anyMatch(rect::intersects);
    }
    private static boolean containedByAny(RenderItem.Rect rect, List<RenderItem.Rect> values) {
        return values.stream().anyMatch(other -> rect.x() >= other.x() - EPSILON && rect.right() <= other.right() + EPSILON
            && rect.y() >= other.y() - EPSILON && rect.bottom() <= other.bottom() + EPSILON);
    }
    private static RenderItem.Rect toRect(Bounds value) {
        return new RenderItem.Rect(value.x(), value.y(), value.width(), value.height());
    }
}
