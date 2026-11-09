package io.github.moyalshoham.runway.api;

import io.github.moyalshoham.runway.api.Dtos.ImportResponse;
import io.github.moyalshoham.runway.api.Dtos.RowErrorDto;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.importer.CsvRejectedException;
import io.github.moyalshoham.runway.service.ImportService;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV uploads. The file is parsed in memory (bounded by the multipart size limit) and never written
 * to disk; only the sanitised filename is kept, in the import log.
 */
@RestController
@RequestMapping(value = "/api/companies/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/transactions/import")
    public ImportResponse transactions(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean skipInvalid) {
        return response(imports.importTransactions(id, file.getOriginalFilename(), bytes(file), skipInvalid));
    }

    @PostMapping("/invoices/import")
    public ImportResponse invoices(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean skipInvalid) {
        return response(imports.importDocuments(
                id, DocumentType.INVOICE, file.getOriginalFilename(), bytes(file), skipInvalid));
    }

    @PostMapping("/bills/import")
    public ImportResponse bills(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean skipInvalid) {
        return response(
                imports.importDocuments(id, DocumentType.BILL, file.getOriginalFilename(), bytes(file), skipInvalid));
    }

    private static byte[] bytes(MultipartFile file) {
        if (file.isEmpty()) {
            throw new CsvRejectedException("The file is empty");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ImportResponse response(ImportService.Result r) {
        return new ImportResponse(
                r.rowsRead(),
                r.imported(),
                r.skipped(),
                r.updated(),
                r.rejected().stream()
                        .map(e -> new RowErrorDto(e.line(), e.message()))
                        .toList());
    }
}
