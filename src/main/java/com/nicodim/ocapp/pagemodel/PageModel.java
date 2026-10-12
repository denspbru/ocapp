package com.nicodim.ocapp.pagemodel;
import java.util.List;
/**
 * Validated page-model schema. {@code recognizedAttributePresent} is an additive page-level hint-presence
 * bit; browser producers may omit it, in which case block hints and hint warnings are used for compatibility.
 */
public record PageModel(String schemaVersion, DocumentMetadata document, CaptureMetadata capture,
                        PageGeometry geometry, List<PageBlock> blocks, List<String> rootIds,
                        List<AssetReference> assets, List<ModelWarning> warnings,
                        boolean recognizedAttributePresent) {
    public static final String SCHEMA_VERSION = "1.0";
    public PageModel {
        blocks=List.copyOf(blocks == null ? List.of() : blocks); rootIds=List.copyOf(rootIds == null ? List.of() : rootIds);
        assets=List.copyOf(assets == null ? List.of() : assets); warnings=List.copyOf(warnings == null ? List.of() : warnings);
        recognizedAttributePresent |= inferRecognizedAttributePresent(blocks, warnings);
    }
    /** Backward-compatible constructor for producers of the original 1.0 shape. */
    public PageModel(String schemaVersion, DocumentMetadata document, CaptureMetadata capture,
                     PageGeometry geometry, List<PageBlock> blocks, List<String> rootIds,
                     List<AssetReference> assets, List<ModelWarning> warnings) {
        this(schemaVersion, document, capture, geometry, blocks, rootIds, assets, warnings,
            inferRecognizedAttributePresent(blocks, warnings));
    }
    private static boolean inferRecognizedAttributePresent(List<PageBlock> blocks, List<ModelWarning> warnings) {
        if (blocks != null && blocks.stream().filter(java.util.Objects::nonNull)
            .anyMatch(block -> block.hints() != null && block.hints().hasRecognizedAttribute())) return true;
        return warnings != null && warnings.stream().filter(java.util.Objects::nonNull)
            .anyMatch(warning -> warning.code().startsWith("PPTX_HINT_"));
    }
}
