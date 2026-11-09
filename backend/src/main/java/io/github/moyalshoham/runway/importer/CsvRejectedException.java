package io.github.moyalshoham.runway.importer;

import java.util.List;

/** The file as a whole cannot be imported: not CSV, wrong encoding, missing columns, or invalid rows. */
public class CsvRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<RowError> errors;

    public CsvRejectedException(String message, List<RowError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public CsvRejectedException(String message) {
        this(message, List.of());
    }

    public List<RowError> errors() {
        return errors;
    }
}
