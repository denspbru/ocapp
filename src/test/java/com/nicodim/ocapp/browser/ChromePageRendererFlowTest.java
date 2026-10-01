package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.conversion.RenderContext;
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

    @Test void mapsSeleniumTimeoutAndOtherFailuresToStableErrors() {
        doThrow(new org.openqa.selenium.TimeoutException("slow")).when(driver).get(anyString());
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "CONVERSION_TIMEOUT");
        reset(driver);
        when(driver.getDevTools()).thenReturn(devTools);
        doThrow(new org.openqa.selenium.WebDriverException("blocked")).when(driver).get(anyString());
        assertCode(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML), "PAGE_RENDER_FAILED");
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
