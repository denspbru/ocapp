package com.nicodim.ocapp.pptx;

import static com.nicodim.ocapp.pptx.EditableRenderPlannerTest.model;
import static com.nicodim.ocapp.pptx.EditableRenderPlannerTest.style;
import static org.assertj.core.api.Assertions.assertThat;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.AuthoringHints;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.ListSemantics;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.TableSemantics;
import com.nicodim.ocapp.pagemodel.TextRun;
import com.nicodim.ocapp.pagemodel.TransformSummary;
import com.nicodim.ocapp.pagination.BreakRationale;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.pagination.SlideViewport;
import com.nicodim.ocapp.pagination.SourceBounds;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

class M4EditableListsTablesTest {
    private final EditableRenderPlanner planner = new EditableRenderPlanner();

    @Test void preservesOrderedAndUnorderedMarkersAndSupportedNesting() throws Exception {
        PageBlock ordered = list("ordered", 0, true, 3, 2, "lower-alpha", "nested ordered");
        PageBlock unordered = list("unordered", 20, false, 1, 1, "square", "nested bullet");
        var items = planner.plan(model(List.of(ordered, unordered)), slice(0, 50), 20);
        assertThat(items).allMatch(RenderItem.NativeListItem.class::isInstance);
        assertThat((RenderItem.NativeListItem) items.getFirst()).satisfies(i -> {
            assertThat(i.ordered()).isTrue(); assertThat(i.start()).isEqualTo(3);
            assertThat(i.level()).isEqualTo(2); assertThat(i.marker()).isEqualTo("lower-alpha");
        });
        byte[] bytes = render(model(List.of(ordered, unordered)), List.of(slice(0, 50)));
        try (XMLSlideShow reopened = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(reopened.getSlides().getFirst().getShapes()).filteredOn(XSLFTextBox.class::isInstance).hasSize(2);
            String xml = reopened.getSlides().getFirst().getXmlObject().toString();
            assertThat(xml).contains("alphaLcPeriod").contains("startAt=\"3\"").contains("char=\"■\"");
        }
    }

    @Test void createsEditableStyledMergedTableAndPaginatesWholeRowsWithRepeatedHeader() throws Exception {
        TableSemantics.Border border = new TableSemantics.Border(2, "rgb(10,20,30)", "solid");
        List<TableSemantics.Cell> cells = List.of(
            cell(0,0,1,2,true,"Header","rgb(220,230,240)","center",border),
            cell(1,0,1,1,false,"A","rgb(255,240,220)","left",border), cell(1,1,1,1,false,"B","transparent","right",border),
            cell(2,0,1,1,false,"C","transparent","left",border), cell(2,1,1,1,false,"D","transparent","right",border),
            cell(3,0,1,1,false,"E","transparent","left",border), cell(3,1,1,1,false,"F","transparent","right",border));
        TableSemantics semantics = new TableSemantics(4,2,List.of(70d,30d),List.of(5d,15d,15d,15d),1,cells);
        PageBlock table = table(semantics);
        var first = (RenderItem.NativeTable) planner.plan(model(List.of(table)), slice(0,25), 20,500,12,18).getFirst();
        var second = (RenderItem.NativeTable) planner.plan(model(List.of(table)), slice(25,50),20,500,12,18).getFirst();
        assertThat(first.rows()).containsExactly(0,1); assertThat(second.rows()).containsExactly(0,2,3);
        assertThat(first.rows()).doesNotContain(2); // a source row is assigned whole, never split

        byte[] bytes = render(model(List.of(table)), List.of(slice(0,25),slice(25,50)));
        try (XMLSlideShow reopened = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(reopened.getSlides()).hasSize(2);
            for (var slide : reopened.getSlides()) {
                XSLFTable nativeTable = slide.getShapes().stream().filter(XSLFTable.class::isInstance).map(XSLFTable.class::cast).findFirst().orElseThrow();
                assertThat(nativeTable.getCell(0,0).getText()).isEqualTo("Header");
                assertThat(nativeTable.getCell(0,0).getFillColor()).isNotNull();
                assertThat(nativeTable.getColumnWidth(0)).isGreaterThan(nativeTable.getColumnWidth(1));
            }
            XSLFTable firstTable = (XSLFTable) reopened.getSlides().getFirst().getShapes().stream().filter(XSLFTable.class::isInstance).findFirst().orElseThrow();
            assertThat(firstTable.getCell(0,0).getGridSpan()).isEqualTo(2);
            assertThat(firstTable.getCell(1,1).getTextParagraphs().getFirst().getTextAlign().name()).isEqualTo("RIGHT");
        }
    }

