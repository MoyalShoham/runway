package io.github.moyalshoham.runway.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ImportBatchRepository {

    private final JdbcClient jdbc;

    public ImportBatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long companyId, String kind, String filename, int read, int imported, int skipped) {
        jdbc.sql("insert into import_batch (company_id, kind, filename, rows_read, rows_imported, rows_skipped) "
                        + "values (?, ?, ?, ?, ?, ?)")
                .params(companyId, kind, filename, read, imported, skipped)
                .update();
    }
}
