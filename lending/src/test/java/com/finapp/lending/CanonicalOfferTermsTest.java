package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical offer terms and the offer document (`P11-TSK-011`; {@code INV-LND-05}, {@code INV-HIST-04}): the same
 * offer renders the same bytes and so the same hashes; every term the borrower agrees to - the pinned version, its whole
 * term set, the four engines, the template, the figures - is in the canonical form and moves its hash; the form holds no
 * whitespace and no escape, as {@code lending V005}'s {@code CHECK} requires. Format 2 (`P11-TSK-031`, {@code lending
 * V011}) spells a servicing zone east of UTC; format 1 is kept, byte for byte, for replay.
 */
@DisplayName("the canonical offer terms and the offer document (P11-TSK-011, P11-TSK-031)")
class CanonicalOfferTermsTest {

    private static final LoanOfferId OFFER = LoanOfferId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000000a1"));
    private static final LoanApplicationId APPLICATION =
            LoanApplicationId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000000b1"));
    private static final UUID DECISION = UUID.fromString("0190a1b2-5c0e-7000-8000-0000000000c1");
    private static final LoanTermsVersionId VERSION =
            LoanTermsVersionId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000000d1"));

    @Test
    @DisplayName("a loan's canonical terms are deterministic, escape-free, and carry the version, the term set, the"
            + " engines, the template and the illustrative figures")
    void aLoansCanonicalTerms() {
        LoanTerms terms = LoanTermsFixtures.personalLoan("0.0899").build();
        CanonicalOfferTerms.Content content = loan(terms, 10_000_00, 36);
        String canonical = CanonicalOfferTerms.render(content);
        assertThat(CanonicalOfferTerms.render(loan(terms, 10_000_00, 36))).isEqualTo(canonical);
        assertThat(canonical).matches("^\\{[A-Za-z0-9_.:+\",{}\\[\\]-]*\\}$").startsWith("{\"format\":\"2\",");
        assertThat(canonical).contains("\"offer\":\"" + OFFER.value() + "\"", "\"decision\":\"" + DECISION + "\"",
                "\"id\":\"" + VERSION.value() + "\"", "\"number\":\"3\"", "\"amount\":\"1000000\"",
                "\"termMonths\":\"36\"", "\"nominalAnnualRate\":\"0.0899\"", "\"lateFee\":\"1500\"",
                "\"delinquencyBounds\":[\"1\",\"30\",\"60\",\"90\"]", "\"schedule\":\"SCHEDULE_ENGINE_V1\"",
                "\"statement\":\"NONE\"", "\"id\":\"PERSONAL_LOAN_AGREEMENT\"",
                "\"basis\":\"ILLUSTRATIVE_FROM_OFFERING_DAY\"", "\"start\":\"2026-10-11\"");
    }

