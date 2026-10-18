package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.support.ConversionException;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.springframework.http.HttpStatus;

/** Fast fail-closed structural reopen gate for every generated presentation. */
public final class PptxOutputValidator {
    private PptxOutputValidator() { }

    public static void validate(byte[] bytes, int expectedSlides, boolean templateRequired) {
        try {
            PptxPackageValidator.validateOutput(bytes, expectedSlides);
        } catch (RuntimeException ex) {
            throw invalid(ex);
        }
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            if (show.getSlides().size() != expectedSlides || expectedSlides < 1) throw invalid(null);
            Dimension page = show.getPageSize();
            if (page == null || page.width < 72 || page.height < 72 || page.width > 14_400 || page.height > 14_400)
                throw invalid(null);
            for (var slide : show.getSlides()) {
                if (templateRequired && (slide.getSlideLayout() == null || slide.getSlideMaster() == null || slide.getTheme() == null))
                    throw invalid(null);
                for (var shape : slide.getShapes()) {
                    Rectangle2D anchor = shape.getAnchor();
                    if (anchor == null || !finite(anchor) || anchor.getWidth() < 0 || anchor.getHeight() < 0
                        || anchor.getX() < -.02 || anchor.getY() < -.02
                        || anchor.getMaxX() > page.width + .02 || anchor.getMaxY() > page.height + .02) throw invalid(null);
                }
            }
        } catch (IOException | RuntimeException ex) { throw invalid(ex); }
    }

    private static boolean finite(Rectangle2D r) {
        return Double.isFinite(r.getX()) && Double.isFinite(r.getY())
            && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight());
    }
    private static ConversionException invalid(Throwable cause) {
        return cause == null
            ? new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_OUTPUT_INVALID", "Generated presentation failed structural validation")
            : new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_OUTPUT_INVALID", "Generated presentation failed structural validation", cause);
    }
}
