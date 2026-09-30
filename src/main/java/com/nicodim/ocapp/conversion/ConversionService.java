package com.nicodim.ocapp.conversion;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.security.NavigationResolver;
import com.nicodim.ocapp.security.UrlSecurityPolicy;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ExecutorService executor;
    private final Semaphore capacity;
    private final Clock clock;

    @Autowired
    public ConversionService(UrlSecurityPolicy urlPolicy, NavigationResolver navigationResolver, PageRenderer renderer,
                             ConverterProperties properties, ExecutorService executor) {
        this(urlPolicy, navigationResolver, renderer, properties.getLimits(), executor, Clock.systemUTC());
    }

    ConversionService(UrlSecurityPolicy urlPolicy, NavigationResolver navigationResolver, PageRenderer renderer,
                      ConverterProperties.Limits limits, ExecutorService executor, Clock clock) {
        this.urlPolicy = urlPolicy; this.navigationResolver = navigationResolver; this.renderer = renderer;
        this.limits = limits; this.executor = executor; this.capacity = new Semaphore(limits.getMaxConcurrent(), true); this.clock = clock;
    }

    @Override public ConversionResult convert(String url, OutputFormat format) {
        try {
            if (!capacity.tryAcquire(limits.getAcquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ConversionException(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED", "Conversion capacity is exhausted");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw timeout("Request was interrupted", ex);
        }

        long startedAt = System.nanoTime();
        AtomicBoolean taskStarted = new AtomicBoolean();
        AtomicBoolean permitReleased = new AtomicBoolean();
        Runnable releasePermit = () -> {
            if (permitReleased.compareAndSet(false, true)) capacity.release();
        };
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        Future<ConversionResult> future;
        try {
            future = executor.submit(() -> {
                taskStarted.set(true);
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                setMdc(callerMdc);
                try {
                    URI initial = urlPolicy.validate(url);
                    URI finalUri = navigationResolver.resolve(initial);
                    byte[] bytes = renderer.render(finalUri, format);
                    ConversionResult result = new ConversionResult(bytes, format, filename(finalUri, format, clock.instant()));
                    log.info("conversion completed operation={} origin={} durationMs={} bytes={}",
                        format, urlPolicy.safeOrigin(finalUri), elapsed(startedAt), bytes.length);
                    return result;
                } finally {
                    setMdc(previousMdc);
                    releasePermit.run();
                }
            });
        } catch (RejectedExecutionException ex) {
            releasePermit.run();
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Conversion executor is unavailable", ex);
        }

        try {
            return future.get(limits.getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            cancel(future, taskStarted, releasePermit);
            log.warn("conversion timed out operation={} durationMs={}", format, elapsed(startedAt));
            throw timeout("Conversion exceeded the configured timeout", ex);
        } catch (InterruptedException ex) {
            cancel(future, taskStarted, releasePermit);
            Thread.currentThread().interrupt();
            throw timeout("Conversion was interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ConversionException conversionException) throw conversionException;
            throw new ConversionException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Conversion failed", cause);
        }
    }

    private static void cancel(Future<?> future, AtomicBoolean taskStarted, Runnable releasePermit) {
        future.cancel(true);
        if (!taskStarted.get()) releasePermit.run();
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
}
