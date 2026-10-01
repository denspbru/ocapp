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
        return result;
    }

    static PageModel fromScriptResult(Object raw, URI source, ConverterProperties.PageModelLimits limits) {
        Map<?,?> root = map(raw, "result");
        String error = string(root.get("error"));
        if (!error.isEmpty()) {
            String code = error.startsWith("GEOMETRY") ? "PAGEMODEL_INVALID" : "PAGEMODEL_LIMIT_EXCEEDED";
            HttpStatus status = code.equals("PAGEMODEL_INVALID") ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.PAYLOAD_TOO_LARGE;
            throw new ConversionException(status, code, "DOM extraction exceeded a PageModel boundary: " + error);
        }
        Map<?,?> doc = map(root.get("document"), "document");
        Map<?,?> capture = map(root.get("capture"), "capture");
        Map<?,?> geometry = map(root.get("geometry"), "geometry");
        List<?> rawBlocks = list(root.get("blocks"), "blocks");
        if (rawBlocks.size() > limits.getMaxBlocks()) throw malformedLimit("block");
        preflightRawBlocks(rawBlocks, root.get("warnings"), limits);
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

    private static void preflightRawBlocks(List<?> blocks, Object rootWarnings, ConverterProperties.PageModelLimits limits) {
        long text=0,cells=0,assets=0,warnings=list(rootWarnings,"warnings").size();
        for(Object value:blocks){Map<?,?> b=map(value,"block");List<?> runs=list(b.get("textRuns"),"text runs"),links=list(b.get("links"),"links");assets=boundedAdd(assets,list(b.get("assets"),"block assets").size(),limits.getMaxAssets(),"asset");warnings=boundedAdd(warnings,list(b.get("warnings"),"warnings").size(),limits.getMaxWarnings(),"warning");for(Object run:runs)text=boundedAdd(text,string(map(run,"text run").get("text")).length(),limits.getMaxTextLength(),"text");for(Object link:links)text=boundedAdd(text,string(map(link,"link").get("text")).length(),limits.getMaxTextLength(),"text");Map<?,?> table=optionalMap(b.get("table"));if(table!=null){List<?> rawCells=list(table.get("cells"),"table cells");cells=boundedAdd(cells,rawCells.size(),limits.getMaxTableCells(),"table-cell");for(Object cell:rawCells)text=boundedAdd(text,string(map(cell,"cell").get("text")).length(),limits.getMaxTextLength(),"text");}}
    }
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
        AuthoringHints hints = h == null ? AuthoringHints.none() : new AuthoringHints(bool(h.get("keepTogether")),string(h.get("slide")),string(h.get("title")),string(h.get("notes")),string(h.get("layout")),enumValue(AuthoringHints.Render.class,string(h.get("render")),AuthoringHints.Render.AUTO));
        return new PageBlock(string(m.get("id")), enumValue(BlockType.class,string(m.get("type")),null), string(m.get("parentId")), strings(m.get("children")), integer(m.get("depth")), integer(m.get("domOrder")), integer(m.get("visualOrder")), bounds(map(m.get("bounds"),"bounds")), nullableBounds(m.get("clip")), transform, strings(m.get("overlaps")), style, runs, links, listSemantics(m.get("list")), table(m.get("table"), limits), assets(rawAssets, limits), hints, warnings(m.get("warnings"), limits));
    }

    private static ComputedStyle style(Map<?,?> s) { return new ComputedStyle(string(s.get("display")),string(s.get("position")),string(s.get("overflowX")),string(s.get("overflowY")),string(s.get("color")),string(s.get("backgroundColor")),string(s.get("fontFamily")),number(s.get("fontSize")),integer(s.get("fontWeight")),string(s.get("fontStyle")),string(s.get("textAlign")),number(s.get("lineHeight")),number(s.get("opacity")),integer(s.get("zIndex")),bool(s.get("flex")),bool(s.get("grid"))); }
    private static ListSemantics listSemantics(Object raw) { Map<?,?> m=optionalMap(raw); return m == null ? null : new ListSemantics(bool(m.get("ordered")),integer(m.get("start")),integer(m.get("level")),string(m.get("marker"))); }
    private static TableSemantics table(Object raw, ConverterProperties.PageModelLimits limits) {
        Map<?,?> m=optionalMap(raw); if (m == null) return null; List<?> values=list(m.get("cells"),"table cells"); if(values.size()>limits.getMaxTableCells()) throw malformedLimit("table-cell");
        List<TableSemantics.Cell> cells=new ArrayList<>(values.size()); for(Object value:values){Map<?,?> c=map(value,"cell");cells.add(new TableSemantics.Cell(integer(c.get("row")),integer(c.get("column")),integer(c.get("rowSpan")),integer(c.get("columnSpan")),bool(c.get("header")),string(c.get("text"))));}
        return new TableSemantics(integer(m.get("rows")),integer(m.get("columns")),cells);
    }
    private static List<AssetReference> assets(List<?> values, ConverterProperties.PageModelLimits limits) { if(values.size()>limits.getMaxAssets()) throw malformedLimit("asset"); List<AssetReference> result=new ArrayList<>(values.size()); for(Object value:values){Map<?,?> a=map(value,"asset");result.add(new AssetReference(string(a.get("id")),enumValue(AssetReference.Kind.class,string(a.get("kind")),AssetReference.Kind.OTHER),uri(a.get("uri")),string(a.get("mediaType")),longNumber(a.get("bytes")),bool(a.get("embedded"))));} return result; }
    private static List<ModelWarning> warnings(Object raw, ConverterProperties.PageModelLimits limits) { List<?> values=list(raw,"warnings"); if(values.size()>limits.getMaxWarnings()) throw malformedLimit("warning"); List<ModelWarning> result=new ArrayList<>(values.size()); for(Object value:values){Map<?,?> w=map(value,"warning");result.add(new ModelWarning(string(w.get("code")),string(w.get("blockId")),string(w.get("detail"))));} return result; }
    private static Bounds nullableBounds(Object raw) { Map<?,?> value=optionalMap(raw); return value == null ? null : bounds(value); }
    private static Bounds bounds(Map<?,?> m) { return new Bounds(number(m.get("x")),number(m.get("y")),number(m.get("width")),number(m.get("height"))); }
    private static URI uri(Object value) { try { return URI.create(string(value)); } catch(RuntimeException ex) { throw malformed("URI",ex); } }
    private static Map<?,?> map(Object value,String field) { if(value instanceof Map<?,?> m)return m; throw malformed(field,null); }
    private static Map<?,?> optionalMap(Object value) { if(value == null)return null; return map(value,"object"); }
    private static List<?> list(Object value,String field) { if(value instanceof List<?> l)return l; throw malformed(field,null); }
    private static List<String> strings(Object value) { List<?> values=list(value,"list"); List<String> result=new ArrayList<>(values.size()); for(Object item:values)result.add(string(item)); return result; }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean bool(Object value) { if(value instanceof Boolean b)return b; throw malformed("boolean",null); }
    private static double number(Object value) { if(value instanceof Number n)return n.doubleValue(); throw malformed("number",null); }
    private static long longNumber(Object value) { if(value instanceof Number n)return n.longValue(); throw malformed("integer",null); }
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
        const blocks=[], assets=[], warnings=[], byElement=new Map(); let textLength=0,cells=0,assetBytes=0,warningCount=0,domOrder=0,visited=0;
        const warning=(code,id,detail)=>{if(++warningCount>L.warnings)throw 'WARNING_LIMIT'; const w={code,blockId:id||'',detail:detail||''};warnings.push(w);return w;};
        const cleanText=(s)=>String(s||'').replace(/\\s+/g,' ').trim();
        const takeText=(s)=>{s=cleanText(s);if(textLength+s.length>L.text)throw 'TEXT_LIMIT';textLength+=s.length;return s;};
        const nodeText=(n)=>{if(++visited>L.blocks*4)throw 'BLOCK_LIMIT';return takeText(n.nodeValue)};
        const elementText=(e)=>{const parts=[],w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let n;while((n=w.nextNode())){const value=nodeText(n);if(value)parts.push(value)}return parts.join(' ')};
        const hash=(s)=>{let h=2166136261;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)}return (h>>>0).toString(36)};
        const path=(e)=>{const p=[];while(e&&e.nodeType===1&&e!==document.documentElement){let i=1,n=e;while((n=n.previousElementSibling))i++;p.push(e.tagName.toLowerCase()+':'+i);e=e.parentElement}return p.reverse().join('/')};
        const css=(e)=>{const s=getComputedStyle(e),num=(v,d=0)=>{const n=parseFloat(v);return Number.isFinite(n)?n:d};return {display:s.display,position:s.position,overflowX:s.overflowX,overflowY:s.overflowY,color:s.color,backgroundColor:s.backgroundColor,fontFamily:s.fontFamily,fontSize:num(s.fontSize),fontWeight:parseInt(s.fontWeight)||400,fontStyle:s.fontStyle,textAlign:s.textAlign,lineHeight:num(s.lineHeight,num(s.fontSize)*1.2),opacity:num(s.opacity,1),zIndex:parseInt(s.zIndex)||0,flex:s.display.includes('flex'),grid:s.display.includes('grid')}};
        const classify=(e)=>{const t=e.tagName.toUpperCase();if(e.hasAttribute('data-pptx-chart')||e.hasAttribute('_echarts_instance_')||/(^|\\s)(chart|echarts)(\\s|$)/i.test(e.className||''))return 'CHART';if(t==='IMG'||t==='PICTURE')return 'IMAGE';if(t==='UL'||t==='OL')return 'LIST';if(t==='TABLE')return 'TABLE';if(t==='SVG')return 'SVG';if(t==='CANVAS')return e.closest('[data-pptx-chart],[_echarts_instance_],.chart,.echarts')?'CHART':'CANVAS';if(['IFRAME','VIDEO','OBJECT','EMBED'].includes(t))return 'FALLBACK';let directText=false;for(const n of e.childNodes)if(n.nodeType===3&&cleanText(n.nodeValue)){directText=true;break}if(/^H[1-6]$/.test(t)||['P','SPAN','A','LABEL','BLOCKQUOTE','PRE','CODE'].includes(t)||directText)return 'TEXT';return 'CONTAINER'};
        const asset=(e,type,id)=>{let u='';if(type==='IMAGE')u=e.currentSrc||e.src||'';else if(type==='SVG')u='urn:ocapp:inline-svg:'+id;else if(type==='CANVAS'||type==='CHART')u='urn:ocapp:canvas:'+id;else return []; const bytes=u.startsWith('data:')?Math.ceil(Math.max(0,u.length-u.indexOf(',')-1)*.75):0;if(assets.length>=L.assets)throw 'ASSET_LIMIT';if(assetBytes+bytes>L.assetBytes)throw 'ASSET_BYTES_LIMIT';assetBytes+=bytes;const a={id:'asset-'+id,kind:type==='IMAGE'?'IMAGE':type,uri:u||'urn:ocapp:missing:'+id,mediaType:e.currentSrc&&e.currentSrc.startsWith('data:')?(e.currentSrc.slice(5,e.currentSrc.indexOf(';'))||'application/octet-stream'):'',bytes,embedded:u.startsWith('data:')||type!=='IMAGE'};assets.push(a);return[a]};
        const transform=(s)=>{if(!s.transform||s.transform==='none')return{transformed:false,matrix:'none',rotation:0,scaleX:1,scaleY:1};let rotation=0,sx=1,sy=1;try{const m=new DOMMatrixReadOnly(s.transform);rotation=Math.atan2(m.b,m.a)*180/Math.PI;sx=Math.hypot(m.a,m.b);sy=Math.hypot(m.c,m.d)}catch(_){}return{transformed:true,matrix:s.transform.slice(0,128),rotation,scaleX:sx,scaleY:sy}};
        const clipping=(e,b)=>{let x=b.x,y=b.y,r=b.x+b.width,bt=b.y+b.height,p=e.parentElement,clipped=false;while(p){const s=getComputedStyle(p);if(/hidden|clip|scroll|auto/.test(s.overflow+s.overflowX+s.overflowY)){const q=rect(p.getBoundingClientRect());x=Math.max(x,q.x);y=Math.max(y,q.y);r=Math.min(r,q.x+q.width);bt=Math.min(bt,q.y+q.height);clipped=true}p=p.parentElement}return clipped&&r>x&&bt>y?{x,y,width:r-x,height:bt-y}:null};
        const hints=(e,id)=>{let render=(e.dataset.pptxRender||'AUTO').toUpperCase();if(!['AUTO','IMAGE','NATIVE'].includes(render)){warning('INVALID_HINT',id,'render');render='AUTO'}return{keepTogether:e.hasAttribute('data-pptx-keep-together'),slide:e.dataset.pptxSlide||'',title:e.dataset.pptxTitle||'',notes:e.dataset.pptxNotes||'',layout:e.dataset.pptxLayout||'',render}};
        const table=(e)=>{if(e.tagName!=='TABLE')return null;const out=[];let max=0;for(let ri=0;ri<e.rows.length;ri++){const row=e.rows[ri];for(let ci=0;ci<row.cells.length;ci++){const c=row.cells[ci];if(++cells>L.cells)throw 'CELL_LIMIT';max=Math.max(max,ci+(c.colSpan||1));out.push({row:ri,column:ci,rowSpan:c.rowSpan||1,columnSpan:c.colSpan||1,header:c.tagName==='TH',text:elementText(c)})}}return{rows:e.rows.length,columns:max,cells:out}};
        const list=(e,depth)=>e.tagName==='UL'||e.tagName==='OL'?{ordered:e.tagName==='OL',start:parseInt(e.start)||1,level:depth,marker:getComputedStyle(e).listStyleType||''}:null;
        const walk=(e,depth,parent)=>{if(++visited>L.blocks*4)throw 'BLOCK_LIMIT';if(depth>L.depth)throw 'DEPTH_LIMIT';if(ignored.has(e.tagName)||e.hasAttribute('data-pptx-ignore'))return;const s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)===0||e.hidden)return;const r=rect(e.getBoundingClientRect());if(!validRect(r)){if(r.width!==0||r.height!==0)throw 'GEOMETRY_INVALID';for(const child of e.children)walk(child,depth+1,parent);return}if(blocks.length>=L.blocks)throw 'BLOCK_LIMIT';const id='b-'+hash(path(e)),type=classify(e),st=css(e),direct=[];for(const n of e.childNodes)if(n.nodeType===3){const value=nodeText(n);if(value)direct.push({text:value,style:st})}const links=[];if(e.tagName==='A'&&e.href)links.push({text:elementText(e),target:e.href});const b={id,type,parentId:parent?parent.id:'',children:[],depth,domOrder:domOrder++,visualOrder:0,bounds:r,clip:clipping(e,r),transform:transform(s),overlaps:[],style:st,textRuns:direct,links,list:list(e,depth),table:table(e),assets:asset(e,type,id),hints:hints(e,id),warnings:[]};blocks.push(b);byElement.set(e,b);if(parent)parent.children.push(id);for(const child of e.children)walk(child,depth+1,b)};
        try{
          const de=document.documentElement,body=document.body;if(!de||!body)return fail('GEOMETRY_INVALID');
          for(const child of body.children)walk(child,0,null);
          const visual=[...blocks].sort((a,b)=>a.style.zIndex-b.style.zIndex||a.bounds.y-b.bounds.y||a.bounds.x-b.bounds.x||a.domOrder-b.domOrder);visual.forEach((b,i)=>b.visualOrder=i);
          let checks=0;for(let i=0;i<visual.length;i++)for(let j=i+1;j<visual.length&&checks<L.overlapChecks;j++,checks++){const a=visual[i],b=visual[j];if(a.parentId===b.id||b.parentId===a.id)continue;if(a.bounds.x<b.bounds.x+b.bounds.width&&a.bounds.x+a.bounds.width>b.bounds.x&&a.bounds.y<b.bounds.y+b.bounds.height&&a.bounds.y+a.bounds.height>b.bounds.y){if(a.overlaps.length<16)a.overlaps.push(b.id);if(b.overlaps.length<16)b.overlaps.push(a.id)}}
          const width=Math.max(de.scrollWidth,body.scrollWidth,de.clientWidth),height=Math.max(de.scrollHeight,body.scrollHeight,de.clientHeight);const viewport={x:scrollX,y:scrollY,width:innerWidth,height:innerHeight};if(!validRect({x:0,y:0,width,height})||!validRect(viewport))return fail('GEOMETRY_INVALID');
          return{document:{title:(document.title||'').slice(0,4096),language:(de.lang||'').slice(0,64)},capture:{userAgent:(navigator.userAgent||'').slice(0,512),locale:(navigator.language||'').slice(0,64),timezone:(Intl.DateTimeFormat().resolvedOptions().timeZone||'').slice(0,64),dpr:devicePixelRatio,readiness:'complete',observations:['dom-complete','fonts-ready']},geometry:{width,height,viewport,scrollX,scrollY},blocks,roots:blocks.filter(b=>!b.parentId).map(b=>b.id),assets,warnings};
        }catch(e){return fail(String(e));}
        """;
}
