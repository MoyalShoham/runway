package io.github.moyalshoham.runway.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.engine.model.VatSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    void everyPaidDocumentHasItsPaymentAndSomeCustomersWithholdTax() {
        SyntheticCompany c = generator.generate(5);
        Map<String, List<BankTransaction>> bank = new HashMap<>();
        for (BankTransaction t : c.transactions()) {
            bank.computeIfAbsent(t.date() + "|" + t.counterparty(), k -> new ArrayList<>())
                    .add(t);
        }

        List<Document> paid =
                c.documents().stream().filter(d -> d.paidDate() != null).toList();
        assertThat(paid).isNotEmpty();
        int withheld = 0;
        for (Document d : paid) {
            List<BankTransaction> sameDay = bank.getOrDefault(d.paidDate() + "|" + d.counterparty(), List.of());
            boolean exact = sameDay.stream().anyMatch(t -> t.amount().compareTo(d.signedAmount()) == 0);
            boolean partial = sameDay.stream()
                    .anyMatch(t -> t.amount().signum() == d.signedAmount().signum()
                            && t.amount().abs().compareTo(d.amount().multiply(new BigDecimal("0.70"))) >= 0
                            && t.amount().abs().compareTo(d.amount()) < 0);
            assertThat(exact || partial).as("payment of %s", d.number()).isTrue();
            if (!exact) {
                withheld++;
            }
        }
        // Seed 5 is an agency: some customers withhold tax at source.
        assertThat(withheld).isPositive();
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
        // Invoices (round net amounts) carry the VAT rate in force on their date: 17% until 2024,
        // 18% from 2025.
        VatSettings rates = VatSettings.israel();
        for (Document d : c.documents().stream()
                .filter(x -> x.type() == DocumentType.INVOICE)
                .toList()) {
            BigDecimal factor = BigDecimal.ONE.add(rates.rateOn(d.issueDate()));
            BigDecimal net = d.amount().divide(factor, 2, RoundingMode.HALF_EVEN);
            assertThat(net.multiply(factor).setScale(2, RoundingMode.HALF_EVEN))
                    .as(d.number())
                    .isEqualByComparingTo(d.amount());
        }
        assertThat(c.transactions()).anyMatch(t -> t.counterparty().equals("ביטוח לאומי"));
    }

    @Test
    void documentsAreNumberedInIssueOrder() {
        SyntheticCompany c = generator.generate(1);
        List<Document> invoices = c.documents().stream()
                .filter(d -> d.type() == DocumentType.INVOICE)
                .sorted(java.util.Comparator.comparing(
                        d -> Integer.parseInt(d.number().substring(4))))
                .toList();

        for (int i = 1; i < invoices.size(); i++) {
            assertThat(invoices.get(i).issueDate())
                    .isAfterOrEqualTo(invoices.get(i - 1).issueDate());
        }
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
