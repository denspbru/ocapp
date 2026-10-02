package com.nicodim.ocapp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "converter")
public class ConverterProperties {
    @Valid private final Browser browser = new Browser();
    @Valid private final Security security = new Security();
    @Valid private final Limits limits = new Limits();
    @Valid private final Pdf pdf = new Pdf();
    @Valid private final Pptx pptx = new Pptx();
    @Valid private final PageModelLimits pageModel = new PageModelLimits();
    public Browser getBrowser() { return browser; }
    public Security getSecurity() { return security; }
    public Limits getLimits() { return limits; }
    public Pdf getPdf() { return pdf; }
    public Pptx getPptx() { return pptx; }
    public PageModelLimits getPageModel() { return pageModel; }

    public static class Browser {
        private String binary = "";
        private String driverPath = "";
        private boolean headless = true;
        private List<String> extraArguments = new ArrayList<>();
        @NotNull @DurationMin(millis = 1) private Duration pageLoadTimeout = Duration.ofSeconds(30);
        @NotNull @DurationMin(millis = 1) private Duration scriptTimeout = Duration.ofSeconds(15);
        @NotNull @DurationMin(millis = 1) private Duration readinessTimeout = Duration.ofSeconds(15);
        @NotNull @DurationMin(millis = 0) private Duration domQuietPeriod = Duration.ofMillis(500);
        @NotNull @DurationMin(millis = 0) private Duration settleDelay = Duration.ZERO;
        private String readinessSelector = "body";
        private String readinessScript = "";
        @Min(320) private int viewportWidth = 1440;
        @Min(200) private int viewportHeight = 900;
        @DecimalMin("0.1") private double deviceScaleFactor = 1.0;
        private boolean failStartupIfUnavailable;
        @NotNull @DurationMin(millis = 1) private Duration cleanupTimeout = Duration.ofSeconds(5);
        @NotNull @DurationMin(millis = 1) private Duration healthProbeTimeout = Duration.ofSeconds(10);
        @NotNull @DurationMin(millis = 1) private Duration healthCacheTtl = Duration.ofSeconds(30);
        public String getBinary() { return binary; } public void setBinary(String v) { binary = v; }
        public String getDriverPath() { return driverPath; } public void setDriverPath(String v) { driverPath = v; }
        public boolean isHeadless() { return headless; } public void setHeadless(boolean v) { headless = v; }
        public List<String> getExtraArguments() { return extraArguments; } public void setExtraArguments(List<String> v) { extraArguments = v; }
        public Duration getPageLoadTimeout() { return pageLoadTimeout; } public void setPageLoadTimeout(Duration v) { pageLoadTimeout = v; }
        public Duration getScriptTimeout() { return scriptTimeout; } public void setScriptTimeout(Duration v) { scriptTimeout = v; }
        public Duration getReadinessTimeout() { return readinessTimeout; } public void setReadinessTimeout(Duration v) { readinessTimeout = v; }
        public Duration getDomQuietPeriod() { return domQuietPeriod; } public void setDomQuietPeriod(Duration v) { domQuietPeriod = v; }
        public Duration getSettleDelay() { return settleDelay; } public void setSettleDelay(Duration v) { settleDelay = v; }
        public String getReadinessSelector() { return readinessSelector; } public void setReadinessSelector(String v) { readinessSelector = v; }
        public String getReadinessScript() { return readinessScript; } public void setReadinessScript(String v) { readinessScript = v; }
        public int getViewportWidth() { return viewportWidth; } public void setViewportWidth(int v) { viewportWidth = v; }
        public int getViewportHeight() { return viewportHeight; } public void setViewportHeight(int v) { viewportHeight = v; }
        public double getDeviceScaleFactor() { return deviceScaleFactor; } public void setDeviceScaleFactor(double v) { deviceScaleFactor = v; }
        public boolean isFailStartupIfUnavailable() { return failStartupIfUnavailable; } public void setFailStartupIfUnavailable(boolean v) { failStartupIfUnavailable = v; }
        public Duration getCleanupTimeout() { return cleanupTimeout; } public void setCleanupTimeout(Duration v) { cleanupTimeout = v; }
        public Duration getHealthProbeTimeout() { return healthProbeTimeout; } public void setHealthProbeTimeout(Duration v) { healthProbeTimeout = v; }
        public Duration getHealthCacheTtl() { return healthCacheTtl; } public void setHealthCacheTtl(Duration v) { healthCacheTtl = v; }
    }

