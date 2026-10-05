package com.nicodim.ocapp.pagemodel;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;
import org.springframework.http.HttpStatus;

/** Trusted, dependency-free DOM extraction adapter. Page content is never logged here. */
public final class DomPageExtractor {
    private final ConverterProperties.PageModelLimits limits;
    public DomPageExtractor(ConverterProperties.PageModelLimits limits) { this.limits = limits; }

    public PageModel extract(JavascriptExecutor javascript, URI source) {
        try {
            Object raw = javascript.executeScript(SCRIPT, limitMap());
            return fromScriptResult(raw, source, limits);
        } catch (ConversionException ex) { throw ex; }
        catch (WebDriverException | IllegalArgumentException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGEMODEL_EXTRACTION_FAILED", "DOM extraction returned malformed data", ex);
        }
    }

    Map<String,Object> limitMap() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("blocks", limits.getMaxBlocks()); result.put("depth", limits.getMaxDepth());
        result.put("text", limits.getMaxTextLength()); result.put("cells", limits.getMaxTableCells());
        result.put("assets", limits.getMaxAssets()); result.put("assetBytes", limits.getMaxAssetBytes());
        result.put("coordinate", limits.getMaxCoordinate()); result.put("warnings", limits.getMaxWarnings());
        result.put("overlapChecks", limits.getMaxOverlapChecks());
        result.put("field", limits.getMaxFieldLength()); result.put("metadata", limits.getMaxMetadataCharacters());
        return result;
    }

    static PageModel fromScriptResult(Object raw, URI source, ConverterProperties.PageModelLimits limits) {
        Map<?,?> root = map(raw, "result");
        String error = optionalString(root.get("error"));
        if (!error.isEmpty()) {
            if ("GEOMETRY_INVALID".equals(error)) throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGEMODEL_INVALID", "DOM extraction returned invalid geometry");
            if (LIMIT_ERRORS.contains(error)) throw new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "PAGEMODEL_LIMIT_EXCEEDED", "DOM extraction exceeded a PageModel boundary");
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGEMODEL_EXTRACTION_FAILED", "DOM extraction failed");
        }
        Map<?,?> doc = map(root.get("document"), "document");
        Map<?,?> capture = map(root.get("capture"), "capture");
        Map<?,?> geometry = map(root.get("geometry"), "geometry");
        List<?> rawBlocks = list(root.get("blocks"), "blocks");
        if (rawBlocks.size() > limits.getMaxBlocks()) throw malformedLimit("block");
        preflightRawResult(root, rawBlocks, limits);
        List<PageBlock> blocks = new ArrayList<>(rawBlocks.size());
        for (Object value : rawBlocks) blocks.add(block(map(value, "block"), limits));
        List<AssetReference> assets = assets(list(root.get("assets"), "assets"), limits);
        List<ModelWarning> warnings = warnings(root.get("warnings"), limits);
        PageModel model = new PageModel(PageModel.SCHEMA_VERSION,
            new DocumentMetadata(source, string(doc.get("title")), string(doc.get("language"))),
            new CaptureMetadata(string(capture.get("userAgent")), string(capture.get("locale")), string(capture.get("timezone")), number(capture.get("dpr")), string(capture.get("readiness")), strings(capture.get("observations"))),
            new PageGeometry(number(geometry.get("width")), number(geometry.get("height")), bounds(map(geometry.get("viewport"), "viewport")), number(geometry.get("scrollX")), number(geometry.get("scrollY"))),
            blocks, strings(root.get("roots")), assets, warnings);
        return PageModelValidator.validate(model, limits);
    }

    private static final java.util.Set<String> LIMIT_ERRORS = java.util.Set.of("BLOCK_LIMIT","DEPTH_LIMIT","TEXT_LIMIT","CELL_LIMIT","ASSET_LIMIT","ASSET_BYTES_LIMIT","WARNING_LIMIT","FIELD_LIMIT","METADATA_LIMIT");
    private static void preflightRawResult(Map<?,?> root,List<?> blocks,ConverterProperties.PageModelLimits limits) {
        long text=0,cells=0,assets=0,warnings=list(root.get("warnings"),"warnings").size(),metadata=0;
        metadata=metadataMap(map(root.get("document"),"document"),metadata,limits);metadata=metadataMap(map(root.get("capture"),"capture"),metadata,limits);
        for(Object value:blocks){Map<?,?> b=map(value,"block");metadata=metadataMap(b,metadata,limits,"textRuns","links","table","assets","bounds","clip","warnings");List<?> runs=list(b.get("textRuns"),"text runs"),links=list(b.get("links"),"links");assets=boundedAdd(assets,list(b.get("assets"),"block assets").size(),limits.getMaxAssets(),"asset");List<?> blockWarnings=list(b.get("warnings"),"warnings");warnings=boundedAdd(warnings,blockWarnings.size(),limits.getMaxWarnings(),"warning");for(Object warning:blockWarnings)metadata=metadataMap(map(warning,"warning"),metadata,limits);for(Object run:runs){Map<?,?>r=map(run,"text run");text=boundedAdd(text,string(r.get("text")).length(),limits.getMaxTextLength(),"text");metadata=metadataMap(map(r.get("style"),"run style"),metadata,limits);}for(Object link:links){Map<?,?>l=map(link,"link");text=boundedAdd(text,string(l.get("text")).length(),limits.getMaxTextLength(),"text");metadata=metadataString(l.get("target"),metadata,limits);}Map<?,?> table=optionalMap(b.get("table"));if(table!=null){List<?> rawCells=list(table.get("cells"),"table cells");cells=boundedAdd(cells,rawCells.size(),limits.getMaxTableCells(),"table-cell");for(Object cell:rawCells)text=boundedAdd(text,string(map(cell,"cell").get("text")).length(),limits.getMaxTextLength(),"text");}}
        for(Object value:list(root.get("assets"),"assets")){Map<?,?> asset=map(value,"asset");metadata=metadataMap(asset,metadata,limits,"bytes","embedded","uri");String uri=string(asset.get("uri"));if(!uri.regionMatches(true,0,"data:",0,5))metadata=metadataString(uri,metadata,limits);}
        for(Object value:list(root.get("warnings"),"warnings"))metadata=metadataMap(map(value,"warning"),metadata,limits);
    }
    private static long metadataMap(Map<?,?> map,long total,ConverterProperties.PageModelLimits limits,String... excluded){java.util.Set<String>x=java.util.Set.of(excluded);for(var e:map.entrySet()){if(x.contains(String.valueOf(e.getKey())))continue;Object v=e.getValue();if(v instanceof String){total=metadataString(v,total,limits);}else if(v instanceof List<?> values){for(Object item:values)if(item instanceof String)total=metadataString(item,total,limits);}else if(v instanceof Map<?,?> nested){total=metadataMap(nested,total,limits);}}return total;}
    private static long metadataString(Object value,long total,ConverterProperties.PageModelLimits limits){String s=string(value);if(s.length()>limits.getMaxFieldLength())throw malformedLimit("field");return boundedAdd(total,s.length(),limits.getMaxMetadataCharacters(),"metadata");}
    private static long boundedAdd(long current,long amount,long maximum,String field){if(amount<0||current>maximum-amount)throw malformedLimit(field);return current+amount;}

    private static PageBlock block(Map<?,?> m, ConverterProperties.PageModelLimits limits) {
        List<?> rawAssets = list(m.get("assets"), "block assets");
        List<?> rawRuns = list(m.get("textRuns"), "text runs");
        List<?> rawLinks = list(m.get("links"), "links");
        if (rawRuns.size() > limits.getMaxTextLength() || rawLinks.size() > limits.getMaxTextLength()) throw malformedLimit("text");
        ComputedStyle style = style(map(m.get("style"), "style"));
        List<TextRun> runs = new ArrayList<>(rawRuns.size());
        for (Object value : rawRuns) { Map<?,?> run=map(value,"text run"); runs.add(new TextRun(string(run.get("text")), style(map(run.get("style"),"run style")))); }
        List<LinkReference> links = new ArrayList<>(rawLinks.size());
        for (Object value : rawLinks) { Map<?,?> link=map(value,"link"); links.add(new LinkReference(string(link.get("text")), uri(link.get("target")))); }
        Map<?,?> t = optionalMap(m.get("transform"));
        TransformSummary transform = t == null ? TransformSummary.none() : new TransformSummary(bool(t.get("transformed")), string(t.get("matrix")), number(t.get("rotation")), number(t.get("scaleX")), number(t.get("scaleY")));
        Map<?,?> h = optionalMap(m.get("hints"));
        AuthoringHints hints = h == null ? AuthoringHints.none() : new AuthoringHints(bool(h.get("keepTogether")),string(h.get("slide")),string(h.get("title")),string(h.get("notes")),string(h.get("layout")),enumValue(AuthoringHints.Render.class,string(h.get("render")),null));
        return new PageBlock(string(m.get("id")), enumValue(BlockType.class,string(m.get("type")),null), string(m.get("parentId")), strings(m.get("children")), integer(m.get("depth")), integer(m.get("domOrder")), integer(m.get("visualOrder")), bounds(map(m.get("bounds"),"bounds")), nullableBounds(m.get("clip")), transform, strings(m.get("overlaps")), style, runs, links, listSemantics(m.get("list")), table(m.get("table"), limits), assets(rawAssets, limits), hints, warnings(m.get("warnings"), limits));
    }

    private static ComputedStyle style(Map<?,?> s) { return new ComputedStyle(string(s.get("display")),string(s.get("position")),string(s.get("overflowX")),string(s.get("overflowY")),string(s.get("color")),string(s.get("backgroundColor")),string(s.get("fontFamily")),number(s.get("fontSize")),integer(s.get("fontWeight")),string(s.get("fontStyle")),string(s.get("textAlign")),number(s.get("lineHeight")),number(s.get("opacity")),integer(s.get("zIndex")),bool(s.get("flex")),bool(s.get("grid"))); }
    private static ListSemantics listSemantics(Object raw) { Map<?,?> m=optionalMap(raw); return m == null ? null : new ListSemantics(bool(m.get("ordered")),integer(m.get("start")),integer(m.get("level")),string(m.get("marker"))); }
    private static TableSemantics table(Object raw, ConverterProperties.PageModelLimits limits) {
        Map<?,?> m=optionalMap(raw); if (m == null) return null; List<?> values=list(m.get("cells"),"table cells"); if(values.size()>limits.getMaxTableCells()) throw malformedLimit("table-cell");
        List<TableSemantics.Cell> cells=new ArrayList<>(values.size());
        for(Object value:values){Map<?,?> c=map(value,"cell");boolean header=bool(c.get("header"));cells.add(new TableSemantics.Cell(integer(c.get("row")),integer(c.get("column")),integer(c.get("rowSpan")),integer(c.get("columnSpan")),header,string(c.get("text")),optionalString(c,"fill","transparent"),optionalString(c,"color","black"),optionalString(c,"fontFamily","Arial"),optionalNumber(c,"fontSize",12),optionalInteger(c,"fontWeight",header?700:400),optionalString(c,"textAlign","left"),optionalString(c,"verticalAlign","middle"),border(c.get("top")),border(c.get("right")),border(c.get("bottom")),border(c.get("left"))));}
        return new TableSemantics(integer(m.get("rows")),integer(m.get("columns")),numbers(m.get("columnWidths")),numbers(m.get("rowHeights")),optionalInteger(m,"headerRows",0),cells);
    }
    private static TableSemantics.Border border(Object raw){if(raw==null)return TableSemantics.Border.none();Map<?,?> b=map(raw,"table border");return new TableSemantics.Border(number(b.get("width")),string(b.get("color")),string(b.get("style")));}
    private static List<Double> numbers(Object value){if(value==null)return List.of();List<?> values=list(value,"number list");List<Double> result=new ArrayList<>(values.size());for(Object item:values)result.add(number(item));return result;}
    private static String optionalString(Map<?,?> map,String key,String fallback){return map.containsKey(key)?string(map.get(key)):fallback;}
    private static double optionalNumber(Map<?,?> map,String key,double fallback){return map.containsKey(key)?number(map.get(key)):fallback;}
    private static int optionalInteger(Map<?,?> map,String key,int fallback){return map.containsKey(key)?integer(map.get(key)):fallback;}
    private static List<AssetReference> assets(List<?> values, ConverterProperties.PageModelLimits limits) { if(values.size()>limits.getMaxAssets()) throw malformedLimit("asset"); List<AssetReference> result=new ArrayList<>(values.size()); for(Object value:values){Map<?,?> a=map(value,"asset");result.add(new AssetReference(string(a.get("id")),enumValue(AssetReference.Kind.class,string(a.get("kind")),null),uri(a.get("uri")),string(a.get("mediaType")),longNumber(a.get("bytes")),bool(a.get("embedded"))));} return result; }
    private static List<ModelWarning> warnings(Object raw, ConverterProperties.PageModelLimits limits) { List<?> values=list(raw,"warnings"); if(values.size()>limits.getMaxWarnings()) throw malformedLimit("warning"); List<ModelWarning> result=new ArrayList<>(values.size()); for(Object value:values){Map<?,?> w=map(value,"warning");result.add(new ModelWarning(string(w.get("code")),string(w.get("blockId")),string(w.get("detail"))));} return result; }
    private static Bounds nullableBounds(Object raw) { Map<?,?> value=optionalMap(raw); return value == null ? null : bounds(value); }
    private static Bounds bounds(Map<?,?> m) { return new Bounds(number(m.get("x")),number(m.get("y")),number(m.get("width")),number(m.get("height"))); }
    private static URI uri(Object value) { try { return URI.create(string(value)); } catch(RuntimeException ex) { throw malformed("URI",ex); } }
    private static Map<?,?> map(Object value,String field) { if(value instanceof Map<?,?> m)return m; throw malformed(field,null); }
    private static Map<?,?> optionalMap(Object value) { if(value == null)return null; return map(value,"object"); }
    private static List<?> list(Object value,String field) { if(value instanceof List<?> l)return l; throw malformed(field,null); }
    private static List<String> strings(Object value) { List<?> values=list(value,"list"); List<String> result=new ArrayList<>(values.size()); for(Object item:values)result.add(string(item)); return result; }
    private static String string(Object value) { if(value instanceof String s)return s; if(value==null)return ""; throw malformed("string",null); }
    private static String optionalString(Object value) { return value == null ? "" : string(value); }
    private static boolean bool(Object value) { if(value instanceof Boolean b)return b; throw malformed("boolean",null); }
    private static double number(Object value) { if(value instanceof Number n)return n.doubleValue(); throw malformed("number",null); }
    private static long longNumber(Object value) { if(value instanceof Number n){double d=n.doubleValue();long v=n.longValue();if(Double.isFinite(d)&&d==v)return v;}throw malformed("integer",null); }
    private static int integer(Object value) { long n=longNumber(value); if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw malformed("integer",null); return (int)n; }
    private static <E extends Enum<E>> E enumValue(Class<E> type,String value,E fallback) { try{return Enum.valueOf(type,value.toUpperCase(Locale.ROOT));}catch(RuntimeException ex){if(fallback!=null)return fallback;throw malformed("enum",ex);} }
    private static ConversionException malformed(String field,Throwable cause) { return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY,"PAGEMODEL_EXTRACTION_FAILED","DOM extraction returned malformed " + field,cause); }
    private static ConversionException malformedLimit(String field) { return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE,"PAGEMODEL_LIMIT_EXCEEDED","DOM extraction exceeded the " + field + " limit"); }

    static final String SCRIPT = """
        const L=arguments[0], fail=code=>({error:code});
        const finite=(n)=>typeof n==='number' && Number.isFinite(n) && Math.abs(n)<=L.coordinate;
        const rect=(r)=>({x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height});
        const validRect=(r)=>finite(r.x)&&finite(r.y)&&finite(r.width)&&finite(r.height)&&r.width>0&&r.height>0&&finite(r.x+r.width)&&finite(r.y+r.height);
        const ignored=new Set(['HTML','HEAD','META','LINK','STYLE','SCRIPT','NOSCRIPT','TEMPLATE','BR']);
        const blocks=[], assets=[], warnings=[], byElement=new Map(), internalErrors=new Set(['BLOCK_LIMIT','DEPTH_LIMIT','TEXT_LIMIT','CELL_LIMIT','ASSET_LIMIT','ASSET_BYTES_LIMIT','WARNING_LIMIT','FIELD_LIMIT','METADATA_LIMIT','GEOMETRY_INVALID']); let textLength=0,cells=0,assetBytes=0,warningCount=0,metadataLength=0,domOrder=0,visited=0;
        const takeMeta=(v)=>{const s=typeof v==='string'?v:String(v??'');if(s.length>L.field)throw 'FIELD_LIMIT';if(metadataLength+s.length>L.metadata)throw 'METADATA_LIMIT';metadataLength+=s.length;return s};
        const accountMeta=(o)=>{for(const v of Object.values(o))if(typeof v==='string')takeMeta(v)};
        const warning=(code,id,detail)=>{if(++warningCount>L.warnings)throw 'WARNING_LIMIT'; const w={code:takeMeta(code),blockId:takeMeta(id||''),detail:takeMeta(detail||'')};warnings.push(w);return w;};
        const cleanText=(s)=>String(s||'').replace(/\\s+/g,' ').trim();
        const takeText=(s)=>{s=cleanText(s);if(textLength+s.length>L.text)throw 'TEXT_LIMIT';textLength+=s.length;return s;};
        const nodeText=(n)=>{if(++visited>L.blocks*4)throw 'BLOCK_LIMIT';return takeText(n.nodeValue)};
        const elementText=(e)=>{const parts=[],w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let n;while((n=w.nextNode())){const value=nodeText(n);if(value)parts.push(value)}return parts.join(' ')};
        const hash=(s)=>{let h=2166136261;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)}return (h>>>0).toString(36)};
        const path=(e)=>{const p=[];while(e&&e.nodeType===1&&e!==document.documentElement){let i=1,n=e;while((n=n.previousElementSibling))i++;p.push(e.tagName.toLowerCase()+':'+i);e=e.parentElement}return p.reverse().join('/')};
        const css=(e)=>{const s=getComputedStyle(e),num=(v,d=0)=>{const n=parseFloat(v);return Number.isFinite(n)?n:d};return {display:takeMeta(s.display),position:takeMeta(s.position),overflowX:takeMeta(s.overflowX),overflowY:takeMeta(s.overflowY),color:takeMeta(s.color),backgroundColor:takeMeta(s.backgroundColor),fontFamily:takeMeta(s.fontFamily),fontSize:num(s.fontSize),fontWeight:parseInt(s.fontWeight)||400,fontStyle:takeMeta(s.fontStyle),textAlign:takeMeta(s.textAlign),lineHeight:num(s.lineHeight,num(s.fontSize)*1.2),opacity:num(s.opacity,1),zIndex:parseInt(s.zIndex)||0,flex:s.display.includes('flex'),grid:s.display.includes('grid')}};
        const classify=(e)=>{const t=e.tagName.toUpperCase();if(e.hasAttribute('data-pptx-chart')||e.hasAttribute('_echarts_instance_')||/(^|\\s)(chart|echarts)(\\s|$)/i.test(e.className||''))return 'CHART';if(t==='IMG'||t==='PICTURE')return 'IMAGE';if(t==='LI')return 'LIST';if(t==='TABLE')return 'TABLE';if(t==='SVG')return 'SVG';if(t==='CANVAS')return e.closest('[data-pptx-chart],[_echarts_instance_],.chart,.echarts')?'CHART':'CANVAS';if(['IFRAME','VIDEO','OBJECT','EMBED'].includes(t))return 'FALLBACK';let directText=false;for(const n of e.childNodes)if(n.nodeType===3&&cleanText(n.nodeValue)){directText=true;break}if(/^H[1-6]$/.test(t)||['P','SPAN','A','LABEL','BLOCKQUOTE','PRE','CODE'].includes(t)||directText)return 'TEXT';return 'CONTAINER'};
        const asset=(e,type,id)=>{let raw='';if(type==='IMAGE')raw=e.currentSrc||e.src||'';else if(type==='SVG')raw='urn:ocapp:inline-svg:'+id;else if(type==='CANVAS'||type==='CHART')raw='urn:ocapp:canvas:'+id;else return [];raw=raw||'urn:ocapp:missing:'+id;const embedded=raw.startsWith('data:'),u=embedded?raw:takeMeta(raw);const comma=embedded?u.indexOf(','):-1,bytes=embedded&&comma>=0?Math.ceil(Math.max(0,u.length-comma-1)*.75):0;if(embedded&&comma<0)throw 'ASSET_BYTES_LIMIT';if(assets.length>=L.assets)throw 'ASSET_LIMIT';if(assetBytes+bytes>L.assetBytes)throw 'ASSET_BYTES_LIMIT';assetBytes+=bytes;const media=embedded?(u.slice(5,u.indexOf(';')>0?u.indexOf(';'):comma)||'application/octet-stream'):'';const a={id:takeMeta('asset-'+id),kind:type==='IMAGE'?'IMAGE':type,uri:u,mediaType:takeMeta(media),bytes,embedded:embedded||type!=='IMAGE'};assets.push(a);return[a]};
        const transform=(s)=>{if(!s.transform||s.transform==='none')return{transformed:false,matrix:takeMeta('none'),rotation:0,scaleX:1,scaleY:1};let rotation=0,sx=1,sy=1;try{const m=new DOMMatrixReadOnly(s.transform);rotation=Math.atan2(m.b,m.a)*180/Math.PI;sx=Math.hypot(m.a,m.b);sy=Math.hypot(m.c,m.d)}catch(_){}return{transformed:true,matrix:takeMeta(s.transform),rotation,scaleX:sx,scaleY:sy}};
        const clipping=(e,b)=>{let x=b.x,y=b.y,r=b.x+b.width,bt=b.y+b.height,p=e.parentElement,clipped=false;while(p){const s=getComputedStyle(p);if(/hidden|clip|scroll|auto/.test(s.overflow+s.overflowX+s.overflowY)){const q=rect(p.getBoundingClientRect());x=Math.max(x,q.x);y=Math.max(y,q.y);r=Math.min(r,q.x+q.width);bt=Math.min(bt,q.y+q.height);clipped=true}p=p.parentElement}return clipped&&r>x&&bt>y?{x,y,width:r-x,height:bt-y}:null};
        const hints=(e,id)=>{let render=takeMeta(e.dataset.pptxRender||'AUTO').toUpperCase();if(!['AUTO','IMAGE','NATIVE'].includes(render)){warning('INVALID_HINT',id,'render');render='AUTO'}return{keepTogether:e.hasAttribute('data-pptx-keep-together'),slide:takeMeta(e.dataset.pptxSlide||''),title:takeMeta(e.dataset.pptxTitle||''),notes:takeMeta(e.dataset.pptxNotes||''),layout:takeMeta(e.dataset.pptxLayout||''),render}};
        const border=(s,side)=>{const width=parseFloat(s['border'+side+'Width'])||0;return{width,color:takeMeta(s['border'+side+'Color']||'transparent'),style:takeMeta(s['border'+side+'Style']||'none')}};
        const table=(e)=>{if(e.tagName!=='TABLE')return null;const out=[],occupied=[],rowHeights=[...e.rows].map(r=>r.getBoundingClientRect().height);let max=0,headerRows=0;for(let ri=0;ri<e.rows.length;ri++){occupied[ri]??=[];let ci=0,rowHeader=true;for(const c of e.rows[ri].cells){while(occupied[ri][ci])ci++;if(++cells>L.cells)throw 'CELL_LIMIT';const rs=Math.max(1,c.rowSpan||1),cs=Math.max(1,c.colSpan||1),s=getComputedStyle(c);rowHeader=rowHeader&&c.tagName==='TH';for(let r=ri;r<ri+rs;r++){occupied[r]??=[];for(let col=ci;col<ci+cs;col++)occupied[r][col]=true}max=Math.max(max,ci+cs);out.push({row:ri,column:ci,rowSpan:rs,columnSpan:cs,header:c.tagName==='TH',text:elementText(c),fill:takeMeta(s.backgroundColor),color:takeMeta(s.color),fontFamily:takeMeta(s.fontFamily),fontSize:parseFloat(s.fontSize)||12,fontWeight:parseInt(s.fontWeight)||400,textAlign:takeMeta(s.textAlign),verticalAlign:takeMeta(s.verticalAlign),top:border(s,'Top'),right:border(s,'Right'),bottom:border(s,'Bottom'),left:border(s,'Left')});ci+=cs}if(rowHeader&&ri===headerRows)headerRows++}const first=e.rows[0],columnWidths=[];if(first)for(let i=0;i<max;i++){let found=null;for(const c of first.cells){const r=c.getBoundingClientRect();if((c.cellIndex||0)<=i){found=r.width/Math.max(1,c.colSpan||1)}}columnWidths.push(found||e.getBoundingClientRect().width/max)}return{rows:e.rows.length,columns:max,columnWidths,rowHeights,headerRows,cells:out}};
        const list=(e)=>{if(e.tagName!=='LI')return null;const owner=e.parentElement;if(!owner||!['UL','OL'].includes(owner.tagName))return null;let level=0,p=owner.parentElement;while(p){if(p.tagName==='UL'||p.tagName==='OL')level++;p=p.parentElement}const siblings=[...owner.children].filter(x=>x.tagName==='LI'),index=Math.max(0,siblings.indexOf(e));return{ordered:owner.tagName==='OL',start:(parseInt(owner.start)||1)+index,level,marker:takeMeta(getComputedStyle(e).listStyleType||'')}};
        const walk=(e,depth,parent)=>{if(++visited>L.blocks*4)throw 'BLOCK_LIMIT';if(depth>L.depth)throw 'DEPTH_LIMIT';if(ignored.has(e.tagName)||e.hasAttribute('data-pptx-ignore'))return;const s=getComputedStyle(e);if(s.display==='none'||parseFloat(s.opacity)===0||e.hidden)return;if(s.visibility==='hidden'){for(const child of e.children)walk(child,depth,parent);return}const r=rect(e.getBoundingClientRect());if(!validRect(r)){if(r.width!==0||r.height!==0)throw 'GEOMETRY_INVALID';for(const child of e.children)walk(child,depth+1,parent);return}if(blocks.length>=L.blocks)throw 'BLOCK_LIMIT';const id=takeMeta('b-'+hash(path(e))),type=classify(e),st=css(e),direct=[];for(const n of e.childNodes)if(n.nodeType===3){const value=nodeText(n);if(value){accountMeta(st);direct.push({text:value,style:st})}}const links=[];if(e.tagName==='A'&&e.href)links.push({text:elementText(e),target:takeMeta(e.href)});const b={id,type,parentId:takeMeta(parent?parent.id:''),children:[],depth,domOrder:domOrder++,visualOrder:0,bounds:r,clip:clipping(e,r),transform:transform(s),overlaps:[],style:st,textRuns:direct,links,list:list(e,depth),table:table(e),assets:asset(e,type,id),hints:hints(e,id),warnings:[]};blocks.push(b);byElement.set(e,b);if(parent)parent.children.push(takeMeta(id));for(const child of e.children)walk(child,depth+1,b)};
        try{
          const de=document.documentElement,body=document.body;if(!de||!body)return fail('GEOMETRY_INVALID');
          walk(body,0,null);
          const visual=[...blocks].sort((a,b)=>a.style.zIndex-b.style.zIndex||a.bounds.y-b.bounds.y||a.bounds.x-b.bounds.x||a.domOrder-b.domOrder);visual.forEach((b,i)=>b.visualOrder=i);
          const blockById=new Map(blocks.map(b=>[b.id,b]));const ancestor=(a,b)=>{let parent=b.parentId;for(let depth=0;parent&&depth<=L.depth;depth++){if(parent===a.id)return true;parent=(blockById.get(parent)||{}).parentId||''}return false};
          let checks=0;for(let i=0;i<visual.length;i++)for(let j=i+1;j<visual.length&&checks<L.overlapChecks;j++,checks++){const a=visual[i],b=visual[j];if(ancestor(a,b)||ancestor(b,a))continue;if(a.bounds.x<b.bounds.x+b.bounds.width&&a.bounds.x+a.bounds.width>b.bounds.x&&a.bounds.y<b.bounds.y+b.bounds.height&&a.bounds.y+a.bounds.height>b.bounds.y){if(a.overlaps.length<16)a.overlaps.push(takeMeta(b.id));if(b.overlaps.length<16)b.overlaps.push(takeMeta(a.id))}}
          const width=Math.max(de.scrollWidth,body.scrollWidth,de.clientWidth),height=Math.max(de.scrollHeight,body.scrollHeight,de.clientHeight);const viewport={x:scrollX,y:scrollY,width:innerWidth,height:innerHeight};if(!validRect({x:0,y:0,width,height})||!validRect(viewport))return fail('GEOMETRY_INVALID');
          return{document:{title:takeMeta(document.title||''),language:takeMeta(de.lang||'')},capture:{userAgent:takeMeta(navigator.userAgent||''),locale:takeMeta(navigator.language||''),timezone:takeMeta(Intl.DateTimeFormat().resolvedOptions().timeZone||''),dpr:devicePixelRatio,readiness:takeMeta('complete'),observations:[takeMeta('dom-complete'),takeMeta('fonts-ready')]},geometry:{width,height,viewport,scrollX,scrollY},blocks,roots:blocks.filter(b=>!b.parentId).map(b=>takeMeta(b.id)),assets,warnings};
        }catch(e){return fail(typeof e==='string'&&internalErrors.has(e)?e:'EXTRACTION_FAILED');}
        """;
}
