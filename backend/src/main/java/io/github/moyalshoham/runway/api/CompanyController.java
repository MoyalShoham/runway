package io.github.moyalshoham.runway.api;

import io.github.moyalshoham.runway.api.Dtos.CompanyRequest;
import io.github.moyalshoham.runway.api.Dtos.CompanyResponse;
import io.github.moyalshoham.runway.api.Dtos.CompanyUpdate;
import io.github.moyalshoham.runway.api.Dtos.DocumentDto;
import io.github.moyalshoham.runway.api.Dtos.TransactionDto;
import io.github.moyalshoham.runway.persistence.CompanyRow;
import io.github.moyalshoham.runway.persistence.DocumentRepository;
import io.github.moyalshoham.runway.persistence.TransactionRepository;
import io.github.moyalshoham.runway.service.CompanyService;
import io.github.moyalshoham.runway.service.DemoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api")
public class CompanyController {

    private final CompanyService companies;
    private final DemoService demo;
    private final TransactionRepository transactions;
    private final DocumentRepository documents;

    public CompanyController(
            CompanyService companies,
            DemoService demo,
            TransactionRepository transactions,
            DocumentRepository documents) {
        this.companies = companies;
        this.demo = demo;
        this.transactions = transactions;
        this.documents = documents;
    }

    @GetMapping("/companies")
    public List<CompanyResponse> list() {
        return companies.list().stream().map(this::response).toList();
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@Valid @RequestBody CompanyRequest r) {
        return response(companies.create(r.name(), r.openingBalance(), r.openingDate(), r.minimumBalance()));
    }

    @GetMapping("/companies/{id}")
    public CompanyResponse get(@PathVariable long id) {
        return response(companies.get(id));
    }

    @PatchMapping("/companies/{id}")
    public CompanyResponse update(@PathVariable long id, @Valid @RequestBody CompanyUpdate r) {
        return response(companies.update(id, r.name(), r.minimumBalance()));
    }

    @DeleteMapping("/companies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        companies.delete(id);
    }

    /** Creates a synthetic company with three years of history ending today. */
    @PostMapping("/demo")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse demo(@RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) long seed) {
        return response(demo.create(seed));
    }

    @GetMapping("/companies/{id}/transactions")
    public List<TransactionDto> transactions(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        CompanyRow c = companies.get(id);
        LocalDate end = to != null ? to : transactions.latestDate(id).orElse(c.openingDate());
        LocalDate start = from != null ? from : end.minusDays(90);
        return transactions.page(id, start, end, limit).stream()
                .map(t -> new TransactionDto(t.id(), t.bookingDate(), t.amount(), t.description(), t.counterparty()))
                .toList();
    }

    @GetMapping("/companies/{id}/documents")
    public List<DocumentDto> documents(@PathVariable long id, @RequestParam(defaultValue = "false") boolean openOnly) {
        companies.get(id);
        return documents.findAll(id).stream()
                .filter(d -> !openOnly || d.paidDate() == null)
                .map(d -> new DocumentDto(
                        d.type().name(),
                        d.number(),
                        d.counterparty(),
                        d.issueDate(),
                        d.dueDate(),
                        d.amount(),
                        d.paidDate()))
                .toList();
    }

    private CompanyResponse response(CompanyRow c) {
        return new CompanyResponse(
                c.id(),
                c.name(),
                c.currency(),
                c.openingBalance(),
                c.openingDate(),
                c.minimumBalance(),
                transactions.count(c.id()),
                transactions.latestDate(c.id()).orElse(null));
    }
}
