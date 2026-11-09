package io.github.moyalshoham.runway.persistence;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public TransactionRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** A stored transaction with its id, for listing. */
    public record Row(long id, LocalDate bookingDate, BigDecimal amount, String description, String counterparty) {}

    /**
     * Inserts the transactions, skipping any already stored (same fingerprint). Returns how many were
     * new. Two identical rows in one file are both kept: the occurrence number is part of the
     * fingerprint, so re-importing the same file adds nothing, while a genuine repeat does count.
     */
    public int insertIgnoringDuplicates(long companyId, List<BankTransaction> txs) {
        List<Object[]> batch = new ArrayList<>(txs.size());
        Map<String, Integer> occurrences = new HashMap<>();
        for (BankTransaction t : txs) {
            String content =
                    t.date() + "|" + t.amount().toPlainString() + "|" + t.description() + "|" + t.counterparty();
            int n = occurrences.merge(content, 1, Integer::sum);
            batch.add(new Object[] {
                companyId,
                Date.valueOf(t.date()),
                t.amount(),
                t.description(),
                t.counterparty(),
                sha256(content + "|" + n)
            });
        }
        int inserted = 0;
        for (int[] chunk : template.batchUpdate(
                "insert into bank_transaction (company_id, booking_date, amount, description, counterparty, fingerprint) "
                        + "values (?, ?, ?, ?, ?, ?) on conflict (company_id, fingerprint) do nothing",
                batch,
                1000,
                (ps, a) -> {
                    for (int i = 0; i < a.length; i++) {
                        ps.setObject(i + 1, a[i]);
                    }
                })) {
            for (int c : chunk) {
                inserted += Math.max(0, c);
            }
        }
        return inserted;
    }

    public List<BankTransaction> findAll(long companyId) {
        return jdbc.sql("select booking_date, amount, description, counterparty from bank_transaction "
                        + "where company_id = ? order by booking_date, id")
                .param(companyId)
                .query((rs, i) -> new BankTransaction(
                        rs.getObject("booking_date", LocalDate.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("description"),
                        rs.getString("counterparty")))
                .list();
    }

    public List<Row> page(long companyId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(
                        "select id, booking_date, amount, description, counterparty from bank_transaction "
                                + "where company_id = ? and booking_date between ? and ? order by booking_date desc, id desc limit ?")
                .params(companyId, from, to, limit)
                .query(Row.class)
                .list();
    }

    public Optional<LocalDate> latestDate(long companyId) {
        // max() over no rows is one row holding SQL NULL.
        List<LocalDate> result = jdbc.sql("select max(booking_date) from bank_transaction where company_id = ?")
                .param(companyId)
                .query(LocalDate.class)
                .list();
        return result.isEmpty() ? Optional.empty() : Optional.ofNullable(result.getFirst());
    }

    public long count(long companyId) {
        return jdbc.sql("select count(*) from bank_transaction where company_id = ?")
                .param(companyId)
                .query(Long.class)
                .single();
    }

    static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