    public static class Security {
        public enum EgressMode { PROXY, UNSAFE }
        private List<String> allowedHosts = new ArrayList<>();
        private List<String> deniedHosts = new ArrayList<>();
        private boolean allowPrivateAddresses;
        @Min(0) @Max(20) private int maxRedirects = 5;
        @NotNull @DurationMin(millis = 1) private Duration preflightTimeout = Duration.ofSeconds(10);
        @NotNull private EgressMode egressMode = EgressMode.PROXY;
        private String proxyUrl = "";
        private boolean unsafeAcknowledgeRisk;
        public List<String> getAllowedHosts() { return allowedHosts; } public void setAllowedHosts(List<String> v) { allowedHosts = v; }
        public List<String> getDeniedHosts() { return deniedHosts; } public void setDeniedHosts(List<String> v) { deniedHosts = v; }
        public boolean isAllowPrivateAddresses() { return allowPrivateAddresses; } public void setAllowPrivateAddresses(boolean v) { allowPrivateAddresses = v; }
        public int getMaxRedirects() { return maxRedirects; } public void setMaxRedirects(int v) { maxRedirects = v; }
        public Duration getPreflightTimeout() { return preflightTimeout; } public void setPreflightTimeout(Duration v) { preflightTimeout = v; }
        public EgressMode getEgressMode() { return egressMode; } public void setEgressMode(EgressMode v) { egressMode = v; }
        public String getProxyUrl() { return proxyUrl; } public void setProxyUrl(String v) { proxyUrl = v; }
        public boolean isUnsafeAcknowledgeRisk() { return unsafeAcknowledgeRisk; } public void setUnsafeAcknowledgeRisk(boolean v) { unsafeAcknowledgeRisk = v; }
    }

    public static class Limits {
        @Min(1) private int maxConcurrent = 2;
        @NotNull @DurationMin(millis = 1) private Duration acquireTimeout = Duration.ofMillis(100);
        @NotNull @DurationMin(millis = 1) private Duration operationTimeout = Duration.ofSeconds(90);
        @Min(1024) private long maxOutputBytes = 50L * 1024 * 1024;
        @Min(320) private int maxCaptureWidth = 10_000;
        @Min(200) private int maxCaptureHeight = 50_000;
        @Min(1) private long maxCapturePixels = 100_000_000L;
        @Min(1024) private long maxScreenshotBytes = 50L * 1024 * 1024;
        public int getMaxConcurrent() { return maxConcurrent; } public void setMaxConcurrent(int v) { maxConcurrent = v; }
        public Duration getAcquireTimeout() { return acquireTimeout; } public void setAcquireTimeout(Duration v) { acquireTimeout = v; }
        public Duration getOperationTimeout() { return operationTimeout; } public void setOperationTimeout(Duration v) { operationTimeout = v; }
        public long getMaxOutputBytes() { return maxOutputBytes; } public void setMaxOutputBytes(long v) { maxOutputBytes = v; }
        public int getMaxCaptureWidth() { return maxCaptureWidth; } public void setMaxCaptureWidth(int v) { maxCaptureWidth = v; }
        public int getMaxCaptureHeight() { return maxCaptureHeight; } public void setMaxCaptureHeight(int v) { maxCaptureHeight = v; }
        public long getMaxCapturePixels() { return maxCapturePixels; } public void setMaxCapturePixels(long v) { maxCapturePixels = v; }
        public long getMaxScreenshotBytes() { return maxScreenshotBytes; } public void setMaxScreenshotBytes(long v) { maxScreenshotBytes = v; }
    }

