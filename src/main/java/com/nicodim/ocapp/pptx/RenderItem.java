package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.pagemodel.AssetReference;
import java.net.URI;
import java.util.List;

/**
 * Renderer-independent item for one already-paginated slide. Coordinates remain in full-page CSS
 * pixels until {@link CssCoordinateMapper} maps them to the target slide.
 */
public sealed interface RenderItem {
    Rect bounds();

    /** A leaf text block rendered as an editable PPTX text box. */
    record NativeText(Rect bounds, String textAlign, double lineHeightPixels,
                      String backgroundColorCss, List<TextSpan> spans) implements RenderItem {
        public NativeText { spans = List.copyOf(spans); }
    }

    /** A simple opaque/semitransparent-free CSS background rendered as an editable rectangle. */
    record NativeBackground(Rect bounds, String colorCss) implements RenderItem { }

    /** A safe embedded raster image rendered as a native PPTX picture. */
    record NativeImage(Rect bounds, AssetReference asset) implements RenderItem { }

    /** A visually ambiguous region rendered from the bounded full-page screenshot. */
    record ScreenshotCrop(Rect bounds) implements RenderItem { }

    /** One styled inline text run within a native text box. */
    record TextSpan(String text, String fontFamily, double fontSizePoints, int fontWeight,
                    boolean italic, String colorCss, URI hyperlink) { }

    /** Axis-aligned rectangle in full-page CSS pixels. */
    record Rect(double x, double y, double width, double height) {
        public Rect {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0 || height <= 0) throw new IllegalArgumentException("Render bounds must be finite and positive");
        }
        public double bottom() { return y + height; }
        public double right() { return x + width; }
        public boolean intersects(Rect other) {
            return x < other.right() && right() > other.x() && y < other.bottom() && bottom() > other.y();
        }
        public Rect union(Rect other) {
            double left = Math.min(x, other.x()), top = Math.min(y, other.y());
            return new Rect(left, top, Math.max(right(), other.right()) - left,
                Math.max(bottom(), other.bottom()) - top);
        }
    }
}
