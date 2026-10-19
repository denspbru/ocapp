package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.pagination.SlideSlice;
import java.awt.geom.Rectangle2D;

/** Maps full-page CSS pixels to PowerPoint points with one uniform, deterministic scale. */
public final class CssCoordinateMapper {
    private final SlideSlice slice;
    private final double scale;
    private final Rectangle2D contentBox;

    public CssCoordinateMapper(SlideSlice slice) {
        this(slice, new Rectangle2D.Double(0, 0, slice.viewport().widthPoints(), slice.viewport().heightPoints()));
    }

    public CssCoordinateMapper(SlideSlice slice, Rectangle2D contentBox) {
        this.slice = java.util.Objects.requireNonNull(slice, "slice");
        this.contentBox = (Rectangle2D) java.util.Objects.requireNonNull(contentBox, "contentBox").clone();
        scale = this.contentBox.getWidth() / slice.source().width();
        if (!Double.isFinite(scale) || scale <= 0
            || Math.abs(slice.viewport().widthPoints() - this.contentBox.getWidth()) > .01
            || Math.abs(slice.viewport().heightPoints() - this.contentBox.getHeight()) > .01)
            throw new IllegalArgumentException("Coordinate scale is invalid");
    }

    public double pointsPerCssPixel() { return scale; }

    public Rectangle2D map(RenderItem.Rect bounds) {
        double x = contentBox.getX() + (bounds.x() - slice.source().x()) * scale;
        double y = contentBox.getY() + (bounds.y() - slice.source().y()) * scale;
        Rectangle2D mapped = new Rectangle2D.Double(x, y, bounds.width() * scale, bounds.height() * scale);
        double epsilon = 0.01;
        if (x < contentBox.getX() - epsilon || y < contentBox.getY() - epsilon || mapped.getMaxX() > contentBox.getMaxX() + epsilon
            || mapped.getMaxY() > contentBox.getMaxY() + epsilon) {
            throw new IllegalArgumentException("Mapped shape is outside the slide viewport");
        }
        return mapped;
    }
}
