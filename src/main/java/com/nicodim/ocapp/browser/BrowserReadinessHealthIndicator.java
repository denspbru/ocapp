package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.conversion.RenderContext;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("browser")
public class BrowserReadinessHealthIndicator implements HealthIndicator {
    private final BrowserFactory factory;
    private final ConverterProperties.Browser properties;
    private final Clock clock;
    private final BrowserProcessLimiter browserProcesses;
    private final AtomicReference<CachedHealth> cached = new AtomicReference<>();

    @Autowired
    public BrowserReadinessHealthIndicator(BrowserFactory factory, ConverterProperties configuration,
                                           BrowserProcessLimiter browserProcesses) {
        this(factory, configuration.getBrowser(), Clock.systemUTC(), browserProcesses);
    }

    BrowserReadinessHealthIndicator(BrowserFactory factory, ConverterProperties.Browser properties, Clock clock) {
        this(factory, properties, clock, new BrowserProcessLimiter(1));
    }

    BrowserReadinessHealthIndicator(BrowserFactory factory, ConverterProperties.Browser properties, Clock clock,
                                    BrowserProcessLimiter browserProcesses) {
        this.factory = factory;
        this.properties = properties;
        this.clock = clock;
        this.browserProcesses = browserProcesses;
    }

    @PostConstruct void verifyRequiredBrowser() {
        if (properties.isFailStartupIfUnavailable() && health().getStatus().equals(org.springframework.boot.actuate.health.Status.DOWN)) {
            throw new IllegalStateException("Configured browser cannot be started");
        }
    }

    @Override public synchronized Health health() {
        Instant now = clock.instant();
        CachedHealth value = cached.get();
        if (value != null && now.isBefore(value.expiresAt())) return value.health();
        Health checked = probe();
        cached.set(new CachedHealth(checked, clock.instant().plus(properties.getHealthCacheTtl())));
        return checked;
    }

    private Health probe() {
        if (!factory.configuredExecutablesExist()) {
            return Health.down().withDetail("reason", "configured browser binary or driver is unavailable").build();
        }
        RenderContext context = new RenderContext();
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            BrowserProcessLimiter.Permit permit = browserProcesses.tryAcquire(properties.getHealthProbeTimeout());
            if (permit == null) return false;
            try (permit; BrowserSession session = factory.open(context)) {
                context.unregister(session);
                return true;
            }
        });
        Thread.ofVirtual().name("browser-health-probe").start(task);
        try {
            if (!task.get(properties.getHealthProbeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                return Health.down().withDetail("reason", "browser process capacity is exhausted").build();
            }
            return Health.up().withDetail("probe", "browser-started-and-closed").build();
        } catch (Exception ex) {
            task.cancel(true);
            Thread.ofVirtual().name("browser-health-cleanup").start(context::cancel);
            return Health.down().withDetail("reason", "browser startup probe failed or timed out").build();
        }
    }

    private record CachedHealth(Health health, Instant expiresAt) { }
}
