package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pptx.validation.LayoutMode;
import com.nicodim.ocapp.pptx.validation.LayoutValidationContext;
import com.nicodim.ocapp.pptx.validation.LayoutValidationPolicy;
import com.nicodim.ocapp.pptx.validation.LayoutValidationReport;
import com.nicodim.ocapp.pptx.validation.PptxLayoutValidator;
import com.nicodim.ocapp.support.ConversionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.springframework.http.HttpStatus;

/** Bounded package plus serialized/reopened structural and layout gate for every generated PPTX. */
public final class PptxOutputValidator {
    private static final Logger log = LoggerFactory.getLogger(PptxOutputValidator.class);
    private PptxOutputValidator() { }

    public static LayoutValidationReport validate(byte[] bytes, int expectedSlides, boolean templateRequired) {
        return validate(bytes, expectedSlides, templateRequired, LayoutValidationPolicy.standard(),
            LayoutValidationContext.builder(LayoutMode.VISUAL).build());
    }

    public static LayoutValidationReport validate(byte[] bytes, int expectedSlides, boolean templateRequired,
                                                   LayoutValidationPolicy policy, LayoutValidationContext context) {
        try { PptxPackageValidator.validateOutput(bytes, expectedSlides); }
        catch (RuntimeException ex) { throw invalid(ex); }
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            if (show.getSlides().size() != expectedSlides || expectedSlides < 1) throw invalid(null);
            if (templateRequired) for (var slide : show.getSlides())
                if (slide.getSlideLayout() == null || slide.getSlideMaster() == null || slide.getTheme() == null) throw invalid(null);
            LayoutValidationReport report = PptxLayoutValidator.validate(show, policy, context);
            if (report.blocking()) throw invalid(null);
            var metrics = report.metrics();
            log.info("pptx layout validated mode={} slides={} shapes={} warnings={} errors={} nativeText={} nativeTable={} nativeImage={} nativeGraphic={} fallback={} visual={} forcedBreaks={} truncated={}",
                metrics.mode(), metrics.slideCount(), metrics.shapeCount(), metrics.warningCount(), metrics.errorCount(),
                metrics.nativeTextCount(), metrics.nativeTableCount(), metrics.nativeImageCount(), metrics.nativeGraphicCount(),
                metrics.fallbackCount(), metrics.visualCount(), metrics.forcedBreakCount(), metrics.truncated());
            return report;
        } catch (ConversionException | CancellationException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw invalid(ex); }
    }

    public static LayoutValidationPolicy policy(ConverterProperties properties) {
        var c = properties.getPptx().getLayoutValidation();
        return new LayoutValidationPolicy(1, c.getMaxDiagnostics(), c.getMaxShapes(), c.getMaxTextShapes(), c.getMaxImages(),
            c.getMaxOverlapComparisons(), .02, c.getTextOverflowTolerancePoints(), c.getTextOverflowToleranceRatio(),
            c.getMinimumBodyFontPoints(), c.getMinimumFooterFontPoints(), c.getMinimumImagePpi(),
            c.getOverlapAreaPoints(), c.getOverlapAreaRatio());
    }

    private static ConversionException invalid(Throwable cause) {
        return cause == null
            ? new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_OUTPUT_INVALID", "Generated presentation failed structural validation")
            : new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_TEMPLATE_OUTPUT_INVALID", "Generated presentation failed structural validation", cause);
    }
}
