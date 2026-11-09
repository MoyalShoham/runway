package io.github.moyalshoham.runway.persistence;

import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Created and updated counts of an upsert. */
    public record Upserted(int created, int updated) {}

    /**
     * Inserts documents, or updates them when the (type, number) already exists, so re-importing an
     * export after an invoice was paid records the payment.
     */
    public Upserted upsert(long companyId, List<Document> docs) {
        int created = 0;
        int updated = 0;
        for (Document d : docs) {
            Boolean inserted = jdbc.sql(
                            """
                            insert into document (company_id, type, number, counterparty, issue_date, due_date, amount, paid_date)
                            values (?, ?, ?, ?, ?, ?, ?, ?)
                            on conflict (company_id, type, number) do update set
                                counterparty = excluded.counterparty,
                                issue_date = excluded.issue_date,
                                due_date = excluded.due_date,
                                amount = excluded.amount,
                                paid_date = excluded.paid_date
                            returning (xmax = 0) as inserted
                            """)
                    .params(
                            companyId,
                            d.type().name(),
                            d.number(),
                            d.counterparty(),
                            d.issueDate(),
                            d.dueDate(),
                            d.amount(),
                            d.paidDate())
                    .query(Boolean.class)
                    .single();
            if (Boolean.TRUE.equals(inserted)) {
                created++;
            } else {
                updated++;
            }
        }
        return new Upserted(created, updated);
    }

    public List<Document> findAll(long companyId) {
        return jdbc.sql("select type, number, counterparty, issue_date, due_date, amount, paid_date from document "
                        + "where company_id = ? order by issue_date, id")
                .param(companyId)
                .query((rs, i) -> new Document(
                        DocumentType.valueOf(rs.getString("type")),
                        rs.getString("number"),
                        rs.getString("counterparty"),
                        rs.getObject("issue_date", LocalDate.class),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("amount"),
                        rs.getObject("paid_date", LocalDate.class)))
                .list();
    }
}
