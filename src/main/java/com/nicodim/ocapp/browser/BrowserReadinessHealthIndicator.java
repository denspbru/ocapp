package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.config.ConverterProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("browser")
public class BrowserReadinessHealthIndicator implements HealthIndicator {
    private final BrowserFactory factory;
    private final ConverterProperties.Browser properties;
    public BrowserReadinessHealthIndicator(BrowserFactory factory, ConverterProperties configuration) { this.factory = factory; this.properties = configuration.getBrowser(); }
    @PostConstruct void verifyRequiredBrowser() {
        if (properties.isFailStartupIfUnavailable() && !factory.configuredBinaryExists()) {
            throw new IllegalStateException("Configured browser binary is unavailable");
        }
    }
    @Override public Health health() {
        boolean available = factory.configuredBinaryExists();
        if (available) return Health.up().withDetail("mode", properties.getBinary().isBlank() ? "selenium-manager" : "configured").build();
        return Health.down().withDetail("reason", "configured browser binary is unavailable").build();
    }
}
