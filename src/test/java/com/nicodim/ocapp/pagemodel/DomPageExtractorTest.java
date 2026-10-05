package com.nicodim.ocapp.pagemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;

class DomPageExtractorTest {
    @Test void extractionScriptImplementsEveryV1HintAndContentFreeValidation() {
        assertThat(DomPageExtractor.SCRIPT).contains("data-pptx-slide", "data-pptx-title", "data-pptx-ignore",
            "data-pptx-keep-together", "data-pptx-notes", "data-pptx-layout", "data-pptx-render",
            "PPTX_HINT_INVALID", "break-before", "title-only");
        assertThat(DomPageExtractor.SCRIPT).doesNotContain("warning('PPTX_HINT_INVALID',id,raw)");
    }

    private ConverterProperties.PageModelLimits limits;
    @BeforeEach void setUp(){limits=new ConverterProperties().getPageModel();}

    @Test void parsesAllBlockSemanticsAndCreatesImmutableValidatedModel(){
        List<Map<String,Object>> blocks=new ArrayList<>();
        String parent="b0"; blocks.add(block(parent,"CONTAINER","",0,0));
        String[] types={"TEXT","IMAGE","LIST","TABLE","SVG","CANVAS","CHART","FALLBACK"};
        for(int i=0;i<types.length;i++){Map<String,Object>b=block("b"+(i+1),types[i],parent,1,i+1);blocks.add(b);}
        blocks.getFirst().put("children",blocks.subList(1,blocks.size()).stream().map(b->(String)b.get("id")).toList());
        blocks.get(1).put("textRuns",List.of(Map.of("text","hello","style",style())));
        blocks.get(1).put("links",List.of(Map.of("text","site","target","https://example.test/path?q=secret")));
        blocks.get(3).put("list",Map.of("ordered",true,"start",3,"level",1,"marker","decimal"));
        blocks.get(4).put("table",Map.of("rows",1,"columns",1,"cells",List.of(Map.of("row",0,"column",0,"rowSpan",1,"columnSpan",1,"header",true,"text","cell"))));
        Map<String,Object> imageAsset=asset("asset-b2","IMAGE","https://cdn.example.test/private.png?token=x",12,false);
        blocks.get(2).put("assets",List.of(imageAsset));
        Map<String,Object> root=result(blocks,List.of(imageAsset));
        root.put("warnings",List.of(Map.of("code","SAFE_CODE","blockId","b1","detail","secret-warning")));
        PageModel model=DomPageExtractor.fromScriptResult(root,URI.create("https://example.test/article?q=secret"),limits);
        assertThat(model.schemaVersion()).isEqualTo("1.0"); assertThat(model.blocks()).extracting(PageBlock::type).containsExactly(BlockType.CONTAINER,BlockType.TEXT,BlockType.IMAGE,BlockType.LIST,BlockType.TABLE,BlockType.SVG,BlockType.CANVAS,BlockType.CHART,BlockType.FALLBACK);
        assertThat(model.blocks().get(1).links()).singleElement().extracting(l->l.target().toString()).isEqualTo("https://example.test/path?q=secret");
        assertThat(model.blocks().get(3).list().ordered()).isTrue(); assertThat(model.blocks().get(4).table().cells()).hasSize(1);
        assertThatThrownBy(()->model.blocks().add(model.blocks().getFirst())).isInstanceOf(UnsupportedOperationException.class);
        String safe=PageModelDiagnostics.safeCanonicalJson(model), full=PageModelDiagnostics.canonicalJsonIncludingSensitiveContent(model);
        assertThat(safe).contains("https://example.test").doesNotContain("secret","hello","private.png");
        assertThat(full).contains("secret","hello","private.png"); assertThat(PageModelDiagnostics.safeCanonicalJson(model)).isEqualTo(safe);
    }

