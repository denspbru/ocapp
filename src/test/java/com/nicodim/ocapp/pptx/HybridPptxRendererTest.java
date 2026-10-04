package com.nicodim.ocapp.pptx;

import static com.nicodim.ocapp.pptx.EditableRenderPlannerTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.AssetReference;
import com.nicodim.ocapp.pagemodel.AuthoringHints;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.ComputedStyle;
import com.nicodim.ocapp.pagemodel.LinkReference;
import com.nicodim.ocapp.pagemodel.PageBlock;
import com.nicodim.ocapp.pagemodel.TextRun;
import com.nicodim.ocapp.pagemodel.TransformSummary;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridPptxRendererTest {
    private ConverterProperties properties;

    @BeforeEach void setUp() {
        properties = new ConverterProperties();
        properties.getPptx().setSlideWidthInches(10);
        properties.getPptx().setSlideHeightInches(5);
    }

    @Test void createsEditableStyledLinkedTextNativeImageAndLocalizedFallback() throws Exception {
        byte[] imageBytes = png(2, 1, Color.MAGENTA);
        AssetReference image = new AssetReference("asset", AssetReference.Kind.IMAGE,
            URI.create("data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes)),
            "image/png", imageBytes.length, true);
        PageBlock text = block("text", BlockType.TEXT, "", List.of(), new Bounds(5, 3, 45, 12),
            List.of(new TextRun("Open link", style())),
            List.of(new LinkReference("Open link", URI.create("https://example.org/path?q=1"))), List.of(), false, List.of());
        PageBlock picture = block("image", BlockType.IMAGE, "", List.of(), new Bounds(60, 3, 20, 20),
            List.of(), List.of(), List.of(image), false, List.of());
        PageBlock table = block("table", BlockType.TABLE, "", List.of(), new Bounds(10, 28, 30, 15),
            List.of(), List.of(), List.of(), false, List.of());
        PageBlock multiline = block("multiline", BlockType.TEXT, "", List.of(), new Bounds(82, 0, 18, 50),
            List.of(new TextRun("line one\nline two", style())), List.of(), List.of(), false, List.of());
        ComputedStyle backgroundStyle = new ComputedStyle("block", "static", "visible", "visible", "black",
            "rgb(240, 240, 240)", "Arial", 16, 400, "normal", "left", 19.2, 1, 0, false, false);
        PageBlock background = new PageBlock("background", BlockType.CONTAINER, "", List.of(), 0, 0, 0,
            new Bounds(0, 0, 100, 50), null, TransformSummary.none(), List.of(), backgroundStyle,
            List.of(), List.of(), null, null, List.of(), AuthoringHints.none(), List.of());
        var model = model(List.of(background, text, picture, table, multiline));
        PaginationPlan plan = new PaginationPlan(100, 50, List.of(slice()), List.of());
        BufferedImage screenshot = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = screenshot.createGraphics(); graphics.setColor(Color.CYAN); graphics.fillRect(0, 0, 200, 100); graphics.dispose();

        byte[] pptx = new HybridPptxRenderer(properties).render(screenshot, model, plan);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertThat(show.getSlides()).hasSize(1);
            assertThat(show.getSlides().getFirst().getShapes()).hasSize(5);
            XSLFTextShape textShape = show.getSlides().getFirst().getShapes().stream()
                .filter(XSLFTextBox.class::isInstance).map(XSLFTextShape.class::cast).findFirst().orElseThrow();
            var run = textShape.getTextParagraphs().getFirst().getTextRuns().getFirst();
            assertThat(run.getRawText()).isEqualTo("Open link");
            assertThat(run.getFontFamily()).isEqualTo("Arial");
            assertThat(run.getFontSize()).isEqualTo(12d);
            assertThat(run.getXmlObject().toString()).containsIgnoringCase("0A141E");
            assertThat(run.getHyperlink()).isNotNull();
            assertThat(run.getHyperlink().getAddress()).isEqualTo("https://example.org/path?q=1");
            assertThat(textShape.getWordWrap()).isFalse();
            assertThat(show.getSlides().getFirst().getShapes()).filteredOn(XSLFTextBox.class::isInstance)
                .map(XSLFTextShape.class::cast).filteredOn(shape -> shape.getText().contains("line two"))
                .singleElement().satisfies(shape -> assertThat(shape.getWordWrap()).isTrue());

            List<XSLFPictureShape> pictures = show.getSlides().getFirst().getShapes().stream()
                .filter(XSLFPictureShape.class::isInstance).map(XSLFPictureShape.class::cast).toList();
            assertThat(pictures).hasSize(2);
            XSLFPictureShape nativeImage = pictures.stream().filter(p -> p.getAnchor().getX() > 400).findFirst().orElseThrow();
            assertThat(nativeImage.getAnchor().getWidth() / nativeImage.getAnchor().getHeight()).isEqualTo(2d);
            assertThat(nativeImage.getAnchor().getCenterX()).isCloseTo(504d, org.assertj.core.data.Offset.offset(0.01));
            XSLFPictureShape fallback = pictures.stream().filter(p -> p.getAnchor().getY() > 150).findFirst().orElseThrow();
            BufferedImage crop = ImageIO.read(new ByteArrayInputStream(fallback.getPictureData().getData()));
            assertThat(crop.getWidth()).isEqualTo(60);
            assertThat(crop.getHeight()).isEqualTo(30);
        }
    }

    @Test void enforcesAssetAndShapeLimitsBeforePoiGrowth() throws Exception {
        byte[] imageBytes = png(4, 4, Color.BLACK);
        AssetReference image = new AssetReference("asset", AssetReference.Kind.IMAGE,
            URI.create("data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes)),
            "image/png", imageBytes.length, true);
        PageBlock picture = block("image", BlockType.IMAGE, "", List.of(), new Bounds(0, 0, 20, 20),
            List.of(), List.of(), List.of(image), false, List.of());
        var page = model(List.of(picture));
        PaginationPlan plan = new PaginationPlan(100, 50, List.of(slice()), List.of());
        BufferedImage screenshot = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        properties.getPageModel().setMaxAssetBytes(8);
        assertThatThrownBy(() -> new HybridPptxRenderer(properties).render(screenshot, page, plan))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("OUTPUT_TOO_LARGE");

        properties.getPageModel().setMaxAssetBytes(1024 * 1024);
        properties.getPptx().setMaxItemsPerSlide(1);
        PageBlock text = block("text", BlockType.TEXT, "", List.of(), new Bounds(30, 0, 20, 10),
            List.of(new TextRun("text", style())), List.of(), List.of(), false, List.of());
        byte[] collapsed = new HybridPptxRenderer(properties).render(screenshot, model(List.of(picture, text)), plan);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(collapsed))) {
            assertThat(show.getSlides().getFirst().getShapes()).singleElement().isInstanceOf(XSLFPictureShape.class);
        }
    }

    private static byte[] png(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, 0, width, height); graphics.dispose();
        return png(image);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }
}