    public static class PageModelLimits {
        @Min(1) @Max(20_000) private int maxBlocks = 5_000;
        @Min(1) @Max(256) private int maxDepth = 64;
        @Min(1) private int maxTextLength = 1_000_000;
        @Min(1) private int maxTableCells = 20_000;
        @Min(1) private int maxAssets = 2_000;
        @Min(1) private long maxAssetBytes = 50L * 1024 * 1024;
        @DecimalMin("1.0") private double maxCoordinate = 1_000_000;
        @Min(0) @Max(10_000) private int maxWarnings = 500;
        @Min(0) private int maxOverlapChecks = 100_000;
        @Min(16) @Max(65_536) private int maxFieldLength = 4_096;
        @Min(64) private int maxMetadataCharacters = 250_000;
        public int getMaxBlocks() { return maxBlocks; } public void setMaxBlocks(int v) { maxBlocks=v; }
        public int getMaxDepth() { return maxDepth; } public void setMaxDepth(int v) { maxDepth=v; }
        public int getMaxTextLength() { return maxTextLength; } public void setMaxTextLength(int v) { maxTextLength=v; }
        public int getMaxTableCells() { return maxTableCells; } public void setMaxTableCells(int v) { maxTableCells=v; }
        public int getMaxAssets() { return maxAssets; } public void setMaxAssets(int v) { maxAssets=v; }
        public long getMaxAssetBytes() { return maxAssetBytes; } public void setMaxAssetBytes(long v) { maxAssetBytes=v; }
        public double getMaxCoordinate() { return maxCoordinate; } public void setMaxCoordinate(double v) { maxCoordinate=v; }
        public int getMaxWarnings() { return maxWarnings; } public void setMaxWarnings(int v) { maxWarnings=v; }
        public int getMaxOverlapChecks() { return maxOverlapChecks; } public void setMaxOverlapChecks(int v) { maxOverlapChecks=v; }
        public int getMaxFieldLength() { return maxFieldLength; } public void setMaxFieldLength(int v) { maxFieldLength=v; }
        public int getMaxMetadataCharacters() { return maxMetadataCharacters; } public void setMaxMetadataCharacters(int v) { maxMetadataCharacters=v; }
    }

    public static class Pdf {
        private boolean landscape;
        @DecimalMin("1.0") private double paperWidthInches = 8.27;
        @DecimalMin("1.0") private double paperHeightInches = 11.69;
        @DecimalMin("0.0") private double marginInches = .4;
        public boolean isLandscape() { return landscape; } public void setLandscape(boolean v) { landscape = v; }
        public double getPaperWidthInches() { return paperWidthInches; } public void setPaperWidthInches(double v) { paperWidthInches = v; }
        public double getPaperHeightInches() { return paperHeightInches; } public void setPaperHeightInches(double v) { paperHeightInches = v; }
        public double getMarginInches() { return marginInches; } public void setMarginInches(double v) { marginInches = v; }
    }

    public static class Pptx {
        @DecimalMin("1.0") private double slideWidthInches = 13.333;
        @DecimalMin("1.0") private double slideHeightInches = 7.5;
        @Min(1) @Max(500) private int maxSlides = 100;
        private boolean smartPaginationEnabled = true;
        private boolean legacyFallbackEnabled = true;
        @DecimalMin("1.0") private double minSliceHeightPixels = 120;
        public double getSlideWidthInches() { return slideWidthInches; } public void setSlideWidthInches(double v) { slideWidthInches = v; }
        public double getSlideHeightInches() { return slideHeightInches; } public void setSlideHeightInches(double v) { slideHeightInches = v; }
        public int getMaxSlides() { return maxSlides; } public void setMaxSlides(int v) { maxSlides = v; }
        public boolean isSmartPaginationEnabled() { return smartPaginationEnabled; } public void setSmartPaginationEnabled(boolean v) { smartPaginationEnabled = v; }
        public boolean isLegacyFallbackEnabled() { return legacyFallbackEnabled; } public void setLegacyFallbackEnabled(boolean v) { legacyFallbackEnabled = v; }
        public double getMinSliceHeightPixels() { return minSliceHeightPixels; } public void setMinSliceHeightPixels(double v) { minSliceHeightPixels = v; }
    }
}
