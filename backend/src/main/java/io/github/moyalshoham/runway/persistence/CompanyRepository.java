package io.github.moyalshoham.runway.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyRepository {

    private static final String COLUMNS =
            "id, name, currency, opening_balance, opening_date, minimum_balance, created_at";

    private final JdbcClient jdbc;

    public CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, BigDecimal openingBalance, LocalDate openingDate, BigDecimal minimumBalance) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into company (name, opening_balance, opening_date, minimum_balance) values (?, ?, ?, ?)")
                .params(name, openingBalance, openingDate, minimumBalance)
                .update(keys, "id");
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("no id returned");
        }
        return id.longValue();
    }

    public Optional<CompanyRow> find(long id) {
        return jdbc.sql("select " + COLUMNS + " from company where id = ?")
                .param(id)
                .query(CompanyRow.class)
                .optional();
    }

    public List<CompanyRow> findAll() {
        return jdbc.sql("select " + COLUMNS + " from company order by id")
                .query(CompanyRow.class)
                .list();
    }

    public void update(long id, String name, BigDecimal minimumBalance) {
        jdbc.sql("update company set name = ?, minimum_balance = ? where id = ?")
                .params(name, minimumBalance, id)
                .update();
    }

    public boolean delete(long id) {
        return jdbc.sql("delete from company where id = ?").param(id).update() > 0;
    }
}
