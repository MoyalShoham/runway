package io.github.moyalshoham.runway.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CompanyRow(
        long id,
        String name,
        String currency,
        BigDecimal openingBalance,
        LocalDate openingDate,
        BigDecimal minimumBalance,
        OffsetDateTime createdAt) {}
