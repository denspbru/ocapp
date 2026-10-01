package com.nicodim.ocapp.pagemodel;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Enforces all model budgets and graph invariants before analysis/pagination. */
public final class PageModelValidator {
    private PageModelValidator() { }

    public static PageModel validate(PageModel model, ConverterProperties.PageModelLimits limits) {
        if (model == null || model.document() == null || model.capture() == null || model.geometry() == null)
            throw invalid("Page model metadata is missing");
        geometry(model.geometry(), limits);
        if (model.blocks().size() > limits.getMaxBlocks()) throw limit("Page model block limit exceeded");
        if (model.assets().size() > limits.getMaxAssets()) throw limit("Page model asset limit exceeded");
        if (model.warnings().size() > limits.getMaxWarnings()) throw limit("Page model warning limit exceeded");
        long text = 0, assetBytes = 0, cells = 0, warningCount = model.warnings().size();
        Set<String> ids = new HashSet<>();
        Set<String> assetIds = new HashSet<>();
        Map<String, PageBlock> byId = new HashMap<>();
        for (PageBlock block : model.blocks()) {
            if (block == null || block.id() == null || block.id().isBlank() || !ids.add(block.id())) throw invalid("Page model block ID is invalid");
            byId.put(block.id(), block);
            if (block.type() == null || block.style() == null) throw invalid("Page model block fields are missing");
            if (block.depth() < 0 || block.depth() > limits.getMaxDepth()) throw limit("Page model depth limit exceeded");
            bounds(block.bounds(), limits);
            if (block.clipBounds() != null) bounds(block.clipBounds(), limits);
            for (TextRun run : block.textRuns()) text = add(text, run.text().length(), limits.getMaxTextLength(), "Page model text limit exceeded");
            for (LinkReference link : block.links()) text = add(text, link.text().length(), limits.getMaxTextLength(), "Page model text limit exceeded");
            if (block.table() != null) {
                cells = add(cells, block.table().cells().size(), limits.getMaxTableCells(), "Page model table-cell limit exceeded");
                for (TableSemantics.Cell cell : block.table().cells()) text = add(text, cell.text().length(), limits.getMaxTextLength(), "Page model text limit exceeded");
            }
            warningCount = add(warningCount, block.warnings().size(), limits.getMaxWarnings(), "Page model warning limit exceeded");
        }
        for (AssetReference asset : model.assets()) {
            assetBytes = asset(asset, assetBytes, limits);
            if (!assetIds.add(asset.id())) throw invalid("Page model asset ID is invalid");
        }
        for (PageBlock block : model.blocks()) {
            if (!block.parentId().isEmpty() && !byId.containsKey(block.parentId())) throw invalid("Page model parent reference is invalid");
            for (String child : block.childIds()) {
                PageBlock value = byId.get(child);
                if (value == null || !block.id().equals(value.parentId())) throw invalid("Page model child reference is invalid");
            }
            for (String overlap : block.overlapIds()) if (!byId.containsKey(overlap)) throw invalid("Page model overlap reference is invalid");
            for (AssetReference asset : block.assets()) if (!assetIds.contains(asset.id())) throw invalid("Page model asset reference is invalid");
        }
        for (String root : model.rootIds()) if (!byId.containsKey(root) || !byId.get(root).parentId().isEmpty()) throw invalid("Page model root reference is invalid");
        return model;
    }

    private static long asset(AssetReference asset, long total, ConverterProperties.PageModelLimits limits) {
        if (asset == null || asset.id() == null || asset.id().isBlank() || asset.kind() == null || asset.uri() == null || asset.estimatedBytes() < 0) throw invalid("Page model asset is invalid");
        return add(total, asset.estimatedBytes(), limits.getMaxAssetBytes(), "Page model asset-byte limit exceeded");
    }
    private static void geometry(PageGeometry value, ConverterProperties.PageModelLimits limits) {
        finitePositive(value.width(), limits); finitePositive(value.height(), limits); bounds(value.viewport(), limits); finite(value.scrollX(), limits); finite(value.scrollY(), limits);
    }
    private static void bounds(Bounds value, ConverterProperties.PageModelLimits limits) {
        if (value == null) throw invalid("Page model bounds are missing");
        finite(value.x(), limits); finite(value.y(), limits); finitePositive(value.width(), limits); finitePositive(value.height(), limits);
        finite(value.right(), limits); finite(value.bottom(), limits);
    }
    private static void finitePositive(double value, ConverterProperties.PageModelLimits limits) { if (value <= 0) throw invalid("Page model geometry is invalid"); finite(value, limits); }
    private static void finite(double value, ConverterProperties.PageModelLimits limits) { if (!Double.isFinite(value) || Math.abs(value) > limits.getMaxCoordinate()) throw invalid("Page model geometry is invalid or unbounded"); }
    private static long add(long current, long amount, long maximum, String message) { if (amount < 0 || current > maximum - amount) throw limit(message); return current + amount; }
    private static ConversionException invalid(String message) { return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGEMODEL_INVALID", message); }
    private static ConversionException limit(String message) { return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "PAGEMODEL_LIMIT_EXCEEDED", message); }
}
