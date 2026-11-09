package io.github.moyalshoham.runway.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CompanyGeneratorTest {

    private final CompanyGenerator generator = new CompanyGenerator();

    @Test
    void sameSeedSameCompany() {
        SyntheticCompany a = generator.generate(7);
        SyntheticCompany b = generator.generate(7);

        assertThat(a.transactions()).isEqualTo(b.transactions());
        assertThat(a.documents()).isEqualTo(b.documents());
        assertThat(generator.generate(8).transactions()).isNotEqualTo(a.transactions());
    }

    @Test
    void seedsCycleThroughArchetypes() {
        Set<Archetype> seen = new HashSet<>();
        LongStream.rangeClosed(1, 4).forEach(s -> seen.add(generator.generate(s).archetype()));

        assertThat(seen).containsExactlyInAnyOrder(Archetype.values());
    }

    @Test
    void everyPaidDocumentHasABankTransactionOfExactlyItsAmount() {
        SyntheticCompany c = generator.generate(5);
        Set<String> bank = new HashSet<>();
        for (BankTransaction t : c.transactions()) {
            bank.add(t.date() + "|" + t.amount());
        }

        List<Document> paid =
                c.documents().stream().filter(d -> d.paidDate() != null).toList();
        assertThat(paid).isNotEmpty();
        for (Document d : paid) {
            assertThat(bank).contains(d.paidDate() + "|" + d.signedAmount());
            assertThat(d.paidDate()).isBeforeOrEqualTo(c.end());
        }
        // Money is exact to the agora everywhere.
        assertThat(c.transactions())
                .allSatisfy(t -> assertThat(t.amount().scale()).isEqualTo(2));
    }

    @Test
    void israeliDetailsArePresent() {
        SyntheticCompany c = generator.generate(1);

        List<BankTransaction> vat = c.transactions().stream()
                .filter(t -> t.counterparty().equals("מע\"מ"))
                .toList();
        // Bimonthly VAT over three years: 17 or 18 payments or refunds.
        assertThat(vat.size()).isBetween(15, 18);
        // Invoices carry 18% VAT on round net amounts: gross / 1.18 has at most two decimals.
        Document inv = c.documents().stream()
                .filter(d -> d.type() == DocumentType.INVOICE)
                .findFirst()
                .orElseThrow();
        assertThat(inv.amount()
                        .divide(new BigDecimal("1.18"), 2, java.math.RoundingMode.HALF_EVEN)
                        .multiply(new BigDecimal("1.18")))
                .isEqualByComparingTo(inv.amount());
        assertThat(c.transactions()).anyMatch(t -> t.counterparty().equals("ביטוח לאומי"));
    }

    @Test
    void balanceOnReconstructsFromOpening() {
        SyntheticCompany c = generator.generate(2);
        BigDecimal sum = c.openingBalance();
        for (BankTransaction t : c.transactions()) {
            sum = sum.add(t.amount());
        }

        assertThat(c.balanceOn(c.end())).isEqualByComparingTo(sum);
        assertThat(c.balanceOn(c.openingDate())).isEqualByComparingTo(c.openingBalance());
    }
}
