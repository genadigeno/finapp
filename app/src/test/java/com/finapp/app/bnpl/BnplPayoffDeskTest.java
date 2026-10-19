package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplClosure;
import com.finapp.bnpl.BnplClosureReason;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplPayoffExecution;
import com.finapp.bnpl.BnplPayoffQuote;
import com.finapp.bnpl.BnplPayoffs;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payoff doors' boundary, hermetic (`P12-TSK-022`): the receipts a retried key replays - the same quote with its
 * inputs, the same payoff with its repayment and closure, never the key or a party ({@code INV-IDEM-01}); the views in exact
 * decimal strings, the amount split into what is paid and what is refunded; the body's date and quote id judged before
 * anything; and the payoff meter's closed, eager series. The doors over a real database are {@code BnplPayoffApiTest}'s.
 */
@DisplayName("the BNPL payoff doors' boundary (P12-TSK-022)")
class BnplPayoffDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("a quote's receipt replays the quote and its inputs - never the key; a negative amount is shown as nothing"
            + " to pay and the refund")
    void theQuoteReceiptReplays() {
        BnplPayoffQuote quote = quote("the-customers-secret-key", eur(450_00), eur(0), eur(4_93), eur(1_64));
        BnplPayoffDesk.BnplPayoffQuoteView view = BnplPayoffDesk.view(quote);
        byte[] stored = BnplPayoffDesk.stored(view).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("the-customers-secret-key");
        assertThat(BnplPayoffDesk.replayedQuote(stored)).isEqualTo(view);
        assertThat(view.amount()).isEqualTo("456.57");
        assertThat(view.refund()).isEqualTo("0.00");
        assertThat(view.projectedInterest()).isEqualTo("1.64");
        assertThat(view.projectedDays()).isEqualTo(5);

        BnplPayoffQuote credit = quote("k", eur(40_00), eur(90_00), eur(0), eur(0));
        BnplPayoffDesk.BnplPayoffQuoteView refunding = BnplPayoffDesk.view(credit);
        assertThat(refunding.amount()).isEqualTo("0.00");
        assertThat(refunding.refund()).isEqualTo("50.00");
        assertThat(BnplPayoffDesk.replayedQuote(BnplPayoffDesk.stored(refunding).body())).isEqualTo(refunding);
    }

    @Test
    @DisplayName("a payoff's receipt replays the execution, its repayment and its closure - never the key; with no"
            + " repayment it pays 0 and refunds the credit")
    void thePayoffReceiptReplays() {
        BnplPayoffQuote quote = quote("q-key", eur(450_00), eur(0), eur(0), eur(0));
        BnplPayoffExecution execution = new BnplPayoffExecution(IDS.next(), quote.id(), quote.obligation(),
                "the-execution-secret-key", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21), Instant.now(),
                CorrelationId.of("desk"));
        BnplPurchaseStore.Repayment repayment = new BnplPurchaseStore.Repayment(IDS.next(), quote.obligation(),
                BnplPurchaseStore.Repayment.PAYOFF, quote.id().toString(), eur(450_00), quote.agreement(), 1,
                "ALLOCATION_ENGINE_V1", JournalEntryId.of(IDS.next()), LocalDate.of(2026, 10, 21), List.of(
                        new BnplPurchaseStore.Allocation(IDS.next(), 1, "DUE_ITEM", Optional.of(IDS.next()),
                                Optional.of("PRINCIPAL"), eur(450_00))));
        BnplClosure closure = new BnplClosure(IDS.next(), quote.obligation(), BnplClosureReason.PAID_OFF_EARLY, eur(0),
                Optional.empty(), Optional.of(repayment.id()), CorrelationId.of("desk"));
        BnplPayoffDesk.BnplPayoffView view = BnplPayoffDesk.view(new BnplPayoffs.Executed(execution, quote,
                Optional.of(repayment), closure, false));
        byte[] stored = BnplPayoffDesk.stored(view).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("the-execution-secret-key")
                .doesNotContain("q-key");
        assertThat(BnplPayoffDesk.replayedPayoff(stored)).isEqualTo(view);
        assertThat(view.paid()).isEqualTo("450.00");
        assertThat(view.refunded()).isEqualTo("0.00");
        assertThat(view.valueDate()).isEqualTo("2026-10-21");
        assertThat(view.closureReason()).isEqualTo("PAID_OFF_EARLY");

        BnplClosure refunding = new BnplClosure(IDS.next(), quote.obligation(), BnplClosureReason.PAID_OFF_EARLY,
                eur(50_00), Optional.of(JournalEntryId.of(IDS.next())), Optional.empty(), CorrelationId.of("desk"));
        BnplPayoffDesk.BnplPayoffView none = BnplPayoffDesk.view(new BnplPayoffs.Executed(execution, quote,
                Optional.empty(), refunding, false));
        assertThat(none.repaymentId()).isNull();
        assertThat(none.paid()).isEqualTo("0.00");
        assertThat(none.refunded()).isEqualTo("50.00");
        assertThat(BnplPayoffDesk.replayedPayoff(BnplPayoffDesk.stored(none).body())).isEqualTo(none);
    }

    @Test
    @DisplayName("the good-through date is an ISO date or absent; a malformed quote id is the uniform 404 - both before"
            + " anything")
    void theBodyIsJudgedFirst() {
        assertThat(BnplPayoffDesk.goodThrough(new BnplPayoffController.BnplPayoffQuoteBody(null))).isEmpty();
        assertThat(BnplPayoffDesk.goodThrough(new BnplPayoffController.BnplPayoffQuoteBody("2026-10-22")))
                .contains(LocalDate.of(2026, 10, 22));
        assertThatThrownBy(() -> BnplPayoffDesk.goodThrough(new BnplPayoffController.BnplPayoffQuoteBody("22/10/2026")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode().status()).isEqualTo(422));
        assertThatThrownBy(() -> BnplPayoffDesk.quoteId(new BnplPayoffController.BnplPayoffBody("not-a-uuid")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode().status()).isEqualTo(404));
    }

    @Test
    @DisplayName("the payoff requires a MULTI_FACTOR session always - a password session is 403"
            + " identity.AssuranceRequired, enrolled or not (A18's negative); MULTI_FACTOR and STRONG pass")
    void thePayoffIsMultiFactorAlways() {
        assertThatThrownBy(() -> BnplPayoffDesk.requireMultiFactor(session(AssuranceLevel.PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
                    assertThat(refused.errorCode().status()).isEqualTo(403);
                });
        BnplPayoffDesk.requireMultiFactor(session(AssuranceLevel.MULTI_FACTOR));
        BnplPayoffDesk.requireMultiFactor(session(AssuranceLevel.STRONG));
    }

    private static Session session(AssuranceLevel assurance) {
        Instant now = Instant.now();
        return Session.rehydrate(SessionId.next(IDS), IdentityId.next(IDS), Sensitive.of("hash"), assurance,
                SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
    }

    @Test
    @DisplayName("the payoff meter registers every product and outcome eagerly, counted once per act")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplPayoffMetrics metrics = new BnplPayoffMetrics(registry);
        assertThat(registry.find(BnplPayoffMetrics.PAYOFF).counters())
                .hasSize(BnplProduct.values().length * BnplPayoffMetrics.Outcome.values().length);
        metrics.record(BnplProduct.BNPL_PAY_IN_N, BnplPayoffMetrics.Outcome.PAID_OFF);
        assertThat(registry.get(BnplPayoffMetrics.PAYOFF).tags("product", "BNPL_PAY_IN_N", "outcome", "paid_off")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(BnplPayoffMetrics.PAYOFF).tags("product", "BNPL_PAY_IN_4", "outcome", "paid_off")
                .counter().count()).isZero();
    }

    private static BnplPayoffQuote quote(String key, Money principal, Money credit, Money accrued, Money projected) {
        Money zero = eur(0);
        Money amount = principal.plus(accrued).minus(credit).plus(projected);
        boolean interest = accrued.isPositive() || projected.isPositive();
        return new BnplPayoffQuote(IDS.next(), BnplObligationId.next(IDS), BnplAgreementId.next(IDS), 1, key,
                LocalDate.of(2026, 10, 20), interest ? LocalDate.of(2026, 10, 25) : LocalDate.of(2026, 10, 20),
                principal, zero, accrued, zero, zero, credit, principal, interest ? 5 : 0, projected, amount,
                interest ? "SCHEDULE_ENGINE_V1" : "BNPL_SCHEDULE_ENGINE_V1",
                interest ? Optional.of("ACCRUAL_ENGINE_V1") : Optional.empty(), "ALLOCATION_ENGINE_V1", Instant.now(),
                CorrelationId.of("desk"));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
