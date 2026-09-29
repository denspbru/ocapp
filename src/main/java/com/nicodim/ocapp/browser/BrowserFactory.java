package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BrowserFactory {
    private static final Set<String> RESERVED_ARGUMENTS = Set.of(
        "--user-data-dir", "--remote-debugging-address", "--remote-debugging-port",
        "--host-resolver-rules", "--proxy-server", "--proxy-pac-url");
    private final ConverterProperties.Browser properties;

    public BrowserFactory(ConverterProperties configuration) { properties = configuration.getBrowser(); }

    public BrowserSession open() {
        Path profile = null;
        try {
            profile = Files.createTempDirectory("ocapp-chrome-");
            ChromeOptions options = new ChromeOptions();
            options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
            if (!properties.getBinary().isBlank()) options.setBinary(properties.getBinary());
            List<String> args = new ArrayList<>(List.of(
                "--disable-dev-shm-usage", "--disable-extensions", "--disable-background-networking",
                "--disable-sync", "--metrics-recording-only", "--no-first-run", "--no-default-browser-check",
                "--hide-scrollbars", "--force-color-profile=srgb", "--disable-features=Translate,MediaRouter",
                "--host-resolver-rules=MAP localhost ~NOTFOUND,MAP *.localhost ~NOTFOUND",
                "--window-size=" + properties.getViewportWidth() + "," + properties.getViewportHeight(),
                "--force-device-scale-factor=" + properties.getDeviceScaleFactor(),
                "--user-data-dir=" + profile.toAbsolutePath()));
            validateExtraArguments(properties.getExtraArguments());
            if (properties.isHeadless()) args.add("--headless=new");
            args.addAll(properties.getExtraArguments());
            options.addArguments(args);
            options.setExperimentalOption("prefs", java.util.Map.of(
                "credentials_enable_service", false, "profile.password_manager_enabled", false));
            ChromeDriver driver = properties.getDriverPath().isBlank()
                ? new ChromeDriver(options)
                : new ChromeDriver(new ChromeDriverService.Builder()
                    .usingDriverExecutable(Path.of(properties.getDriverPath()).toFile()).build(), options);
            driver.manage().timeouts().pageLoadTimeout(properties.getPageLoadTimeout());
            driver.manage().timeouts().scriptTimeout(properties.getScriptTimeout());
            return new BrowserSession(driver, profile);
        } catch (IOException | RuntimeException ex) {
            BrowserSession.deleteDirectory(profile);
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "BROWSER_UNAVAILABLE", "Browser could not be started", ex);
        }
    }

    static void validateExtraArguments(List<String> arguments) {
        for (String argument : arguments) {
            String key = argument.toLowerCase(Locale.ROOT).split("=", 2)[0];
            if (RESERVED_ARGUMENTS.contains(key)) {
                throw new IllegalArgumentException("Reserved browser argument: " + key);
            }
        }
    }

    public boolean configuredBinaryExists() {
        return properties.getBinary().isBlank() || Files.isExecutable(Path.of(properties.getBinary()));
    }
}
