package com.nicodim.ocapp.pagemodel;
import java.util.List;
public record TableSemantics(int rows, int columns, List<Cell> cells) {
    public TableSemantics { cells = List.copyOf(cells == null ? List.of() : cells); }
    public record Cell(int row, int column, int rowSpan, int columnSpan, boolean header, String text) { public Cell { text = text == null ? "" : text; } }
}
