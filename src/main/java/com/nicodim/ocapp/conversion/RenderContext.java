package com.nicodim.ocapp.conversion;

import com.nicodim.ocapp.browser.BrowserSession;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RenderContext {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<BrowserSession> session = new AtomicReference<>();
    private final AtomicReference<BrowserSession.CleanupResult> cleanupResult =
        new AtomicReference<>(BrowserSession.CleanupResult.SUCCESS);

    public boolean register(BrowserSession value) {
        if (cancelled.get()) {
            record(value.forceClose());
            return false;
        }
        if (!session.compareAndSet(null, value)) throw new IllegalStateException("A browser session is already registered");
        if (cancelled.get() && session.compareAndSet(value, null)) {
            record(value.forceClose());
            return false;
        }
        return true;
    }

    public void unregister(BrowserSession value) { session.compareAndSet(value, null); }

    public BrowserSession.CleanupResult cancel() {
        cancelled.set(true);
        BrowserSession value = session.getAndSet(null);
        if (value != null) record(value.forceClose());
        return cleanupResult.get();
    }

    public boolean isCancelled() { return cancelled.get(); }

    public boolean cleanupFailed() { return cleanupResult.get() == BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED; }

    private void record(BrowserSession.CleanupResult result) {
        if (result == BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED) {
            cleanupResult.set(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        }
    }
}
