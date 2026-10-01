package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.config.ConverterProperties.Security.EgressMode;
import com.nicodim.ocapp.conversion.RenderContext;
import com.nicodim.ocapp.support.ConversionException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BrowserFactory {
    private static final Set<String> RESERVED_ARGUMENTS = Set.of(
        "--user-data-dir", "--remote-debugging-address", "--remote-debugging-port",
        "--host-resolver-rules", "--proxy-server", "--proxy-pac-url", "--proxy-bypass-list",
        "--no-proxy-server", "--proxy-auto-detect", "--disable-quic", "--enable-quic",
        "--origin-to-force-quic-on", "--force-webrtc-ip-handling-policy", "--webrtc-ip-handling-policy",
        "--no-sandbox", "--disable-setuid-sandbox", "--load-extension", "--enable-features");
    private final ConverterProperties.Browser browser;
    private final ConverterProperties.Security security;
    private final DriverStarter starter;

    @Autowired
    public BrowserFactory(ConverterProperties configuration) {
        this(configuration, (service, options) -> new ChromeDriver(service, options));
    }

    BrowserFactory(ConverterProperties configuration, DriverStarter starter) {
        browser = configuration.getBrowser();
        security = configuration.getSecurity();
        this.starter = starter;
    }

    @PostConstruct void validateConfiguration() {
        validateExtraArguments(browser.getExtraArguments());
        if (!configuredExecutablesExist()) {
            throw new IllegalStateException("Configured browser binary or driver is unavailable");
        }
        if ((browser.getReadinessSelector() == null || browser.getReadinessSelector().isBlank())
            && (browser.getReadinessScript() == null || browser.getReadinessScript().isBlank())) {
            throw new IllegalStateException("A readiness selector or operator-configured script is required");
        }
        if (security.getEgressMode() == EgressMode.PROXY) validateProxyUri(security.getProxyUrl());
        else if (!security.isUnsafeAcknowledgeRisk()) {
            throw new IllegalStateException("UNSAFE browser egress requires converter.security.unsafe-acknowledge-risk=true");
        }
    }

    public BrowserSession open() { return open(null); }

    public BrowserSession open(RenderContext context) {
        Path profile = null;
        BrowserSession session = null;
        try {
            validateConfiguration();
            profile = Files.createTempDirectory("ocapp-chrome-");
            session = new BrowserSession(profile, browser.getCleanupTimeout());
            if (context != null && !context.register(session)) throw cancelled();
            if (!session.beginStartup()) throw cancelled();
            ChromeOptions options = options(profile);
            ChromeDriverService.Builder serviceBuilder = new ChromeDriverService.Builder();
            if (!browser.getDriverPath().isBlank()) {
                serviceBuilder.usingDriverExecutable(Path.of(browser.getDriverPath()).toFile());
            }
            ChromeDriverService service = serviceBuilder.build();
            session.attachService(service);
            ChromeDriver driver = starter.start(service, options);
            session.attachDriver(driver);
            if (context != null && context.isCancelled()) throw cancelled();
            driver.manage().timeouts().pageLoadTimeout(browser.getPageLoadTimeout());
            driver.manage().timeouts().scriptTimeout(browser.getScriptTimeout());
            if (!session.finishStartup()) throw cancelled();
            return session;
        } catch (ConversionException ex) {
            BrowserSession.CleanupResult cleanup = session == null ? BrowserSession.CleanupResult.SUCCESS : session.forceClose();
            if (context != null && session != null) context.unregister(session);
            if (!cleanup.successful()) throw BrowserSession.cleanupFailure();
            throw ex;
        } catch (IOException | RuntimeException ex) {
            BrowserSession.CleanupResult cleanup = BrowserSession.CleanupResult.SUCCESS;
            if (session != null) cleanup = session.forceClose();
            else BrowserSession.deleteDirectory(profile);
            if (context != null && session != null) context.unregister(session);
            if (!cleanup.successful()) throw BrowserSession.cleanupFailure();
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "BROWSER_UNAVAILABLE", "Browser could not be started", ex);
        }
    }

    private static ConversionException cancelled() {
        return new ConversionException(HttpStatus.GATEWAY_TIMEOUT, "CONVERSION_TIMEOUT", "Browser startup was cancelled");
    }

    ChromeOptions options(Path profile) {
        ChromeOptions options = new ChromeOptions();
        options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
        if (!browser.getBinary().isBlank()) options.setBinary(browser.getBinary());
        List<String> args = new ArrayList<>(List.of(
            "--disable-dev-shm-usage", "--disable-extensions", "--disable-background-networking",
            "--disable-sync", "--metrics-recording-only", "--no-first-run", "--no-default-browser-check",
            "--hide-scrollbars", "--force-color-profile=srgb", "--disable-features=Translate,MediaRouter",
            "--disable-quic", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
            "--window-size=" + browser.getViewportWidth() + "," + browser.getViewportHeight(),
            "--force-device-scale-factor=" + browser.getDeviceScaleFactor(),
            "--user-data-dir=" + profile.toAbsolutePath()));
        if (security.getEgressMode() == EgressMode.PROXY) {
            URI proxy = validateProxyUri(security.getProxyUrl());
            args.add("--proxy-server=" + proxy);
            args.add("--proxy-bypass-list=<-loopback>");
            args.add("--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE " + proxy.getHost());
        } else if (!security.isAllowPrivateAddresses()) {
            args.add("--host-resolver-rules=MAP localhost ~NOTFOUND, MAP *.localhost ~NOTFOUND");
        }
        if (browser.isHeadless()) args.add("--headless=new");
        args.addAll(browser.getExtraArguments());
        options.addArguments(args);
        options.setExperimentalOption("prefs", java.util.Map.of(
            "credentials_enable_service", false, "profile.password_manager_enabled", false));
        LoggingPreferences logging = new LoggingPreferences();
        logging.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", logging);
        return options;
    }

    static void validateExtraArguments(List<String> arguments) {
        for (String argument : arguments) {
            if (argument == null || argument.isBlank()) continue;
            String key = argument.toLowerCase(Locale.ROOT).split("=", 2)[0];
            if (RESERVED_ARGUMENTS.contains(key) || key.startsWith("--proxy-") || key.startsWith("--host-resolver-")) {
                throw new IllegalArgumentException("Reserved browser argument: " + key);
            }
        }
    }

    static URI validateProxyUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("invalid proxy URL");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("PROXY egress requires an absolute HTTP converter.security.proxy-url", ex);
        }
    }

    public boolean configuredExecutablesExist() {
        return executableOrBlank(browser.getBinary()) && executableOrBlank(browser.getDriverPath());
    }

    private static boolean executableOrBlank(String value) {
        return value == null || value.isBlank() || Files.isExecutable(Path.of(value));
    }

    @FunctionalInterface
    interface DriverStarter { ChromeDriver start(ChromeDriverService service, ChromeOptions options); }
}
