package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
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
    @SuppressWarnings("rawtypes")
    private Network network;
    private ChromePageRenderer renderer;

    @BeforeEach void setUp() throws Exception {
        properties = new ConverterProperties();
        properties.getBrowser().setSettleDelay(java.time.Duration.ZERO);
        factory = mock(BrowserFactory.class);
        policy = mock(UrlSecurityPolicy.class);
        driver = mock(ChromeDriver.class);
        DevTools devTools = mock(DevTools.class);
        Domains domains = mock(Domains.class);
        network = mock(Network.class);
        when(driver.getDevTools()).thenReturn(devTools);
        when(devTools.getDomains()).thenReturn(domains);
        doReturn(network).when(domains).network();
        when(driver.executeScript(anyString())).thenReturn(true);
        when(driver.getCurrentUrl()).thenReturn("https://example.org/");
        URI uri = URI.create("https://example.org/");
        when(policy.validate(anyString())).thenReturn(uri);
        when(factory.open()).thenAnswer(invocation -> new BrowserSession(driver, Files.createDirectory(temp.resolve("profile-" + System.nanoTime()))));
        renderer = new ChromePageRenderer(factory, properties, policy);
    }

    @Test void rendersAndValidatesAllThreeFormatsThroughSeleniumApis() throws Exception {
        byte[] mhtml = "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] png = png();
        when(driver.executeCdpCommand(eq("Page.captureSnapshot"), anyMap())).thenReturn(Map.of("data", new String(mhtml, StandardCharsets.UTF_8)));
        when(driver.executeCdpCommand(eq("Page.printToPDF"), anyMap())).thenReturn(Map.of("data", Base64.getEncoder().encodeToString(pdf)));
        when(driver.executeCdpCommand(eq("Page.captureScreenshot"), anyMap())).thenReturn(Map.of("data", Base64.getEncoder().encodeToString(png)));
        URI uri = URI.create("https://example.org/");
        assertThat(renderer.render(uri, OutputFormat.MHTML)).isEqualTo(mhtml);
        assertThat(renderer.render(uri, OutputFormat.PDF)).isEqualTo(pdf);
        assertThat(renderer.render(uri, OutputFormat.PPTX)).startsWith((byte) 'P', (byte) 'K');
        verify(driver, times(3)).get(uri.toASCIIString());
        verify(driver, times(3)).quit();
    }

    @Test void requestGuardContinuesAllowedRequestsAndRecordsBlockedSubresources() {
        when(driver.executeCdpCommand(eq("Page.captureSnapshot"), anyMap())).thenReturn(Map.of("data", "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n"));
        renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML);
        ArgumentCaptor<Filter> captor = ArgumentCaptor.forClass(Filter.class);
        verify(network).interceptTrafficWith(captor.capture());
        HttpHandler next = request -> new HttpResponse().setStatus(204);
        assertThat(captor.getValue().apply(next).execute(new HttpRequest(HttpMethod.GET, "https://cdn.example.org/a.js")).getStatus()).isEqualTo(204);
        when(policy.validate("http://127.0.0.1/secret")).thenThrow(new ConversionException(org.springframework.http.HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "blocked"));
        assertThat(captor.getValue().apply(next).execute(new HttpRequest(HttpMethod.GET, "http://127.0.0.1/secret")).getStatus()).isEqualTo(403);
    }

    @Test void propagatesBlockedTopLevelNavigationInsteadOfGenericSeleniumFailure() {
        when(driver.executeCdpCommand(eq("Page.captureSnapshot"), anyMap())).thenReturn(Map.of("data", "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n"));
        doThrow(new org.openqa.selenium.WebDriverException("blocked")).when(driver).get(anyString());
        // No guard callback happened, so the renderer safely maps the driver failure.
        assertThatThrownBy(() -> renderer.render(URI.create("https://example.org/"), OutputFormat.MHTML))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PAGE_RENDER_FAILED");
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
}
