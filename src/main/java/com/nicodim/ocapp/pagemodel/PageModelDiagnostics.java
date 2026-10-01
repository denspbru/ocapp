package com.nicodim.ocapp.pagemodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Explicit diagnostic serialization. The default form omits page text and URL paths/query/fragment. */
public final class PageModelDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private PageModelDiagnostics() { }

    public static String safeCanonicalJson(PageModel model) { return canonical(model, false); }
    public static String canonicalJsonIncludingSensitiveContent(PageModel model) { return canonical(model, true); }

    private static String canonical(PageModel model, boolean sensitive) {
        try { return JSON.writeValueAsString(project(model, sensitive)); }
        catch (JsonProcessingException ex) { throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGEMODEL_DIAGNOSTIC_FAILED", "PageModel diagnostic serialization failed", ex); }
    }
    private static Map<String,Object> project(PageModel m, boolean sensitive) {
        Map<String,Object> out=new LinkedHashMap<>(); out.put("schemaVersion",m.schemaVersion());
        out.put("document",Map.of("source",sensitive?m.document().source().toASCIIString():origin(m.document().source()),"title",sensitive?m.document().title():"[omitted]","language",m.document().language()));
        out.put("capture",m.capture()); out.put("geometry",m.geometry());
        List<Map<String,Object>> blocks=new ArrayList<>();
        for(PageBlock b:m.blocks()){Map<String,Object> value=new LinkedHashMap<>();value.put("id",b.id());value.put("type",b.type());value.put("parentId",b.parentId());value.put("childIds",b.childIds());value.put("depth",b.depth());value.put("domOrder",b.domOrder());value.put("visualOrder",b.visualOrder());value.put("bounds",b.bounds());value.put("clipBounds",b.clipBounds());value.put("transform",b.transform());value.put("overlapIds",b.overlapIds());value.put("style",b.style());value.put("textRuns",sensitive?b.textRuns():Map.of("count",b.textRuns().size(),"characters",b.textRuns().stream().mapToInt(r->r.text().length()).sum()));value.put("links",sensitive?b.links():Map.of("count",b.links().size()));value.put("list",b.list());value.put("table",sensitive?b.table():safeTable(b.table()));value.put("assets",safeAssets(b.assets(),sensitive));value.put("hints",sensitive?b.hints():new AuthoringHints(b.hints().keepTogether(),"[omitted]","[omitted]","[omitted]","[omitted]",b.hints().render()));value.put("warnings",safeWarnings(b.warnings(),sensitive));blocks.add(value);} out.put("blocks",blocks);
        out.put("rootIds",m.rootIds());out.put("assets",safeAssets(m.assets(),sensitive));out.put("warnings",safeWarnings(m.warnings(),sensitive));return out;
    }
    private static Object safeTable(TableSemantics table){if(table==null)return null;return Map.of("rows",table.rows(),"columns",table.columns(),"cellCount",table.cells().size());}
    private static List<Object> safeAssets(List<AssetReference> assets,boolean sensitive){List<Object> out=new ArrayList<>();for(AssetReference a:assets)out.add(sensitive?a:Map.of("id",a.id(),"kind",a.kind(),"uri",origin(a.uri()),"mediaType",a.mediaType(),"estimatedBytes",a.estimatedBytes(),"embedded",a.embedded()));return out;}
    private static List<ModelWarning> safeWarnings(List<ModelWarning> warnings,boolean sensitive){if(sensitive)return warnings;return warnings.stream().map(w->new ModelWarning(w.code(),w.blockId(),"[omitted]")).toList();}
    private static String origin(URI uri){if(uri==null)return "";if("urn".equalsIgnoreCase(uri.getScheme()))return "urn:[omitted]";String authority=uri.getRawAuthority();return uri.getScheme()==null?"[relative]":uri.getScheme()+"://"+(authority==null?"":authority);}
}
