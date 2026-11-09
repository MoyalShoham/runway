package io.github.moyalshoham.runway.service;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.importer.CsvParser;
import io.github.moyalshoham.runway.importer.CsvRejectedException;
import io.github.moyalshoham.runway.importer.Parsed;
import io.github.moyalshoham.runway.importer.RowError;
import io.github.moyalshoham.runway.persistence.CompanyRow;
import io.github.moyalshoham.runway.persistence.DocumentRepository;
import io.github.moyalshoham.runway.persistence.ImportBatchRepository;
import io.github.moyalshoham.runway.persistence.TransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports CSV files. By default a file with any invalid row is rejected as a whole, with every error
 * listed, so a half-imported bank statement never silently skews a forecast; {@code skipInvalid}
 * imports the valid rows and reports the rest.
 */
@Service
public class ImportService {

    /** Outcome of an import. {@code skipped} are duplicates (transactions) or updates (documents). */
    public record Result(int rowsRead, int imported, int skipped, int updated, List<RowError> rejected) {}

    private final CompanyService companies;
    private final TransactionRepository transactions;
    private final DocumentRepository documents;
    private final ImportBatchRepository batches;

    public ImportService(
            CompanyService companies,
            TransactionRepository transactions,
            DocumentRepository documents,
            ImportBatchRepository batches) {
        this.companies = companies;
        this.transactions = transactions;
        this.documents = documents;
        this.batches = batches;
    }

    @Transactional
    public Result importTransactions(long companyId, String filename, byte[] content, boolean skipInvalid) {
        CompanyRow company = companies.get(companyId);
        Parsed<BankTransaction> parsed = CsvParser.transactions(content);
        List<RowError> errors = new ArrayList<>(parsed.errors());
        List<BankTransaction> valid = new ArrayList<>();
        for (Parsed.Line<BankTransaction> line : parsed.rows()) {
            if (!line.value().date().isAfter(company.openingDate())) {
                errors.add(new RowError(
                        line.line(), "date is on or before the opening-balance date " + company.openingDate()));
            } else {
                valid.add(line.value());
            }
        }
        int read = parsed.rows().size() + parsed.errors().size();
        rejectIfNeeded(errors, skipInvalid);
        int inserted = transactions.insertIgnoringDuplicates(companyId, valid);
        batches.record(companyId, "TRANSACTIONS", safeName(filename), read, inserted, valid.size() - inserted);
        return new Result(read, inserted, valid.size() - inserted, 0, errors);
    }

    @Transactional
    public Result importDocuments(
            long companyId, DocumentType type, String filename, byte[] content, boolean skipInvalid) {
        companies.get(companyId);
        Parsed<Document> parsed = CsvParser.documents(content, type);
        int read = parsed.rows().size() + parsed.errors().size();
        rejectIfNeeded(parsed.errors(), skipInvalid);
        DocumentRepository.Upserted result = documents.upsert(
                companyId, parsed.rows().stream().map(Parsed.Line::value).toList());
        batches.record(companyId, type.name(), safeName(filename), read, result.created(), result.updated());
        return new Result(read, result.created(), 0, result.updated(), parsed.errors());
    }

    private static void rejectIfNeeded(List<RowError> errors, boolean skipInvalid) {
        if (!errors.isEmpty() && !skipInvalid) {
            throw new CsvRejectedException(
                    errors.size() + " invalid row(s); nothing was imported. Fix them, or import with skipInvalid=true.",
                    errors);
        }
    }

    /** The client-supplied filename is only stored for the audit log; keep it short and printable. */
    static String safeName(String filename) {
        String s = filename == null ? "upload.csv" : filename.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
