package com.nicodim.ocapp.pagemodel;
import java.util.List;
public record PageBlock(String id, BlockType type, String parentId, List<String> childIds, int depth,
                        int domOrder, int visualOrder, Bounds bounds, Bounds clipBounds,
                        TransformSummary transform, List<String> overlapIds, ComputedStyle style,
                        List<TextRun> textRuns, List<LinkReference> links, ListSemantics list,
                        TableSemantics table, List<AssetReference> assets, AuthoringHints hints,
                        List<ModelWarning> warnings) {
    public PageBlock { childIds=copy(childIds); overlapIds=copy(overlapIds); textRuns=copy(textRuns); links=copy(links); assets=copy(assets); warnings=copy(warnings); parentId=parentId == null ? "" : parentId; transform=transform == null ? TransformSummary.none() : transform; hints=hints == null ? AuthoringHints.none() : hints; }
    private static <T> List<T> copy(List<T> value) { return List.copyOf(value == null ? List.of() : value); }
}
