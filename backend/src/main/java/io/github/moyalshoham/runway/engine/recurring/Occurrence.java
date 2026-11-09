package io.github.moyalshoham.runway.engine.recurring;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One observed instance of a possibly recurring flow; {@code amount} is positive. {@code sources}
 * are caller-defined ids (e.g. transaction indexes) so the caller can tell which inputs a pattern
 * explains.
 */
public record Occurrence(LocalDate date, BigDecimal amount, String label, List<Integer> sources) {

    public Occurrence {
        amount = Money.round(amount.abs());
        sources = List.copyOf(sources);
    }

    public Occurrence(LocalDate date, BigDecimal amount, String label) {
        this(date, amount, label, List.of());
    }
}
