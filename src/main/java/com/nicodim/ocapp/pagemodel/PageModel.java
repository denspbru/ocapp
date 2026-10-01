package com.nicodim.ocapp.pagemodel;
import java.util.List;
public record PageModel(String schemaVersion, DocumentMetadata document, CaptureMetadata capture,
                        PageGeometry geometry, List<PageBlock> blocks, List<String> rootIds,
                        List<AssetReference> assets, List<ModelWarning> warnings) {
    public static final String SCHEMA_VERSION = "1.0";
    public PageModel { blocks=List.copyOf(blocks == null ? List.of() : blocks); rootIds=List.copyOf(rootIds == null ? List.of() : rootIds); assets=List.copyOf(assets == null ? List.of() : assets); warnings=List.copyOf(warnings == null ? List.of() : warnings); }
}
