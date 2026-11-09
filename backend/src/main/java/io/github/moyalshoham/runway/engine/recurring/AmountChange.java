package io.github.moyalshoham.runway.engine.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A step change in a recurring amount, e.g. a rent increase. */
public record AmountChange(BigDecimal from, BigDecimal to, LocalDate since) {}
