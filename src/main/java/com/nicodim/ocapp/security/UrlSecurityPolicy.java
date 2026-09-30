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
        final URI uri;
        try { uri = new URI(rawUrl).normalize(); }
        catch (URISyntaxException ex) { throw invalid("URL syntax is invalid"); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) throw invalid("Only HTTP and HTTPS URLs are allowed");
        if (uri.getRawUserInfo() != null) throw invalid("URL credentials are not allowed");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw invalid("URL host is required");
        if (uri.getPort() < -1 || uri.getPort() > 65535) throw invalid("URL port is invalid");
        String host;
        try { host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException ex) { throw invalid("URL host is invalid"); }
        if (matches(host, properties.getDeniedHosts())) throw forbidden();
        if (!properties.getAllowedHosts().isEmpty() && !matches(host, properties.getAllowedHosts())) throw forbidden();
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) throw forbidden();
            if (!properties.isAllowPrivateAddresses()) for (InetAddress address : addresses) if (isForbidden(address)) throw forbidden();
        } catch (UnknownHostException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_UNREACHABLE", "Target host cannot be resolved");
        }
        return uri;
    }

    static boolean matches(String host, List<String> patterns) {
        return patterns.stream().map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT))
            .anyMatch(pattern -> pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) && host.length() > pattern.length() - 1 : host.equals(pattern));
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
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(b[0]);
            return (first & 0xfe) == 0xfc || (first == 0xfe && (Byte.toUnsignedInt(b[1]) & 0xc0) == 0x80)
                || ((Inet6Address) address).isIPv4CompatibleAddress();
        }
        return true;
    }

    public String safeOrigin(URI uri) { return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT); }
    private static ConversionException invalid(String detail) { return new ConversionException(HttpStatus.BAD_REQUEST, "INVALID_URL", detail); }
    private static ConversionException forbidden() { return new ConversionException(HttpStatus.FORBIDDEN, "URL_NOT_ALLOWED", "URL is blocked by security policy"); }
}
