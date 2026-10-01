package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Shared upper bound for every browser process started by conversions or health probes. */
@Component
public final class BrowserProcessLimiter {
    private final Semaphore permits;

    @Autowired
    public BrowserProcessLimiter(ConverterProperties properties) {
        this(properties.getLimits().getMaxConcurrent());
    }

    BrowserProcessLimiter(int maximum) {
        permits = new Semaphore(maximum, true);
    }

    public Permit tryAcquire(Duration timeout) throws InterruptedException {
        return permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS) ? new Permit() : null;
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() { }

        @Override public void close() {
            if (released.compareAndSet(false, true)) permits.release();
        }
    }
}
