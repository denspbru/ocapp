package com.nicodim.ocapp.visual;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;

final class SyntheticVisualFixture {
    private SyntheticVisualFixture() { }

    static Path create(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.setPageSize(new Dimension(960, 540));
            XSLFSlide first = show.createSlide();
            rectangle(first, 0, 0, 960, 540, new Color(248, 250, 252));
            text(first, 54, 42, 850, 54, "M8 deterministic text fixture", 28, true, new Color(23, 54, 93));
            text(first, 54, 108, 850, 36, "Native text, table geometry, vector and raster assets", 16, false, new Color(60, 72, 88));
            XSLFTable table = first.createTable(3, 3);
            table.setAnchor(new Rectangle2D.Double(54, 174, 852, 192));
            String[][] cells = {{"Metric", "Baseline", "Candidate"}, {"Slides", "2", "2"}, {"DPI", "144", "144"}};
            for (int row = 0; row < cells.length; row++) for (int column = 0; column < cells[row].length; column++) {
                var cell = table.getCell(row, column);
                cell.setFillColor(row == 0 ? new Color(30, 90, 150) : (row % 2 == 0 ? new Color(230, 238, 246) : Color.WHITE));
                var paragraph = cell.addNewTextParagraph();
                var run = paragraph.addNewTextRun(); run.setText(cells[row][column]); run.setFontFamily("Liberation Sans"); run.setFontSize(14d);
                run.setFontColor(row == 0 ? Color.WHITE : new Color(25, 35, 45)); run.setBold(row == 0);
                paragraph.setTextAlign(TextParagraph.TextAlign.CENTER);
            }
            for (int column = 0; column < 3; column++) table.setColumnWidth(column, 284);
            for (int row = 0; row < 3; row++) table.setRowHeight(row, 64);
            text(first, 54, 405, 852, 70, "Approved baselines are immutable inputs. Failures retain candidate, diff, and bounded metadata under target.",
                17, false, new Color(25, 35, 45));

            XSLFSlide second = show.createSlide();
            rectangle(second, 0, 0, 960, 540, Color.WHITE);
            text(second, 54, 38, 852, 48, "Mixed graphics fixture", 27, true, new Color(23, 54, 93));
            XSLFAutoShape vector = second.createAutoShape();
            vector.setShapeType(ShapeType.HEXAGON); vector.setAnchor(new Rectangle2D.Double(88, 130, 260, 260));
            vector.setFillColor(new Color(38, 132, 122)); vector.setLineColor(new Color(13, 83, 75)); vector.setLineWidth(4);
            byte[] png = raster();
            var pictureData = show.addPicture(png, PictureData.PictureType.PNG);
            second.createPicture(pictureData).setAnchor(new Rectangle2D.Double(550, 130, 260, 260));
            text(second, 88, 420, 260, 34, "Editable vector", 18, true, new Color(13, 83, 75));
            text(second, 550, 420, 260, 34, "Localized raster", 18, true, new Color(123, 50, 30));
            show.getProperties().getCustomProperties().addProperty("ocapp.visual-fixture.contract", "1");
            try (var output = Files.newOutputStream(target)) { show.write(output); }
        }
        return target;
    }

    private static void rectangle(XSLFSlide slide, double x, double y, double width, double height, Color color) {
        XSLFAutoShape shape = slide.createAutoShape(); shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(x, y, width, height)); shape.setFillColor(color); shape.setLineColor(color);
    }
    private static void text(XSLFSlide slide, double x, double y, double width, double height, String value,
                             double size, boolean bold, Color color) {
        XSLFAutoShape shape = slide.createAutoShape(); shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(x, y, width, height)); shape.setFillColor(new Color(255, 255, 255, 0)); shape.setLineColor(new Color(255,255,255,0));
        var paragraph = shape.addNewTextParagraph(); var run = paragraph.addNewTextRun(); run.setText(value);
        run.setFontFamily("Liberation Sans"); run.setFontSize(size); run.setBold(bold); run.setFontColor(color);
    }
    private static byte[] raster() throws IOException {
        BufferedImage image = new BufferedImage(260, 260, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(255, 244, 230)); graphics.fillRect(0, 0, 260, 260);
        graphics.setColor(new Color(220, 92, 52)); graphics.fillOval(30, 30, 200, 200);
        graphics.setColor(new Color(255, 220, 110)); graphics.fillOval(80, 80, 100, 100); graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
        return output.toByteArray();
    }
}
