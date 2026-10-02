package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.pagination.BreakRationale;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.pagination.SlideViewport;
import com.nicodim.ocapp.pagination.SourceBounds;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChromePageRendererTest {
    private ConverterProperties properties;
    private ChromePageRenderer renderer;

    @BeforeEach void setUp() {
        properties = new ConverterProperties();
        properties.getPptx().setSlideWidthInches(10);
        properties.getPptx().setSlideHeightInches(5);
        renderer = new ChromePageRenderer(mock(BrowserFactory.class), properties, mock(UrlSecurityPolicy.class));
    }

    @Test void createsValidLegacyMultiSlidePresentationWithoutNetwork() throws Exception {
        byte[] pptx = renderer.createPresentation(png(1000, 1200));
        ChromePageRenderer.validateArtifact(pptx, OutputFormat.PPTX);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertThat(show.getSlides()).hasSize(3);
            assertThat(show.getPageSize()).isEqualTo(new java.awt.Dimension(720, 360));
        }
    }

    @Test void rendersPlannedCropsInOrderWithExactAspectAndNoDistortion() throws Exception {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        paint(source, 0, 25, Color.RED); paint(source, 25, 35, Color.GREEN); paint(source, 60, 40, Color.BLUE);
        PaginationPlan plan = plan(100, 100, 25, 35, 40);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(renderer.createPresentation(png(source), plan)))) {
            assertThat(show.getSlides()).hasSize(3);
            List<Color> expected = List.of(Color.RED, Color.GREEN, Color.BLUE);
            for (int i = 0; i < 3; i++) {
                XSLFPictureShape shape = (XSLFPictureShape) show.getSlides().get(i).getShapes().getFirst();
                BufferedImage crop = ImageIO.read(new ByteArrayInputStream(shape.getPictureData().getData()));
                assertThat(new Color(crop.getRGB(crop.getWidth() / 2, crop.getHeight() / 2))).isEqualTo(expected.get(i));
                assertThat(shape.getAnchor().getWidth() / shape.getAnchor().getHeight())
                    .isCloseTo((double) crop.getWidth() / crop.getHeight(), org.assertj.core.data.Offset.offset(0.000_001));
            }
        }
    }

    @Test void mapsCssSourceDimensionsToDprScreenshotWithSafeExactPixelCoverage() throws Exception {
        PaginationPlan cssPlan = plan(100, 100, 33.3, 33.3, 33.4);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(renderer.createPresentation(png(200, 200), cssPlan)))) {
            List<Integer> heights = show.getSlides().stream().map(slide -> {
                try {
                    XSLFPictureShape shape = (XSLFPictureShape) slide.getShapes().getFirst();
                    return ImageIO.read(new ByteArrayInputStream(shape.getPictureData().getData())).getHeight();
                } catch (Exception ex) { throw new AssertionError(ex); }
            }).toList();
            assertThat(heights).containsExactly(67, 66, 67);
            assertThat(heights.stream().mapToInt(Integer::intValue).sum()).isEqualTo(200);
        }
    }

    @Test void rejectsInvalidRoundedZeroNonMonotonicAspectAndExcessivePlans() throws Exception {
        PaginationPlan invalid = mock(PaginationPlan.class);
        when(invalid.sourceWidth()).thenReturn(Double.NaN);
        when(invalid.sourceHeight()).thenReturn(100.0);
        when(invalid.slices()).thenReturn(List.of(plan(100, 100, 100).slices().getFirst()));
        assertCode(() -> renderer.createPresentation(png(100, 100), invalid), "PAGINATION_INVALID");

        assertCode(() -> renderer.createPresentation(png(2, 2), plan(1000, 1000, 100, 900)), "PAGINATION_INVALID");

        PaginationPlan nonMonotonic = mock(PaginationPlan.class);
        when(nonMonotonic.sourceWidth()).thenReturn(100.0); when(nonMonotonic.sourceHeight()).thenReturn(100.0);
        when(nonMonotonic.slices()).thenReturn(List.of(
            slice(0, 0, 50, 100), slice(1, 40, 60, 100)));
        assertCode(() -> renderer.createPresentation(png(100, 100), nonMonotonic), "PAGINATION_INVALID");

        assertCode(() -> renderer.createPresentation(png(110, 100), plan(100, 100, 100)), "PAGINATION_INVALID");

        properties.getPptx().setMaxSlides(1);
        assertCode(() -> renderer.createPresentation(png(100, 100), plan(100, 100, 50, 50)), "PPTX_MAX_SLIDES_EXCEEDED");
    }

    @Test void rejectsUnreadableScreenshotAndSlideOverflowWithStableCode() throws Exception {
        assertCode(() -> renderer.createPresentation(new byte[]{1, 2, 3}), "INVALID_SCREENSHOT");
        properties.getPptx().setMaxSlides(1);
        assertCode(() -> renderer.createPresentation(png(100, 1000)), "PPTX_MAX_SLIDES_EXCEEDED");
    }

    @Test void validatesRealisticMhtmlPdfAndPptxSignatures() throws Exception {
        byte[] mhtml = "From: <Saved by Blink>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = "%PDF-1.7\nbody\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        ChromePageRenderer.validateArtifact(mhtml, OutputFormat.MHTML);
        ChromePageRenderer.validateArtifact(pdf, OutputFormat.PDF);
        ChromePageRenderer.validateArtifact(renderer.createPresentation(png(10, 10)), OutputFormat.PPTX);
    }

    @Test void rejectsOversizedPngFromIhdrBeforeImageIoAllocation() throws Exception {
        byte[] image = png(10, 10);
        image[16] = 0; image[17] = 1; image[18] = (byte) 0x86; image[19] = (byte) 0xa0;
        assertCode(() -> renderer.createPresentation(image), "OUTPUT_TOO_LARGE");
    }

    @Test void rejectsEmptyTruncatedAndMislabeledArtifacts() {
        assertInvalid(null, OutputFormat.PDF); assertInvalid(new byte[0], OutputFormat.PDF);
        assertInvalid("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), OutputFormat.PDF);
        assertInvalid("not mime".getBytes(StandardCharsets.US_ASCII), OutputFormat.MHTML);
        assertInvalid(new byte[]{'P', 'K'}, OutputFormat.PPTX);
    }

    private static PaginationPlan plan(double width, double height, double... heights) {
        List<SlideSlice> slices = new ArrayList<>(); double y = 0;
        for (int i = 0; i < heights.length; i++) { slices.add(slice(i, y, heights[i], width)); y += heights[i]; }
        return new PaginationPlan(width, height, slices, List.of());
    }

    private static SlideSlice slice(int index, double y, double height, double width) {
        return new SlideSlice(index, new SourceBounds(0, y, width, height), new SlideViewport(720, 360),
            y + height >= 100 ? BreakRationale.DOCUMENT_END : BreakRationale.BLOCK_BOUNDARY, List.of());
    }

    private static void paint(BufferedImage image, int y, int height, Color color) {
        var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, y, image.getWidth(), height); graphics.dispose();
    }

    private static void assertInvalid(byte[] bytes, OutputFormat format) {
        assertThatThrownBy(() -> ChromePageRenderer.validateArtifact(bytes, format)).isInstanceOf(ConversionException.class);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(ConversionException.class).extracting("code").isEqualTo(code);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        paint(image, 0, height, Color.WHITE); return png(image);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue(); return output.toByteArray();
    }
}
