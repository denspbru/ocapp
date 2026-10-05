package com.nicodim.ocapp.pagemodel;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Enforces all model budgets and exact forest/semantic invariants before analysis/pagination. */
public final class PageModelValidator {
    private PageModelValidator() { }

    public static PageModel validate(PageModel model, ConverterProperties.PageModelLimits limits) {
        if (model == null || model.document() == null || model.capture() == null || model.geometry() == null)
            throw invalid("Page model metadata is missing");
        if (!PageModel.SCHEMA_VERSION.equals(model.schemaVersion())) throw invalid("Page model schema is invalid");
        long metadata = validateMetadata(model, limits);
        geometry(model.geometry(), limits);
        if (model.blocks().size() > limits.getMaxBlocks()) throw limit("Page model block limit exceeded");
        if (model.assets().size() > limits.getMaxAssets()) throw limit("Page model asset limit exceeded");
        if (model.warnings().size() > limits.getMaxWarnings()) throw limit("Page model warning limit exceeded");

        long text=0, assetBytes=0, cells=0, warningCount=model.warnings().size();
        Map<String,PageBlock> byId=new HashMap<>(); Set<String> assetIds=new HashSet<>();
        Set<Integer> domOrders=new HashSet<>(), visualOrders=new HashSet<>();
        for (PageBlock block:model.blocks()) {
            if (block==null || blank(block.id()) || byId.putIfAbsent(block.id(),block)!=null) throw invalid("Page model block ID is invalid");
            field(block.id(),limits); field(block.parentId(),limits);
            if (block.type()==null || block.style()==null || block.transform()==null || block.hints()==null) throw invalid("Page model block fields are missing");
            if (block.depth()<0 || block.depth()>limits.getMaxDepth()) throw limit("Page model depth limit exceeded");
            int count=model.blocks().size();
            if (block.domOrder()<0 || block.domOrder()>=count || !domOrders.add(block.domOrder())) throw invalid("Page model DOM order is invalid");
            if (block.visualOrder()<0 || block.visualOrder()>=count || !visualOrders.add(block.visualOrder())) throw invalid("Page model visual order is invalid");
            bounds(block.bounds(),limits); if(block.clipBounds()!=null)bounds(block.clipBounds(),limits);
            metadata=add(metadata,validateStyle(block.style(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");
            metadata=add(metadata,validateTransform(block.transform(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");
            metadata=add(metadata,validateHints(block.hints(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");
            uniqueFields(block.childIds(),limits,"child"); uniqueFields(block.overlapIds(),limits,"overlap");
            for(TextRun run:block.textRuns()){if(run==null||run.style()==null)throw invalid("Page model text run is invalid");text=add(text,required(run.text(),"text run").length(),limits.getMaxTextLength(),"Page model text limit exceeded");metadata=add(metadata,validateStyle(run.style(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");}
            for(LinkReference link:block.links()){if(link==null||link.target()==null)throw invalid("Page model link is invalid");validUri(link.target());text=add(text,required(link.text(),"link text").length(),limits.getMaxTextLength(),"Page model text limit exceeded");metadata=add(metadata,uriLength(link.target(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");}
            validateList(block.list(),limits);
            if(block.table()!=null){metadata=add(metadata,validateTable(block.table(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");cells=add(cells,block.table().cells().size(),limits.getMaxTableCells(),"Page model table-cell limit exceeded");for(TableSemantics.Cell cell:block.table().cells())text=add(text,required(cell.text(),"table cell text").length(),limits.getMaxTextLength(),"Page model text limit exceeded");}
            warningCount=add(warningCount,block.warnings().size(),limits.getMaxWarnings(),"Page model warning limit exceeded");
            metadata=add(metadata,warningMetadata(block.warnings(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");
        }
        if(domOrders.size()!=model.blocks().size()||visualOrders.size()!=model.blocks().size())throw invalid("Page model orders are incomplete");
        for(AssetReference asset:model.assets()){assetBytes=asset(asset,assetBytes,limits);if(!assetIds.add(asset.id()))throw invalid("Page model asset ID is invalid");metadata=add(metadata,assetMetadata(asset,limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");}
        metadata=add(metadata,warningMetadata(model.warnings(),limits),limits.getMaxMetadataCharacters(),"Page model metadata limit exceeded");
        validateForest(model,byId,limits);
        for(PageBlock block:model.blocks()){
            for(String overlap:block.overlapIds())if(block.id().equals(overlap)||!byId.containsKey(overlap))throw invalid("Page model overlap reference is invalid");
            for(AssetReference asset:block.assets())if(asset==null||!assetIds.contains(asset.id()))throw invalid("Page model asset reference is invalid");
        }
        return model;
    }

    private static long validateMetadata(PageModel m,ConverterProperties.PageModelLimits l){
        if(m.document().source()==null)throw invalid("Page model source is missing");validUri(m.document().source());
        long n=uriLength(m.document().source(),l);n=plusField(n,m.document().title(),l);n=plusField(n,m.document().language(),l);
        CaptureMetadata c=m.capture();finiteRange(c.deviceScaleFactor(),0.01,100,"Page model device scale factor is invalid");
        n=plusField(n,c.userAgent(),l);n=plusField(n,c.locale(),l);n=plusField(n,c.timezone(),l);if(blank(c.readiness()))throw invalid("Page model readiness is missing");n=plusField(n,c.readiness(),l);
        for(String s:c.observations())n=plusField(n,s,l);return n;
    }
    private static void validateForest(PageModel model,Map<String,PageBlock> byId,ConverterProperties.PageModelLimits limits){
        Set<String> expectedRoots=new LinkedHashSet<>();for(PageBlock b:model.blocks())if(b.parentId().isEmpty())expectedRoots.add(b.id());
        Set<String> roots=new LinkedHashSet<>(model.rootIds());if(roots.size()!=model.rootIds().size()||!roots.equals(expectedRoots))throw invalid("Page model roots are invalid");
        Map<String,Integer> owners=new HashMap<>();
        for(PageBlock p:model.blocks())for(String id:p.childIds()){PageBlock c=byId.get(id);if(c==null||!p.id().equals(c.parentId())||owners.merge(id,1,Integer::sum)!=1)throw invalid("Page model child ownership is invalid");}
        for(PageBlock b:model.blocks())if(!b.parentId().isEmpty()){PageBlock p=byId.get(b.parentId());if(p==null||owners.getOrDefault(b.id(),0)!=1||b.depth()!=p.depth()+1)throw invalid("Page model parent/depth relation is invalid");}else if(b.depth()!=0)throw invalid("Page model root depth is invalid");
        Set<String> visiting=new HashSet<>(),visited=new HashSet<>();for(String root:model.rootIds())dfs(root,byId,visiting,visited,limits);if(visited.size()!=model.blocks().size())throw invalid("Page model graph has a cycle or unreachable block");
    }
    private static void dfs(String id,Map<String,PageBlock> byId,Set<String> visiting,Set<String> visited,ConverterProperties.PageModelLimits l){if(!visiting.add(id))throw invalid("Page model graph has a cycle");if(visited.contains(id)){visiting.remove(id);return;}PageBlock b=byId.get(id);if(b==null)throw invalid("Page model graph is invalid");if(b.depth()>l.getMaxDepth())throw limit("Page model depth limit exceeded");for(String child:b.childIds())dfs(child,byId,visiting,visited,l);visiting.remove(id);visited.add(id);}
    private static long validateStyle(ComputedStyle s,ConverterProperties.PageModelLimits l){if(blank(s.display())||blank(s.position())||s.fontWeight()<1||s.fontWeight()>1000||Math.abs((long)s.zIndex())>l.getMaxCoordinate())throw invalid("Page model style is invalid");finiteRange(s.fontSize(),0,10000,"Page model font size is invalid");finiteRange(s.lineHeight(),0,100000,"Page model line height is invalid");finiteRange(s.opacity(),0,1,"Page model opacity is invalid");long n=0;for(String v:List.of(s.display(),s.position(),s.overflowX(),s.overflowY(),s.color(),s.backgroundColor(),s.fontFamily(),s.fontStyle(),s.textAlign()))n=plusField(n,v,l);return n;}
    private static long validateTransform(TransformSummary t,ConverterProperties.PageModelLimits l){if(blank(t.matrix()))throw invalid("Page model transform is invalid");field(t.matrix(),l);finiteRange(t.rotationDegrees(),-360000,360000,"Page model transform rotation is invalid");finiteRange(t.scaleX(),-10000,10000,"Page model transform scale is invalid");finiteRange(t.scaleY(),-10000,10000,"Page model transform scale is invalid");return t.matrix().length();}
    private static long validateHints(AuthoringHints h,ConverterProperties.PageModelLimits l){
        if(h.render()==null || (!h.slide().isEmpty() && !AuthoringHints.BREAK_BEFORE.equals(h.slide()))
            || !AuthoringHints.LAYOUTS.contains(h.layout())) throw invalid("Page model authoring hint is invalid");
        long n=0;for(String v:List.of(h.slide(),h.title(),h.notes(),h.layout()))n=plusField(n,v,l);return n;
    }
    private static void validateList(ListSemantics x,ConverterProperties.PageModelLimits l){if(x==null)return;if(x.start()<Integer.MIN_VALUE+1||x.level()<0||x.level()>l.getMaxDepth())throw invalid("Page model list semantics are invalid");field(x.marker(),l);}
    private static long validateTable(TableSemantics t,ConverterProperties.PageModelLimits l){
        if(t.rows()<0||t.columns()<0||t.headerRows()<0||t.headerRows()>t.rows())throw invalid("Page model table dimensions are invalid");
        if(!t.columnWidths().isEmpty()&&t.columnWidths().size()!=t.columns())throw invalid("Page model table column widths are invalid");
        if(!t.rowHeights().isEmpty()&&t.rowHeights().size()!=t.rows())throw invalid("Page model table row heights are invalid");
        for(double width:t.columnWidths())finiteRange(width,0.01,l.getMaxCoordinate(),"Page model table column width is invalid");
        for(double height:t.rowHeights())finiteRange(height,0.01,l.getMaxCoordinate(),"Page model table row height is invalid");
        long metadata=0;Set<String> occupied=new HashSet<>();
        for(TableSemantics.Cell c:t.cells()){
            if(c==null||c.row()<0||c.column()<0||c.rowSpan()<1||c.columnSpan()<1||c.row()+c.rowSpan()>t.rows()||c.column()+c.columnSpan()>t.columns()||!Double.isFinite(c.fontSize())||c.fontSize()<=0||c.fontWeight()<1||c.fontWeight()>1000)throw invalid("Page model table cell is invalid");
            for(String value:List.of(c.fillColor(),c.textColor(),c.fontFamily(),c.textAlign(),c.verticalAlign()))metadata=plusField(metadata,value,l);
            for(TableSemantics.Border border:List.of(c.top(),c.right(),c.bottom(),c.left())){if(border==null||!Double.isFinite(border.widthPixels())||border.widthPixels()<0)throw invalid("Page model table border is invalid");metadata=plusField(metadata,border.color(),l);metadata=plusField(metadata,border.style(),l);}
            for(int r=c.row();r<c.row()+c.rowSpan();r++)for(int col=c.column();col<c.column()+c.columnSpan();col++)if(!occupied.add(r+":"+col))throw invalid("Page model table cells overlap");
        }
        return metadata;
    }
    private static long asset(AssetReference a,long total,ConverterProperties.PageModelLimits l){if(a==null||blank(a.id())||a.kind()==null||a.uri()==null||a.estimatedBytes()<0)throw invalid("Page model asset is invalid");validUri(a.uri());long bytes=a.estimatedBytes();if("data".equalsIgnoreCase(a.uri().getScheme())){bytes=dataUriUpperBound(a.uri());if(bytes>l.getMaxAssetBytes())throw limit("Page model asset-byte limit exceeded");}return add(total,bytes,l.getMaxAssetBytes(),"Page model asset-byte limit exceeded");}
    private static long assetMetadata(AssetReference a,ConverterProperties.PageModelLimits l){long n=0;n=plusField(n,a.id(),l);n=plusField(n,a.mediaType(),l);if(!"data".equalsIgnoreCase(a.uri().getScheme()))n=add(n,uriLength(a.uri(),l),l.getMaxMetadataCharacters(),"Page model metadata limit exceeded");return n;}
    private static long dataUriUpperBound(URI uri){String value=uri.toString();int comma=value.indexOf(',');if(comma<5)throw invalid("Page model data asset URI is invalid");long encoded=value.length()-(long)comma-1;try{return Math.multiplyExact((encoded+3)/4,3);}catch(ArithmeticException ex){throw limit("Page model asset-byte limit exceeded");}}
    private static long warningMetadata(List<ModelWarning> ws,ConverterProperties.PageModelLimits l){long n=0;for(ModelWarning w:ws){if(w==null||blank(w.code()))throw invalid("Page model warning is invalid");n=plusField(n,w.code(),l);n=plusField(n,w.blockId(),l);n=plusField(n,w.detail(),l);}return n;}
    private static void uniqueFields(List<String> values,ConverterProperties.PageModelLimits l,String kind){Set<String>s=new HashSet<>();for(String v:values){if(blank(v)||!s.add(v))throw invalid("Page model "+kind+" IDs are invalid");field(v,l);}}
    private static void geometry(PageGeometry v,ConverterProperties.PageModelLimits l){finitePositive(v.width(),l);finitePositive(v.height(),l);bounds(v.viewport(),l);finite(v.scrollX(),l);finite(v.scrollY(),l);}
    private static void bounds(Bounds v,ConverterProperties.PageModelLimits l){if(v==null)throw invalid("Page model bounds are missing");finite(v.x(),l);finite(v.y(),l);finitePositive(v.width(),l);finitePositive(v.height(),l);finite(v.right(),l);finite(v.bottom(),l);}
    private static void finitePositive(double v,ConverterProperties.PageModelLimits l){if(v<=0)throw invalid("Page model geometry is invalid");finite(v,l);} private static void finite(double v,ConverterProperties.PageModelLimits l){if(!Double.isFinite(v)||Math.abs(v)>l.getMaxCoordinate())throw invalid("Page model geometry is invalid or unbounded");}
    private static void finiteRange(double v,double min,double max,String message){if(!Double.isFinite(v)||v<min||v>max)throw invalid(message);}
    private static long uriLength(URI uri,ConverterProperties.PageModelLimits l){String s=uri.toASCIIString();field(s,l);return s.length();} private static void validUri(URI uri){if(uri.toString().isBlank()||uri.getScheme()==null)throw invalid("Page model URI is invalid");}
    private static String required(String s,String name){if(s==null)throw invalid("Page model "+name+" is missing");return s;} private static boolean blank(String s){return s==null||s.isBlank();}
    private static void field(String s,ConverterProperties.PageModelLimits l){if(s==null||s.length()>l.getMaxFieldLength())throw limit("Page model field limit exceeded");}
    private static long plusField(long n,String s,ConverterProperties.PageModelLimits l){field(s,l);return add(n,s.length(),l.getMaxMetadataCharacters(),"Page model metadata limit exceeded");}
    private static long add(long current,long amount,long max,String message){if(amount<0||current>max-amount)throw limit(message);return current+amount;}
    private static ConversionException invalid(String m){return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY,"PAGEMODEL_INVALID",m);} private static ConversionException limit(String m){return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE,"PAGEMODEL_LIMIT_EXCEEDED",m);}
}
