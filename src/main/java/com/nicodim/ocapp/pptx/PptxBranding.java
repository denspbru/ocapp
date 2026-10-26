package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import com.nicodim.ocapp.pptx.validation.LayoutRole;
import com.nicodim.ocapp.pptx.validation.LayoutValidationContext;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.http.HttpStatus;

/** Bounded passive branding applied after page content. */
public final class PptxBranding {
    private final ConverterProperties.Pptx pptx;
    private volatile Logo logo;

    public PptxBranding(ConverterProperties properties) { pptx = properties.getPptx(); }

    public int shapeCount(URI source) {
        var brand = pptx.getBrand();
        int count = brand.getLogoPath().isBlank() ? 0 : 1;
        if (!footerText(source).isEmpty()) count++;
        if (brand.isSlideNumbers()) count++;
        return count;
    }

    public void preflight() { if (!pptx.getBrand().getLogoPath().isBlank()) logo(); }

    public int assetCount() { return pptx.getBrand().getLogoPath().isBlank() ? 0 : 1; }

    public long assetBytes() { return assetCount() == 0 ? 0 : logo().bytes.length; }

    public void applyTheme(XSLFSlide slide, boolean templateAuthoritative) {
        if (templateAuthoritative) return;
        var brand = pptx.getBrand();
        for (var shape : slide.getShapes()) if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape text) {
            boolean title = text.getTextType() == org.apache.poi.sl.usermodel.Placeholder.TITLE
                || text.getTextType() == org.apache.poi.sl.usermodel.Placeholder.CENTERED_TITLE;
            String family = title ? brand.getTitleFontFamily() : brand.getBodyFontFamily();
            Color color = CssColors.parse(title && !brand.getPrimaryColor().isBlank()
                ? brand.getPrimaryColor() : brand.getTextColor());
            for (var paragraph : text.getTextParagraphs()) for (var run : paragraph.getTextRuns()) {
                if (!family.isBlank()) run.setFontFamily(clean(family, 256));
                if (color != null) run.setFontColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
            }
        }
    }

    public void apply(XMLSlideShow show, XSLFSlide slide, int slideNumber, URI source, boolean templateAuthoritative) {
        var brand = pptx.getBrand();
        Dimension page = show.getPageSize();
        double margin = Math.max(0, brand.getMarginPoints());
        if (!brand.getLogoPath().isBlank()) {
            Logo image = logo();
            XSLFPictureData data = show.addPicture(image.bytes, image.type);
            double width = brand.getLogoWidthPoints() > 0 ? brand.getLogoWidthPoints() : image.width * 36d / image.height;
            double height = brand.getLogoHeightPoints() > 0 ? brand.getLogoHeightPoints() : 36d;
            double fit = Math.min(1d, Math.min((page.width - 2 * margin) / width, (page.height - 2 * margin) / height));
            width *= fit; height *= fit;
            slide.createPicture(data).setAnchor(new Rectangle2D.Double(page.width - margin - width, margin, width, height));
        }
        String footer = footerText(source);
        double footerRight = page.width - margin - (brand.isSlideNumbers() ? 42 : 0);
        if (!footer.isEmpty()) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new Rectangle2D.Double(margin, page.height - margin - 14, Math.max(1, footerRight - margin), 14));
            box.setLeftInset(0); box.setRightInset(0); box.setTopInset(0); box.setBottomInset(0);
            XSLFTextRun run = box.addNewTextParagraph().addNewTextRun();
            run.setText(footer); run.setFontSize(8d);
            style(run, templateAuthoritative);
        }
        if (brand.isSlideNumbers()) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new Rectangle2D.Double(page.width - margin - 36, page.height - margin - 14, 36, 14));
            box.setLeftInset(0); box.setRightInset(0); box.setTopInset(0); box.setBottomInset(0);
            var paragraph = box.addNewTextParagraph();
            paragraph.setTextAlign(TextParagraph.TextAlign.RIGHT);
            XSLFTextRun run = paragraph.addNewTextRun(); run.setText(Integer.toString(slideNumber)); run.setFontSize(8d);
            style(run, templateAuthoritative);
        }
    }

    public void registerValidationRoles(XSLFSlide slide, int firstShape, int slideIndex, URI source,
                                                LayoutValidationContext.Builder validation) {
        int cursor = firstShape;
        if (!pptx.getBrand().getLogoPath().isBlank() && cursor < slide.getShapes().size())
            validation.register(slideIndex, slide.getShapes().get(cursor++), LayoutRole.LOGO);
        if (!footerText(source).isEmpty() && cursor < slide.getShapes().size())
            validation.register(slideIndex, slide.getShapes().get(cursor++), LayoutRole.FOOTER);
        if (pptx.getBrand().isSlideNumbers() && cursor < slide.getShapes().size())
            validation.register(slideIndex, slide.getShapes().get(cursor), LayoutRole.SLIDE_NUMBER);
    }

    private void style(XSLFTextRun run, boolean templateAuthoritative) {
        if (templateAuthoritative) return;
        var brand = pptx.getBrand();
        if (!brand.getFooterFontFamily().isBlank()) run.setFontFamily(clean(brand.getFooterFontFamily(), 256));
        Color color = CssColors.parse(brand.getTextColor());
        if (color != null) run.setFontColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
    }

    private String footerText(URI source) {
        String footer = clean(pptx.getBrand().getFooter(), 2048);
        var mode = pptx.getBrand().isSourceUrl() ? ConverterProperties.Pptx.Brand.SourceMode.ORIGIN_PATH
            : pptx.getBrand().getSourceMode();
        String url = mode == ConverterProperties.Pptx.Brand.SourceMode.NONE ? "" : sanitizeSource(source,
            mode == ConverterProperties.Pptx.Brand.SourceMode.ORIGIN_PATH);
        if (footer.isEmpty()) return url;
        if (url.isEmpty()) return footer;
        return clean(footer + " • " + url, 2048);
    }

    static String sanitizeSource(URI source) {
        return sanitizeSource(source, true);
    }

    static String sanitizeSource(URI source, boolean includePath) {
        if (source == null || source.getHost() == null
            || !("http".equalsIgnoreCase(source.getScheme()) || "https".equalsIgnoreCase(source.getScheme()))) return "";
        try {
            URI clean = new URI(source.getScheme().toLowerCase(Locale.ROOT), null,
                source.getHost().toLowerCase(Locale.ROOT), source.getPort(), includePath ? source.getPath() : null, null, null);
            return clean.toASCIIString();
        } catch (URISyntaxException ex) { return ""; }
    }

    private Logo logo() {
        Logo value = logo;
        if (value != null) return value;
        synchronized (this) {
            if (logo == null) logo = loadLogo();
            return logo;
        }
    }

    private Logo loadLogo() {
        var brand = pptx.getBrand();
        byte[] bytes = TrustedLocalFile.read(brand.getLogoPath(), brand.getTrustedRoot(), brand.getMaxLogoBytes(),
            () -> contentNotAllowed(null), PptxBranding::tooLarge);
        PictureData.PictureType type;
        if (isPng(bytes)) type = PictureData.PictureType.PNG;
        else if (isJpeg(bytes)) type = PictureData.PictureType.JPEG;
        else throw contentNotAllowed(null);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw contentNotAllowed(null);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw contentNotAllowed(null);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int images = reader.getNumImages(true);
                if (images != 1) throw contentNotAllowed(null);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 10_000 || height > 10_000
                    || width > 20_000_000L / height) throw contentNotAllowed(null);
                return new Logo(bytes.clone(), type, width, height);
            } finally { reader.dispose(); }
        } catch (ConversionException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw contentNotAllowed(ex); }
    }

    private static String clean(String value, int maximum) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), maximum));
        value.codePoints().filter(c -> c >= 32 && c != 127).limit(maximum).forEach(out::appendCodePoint);
        return out.toString().trim();
    }
    private static boolean isPng(byte[] b) { return b.length >= 8 && (b[0] & 255) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71; }
    private static boolean isJpeg(byte[] b) { return b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255; }
    private static ConversionException tooLarge() {
        return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "PPTX_TEMPLATE_TOO_LARGE", "Configured presentation branding exceeds resource limits");
    }
    private static ConversionException contentNotAllowed(Throwable cause) {
        return cause == null
            ? new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", "Configured presentation branding is not allowed")
            : new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", "Configured presentation branding is not allowed", cause);
    }
    private record Logo(byte[] bytes, PictureData.PictureType type, int width, int height) { }
}
