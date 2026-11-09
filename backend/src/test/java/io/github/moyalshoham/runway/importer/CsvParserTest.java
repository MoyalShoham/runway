package io.github.moyalshoham.runway.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CsvParserTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesSignedAmountsAndIsoDates() {
        Parsed<BankTransaction> p = CsvParser.transactions(
                utf8(
                        """
                date,amount,description,counterparty
                2025-03-01,-8000.00,Rent March,Landlord
                2025-03-05,"11,800.00",Invoice 1001,Acme
                """));

        assertThat(p.errors()).isEmpty();
        assertThat(p.rows()).hasSize(2);
        assertThat(p.rows().get(1).value().amount()).isEqualByComparingTo("11800.00");
        assertThat(p.rows().get(1).line()).isEqualTo(3);
    }

    @Test
    void readsAnIsraeliBankExportWithHebrewHeadersDebitCreditAndDayFirstDates() {
        String csv =
                """
                תאריך;תיאור;חובה;זכות
                09/03/2025;משכורת דנה;14,500.00;
                10/03/2025;העברה מלקוח;;"5,900.00"
                """;
        Parsed<BankTransaction> p = CsvParser.transactions(csv.getBytes(Charset.forName("windows-1255")));

        assertThat(p.errors()).isEmpty();
        assertThat(p.rows())
                .extracting(l -> l.value().amount().toPlainString())
                .containsExactly("-14500.00", "5900.00");
        assertThat(p.rows().getFirst().value().date()).isEqualTo(LocalDate.of(2025, 3, 9));
        assertThat(p.rows().getFirst().value().description()).isEqualTo("משכורת דנה");
    }

    @Test
    void acceptsTrailingMinusParenthesesAndCurrencySymbols() {
        Parsed<BankTransaction> p = CsvParser.transactions(
                utf8(
                        """
                date,amount,description
                2025-03-01,"1,234.50-",a
                2025-03-02,(99.90),b
                2025-03-03,₪ 250,c
                """));

        assertThat(p.rows())
                .extracting(l -> l.value().amount().toPlainString())
                .containsExactly("-1234.50", "-99.90", "250.00");
    }

    @Test
    void reportsEveryBadRowWithItsLineNumber() {
        Parsed<BankTransaction> p = CsvParser.transactions(
                utf8(
                        """
                date,amount,description
                2025-02-30,10,impossible date
                2025-03-01,12.345,three decimals
                2025-03-01,abc,not a number
                2025-03-01,0,zero
                1985-01-01,10,too old
                2025-03-01,10,
                2025-03-02,10,fine
                """));

        assertThat(p.rows()).hasSize(1);
        assertThat(p.errors()).extracting(RowError::line).containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(p.errors().get(1).message()).contains("not an amount");
    }

    @Test
    void refusesBinaryAndUndecodableFiles() {
        assertThatThrownBy(() -> CsvParser.transactions(new byte[] {'P', 'K', 3, 4, 0, 0}))
                .isInstanceOf(CsvRejectedException.class)
                .hasMessageContaining("binary");
        assertThatThrownBy(() -> CsvParser.transactions(new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD}))
                .isInstanceOf(CsvRejectedException.class);
    }

    @Test
    void refusesFilesWithoutTheNeededColumns() {
        assertThatThrownBy(() -> CsvParser.transactions(utf8("when,how much\n2025-01-01,5\n")))
                .isInstanceOf(CsvRejectedException.class)
                .hasMessageContaining("date column");
        assertThatThrownBy(() -> CsvParser.transactions(utf8(""))).isInstanceOf(CsvRejectedException.class);
    }

    @Test
    void refusesTooManyRows() {
        StringBuilder sb = new StringBuilder("date,amount,description\n");
        for (int i = 0; i <= CsvParser.MAX_ROWS; i++) {
            sb.append("2025-01-01,1,x\n");
        }
        assertThatThrownBy(() -> CsvParser.transactions(utf8(sb.toString())))
                .isInstanceOf(CsvRejectedException.class)
                .hasMessageContaining("rows");
    }

    @Test
    void stripsTheUtf8ByteOrderMarkAndRejectsControlCharacters() {
        Parsed<BankTransaction> p =
                CsvParser.transactions(utf8("﻿date,amount,description\n2025-01-01,5,ok\n2025-01-02,5,bad\u0007bell\n"));

        assertThat(p.rows()).hasSize(1);
        assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e.message())
                .contains("control"));
    }

    @Test
    void parsesDocumentsAndFlagsDuplicatesAndBadDates() {
        Parsed<Document> p = CsvParser.documents(
                utf8(
                        """
                number,customer,issue_date,due_date,amount,paid_date
                INV-1,Acme,2025-01-01,2025-01-31,11800,2025-02-10
                INV-2,Acme,2025-02-01,2025-03-31,5900,
                INV-2,Acme,2025-02-01,2025-03-31,5900,
                INV-3,Beta,2025-02-01,2025-01-01,100,
                INV-4,Beta,2025-02-01,2025-02-01,-5,
                """),
                DocumentType.INVOICE);

        assertThat(p.rows()).hasSize(2);
        assertThat(p.rows().get(1).value().paidDate()).isNull();
        assertThat(p.errors())
                .extracting(RowError::message)
                .anyMatch(m -> m.contains("duplicate"))
                .anyMatch(m -> m.contains("due_date is before"))
                .anyMatch(m -> m.contains("positive"));
    }

    @Test
    void detectsTabDelimitedFiles() {
        Parsed<BankTransaction> p =
                CsvParser.transactions(utf8("date\tamount\tdescription\n2025-01-01\t-5\tcoffee, black\n"));

        assertThat(p.rows()).singleElement().satisfies(l -> assertThat(l.value().description())
                .isEqualTo("coffee, black"));
    }

    @Test
    void aHebrewExportWithBookingAndValueDatesUsesTheBookingDate() {
        String csv =
                """
                תאריך,תאריך ערך,תיאור,חובה,זכות
                01/11/26,03/11/26,העברה,0.00,"5,900.00"
                02/11/26,02/11/26,שכירות,"8,000.00",0
                """;
        Parsed<BankTransaction> p = CsvParser.transactions(csv.getBytes(Charset.forName("windows-1255")));

        assertThat(p.errors()).isEmpty();
        assertThat(p.rows())
                .extracting(l -> l.value().date())
                .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        assertThat(p.rows()).extracting(l -> l.value().amount().toPlainString()).containsExactly("5900.00", "-8000.00");
    }

    @Test
    void readsUtf16WithAByteOrderMark() {
        byte[] bytes = "date,amount,description\n2025-01-01,-5,קפה\n".getBytes(StandardCharsets.UTF_16);

        assertThat(CsvParser.transactions(bytes).rows())
                .singleElement()
                .satisfies(l -> assertThat(l.value().description()).isEqualTo("קפה"));
    }

    @Test
    void badQuotingIsARejectionNotACrash() {
        assertThatThrownBy(
                        () -> CsvParser.transactions(utf8("date,amount,description\n2025-01-01,-10.00,\"abc\"def\n")))
                .isInstanceOf(CsvRejectedException.class)
                .hasMessageContaining("Malformed CSV")
                .hasMessageContaining("line");
        assertThatThrownBy(() ->
                        CsvParser.transactions(utf8("date,amount,description\n2025-01-01,-10.00,\"unterminated\n")))
                .isInstanceOf(CsvRejectedException.class);
    }

    @Test
    void hugeNumbersAreRejectedQuickly() {
        String nines = "9".repeat(1_000_000);
        long started = System.nanoTime();
        Parsed<BankTransaction> p =
                CsvParser.transactions(utf8("date,amount,description\n2025-01-01," + nines + ",x\n"));

        assertThat((System.nanoTime() - started) / 1e9).isLessThan(2.0);
        assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e.message())
                .contains("not an amount"));
        assertThat(CsvParser.transactions(utf8("date,amount,description\n2025-01-01,99999999999999,x\n"))
                        .errors())
                .hasSize(1);
        assertThat(CsvParser.transactions(utf8("date,amount,description\n2025-01-01,\"999,999,999,999.99\",x\n"))
                        .rows())
                .hasSize(1);
    }

    @Test
    void aPaymentBeforeTheInvoiceDateIsRejected() {
        Parsed<Document> p = CsvParser.documents(
                utf8(
                        """
                number,customer,issue_date,due_date,amount,paid_date
                INV-1,Acme,2025-03-01,2025-03-31,100,2020-01-01
                """),
                DocumentType.INVOICE);

        assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e.message())
                .contains("before issue_date"));
    }
}
