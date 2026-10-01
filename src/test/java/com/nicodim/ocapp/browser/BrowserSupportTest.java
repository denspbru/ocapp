package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.config.ConverterProperties.Security.EgressMode;
import com.nicodim.ocapp.conversion.RenderContext;
import com.nicodim.ocapp.support.ConversionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.springframework.boot.actuate.health.Status;

class BrowserSupportTest {
    @TempDir Path temp;

    @Test void rejectsExtraArgumentsThatCanBypassIsolationOrNetworkPolicy() {
        for (String argument : List.of("--user-data-dir=/tmp/shared", "--HOST-RESOLVER-RULES=MAP * 127.0.0.1", "--proxy-server=http://127.0.0.1", "--proxy-bypass-list=*", "--disable-quic=false", "--enable-quic", "--force-webrtc-ip-handling-policy=default", "--no-proxy-server",
                "--proxy-auto-detect", "--no-sandbox", "--disable-setuid-sandbox", "--load-extension=/tmp/x", "--remote-debugging-port=9222")) {
            assertThatThrownBy(() -> BrowserFactory.validateExtraArguments(List.of(argument))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> BrowserFactory.validateExtraArguments(List.of("--proxy-future-switch=x")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserFactory.validateExtraArguments(List.of("--host-resolver-future=x")))
            .isInstanceOf(IllegalArgumentException.class);
        BrowserFactory.validateExtraArguments(java.util.Arrays.asList(null, " ", "--lang=en-US", "--disable-gpu"));
    }

    @Test void validatesFailClosedProxyAndSeparatelyAcknowledgedUnsafeMode() {
        ConverterProperties properties = new ConverterProperties();
        BrowserFactory factory = new BrowserFactory(properties);
        assertThatThrownBy(factory::validateConfiguration).isInstanceOf(IllegalStateException.class).hasMessageContaining("PROXY");
        properties.getSecurity().setProxyUrl("http://proxy:3128");
        factory.validateConfiguration();
        assertThatThrownBy(() -> BrowserFactory.validateProxyUri("http://user:secret@proxy:3128/path"))
            .isInstanceOf(IllegalStateException.class);
        properties.getBrowser().setReadinessSelector(null);
        properties.getBrowser().setReadinessScript("return true");
        factory.validateConfiguration();
        properties.getBrowser().setReadinessSelector("body");
        properties.getBrowser().setReadinessScript(null);
        factory.validateConfiguration();
        properties.getSecurity().setEgressMode(EgressMode.UNSAFE);
        properties.getSecurity().setUnsafeAcknowledgeRisk(false);
        assertThatThrownBy(factory::validateConfiguration).isInstanceOf(IllegalStateException.class).hasMessageContaining("acknowledge");
        properties.getSecurity().setUnsafeAcknowledgeRisk(true);
        factory.validateConfiguration();
    }

    @Test void proxyOptionsDisableBypassDnsQuicAndNonProxiedWebRtc() {
        ConverterProperties properties = proxyProperties();
        List<String> args = arguments(new BrowserFactory(properties).options(temp));
        assertThat(args).contains("--proxy-server=http://proxy.example:3128", "--proxy-bypass-list=<-loopback>",
            "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE proxy.example", "--disable-quic",
            "--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
    }

    @Test void unsafeResolverRulesFollowAllowPrivateAddresses() {
        ConverterProperties properties = unsafeProperties();
        BrowserFactory factory = new BrowserFactory(properties);
        assertThat(arguments(factory.options(temp))).contains("--host-resolver-rules=MAP localhost ~NOTFOUND, MAP *.localhost ~NOTFOUND");
        properties.getSecurity().setAllowPrivateAddresses(true);
        assertThat(arguments(factory.options(temp))).noneMatch(value -> value.startsWith("--host-resolver-rules"));
    }

    @Test void configuredBrowserAndDriverMustBothBeExecutable() throws Exception {
        ConverterProperties properties = unsafeProperties();
        Path binary = Files.writeString(temp.resolve("chrome"), "x");
        Path driver = Files.writeString(temp.resolve("driver"), "x");
        properties.getBrowser().setBinary(binary.toString());
        properties.getBrowser().setDriverPath(driver.toString());
        BrowserFactory factory = new BrowserFactory(properties);
        assertThat(factory.configuredExecutablesExist()).isFalse();
        assertThat(binary.toFile().setExecutable(true)).isTrue();
        assertThat(factory.configuredExecutablesExist()).isFalse();
        assertThat(driver.toFile().setExecutable(true)).isTrue();
        assertThat(factory.configuredExecutablesExist()).isTrue();
    }

    @Test void recursivelyDeletesTemporaryProfile() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("profile/nested"));
        Files.writeString(profile.resolve("cookie"), "secret");
        BrowserSession.deleteDirectory(temp.resolve("profile"));
        assertThat(temp.resolve("profile")).doesNotExist();
        BrowserSession.deleteDirectory(null);
    }

    @Test void browserSessionCloseAndForceCloseAreIdempotentAndBounded() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("profile2"));
        ChromeDriver driver = mock(ChromeDriver.class);
        ChromeDriverService service = mock(ChromeDriverService.class);
        BrowserSession session = new BrowserSession(driver, service, profile, Duration.ofMillis(100));
        assertThat(session.driver()).isSameAs(driver);
        assertThat(session.forceClose()).isEqualTo(BrowserSession.CleanupResult.SUCCESS);
        session.close();
        verify(driver).quit();
        verify(service).stop();
        assertThat(profile).doesNotExist();
    }

    @Test void cleanupAcceptsAnAbsentProfile() {
        new BrowserSession(null, Duration.ofMillis(10)).forceClose();
    }

    @Test void externalSupervisorTerminatesOwnedProcessTree() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("owned-process-profile"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            SleepProcess.class.getName(), profile.toAbsolutePath().toString()).start();
        assertThat(process.isAlive()).isTrue();
        BrowserSession session = new BrowserSession(profile, Duration.ofMillis(200));
        assertThat(session.forceClose()).isEqualTo(BrowserSession.CleanupResult.SUCCESS);
        assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
        assertThat(profile).doesNotExist();
    }

    @Test void cleanupReportsStableFailureWhenProcessOrDescendantSurvivesBothDeadlines() {
        ProcessHandle parent = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(parent.descendants()).thenAnswer(invocation -> Stream.of(child));
        when(child.descendants()).thenAnswer(invocation -> Stream.empty());
        when(parent.isAlive()).thenReturn(true);
        when(child.isAlive()).thenReturn(true);

        BrowserSession.CleanupResult result = BrowserSession.terminateProcessTree(Set.of(parent), Duration.ofMillis(1));

        assertThat(result).isEqualTo(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        verify(parent).destroy();
        verify(parent).destroyForcibly();
        verify(child).destroy();
        verify(child).destroyForcibly();
        assertThat(result.successful()).isFalse();
    }

    @Test void sessionReturnsStableFailureAndRetainsProfileWhenTreeSurvives() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("surviving-tree-profile"));
        BrowserSession session = new BrowserSession(profile, Duration.ofMillis(1),
            service -> BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        assertThat(session.forceClose()).isEqualTo(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        assertThat(session.forceClose()).isEqualTo(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        assertThat(profile).exists();
        assertThatThrownBy(session::close).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("BROWSER_CLEANUP_FAILED");
    }

    @Test void boundedCloseReturnsWhenDriverAndServiceIgnoreShutdown() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ChromeDriver driver = mock(ChromeDriver.class);
        ChromeDriverService service = mock(ChromeDriverService.class);
        doAnswer(invocation -> { release.await(); return null; }).when(driver).quit();
        doAnswer(invocation -> { release.await(); return null; }).when(service).stop();
        BrowserSession session = new BrowserSession(driver, service, temp.resolve("hung-profile"), Duration.ofMillis(20));
        long started = System.nanoTime();
        session.forceClose();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        release.countDown();
    }

    @Test void resourcesAttachedAfterCancellationAreClosedImmediately() {
        BrowserSession session = new BrowserSession(temp.resolve("late-profile"), Duration.ofMillis(100));
        ChromeDriverService service = mock(ChromeDriverService.class);
        ChromeDriver driver = mock(ChromeDriver.class);
        session.attachService(null);
        assertThatThrownBy(() -> session.attachDriver(null)).isInstanceOf(IllegalArgumentException.class);
        session.forceClose();
        session.attachService(service);
        session.attachDriver(driver);
        verify(service).stop();
        verify(driver).quit();
        assertThatThrownBy(session::driver).isInstanceOf(IllegalStateException.class);
    }

    @Test void defaultStarterCreatesManagedDriverAndSession() {
        ConverterProperties properties = unsafeProperties();
        WebDriver.Options options = mock(WebDriver.Options.class);
        WebDriver.Timeouts timeouts = mock(WebDriver.Timeouts.class);
        when(options.timeouts()).thenReturn(timeouts);
        try (MockedConstruction<ChromeDriver> construction = mockConstruction(ChromeDriver.class,
                (driver, context) -> when(driver.manage()).thenReturn(options))) {
            try (BrowserSession session = new BrowserFactory(properties).open()) {
                assertThat(session.driver()).isSameAs(construction.constructed().getFirst());
            }
            verify(construction.constructed().getFirst()).quit();
        }
    }

    @Test void cancellationBeforeStartupCannotInvokeDriverStarter() {
        ConverterProperties properties = unsafeProperties();
        AtomicBoolean started = new AtomicBoolean();
        BrowserFactory factory = new BrowserFactory(properties, (service, options) -> {
            started.set(true);
            return mock(ChromeDriver.class);
        });
        RenderContext context = new RenderContext();
        context.cancel();
        assertThatThrownBy(() -> factory.open(context)).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("CONVERSION_TIMEOUT");
        assertThat(started).isFalse();
    }

    @Test void cancellationDuringDriverStartupClosesLateDriverAndReturnsTimeout() throws Exception {
        ConverterProperties properties = unsafeProperties();
        properties.getBrowser().setCleanupTimeout(Duration.ofMillis(50));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ChromeDriver driver = mock(ChromeDriver.class);
        BrowserFactory factory = new BrowserFactory(properties, (service, options) -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(10, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { }
            }
            return driver;
        });
        RenderContext context = new RenderContext();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { factory.open(context); }
            catch (Throwable ex) { failure.set(ex); }
        });
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        context.cancel();
        release.countDown();
        worker.join(1000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("CONVERSION_TIMEOUT");
        verify(driver).quit();
    }

    @Test void postStartupFailureQuitsDriverAndReturnsSanitizedError() {
        ConverterProperties properties = unsafeProperties();
        ChromeDriver driver = mock(ChromeDriver.class);
        when(driver.manage()).thenReturn(null);
        BrowserFactory factory = new BrowserFactory(properties, (service, options) -> driver);
        assertThatThrownBy(factory::open).isInstanceOf(com.nicodim.ocapp.support.ConversionException.class)
            .extracting("code").isEqualTo("BROWSER_UNAVAILABLE");
        verify(driver).quit();
    }

    @Test void startupFailureAndMissingDriverAreControlled() {
        ConverterProperties properties = unsafeProperties();
        properties.getBrowser().setDriverPath(temp.resolve("missing-driver").toString());
        BrowserFactory missing = new BrowserFactory(properties);
        assertThatThrownBy(missing::validateConfiguration).isInstanceOf(IllegalStateException.class);
        properties.getBrowser().setDriverPath("");
        BrowserFactory incompatible = new BrowserFactory(properties, (service, options) -> { throw new IllegalStateException("incompatible"); });
        assertThatThrownBy(incompatible::open).isInstanceOf(com.nicodim.ocapp.support.ConversionException.class)
            .extracting("code").isEqualTo("BROWSER_UNAVAILABLE");
    }

    @Test void healthActuallyProbesBrowserAndCachesBoundedResult() {
        ConverterProperties properties = unsafeProperties();
        properties.getBrowser().setHealthProbeTimeout(Duration.ofSeconds(1));
        properties.getBrowser().setHealthCacheTtl(Duration.ofSeconds(30));
        BrowserFactory factory = mock(BrowserFactory.class);
        BrowserSession session = mock(BrowserSession.class);
        when(factory.configuredExecutablesExist()).thenReturn(true);
        when(factory.open(any(RenderContext.class))).thenReturn(session);
        BrowserReadinessHealthIndicator indicator = new BrowserReadinessHealthIndicator(factory, properties.getBrowser(),
            Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verify(factory, times(1)).open(any(RenderContext.class));
        verify(session, times(1)).close();
    }

    @Test void healthProbeIsSingleFlightForConcurrentCacheMisses() throws Exception {
        ConverterProperties properties = unsafeProperties();
        properties.getBrowser().setHealthProbeTimeout(Duration.ofSeconds(1));
        BrowserFactory factory = mock(BrowserFactory.class);
        BrowserSession session = mock(BrowserSession.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(factory.configuredExecutablesExist()).thenReturn(true);
        when(factory.open(any(RenderContext.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return session;
        });
        BrowserReadinessHealthIndicator indicator = new BrowserReadinessHealthIndicator(factory, properties.getBrowser(), Clock.systemUTC());
        AtomicReference<Status> first = new AtomicReference<>(), second = new AtomicReference<>();
        Thread one = Thread.ofPlatform().start(() -> first.set(indicator.health().getStatus()));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread two = Thread.ofPlatform().start(() -> second.set(indicator.health().getStatus()));
        release.countDown();
        one.join(1000); two.join(1000);
        assertThat(first).hasValue(Status.UP);
        assertThat(second).hasValue(Status.UP);
        verify(factory, times(1)).open(any(RenderContext.class));
    }

    @Test void healthProbeSharesGlobalCapacityAndPermitRecoversAfterCancellation() throws Exception {
        ConverterProperties properties = unsafeProperties();
        properties.getBrowser().setHealthProbeTimeout(Duration.ofMillis(30));
        BrowserFactory factory = mock(BrowserFactory.class);
        BrowserSession session = mock(BrowserSession.class);
        when(factory.configuredExecutablesExist()).thenReturn(true);
        when(factory.open(any(RenderContext.class))).thenReturn(session);
        BrowserProcessLimiter limiter = new BrowserProcessLimiter(1);
        BrowserProcessLimiter.Permit conversion = limiter.tryAcquire(Duration.ofMillis(10));
        assertThat(conversion).isNotNull();

        BrowserReadinessHealthIndicator blocked = new BrowserReadinessHealthIndicator(
            factory, properties.getBrowser(), Clock.systemUTC(), limiter);
        assertThat(blocked.health().getStatus()).isEqualTo(Status.DOWN);
        verify(factory, never()).open(any(RenderContext.class));
        conversion.close();

        BrowserReadinessHealthIndicator recovered = new BrowserReadinessHealthIndicator(
            factory, properties.getBrowser(), Clock.systemUTC(), limiter);
        assertThat(recovered.health().getStatus()).isEqualTo(Status.UP);
        assertThat(limiter.availablePermits()).isEqualTo(1);
        verify(factory).open(any(RenderContext.class));
    }

    @Test void sharedPermitIsRecoveredAfterTimedOutProbeCleanup() throws Exception {
        ConverterProperties properties = unsafeProperties();
        properties.getBrowser().setHealthProbeTimeout(Duration.ofMillis(20));
        BrowserFactory factory = mock(BrowserFactory.class);
        BrowserSession pending = mock(BrowserSession.class);
        when(factory.configuredExecutablesExist()).thenReturn(true);
        when(factory.open(any(RenderContext.class))).thenAnswer(invocation -> {
            RenderContext context = invocation.getArgument(0);
            context.register(pending);
            long deadline = System.nanoTime() + Duration.ofMillis(100).toNanos();
            while (System.nanoTime() < deadline) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) { }
            }
            return pending;
        });
        BrowserProcessLimiter limiter = new BrowserProcessLimiter(1);
        BrowserReadinessHealthIndicator indicator = new BrowserReadinessHealthIndicator(
            factory, properties.getBrowser(), Clock.systemUTC(), limiter);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        verify(pending, timeout(1000)).forceClose();
        for (int i = 0; i < 100 && limiter.availablePermits() == 0; i++) Thread.sleep(5);
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test void limiterPermitCloseIsIdempotent() throws Exception {
        BrowserProcessLimiter limiter = new BrowserProcessLimiter(1);
        BrowserProcessLimiter.Permit permit = limiter.tryAcquire(Duration.ofMillis(10));
        assertThat(permit).isNotNull();
        assertThat(limiter.availablePermits()).isZero();
        permit.close();
        permit.close();
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test void healthReportsMissingAndTimedOutBrowserAndCanFailStartup() {
        ConverterProperties properties = unsafeProperties();
        BrowserFactory factory = mock(BrowserFactory.class);
        when(factory.configuredExecutablesExist()).thenReturn(false);
        BrowserReadinessHealthIndicator missing = new BrowserReadinessHealthIndicator(factory, properties.getBrowser(), Clock.systemUTC());
        assertThat(missing.health().getStatus()).isEqualTo(Status.DOWN);
        properties.getBrowser().setFailStartupIfUnavailable(true);
        assertThatThrownBy(missing::verifyRequiredBrowser).isInstanceOf(IllegalStateException.class);

        properties.getBrowser().setFailStartupIfUnavailable(false);
        properties.getBrowser().setHealthProbeTimeout(Duration.ofMillis(20));
        when(factory.configuredExecutablesExist()).thenReturn(true);
        BrowserSession pending = mock(BrowserSession.class);
        when(factory.open(any(RenderContext.class))).thenAnswer(invocation -> {
            RenderContext context = invocation.getArgument(0);
            context.register(pending);
            long deadline = System.nanoTime() + Duration.ofMillis(250).toNanos();
            while (System.nanoTime() < deadline) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }
            return pending;
        });
        BrowserReadinessHealthIndicator timeout = new BrowserReadinessHealthIndicator(factory, properties.getBrowser(), Clock.systemUTC());
        long started = System.nanoTime();
        assertThat(timeout.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
        verify(pending, timeout(1000)).forceClose();
        timeout.verifyRequiredBrowser();
    }

    public static final class SleepProcess {
        public static void main(String[] args) throws Exception {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
            }));
            Thread.sleep(30_000);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> arguments(org.openqa.selenium.chrome.ChromeOptions options) {
        return (List<String>) ((java.util.Map<String, Object>) options.asMap().get("goog:chromeOptions")).get("args");
    }

    private static ConverterProperties proxyProperties() {
        ConverterProperties properties = new ConverterProperties();
        properties.getSecurity().setProxyUrl("http://proxy.example:3128");
        return properties;
    }

    private static ConverterProperties unsafeProperties() {
        ConverterProperties properties = new ConverterProperties();
        properties.getSecurity().setEgressMode(EgressMode.UNSAFE);
        properties.getSecurity().setUnsafeAcknowledgeRisk(true);
        return properties;
    }
}
