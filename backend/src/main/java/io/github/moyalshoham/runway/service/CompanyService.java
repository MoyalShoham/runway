package io.github.moyalshoham.runway.service;

import io.github.moyalshoham.runway.engine.domain.Money;
import io.github.moyalshoham.runway.persistence.CompanyRepository;
import io.github.moyalshoham.runway.persistence.CompanyRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private final CompanyRepository companies;

    public CompanyService(CompanyRepository companies) {
        this.companies = companies;
    }

    static final LocalDate MIN_OPENING = LocalDate.of(1990, 1, 1);
    static final LocalDate MAX_OPENING = LocalDate.of(2100, 12, 31);

    @Transactional
    public CompanyRow create(String name, BigDecimal openingBalance, LocalDate openingDate, BigDecimal minimumBalance) {
        if (openingDate.isBefore(MIN_OPENING) || openingDate.isAfter(MAX_OPENING)) {
            throw new IllegalArgumentException("openingDate must be between " + MIN_OPENING + " and " + MAX_OPENING);
        }
        long id = companies.insert(name.strip(), Money.round(openingBalance), openingDate, Money.round(minimumBalance));
        return get(id);
    }

    public CompanyRow get(long id) {
        return companies.find(id).orElseThrow(() -> new NotFoundException("No company " + id));
    }

    public List<CompanyRow> list() {
        return companies.findAll();
    }

    @Transactional
    public CompanyRow update(long id, String name, BigDecimal minimumBalance) {
        CompanyRow c = get(id);
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        companies.update(
                id,
                name == null ? c.name() : name.strip(),
                minimumBalance == null ? c.minimumBalance() : Money.round(minimumBalance));
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        if (!companies.delete(id)) {
            throw new NotFoundException("No company " + id);
        }
    }
}
