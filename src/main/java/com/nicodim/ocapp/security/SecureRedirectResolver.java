package com.nicodim.ocapp.security;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SecureRedirectResolver implements NavigationResolver {
    private final UrlSecurityPolicy policy;
    private final ConverterProperties.Security properties;
    private final HttpClient client;

    @Autowired
    public SecureRedirectResolver(UrlSecurityPolicy policy, ConverterProperties configuration) {
        this(policy, configuration.getSecurity(), HttpClient.newBuilder()
            .connectTimeout(configuration.getSecurity().getPreflightTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    SecureRedirectResolver(UrlSecurityPolicy policy, ConverterProperties.Security properties, HttpClient client) {
        this.policy = policy; this.properties = properties; this.client = client;
    }

    @Override public URI resolve(URI initial) {
        URI current = policy.validate(initial.toString());
        for (int redirects = 0; redirects <= properties.getMaxRedirects(); redirects++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(properties.getPreflightTimeout())
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).header("User-Agent", "ocapp-security-preflight/1.0").build();
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (isRedirect(status)) {
                    if (redirects == properties.getMaxRedirects()) throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_REDIRECTS", "Target has too many redirects");
                    String location = response.headers().firstValue("location").orElseThrow(() -> new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REDIRECT", "Redirect has no location"));
                    current = policy.validate(current.resolve(location).toString());
                    continue;
                }
                return current;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ConversionException(HttpStatus.GATEWAY_TIMEOUT, "CONVERSION_TIMEOUT", "Target preflight was interrupted", ex);
            } catch (IOException | IllegalArgumentException ex) {
                throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_UNREACHABLE", "Target cannot be reached", ex);
            }
        }
        throw new IllegalStateException("redirect loop invariant");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}
