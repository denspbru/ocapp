package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.conversion.PageRenderer;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChromePageRenderer implements PageRenderer {
    private final BrowserFactory browserFactory;
    private final ConverterProperties properties;
    private final UrlSecurityPolicy urlPolicy;

    public ChromePageRenderer(BrowserFactory browserFactory, ConverterProperties properties, UrlSecurityPolicy urlPolicy) {
        this.browserFactory = browserFactory; this.properties = properties; this.urlPolicy = urlPolicy;
    }

    @Override public byte[] render(URI uri, OutputFormat format) {
        try (BrowserSession session = browserFactory.open()) {
            ChromeDriver driver = session.driver();
            AtomicReference<ConversionException> blockedRequest = installRequestGuard(driver);
            try {
                driver.get(uri.toASCIIString());
            } catch (WebDriverException ex) {
                throwBlocked(blockedRequest);
                throw ex;
            }
            throwBlocked(blockedRequest);
            urlPolicy.validate(driver.getCurrentUrl());
            waitUntilReady(driver);
            throwBlocked(blockedRequest);
            byte[] result = switch (format) {
                case MHTML -> captureMhtml(driver);
                case PDF -> capturePdf(driver);
                case PPTX -> capturePptx(driver);
            };
            throwBlocked(blockedRequest);
            validateArtifact(result, format);
            if (result.length > properties.getLimits().getMaxOutputBytes()) {
                throw new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "OUTPUT_TOO_LARGE", "Generated document exceeds the configured limit");
            }
            // Revalidate the browser's actual top-level URL after rendering in case script navigation occurred.
            urlPolicy.validate(driver.getCurrentUrl());
            return result;
        } catch (ConversionException ex) { throw ex; }
        catch (TimeoutException ex) { throw new ConversionException(HttpStatus.GATEWAY_TIMEOUT, "CONVERSION_TIMEOUT", "Page did not become ready in time", ex); }
        catch (RuntimeException ex) { throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGE_RENDER_FAILED", "Page could not be rendered", ex); }
    }

    private AtomicReference<ConversionException> installRequestGuard(ChromeDriver driver) {
        AtomicReference<ConversionException> blocked = new AtomicReference<>();
        DevTools devTools = driver.getDevTools();
        devTools.createSessionIfThereIsNotOne();
        devTools.getDomains().network().interceptTrafficWith(next -> request -> {
            try {
                urlPolicy.validate(request.getUri());
                return next.execute(request);
            } catch (ConversionException ex) {
                blocked.compareAndSet(null, ex);
                return new HttpResponse().setStatus(HttpStatus.FORBIDDEN.value());
            }
        });
        return blocked;
    }

    private static void throwBlocked(AtomicReference<ConversionException> blocked) {
        ConversionException failure = blocked.get();
        if (failure != null) throw failure;
    }

    private void waitUntilReady(ChromeDriver driver) {
        JavascriptExecutor js = driver;
        new WebDriverWait(driver, properties.getBrowser().getReadinessTimeout()).until(ignored -> Boolean.TRUE.equals(js.executeScript("""
            return document.readyState === 'complete' &&
              (!document.fonts || document.fonts.status === 'loaded') &&
              Array.from(document.images).every(i => i.complete) &&
              Array.from(document.querySelectorAll('canvas')).every(c => c.width > 0 && c.height > 0);
            """)));
        Duration delay = properties.getBrowser().getSettleDelay();
        if (!delay.isZero() && !delay.isNegative()) {
            try { Thread.sleep(delay); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ConversionException(HttpStatus.GATEWAY_TIMEOUT, "CONVERSION_TIMEOUT", "Rendering was interrupted", ex);
            }
        }
    }

    private static byte[] captureMhtml(ChromeDriver driver) {
        Map<String, Object> response = driver.executeCdpCommand("Page.captureSnapshot", Map.of("format", "mhtml"));
        Object data = response.get("data");
        if (!(data instanceof String text)) throw new IllegalStateException("Chrome returned no MHTML data");
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] capturePdf(ChromeDriver driver) {
        var pdf = properties.getPdf();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("printBackground", true); parameters.put("preferCSSPageSize", false);
        parameters.put("landscape", pdf.isLandscape()); parameters.put("paperWidth", pdf.getPaperWidthInches());
        parameters.put("paperHeight", pdf.getPaperHeightInches()); parameters.put("marginTop", pdf.getMarginInches());
        parameters.put("marginBottom", pdf.getMarginInches()); parameters.put("marginLeft", pdf.getMarginInches()); parameters.put("marginRight", pdf.getMarginInches());
        parameters.put("transferMode", "ReturnAsBase64");
        Object data = driver.executeCdpCommand("Page.printToPDF", parameters).get("data");
        if (!(data instanceof String encoded)) throw new IllegalStateException("Chrome returned no PDF data");
        return Base64.getDecoder().decode(encoded);
    }

    private byte[] capturePptx(ChromeDriver driver) {
        Map<String, Object> result = driver.executeCdpCommand("Page.captureScreenshot", Map.of(
            "format", "png", "captureBeyondViewport", true, "fromSurface", true));
        Object data = result.get("data");
        if (!(data instanceof String encoded)) throw new IllegalStateException("Chrome returned no screenshot data");
        return createPresentation(Base64.getDecoder().decode(encoded));
    }

    byte[] createPresentation(byte[] screenshot) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(screenshot));
            if (source == null) throw new IOException("Unsupported screenshot");
            var ppt = properties.getPptx();
            try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                int slideWidth = (int) Math.round(ppt.getSlideWidthInches() * 72);
                int slideHeight = (int) Math.round(ppt.getSlideHeightInches() * 72);
                show.setPageSize(new Dimension(slideWidth, slideHeight));
                double slideAspect = (double) slideWidth / slideHeight;
                int cropHeight = Math.max(1, (int) Math.round(source.getWidth() / slideAspect));
                int requiredSlides = (source.getHeight() + cropHeight - 1) / cropHeight;
                if (requiredSlides > ppt.getMaxSlides()) {
                    throw new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_MANY_SLIDES", "Page requires more slides than configured");
                }
                for (int i = 0; i < requiredSlides; i++) {
                    int y = i * cropHeight;
                    int height = Math.min(cropHeight, source.getHeight() - y);
                    BufferedImage crop = source.getSubimage(0, y, source.getWidth(), height);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    if (!ImageIO.write(crop, "png", png)) throw new IOException("PNG encoder is unavailable");
                    XSLFPictureData picture = show.addPicture(png.toByteArray(), PictureData.PictureType.PNG);
                    XSLFSlide slide = show.createSlide();
                    XSLFPictureShape shape = slide.createPicture(picture);
                    double renderedHeight = (double) height / cropHeight * slideHeight;
                    shape.setAnchor(new java.awt.geom.Rectangle2D.Double(0, 0, slideWidth, renderedHeight));
                }
                show.write(output);
                return output.toByteArray();
            }
        } catch (IOException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_CREATION_FAILED", "Presentation could not be created", ex);
        }
    }

    static void validateArtifact(byte[] bytes, OutputFormat format) {
        if (bytes == null || bytes.length == 0) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "CONVERSION_FAILED", "Browser produced an empty document");
        }
        boolean valid = switch (format) {
            case PDF -> startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII)) && containsNearEnd(bytes, "%%EOF".getBytes(StandardCharsets.US_ASCII), 2048);
            case PPTX -> startsWith(bytes, new byte[]{'P', 'K', 3, 4});
            case MHTML -> {
                String prefix = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT);
                yield prefix.contains("mime-version:") && prefix.contains("content-type: multipart/related");
            }
        };
        if (!valid) throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OUTPUT", "Browser produced an invalid document");
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static boolean containsNearEnd(byte[] value, byte[] marker, int window) {
        int from = Math.max(0, value.length - window);
        outer: for (int i = from; i <= value.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) if (value[i + j] != marker[j]) continue outer;
            return true;
        }
        return false;
    }
}
