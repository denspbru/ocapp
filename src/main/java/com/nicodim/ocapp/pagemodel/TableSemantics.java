package com.nicodim.ocapp.pagemodel;

import java.util.List;

/** Bounded browser-derived table geometry and supported cell presentation. */
public record TableSemantics(int rows, int columns, List<Double> columnWidths,
                             List<Double> rowHeights, int headerRows, List<Cell> cells) {
    public TableSemantics {
        columnWidths = List.copyOf(columnWidths == null ? List.of() : columnWidths);
        rowHeights = List.copyOf(rowHeights == null ? List.of() : rowHeights);
        cells = List.copyOf(cells == null ? List.of() : cells);
    }

    /** Compatibility constructor for callers which do not have browser geometry. */
    public TableSemantics(int rows, int columns, List<Cell> cells) {
        this(rows, columns, List.of(), List.of(), 0, cells);
    }

    public record Cell(int row, int column, int rowSpan, int columnSpan, boolean header, String text,
                       String fillColor, String textColor, String fontFamily, double fontSize,
                       int fontWeight, String textAlign, String verticalAlign,
                       Border top, Border right, Border bottom, Border left) {
        public Cell {
            text = safe(text); fillColor = safe(fillColor); textColor = safe(textColor);
            fontFamily = safe(fontFamily); textAlign = safe(textAlign); verticalAlign = safe(verticalAlign);
            top = top == null ? Border.none() : top; right = right == null ? Border.none() : right;
            bottom = bottom == null ? Border.none() : bottom; left = left == null ? Border.none() : left;
        }

        /** Compatibility constructor for semantic-only cells. */
        public Cell(int row, int column, int rowSpan, int columnSpan, boolean header, String text) {
            this(row, column, rowSpan, columnSpan, header, text, "transparent", "black", "Arial", 12,
                header ? 700 : 400, "left", "middle", Border.none(), Border.none(), Border.none(), Border.none());
        }
    }

    public record Border(double widthPixels, String color, String style) {
        public Border { color = safe(color); style = safe(style); }
        public static Border none() { return new Border(0, "transparent", "none"); }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
