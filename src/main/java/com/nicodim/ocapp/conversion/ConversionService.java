package com.nicodim.ocapp.conversion;

import com.nicodim.ocapp.browser.BrowserProcessLimiter;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.security.NavigationResolver;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConversionService implements ConversionOperations {
    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final UrlSecurityPolicy urlPolicy;
    private final NavigationResolver navigationResolver;
    private final PageRenderer renderer;
    private final ConverterProperties.Limits limits;
    private final Duration cleanupTimeout;
    private final ExecutorService executor;
    private final BrowserProcessLimiter browserProcesses;
    private final Clock clock;

    @Autowired
    public ConversionService(UrlSecurityPolicy urlPolicy, NavigationResolver navigationResolver, PageRenderer renderer,
                             ConverterProperties properties, ExecutorService executor, BrowserProcessLimiter browserProcesses) {
        this(urlPolicy, navigationResolver, renderer, properties.getLimits(), properties.getBrowser().getCleanupTimeout(), executor,
            Clock.systemUTC(), browserProcesses);
    }

    ConversionService(UrlSecurityPolicy urlPolicy, NavigationResolver navigationResolver, PageRenderer renderer,
                      ConverterProperties properties, ExecutorService executor) {
        this(urlPolicy, navigationResolver, renderer, properties.getLimits(), properties.getBrowser().getCleanupTimeout(), executor,
            Clock.systemUTC(), new BrowserProcessLimiter(properties));
    }

    ConversionService(UrlSecurityPolicy urlPolicy, NavigationResolver navigationResolver, PageRenderer renderer,
                      ConverterProperties.Limits limits, ExecutorService executor, Clock clock) {
        this(urlPolicy, navigationResolver, renderer, limits, Duration.ofSeconds(1), executor, clock, limiter(limits));
    }

    ConversionService(UrlSecurityPolicy urlPolicy, NavigationResolver navigationResolver, PageRenderer renderer,
                      ConverterProperties.Limits limits, Duration cleanupTimeout, ExecutorService executor, Clock clock,
                      BrowserProcessLimiter browserProcesses) {
        this.urlPolicy = urlPolicy;
        this.navigationResolver = navigationResolver;
        this.renderer = renderer;
        this.limits = limits;
        this.cleanupTimeout = cleanupTimeout;
        this.executor = executor;
        this.browserProcesses = browserProcesses;
        this.clock = clock;
    }

    @Override public ConversionResult convert(String url, OutputFormat format) {
        BrowserProcessLimiter.Permit permit = acquire();
        long startedAt = System.nanoTime();
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        AtomicBoolean permitReleased = new AtomicBoolean();
        Runnable releasePermit = () -> { if (permitReleased.compareAndSet(false, true)) permit.close(); };
        RenderContext renderContext = new RenderContext();
        TaskExecution task = new TaskExecution(() -> perform(url, format, startedAt, callerMdc, renderContext), renderContext, releasePermit);
        Future<ConversionResult> future;
        try {
            future = executor.submit(task);
            task.setFuture(future);
        } catch (RejectedExecutionException ex) {
            task.reject();
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Conversion executor is unavailable", ex);
        }

        try {
            return future.get(limits.getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            task.cancelAndAwait(cleanupTimeout);
            if (task.cleanupFailed()) throw cleanupFailure(ex);
            log.warn("conversion timed out operation={} durationMs={}", format, elapsed(startedAt));
            throw timeout("Conversion exceeded the configured timeout", ex);
        } catch (InterruptedException ex) {
            task.cancelAndAwait(cleanupTimeout);
            Thread.currentThread().interrupt();
            if (task.cleanupFailed()) throw cleanupFailure(ex);
            throw timeout("Conversion was interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ConversionException conversionException) throw conversionException;
            throw new ConversionException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Conversion failed", cause);
        }
    }

    private BrowserProcessLimiter.Permit acquire() {
        try {
            BrowserProcessLimiter.Permit permit = browserProcesses.tryAcquire(limits.getAcquireTimeout());
            if (permit == null) {
                throw new ConversionException(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED", "Conversion capacity is exhausted");
            }
            return permit;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw timeout("Request was interrupted", ex);
        }
    }

    private static BrowserProcessLimiter limiter(ConverterProperties.Limits limits) {
        ConverterProperties properties = new ConverterProperties();
        properties.getLimits().setMaxConcurrent(limits.getMaxConcurrent());
        return new BrowserProcessLimiter(properties);
    }

    private ConversionResult perform(String url, OutputFormat format, long startedAt, Map<String, String> callerMdc, RenderContext context) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        setMdc(callerMdc);
        try {
            URI initial = urlPolicy.validate(url);
            URI finalUri = navigationResolver.resolve(initial);
            byte[] bytes = renderer.render(finalUri, format, context);
            ConversionResult result = new ConversionResult(bytes, format, filename(finalUri, format, clock.instant()));
            log.info("conversion completed operation={} origin={} durationMs={} bytes={}",
                format, urlPolicy.safeOrigin(finalUri), elapsed(startedAt), bytes.length);
            return result;
        } finally {
            setMdc(previousMdc);
        }
    }

    private static void setMdc(Map<String, String> context) {
        if (context == null || context.isEmpty()) MDC.clear();
        else MDC.setContextMap(context);
    }

    static String filename(URI uri, OutputFormat format, Instant instant) {
        String host = uri.getHost() == null ? "page" : uri.getHost().toLowerCase(Locale.ROOT);
        host = host.replaceAll("[^a-z0-9.-]", "-").replaceAll("-+", "-");
        host = host.replaceAll("^[.-]+|[.-]+$", "");
        if (host.isBlank()) host = "page";
        if (host.length() > 100) host = host.substring(0, 100).replaceAll("[.-]+$", "");
        return host + "-" + FILE_TIME.format(instant) + "." + format.extension();
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static ConversionException timeout(String message, Throwable cause) {
        return new ConversionException(HttpStatus.GATEWAY_TIMEOUT, "CONVERSION_TIMEOUT", message, cause);
    }
    private static ConversionException cleanupFailure(Throwable cause) {
        return new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "BROWSER_CLEANUP_FAILED",
            "Browser process cleanup did not complete", cause);
    }

    private enum State { QUEUED, RUNNING, CANCELLING, FINISHED }

    private static final class TaskExecution implements Callable<ConversionResult> {
        private final Callable<ConversionResult> operation;
        private final RenderContext context;
        private final Runnable releasePermit;
        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile Future<?> future;

        TaskExecution(Callable<ConversionResult> operation, RenderContext context, Runnable releasePermit) {
            this.operation = operation;
            this.context = context;
            this.releasePermit = releasePermit;
        }

        void setFuture(Future<?> value) { future = value; }

        @Override public ConversionResult call() throws Exception {
            if (!state.compareAndSet(State.QUEUED, State.RUNNING)) return null;
            try { return operation.call(); }
            finally {
                state.set(State.FINISHED);
                finished.countDown();
                releasePermit.run();
            }
        }

        void reject() {
            if (state.compareAndSet(State.QUEUED, State.FINISHED)) {
                finished.countDown();
                releasePermit.run();
            }
        }

        boolean cleanupFailed() { return context.cleanupFailed(); }

        void cancelAndAwait(Duration timeout) {
            while (true) {
                State current = state.get();
                if (current == State.FINISHED) break;
                if (current == State.QUEUED && state.compareAndSet(State.QUEUED, State.FINISHED)) {
                    Future<?> value = future;
                    if (value != null) value.cancel(true);
                    finished.countDown();
                    releasePermit.run();
                    break;
                }
                if (current == State.RUNNING && state.compareAndSet(State.RUNNING, State.CANCELLING)) {
                    context.cancel();
                    Future<?> value = future;
                    if (value != null) value.cancel(true);
                    break;
                }
                if (current == State.CANCELLING) {
                    context.cancel();
                    break;
                }
            }
            try { finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
}
