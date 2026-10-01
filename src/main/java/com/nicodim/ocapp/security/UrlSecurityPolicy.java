package com.nicodim.ocapp.security;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UrlSecurityPolicy {
    private final ConverterProperties.Security properties;
    private final HostResolver resolver;

    @Autowired
    public UrlSecurityPolicy(ConverterProperties properties) { this(properties.getSecurity(), HostResolver.system()); }
    UrlSecurityPolicy(ConverterProperties.Security properties, HostResolver resolver) { this.properties = properties; this.resolver = resolver; }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw invalid("URL is required");
        if (rawUrl.length() > 4096) throw invalid("URL is too long");
        final URI parsed;
        try { parsed = new URI(rawUrl).normalize(); }
        catch (URISyntaxException ex) { throw invalid("URL syntax is invalid"); }
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) throw invalid("Only HTTP and HTTPS URLs are allowed");
        if (parsed.getRawUserInfo() != null || (parsed.getRawAuthority() != null && parsed.getRawAuthority().contains("@"))) throw invalid("URL credentials are not allowed");
        if (parsed.getPort() < -1 || parsed.getPort() > 65535) throw invalid("URL port is invalid");
        String host = canonicalHost(extractHost(parsed));
        if (matches(host, properties.getDeniedHosts())) throw forbidden();
        if (!properties.getAllowedHosts().isEmpty() && !matches(host, properties.getAllowedHosts())) throw forbidden();
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) throw forbidden();
            if (!properties.isAllowPrivateAddresses()) for (InetAddress address : addresses) if (isForbidden(address)) throw forbidden();
        } catch (UnknownHostException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_UNREACHABLE", "Target host cannot be resolved");
        }
        try {
            return new URI(scheme.toLowerCase(Locale.ROOT), null, host, parsed.getPort(), parsed.getRawPath(), parsed.getRawQuery(), parsed.getRawFragment());
        } catch (URISyntaxException ex) {
            throw invalid("URL host is invalid");
        }
    }

    private static String extractHost(URI uri) {
        if (uri.getHost() != null && !uri.getHost().isBlank()) return uri.getHost();
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) throw invalid("URL host is required");
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0 || (end + 1 < authority.length() && (authority.charAt(end + 1) != ':' || uri.getPort() < 0))) {
                throw invalid("URL host is invalid");
            }
            return authority.substring(1, end);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && uri.getPort() < 0) throw invalid("URL port is invalid");
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    static String canonicalHost(String value) {
        if (value == null) throw invalid("URL host is required");
        String host = value.trim();
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isBlank()) throw invalid("URL host is required");
        try { return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException ex) {
            if (host.contains(":")) return host.toLowerCase(Locale.ROOT);
            throw invalid("URL host is invalid");
        }
    }

    static boolean matches(String host, List<String> patterns) {
        String canonical = canonicalHost(host);
        return patterns.stream().map(String::trim).filter(s -> !s.isEmpty()).anyMatch(pattern -> {
            boolean wildcard = pattern.startsWith("*.");
            String candidate = canonicalHost(wildcard ? pattern.substring(2) : pattern);
            return wildcard ? canonical.endsWith("." + candidate) && canonical.length() > candidate.length() + 1 : canonical.equals(candidate);
        });
    }

    static boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(b[0]), c = Byte.toUnsignedInt(b[1]);
            return a == 0 || a == 10 || a == 127 || (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31)
                || (a == 192 && c == 168) || (a == 100 && c >= 64 && c <= 127) || a >= 224;
        }
        if (address instanceof Inet6Address inet6) {
            int first = Byte.toUnsignedInt(b[0]);
            return (first & 0xfe) == 0xfc || (first == 0xfe && (Byte.toUnsignedInt(b[1]) & 0xc0) == 0x80)
                || inet6.isIPv4CompatibleAddress();
        }
        return true;
    }

    public String safeOrigin(URI uri) { return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + canonicalHost(uri.getHost()); }
    private static ConversionException invalid(String detail) { return new ConversionException(HttpStatus.BAD_REQUEST, "INVALID_URL", detail); }
    private static ConversionException forbidden() { return new ConversionException(HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "URL is blocked by security policy"); }
}
