package io.github.moyalshoham.runway.importer;

/** A rejected CSV row; {@code line} is the 1-based line number in the file (the header is line 1). */
public record RowError(long line, String message) {}
