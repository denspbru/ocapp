package com.nicodim.ocapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecureRedirectResolverTest {
    private UrlSecurityPolicy policy;
    private ConverterProperties.Security properties;
    private HttpClient client;

    @BeforeEach void setUp() {
        policy = mock(UrlSecurityPolicy.class);
        properties = new ConverterProperties.Security(); properties.setMaxRedirects(2);
        client = mock(HttpClient.class);
    }

    @Test void springConstructorBuildsNoFollowHttpClient() {
        ConverterProperties root = new ConverterProperties();
        assertThat(new SecureRedirectResolver(policy, root)).isNotNull();
    }

    @Test void followsRelativeRedirectsWithoutAutomaticClientRedirects() throws Exception {
        URI first = URI.create("https://example.org/a"), second = URI.create("https://example.org/b");
        when(policy.validate(first.toString())).thenReturn(first);
        when(policy.validate(second.toString())).thenReturn(second);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(302, "/b"), response(200, null));
        assertThat(new SecureRedirectResolver(policy, properties, client).resolve(first)).isEqualTo(second);
        verify(policy).validate(second.toString());
    }

    @Test void rejectsRedirectBeforeRequestingBlockedDestination() throws Exception {
        URI first = URI.create("https://example.org/a");
        when(policy.validate(first.toString())).thenReturn(first);
        when(policy.validate("http://127.0.0.1/admin")).thenThrow(new ConversionException(org.springframework.http.HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "blocked"));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(301, "http://127.0.0.1/admin"));
        assertCode(() -> new SecureRedirectResolver(policy, properties, client).resolve(first), "URL_NOT_ALLOWED");
        verify(client, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test void rejectsMissingLocationAndTooManyRedirects() throws Exception {
        URI uri = URI.create("https://example.org/a"); when(policy.validate(uri.toString())).thenReturn(uri);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(302, null));
        assertCode(() -> new SecureRedirectResolver(policy, properties, client).resolve(uri), "INVALID_REDIRECT");
        reset(client); properties.setMaxRedirects(0);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(307, "/again"));
        assertCode(() -> new SecureRedirectResolver(policy, properties, client).resolve(uri), "TOO_MANY_REDIRECTS");
    }

    @Test void mapsIoFailureAndInterruption() throws Exception {
        URI uri = URI.create("https://example.org/a"); when(policy.validate(uri.toString())).thenReturn(uri);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("secret"));
        assertCode(() -> new SecureRedirectResolver(policy, properties, client).resolve(uri), "TARGET_UNREACHABLE");
        reset(client); when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        assertCode(() -> new SecureRedirectResolver(policy, properties, client).resolve(uri), "CONVERSION_TIMEOUT");
        assertThat(Thread.interrupted()).isTrue();
    }

    private static HttpResponse<Void> response(int status, String location) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public java.util.Optional<HttpResponse<Void>> previousResponse() { return java.util.Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(location == null ? Map.of() : Map.of("location", List.of(location)), (a, b) -> true); }
            public Void body() { return null; }
            public java.util.Optional<SSLSession> sslSession() { return java.util.Optional.empty(); }
            public URI uri() { return URI.create("https://example.org"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(ConversionException.class).extracting("code").isEqualTo(code);
    }
}
