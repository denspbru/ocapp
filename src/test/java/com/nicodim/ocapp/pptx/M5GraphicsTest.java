package com.nicodim.ocapp.pptx;

import static com.nicodim.ocapp.pptx.EditableRenderPlannerTest.block;
import static com.nicodim.ocapp.pptx.EditableRenderPlannerTest.model;
import static com.nicodim.ocapp.pptx.EditableRenderPlannerTest.slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.AssetReference;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.junit.jupiter.api.Test;

class M5GraphicsTest {
    @Test void plannerSelectsSafeEmbeddedGraphicsAndLocalizesFailedExportsDeterministically() throws Exception {
        AssetReference svg = data("svg", AssetReference.Kind.SVG, "image/svg+xml", safeSvg());
        AssetReference canvas = data("canvas", AssetReference.Kind.CANVAS, "image/png", png());
        AssetReference failed = new AssetReference("failed", AssetReference.Kind.CHART,
            URI.create("urn:ocapp:graphics-fallback:graphics_echarts_missing"), "", 0, false);
        var svgBlock = block("g1", BlockType.SVG, "", List.of(), new Bounds(0, 0, 20, 20), List.of(), List.of(), List.of(svg), false, List.of());
        var canvasBlock = block("g2", BlockType.CANVAS, "", List.of(), new Bounds(25, 0, 20, 20), List.of(), List.of(), List.of(canvas), false, List.of());
        var failedBlock = block("g3", BlockType.CHART, "", List.of(), new Bounds(50, 0, 20, 20), List.of(), List.of(), List.of(failed), false, List.of());
        var page = model(List.of(svgBlock, canvasBlock, failedBlock));

        List<RenderItem> items = new EditableRenderPlanner().plan(page, slice(), 10);
        assertThat(items).containsExactlyElementsOf(new EditableRenderPlanner().plan(page, slice(), 10));
        assertThat(items).filteredOn(RenderItem.NativeGraphic.class::isInstance).hasSize(2);
        assertThat(items).filteredOn(RenderItem.ScreenshotCrop.class::isInstance).singleElement()
            .extracting(RenderItem::bounds).isEqualTo(new RenderItem.Rect(50, 0, 20, 20));
    }

    @Test void validatedGraphicRootSuppressesUnsupportedDescendantWithoutLosingNativeAsset() throws Exception {
        AssetReference svg = data("svg", AssetReference.Kind.SVG, "image/svg+xml", safeSvg());
        AssetReference failed = new AssetReference("failed", AssetReference.Kind.CHART,
            URI.create("urn:ocapp:graphics-fallback:graphics_echarts_missing"), "", 0, false);
        var svgRoot = block("g1", BlockType.SVG, "", List.of("g2"), new Bounds(0, 0, 40, 20),
            List.of(), List.of(), List.of(svg), false, List.of());
        var unsupportedDescendant = block("g2", BlockType.FALLBACK, "g1", List.of(), new Bounds(0, 0, 40, 20),
            List.of(), List.of(), List.of(), false, List.of());
        var failedChart = block("g3", BlockType.CHART, "", List.of(), new Bounds(50, 0, 20, 20),
            List.of(), List.of(), List.of(failed), false, List.of());

        assertThat(new EditableRenderPlanner().plan(model(List.of(svgRoot, unsupportedDescendant, failedChart)), slice(), 10))
            .containsExactly(
                new RenderItem.ScreenshotCrop(new RenderItem.Rect(50, 0, 20, 20)),
                new RenderItem.NativeGraphic(new RenderItem.Rect(0, 0, 40, 20), svg));
    }

    @Test void rendererEmbedsSvgAndPngReopensWithPoiAndKeepsAlphaAndAspectBoundary() throws Exception {
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().setSlideWidthInches(10); properties.getPptx().setSlideHeightInches(5);
        AssetReference svg = data("svg", AssetReference.Kind.SVG, "image/svg+xml", safeSvg());
        AssetReference canvas = data("canvas", AssetReference.Kind.CANVAS, "image/png", png());
        var page = model(List.of(
            block("g1", BlockType.SVG, "", List.of(), new Bounds(0, 0, 40, 20), List.of(), List.of(), List.of(svg), false, List.of()),
            block("g2", BlockType.CANVAS, "", List.of(), new Bounds(50, 0, 20, 20), List.of(), List.of(), List.of(canvas), false, List.of())));
        BufferedImage screenshot = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB);
        byte[] bytes = new HybridPptxRenderer(properties).render(screenshot, page,
            new PaginationPlan(100, 50, List.of(slice()), List.of()));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(show.getSlides()).hasSize(1);
            assertThat(show.getSlides().getFirst().getShapes()).hasSize(2).allMatch(XSLFPictureShape.class::isInstance);
            assertThat(show.getPictureData()).extracting(PictureData::getType)
                .containsExactlyInAnyOrder(PictureData.PictureType.SVG, PictureData.PictureType.PNG);
            assertThat(show.getSlides().getFirst().getShapes().getFirst().getAnchor().getWidth()
                / show.getSlides().getFirst().getShapes().getFirst().getAnchor().getHeight()).isEqualTo(2d);
        }
    }

    @Test void svgValidatorRejectsActiveExternalMalformedAndUnsupportedContent() {
        SafeSvg.validate(safeSvg());
        for (String unsafe : List.of(
            "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg'><image href='https://example.test/x'/></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg'><rect style='fill:url(data:x)'/></svg>",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>",
            "<svg xmlns='http://www.w3.org/2000/svg'><animate attributeName='x'/></svg>"))
            assertThatThrownBy(() -> SafeSvg.validate(unsafe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rendererAppliesGraphicsByteLimitBeforePoiEmbedding() throws Exception {
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().setGraphicsExportScale(1);
        properties.getPptx().setMaxGraphicsExportBytes(1024);
        byte[] oversized = ("<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><text>"
            + "x".repeat(2000) + "</text></svg>").getBytes(StandardCharsets.UTF_8);
        AssetReference svg = data("svg", AssetReference.Kind.SVG, "image/svg+xml", oversized);
        var page = model(List.of(block("g1", BlockType.SVG, "", List.of(), new Bounds(0, 0, 20, 20),
            List.of(), List.of(), List.of(svg), false, List.of())));
        assertThatThrownBy(() -> new HybridPptxRenderer(properties).render(new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB),
            page, new PaginationPlan(100, 50, List.of(slice()), List.of())))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("OUTPUT_TOO_LARGE");
    }

    private static AssetReference data(String id, AssetReference.Kind kind, String media, byte[] bytes) {
        return new AssetReference(id, kind, URI.create("data:" + media + ";base64," + Base64.getEncoder().encodeToString(bytes)),
            media, bytes.length, true);
    }
    private static byte[] safeSvg() {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 20' preserveAspectRatio='xMidYMid meet'><rect width='40' height='20' fill='#ff0000' fill-opacity='.5'/></svg>".getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, new Color(20, 80, 220, 128).getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", out)).isTrue();
        return out.toByteArray();
    }
}
