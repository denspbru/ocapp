package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.nicodim.ocapp.config.ConverterProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.springframework.boot.actuate.health.Status;

class BrowserSupportTest {
    @TempDir Path temp;

    @Test void rejectsExtraArgumentsThatCanBypassIsolationOrNetworkPolicy() {
        for (String argument : List.of("--user-data-dir=/tmp/shared", "--HOST-RESOLVER-RULES=MAP * 127.0.0.1", "--proxy-server=http://127.0.0.1", "--remote-debugging-port=9222")) {
            assertThatThrownBy(() -> BrowserFactory.validateExtraArguments(List.of(argument))).isInstanceOf(IllegalArgumentException.class);
        }
        BrowserFactory.validateExtraArguments(List.of("--lang=en-US", "--disable-gpu"));
    }

    @Test void configuredBinaryMustBeExecutable() throws Exception {
        ConverterProperties properties = new ConverterProperties();
        Path file = Files.writeString(temp.resolve("chrome"), "x");
        properties.getBrowser().setBinary(file.toString());
        BrowserFactory factory = new BrowserFactory(properties);
        assertThat(factory.configuredBinaryExists()).isFalse();
        assertThat(file.toFile().setExecutable(true)).isTrue();
        assertThat(factory.configuredBinaryExists()).isTrue();
        properties.getBrowser().setBinary("");
        assertThat(factory.configuredBinaryExists()).isTrue();
    }

    @Test void recursivelyDeletesTemporaryProfile() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("profile/nested"));
        Files.writeString(profile.resolve("cookie"), "secret");
        BrowserSession.deleteDirectory(temp.resolve("profile"));
        assertThat(temp.resolve("profile")).doesNotExist();
        BrowserSession.deleteDirectory(null);
    }

    @Test void browserSessionAlwaysAttemptsQuitAndDeletesProfile() throws Exception {
        Path profile = Files.createDirectories(temp.resolve("profile2"));
        ChromeDriver driver = mock(ChromeDriver.class);
        BrowserSession session = new BrowserSession(driver, profile);
        assertThat(session.driver()).isSameAs(driver);
        session.close();
        org.mockito.Mockito.verify(driver).quit();
        assertThat(profile).doesNotExist();
    }

    @Test void browserStartupFailureIsSanitizedAndCleansProfile() {
        ConverterProperties properties = new ConverterProperties();
        properties.getBrowser().setDriverPath(temp.resolve("missing-driver").toString());
        assertThatThrownBy(() -> new BrowserFactory(properties).open())
            .isInstanceOf(com.nicodim.ocapp.support.ConversionException.class)
            .extracting("code").isEqualTo("BROWSER_UNAVAILABLE");
    }

    @Test void healthReflectsConfiguredBinaryAndStartupPolicy() throws Exception {
        ConverterProperties properties = new ConverterProperties();
        Path missing = temp.resolve("missing"); properties.getBrowser().setBinary(missing.toString());
        BrowserFactory factory = new BrowserFactory(properties);
        BrowserReadinessHealthIndicator indicator = new BrowserReadinessHealthIndicator(factory, properties);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        properties.getBrowser().setFailStartupIfUnavailable(true);
        assertThatThrownBy(indicator::verifyRequiredBrowser).isInstanceOf(IllegalStateException.class);
        properties.getBrowser().setFailStartupIfUnavailable(false);
        indicator.verifyRequiredBrowser();
    }
}
