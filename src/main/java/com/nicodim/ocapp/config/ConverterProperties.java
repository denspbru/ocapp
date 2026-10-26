package com.nicodim.ocapp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
        @Valid private final Template template = new Template();
        @Valid private final Layouts layouts = new Layouts();
        @Valid private final Brand brand = new Brand();
        @Valid private final LayoutValidation layoutValidation = new LayoutValidation();
        @DecimalMin("1.0") private double slideWidthInches = 13.333;
        @DecimalMin("1.0") private double slideHeightInches = 7.5;
        @Min(1) @Max(500) private int maxSlides = 100;
        private boolean smartPaginationEnabled = true;
        private boolean editableEnabled = true;
        private boolean legacyFallbackEnabled = true;
        @Min(1) @Max(10_000) private int maxItemsPerSlide = 1_000;
        @Min(1) @Max(20_000) private int maxNativeTableCells = 500;
        @Min(1) @Max(100) private int maxNativeTableColumns = 12;
        @DecimalMin("1.0") private double minNativeTableColumnPoints = 18;
        @DecimalMin("1.0") private double minSliceHeightPixels = 120;
        @DecimalMin("1.0") @DecimalMax("4.0") private double graphicsExportScale = 2.0;
        @Pattern(regexp = "transparent|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}") private String graphicsExportBackground = "transparent";
        @Pattern(regexp = "png|svg") private String graphicsExportFormat = "png";
        @Min(1) private long maxGraphicsExportPixels = 16_000_000L;
        @Min(1024) private long maxGraphicsExportBytes = 8L * 1024 * 1024;
        public Template getTemplate() { return template; }
        public Layouts getLayouts() { return layouts; }
        public Brand getBrand() { return brand; }
        public LayoutValidation getLayoutValidation() { return layoutValidation; }
        public double getSlideWidthInches() { return slideWidthInches; } public void setSlideWidthInches(double v) { slideWidthInches = v; }
        public double getSlideHeightInches() { return slideHeightInches; } public void setSlideHeightInches(double v) { slideHeightInches = v; }
        public int getMaxSlides() { return maxSlides; } public void setMaxSlides(int v) { maxSlides = v; }
        public boolean isSmartPaginationEnabled() { return smartPaginationEnabled; } public void setSmartPaginationEnabled(boolean v) { smartPaginationEnabled = v; }
        public boolean isEditableEnabled() { return editableEnabled; } public void setEditableEnabled(boolean v) { editableEnabled = v; }
        public boolean isLegacyFallbackEnabled() { return legacyFallbackEnabled; } public void setLegacyFallbackEnabled(boolean v) { legacyFallbackEnabled = v; }
        public int getMaxItemsPerSlide() { return maxItemsPerSlide; } public void setMaxItemsPerSlide(int v) { maxItemsPerSlide = v; }
        public int getMaxNativeTableCells() { return maxNativeTableCells; } public void setMaxNativeTableCells(int v) { maxNativeTableCells = v; }
        public int getMaxNativeTableColumns() { return maxNativeTableColumns; } public void setMaxNativeTableColumns(int v) { maxNativeTableColumns = v; }
        public double getMinNativeTableColumnPoints() { return minNativeTableColumnPoints; } public void setMinNativeTableColumnPoints(double v) { minNativeTableColumnPoints = v; }
        public double getMinSliceHeightPixels() { return minSliceHeightPixels; } public void setMinSliceHeightPixels(double v) { minSliceHeightPixels = v; }
        public double getGraphicsExportScale() { return graphicsExportScale; } public void setGraphicsExportScale(double v) { graphicsExportScale = v; }
        public String getGraphicsExportBackground() { return graphicsExportBackground; } public void setGraphicsExportBackground(String v) { graphicsExportBackground = v; }
        public String getGraphicsExportFormat() { return graphicsExportFormat; } public void setGraphicsExportFormat(String v) { graphicsExportFormat = v; }
        public long getMaxGraphicsExportPixels() { return maxGraphicsExportPixels; } public void setMaxGraphicsExportPixels(long v) { maxGraphicsExportPixels = v; }
        public long getMaxGraphicsExportBytes() { return maxGraphicsExportBytes; } public void setMaxGraphicsExportBytes(long v) { maxGraphicsExportBytes = v; }


        public static class LayoutValidation {
            @Min(1) @Max(100_000) private int maxShapes = 10_000;
            @Min(1) @Max(100_000) private int maxTextShapes = 5_000;
            @Min(1) @Max(100_000) private int maxImages = 2_000;
            @Min(1) @Max(10_000) private int maxDiagnostics = 200;
            @Min(0) @Max(10_000_000) private long maxOverlapComparisons = 100_000;
            @DecimalMin("0.0") @DecimalMax("72.0") private double textOverflowTolerancePoints = 1.0;
            @DecimalMin("0.0") @DecimalMax("1.0") private double textOverflowToleranceRatio = 0.02;
            @DecimalMin("0.0") @DecimalMax("72.0") private double minimumBodyFontPoints = 8.0;
            @DecimalMin("0.0") @DecimalMax("72.0") private double minimumFooterFontPoints = 6.0;
            @DecimalMin("0.0") @DecimalMax("2400.0") private double minimumImagePpi = 96.0;
            @DecimalMin("0.0") private double overlapAreaPoints = 4.0;
            @DecimalMin("0.0") @DecimalMax("1.0") private double overlapAreaRatio = 0.05;
            public int getMaxShapes() { return maxShapes; } public void setMaxShapes(int v) { maxShapes = v; }
            public int getMaxTextShapes() { return maxTextShapes; } public void setMaxTextShapes(int v) { maxTextShapes = v; }
            public int getMaxImages() { return maxImages; } public void setMaxImages(int v) { maxImages = v; }
            public int getMaxDiagnostics() { return maxDiagnostics; } public void setMaxDiagnostics(int v) { maxDiagnostics = v; }
            public long getMaxOverlapComparisons() { return maxOverlapComparisons; } public void setMaxOverlapComparisons(long v) { maxOverlapComparisons = v; }
            public double getTextOverflowTolerancePoints() { return textOverflowTolerancePoints; } public void setTextOverflowTolerancePoints(double v) { textOverflowTolerancePoints = v; }
            public double getTextOverflowToleranceRatio() { return textOverflowToleranceRatio; } public void setTextOverflowToleranceRatio(double v) { textOverflowToleranceRatio = v; }
            public double getMinimumBodyFontPoints() { return minimumBodyFontPoints; } public void setMinimumBodyFontPoints(double v) { minimumBodyFontPoints = v; }
            public double getMinimumFooterFontPoints() { return minimumFooterFontPoints; } public void setMinimumFooterFontPoints(double v) { minimumFooterFontPoints = v; }
            public double getMinimumImagePpi() { return minimumImagePpi; } public void setMinimumImagePpi(double v) { minimumImagePpi = v; }
            public double getOverlapAreaPoints() { return overlapAreaPoints; } public void setOverlapAreaPoints(double v) { overlapAreaPoints = v; }
            public double getOverlapAreaRatio() { return overlapAreaRatio; } public void setOverlapAreaRatio(double v) { overlapAreaRatio = v; }
        }

        public static class Template {
            public enum OriginalSlidePolicy { REJECT, REMOVE }
            @Size(max = 4096) private String path = "";
            @Size(max = 4096) private String trustedRoot = "";
            @Min(1024) private long maxBytes = 10L * 1024 * 1024;
            @Min(1) @Max(20_000) private int maxEntries = 2_000;
            @Min(1024) private long maxEntryBytes = 10L * 1024 * 1024;
            @Min(1024) private long maxUncompressedBytes = 50L * 1024 * 1024;
            @DecimalMin("1.0") @DecimalMax("10000.0") private double maxCompressionRatio = 100.0;
            @NotNull private OriginalSlidePolicy originalSlidePolicy = OriginalSlidePolicy.REJECT;
            public String getPath() { return path; } public void setPath(String v) { path = v == null ? "" : v; }
            public String getTrustedRoot() { return trustedRoot; } public void setTrustedRoot(String v) { trustedRoot = v == null ? "" : v; }
            public long getMaxBytes() { return maxBytes; } public void setMaxBytes(long v) { maxBytes = v; }
            public int getMaxEntries() { return maxEntries; } public void setMaxEntries(int v) { maxEntries = v; }
            public long getMaxEntryBytes() { return maxEntryBytes; } public void setMaxEntryBytes(long v) { maxEntryBytes = v; }
            public long getMaxUncompressedBytes() { return maxUncompressedBytes; } public void setMaxUncompressedBytes(long v) { maxUncompressedBytes = v; }
            public double getMaxCompressionRatio() { return maxCompressionRatio; } public void setMaxCompressionRatio(double v) { maxCompressionRatio = v; }
            public OriginalSlidePolicy getOriginalSlidePolicy() { return originalSlidePolicy; } public void setOriginalSlidePolicy(OriginalSlidePolicy v) { originalSlidePolicy = v; }
        }

        public static class Layouts {
            @Pattern(regexp = "blank|title|title-only|content|section|closing") private String defaultAlias = "content";
            @Size(max = 6) private Map<@Pattern(regexp = "blank|title|title-only|content|section|closing") String, @Size(max = 256) String> aliases = new LinkedHashMap<>();
            public String getDefaultAlias() { return defaultAlias; } public void setDefaultAlias(String v) { defaultAlias = v; }
            public Map<String,String> getAliases() { return aliases; }
            public void setAliases(Map<String,String> v) { aliases = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v); }
        }

        public static class Brand {
            public enum SourceMode { NONE, ORIGIN, ORIGIN_PATH }
            @Size(max = 4096) private String logoPath = "";
            @Size(max = 4096) private String trustedRoot = "";
            @Min(1024) private long maxLogoBytes = 2L * 1024 * 1024;
            @DecimalMin("0.0") private double logoWidthPoints = 0;
            @DecimalMin("0.0") private double logoHeightPoints = 0;
            @DecimalMin("0.0") private double marginPoints = 18;
            @Size(max = 2048) private String footer = "";
            private boolean sourceUrl;
            @NotNull private SourceMode sourceMode = SourceMode.NONE;
            private boolean slideNumbers;
            @Pattern(regexp = "|#[0-9a-fA-F]{6}") private String primaryColor = "";
            @Pattern(regexp = "|#[0-9a-fA-F]{6}") private String textColor = "";
            @Size(max = 256) private String titleFontFamily = "";
            @Size(max = 256) private String bodyFontFamily = "";
            @Size(max = 256) private String footerFontFamily = "";
            public String getLogoPath() { return logoPath; } public void setLogoPath(String v) { logoPath = v == null ? "" : v; }
            public String getTrustedRoot() { return trustedRoot; } public void setTrustedRoot(String v) { trustedRoot = v == null ? "" : v; }
            public long getMaxLogoBytes() { return maxLogoBytes; } public void setMaxLogoBytes(long v) { maxLogoBytes = v; }
            public double getLogoWidthPoints() { return logoWidthPoints; } public void setLogoWidthPoints(double v) { logoWidthPoints = v; }
            public double getLogoHeightPoints() { return logoHeightPoints; } public void setLogoHeightPoints(double v) { logoHeightPoints = v; }
            public double getMarginPoints() { return marginPoints; } public void setMarginPoints(double v) { marginPoints = v; }
            public String getFooter() { return footer; } public void setFooter(String v) { footer = v == null ? "" : v; }
            public boolean isSourceUrl() { return sourceUrl; } public void setSourceUrl(boolean v) { sourceUrl = v; }
            public SourceMode getSourceMode() { return sourceMode; } public void setSourceMode(SourceMode v) { sourceMode = v; }
            public boolean isSlideNumbers() { return slideNumbers; } public void setSlideNumbers(boolean v) { slideNumbers = v; }
            public String getPrimaryColor() { return primaryColor; } public void setPrimaryColor(String v) { primaryColor = v == null ? "" : v; }
            public String getTextColor() { return textColor; } public void setTextColor(String v) { textColor = v == null ? "" : v; }
            public String getTitleFontFamily() { return titleFontFamily; } public void setTitleFontFamily(String v) { titleFontFamily = v == null ? "" : v; }
            public String getBodyFontFamily() { return bodyFontFamily; } public void setBodyFontFamily(String v) { bodyFontFamily = v == null ? "" : v; }
            public String getFooterFontFamily() { return footerFontFamily; } public void setFooterFontFamily(String v) { footerFontFamily = v == null ? "" : v; }
        }
    }
}
