package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.bnpl.BnplAcceptances;
import com.finapp.bnpl.BnplAgreement;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplAgreementTemplate;
import com.finapp.bnpl.BnplObligation;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplObligations;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchase;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplPurchaseStatus;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplTermsVersionId;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The acceptance's and the agreement reads' boundary, hermetic (`P12-TSK-013`): the receipt a retried key replays - the
 * same agreement, purchase and order, no figure ({@code INV-IDEM-01}); the views in exact decimal strings, the balances the
 * derived ones; and the purchase meter's closed, eager series. The doors over a real database are
 * {@code BnplAcceptanceApiTest}'s.
 */
@DisplayName("the BNPL acceptance and agreement doors' boundary (P12-TSK-013)")
class BnplAgreementDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-013-desk");

    @Test
    @DisplayName("an acceptance's receipt replays the agreement, its version, the purchase, the order and the hash - and"
            + " no amount, no party, no merchant")
    void theReceiptReplays() {
        Instant accepted = Instant.parse("2026-10-17T10:12:00Z");
        UUID order = IDS.next();
        BnplAcceptances.Accepted acceptance = new BnplAcceptances.Accepted(agreement(), purchase(order), obligation(),
                order, JournalEntryId.of(IDS.next()), accepted);
        byte[] stored = BnplAgreementDesk.stored(acceptance).body();
        String text = new String(stored, StandardCharsets.UTF_8);
        // The gross as an amount, never "600" alone - a UUIDv7 identifier can carry those three digits.
        assertThat(text).doesNotContain("600.00").doesNotContain("60000")
                .doesNotContain(acceptance.purchase().party().toString())
                .doesNotContain(acceptance.purchase().merchant().toString());
        BnplAgreementDesk.BnplAcceptedView view = BnplAgreementDesk.replayed(stored);
        assertThat(view).isEqualTo(new BnplAgreementDesk.BnplAcceptedView(
                acceptance.agreement().id().value().toString(), 1, acceptance.purchase().id().value().toString(),
                order.toString(), "BNPL_PAY_IN_4", "PAY_IN_4", "FINANCED", acceptance.agreement().termsSha256Hex(),
                accepted.toString()));
    }

    @Test
    @DisplayName("an agreement is answered in exact decimal strings, its balances the derived ones, its outstanding the"
            + " principal not yet due plus billed")
    void theAgreementView() {
        UUID order = IDS.next();
        BnplPurchaseStore.Owned owned = new BnplPurchaseStore.Owned(obligation(), purchase(order), agreement(),
                eur(600_00));
        BnplObligations.Balances balances = new BnplObligations.Balances(eur(300_00), eur(150_00), eur(0), eur(0),
                eur(0), eur(0));
        BnplAgreementDesk.BnplAgreementView view = BnplAgreementDesk.view(new BnplObligations.Read(owned, balances));
        assertThat(view.amount()).isEqualTo("600.00");
        assertThat(view.balances().principalOutstanding()).isEqualTo("450.00");
        assertThat(view.balances().principalDue()).isEqualTo("150.00");
        assertThat(view.orderId()).isEqualTo(order.toString());
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(BnplAgreementDesk.summary(owned).purchaseDate()).isEqualTo("2026-10-17");
    }

    @Test
    @DisplayName("the agreement shows its condition, derived (P12-TSK-020): PAST_DUE with DPD, bucket and the default flag;"
            + " CURRENT with no bucket; none for a closed agreement")
    void theAgreementShowsItsCondition() {
        BnplPurchaseStore.Owned owned = new BnplPurchaseStore.Owned(obligation(), purchase(IDS.next()), agreement(),
                eur(600_00));
        BnplObligations.Read read = new BnplObligations.Read(owned, new BnplObligations.Balances(eur(300_00),
                eur(150_00), eur(0), eur(0), eur(5_00), eur(0)));
        BnplAgreementDesk.BnplConditionView late = BnplAgreementDesk.view(read, java.util.Optional.of(
                new com.finapp.bnpl.BnplDelinquencyState(91, java.util.Optional.of(
                        com.finapp.bnpl.BnplDelinquencyBucket.B4_90_PLUS), true))).condition();
        assertThat(late).isEqualTo(new BnplAgreementDesk.BnplConditionView("PAST_DUE", 91, "B4_90_PLUS", true));
        assertThat(BnplAgreementDesk.view(read, java.util.Optional.of(com.finapp.bnpl.BnplDelinquencyState.CURRENT))
                .condition()).isEqualTo(new BnplAgreementDesk.BnplConditionView("CURRENT", 0, null, false));
        assertThat(BnplAgreementDesk.view(read).condition()).isNull();
    }

    @Test
    @DisplayName("a schedule lists every instalment with its billed mark and - billed - what it billed, what was paid and"
            + " what is still due (P12-TSK-015)")
    void theScheduleView() {
        UUID first = IDS.next();
        UUID second = IDS.next();
        BnplPurchaseStore.StoredSchedule schedule = new BnplPurchaseStore.StoredSchedule(IDS.next(), obligation().id(), 1,
                "BNPL_SCHEDULE_ENGINE_V1", eur(600_00), LocalDate.of(2026, 10, 17), List.of(
                        instalment(first, 1, LocalDate.of(2026, 10, 17)),
                        instalment(second, 2, LocalDate.of(2026, 10, 31)),
                        instalment(IDS.next(), 3, LocalDate.of(2026, 11, 14)),
                        instalment(IDS.next(), 4, LocalDate.of(2026, 11, 28))));
        BnplPurchaseStore.Owned owned = new BnplPurchaseStore.Owned(obligation(), purchase(IDS.next()), agreement(),
                eur(600_00));
        BnplPurchaseStore.BilledItem paid = new BnplPurchaseStore.BilledItem(IDS.next(), first,
                LocalDate.of(2026, 10, 17), eur(0), eur(0), eur(150_00), eur(0), eur(0), eur(150_00));
        BnplPurchaseStore.BilledItem partly = new BnplPurchaseStore.BilledItem(IDS.next(), second,
                LocalDate.of(2026, 10, 31), eur(5_00), eur(0), eur(150_00), eur(5_00), eur(0), eur(35_00));
        BnplAgreementDesk.BnplScheduleView view = BnplAgreementDesk.schedule(new BnplObligations.Schedule(owned,
                schedule, java.util.Map.of(first, paid, second, partly)));
        assertThat(view.instalments()).extracting(BnplAgreementDesk.BnplScheduledInstalmentView::billed)
                .containsExactly(true, true, false, false);
        assertThat(view.instalments().get(0).amount()).isEqualTo("150.00");
        assertThat(view.instalments().get(0).due()).isEqualTo("0.00");
        assertThat(view.instalments().get(1).billedPrincipal()).isEqualTo("150.00");
        assertThat(view.instalments().get(1).paid()).isEqualTo("40.00");
        assertThat(view.instalments().get(1).due()).isEqualTo("115.00");
        assertThat(view.instalments().get(2).billedPrincipal()).isEqualTo("0.00");
        assertThat(view.instalments().get(2).due()).isEqualTo("0.00");
        assertThat(view.instalments()).extracting(BnplAgreementDesk.BnplScheduledInstalmentView::billedFees)
                .as("instalment 2's late fee, part of what it owes (P12-TSK-020)")
                .containsExactly("0.00", "5.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("the purchase meter registers every product, plan and outcome eagerly, and refuses a plan under another"
            + " product")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplPurchaseMetrics metrics = new BnplPurchaseMetrics(registry);
        assertThat(registry.find(BnplPurchaseMetrics.PURCHASE).counters())
                .hasSize(4 * BnplPurchaseMetrics.Outcome.values().length);
        metrics.record(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS, BnplPurchaseMetrics.Outcome.FINANCED);
        assertThat(registry.get(BnplPurchaseMetrics.PURCHASE).tags("product", "BNPL_PAY_IN_N", "plan",
                "PAY_IN_6_MONTHS", "outcome", "financed").counter().count()).isEqualTo(1.0);
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.record(BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_3_MONTHS, BnplPurchaseMetrics.Outcome.FINANCED));
    }

    // -----------------------------------------------------------------

    private static final BnplObligationId OBLIGATION = BnplObligationId.next(IDS);
    private static final BnplAgreementId AGREEMENT = BnplAgreementId.next(IDS);
    private static final BnplPurchaseId PURCHASE = BnplPurchaseId.next(IDS);

    private static BnplObligation obligation() {
        return new BnplObligation(OBLIGATION, PURCHASE, AGREEMENT, UUID.randomUUID(), UUID.randomUUID(),
                BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, EUR, BnplObligationStatus.ACTIVE, LocalDate.of(2026, 10, 17),
                Instant.parse("2026-10-17T10:12:00Z"), Optional.empty(), CORRELATION);
    }

    private static BnplPurchase purchase(UUID order) {
        return new BnplPurchase(PURCHASE, BnplOfferId.next(IDS), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, EUR, BnplPurchaseStatus.FINANCED,
                LocalDate.of(2026, 10, 17), Instant.parse("2026-10-17T10:12:00Z"),
                Optional.of(Instant.parse("2026-10-17T10:12:00Z")), Optional.of(order), CORRELATION);
    }

    private static BnplAgreement agreement() {
        return new BnplAgreement(AGREEMENT, 1, OBLIGATION, BnplOfferId.next(IDS), UUID.randomUUID(),
                BnplTermsVersionId.of(IDS.next()), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4,
                "BNPL_SCHEDULE_ENGINE_V1", Optional.empty(), "ALLOCATION_ENGINE_V1", "REFUND_ALLOCATION_ENGINE_V1",
                UUID.randomUUID(), BnplAgreementTemplate.BNPL_PAY_IN_4_AGREEMENT_V1, 1, "{\"format\":\"1\"}",
                new byte[32], new byte[32], Instant.parse("2026-10-17T10:12:00Z"));
    }

    private static BnplPurchaseStore.StoredInstalment instalment(UUID id, int sequence, LocalDate due) {
        return new BnplPurchaseStore.StoredInstalment(id, sequence, due, eur(150_00), eur(0), eur(150_00));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
