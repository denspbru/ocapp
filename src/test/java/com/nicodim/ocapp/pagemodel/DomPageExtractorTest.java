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
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;

class DomPageExtractorTest {
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
        assertThat(new DomPageExtractor(limits).limitMap()).containsEntry("blocks",5000).containsEntry("depth",64);
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
