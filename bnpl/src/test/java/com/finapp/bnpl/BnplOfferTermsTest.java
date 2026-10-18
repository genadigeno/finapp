package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The offer's canonical terms, document and illustration, as pure functions (`P12-TSK-011`; {@code INV-HIST-04}): the
 * same content renders the same bytes and hash; the form holds no escape and no whitespace, so {@code bnpl V004}'s
 * {@code CHECK} can hash it as stored; a value the token set cannot spell is refused rather than escaped; the
 * illustration conserves on both engines.
 */
@DisplayName("the canonical offer terms, the document and the illustration (P12-TSK-011)")
class BnplOfferTermsTest {

    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.systemUTC(), new java.security.SecureRandom());
    private static final BnplOfferId OFFER = BnplOfferId.next(IDS);
    private static final BnplApplicationId APPLICATION = BnplApplicationId.next(IDS);
    private static final UUID DECISION = UUID.fromString("00000000-0000-4000-8000-0000000000de");
    private static final BnplTermsVersionId VERSION = BnplTermsVersionId.of(IDS.next());
    private static final LocalDate START = LocalDate.of(2026, 10, 17);

    @Test
    @DisplayName("the same content renders the same bytes and the same SHA-256 - deterministic, no whitespace, no escape")
    void theFormIsDeterministic() {
        BnplTerms terms = BnplTermsFixtures.payInN("0.1999").build();
        BnplIllustration illustration = BnplIllustration.of(terms, BnplPlan.PAY_IN_6_MONTHS, euros(600_00), START);
        String first = BnplOfferTerms.render(content(terms, BnplPlan.PAY_IN_6_MONTHS, illustration));
        String again = BnplOfferTerms.render(content(BnplTermsFixtures.payInN("0.19990").build(), BnplPlan.PAY_IN_6_MONTHS,
                BnplIllustration.of(terms, BnplPlan.PAY_IN_6_MONTHS, euros(600_00), START)));
        assertThat(again).isEqualTo(first);
        assertThat(BnplOfferTerms.sha256(again)).isEqualTo(BnplOfferTerms.sha256(first));
        assertThat(first).startsWith("{\"format\":\"1\",").endsWith("}").doesNotContain(" ").doesNotContain("\\")
                .matches("^\\{[A-Za-z0-9_.:+\",{}\\[\\]-]*\\}$")
                .contains("\"nominalAnnualRate\":\"0.1999\"").contains("\"plan\":\"PAY_IN_6_MONTHS\"")
                .contains("\"accrualEngine\":\"ACCRUAL_ENGINE_V1\"");
    }

    @Test
    @DisplayName("every term changes the hash: another amount, plan, decision or rate is another SHA-256")
    void everyTermIsInTheHash() {
        BnplTerms terms = BnplTermsFixtures.payIn4().build();
        byte[] base = hash(terms, euros(600_00), DECISION);
        assertThat(hash(terms, euros(600_01), DECISION)).isNotEqualTo(base);
        assertThat(hash(terms, euros(600_00), UUID.randomUUID())).isNotEqualTo(base);
        assertThat(hash(terms.toBuilder().graceDays(4).build(), euros(600_00), DECISION)).isNotEqualTo(base);
    }

    @Test
    @DisplayName("a pay-in-4 illustration: four interest-free instalments, the remainder on the first, every 14 days from"
            + " the start; the document names the terms' hash")
    void aPayIn4Illustration() {
        BnplTerms terms = BnplTermsFixtures.payIn4().build();
        BnplIllustration illustration = BnplIllustration.of(terms, BnplPlan.PAY_IN_4, euros(100_03), START);
        assertThat(illustration.lines()).extracting(BnplIllustration.Line::amount).containsExactly(euros(25_03),
                euros(25_00), euros(25_00), euros(25_00));
        assertThat(illustration.lastDueDate()).isEqualTo(START.plusDays(42));
        assertThat(illustration.totalInterest().isZero()).isTrue();
        String hex = HexFormat.of().formatHex(BnplOfferTerms.sha256("x"));
        assertThat(BnplOfferDocument.render(OFFER, terms, BnplPlan.PAY_IN_4, illustration, hex))
                .contains("Instalment 1 due 2026-10-17: 25.03 EUR").endsWith("Terms SHA-256: " + hex + "\n");
    }

    @Test
    @DisplayName("a value the token set cannot spell is refused, never escaped")
    void anUnspellableValueIsRefused() {
        BnplTerms terms = BnplTermsFixtures.payIn4().build();
        BnplIllustration illustration = BnplIllustration.of(terms, BnplPlan.PAY_IN_4, euros(600_00), START);
        assertThatThrownBy(() -> BnplOfferTerms.render(new BnplOfferTerms.Content(OFFER, APPLICATION, DECISION, VERSION,
                1, terms.toBuilder().servicingZone("U T C").build(), BnplPlan.PAY_IN_4, illustration)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new BnplOfferTerms.Content(OFFER, APPLICATION, DECISION, VERSION, 1, terms,
                BnplPlan.PAY_IN_6_MONTHS, illustration)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] hash(BnplTerms terms, Money amount, UUID decision) {
        BnplIllustration illustration = BnplIllustration.of(terms, BnplPlan.PAY_IN_4, amount, START);
        return BnplOfferTerms.sha256(BnplOfferTerms.render(new BnplOfferTerms.Content(OFFER, APPLICATION, decision,
                VERSION, 1, terms, BnplPlan.PAY_IN_4, illustration)));
    }

    private static BnplOfferTerms.Content content(BnplTerms terms, BnplPlan plan, BnplIllustration illustration) {
        return new BnplOfferTerms.Content(OFFER, APPLICATION, DECISION, VERSION, 3, terms, plan, illustration);
    }

    private static Money euros(long minor) {
        return Money.ofMinorUnits(minor, BnplTermsFixtures.EUR);
    }
}
