package com.nicodim.ocapp.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.nicodim.ocapp.browser.BrowserSession;
import org.junit.jupiter.api.Test;

class RenderContextTest {
    @Test void cancellationForceClosesRegisteredSessionAndIsIdempotent() {
        RenderContext context = new RenderContext();
        BrowserSession session = mock(BrowserSession.class);
        when(session.forceClose()).thenReturn(BrowserSession.CleanupResult.SUCCESS);
        assertThat(context.register(session)).isTrue();
        assertThat(context.isCancelled()).isFalse();
        context.cancel();
        context.cancel();
        assertThat(context.isCancelled()).isTrue();
        verify(session).forceClose();
    }

    @Test void cancellationRetainsStableCleanupFailure() {
        RenderContext context = new RenderContext();
        BrowserSession session = mock(BrowserSession.class);
        when(session.forceClose()).thenReturn(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        assertThat(context.register(session)).isTrue();
        assertThat(context.cancel()).isEqualTo(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        assertThat(context.cancel()).isEqualTo(BrowserSession.CleanupResult.PROCESS_TREE_SURVIVED);
        assertThat(context.cleanupFailed()).isTrue();
    }

    @Test void cancelledContextRejectsLateSessionAndOnlyOneCanBeRegistered() {
        RenderContext cancelled = new RenderContext();
        BrowserSession late = mock(BrowserSession.class);
        when(late.forceClose()).thenReturn(BrowserSession.CleanupResult.SUCCESS);
        cancelled.cancel();
        assertThat(cancelled.register(late)).isFalse();
        verify(late).forceClose();

        RenderContext context = new RenderContext();
        BrowserSession first = mock(BrowserSession.class);
        BrowserSession second = mock(BrowserSession.class);
        assertThat(context.register(first)).isTrue();
        assertThatThrownBy(() -> context.register(second)).isInstanceOf(IllegalStateException.class);
        context.unregister(first);
        assertThat(context.register(second)).isTrue();
        context.unregister(second);
    }
}