    @Test void nativeCompositeRootsSuppressCellLeavesButKeepNestedListItems() {
        TableSemantics semantics = new TableSemantics(1,1,List.of(100d),List.of(20d),0,
            List.of(new TableSemantics.Cell(0,0,1,1,false,"cell")));
        PageBlock table = new PageBlock("table",BlockType.TABLE,"",List.of("cell"),0,0,0,
            new Bounds(0,0,100,20),null,TransformSummary.none(),List.of(),style(),List.of(),List.of(),null,
            semantics,List.of(),AuthoringHints.none(),List.of());
        PageBlock cellLeaf = new PageBlock("cell",BlockType.TEXT,"table",List.of(),1,1,1,
            new Bounds(0,0,100,20),null,TransformSummary.none(),List.of(),style(),
            List.of(new TextRun("cell",style())),List.of(),null,null,List.of(),AuthoringHints.none(),List.of());
        assertThat(planner.plan(model(List.of(table,cellLeaf)),slice(0,50),20))
            .singleElement().isInstanceOf(RenderItem.NativeTable.class);

        PageBlock outer = new PageBlock("outer",BlockType.LIST,"",List.of("nested"),0,0,0,
            new Bounds(0,20,80,15),null,TransformSummary.none(),List.of(),style(),
            List.of(new TextRun("outer",style())),List.of(),new ListSemantics(false,1,0,"disc"),null,
            List.of(),AuthoringHints.none(),List.of());
        PageBlock nested = new PageBlock("nested",BlockType.LIST,"outer",List.of(),1,1,1,
            new Bounds(10,35,70,15),null,TransformSummary.none(),List.of(),style(),
            List.of(new TextRun("nested",style())),List.of(),new ListSemantics(true,2,1,"decimal"),null,
            List.of(),AuthoringHints.none(),List.of());
        assertThat(planner.plan(model(List.of(outer,nested)),slice(0,50),20))
            .filteredOn(RenderItem.NativeListItem.class::isInstance).hasSize(2);
    }

    @Test void boundedWideAndComplexTablesUseLocalizedFallback() {
        TableSemantics simple = new TableSemantics(1,13,List.of(),List.of(),0,
            java.util.stream.IntStream.range(0,13).mapToObj(c -> new TableSemantics.Cell(0,c,1,1,false,"x")).toList());
        assertThat(planner.plan(model(List.of(table(simple))), slice(0,50),20,500,12,18))
            .singleElement().isInstanceOf(RenderItem.ScreenshotCrop.class);
        TableSemantics mergedAcrossPages = new TableSemantics(2,1,List.of(100d),List.of(40d,40d),0,
            List.of(new TableSemantics.Cell(0,0,2,1,false,"merged")));
        assertThat(planner.plan(model(List.of(table(mergedAcrossPages))), slice(0,25),20,500,12,18))
            .singleElement().isInstanceOf(RenderItem.ScreenshotCrop.class);
    }

    private static TableSemantics.Cell cell(int r,int c,int rs,int cs,boolean header,String text,String fill,String align,TableSemantics.Border border) {
        return new TableSemantics.Cell(r,c,rs,cs,header,text,fill,"rgb(20,30,40)","Arial",16,header?700:400,align,"middle",border,border,border,border);
    }
    private static PageBlock list(String id,double y,boolean ordered,int start,int level,String marker,String text) {
        return new PageBlock(id,BlockType.LIST,"",List.of(),0,(int)y,(int)y,new Bounds(0,y,80,15),null,TransformSummary.none(),List.of(),style(),List.of(new TextRun(text,style())),List.of(),new ListSemantics(ordered,start,level,marker),null,List.of(),AuthoringHints.none(),List.of());
    }
    private static PageBlock table(TableSemantics semantics) {
        return new PageBlock("table",BlockType.TABLE,"",List.of(),0,0,0,new Bounds(0,0,100,50),null,TransformSummary.none(),List.of(),style(),List.of(),List.of(),null,semantics,List.of(),AuthoringHints.none(),List.of());
    }
    private static SlideSlice slice(double y,double bottom) {
        return new SlideSlice(y == 0 ? 0 : 1,new SourceBounds(0,y,100,bottom-y),new SlideViewport(720,(bottom-y)*7.2),BreakRationale.FALLBACK,List.of());
    }
    private static byte[] render(com.nicodim.ocapp.pagemodel.PageModel model,List<SlideSlice> slices) throws Exception {
        ConverterProperties properties = new ConverterProperties(); properties.getPptx().setSlideWidthInches(10); properties.getPptx().setSlideHeightInches(5);
        return new HybridPptxRenderer(properties).render(new BufferedImage(200,(int)(model.geometry().height()*2),BufferedImage.TYPE_INT_RGB),model,new PaginationPlan(model.geometry().width(),model.geometry().height(),slices,List.of()));
    }
}
