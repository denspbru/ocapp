package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.conversion.RenderContext;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonInput;
import org.openqa.selenium.devtools.idealized.Domains;
import org.openqa.selenium.devtools.idealized.Network;
import org.openqa.selenium.remote.http.Filter;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

class ChromePageRendererFlowTest {
    @TempDir Path temp;
    private BrowserFactory factory;
    private ConverterProperties properties;
    private UrlSecurityPolicy policy;
    private ChromeDriver driver;
    private NavigationTrace navigationTrace;
    private NavigationTrace.Recorder traceRecorder;
    private DevTools devTools;
    private ChromePageRenderer renderer;

    @BeforeEach void setUp() throws Exception {
        properties = new ConverterProperties();
        properties.getBrowser().setSettleDelay(Duration.ZERO);
        properties.getBrowser().setDomQuietPeriod(Duration.ZERO);
        factory = mock(BrowserFactory.class);
        policy = mock(UrlSecurityPolicy.class);
        driver = mock(ChromeDriver.class);
        navigationTrace = mock(NavigationTrace.class);
        traceRecorder = mock(NavigationTrace.Recorder.class);
        devTools = mock(DevTools.class);
        when(driver.getDevTools()).thenReturn(devTools);
        when(driver.executeScript(anyString(), any(Object[].class))).thenReturn(true);
        when(driver.executeScript(anyString())).thenReturn(true);
        when(driver.getCurrentUrl()).thenReturn("https://example.org/");
        when(driver.executeCdpCommand(eq("Page.getLayoutMetrics"), anyMap()))
            .thenReturn(Map.of("cssContentSize", Map.of("width", 100.0, "height", 200.0)));
        URI uri = URI.create("https://example.org/");
        when(policy.validate(anyString())).thenReturn(uri);
        when(navigationTrace.observe(devTools)).thenReturn(traceRecorder);
        when(traceRecorder.result()).thenReturn(new NavigationTrace.Result(200, 0, uri.toString()));
        when(factory.open(any(RenderContext.class))).thenAnswer(invocation -> {
            RenderContext context = invocation.getArgument(0);
            BrowserSession session = new BrowserSession(driver, Files.createDirectory(temp.resolve("profile-" + System.nanoTime())));
            if (!context.register(session)) throw new ConversionException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                "CONVERSION_TIMEOUT", "cancelled");
            return session;
        });
        renderer = new ChromePageRenderer(factory, properties, policy, navigationTrace);
    }

    @Test void rendersAndValidatesAllThreeFormatsThroughSeleniumApis() throws Exception {
        byte[] mhtml = "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] png = png();
        when(driver.executeCdpCommand(eq("Page.captureSnapshot"), anyMap())).thenReturn(Map.of("data", new String(mhtml, StandardCharsets.UTF_8)));
        when(driver.executeCdpCommand(eq("Page.printToPDF"), anyMap())).thenReturn(Map.of("stream", "pdf-stream"));
        when(driver.executeCdpCommand(eq("IO.read"), anyMap())).thenReturn(Map.of("data", Base64.getEncoder().encodeToString(pdf), "base64Encoded", true, "eof", true));
        when(driver.executeCdpCommand(eq("IO.close"), anyMap())).thenReturn(Map.of());
        when(driver.executeCdpCommand(eq("Page.captureScreenshot"), anyMap())).thenReturn(Map.of("data", Base64.getEncoder().encodeToString(png)));
        URI uri = URI.create("https://example.org/");
        assertThat(renderer.render(uri, OutputFormat.MHTML)).isEqualTo(mhtml);
        assertThat(renderer.render(uri, OutputFormat.PDF)).isEqualTo(pdf);
        assertThat(renderer.render(uri, OutputFormat.PPTX)).startsWith((byte) 'P', (byte) 'K');
        verify(driver, times(3)).get(uri.toASCIIString());
        verify(driver, times(3)).quit();
        verify(driver).executeCdpCommand(eq("IO.close"), eq(Map.of("handle", "pdf-stream")));
    }

    @Test void requestGuardValidatesHttpAndWebSocketButAllowsBrowserInternalSchemes() {
        renderer.validateBrowserRequest("https://cdn.example.org/a.js");
        renderer.validateBrowserRequest("data:image/png;base64,AA==");
        renderer.validateBrowserRequest("wss://socket.example.org/events");
        verify(policy).validate("https://cdn.example.org/a.js");
        verify(policy).validate("https://socket.example.org/events");
        when(policy.validate("http://127.0.0.1/secret")).thenThrow(new ConversionException(org.springframework.http.HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "blocked"));
        assertCode(() -> renderer.validateBrowserRequest("http://127.0.0.1/secret"), "URL_NOT_ALLOWED");
        assertCode(() -> renderer.validateBrowserRequest("http://bad host/"), "URL_NOT_ALLOWED");
        assertCode(() -> renderer.validateBrowserRequest("file:///etc/passwd"), "URL_NOT_ALLOWED");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test void rawFetchListenerContinuesAllowedAndFulfillsBlockedRequests() throws Exception {
        when(driver.executeCdpCommand(eq("Page.captureSnapshot"), anyMap())).thenReturn(Map.of("data", "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n"));
        renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML);
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        ArgumentCaptor<Consumer> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(devTools).addListener(eventCaptor.capture(), consumerCaptor.capture());
        assertThat(invokeMapper(eventCaptor.getValue(), "{\"requestId\":\"mapped\",\"request\":{\"url\":\"https://example.org/x\"}}"))
            .containsEntry("requestId", "mapped");
        consumerCaptor.getValue().accept(Map.of("requestId", "one", "request", Map.of("url", "https://example.org/x")));
        when(policy.validate("http://127.0.0.1/secret")).thenThrow(new ConversionException(org.springframework.http.HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "blocked"));
        consumerCaptor.getValue().accept(Map.of("requestId", "two", "request", Map.of("url", "http://127.0.0.1/secret")));
        verify(devTools, atLeast(2)).send(any());
    }

    @Test void rejectsBrowserGetErrorsAndRealRedirectOverflow() {
        when(traceRecorder.result()).thenReturn(new NavigationTrace.Result(404, 0, "https://example.org/missing"));
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "TARGET_HTTP_ERROR");
        when(traceRecorder.result()).thenReturn(new NavigationTrace.Result(200, properties.getSecurity().getMaxRedirects() + 1, "https://example.org/final"));
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "TOO_MANY_REDIRECTS");
    }

    @Test void rejectsLateTopLevelNavigationBeforeAndAfterCapture() {
        URI uri = URI.create("https://example.org/");
        when(traceRecorder.result()).thenReturn(new NavigationTrace.Result(200, 0, uri.toString()),
            new NavigationTrace.Result(500, 0, "https://example.org/late"));
        assertCode(() -> renderer.render(uri, OutputFormat.MHTML), "TARGET_HTTP_ERROR");

        when(traceRecorder.result()).thenReturn(new NavigationTrace.Result(200, 0, uri.toString()),
            new NavigationTrace.Result(200, 0, uri.toString()),
            new NavigationTrace.Result(200, 0, uri.toString()),
            new NavigationTrace.Result(500, 0, "https://example.org/late"));
        when(driver.executeCdpCommand(eq("Page.captureSnapshot"), anyMap())).thenReturn(Map.of("data",
            "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n"));
        assertCode(() -> renderer.render(uri, OutputFormat.MHTML), "TARGET_HTTP_ERROR");
    }

    @Test void rejectsLayoutBeforeCaptureAndOversizedBase64BeforeDecode() {
        when(driver.executeCdpCommand(eq("Page.getLayoutMetrics"), anyMap()))
            .thenReturn(Map.of("cssContentSize", Map.of("width", 100_000.0, "height", 100_000.0)));
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "OUTPUT_TOO_LARGE");
        assertCode(() -> ChromePageRenderer.decodeBase64("AAAA".repeat(100), 8), "OUTPUT_TOO_LARGE");
    }

    @Test void cancelledContextCannotEnterBrowserWork() {
        RenderContext context = new RenderContext();
        context.cancel();
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML, context), "CONVERSION_TIMEOUT");
        verify(driver, never()).get(anyString());
        verify(driver).quit();
    }

    @Test void preparePaginationHonorsSwitchFallbackAndStableErrors() {
        ControlledRenderer controlled = new ControlledRenderer(factory, properties, policy, navigationTrace);
        properties.getPptx().setSmartPaginationEnabled(false);
        assertThat(controlled.preparePagination(driver)).isNull();
        assertThat(controlled.extractions).isZero();

        properties.getPptx().setSmartPaginationEnabled(true);
        assertThat(controlled.preparePagination(driver)).isNotNull();
        controlled.failure = new IllegalStateException("page-private-value");
        assertThat(controlled.preparePagination(driver)).isNull();
        assertThat(controlled.extractions).isEqualTo(2);

        properties.getPptx().setLegacyFallbackEnabled(false);
        assertCode(() -> controlled.preparePagination(driver), "PAGINATION_FAILED");
        controlled.failure = new ConversionException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
            "PAGEMODEL_INVALID", "private model detail");
        assertCode(() -> controlled.preparePagination(driver), "PAGINATION_FAILED");

        controlled.failure = new ConversionException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
            "PPTX_MAX_SLIDES_EXCEEDED", "too many");
        assertCode(() -> controlled.preparePagination(driver), "PPTX_MAX_SLIDES_EXCEEDED");
    }

    @Test void extractsAndPlansValidatedPageModelThroughProductionHelpers() {
        when(driver.executeScript(anyString(), any(Object[].class))).thenReturn(rawPageModel());
        PageModel model = renderer.extractPageModel(driver);
        assertThat(model.blocks()).hasSize(1);
        properties.getPptx().setMinSliceHeightPixels(20);
        PaginationPlan plan = renderer.planPageModel(model);
        assertThat(plan.slices()).isNotEmpty();
        assertThat(plan.sourceWidth()).isEqualTo(100);
        assertThat(plan.sourceHeight()).isEqualTo(200);
    }

    @Test void maxSlideFailureOccursBeforeScreenshotCaptureOrPoiCreation() {
        ChromePageRenderer rejecting = spy(renderer);
        doThrow(new ConversionException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
            "PPTX_MAX_SLIDES_EXCEEDED", "too many")).when(rejecting).preparePagination(driver);
        assertCode(() -> rejecting.render(URI.create("https://example.org/"), OutputFormat.PPTX), "PPTX_MAX_SLIDES_EXCEEDED");
        verify(driver, never()).executeCdpCommand(eq("Page.captureScreenshot"), anyMap());
    }

    @Test void mapsSeleniumTimeoutAndOtherFailuresToStableErrors() {
        doThrow(new org.openqa.selenium.TimeoutException("slow")).when(driver).get(anyString());
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "CONVERSION_TIMEOUT");
        reset(driver);
        when(driver.getDevTools()).thenReturn(devTools);
        doThrow(new org.openqa.selenium.WebDriverException("blocked")).when(driver).get(anyString());
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "PAGE_RENDER_FAILED");
    }

    private static final class ControlledRenderer extends ChromePageRenderer {
        private RuntimeException failure;
        private int extractions;
        ControlledRenderer(BrowserFactory factory, ConverterProperties properties, UrlSecurityPolicy policy,
                           NavigationTrace navigationTrace) {
            super(factory, properties, policy, navigationTrace);
        }
        @Override PageModel extractPageModel(ChromeDriver driver) {
            extractions++;
            if (failure != null) throw failure;
            return mock(PageModel.class);
        }
        @Override PaginationPlan planPageModel(PageModel model) {
            if (failure != null) throw failure;
            return mock(PaginationPlan.class);
        }
    }

    private static Map<String, Object> rawPageModel() {
        Map<String,Object> block = new LinkedHashMap<>();
        block.put("id","body"); block.put("type","TEXT"); block.put("parentId",""); block.put("children",List.of());
        block.put("depth",0); block.put("domOrder",0); block.put("visualOrder",0);
        block.put("bounds",Map.of("x",0d,"y",0d,"width",100d,"height",200d)); block.put("clip",null);
        block.put("transform",Map.of("transformed",false,"matrix","none","rotation",0d,"scaleX",1d,"scaleY",1d));
        block.put("overlaps",List.of());
        block.put("style",Map.ofEntries(Map.entry("display","block"),Map.entry("position","static"),
            Map.entry("overflowX","visible"),Map.entry("overflowY","visible"),Map.entry("color","black"),
            Map.entry("backgroundColor","transparent"),Map.entry("fontFamily","sans"),Map.entry("fontSize",16d),
            Map.entry("fontWeight",400),Map.entry("fontStyle","normal"),Map.entry("textAlign","start"),
            Map.entry("lineHeight",19.2d),Map.entry("opacity",1d),Map.entry("zIndex",0),
            Map.entry("flex",false),Map.entry("grid",false)));
        block.put("textRuns",List.of()); block.put("links",List.of()); block.put("list",null); block.put("table",null);
        block.put("assets",List.of()); block.put("hints",Map.of("keepTogether",false,"slide","","title","","notes","","layout","","render","AUTO"));
        block.put("warnings",List.of());
        return Map.of("document",Map.of("title","","language",""),
            "capture",Map.of("userAgent","Chrome","locale","en","timezone","UTC","dpr",1d,"readiness","complete","observations",List.of()),
            "geometry",Map.of("width",100d,"height",200d,"viewport",Map.of("x",0d,"y",0d,"width",100d,"height",100d),"scrollX",0d,"scrollY",0d),
            "blocks",List.of(block),"roots",List.of("body"),"assets",List.of(),"warnings",List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeMapper(Event<?> event, String json) throws Exception {
        Method method = Event.class.getDeclaredMethod("getMapper");
        method.setAccessible(true);
        Function<JsonInput, ?> mapper = (Function<JsonInput, ?>) method.invoke(event);
        try (JsonInput input = new Json().newInput(new StringReader(json))) {
            return (Map<String, Object>) mapper.apply(input);
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(ConversionException.class).extracting("code").isEqualTo(code);
    }
}
