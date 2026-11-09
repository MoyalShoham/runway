package io.github.moyalshoham.runway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.moyalshoham.runway.PostgresTestBase;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.sim.CompanyGenerator;
import io.github.moyalshoham.runway.sim.CsvExport;
import io.github.moyalshoham.runway.sim.SyntheticCompany;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

/** Full flow over HTTP against PostgreSQL: create, import, re-import, forecast, scenarios, errors. */
class ApiEndToEndTest extends PostgresTestBase {

    @Autowired
    private TestRestTemplate http;

    private long createCompany(SyntheticCompany c) {
        ResponseEntity<JsonNode> r = http.postForEntity(
                "/api/companies",
                Map.of(
                        "name", c.name(),
                        "openingBalance", c.openingBalance(),
                        "openingDate", c.openingDate().toString(),
                        "minimumBalance", c.minimumBalance()),
                JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asLong();
    }

    private ResponseEntity<JsonNode> upload(String path, String filename, byte[] content) {
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void importForecastAndScenarioOverHttp() {
        SyntheticCompany c = new CompanyGenerator().generate(5);
        LocalDate asOf = LocalDate.of(2025, 6, 30);
        long id = createCompany(c);
        byte[] txCsv = CsvExport.transactions(c, asOf).getBytes(StandardCharsets.UTF_8);

        JsonNode tx = upload("/api/companies/" + id + "/transactions/import", "bank.csv", txCsv)
                .getBody();
        JsonNode inv = upload(
                        "/api/companies/" + id + "/invoices/import",
                        "invoices.csv",
                        CsvExport.documents(c, DocumentType.INVOICE, asOf).getBytes(StandardCharsets.UTF_8))
                .getBody();
        JsonNode bills = upload(
                        "/api/companies/" + id + "/bills/import",
                        "bills.csv",
                        CsvExport.documents(c, DocumentType.BILL, asOf).getBytes(StandardCharsets.UTF_8))
                .getBody();
        assertThat(tx.get("imported").asInt()).isGreaterThan(1000);
        assertThat(tx.get("rejected")).isEmpty();
        assertThat(inv.get("imported").asInt()).isPositive();
        assertThat(bills.get("imported").asInt()).isPositive();

        // Re-importing the same bank file adds nothing.
        JsonNode again = upload("/api/companies/" + id + "/transactions/import", "bank.csv", txCsv)
                .getBody();
        assertThat(again.get("imported").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(tx.get("imported").asInt());

        JsonNode company = http.getForObject("/api/companies/" + id, JsonNode.class);
        assertThat(company.get("lastTransactionDate").asText()).isEqualTo("2025-06-30");

        JsonNode f = http.getForObject("/api/companies/" + id + "/forecast", JsonNode.class);
        assertThat(f.get("asOf").asText()).isEqualTo("2025-06-30");
        assertThat(f.get("startingBalance").asText())
                .isEqualTo(c.balanceOn(asOf).toPlainString());
        assertThat(f.get("days")).hasSize(90);
        JsonNode first = f.get("days").get(0);
        // Money is a decimal string; the band is ordered.
        assertThat(first.get("p50").isTextual()).isTrue();
        assertThat(new BigDecimal(first.get("p10").asText()))
                .isLessThanOrEqualTo(new BigDecimal(first.get("p50").asText()));
        assertThat(new BigDecimal(first.get("p50").asText()))
                .isLessThanOrEqualTo(new BigDecimal(first.get("p90").asText()));
        assertThat(f.get("recurring").size()).isGreaterThan(5);
        assertThat(f.get("schedule").size()).isPositive();

        // The same forecast twice is identical (seeded).
        assertThat(http.getForObject("/api/companies/" + id + "/forecast", JsonNode.class)
                        .get("days"))
                .isEqualTo(f.get("days"));

        JsonNode cmp = http.postForObject(
                "/api/companies/" + id + "/scenarios",
                Map.of("scenarios", new Object[] {
                    Map.of("type", "ONE_OFF", "date", "2025-07-15", "amount", "-25000.00", "label", "New server")
                }),
                JsonNode.class);
        assertThat(cmp.get("endDifference").asText()).isEqualTo("-25000.00");

        JsonNode history = http.getForObject("/api/companies/" + id + "/history?days=30", JsonNode.class);
        assertThat(history).hasSize(30);
        assertThat(history.get(29).get("balance").asText())
                .isEqualTo(c.balanceOn(asOf).toPlainString());
    }

    @Test
    void aFileWithAnyInvalidRowIsRejectedWholeUnlessSkipInvalid() {
        long id = createCompany(new CompanyGenerator().generate(6));
        byte[] csv =
                """
                date,amount,description
                2023-02-01,-100,ok
                2023-02-31,-100,bad date
                2023-02-02,12.345,bad amount
                """
                        .getBytes(StandardCharsets.UTF_8);

        ResponseEntity<JsonNode> r = upload("/api/companies/" + id + "/transactions/import", "x.csv", csv);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("errors")).hasSize(2);
        assertThat(r.getBody().get("errors").get(0).get("line").asInt()).isEqualTo(3);
        assertThat(http.getForObject("/api/companies/" + id, JsonNode.class)
                        .get("transactions")
                        .asInt())
                .isZero();

        JsonNode partial = upload("/api/companies/" + id + "/transactions/import?skipInvalid=true", "x.csv", csv)
                .getBody();
        assertThat(partial.get("imported").asInt()).isEqualTo(1);
        assertThat(partial.get("rejected")).hasSize(2);
    }

    @Test
    void uploadsAreBoundedAndValidated() {
        long id = createCompany(new CompanyGenerator().generate(7));
        String base = "/api/companies/" + id + "/transactions/import";

        assertThat(upload(base, "evil.xlsx", new byte[] {'P', 'K', 3, 4, 0, 0, 0})
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload(base, "big.csv", new byte[6 * 1024 * 1024]).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(upload(base, "empty.csv", new byte[0]).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // Rows dated on or before the opening balance would double count it.
        byte[] early = "date,amount,description\n2022-12-31,-5,before opening\n".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(base, "early.csv", early).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(upload("/api/companies/999999/transactions/import", "x.csv", early)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void badRequestsGetProblemDetailsNotStackTraces() {
        long id = createCompany(new CompanyGenerator().generate(8));

        ResponseEntity<JsonNode> missing = http.postForEntity(
                "/api/companies/" + id + "/scenarios",
                Map.of("scenarios", new Object[] {Map.of("type", "NEW_HIRE", "date", "2025-01-01")}),
                JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("detail").asText()).contains("amount");

        ResponseEntity<JsonNode> invalid = http.postForEntity("/api/companies", Map.of("name", ""), JsonNode.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().toString()).doesNotContain("Exception").doesNotContain("at io.github");

        assertThat(http.getForEntity("/api/companies/" + id + "/forecast?asOf=2099-01-01", JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/companies/" + id + "/forecast?horizonDays=5000", JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/companies/abc", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCompanyWithNoDataStillForecastsItsOpeningBalance() {
        long id = createCompany(new CompanyGenerator().generate(9));

        JsonNode f = http.getForObject("/api/companies/" + id + "/forecast", JsonNode.class);

        assertThat(f.get("days")).hasSize(90);
        assertThat(f.get("days").get(89).get("p50").asText())
                .isEqualTo(f.get("startingBalance").asText());
    }

    @Test
    void demoCompanyEndsToday() {
        ResponseEntity<JsonNode> r = http.postForEntity("/api/demo?seed=3", null, JsonNode.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("transactions").asInt()).isGreaterThan(1000);
        assertThat(LocalDate.parse(r.getBody().get("lastTransactionDate").asText()))
                .isAfter(LocalDate.now().minusDays(7));
    }
}
