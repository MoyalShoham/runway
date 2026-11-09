package io.github.moyalshoham.runway.importer;

import java.util.List;

/** Valid rows with their 1-based line numbers, and the rows that were rejected. */
public record Parsed<T>(List<Line<T>> rows, List<RowError> errors) {

    public record Line<T>(long line, T value) {}

    public Parsed {
        rows = List.copyOf(rows);
        errors = List.copyOf(errors);
    }
}
