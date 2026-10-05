package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.browser.BoundedByteArrayOutputStream;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.AssetReference;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagemodel.TableSemantics;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TableCell.BorderEdge;
import org.apache.poi.sl.usermodel.StrokeStyle;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFHyperlink;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.http.HttpStatus;

/** Apache POI renderer for the deterministic M3 render plan. */
public final class HybridPptxRenderer {
    private final ConverterProperties properties;
    private final EditableRenderPlanner planner = new EditableRenderPlanner();

    public HybridPptxRenderer(ConverterProperties properties) { this.properties = properties; }

    public byte[] render(BufferedImage screenshot, PageModel model, PaginationPlan plan) {
        validateScreenshotMapping(screenshot, model, plan);
        int maxItems = properties.getPptx().getMaxItemsPerSlide();
        List<List<RenderItem>> slideItems = new ArrayList<>(plan.slices().size());
        long cropPixels = 0;
        for (SlideSlice slice : plan.slices()) {
            List<RenderItem> items = planner.plan(model, slice, maxItems, properties.getPptx().getMaxNativeTableCells(),
                properties.getPptx().getMaxNativeTableColumns(), properties.getPptx().getMinNativeTableColumnPoints());
            if (items.size() > maxItems) throw tooLarge("PPTX shape count exceeds the configured limit");
            for (RenderItem item : items) if (item instanceof RenderItem.ScreenshotCrop crop) {
                PixelRect pixels = pixelRect(crop.bounds(), screenshot, model);
                cropPixels = safeAdd(cropPixels, (long) pixels.width() * pixels.height());
                if (cropPixels > properties.getLimits().getMaxCapturePixels())
                    throw tooLarge("Localized fallback pixels exceed the configured limit");
            }
            slideItems.add(items);
        }

        var ppt = properties.getPptx();
        try (XMLSlideShow show = new XMLSlideShow();
             BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(properties.getLimits().getMaxOutputBytes())) {
            int slideWidth = (int) Math.round(ppt.getSlideWidthInches() * 72);
            int slideHeight = (int) Math.round(ppt.getSlideHeightInches() * 72);
            show.setPageSize(new Dimension(slideWidth, slideHeight));
            Map<String, NativePicture> nativePictures = new HashMap<>();
            long[] assetBytes = {0};
            for (int index = 0; index < plan.slices().size(); index++) {
                SlideSlice slice = plan.slices().get(index);
                XSLFSlide slide = show.createSlide();
                CssCoordinateMapper mapper = new CssCoordinateMapper(slice);
                for (RenderItem item : slideItems.get(index)) {
                    switch (item) {
                        case RenderItem.NativeBackground background -> addBackground(slide, mapper, background);
                        case RenderItem.ScreenshotCrop crop -> addCrop(show, slide, mapper, screenshot, model, crop);
                        case RenderItem.NativeText text -> addText(slide, mapper, text);
                        case RenderItem.NativeListItem list -> addList(slide, mapper, list);
                        case RenderItem.NativeTable table -> addTable(slide, mapper, table);
                        case RenderItem.NativeImage image -> addImage(show, slide, mapper, image, nativePictures, assetBytes);
                    }
                }
            }
            show.write(output);
            return output.toByteArray();
        } catch (ConversionException ex) { throw ex; }
        catch (IOException | IllegalArgumentException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_CREATION_FAILED",
                "Editable presentation could not be created", ex);
        }
    }

    private static void addBackground(XSLFSlide slide, CssCoordinateMapper mapper, RenderItem.NativeBackground background) {
        Color parsed = CssColors.parse(background.colorCss());
        if (parsed == null || parsed.getAlpha() != 255) throw new IllegalArgumentException("Background color is invalid");
        Color color = new Color(parsed.getRed(), parsed.getGreen(), parsed.getBlue());
        var shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(mapper.map(background.bounds()));
        shape.setFillColor(color);
        shape.setLineColor(color);
        shape.setLineWidth(0);
    }

    private void addCrop(XMLSlideShow show, XSLFSlide slide, CssCoordinateMapper mapper, BufferedImage screenshot,
                         PageModel model, RenderItem.ScreenshotCrop crop) throws IOException {
        PixelRect pixels = pixelRect(crop.bounds(), screenshot, model);
        BufferedImage view = screenshot.getSubimage(pixels.x(), pixels.y(), pixels.width(), pixels.height());
        try (BoundedByteArrayOutputStream png = new BoundedByteArrayOutputStream(properties.getLimits().getMaxScreenshotBytes())) {
            if (!ImageIO.write(view, "png", png)) throw new IOException("PNG encoder is unavailable");
            XSLFPictureData data = show.addPicture(png.toByteArray(), PictureData.PictureType.PNG);
            XSLFPictureShape shape = slide.createPicture(data);
            shape.setAnchor(mapper.map(crop.bounds()));
        }
    }

    private static void addText(XSLFSlide slide, CssCoordinateMapper mapper, RenderItem.NativeText text) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(mapper.map(text.bounds()));
        box.setLeftInset(0); box.setRightInset(0); box.setTopInset(0); box.setBottomInset(0);
        box.setVerticalAlignment(VerticalAlignment.TOP);
        box.setWordWrap(text.bounds().height() > text.lineHeightPixels() * 1.4);
        Color background = CssColors.parse(text.backgroundColorCss());
        if (background != null && background.getAlpha() > 0) box.setFillColor(new Color(background.getRed(), background.getGreen(), background.getBlue()));
        box.clearText();
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(alignment(text.textAlign()));
        paragraph.setSpaceBefore(0d); paragraph.setSpaceAfter(0d);
        RenderItem.TextSpan first = text.spans().getFirst();
        double fontPixels = first.fontSizePoints() * 96d / 72d;
        paragraph.setLineSpacing(Math.max(1d, text.lineHeightPixels() / fontPixels * 100d));
        for (RenderItem.TextSpan span : text.spans()) {
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText(span.text());
            String family = firstFont(span.fontFamily());
            if (!family.isBlank()) run.setFontFamily(family);
            run.setFontSize(span.fontSizePoints());
            run.setBold(span.fontWeight() >= 600);
            run.setItalic(span.italic());
            Color color = CssColors.parse(span.colorCss());
            if (color != null) run.setFontColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
            if (span.hyperlink() != null) {
                XSLFHyperlink hyperlink = run.createHyperlink();
                hyperlink.setAddress(span.hyperlink().toASCIIString());
            }
        }
    }

    private static void addList(XSLFSlide slide, CssCoordinateMapper mapper, RenderItem.NativeListItem item) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(mapper.map(item.bounds()));
        box.setLeftInset(0); box.setRightInset(0); box.setTopInset(0); box.setBottomInset(0);
        box.setVerticalAlignment(VerticalAlignment.TOP); box.setWordWrap(true); box.clearText();
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(alignment(item.textAlign())); paragraph.setSpaceBefore(0d); paragraph.setSpaceAfter(0d);
        paragraph.setIndentLevel(item.level());
        double margin = 18d + item.level() * 18d;
        paragraph.setLeftMargin(margin); paragraph.setIndent(-12d);
        if (item.ordered()) paragraph.setBulletAutoNumber(numbering(item.marker()), Math.max(1, item.start()));
        else if (!"none".equalsIgnoreCase(item.marker())) {
            paragraph.setBullet(true); paragraph.setBulletCharacter(bulletCharacter(item.marker()));
        }
        applySpans(paragraph, item.spans(), item.lineHeightPixels());
    }

    private static void addTable(XSLFSlide slide, CssCoordinateMapper mapper, RenderItem.NativeTable item) {
        TableSemantics semantics = item.table();
        XSLFTable table = slide.createTable(item.rows().size(), semantics.columns());
        Rectangle2D anchor = mapper.map(item.bounds());
        table.setAnchor(anchor);
        List<Double> widths = semantics.columnWidths().size() == semantics.columns() ? semantics.columnWidths()
            : java.util.Collections.nCopies(semantics.columns(), 1d);
        double widthTotal = widths.stream().mapToDouble(Double::doubleValue).sum();
        for (int column = 0; column < semantics.columns(); column++)
            table.setColumnWidth(column, anchor.getWidth() * widths.get(column) / widthTotal);
        List<Double> heights = semantics.rowHeights().size() == semantics.rows() ? semantics.rowHeights()
            : java.util.Collections.nCopies(semantics.rows(), 1d);
        double heightTotal = item.rows().stream().mapToDouble(heights::get).sum();
        for (int row = 0; row < item.rows().size(); row++)
            table.setRowHeight(row, anchor.getHeight() * heights.get(item.rows().get(row)) / heightTotal);
        Map<Integer,Integer> rowMap = new HashMap<>();
        for (int row = 0; row < item.rows().size(); row++) rowMap.put(item.rows().get(row), row);
        for (TableSemantics.Cell source : semantics.cells()) {
            Integer row = rowMap.get(source.row());
            if (row == null) continue;
            boolean spanPresent = true;
            for (int r = source.row(); r < source.row() + source.rowSpan(); r++) spanPresent &= rowMap.containsKey(r);
            if (!spanPresent) continue;
            XSLFTableCell cell = table.getCell(row, source.column());
            cell.setText(source.text()); cell.setLeftInset(3); cell.setRightInset(3); cell.setTopInset(2); cell.setBottomInset(2);
            Color fill = CssColors.parse(source.fillColor());
            if (fill != null && fill.getAlpha() > 0) cell.setFillColor(opaque(fill));
            cell.setVerticalAlignment(vertical(source.verticalAlign()));
            XSLFTextParagraph paragraph = cell.getTextParagraphs().getFirst();
            paragraph.setTextAlign(alignment(source.textAlign())); paragraph.setSpaceBefore(0d); paragraph.setSpaceAfter(0d);
            for (XSLFTextRun run : paragraph.getTextRuns()) {
                run.setFontFamily(firstFont(source.fontFamily())); run.setFontSize(source.fontSize() * 72d / 96d);
                run.setBold(source.fontWeight() >= 600); Color color = CssColors.parse(source.textColor());
                if (color != null) run.setFontColor(opaque(color));
            }
            applyBorder(cell, BorderEdge.top, source.top()); applyBorder(cell, BorderEdge.right, source.right());
            applyBorder(cell, BorderEdge.bottom, source.bottom()); applyBorder(cell, BorderEdge.left, source.left());
            if (source.rowSpan() > 1 || source.columnSpan() > 1)
                table.mergeCells(row, row + source.rowSpan() - 1, source.column(), source.column() + source.columnSpan() - 1);
        }
    }

    private static void applySpans(XSLFTextParagraph paragraph, List<RenderItem.TextSpan> spans, double lineHeightPixels) {
        RenderItem.TextSpan first = spans.getFirst();
        double fontPixels = first.fontSizePoints() * 96d / 72d;
        paragraph.setLineSpacing(Math.max(1d, lineHeightPixels / fontPixels * 100d));
        for (RenderItem.TextSpan span : spans) {
            XSLFTextRun run = paragraph.addNewTextRun(); run.setText(span.text()); String family = firstFont(span.fontFamily());
            if (!family.isBlank()) run.setFontFamily(family); run.setFontSize(span.fontSizePoints());
            run.setBold(span.fontWeight() >= 600); run.setItalic(span.italic()); Color color = CssColors.parse(span.colorCss());
            if (color != null) run.setFontColor(opaque(color));
            if (span.hyperlink() != null) run.createHyperlink().setAddress(span.hyperlink().toASCIIString());
        }
    }

    private static void applyBorder(XSLFTableCell cell, BorderEdge edge, TableSemantics.Border border) {
        if (border.widthPixels() <= 0 || "none".equalsIgnoreCase(border.style()) || "hidden".equalsIgnoreCase(border.style())) {
            cell.removeBorder(edge); return;
        }
        cell.setBorderWidth(edge, border.widthPixels() * 72d / 96d);
        Color color = CssColors.parse(border.color()); if (color != null) cell.setBorderColor(edge, opaque(color));
        if ("dashed".equalsIgnoreCase(border.style())) cell.setBorderDash(edge, StrokeStyle.LineDash.DASH);
        else if ("dotted".equalsIgnoreCase(border.style())) cell.setBorderDash(edge, StrokeStyle.LineDash.DOT);
    }

    private static AutoNumberingScheme numbering(String marker) {
        return switch (marker == null ? "" : marker.toLowerCase(Locale.ROOT)) {
            case "lower-alpha" -> AutoNumberingScheme.alphaLcPeriod; case "upper-alpha" -> AutoNumberingScheme.alphaUcPeriod;
            case "lower-roman" -> AutoNumberingScheme.romanLcPeriod; case "upper-roman" -> AutoNumberingScheme.romanUcPeriod;
            default -> AutoNumberingScheme.arabicPeriod;
        };
    }
    private static String bulletCharacter(String marker) {
        return switch (marker == null ? "" : marker.toLowerCase(Locale.ROOT)) { case "circle" -> "○"; case "square" -> "■"; default -> "•"; };
    }
    private static VerticalAlignment vertical(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) { case "bottom" -> VerticalAlignment.BOTTOM; case "middle", "center" -> VerticalAlignment.MIDDLE; default -> VerticalAlignment.TOP; };
    }
    private static Color opaque(Color color) { return new Color(color.getRed(), color.getGreen(), color.getBlue()); }

    private void addImage(XMLSlideShow show, XSLFSlide slide, CssCoordinateMapper mapper, RenderItem.NativeImage image,
                          Map<String, NativePicture> cache, long[] totalAssetBytes) throws IOException {
        String key = image.asset().id() + "\n" + image.asset().uri();
        NativePicture picture = cache.get(key);
        if (picture == null) {
            if (cache.size() >= properties.getPageModel().getMaxAssets()) throw tooLarge("PPTX asset count exceeds the configured limit");
            byte[] bytes = decodeAsset(image.asset());
            totalAssetBytes[0] = safeAdd(totalAssetBytes[0], bytes.length);
            if (totalAssetBytes[0] > properties.getPageModel().getMaxAssetBytes())
                throw tooLarge("PPTX assets exceed the configured byte limit");
            ImageSize size = imageSize(bytes);
            XSLFPictureData data = show.addPicture(bytes, pictureType(image.asset()));
            picture = new NativePicture(data, size.width(), size.height());
            cache.put(key, picture);
        }
        Rectangle2D box = mapper.map(image.bounds());
        double scale = Math.min(box.getWidth() / picture.width(), box.getHeight() / picture.height());
        double width = picture.width() * scale, height = picture.height() * scale;
        Rectangle2D fitted = new Rectangle2D.Double(box.getX() + (box.getWidth() - width) / 2,
            box.getY() + (box.getHeight() - height) / 2, width, height);
        slide.createPicture(picture.data()).setAnchor(fitted);
    }

    private byte[] decodeAsset(AssetReference asset) {
        String value = asset.uri().toString();
        int comma = value.indexOf(',');
        if (comma < 0 || comma > 512 || !value.substring(0, comma).toLowerCase(Locale.ROOT).endsWith(";base64"))
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_ASSET_INVALID", "Embedded image is not bounded base64 data");
        String encoded = value.substring(comma + 1);
        long upperBound = ((long) encoded.length() + 3) / 4 * 3;
        long maximum = properties.getPageModel().getMaxAssetBytes();
        if (upperBound > maximum + 2) throw tooLarge("Embedded image exceeds the configured byte limit");
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > maximum) throw tooLarge("Embedded image exceeds the configured byte limit");
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "PPTX_ASSET_INVALID", "Embedded image is invalid", ex);
        }
    }

    private ImageSize imageSize(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IOException("Image reader input is unavailable");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Embedded image format is unsupported");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                var limits = properties.getLimits();
                if (width < 1 || height < 1 || width > limits.getMaxCaptureWidth() || height > limits.getMaxCaptureHeight()
                    || width > limits.getMaxCapturePixels() / (long) height) throw tooLarge("Embedded image dimensions exceed the configured limit");
                return new ImageSize(width, height);
            } finally { reader.dispose(); }
        }
    }

    private static PictureData.PictureType pictureType(AssetReference asset) {
        return switch (asset.mediaType().toLowerCase(Locale.ROOT)) {
            case "image/png" -> PictureData.PictureType.PNG;
            case "image/jpeg", "image/jpg" -> PictureData.PictureType.JPEG;
            case "image/gif" -> PictureData.PictureType.GIF;
            case "image/bmp" -> PictureData.PictureType.BMP;
            default -> throw new IllegalArgumentException("Unsupported native image type");
        };
    }

    private static TextParagraph.TextAlign alignment(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "center" -> TextParagraph.TextAlign.CENTER;
            case "right", "end" -> TextParagraph.TextAlign.RIGHT;
            case "justify" -> TextParagraph.TextAlign.JUSTIFY;
            default -> TextParagraph.TextAlign.LEFT;
        };
    }

    private static String firstFont(String value) {
        if (value == null) return "";
        String result = value.split(",", 2)[0].trim();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
            || (result.startsWith("'") && result.endsWith("'")))) result = result.substring(1, result.length() - 1);
        return result;
    }

    private static PixelRect pixelRect(RenderItem.Rect bounds, BufferedImage image, PageModel model) {
        double sx = image.getWidth() / model.geometry().width(), sy = image.getHeight() / model.geometry().height();
        int x1 = clamp((int) Math.floor(bounds.x() * sx), 0, image.getWidth() - 1);
        int y1 = clamp((int) Math.floor(bounds.y() * sy), 0, image.getHeight() - 1);
        int x2 = clamp((int) Math.ceil(bounds.right() * sx), x1 + 1, image.getWidth());
        int y2 = clamp((int) Math.ceil(bounds.bottom() * sy), y1 + 1, image.getHeight());
        return new PixelRect(x1, y1, x2 - x1, y2 - y1);
    }

    private static void validateScreenshotMapping(BufferedImage image, PageModel model, PaginationPlan plan) {
        if (image == null || model == null || plan == null || plan.sourceWidth() != model.geometry().width()
            || plan.sourceHeight() != model.geometry().height()) throw new IllegalArgumentException("Page model and pagination dimensions differ");
        double sx = image.getWidth() / plan.sourceWidth(), sy = image.getHeight() / plan.sourceHeight();
        double tolerance = Math.max(sx, sy) * 0.01 + 1d / Math.max(plan.sourceWidth(), plan.sourceHeight());
        if (!Double.isFinite(sx) || !Double.isFinite(sy) || sx <= 0 || sy <= 0 || Math.abs(sx - sy) > tolerance)
            throw new IllegalArgumentException("Screenshot scale differs from CSS layout scale");
    }

    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static long safeAdd(long left, long right) {
        if (right < 0 || left > Long.MAX_VALUE - right) throw tooLarge("PPTX resource accounting overflow");
        return left + right;
    }
    private static ConversionException tooLarge(String message) {
        return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "OUTPUT_TOO_LARGE", message);
    }
    private record PixelRect(int x, int y, int width, int height) { }
    private record ImageSize(int width, int height) { }
    private record NativePicture(XSLFPictureData data, int width, int height) { }
}