    @Test void extractorExecutesOneScriptPassAndMapsDriverFailures(){
        JavascriptExecutor js=mock(JavascriptExecutor.class); Map<String,Object> value=result(List.of(block("b0","TEXT","",0,0)),List.of());
        when(js.executeScript(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(value);
        PageModel model=new DomPageExtractor(limits).extract(js,URI.create("https://example.test/")); assertThat(model.blocks()).hasSize(1);
        when(js.executeScript(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class))).thenThrow(new WebDriverException("page text must not escape"));
        assertCode(()->new DomPageExtractor(limits).extract(js,URI.create("https://example.test/")),"PAGEMODEL_EXTRACTION_FAILED");
        assertThat(new DomPageExtractor(limits).limitMap()).containsEntry("blocks",5000).containsEntry("depth",64)
            .containsEntry("exportScale",2.0).containsEntry("exportBackground","transparent")
            .containsEntry("exportFormat","png").containsEntry("exportPixels",16_000_000L)
            .containsEntry("exportBytes",8L*1024*1024);
        assertThat(DomPageExtractor.SCRIPT).contains("GRAPHICS_SVG_UNSAFE", "GRAPHICS_CANVAS_EXPORT_FAILED",
            "GRAPHICS_ECHARTS_MISSING", "localized-fallback");
    }

    @Test void valueTypesNormalizeNullsAndProvideNeutralDefaults(){
        assertThat(TransformSummary.none()).isEqualTo(new TransformSummary(false,"none",0,1,1));
        assertThat(AuthoringHints.none().render()).isEqualTo(AuthoringHints.Render.AUTO);
        assertThat(new ModelWarning(null,null,null)).isEqualTo(new ModelWarning("UNKNOWN","",""));
        assertThat(new CaptureMetadata(null,null,null,1,null,null).observations()).isEmpty();
        assertThat(new DocumentMetadata(URI.create("https://x.test"),null,null).title()).isEmpty();
        assertThat(new TextRun(null,null).text()).isEmpty();
        assertThat(new LinkReference(null,URI.create("https://x.test")).text()).isEmpty();
        assertThat(new ListSemantics(false,1,0,null).marker()).isEmpty();
        assertThat(new TableSemantics(0,0,null).cells()).isEmpty();
        assertThat(new TableSemantics.Cell(0,0,1,1,false,null).text()).isEmpty();
        assertThat(new AssetReference("a",AssetReference.Kind.OTHER,URI.create("urn:x"),null,0,false).mediaType()).isEmpty();
        assertThat(new ComputedStyle(null,null,null,null,null,null,null,1,400,null,null,1,1,0,false,false).display()).isEmpty();
        assertThat(new PageBlock("a",BlockType.TEXT,"",null,0,0,0,new Bounds(0,0,1,1),null,null,null,
            styleRecord(),null,null,null,null,null,null,null).childIds()).isEmpty();
    }

    @Test void rejectsScriptLimitsMalformedResultsAndInvalidGraphBeforePagination(){
        for(String error:List.of("BLOCK_LIMIT","DEPTH_LIMIT","TEXT_LIMIT","CELL_LIMIT","ASSET_LIMIT","ASSET_BYTES_LIMIT","WARNING_LIMIT")) assertCode(()->DomPageExtractor.fromScriptResult(Map.of("error",error),URI.create("https://x.test"),limits),"PAGEMODEL_LIMIT_EXCEEDED");
        assertCode(()->DomPageExtractor.fromScriptResult(Map.of("error","GEOMETRY_INVALID"),URI.create("https://x.test"),limits),"PAGEMODEL_INVALID");
        assertCode(()->DomPageExtractor.fromScriptResult(true,URI.create("https://x.test"),limits),"PAGEMODEL_EXTRACTION_FAILED");
        Map<String,Object> malformed=result(List.of(block("b0","TEXT","",0,0)),List.of()); malformed.put("geometry",Map.of("width",Double.NaN,"height",100d,"viewport",bounds(),"scrollX",0d,"scrollY",0d)); assertCode(()->DomPageExtractor.fromScriptResult(malformed,URI.create("https://x.test"),limits),"PAGEMODEL_INVALID");
        Map<String,Object> graph=result(List.of(block("b0","TEXT","missing",1,0)),List.of()); assertCode(()->DomPageExtractor.fromScriptResult(graph,URI.create("https://x.test"),limits),"PAGEMODEL_INVALID");
    }

    @Test void rejectsUnknownOrHostileScriptErrorsWithoutReflectingPageContent(){
        assertThatThrownBy(()->DomPageExtractor.fromScriptResult(Map.of("error","hostile secret from page"),URI.create("https://x.test"),limits))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PAGEMODEL_EXTRACTION_FAILED");
        assertThatThrownBy(()->DomPageExtractor.fromScriptResult(Map.of("error","hostile secret from page"),URI.create("https://x.test"),limits))
            .hasMessageNotContaining("hostile").hasMessageNotContaining("secret");
        assertThat(DomPageExtractor.SCRIPT).doesNotContain("fail(String(e))").contains("internalErrors.has(e)");
    }

    @Test void enforcesMetadataFieldBudgetsBeforeRecordMaterialization(){
        limits.setMaxFieldLength(16);limits.setMaxMetadataCharacters(64);
        Map<String,Object> hint=block("a","TEXT","",0,0);hint.put("hints",Map.of("keepTogether",false,"slide","x".repeat(17),"title","","notes","","layout","","render","AUTO"));assertLimit(result(List.of(hint),List.of()));
        Map<String,Object> uri=block("a","TEXT","",0,0);uri.put("links",List.of(Map.of("text","x","target","https://x.test/"+"q".repeat(20))));assertLimit(result(List.of(uri),List.of()));
        Map<String,Object> styled=block("a","TEXT","",0,0);Map<String,Object>s=new LinkedHashMap<>(style());s.put("fontFamily","s".repeat(17));styled.put("style",s);assertLimit(result(List.of(styled),List.of()));
        Map<String,Object> doc=result(List.of(block("a","TEXT","",0,0)),List.of());doc.put("document",Map.of("title","t".repeat(17),"language","en"));assertLimit(doc);
        assertThat(new DomPageExtractor(limits).limitMap()).containsEntry("field",16).containsEntry("metadata",64);
    }

    @Test void permitsBoundedEmbeddedDataAssetsBeyondMetadataFieldLimit(){
        limits.setMaxFieldLength(16);
        String data="data:image/png;base64,"+"A".repeat(100);
        Map<String,Object> embedded=asset("a","IMAGE",data,75,true);
        Map<String,Object> image=block("image","IMAGE","",0,0);image.put("assets",List.of(embedded));
        PageModel model=DomPageExtractor.fromScriptResult(result(List.of(image),List.of(embedded)),URI.create("https://x.test"),limits);
        assertThat(model.assets()).singleElement().satisfies(a->assertThat(a.uri().toString()).isEqualTo(data));
        limits.setMaxAssetBytes(50);
        assertLimit(result(List.of(image),List.of(embedded)));
    }

    @ParameterizedTest @MethodSource("malformedModels")
    void rejectsMalformedForestNumericAndSemanticModels(Map<String,Object> value){
        assertCode(()->DomPageExtractor.fromScriptResult(value,URI.create("https://x.test"),limits),"PAGEMODEL_INVALID");
    }

    static Stream<Arguments> malformedModels(){
        List<Arguments> out=new ArrayList<>();
        Map<String,Object> duplicateRoots=result(List.of(block("a","TEXT","",0,0)),List.of());duplicateRoots.put("roots",List.of("a","a"));out.add(Arguments.of(duplicateRoots));
        Map<String,Object> missingOwner=result(List.of(block("a","CONTAINER","",0,0),block("b","TEXT","a",1,1)),List.of());out.add(Arguments.of(missingOwner));
        Map<String,Object> depth=result(List.of(block("a","CONTAINER","",0,0),block("b","TEXT","a",2,1)),List.of());
        ((Map<String,Object>)((List<?>)depth.get("blocks")).get(0)).put("children",List.of("b"));out.add(Arguments.of(depth));
        Map<String,Object> orders=result(List.of(block("a","TEXT","",0,0),block("b","TEXT","",0,0)),List.of());out.add(Arguments.of(orders));
        Map<String,Object> overlap=result(List.of(block("a","TEXT","",0,0),block("b","TEXT","",0,1)),List.of());((Map<String,Object>)((List<?>)overlap.get("blocks")).get(0)).put("overlaps",List.of("b","b"));out.add(Arguments.of(overlap));
        Map<String,Object> opacity=result(List.of(block("a","TEXT","",0,0)),List.of());Map<String,Object> os=new LinkedHashMap<>(style());os.put("opacity",1.5d);((Map<String,Object>)((List<?>)opacity.get("blocks")).get(0)).put("style",os);out.add(Arguments.of(opacity));
        Map<String,Object> transform=result(List.of(block("a","TEXT","",0,0)),List.of());((Map<String,Object>)((List<?>)transform.get("blocks")).get(0)).put("transform",Map.of("transformed",true,"matrix","x","rotation",Double.POSITIVE_INFINITY,"scaleX",1d,"scaleY",1d));out.add(Arguments.of(transform));
        Map<String,Object> table=result(List.of(block("a","TABLE","",0,0)),List.of());((Map<String,Object>)((List<?>)table.get("blocks")).get(0)).put("table",Map.of("rows",1,"columns",1,"cells",List.of(Map.of("row",0,"column",0,"rowSpan",2,"columnSpan",1,"header",false,"text","x"))));out.add(Arguments.of(table));
        return out.stream();
    }

    @Test void fractionalIntegralFieldsAreMalformed(){
        Map<String,Object> fractional=new LinkedHashMap<>(asset("x","IMAGE","https://x.test/a",1,false));fractional.put("bytes",1.5d);
        Map<String,Object> value=result(List.of(block("a","TEXT","",0,0)),List.of(fractional));
        assertCode(()->DomPageExtractor.fromScriptResult(value,URI.create("https://x.test"),limits),"PAGEMODEL_EXTRACTION_FAILED");
    }

    @Test void safeDiagnosticRedactsEveryPageControlledStringClass(){
        Map<String,Object> b=block("a","TEXT","",0,0);Map<String,Object>s=new LinkedHashMap<>(style());s.put("fontFamily","SECRET_STYLE");b.put("style",s);b.put("transform",Map.of("transformed",true,"matrix","SECRET_MATRIX","rotation",0d,"scaleX",1d,"scaleY",1d));b.put("hints",Map.of("keepTogether",false,"slide","","title","SECRET_HINT","notes","","layout","","render","AUTO"));b.put("textRuns",List.of(Map.of("text","SECRET_TEXT","style",style())));b.put("list",Map.of("ordered",false,"start",1,"level",0,"marker","SECRET_MARKER"));b.put("table",null);
        Map<String,Object> r=result(List.of(b),List.of());r.put("document",Map.of("title","SECRET_TITLE","language","SECRET_LANG"));r.put("capture",Map.of("userAgent","SECRET_UA","locale","SECRET_LOCALE","timezone","SECRET_TZ","dpr",1d,"readiness","SECRET_READY","observations",List.of("SECRET_OBS")));r.put("warnings",List.of(Map.of("code","SAFE_CODE","blockId","a","detail","SECRET_WARNING")));
        String safe=PageModelDiagnostics.safeCanonicalJson(DomPageExtractor.fromScriptResult(r,URI.create("https://SECRET_USER:SECRET_PASS@example.test/path?q=SECRET_QUERY#frag"),limits));
        assertThat(safe).doesNotContain("SECRET_").doesNotContain("/path").doesNotContain("QUERY");
    }

    @Test void enforcesEveryConfiguredBudgetBeforeLargeModelMaterialization(){
        limits.setMaxBlocks(1); assertLimit(result(List.of(block("a","TEXT","",0,0),block("b","TEXT","",0,1)),List.of())); limits.setMaxBlocks(5000);
        limits.setMaxDepth(1); assertLimit(result(List.of(block("a","TEXT","",2,0)),List.of())); limits.setMaxDepth(64);
        limits.setMaxTextLength(2); Map<String,Object> text=block("a","TEXT","",0,0);text.put("textRuns",List.of(Map.of("text","long","style",style())));assertLimit(result(List.of(text),List.of()));limits.setMaxTextLength(1000000);
        limits.setMaxTableCells(1);Map<String,Object> table=block("a","TABLE","",0,0);table.put("table",Map.of("rows",1,"columns",2,"cells",List.of(cell(0),cell(1))));assertLimit(result(List.of(table),List.of()));limits.setMaxTableCells(20000);
        limits.setMaxAssets(1);assertLimit(result(List.of(block("a","TEXT","",0,0)),List.of(asset("a1","IMAGE","https://x.test/a",0,false),asset("a2","IMAGE","https://x.test/b",0,false))));limits.setMaxAssets(2000);
        limits.setMaxAssetBytes(1);assertLimit(result(List.of(block("a","TEXT","",0,0)),List.of(asset("a1","IMAGE","https://x.test/a",2,false))));limits.setMaxAssetBytes(50L*1024*1024);
        limits.setMaxWarnings(0);Map<String,Object> warned=result(List.of(block("a","TEXT","",0,0)),List.of());warned.put("warnings",List.of(Map.of("code","X","blockId","a","detail","safe")));assertLimit(warned);
        limits.setMaxCoordinate(10);Map<String,Object> coordinate=result(List.of(block("a","TEXT","",0,0)),List.of());coordinate.put("geometry",Map.of("width",100d,"height",100d,"viewport",bounds(),"scrollX",0d,"scrollY",0d));assertCode(()->DomPageExtractor.fromScriptResult(coordinate,URI.create("https://x.test"),limits),"PAGEMODEL_INVALID");
    }

    private void assertLimit(Map<String,Object> value){assertCode(()->DomPageExtractor.fromScriptResult(value,URI.create("https://x.test"),limits),"PAGEMODEL_LIMIT_EXCEEDED");}
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOf(ConversionException.class).extracting("code").isEqualTo(code);}
    private static Map<String,Object> result(List<Map<String,Object>> blocks,List<Map<String,Object>> assets){Map<String,Object> r=new LinkedHashMap<>();r.put("document",Map.of("title","Doc","language","en"));r.put("capture",Map.of("userAgent","Chrome","locale","en","timezone","UTC","dpr",1d,"readiness","complete","observations",List.of("ready")));r.put("geometry",Map.of("width",100d,"height",100d,"viewport",bounds(),"scrollX",0d,"scrollY",0d));r.put("blocks",blocks);r.put("roots",blocks.stream().filter(b->"".equals(b.get("parentId"))).map(b->b.get("id")).toList());r.put("assets",assets);r.put("warnings",List.of());return r;}
    private static Map<String,Object> block(String id,String type,String parent,int depth,int order){Map<String,Object>b=new LinkedHashMap<>();b.put("id",id);b.put("type",type);b.put("parentId",parent);b.put("children",List.of());b.put("depth",depth);b.put("domOrder",order);b.put("visualOrder",order);b.put("bounds",bounds());b.put("clip",null);b.put("transform",Map.of("transformed",false,"matrix","none","rotation",0d,"scaleX",1d,"scaleY",1d));b.put("overlaps",List.of());b.put("style",style());b.put("textRuns",List.of());b.put("links",List.of());b.put("list",null);b.put("table",null);b.put("assets",List.of());b.put("hints",Map.of("keepTogether",false,"slide","","title","","notes","","layout","","render","AUTO"));b.put("warnings",List.of());return b;}
    private static Map<String,Object> bounds(){return Map.of("x",0d,"y",0d,"width",10d,"height",10d);}
    private static Map<String,Object> style(){return Map.ofEntries(Map.entry("display","block"),Map.entry("position","static"),Map.entry("overflowX","visible"),Map.entry("overflowY","visible"),Map.entry("color","rgb(0,0,0)"),Map.entry("backgroundColor","transparent"),Map.entry("fontFamily","sans"),Map.entry("fontSize",16d),Map.entry("fontWeight",400),Map.entry("fontStyle","normal"),Map.entry("textAlign","start"),Map.entry("lineHeight",19.2d),Map.entry("opacity",1d),Map.entry("zIndex",0),Map.entry("flex",false),Map.entry("grid",false));}
    private static Map<String,Object> asset(String id,String kind,String uri,long bytes,boolean embedded){return Map.of("id",id,"kind",kind,"uri",uri,"mediaType","image/png","bytes",bytes,"embedded",embedded);}
    private static Map<String,Object> cell(int col){return Map.of("row",0,"column",col,"rowSpan",1,"columnSpan",1,"header",false,"text","x");}
    private static ComputedStyle styleRecord(){return new ComputedStyle("block","static","visible","visible","black","transparent","sans",16,400,"normal","start",19,1,0,false,false);}
}
