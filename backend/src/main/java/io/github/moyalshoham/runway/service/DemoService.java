package io.github.moyalshoham.runway.service;

import io.github.moyalshoham.runway.persistence.CompanyRepository;
import io.github.moyalshoham.runway.persistence.CompanyRow;
import io.github.moyalshoham.runway.persistence.DocumentRepository;
import io.github.moyalshoham.runway.persistence.TransactionRepository;
import io.github.moyalshoham.runway.sim.CompanyGenerator;
import io.github.moyalshoham.runway.sim.SyntheticCompany;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads a generated company whose history ends today, so the app can be tried without real data. */
@Service
public class DemoService {

    private final CompanyRepository companies;
    private final TransactionRepository transactions;
    private final DocumentRepository documents;
    private final CompanyService companyService;
    private final Clock clock;
    private final CompanyGenerator generator = new CompanyGenerator();

    public DemoService(
            CompanyRepository companies,
            TransactionRepository transactions,
            DocumentRepository documents,
            CompanyService companyService,
            Clock clock) {
        this.companies = companies;
        this.transactions = transactions;
        this.documents = documents;
        this.companyService = companyService;
        this.clock = clock;
    }

    /** Generating a demo writes about 2,500 rows; one every few seconds is plenty. */
    static final long MIN_INTERVAL_MILLIS = 3_000;

    private long lastCreated;

    @Transactional
    public CompanyRow create(long seed) {
        synchronized (this) {
            long now = clock.millis();
            if (now - lastCreated < MIN_INTERVAL_MILLIS) {
                throw new TooManyRequestsException("A demo company was just created; wait a few seconds");
            }
            lastCreated = now;
        }
        LocalDate today = LocalDate.now(clock);
        SyntheticCompany c = generator.generate(seed, today.minusYears(3).plusDays(1), 3);
        long id = companies.insert(c.name() + " (demo)", c.openingBalance(), c.openingDate(), c.minimumBalance());
        transactions.insertIgnoringDuplicates(id, c.transactions());
        documents.upsert(id, c.documents());
        return companyService.get(id);
    }
}
