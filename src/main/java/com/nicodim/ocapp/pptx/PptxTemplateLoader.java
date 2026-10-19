package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.http.HttpStatus;

/** Loads one operator-installed template as immutable validated bytes. */
public final class PptxTemplateLoader {
    public static final Set<String> ALIASES = Set.of("blank", "title", "title-only", "content", "section", "closing");
    private final ConverterProperties.Pptx pptx;
    private volatile Snapshot cached;

    public PptxTemplateLoader(ConverterProperties properties) {
        this.pptx = java.util.Objects.requireNonNull(properties, "properties").getPptx();
    }

    public boolean configured() { return !pptx.getTemplate().getPath().isBlank(); }

    /** Performs all file/ZIP/POI validation and is safe to call before a browser is opened. */
    public Snapshot preflight() {
        if (!configured()) return null;
        Snapshot value = cached;
        if (value != null) return value;
        synchronized (this) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    public XMLSlideShow openFresh() {
        Snapshot value = preflight();
        if (value == null) return new XMLSlideShow();
        XMLSlideShow show = null;
        try {
            show = new XMLSlideShow(new ByteArrayInputStream(value.bytes));
            int originals = show.getSlides().size();
            if (originals != value.originalSlides) throw invalid(null);
            if (originals > 0) {
                if (pptx.getTemplate().getOriginalSlidePolicy() == ConverterProperties.Pptx.Template.OriginalSlidePolicy.REJECT)
                    throw invalid(null);
                for (int i = originals - 1; i >= 0; i--) show.removeSlide(i);
            }
            validatePresentation(show);
            return show;
        } catch (ConversionException ex) {
            closeOnFailure(show); throw ex;
        } catch (IOException | RuntimeException ex) {
            closeOnFailure(show); throw invalid(ex);
        }
    }

    public org.apache.poi.xslf.usermodel.XSLFSlide createSlide(XMLSlideShow show, String pageAlias) {
        if (!configured()) return show.createSlide();
        org.apache.poi.xslf.usermodel.XSLFSlide slide = show.createSlide(selectLayout(show, pageAlias));
        // Retain authored placeholder anchors/styles, but never retain design-time prompt text.
        for (var shape : slide.getShapes())
            if (shape instanceof XSLFTextShape text && text.getTextType() != null) text.clearText();
        return slide;
    }

    public XSLFSlideLayout selectLayout(XMLSlideShow show, String pageAlias) {
        java.util.List<String> order = new java.util.ArrayList<>(4);
        String page = normalizeAlias(pageAlias);
        if (!page.isEmpty()) order.add(page);
        String global = normalizeAlias(pptx.getLayouts().getDefaultAlias());
        if (!global.isEmpty() && !order.contains(global)) order.add(global);
        if (!order.contains("content")) order.add("content");
        if (!order.contains("blank")) order.add("blank");
        for (String alias : order) {
            XSLFSlideLayout layout = findLayout(show, alias);
            if (layout != null) return layout;
        }
        throw layoutUnavailable();
    }

    private Snapshot load() {
        ConverterProperties.Pptx.Template options = pptx.getTemplate();
        if (!options.getPath().toLowerCase(Locale.ROOT).endsWith(".pptx")) throw untrusted(null);
        Path configured;
        try { configured = Paths.get(options.getPath()); } catch (RuntimeException ex) { throw untrusted(ex); }
        if (configured.isAbsolute() && !Files.exists(configured, LinkOption.NOFOLLOW_LINKS))
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "PPTX_TEMPLATE_NOT_FOUND", "Configured presentation template was not found");
        if (configured.isAbsolute() && !Files.isReadable(configured))
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "PPTX_TEMPLATE_UNREADABLE", "Configured presentation template is not readable");
        byte[] bytes = TrustedLocalFile.read(options.getPath(), options.getTrustedRoot(), options.getMaxBytes(),
            () -> untrusted(null), PptxTemplateLoader::tooLarge);
        ZipManifest zip = validateZip(bytes, options);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            validatePresentation(show);
            int originals = show.getSlides().size();
            if (originals > 0 && options.getOriginalSlidePolicy() == ConverterProperties.Pptx.Template.OriginalSlidePolicy.REJECT)
                throw invalid(null);
            validateAliases(show);
            XSLFSlideLayout common = selectLayout(show, "");
            Rectangle2D content = layoutContentBox(common, show.getPageSize());
            return new Snapshot(bytes.clone(), show.getPageSize(), content, originals, zip.entries, zip.uncompressedBytes);
        } catch (ConversionException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw invalid(ex); }
    }

    private void validateAliases(XMLSlideShow show) {
        for (Map.Entry<String,String> entry : pptx.getLayouts().getAliases().entrySet()) {
            String alias = normalizeAlias(entry.getKey());
            String target = safeTarget(entry.getValue());
            if (!ALIASES.contains(alias) || target.isEmpty()) throw invalid(null);
            if (findByName(show, target) == null) throw layoutUnavailable();
        }
        String global = normalizeAlias(pptx.getLayouts().getDefaultAlias());
        if (!ALIASES.contains(global)) throw invalid(null);
        if (selectAvailable(show, global) == null && selectAvailable(show, "content") == null
            && selectAvailable(show, "blank") == null) throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PPTX_LAYOUT_UNAVAILABLE", "Configured presentation layout is unavailable");
    }

    private XSLFSlideLayout findLayout(XMLSlideShow show, String alias) {
        String configured = pptx.getLayouts().getAliases().get(alias);
        if (configured != null) return findByName(show, safeTarget(configured));
        return selectAvailable(show, alias);
    }

    public Dimension pageSize() {
        Snapshot value = preflight();
        if (value != null) return value.pageSize();
        return new Dimension((int) Math.round(pptx.getSlideWidthInches() * 72),
            (int) Math.round(pptx.getSlideHeightInches() * 72));
    }

    /** One shared box is used by pagination and both visual/editable mappers. */
    public Rectangle2D contentBox() {
        Snapshot value = preflight();
        Dimension page = pageSize();
        double pageWidth = value == null ? pptx.getSlideWidthInches() * 72d : page.width;
        double pageHeight = value == null ? pptx.getSlideHeightInches() * 72d : page.height;
        Rectangle2D base = value == null ? new Rectangle2D.Double(0, 0, pageWidth, pageHeight) : value.contentBox();
        ConverterProperties.Pptx.Brand brand = pptx.getBrand();
        boolean logo = !brand.getLogoPath().isBlank();
        boolean footer = !brand.getFooter().isBlank() || brand.isSourceUrl() || brand.isSlideNumbers();
        if (!logo && !footer) return (Rectangle2D) base.clone();
        double margin = Math.max(0, brand.getMarginPoints());
        double left = Math.max(base.getX(), margin);
        double right = Math.min(base.getMaxX(), pageWidth - margin);
        double top = Math.max(base.getY(), logo ? margin + effectiveLogoHeight(brand) : margin);
        double bottom = Math.min(base.getMaxY(), pageHeight - (footer ? margin + 18 : margin));
        if (right - left < 72 || bottom - top < 72) throw invalid(null);
        return new Rectangle2D.Double(left, top, right - left, bottom - top);
    }

    private static double effectiveLogoHeight(ConverterProperties.Pptx.Brand brand) {
        return brand.getLogoHeightPoints() > 0 ? brand.getLogoHeightPoints() : 36;
    }

    private static Rectangle2D layoutContentBox(XSLFSlideLayout layout, Dimension page) {
        for (var shape : layout.getShapes()) if (shape instanceof XSLFTextShape text) {
            var placeholder = text.getTextType();
            if (placeholder == org.apache.poi.sl.usermodel.Placeholder.BODY
                || placeholder == org.apache.poi.sl.usermodel.Placeholder.CONTENT) {
                Rectangle2D anchor = text.getAnchor();
                if (anchor != null && anchor.getWidth() >= 72 && anchor.getHeight() >= 72
                    && anchor.getX() >= 0 && anchor.getY() >= 0
                    && anchor.getMaxX() <= page.width + .01 && anchor.getMaxY() <= page.height + .01)
                    return (Rectangle2D) anchor.clone();
            }
        }
        return new Rectangle2D.Double(0, 0, page.width, page.height);
    }

    private static XSLFSlideLayout findByName(XMLSlideShow show, String target) {
        java.util.List<XSLFSlideLayout> layouts = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (XSLFSlideMaster master : show.getSlideMasters()) for (XSLFSlideLayout layout : master.getSlideLayouts()) {
            layouts.add(layout); names.add(layout.getName());
        }
        int index = uniqueLayoutIndex(names, target);
        return index < 0 ? null : layouts.get(index);
    }

    static int uniqueLayoutIndex(java.util.List<String> names, String target) {
        int found = -1;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            if (name != null && name.equalsIgnoreCase(target)) {
                if (found >= 0) return -2;
                found = index;
            }
        }
        return found;
    }

    private static XSLFSlideLayout selectAvailable(XMLSlideShow show, String alias) {
        SlideLayout[] desired = switch (alias) {
            case "title" -> new SlideLayout[]{SlideLayout.TITLE, SlideLayout.TITLE_ONLY};
            case "title-only" -> new SlideLayout[]{SlideLayout.TITLE_ONLY, SlideLayout.TITLE};
            case "content" -> new SlideLayout[]{SlideLayout.TITLE_AND_CONTENT, SlideLayout.TEXT, SlideLayout.OBJ_ONLY};
            case "section" -> new SlideLayout[]{SlideLayout.SECTION_HEADER, SlideLayout.TITLE_ONLY};
            case "closing" -> new SlideLayout[]{SlideLayout.TITLE_ONLY, SlideLayout.TITLE, SlideLayout.BLANK};
            case "blank" -> new SlideLayout[]{SlideLayout.BLANK};
            default -> new SlideLayout[0];
        };
        for (SlideLayout type : desired) for (XSLFSlideMaster master : show.getSlideMasters()) {
            XSLFSlideLayout layout = master.getLayout(type);
            if (layout != null) return layout;
        }
        return null;
    }

    private static void validatePresentation(XMLSlideShow show) {
        Dimension size = show.getPageSize();
        if (size == null || size.width < 72 || size.height < 72 || size.width > 14_400 || size.height > 14_400)
            throw invalid(null);
        if (show.getSlideMasters().isEmpty()) throw invalid(null);
        boolean layout = false;
        for (XSLFSlideMaster master : show.getSlideMasters()) {
            if (master.getTheme() == null || master.getSlideLayouts().length == 0) throw invalid(null);
            for (XSLFSlideLayout candidate : master.getSlideLayouts()) {
                if (candidate.getSlideMaster() != master || candidate.getTheme() == null) throw invalid(null);
                layout = true;
            }
        }
        if (!layout) throw invalid(null);
    }

    private static ZipManifest validateZip(byte[] bytes, ConverterProperties.Pptx.Template options) {
        PptxPackageValidator.Stats stats = PptxPackageValidator.validateTemplate(bytes, options);
        return new ZipManifest(stats.entries(), stats.uncompressedBytes());
    }

    private static String normalizeAlias(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    private static String safeTarget(String value) {
        if (value == null) return "";
        String result = value.trim();
        return result.length() <= 256 && result.chars().allMatch(c -> c >= 32 && c != 127) ? result : "";
    }
    private static void closeOnFailure(XMLSlideShow show) {
        if (show != null) try { show.close(); } catch (IOException ignored) { }
    }
    private static ConversionException layoutUnavailable() {
        return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_LAYOUT_UNAVAILABLE",
            "Configured presentation layout is unavailable");
    }
    private static ConversionException invalid(Throwable cause) {
        return cause == null
            ? new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_INVALID", "Configured presentation template is invalid")
            : new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_INVALID", "Configured presentation template is invalid", cause);
    }
    private static ConversionException untrusted(Throwable cause) {
        return cause == null
            ? new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_UNTRUSTED", "Configured presentation template is not trusted")
            : new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_UNTRUSTED", "Configured presentation template is not trusted", cause);
    }
    private static ConversionException tooLarge() {
        return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "PPTX_TEMPLATE_TOO_LARGE", "Configured presentation template exceeds resource limits");
    }
    public static final class Snapshot {
        private final byte[] bytes;
        private final Dimension pageSize;
        private final Rectangle2D contentBox;
        private final int originalSlides;
        private final int entries;
        private final long uncompressedBytes;
        private Snapshot(byte[] bytes, Dimension pageSize, Rectangle2D contentBox, int originalSlides, int entries, long uncompressedBytes) {
            this.bytes = bytes; this.pageSize = new Dimension(pageSize); this.contentBox = (Rectangle2D) contentBox.clone(); this.originalSlides = originalSlides;
            this.entries = entries; this.uncompressedBytes = uncompressedBytes;
        }
        public byte[] bytes() { return bytes.clone(); }
        public Dimension pageSize() { return new Dimension(pageSize); }
        public Rectangle2D contentBox() { return (Rectangle2D) contentBox.clone(); }
        public int originalSlides() { return originalSlides; }
        public int entries() { return entries; }
        public long uncompressedBytes() { return uncompressedBytes; }
    }
    private record ZipManifest(int entries, long uncompressedBytes) { }
}
