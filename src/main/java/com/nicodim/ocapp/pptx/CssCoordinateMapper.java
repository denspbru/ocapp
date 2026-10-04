package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.pagination.SlideSlice;
import java.awt.geom.Rectangle2D;

/** Maps full-page CSS pixels to PowerPoint points with one uniform, deterministic scale. */
public final class CssCoordinateMapper {
    private final SlideSlice slice;
    private final double scale;

    public CssCoordinateMapper(SlideSlice slice) {
        this.slice = java.util.Objects.requireNonNull(slice, "slice");
        scale = slice.viewport().widthPoints() / slice.source().width();
        if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Coordinate scale is invalid");
    }

    public double pointsPerCssPixel() { return scale; }

    public Rectangle2D map(RenderItem.Rect bounds) {
        double x = (bounds.x() - slice.source().x()) * scale;
        double y = (bounds.y() - slice.source().y()) * scale;
        Rectangle2D mapped = new Rectangle2D.Double(x, y, bounds.width() * scale, bounds.height() * scale);
        double epsilon = 0.01;
        if (x < -epsilon || y < -epsilon || mapped.getMaxX() > slice.viewport().widthPoints() + epsilon
            || mapped.getMaxY() > slice.viewport().heightPoints() + epsilon) {
            throw new IllegalArgumentException("Mapped shape is outside the slide viewport");
        }
        return mapped;
    }
}
