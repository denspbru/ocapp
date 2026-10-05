package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.conversion.PageRenderer;
import com.nicodim.ocapp.conversion.RenderContext;
import com.nicodim.ocapp.pagemodel.DomPageExtractor;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagination.PaginationOptions;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.pagination.SmartPaginationPlanner;
import com.nicodim.ocapp.pptx.HybridPptxRenderer;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.openqa.selenium.devtools.Command;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChromePageRenderer implements PageRenderer {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final BrowserFactory browserFactory;
    private final ConverterProperties properties;
    private final UrlSecurityPolicy urlPolicy;
    private final NavigationTrace navigationTrace;

    @Autowired
    public ChromePageRenderer(BrowserFactory browserFactory, ConverterProperties properties, UrlSecurityPolicy urlPolicy) {
        this(browserFactory, properties, urlPolicy, new NavigationTrace());
    }

    ChromePageRenderer(BrowserFactory browserFactory, ConverterProperties properties, UrlSecurityPolicy urlPolicy, NavigationTrace navigationTrace) {
        this.browserFactory = browserFactory;
        this.properties = properties;
        this.urlPolicy = urlPolicy;
        this.navigationTrace = navigationTrace;
    }

    @Override public byte[] render(URI uri, OutputFormat format) {
        return render(uri, format, new RenderContext());
    }

    @Override public byte[] render(URI uri, OutputFormat format, RenderContext context) {
        BrowserSession session = browserFactory.open(context);
        try (session) {
            ChromeDriver driver = session.driver();
            DevTools devTools = driver.getDevTools();
            try {
            AtomicReference<ConversionException> blockedRequest = installRequestGuard(driver);
            NavigationTrace.Recorder trace = navigationTrace.observe(driver.getDevTools());
            try { driver.get(uri.toASCIIString()); }
            catch (WebDriverException ex) {
                throwBlocked(blockedRequest);
                throw ex;
            }
            throwBlocked(blockedRequest);
            NavigationTrace.Result navigation = trace.result();
            validateNavigation(navigation);
            urlPolicy.validate(navigation.finalUrl() == null ? driver.getCurrentUrl() : navigation.finalUrl());
            waitUntilReady(driver, trace);
            throwBlocked(blockedRequest);
            NavigationTrace.Result readyNavigation = trace.result();
            validateNavigation(readyNavigation);
            urlPolicy.validate(readyNavigation.finalUrl() == null ? driver.getCurrentUrl() : readyNavigation.finalUrl());
            validateLayout(driver);
            byte[] result = switch (format) {
                case MHTML -> captureMhtml(driver);
                case PDF -> capturePdf(driver);
                case PPTX -> {
                    PreparedPptx prepared = preparePptx(driver);
                    yield capturePptx(driver, prepared);
                }
            };
            throwBlocked(blockedRequest);
            NavigationTrace.Result capturedNavigation = trace.result();
            validateNavigation(capturedNavigation);
            urlPolicy.validate(capturedNavigation.finalUrl() == null ? driver.getCurrentUrl() : capturedNavigation.finalUrl());
            validateArtifact(result, format);
            if (result.length > properties.getLimits().getMaxOutputBytes()) throw tooLarge("Generated document exceeds the configured limit");
            urlPolicy.validate(driver.getCurrentUrl());
            return result;
            } finally {
                stopCdpObservers(devTools);
            }
        } catch (ConversionException ex) { throw ex; }
        catch (TimeoutException ex) { throw timeout("Page did not become ready in time", ex); }
        catch (RuntimeException ex) { throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGE_RENDER_FAILED", "Page could not be rendered", ex); }
        finally { context.unregister(session); }
    }

    private static void stopCdpObservers(DevTools devTools) {
        try {
            // The browser session is closed immediately after rendering. Clearing listeners is
            // sufficient and avoids racing a queued Fetch.requestPaused callback by disabling
            // the domain while that callback is still sending continueRequest/fulfillRequest.
            devTools.clearListeners();
        } catch (RuntimeException ignored) { }
    }

    private AtomicReference<ConversionException> installRequestGuard(ChromeDriver driver) {
        AtomicReference<ConversionException> blocked = new AtomicReference<>();
        DevTools devTools = driver.getDevTools();
        devTools.createSessionIfThereIsNotOne();
        devTools.addListener(mapEvent("Fetch.requestPaused"), event -> {
            String requestId = String.valueOf(event.get("requestId"));
            Object rawRequest = event.get("request");
            String requestUri = rawRequest instanceof Map<?, ?> request ? String.valueOf(request.get("url")) : "";
            try {
                validateBrowserRequest(requestUri);
                devTools.send(new Command<>("Fetch.continueRequest", Map.of("requestId", requestId)));
            } catch (ConversionException ex) {
                blocked.compareAndSet(null, ex);
                devTools.send(new Command<>("Fetch.fulfillRequest", Map.of("requestId", requestId, "responseCode", 403)));
            }
        });
        devTools.send(new Command<>("Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*")))));
        return blocked;
    }

    void validateBrowserRequest(String requestUri) {
        String scheme;
        try { scheme = URI.create(requestUri).getScheme(); }
        catch (IllegalArgumentException ex) { throw new ConversionException(HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "Browser request URL is invalid", ex); }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            urlPolicy.validate(requestUri);
        } else if ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
            urlPolicy.validate(("wss".equalsIgnoreCase(scheme) ? "https" : "http") + requestUri.substring(scheme.length()));
        } else if (!("data".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme))) {
            throw new ConversionException(HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "Browser request scheme is blocked by security policy");
        }
    }

    @SuppressWarnings("unchecked")
    private static Event<Map<String, Object>> mapEvent(String method) {
        return new Event<>(method, input -> (Map<String, Object>) input.read(Map.class));
    }

    private static void throwBlocked(AtomicReference<ConversionException> blocked) {
        ConversionException failure = blocked.get();
        if (failure != null) throw failure;
    }

    private void validateNavigation(NavigationTrace.Result result) {
        if (result.redirects() > properties.getSecurity().getMaxRedirects()) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_REDIRECTS", "Browser navigation has too many redirects");
        }
        if (result.status() >= 400) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_HTTP_ERROR", "Target browser GET returned HTTP " + result.status());
        }
    }

    private void waitUntilReady(ChromeDriver driver, NavigationTrace.Recorder trace) {
        JavascriptExecutor js = driver;
        String stateKey = "__ocapp_" + UUID.randomUUID().toString().replace("-", "");
        long quietMillis = properties.getBrowser().getDomQuietPeriod().toMillis();
        String selector = properties.getBrowser().getReadinessSelector();
        String markerScript = properties.getBrowser().getReadinessScript();
        new WebDriverWait(driver, properties.getBrowser().getReadinessTimeout()).until(ignored -> {
            try {
                NavigationTrace.Result navigation = trace.result();
                validateNavigation(navigation);
                urlPolicy.validate(navigation.finalUrl() == null ? driver.getCurrentUrl() : navigation.finalUrl());
            } catch (ConversionException ex) {
                if ("NAVIGATION_STATUS_UNAVAILABLE".equals(ex.code())) return false;
                throw ex;
            }
            js.executeScript("""
                const key = arguments[0];
                let state = window[key];
                if (!state || state.document !== document) {
                  state = {document: document, lastMutation: Date.now()};
                  state.observer = new MutationObserver(() => state.lastMutation = Date.now());
                  state.observer.observe(document.documentElement, {subtree:true, childList:true, attributes:true, characterData:true});
                  Object.defineProperty(window, key, {value: state, configurable: false, enumerable: false});
                }
                """, stateKey);
            Object base = js.executeScript("""
                const state = window[arguments[2]];
                return Boolean(state) && document.readyState === 'complete' &&
                  (!document.fonts || document.fonts.status === 'loaded') &&
                  Array.from(document.images).every(i => i.complete) &&
                  Array.from(document.querySelectorAll('canvas')).every(c => c.width > 0 && c.height > 0) &&
                  Date.now() - state.lastMutation >= arguments[0] &&
                  (!arguments[1] || document.querySelector(arguments[1]) !== null);
                """, quietMillis, selector == null ? "" : selector.trim(), stateKey);
            if (!Boolean.TRUE.equals(base)) return false;
            return markerScript == null || markerScript.isBlank()
                || Boolean.TRUE.equals(js.executeScript("return Boolean(eval(arguments[0]));", markerScript));
        });
        Duration delay = properties.getBrowser().getSettleDelay();
        if (!delay.isZero() && !delay.isNegative()) {
            try { Thread.sleep(delay); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw timeout("Rendering was interrupted", ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateLayout(ChromeDriver driver) {
        Map<String, Object> metrics = driver.executeCdpCommand("Page.getLayoutMetrics", Map.of());
        Object raw = metrics.get("cssContentSize");
        if (!(raw instanceof Map<?, ?>)) raw = metrics.get("contentSize");
        if (!(raw instanceof Map<?, ?> size)) throw new IllegalStateException("Chrome returned no page dimensions");
        int width = dimension(size.get("width"));
        int height = dimension(size.get("height"));
        ConverterProperties.Limits limits = properties.getLimits();
        if (width > limits.getMaxCaptureWidth() || height > limits.getMaxCaptureHeight()
            || width > limits.getMaxCapturePixels() / Math.max(1, height)) {
            throw tooLarge("Page capture dimensions exceed the configured limit");
        }
    }

    private static int dimension(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() <= 0
            || number.doubleValue() > Integer.MAX_VALUE) throw new IllegalStateException("Chrome returned invalid page dimensions");
        return (int) Math.ceil(number.doubleValue());
    }

    private byte[] captureMhtml(ChromeDriver driver) {
        Object data = driver.executeCdpCommand("Page.captureSnapshot", Map.of("format", "mhtml")).get("data");
        if (!(data instanceof String text)) throw new IllegalStateException("Chrome returned no MHTML data");
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(properties.getLimits().getMaxOutputBytes());
        for (int offset = 0; offset < text.length(); offset += 8192) {
            byte[] chunk = text.substring(offset, Math.min(text.length(), offset + 8192)).getBytes(StandardCharsets.UTF_8);
            output.write(chunk, 0, chunk.length);
        }
        return output.toByteArray();
    }

    private byte[] capturePdf(ChromeDriver driver) {
        var pdf = properties.getPdf();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("printBackground", true); parameters.put("preferCSSPageSize", false);
        parameters.put("landscape", pdf.isLandscape()); parameters.put("paperWidth", pdf.getPaperWidthInches());
        parameters.put("paperHeight", pdf.getPaperHeightInches()); parameters.put("marginTop", pdf.getMarginInches());
        parameters.put("marginBottom", pdf.getMarginInches()); parameters.put("marginLeft", pdf.getMarginInches()); parameters.put("marginRight", pdf.getMarginInches());
        parameters.put("transferMode", "ReturnAsStream");
        Object streamValue = driver.executeCdpCommand("Page.printToPDF", parameters).get("stream");
        if (!(streamValue instanceof String stream)) throw new IllegalStateException("Chrome returned no PDF stream");
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(properties.getLimits().getMaxOutputBytes());
        try {
            boolean eof = false;
            while (!eof) {
                Map<String, Object> read = driver.executeCdpCommand("IO.read", Map.of("handle", stream, "size", 65_536));
                Object data = read.get("data");
                if (!(data instanceof String encoded)) throw new IllegalStateException("Chrome returned invalid PDF stream data");
                byte[] chunk = Boolean.TRUE.equals(read.get("base64Encoded"))
                    ? decodeBase64(encoded, properties.getLimits().getMaxOutputBytes() - output.size())
                    : encoded.getBytes(StandardCharsets.ISO_8859_1);
                output.write(chunk, 0, chunk.length);
                eof = Boolean.TRUE.equals(read.get("eof"));
            }
            return output.toByteArray();
        } finally {
            driver.executeCdpCommand("IO.close", Map.of("handle", stream));
        }
    }

    PreparedPptx preparePptx(ChromeDriver driver) {
        var ppt = properties.getPptx();
        if (!ppt.isSmartPaginationEnabled()) return null;
        try {
            PageModel model = extractPageModel(driver);
            return new PreparedPptx(model, planPageModel(model));
        } catch (RuntimeException ex) {
            if (ex instanceof ConversionException conversion
                && "PPTX_MAX_SLIDES_EXCEEDED".equals(conversion.code())) throw conversion;
            if (!ppt.isLegacyFallbackEnabled()) {
                throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGINATION_FAILED",
                    "Smart pagination failed", ex);
            }
            return null;
        }
    }

    /** Retained package-level helper for pagination-only tests and visual compatibility callers. */
    PaginationPlan preparePagination(ChromeDriver driver) {
        PreparedPptx prepared = preparePptx(driver);
        return prepared == null ? null : prepared.plan();
    }

    PageModel extractPageModel(ChromeDriver driver) {
        return new DomPageExtractor(properties.getPageModel(), properties.getPptx()).extract(driver, URI.create(driver.getCurrentUrl()));
    }

    PaginationPlan planPageModel(PageModel model) {
        var ppt = properties.getPptx();
        return new SmartPaginationPlanner().plan(model, new PaginationOptions(
            ppt.getSlideWidthInches() * 72, ppt.getSlideHeightInches() * 72,
            ppt.getMinSliceHeightPixels(), ppt.getMaxSlides()));
    }

    private byte[] capturePptx(ChromeDriver driver, PreparedPptx prepared) {
        Object data = driver.executeCdpCommand("Page.captureScreenshot", Map.of(
            "format", "png", "captureBeyondViewport", true, "fromSurface", true)).get("data");
        if (!(data instanceof String encoded)) throw new IllegalStateException("Chrome returned no screenshot data");
        byte[] screenshot = decodeBase64(encoded, properties.getLimits().getMaxScreenshotBytes());
        validatePngHeader(screenshot);
        if (prepared == null) return createPresentation(screenshot, null);
        if (!properties.getPptx().isEditableEnabled()) return createPresentation(screenshot, prepared.plan());
        return createEditablePresentation(screenshot, prepared.model(), prepared.plan());
    }

    byte[] createEditablePresentation(byte[] screenshot, PageModel model, PaginationPlan plan) {
        validatePngHeader(screenshot);
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(screenshot));
            if (source == null) throw new IOException("Unsupported screenshot");
            return new HybridPptxRenderer(properties).render(source, model, plan);
        } catch (ConversionException ex) { throw ex; }
        catch (IOException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_CREATION_FAILED",
                "Editable presentation could not be created", ex);
        }
    }

    static byte[] decodeBase64(String encoded, long maximum) {
        long upperBound = ((long) encoded.length() + 3L) / 4L * 3L;
        if (upperBound > maximum + 2L) throw tooLarge("Decoded browser artifact exceeds the configured limit");
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OUTPUT", "Browser returned invalid base64 data", ex); }
        if (decoded.length > maximum) throw tooLarge("Decoded browser artifact exceeds the configured limit");
        return decoded;
    }

    private void validatePngHeader(byte[] screenshot) {
        if (screenshot.length < 24 || !startsWith(screenshot, PNG_SIGNATURE)
            || screenshot[12] != 'I' || screenshot[13] != 'H' || screenshot[14] != 'D' || screenshot[15] != 'R') {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SCREENSHOT", "Browser returned an invalid PNG screenshot");
        }
        long width = unsignedInt(screenshot, 16);
        long height = unsignedInt(screenshot, 20);
        ConverterProperties.Limits limits = properties.getLimits();
        if (width == 0 || height == 0 || width > limits.getMaxCaptureWidth() || height > limits.getMaxCaptureHeight()
            || width > limits.getMaxCapturePixels() / height) {
            throw tooLarge("Screenshot dimensions exceed the configured limit");
        }
    }

    private static long unsignedInt(byte[] value, int offset) {
        return ((long) (value[offset] & 255) << 24) | ((long) (value[offset + 1] & 255) << 16)
            | ((long) (value[offset + 2] & 255) << 8) | (value[offset + 3] & 255L);
    }

    byte[] createPresentation(byte[] screenshot) {
        return createPresentation(screenshot, null);
    }

    byte[] createPresentation(byte[] screenshot, PaginationPlan plan) {
        validatePngHeader(screenshot);
        try {
            long imageWidth = unsignedInt(screenshot, 16), imageHeight = unsignedInt(screenshot, 20);
            List<PixelSlice> pixelSlices = plan == null ? legacySlices(imageWidth, imageHeight) : plannedSlices(plan, imageWidth, imageHeight);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(screenshot));
            if (source == null) throw new IOException("Unsupported screenshot");
            var ppt = properties.getPptx();
            try (XMLSlideShow show = new XMLSlideShow(); BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(properties.getLimits().getMaxOutputBytes())) {
                int slideWidth = (int) Math.round(ppt.getSlideWidthInches() * 72);
                int slideHeight = (int) Math.round(ppt.getSlideHeightInches() * 72);
                show.setPageSize(new Dimension(slideWidth, slideHeight));
                for (PixelSlice pixelSlice : pixelSlices) {
                    int y = pixelSlice.y(), height = pixelSlice.height();
                    BufferedImage crop = source.getSubimage(0, y, source.getWidth(), height);
                    BoundedByteArrayOutputStream png = new BoundedByteArrayOutputStream(properties.getLimits().getMaxScreenshotBytes());
                    if (!ImageIO.write(crop, "png", png)) throw new IOException("PNG encoder is unavailable");
                    XSLFPictureData picture = show.addPicture(png.toByteArray(), PictureData.PictureType.PNG);
                    XSLFSlide slide = show.createSlide();
                    XSLFPictureShape shape = slide.createPicture(picture);
                    double renderedHeight = (double) height / source.getWidth() * slideWidth;
                    shape.setAnchor(new java.awt.geom.Rectangle2D.Double(0, 0, slideWidth, renderedHeight));
                }
                show.write(output);
                return output.toByteArray();
            }
        } catch (ConversionException ex) { throw ex; }
        catch (IOException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_CREATION_FAILED", "Presentation could not be created", ex);
        }
    }

    private List<PixelSlice> legacySlices(long imageWidth, long imageHeight) {
        validateImageDimensions(imageWidth, imageHeight);
        double aspect = properties.getPptx().getSlideWidthInches() / properties.getPptx().getSlideHeightInches();
        long cropHeight = Math.max(1, Math.round(imageWidth / aspect));
        long count = (imageHeight + cropHeight - 1) / cropHeight;
        if (count > properties.getPptx().getMaxSlides()) throw maxSlides();
        java.util.ArrayList<PixelSlice> result = new java.util.ArrayList<>((int) count);
        for (long y = 0; y < imageHeight; y += cropHeight) result.add(new PixelSlice((int) y, (int) Math.min(cropHeight, imageHeight - y)));
        return List.copyOf(result);
    }

    private List<PixelSlice> plannedSlices(PaginationPlan plan, long imageWidth, long imageHeight) {
        validateImageDimensions(imageWidth, imageHeight);
        double horizontalScale = imageWidth / plan.sourceWidth(), verticalScale = imageHeight / plan.sourceHeight();
        double scaleTolerance = Math.max(horizontalScale, verticalScale) * 0.01
            + 1.0 / Math.max(plan.sourceWidth(), plan.sourceHeight());
        if (plan.slices().size() > properties.getPptx().getMaxSlides()) throw maxSlides();
        if (plan.slices().isEmpty()
            || !Double.isFinite(plan.sourceWidth()) || !Double.isFinite(plan.sourceHeight())
            || plan.sourceWidth() <= 0 || plan.sourceHeight() <= 0
            || !Double.isFinite(horizontalScale) || !Double.isFinite(verticalScale)
            || Math.abs(horizontalScale - verticalScale) > scaleTolerance) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGINATION_INVALID", "Pagination plan is invalid");
        }
        java.util.ArrayList<PixelSlice> result = new java.util.ArrayList<>(plan.slices().size());
        int cursor = 0;
        for (int i = 0; i < plan.slices().size(); i++) {
            SlideSlice slice = plan.slices().get(i);
            int end = i == plan.slices().size() - 1 ? (int) imageHeight
                : (int) Math.round(slice.source().bottom() / plan.sourceHeight() * imageHeight);
            if (slice.index() != i || Math.abs(slice.source().y() - (i == 0 ? 0 : plan.slices().get(i - 1).source().bottom())) > 0.000_001
                || slice.source().height() <= 0 || end <= cursor || end > imageHeight) {
                throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGINATION_INVALID", "Pagination plan cannot be mapped to screenshot pixels");
            }
            result.add(new PixelSlice(cursor, end - cursor)); cursor = end;
        }
        if (cursor != imageHeight) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGINATION_INVALID", "Pagination screenshot coverage is incomplete");
        }
        return List.copyOf(result);
    }

    private static void validateImageDimensions(long imageWidth, long imageHeight) {
        if (imageWidth < 1 || imageHeight < 1 || imageWidth > Integer.MAX_VALUE || imageHeight > Integer.MAX_VALUE)
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGINATION_INVALID", "Screenshot dimensions cannot be mapped safely");
    }

    private static ConversionException maxSlides() {
        return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "PPTX_MAX_SLIDES_EXCEEDED", "Page requires more slides than configured");
    }

    record PreparedPptx(PageModel model, PaginationPlan plan) {
        PreparedPptx {
            java.util.Objects.requireNonNull(model, "model");
            java.util.Objects.requireNonNull(plan, "plan");
        }
    }

    private record PixelSlice(int y, int height) { }

    static void validateArtifact(byte[] bytes, OutputFormat format) {
        if (bytes == null || bytes.length == 0) throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "CONVERSION_FAILED", "Browser produced an empty document");
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

    private static ConversionException tooLarge(String message) {
        return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "OUTPUT_TOO_LARGE", message);
    }

    private static ConversionException timeout(String message, Throwable cause) {
        return new ConversionException(HttpStatus.GATEWAY_TIMEOUT, "CONVERSION_TIMEOUT", message, cause);
    }
}
