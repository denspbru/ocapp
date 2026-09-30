package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
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

    @Test void createsValidMultiSlidePresentationWithoutNetwork() throws Exception {
        byte[] pptx = renderer.createPresentation(png(1000, 1200));
        ChromePageRenderer.validateArtifact(pptx, OutputFormat.PPTX);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertThat(show.getSlides()).hasSize(3);
            assertThat(show.getPageSize()).isEqualTo(new java.awt.Dimension(720, 360));
        }
    }

    @Test void rejectsUnreadableScreenshotAndSlideOverflow() {
        assertThatThrownBy(() -> renderer.createPresentation(new byte[]{1, 2, 3}))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_CREATION_FAILED");
        properties.getPptx().setMaxSlides(1);
        assertThatThrownBy(() -> renderer.createPresentation(png(100, 1000)))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("TOO_MANY_SLIDES");
    }

    @Test void validatesRealisticMhtmlPdfAndPptxSignatures() throws Exception {
        byte[] mhtml = "From: <Saved by Blink>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = "%PDF-1.7\nbody\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        ChromePageRenderer.validateArtifact(mhtml, OutputFormat.MHTML);
        ChromePageRenderer.validateArtifact(pdf, OutputFormat.PDF);
        ChromePageRenderer.validateArtifact(renderer.createPresentation(png(10, 10)), OutputFormat.PPTX);
    }

    @Test void rejectsEmptyTruncatedAndMislabeledArtifacts() {
        assertInvalid(null, OutputFormat.PDF);
        assertInvalid(new byte[0], OutputFormat.PDF);
        assertInvalid("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), OutputFormat.PDF);
        assertInvalid("not mime".getBytes(StandardCharsets.US_ASCII), OutputFormat.MHTML);
        assertInvalid(new byte[]{'P', 'K'}, OutputFormat.PPTX);
    }

    private static void assertInvalid(byte[] bytes, OutputFormat format) {
        assertThatThrownBy(() -> ChromePageRenderer.validateArtifact(bytes, format))
            .isInstanceOf(ConversionException.class);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height); graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }
}
