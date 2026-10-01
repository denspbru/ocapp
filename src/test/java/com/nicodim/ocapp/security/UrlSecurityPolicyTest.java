package com.nicodim.ocapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrlSecurityPolicyTest {
    private ConverterProperties.Security properties;

    @BeforeEach void setUp() { properties = new ConverterProperties.Security(); }
    private UrlSecurityPolicy policy(String... addresses) {
        return new UrlSecurityPolicy(properties, host -> {
            InetAddress[] values = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) values[i] = InetAddress.getByName(addresses[i]);
            return values;
        });
    }

    @Test void acceptsPublicHttpUrlNormalizesPathAndBuildsSafeOrigin() {
        UrlSecurityPolicy policy = policy("93.184.216.34");
        var uri = policy.validate("https://Example.org/a/../b?secret=x#fragment");
        assertThat(uri.toString()).isEqualTo("https://example.org/b?secret=x#fragment");
        assertThat(policy.safeOrigin(uri)).isEqualTo("https://example.org");
    }

    @Test void rejectsBlankLongMalformedSchemeCredentialsHostAndPort() {
        for (String value : new String[]{null, " ", "file:///etc/passwd", "data:text/plain,x", "http://user:pass@example.org", "http:///x", "http://[:::1]", "http://example.org:99999", "http://example.org:not-a-port"}) {
            assertBlocked(value, "INVALID_URL");
        }
        assertBlocked("https://example.org/" + "x".repeat(4097), "INVALID_URL");
    }

    @Test void blocksEveryImportantIpv4AndIpv6Class() {
        for (String ip : new String[]{"0.0.0.0", "10.1.2.3", "127.0.0.1", "172.16.1.1", "192.168.1.1", "169.254.169.254", "100.64.1.2", "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fe80::1", "ff02::1"}) {
            assertThatThrownBy(() -> policy(ip).validate("https://example.org")).isInstanceOf(ConversionException.class)
                .extracting("code").isEqualTo("URL_NOT_ALLOWED");
        }
    }

    @Test void rejectsIfAnyDnsAnswerIsUnsafeOrDnsHasNoAnswers() {
        assertBlocked(policy("93.184.216.34", "127.0.0.1"), "https://example.org", "URL_NOT_ALLOWED");
        assertBlocked(policy(), "https://example.org", "URL_NOT_ALLOWED");
    }

    @Test void mapsUnknownHostToUnreachable() {
        UrlSecurityPolicy policy = new UrlSecurityPolicy(properties, host -> { throw new UnknownHostException("hidden details"); });
        assertBlocked(policy, "https://example.org", "TARGET_UNREACHABLE");
    }

    @Test void denyWinsAllowListSupportsExactAndWildcardWithoutMatchingApex() {
        properties.setAllowedHosts(List.of("*.example.org", "exact.example.net", " "));
        properties.setDeniedHosts(List.of("bad.example.org"));
        assertThat(policy("93.184.216.34").validate("https://ok.example.org").getHost()).isEqualTo("ok.example.org");
        assertThat(policy("93.184.216.34").validate("https://exact.example.net").getHost()).isEqualTo("exact.example.net");
        assertBlocked("https://bad.example.org", "URL_NOT_ALLOWED");
        assertBlocked("https://example.org", "URL_NOT_ALLOWED");
        assertBlocked("https://notexample.org", "URL_NOT_ALLOWED");
    }

    @Test void privateCanBeExplicitlyAllowed() {
        properties.setAllowPrivateAddresses(true);
        assertThat(policy("127.0.0.1").validate("http://localhost").getHost()).isEqualTo("localhost");
    }

    @Test void springConstructorUsesSystemResolver() {
        assertThat(new UrlSecurityPolicy(new ConverterProperties())).isNotNull();
    }

    @Test void systemResolverResolvesLocalhostWithoutExternalNetwork() throws Exception {
        assertThat(HostResolver.system().resolve("localhost")).isNotEmpty();
    }

    @Test void patternMatchingCanonicalizesCaseTerminalDotsAndIdn() {
        assertThat(UrlSecurityPolicy.matches("a.example.org..", List.of("*.EXAMPLE.ORG."))).isTrue();
        assertThat(UrlSecurityPolicy.matches("example.org.", List.of("*.example.org.."))).isFalse();
        assertThat(UrlSecurityPolicy.matches("badexample.org", List.of("*.example.org"))).isFalse();
        assertThat(UrlSecurityPolicy.matches("xn--e1afmkfd.xn--p1ai", List.of("пример.рф."))).isTrue();
    }

    @Test void requestHostsAndPatternsShareCanonicalIdnAndMultipleDotRules() {
        properties.setAllowedHosts(List.of("*.пример.рф.."));
        UrlSecurityPolicy policy = policy("93.184.216.34");
        assertThat(policy.validate("https://www.пример.рф.../x").getHost()).isEqualTo("www.xn--e1afmkfd.xn--p1ai");
        properties.setDeniedHosts(List.of("www.xn--e1afmkfd.xn--p1ai."));
        assertBlocked(policy, "https://www.пример.рф./x", "URL_NOT_ALLOWED");
    }

    private void assertBlocked(String value, String code) { assertBlocked(policy("93.184.216.34"), value, code); }
    private static void assertBlocked(UrlSecurityPolicy policy, String value, String code) {
        assertThatThrownBy(() -> policy.validate(value)).isInstanceOf(ConversionException.class).extracting("code").isEqualTo(code);
    }
}
