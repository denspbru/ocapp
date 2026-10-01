package com.nicodim.ocapp.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.nicodim.ocapp.browser.BrowserSession;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.security.NavigationResolver;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ConversionServiceTest {
    private ExecutorService executor;
    private UrlSecurityPolicy policy;
    private ConverterProperties.Limits limits;
    private URI uri;

    @BeforeEach void setUp() {
        executor = Executors.newSingleThreadExecutor();
        policy = mock(UrlSecurityPolicy.class);
        limits = new ConverterProperties.Limits();
        uri = URI.create("https://Example.org/report?secret=x");
        when(policy.validate(uri.toString())).thenReturn(uri);
        when(policy.safeOrigin(uri)).thenReturn("https://example.org");
    }

    @AfterEach void tearDown() { MDC.clear(); executor.shutdownNow(); }

    @Test void springConstructorBuildsService() {
        ConverterProperties properties = new ConverterProperties();
        assertThat(new ConversionService(policy, value -> value, (value, format) -> new byte[]{1}, properties, executor)).isNotNull();
    }

    @Test void convertsAndBuildsSafeDeterministicFilename() {
        ConversionService service = service(value -> value, (value, format) -> new byte[]{1, 2, 3});
        ConversionResult result = service.convert(uri.toString(), OutputFormat.PDF);
        assertThat(result.bytes()).containsExactly(1, 2, 3);
        assertThat(result.filename()).isEqualTo("example.org-20260928T204000Z.pdf");
    }

    @Test void operationTimeoutIncludesInitialDnsValidation() {
        limits.setOperationTimeout(Duration.ofMillis(30));
        when(policy.validate(uri.toString())).thenAnswer(invocation -> {
            try { Thread.sleep(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return uri;
        });
        ConversionService service = service(value -> value, (value, format) -> new byte[]{1});
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "CONVERSION_TIMEOUT");
    }

    @Test void operationTimeoutIncludesRedirectResolution() {
        limits.setOperationTimeout(Duration.ofMillis(30));
        ConversionService service = service(value -> {
            try { Thread.sleep(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return value;
        }, (value, format) -> new byte[]{1});
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "CONVERSION_TIMEOUT");
    }

    @Test void timedOutRunningTaskKeepsPermitUntilItActuallyStops() throws Exception {
        limits.setMaxConcurrent(1); limits.setOperationTimeout(Duration.ofMillis(30)); limits.setAcquireTimeout(Duration.ofMillis(20));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ConversionService service = service(value -> value, (value, format) -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(10, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
            return new byte[]{1};
        });
        Thread first = Thread.ofPlatform().start(() -> assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "CONVERSION_TIMEOUT"));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        first.join(1000);
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "CAPACITY_EXCEEDED");
        release.countDown();
        awaitPermitRelease(service);
    }

    @Test void cancelledBeforeStartNeverEntersBrowserWorkAndReleasesPermit() throws Exception {
        limits.setMaxConcurrent(1); limits.setOperationTimeout(Duration.ofMillis(30));
        CountDownLatch occupying = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.submit(() -> { occupying.countDown(); release.await(); return null; });
        assertThat(occupying.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicInteger renders = new AtomicInteger();
        ConversionService service = service(value -> value, (value, format) -> { renders.incrementAndGet(); return new byte[]{1}; });
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "CONVERSION_TIMEOUT");
        release.countDown();
        Thread.sleep(50);
        assertThat(renders).hasValue(0);
        assertThat(service.convert(uri.toString(), OutputFormat.PDF).bytes()).containsExactly(1);
    }

    @Test void timeoutInvokesRegisteredBrowserForceCloseBeforePermitRelease() throws Exception {
        limits.setMaxConcurrent(1); limits.setOperationTimeout(Duration.ofMillis(30)); limits.setAcquireTimeout(Duration.ofMillis(100));
        BrowserSession session = mock(BrowserSession.class);
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        doAnswer(invocation -> { released.countDown(); return null; }).when(session).forceClose();
        PageRenderer renderer = new PageRenderer() {
            public byte[] render(URI value, OutputFormat format) { return new byte[]{1}; }
            public byte[] render(URI value, OutputFormat format, RenderContext context) {
                context.register(session); entered.countDown();
                try { released.await(); } catch (InterruptedException ignored) { }
                context.unregister(session); return new byte[]{1};
            }
        };
        ConversionService service = service(value -> value, renderer);
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "CONVERSION_TIMEOUT");
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        verify(session).forceClose();
        assertThat(service.convert(uri.toString(), OutputFormat.PDF).bytes()).containsExactly(1);
    }

    @Test void timeoutReportsStableCleanupFailureWhenBrowserTreeSurvives() {
        limits.setOperationTimeout(Duration.ofMillis(30));
        BrowserSession session = mock(BrowserSession.class);
        when(session.forceClose()).thenReturn(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        PageRenderer renderer = new PageRenderer() {
            public byte[] render(URI value, OutputFormat format) { return new byte[]{1}; }
            public byte[] render(URI value, OutputFormat format, RenderContext context) {
                context.register(session);
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                return new byte[]{1};
            }
        };
        ConversionService service = service(value -> value, renderer);
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "BROWSER_CLEANUP_FAILED");
    }

    @Test void propagatesCorrelationMdcToWorkerAndRestoresCaller() {
        AtomicReference<String> observed = new AtomicReference<>();
        MDC.put("correlationId", "cid-1");
        ConversionService service = service(value -> value, (value, format) -> {
            observed.set(MDC.get("correlationId")); return new byte[]{1};
        });
        service.convert(uri.toString(), OutputFormat.MHTML);
        assertThat(observed).hasValue("cid-1");
        assertThat(MDC.get("correlationId")).isEqualTo("cid-1");
    }

    @Test void unwrapsConversionFailureAndWrapsUnexpectedFailure() {
        ConversionService expected = service(value -> value, (value, format) -> { throw new ConversionException(org.springframework.http.HttpStatus.FORBIDDEN, "BLOCKED", "blocked"); });
        assertCode(() -> expected.convert(uri.toString(), OutputFormat.PDF), "BLOCKED");
        ConversionService unexpected = service(value -> value, (value, format) -> { throw new AssertionError("boom"); });
        assertCode(() -> unexpected.convert(uri.toString(), OutputFormat.PDF), "INTERNAL_ERROR");
    }

    @Test void rejectedExecutorReleasesPermitAndReturnsStableError() {
        executor.shutdownNow();
        ConversionService service = service(value -> value, (value, format) -> new byte[]{1});
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "SERVICE_UNAVAILABLE");
        assertCode(() -> service.convert(uri.toString(), OutputFormat.PDF), "SERVICE_UNAVAILABLE");
    }

    @Test void sanitizesAndBoundsFilename() {
        String longHost = "a".repeat(120) + ".example";
        String name = ConversionService.filename(URI.create("https://" + longHost), OutputFormat.MHTML, Instant.EPOCH);
        assertThat(name).hasSizeLessThanOrEqualTo(123).matches("[A-Za-z0-9.-]+");
        assertThat(ConversionService.filename(URI.create("https://xn--e1afmkfd.xn--p1ai"), OutputFormat.MHTML, Instant.EPOCH)).endsWith(".mhtml");
    }

    private ConversionService service(NavigationResolver resolver, PageRenderer renderer) {
        return new ConversionService(policy, resolver, renderer, limits, executor,
            Clock.fixed(Instant.parse("2026-09-28T20:40:00Z"), ZoneOffset.UTC));
    }

    private void awaitPermitRelease(ConversionService service) throws Exception {
        for (int i = 0; i < 50; i++) {
            try {
                ConversionResult result = service.convert(uri.toString(), OutputFormat.PDF);
                assertThat(result.bytes()).containsExactly(1); return;
            } catch (ConversionException ex) {
                if (!ex.code().equals("CAPACITY_EXCEEDED")) throw ex;
                Thread.sleep(10);
            }
        }
        throw new AssertionError("permit was not released");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(ConversionException.class).extracting("code").isEqualTo(code);
    }
}