    @Test
    @DisplayName("every term moves the hash: the amount, the term, the day, the version, a fee and the illustration")
    void everyTermMovesTheHash() {
        LoanTerms terms = LoanTermsFixtures.personalLoan("0.0899").build();
        String hash = hex(CanonicalOfferTerms.render(loan(terms, 10_000_00, 36)));
        assertThat(hex(CanonicalOfferTerms.render(loan(terms, 10_000_01, 36)))).isNotEqualTo(hash);
        assertThat(hex(CanonicalOfferTerms.render(loan(terms, 10_000_00, 35)))).isNotEqualTo(hash);
        assertThat(hex(CanonicalOfferTerms.render(loan(terms.toBuilder()
                .lateFee(Money.ofMinorUnits(16_00, LoanTermsFixtures.EUR)).build(), 10_000_00, 36)))).isNotEqualTo(hash);
        CanonicalOfferTerms.Content base = loan(terms, 10_000_00, 36);
        assertThat(hex(CanonicalOfferTerms.render(new CanonicalOfferTerms.Content(base.offer(), base.application(),
                base.decision(), base.termsVersionId(), 4, terms, base.amount(), base.termMonths(), base.repaymentDay(),
                base.illustration())))).isNotEqualTo(hash);
        assertThat(hex(CanonicalOfferTerms.render(new CanonicalOfferTerms.Content(base.offer(), base.application(),
                base.decision(), base.termsVersionId(), 3, terms, base.amount(), base.termMonths(), 15,
                base.illustration())))).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("a line's canonical terms carry its minimum-payment rule and statement engine and no illustration; its"
            + " document states the rule; a figure in another currency is refused")
    void aLinesCanonicalTermsAndDocument() {
        LoanTerms terms = LoanTermsFixtures.creditLine("0.1599").build();
        String canonical = CanonicalOfferTerms.render(new CanonicalOfferTerms.Content(OFFER, APPLICATION, DECISION,
                VERSION, 1, terms, Money.ofMinorUnits(2_000_00, LoanTermsFixtures.EUR), Optional.empty(), 5,
                Optional.empty()));
        assertThat(canonical).contains("\"termMonths\":\"NONE\"", "\"minimumPaymentFloor\":\"2500\"",
                "\"minimumPaymentRatioBps\":\"300\"", "\"paymentDueDays\":\"25\"",
                "\"statement\":\"STATEMENT_ENGINE_V1\"", "\"basis\":\"NONE\"");
        LoanOffer.Terms offered = new LoanOffer.Terms(Money.ofMinorUnits(2_000_00, LoanTermsFixtures.EUR),
                Optional.empty(), terms.nominalAnnualRate(), 5, Optional.empty(),
                Optional.of(new LoanOffer.MinimumPaymentRule(Money.ofMinorUnits(25_00, LoanTermsFixtures.EUR), 300, 25)),
                Optional.empty(), EngineVersion.ACCRUAL_ENGINE_V1, EngineVersion.ALLOCATION_ENGINE_V1,
                Optional.of(EngineVersion.STATEMENT_ENGINE_V1), AgreementTemplate.CREDIT_LINE_AGREEMENT_V1, canonical,
                CanonicalOfferTerms.sha256(canonical), new byte[32]);
        String document = OfferDocument.render(OFFER, LoanProduct.CREDIT_LINE, offered);
        assertThat(document).isEqualTo(OfferDocument.render(OFFER, LoanProduct.CREDIT_LINE, offered))
                .contains("Credit limit: 2000.00 EUR", "3 % of the drawn principal", "25.00 EUR",
                        "Payment due: 25 days after each statement date", offered.termsSha256Hex())
                .doesNotContain("ILLUSTRATIVE");
        assertThatIllegalArgumentException().isThrownBy(() -> new CanonicalOfferTerms.Content(OFFER, APPLICATION,
                DECISION, VERSION, 1, terms, Money.ofMinorUnits(2_000_00, com.finapp.sharedkernel.money.CurrencyCode.of(
                        "GBP")), Optional.empty(), 5, Optional.empty()));
        assertThatIllegalArgumentException().as("a document is its own product's template's").isThrownBy(() ->
                OfferDocument.render(OFFER, LoanProduct.PERSONAL_LOAN, offered));
    }

    @Test
    @DisplayName("format 2 spells a servicing zone east of UTC - +14:00 and +05:30 - and declares itself; format 1 never"
            + " spells a '+' and is refused it rather than re-spelt; a format beyond 2 is neither rendered nor read")
    void format2SpellsAZoneEastOfUtc() {
        for (String zone : List.of("+14:00", "+05:30")) {
            CanonicalOfferTerms.Content east =
                    loan(LoanTermsFixtures.personalLoan("0.0899").servicingZone(zone).build(), 10_000_00, 36);
            String canonical = CanonicalOfferTerms.render(east);
            assertThat(canonical).startsWith("{\"format\":\"2\",").contains("\"servicingZone\":\"" + zone + "\"")
                    .matches("^\\{[A-Za-z0-9_.:+\",{}\\[\\]-]*\\}$");
            assertThat(CanonicalOfferTerms.formatOf(canonical)).isEqualTo(2);
            assertThatIllegalArgumentException().as("format 1 never wrote a '+'")
                    .isThrownBy(() -> CanonicalOfferTerms.render(east, 1))
                    .withMessageNotContaining(zone);
        }
        CanonicalOfferTerms.Content west =
                loan(LoanTermsFixtures.personalLoan("0.0899").servicingZone("-14:00").build(), 10_000_00, 36);
        assertThat(CanonicalOfferTerms.render(west, 1)).contains("\"servicingZone\":\"-14:00\"");
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalOfferTerms.render(west, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalOfferTerms.render(west, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalOfferTerms.formatOf("{\"format\":\"3\",\"x\":\"y\"}"));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalOfferTerms.formatOf("{\"offer\":{}}"));
        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalOfferTerms.formatOf("{\"format\":\"2\"}"));
    }

    @Test
    @DisplayName("format 1 is kept for replay byte for byte: its golden hash pinned from the renderer that wrote every"
            + " format-1 row, and format 2 the same bytes but for its format token wherever format 1 renders")
    void format1IsKeptByteForByte() {
        // The golden's content: the fixture as it stood then - auto-collection on (P11-TSK-019 turned the fixture's
        // default off, which is a different term set and so a different hash).
        CanonicalOfferTerms.Content content = loan(LoanTermsFixtures.personalLoan("0.0899").autoCollection(true).build(),
                10_000_00, 36);
        String one = CanonicalOfferTerms.render(content, 1);
        assertThat(one).startsWith("{\"format\":\"1\",").matches("^\\{[A-Za-z0-9_.:\",{}\\[\\]-]*\\}$");
        assertThat(CanonicalOfferTerms.formatOf(one)).isEqualTo(1);
        // Pinned from P11-TSK-011's renderer (format 1, before format 2 existed): a change to it breaks replay (-029).
        assertThat(hex(one)).isEqualTo(FORMAT_1_GOLDEN);
        assertThat(CanonicalOfferTerms.render(content, 2))
                .isEqualTo(CanonicalOfferTerms.render(content))
                .isEqualTo("{\"format\":\"2\"" + one.substring("{\"format\":\"1\"".length()));
    }

    /** SHA-256 of format 1 of {@code format1IsKeptByteForByte}'s content, from the renderer at commit 1a4c8575. */
    private static final String FORMAT_1_GOLDEN = "81225f4fe1808cd997c314c7540572e90dbf5befed3f8370fadea656835d15db";

    private static CanonicalOfferTerms.Content loan(LoanTerms terms, long minor, int months) {
        Money principal = Money.ofMinorUnits(minor, LoanTermsFixtures.EUR);
        Schedule schedule = ((ScheduleOutcome.Projected) ScheduleEngineV1.project(terms, principal, months, 10,
                LocalDate.of(2026, 10, 11))).schedule();
        return new CanonicalOfferTerms.Content(OFFER, APPLICATION, DECISION, VERSION, 3, terms, principal,
                Optional.of(months), 10, Optional.of(LoanOffer.Illustration.of(schedule)));
    }

    private static String hex(String canonical) {
        return HexFormat.of().formatHex(CanonicalOfferTerms.sha256(canonical));
    }
}
