package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-ref, hermetic (`P12-TSK-021`; owner decisions B3 and B7, ADR-0105; {@code PHASE_12_PLAN.md} sections 7.1, 7.2, 12.6 and
 * 12.11 F5/F5a; {@code INV-BNPL-06}, {@code -07}, {@code -09}, {@code INV-MER-07} as amended, {@code INV-HIST-04},
 * {@code INV-IDEM-01}): a merchant or an operator refunds all or part of a BNPL purchase at any point in its life - the
 * unpaid instalments cancelled latest first, the excess to the customer's wallet (or held as credit), the merchant fee
 * returned by the cumulative method, the payable debited the refund's net and below zero after a payout by at most the
 * purchase's net, schedule and agreement version n+1 for a shortened plan, and the closure of a refund that leaves nothing
 * owed. Over {@link BnplApplicationFakes} - in-memory {@code bnpl V006}...{@code V012} (the refund trigger's bound, the B7
 * bound, the versions' identity, the closure's reasons, the purchase's terminal edge), a ledger that refuses an unbalanced
 * or a second posting, and a unit of work that rolls every fake back. The races across instances and the database's own
 * ranks are {@code BnplRefundDatabaseTest}'s.
 *
 * <p>The worked figures are ADR-0105 decision 12's at six times the scale: pay-in-4, {@code G = 600.00}, {@code f = 24.00}
 * (400 bps), four instalments of 150.00, instalment 1 paid at purchase, F1 leaving the payable +576.00.
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
@DisplayName("merchant refunds, latest instalments first (P12-TSK-021)")
class BnplRefundsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-021-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    // An operator's session actor is CUSTOMER-typed today (the platform's known debt) - the door admits it.
    private static final Actor OPERATOR = new Actor("operator-7", ActorType.CUSTOMER);
    private static final long GROSS = 600_00L;
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplAcceptances acceptances;
    private BnplServicing servicing;
    private BnplRepayments repayments;
    private BnplRefunds refunds;
    private final Map<String, UUID> operatorParties = new LinkedHashMap<>();
    private UUID party;
    private Actor customer;
    private UUID merchant;
    private Actor merchantActor;
    private LedgerAccountId payable;
    private LedgerAccountId wallet;

    @BeforeEach
    void setUp() {
        init(IDS.next(), IDS.next());
    }

    private void init(UUID payableId, UUID walletId) {
        world = new BnplApplicationFakes();
        BnplTermsAdministration terms = new BnplTermsAdministration(world.terms.store(), world.audit, world.outbox, IDS,
                CLOCK, BnplTermsFixtures.BOUNDS);
        applications = new BnplApplications(world.store, terms, world.standing, world.sessions, world.merchants,
                BnplTermsFixtures.BOUNDS, world.credit, world.outbox, world.audit, IDS, CLOCK);
        origination = new BnplOrigination(world.store, world.offers, terms, world.credit, world.sessions, world.outbox,
                IDS, CLOCK);
        acceptances = new BnplAcceptances(world.offers, world.terms.store(), world.purchases, world.credit,
                world.standing, world.sessions, world.merchants, world.capital, world.wallets, world.book, world.outbox,
                world.audit, IDS, CLOCK);
        BnplClosures closures = new BnplClosures(world.repayments, world.servicing, world.purchases, world.wallets,
                world.book, world.outbox, IDS, CLOCK);
        servicing = new BnplServicing(world.servicing, world.purchases, world.terms.store(), world.book, closures,
                world.outbox, IDS, CLOCK);
        repayments = new BnplRepayments(world.repayments, world.servicing, world.purchases, world.terms.store(),
                world.wallets, world.book, closures, world.outbox, IDS, CLOCK);
        refunds = new BnplRefunds(world.refunds, world.servicing, world.purchases, world.terms.store(), world.merchants,
                world.wallets, world.book, closures, (uow, actor) -> Optional.ofNullable(operatorParties.get(actor.id())),
                world.outbox, world.audit, IDS, CLOCK);
        party = UUID.randomUUID();
        customer = new Actor(party.toString(), ActorType.CUSTOMER);
        merchant = UUID.randomUUID();
        merchantActor = new Actor(merchant.toString(), ActorType.MERCHANT);
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        payable = world.book.external("payable", payableId);
        world.merchants.payables.put(merchant, payable);
        wallet = world.book.external("wallet", walletId);
        world.book.funds.put(wallet, eur(1_000_00));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
        operatorParties.clear();
    }

    // ------------------------------------------------------------------ before, during, after (scenario 9)

    @Test
    @DisplayName("a full refund before instalment 2 cancels 4, 3 and 2 latest first, returns instalment 1 as the excess, the"
            + " fee in full, the payable to zero - and closes REFUNDED with every BNPL account zero (scenario 9,"
            + " INV-BNPL-09)")
    void aFullRefundClosesWithNothingStranded() {
        Agreement p4 = payIn4();
        BnplRefunds.Refunded refunded = refundAt("2026-10-20T10:00:00Z", p4, "full", eur(600_00));
        BnplRefund refund = refunded.refund();
        assertThat(refunded.kind()).isEqualTo(BnplRefunds.Kind.FULL);
        assertThat(refund.adjustments()).extracting(adjustment -> adjustment.sequence() + " " + adjustment.kind() + " "
                + adjustment.principalCancelled().minorUnits() + " " + adjustment.whole())
                .containsExactly("4 UNBILLED 15000 true", "3 UNBILLED 15000 true", "2 UNBILLED 15000 true");
        assertThat(refund.excess()).isEqualTo(eur(150_00));
        assertThat(refund.excessTo()).isEqualTo(BnplRefund.ExcessTo.WALLET);
        assertThat(refund.feeReturned()).isEqualTo(eur(24_00));
        assertThat(refund.payableDebit()).isEqualTo(eur(576_00));
        assertThat(labelled(world.book.linesOf(BnplRefunds.POSTING_KEY_PREFIX + refund.id())))
                .containsExactly("payable DEBIT 60000", "BNPL_MERCHANT_FEE_INCOME DEBIT 2400", "BNPL_PRINCIPAL CREDIT 45000",
                        "wallet CREDIT 15000", "payable CREDIT 2400");
        BnplLedgerAccounts accounts = accounts(p4);
        for (com.finapp.ledger.LedgerAccount account : accounts.all()) {
            assertThat(balance(account)).as(account.purpose().name()).isEqualTo(eur(0));
        }
        assertThat(world.book.balance(payable, false)).as("the payable back to zero").isEqualTo(eur(0));
        assertThat(world.book.balance(feeIncome(), false)).as("the fee returned in full").isEqualTo(eur(0));
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).as("instalment 1 returned")
                .isEqualTo(eur(1_000_00));
        assertThat(refunded.closure()).hasValueSatisfying(closure -> {
            assertThat(closure.reason()).isEqualTo(BnplClosureReason.REFUNDED);
            assertThat(closure.closingRefund()).contains(refund.id());
        });
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(purchase(p4).status()).isEqualTo(BnplPurchaseStatus.FULLY_REFUNDED);
        BnplPurchaseStore.StoredSchedule latest = world.purchases.schedules.get(p4.obligation().value());
        assertThat(latest.version()).as("schedule version 2 - the unbilled instalments cancelled").isEqualTo(2);
        assertThat(latest.instalments()).extracting(BnplPurchaseStore.StoredInstalment::sequence).containsExactly(1);
        assertThat(world.purchases.agreements.get(p4.agreement().value()).version()).isEqualTo(2);
        assertThat(refund.agreementVersion()).isEqualTo(2);
        assertThat(world.events).extracting(EventEnvelope::eventType)
                .contains(BnplRefunds.APPLIED_EVENT, BnplClosures.CLOSED_EVENT);
    }

    @Test
    @DisplayName("during: with instalment 2 billed and unpaid, 180 cancels 4 and 30 of 3 (no excess); then 420 cancels the"
            + " rest of 3 and instalment 2's billed principal, curing it, and returns the excess - the fee 7.20 then 16.80,"
            + " exactly 24.00 in all")
    void refundBeforeDuringAfter() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        BnplRefund first = refundAt("2026-11-02T10:00:00Z", p4, "during-1", eur(180_00)).refund();
        assertThat(first.adjustments()).extracting(adjustment -> adjustment.sequence() + " " + adjustment.kind() + " "
                + adjustment.principalCancelled().minorUnits() + " " + adjustment.whole())
                .containsExactly("4 UNBILLED 15000 true", "3 UNBILLED 3000 false");
        assertThat(first.excess()).isEqualTo(eur(0));
        assertThat(first.excessTo()).isEqualTo(BnplRefund.ExcessTo.NONE);
        assertThat(first.feeReturned()).isEqualTo(eur(7_20));
        assertThat(first.payableDebit()).isEqualTo(eur(172_80));
        BnplPurchaseStore.StoredSchedule v2 = world.purchases.schedules.get(p4.obligation().value());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.instalments()).extracting(row -> row.sequence() + " " + row.principal().minorUnits())
                .containsExactly("1 15000", "2 15000", "3 12000");
        assertThat(v2.principal()).isEqualTo(eur(420_00));
        assertThat(balance(accounts(p4).principalDue())).as("instalment 2 still due").isEqualTo(eur(150_00));
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(purchase(p4).status()).isEqualTo(BnplPurchaseStatus.FINANCED);

        BnplRefunds.Refunded second = refundAt("2026-11-03T10:00:00Z", p4, "during-2", eur(420_00));
        assertThat(second.refund().adjustments()).extracting(adjustment -> adjustment.sequence() + " " + adjustment.kind()
                + " " + adjustment.principalCancelled().minorUnits())
                .containsExactly("3 UNBILLED 12000", "2 BILLED_UNPAID 15000");
        assertThat(second.refund().excess()).isEqualTo(eur(150_00));
        assertThat(second.refund().feeReturned()).isEqualTo(eur(16_80));
        assertThat(first.feeReturned().plus(second.refund().feeReturned())).isEqualTo(eur(24_00));
        assertThat(balance(accounts(p4).principalDue())).as("instalment 2's arrears cured by the cancellation")
                .isEqualTo(eur(0));
        assertThat(second.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED);
        assertThat(world.book.balance(payable, false)).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("after every instalment is paid (CLOSED, REPAID) a refund cancels nothing and is all excess to the wallet -"
            + " the obligation stays CLOSED and no BNPL account moves (OQ-15)")
    void aRefundAfterClosurePaysTheCustomerOnly() {
        Agreement p4 = payIn4();
        world.book.funds.put(wallet, eur(10_000_00));
        serviceAt("2026-11-28T09:00:00Z", p4);
        BnplRepayments.Repaid repaid = repayAt("2026-11-28T12:00:00Z", p4, "all", eur(450_00));
        assertThat(repaid.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REPAID);
        Money walletBefore = world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        BnplRefunds.Refunded refunded = refundAt("2026-11-30T10:00:00Z", p4, "after", eur(240_00));
        assertThat(refunded.refund().adjustments()).isEmpty();
        assertThat(refunded.refund().excess()).isEqualTo(eur(240_00));
        assertThat(refunded.refund().feeReturned()).isEqualTo(eur(9_60));
        assertThat(refunded.closure()).isEmpty();
        assertThat(labelled(world.book.linesOf(BnplRefunds.POSTING_KEY_PREFIX + refunded.refund().id())))
                .containsExactly("payable DEBIT 24000", "BNPL_MERCHANT_FEE_INCOME DEBIT 960", "wallet CREDIT 24000",
                        "payable CREDIT 960");
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(walletBefore.plus(eur(240_00)));
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(obligation(p4).closureReason()).contains(BnplClosureReason.REPAID);
        for (com.finapp.ledger.LedgerAccount account : accounts(p4).all()) {
            assertThat(balance(account)).as(account.purpose().name()).isEqualTo(eur(0));
        }
        assertThat(world.purchases.schedules.get(p4.obligation().value()).version()).as("nothing cancelled, no version")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refund after closure whose wallet cannot be credited is refused with nothing written - a CLOSED"
            + " obligation holds no credit balance (OQ-15, INV-BNPL-09)")
    void aRefundAfterClosureToAnUnpostableWalletIsRefused() {
        Agreement p4 = payIn4();
        world.book.funds.put(wallet, eur(10_000_00));
        serviceAt("2026-11-28T09:00:00Z", p4);
        repayAt("2026-11-28T12:00:00Z", p4, "all", eur(450_00));
        world.book.statuses.put(wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> refundAt("2026-11-30T10:00:00Z", p4, "after", eur(100_00)))
                .isInstanceOf(BnplRefunds.RefundNotPostable.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.refunds.rows).isEmpty();
    }

    @Test
    @DisplayName("the excess goes to the customer's wallet; with the wallet unpostable it is held in BNPL_CREDIT_BALANCE,"
            + " the obligation left open, and the next servicing step refunds it and closes REFUNDED (OQ-13)")
    void anUnpostableWalletHoldsTheExcessAsCredit() {
        Agreement p4 = payIn4();
        world.book.statuses.put(wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        BnplRefunds.Refunded refunded = refundAt("2026-10-20T10:00:00Z", p4, "held", eur(600_00));
        assertThat(refunded.refund().excessTo()).isEqualTo(BnplRefund.ExcessTo.CREDIT_BALANCE);
        assertThat(refunded.closure()).as("the credit cannot be refunded yet").isEmpty();
        assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(150_00));
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(purchase(p4).status()).isEqualTo(BnplPurchaseStatus.FULLY_REFUNDED);

        world.book.statuses.put(wallet, LedgerAccountStatus.ACTIVE);
        try (SecurityContext.Scope scope = SecurityContext.enter(SYSTEM)) {
            assertThat(serviceAt("2026-10-21T09:00:00Z", p4).closed()).isTrue();
        }
        assertThat(world.repayments.closures.get(p4.obligation().value()).reason())
                .as("refunds settled it, whichever transaction refunds the credit").isEqualTo(BnplClosureReason.REFUNDED);
        assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(0));
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(eur(1_000_00));
    }

    @Test
    @DisplayName("a customer repaying after a full refund is refused 409 ObligationClosed, nothing posted (scenario 16)")
    void aRepaymentAfterAFullRefundIsRefused() {
        Agreement p4 = payIn4();
        refundAt("2026-10-20T10:00:00Z", p4, "full", eur(600_00));
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> repayAt("2026-10-21T10:00:00Z", p4, "late", eur(10_00)))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThat(world.book.keys).hasSize(keys);
    }

    // ------------------------------------------------------------------ latest first, the versions, the servicing after

    @Test
    @DisplayName("a refund cancels latest first: 180 of a fresh pay-in-4 takes all of instalment 4 and 30 of 3, never 2 -"
            + " and the next servicing steps bill version 2's instalments, 150 then 120, nothing for 4")
    void refundCancelsLatestFirst() {
        Agreement p4 = payIn4();
        BnplRefund refund = refundAt("2026-10-20T10:00:00Z", p4, "latest", eur(180_00)).refund();
        assertThat(refund.adjustments()).extracting(BnplRefund.Adjustment::sequence).containsExactly(4, 3);
        assertThat(refund.cancelledUnbilled()).isEqualTo(eur(180_00));
        assertThat(balance(accounts(p4).principal())).isEqualTo(eur(270_00));

        serviceAt("2026-11-28T09:00:00Z", p4);
        List<BnplBilling> billed = world.purchases.billings.values().stream()
                .filter(row -> row.obligation().equals(p4.obligation())).sorted(java.util.Comparator
                        .comparing(BnplBilling::dueDate)).toList();
        assertThat(billed).extracting(row -> row.sequence() + " " + row.principal().minorUnits() + " v"
                + row.scheduleVersion()).containsExactly("1 15000 v1", "2 15000 v2", "3 12000 v2");
        assertThat(balance(accounts(p4).principal())).as("nothing left to bill").isEqualTo(eur(0));
        BnplRepayments.Repaid repaid = repayAt("2026-11-28T12:00:00Z", p4, "rest", eur(270_00));
        assertThat(repaid.closure()).as("a repayment closes it - the closing act names the reason")
                .map(BnplClosure::reason).contains(BnplClosureReason.REPAID);
    }

    @Test
    @DisplayName("a refund that cancels the unpaid tail closes REFUNDED_PARTIALLY_REPAID (refunds and the paid instalment"
            + " together); one that cancels nothing unbilled makes no version (PHASE_12_PLAN section 5.4)")
    void aRefundCancellingNothingUnbilledMakesNoVersion() {
        Agreement p4 = payIn4();
        BnplRefunds.Refunded tail = refundAt("2026-10-20T10:00:00Z", p4, "tail", eur(450_00));
        assertThat(tail.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED_PARTIALLY_REPAID);
        assertThat(world.purchases.schedules.get(p4.obligation().value()).version()).isEqualTo(2);
        world.book.funds.put(wallet, eur(10_000_00));
        // Nothing unbilled is left; a second refund can only take the excess.
        BnplRefunds.Refunded rest = refundAt("2026-10-21T10:00:00Z", p4, "rest", eur(150_00));
        assertThat(rest.refund().cancelled()).isEqualTo(eur(0));
        assertThat(world.purchases.schedules.get(p4.obligation().value()).version()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the fee, the bound, B7

    @Test
    @DisplayName("the fee telescopes to exactly f over split refunds, in either order, and each refund returns exactly"
            + " cum(after) - cum(before) - never a running clamp (ADR-0105 decision 3)")
    void theFeeTelescopesToExactlyFOverSplitRefunds() {
        Agreement split = payIn4();
        Money returned = eur(0);
        for (long minor : new long[] {100_00, 133_33, 366_67}) {
            returned = returned.plus(refundAt("2026-10-20T10:00:00Z", split, "split-" + minor, eur(minor)).refund()
                    .feeReturned());
        }
        assertThat(returned).isEqualTo(eur(24_00));
        List<Money> shares = world.refunds.rows.values().stream().map(BnplRefund::feeReturned).toList();
        assertThat(shares).containsExactly(eur(4_00), eur(5_33), eur(14_67));

        init(IDS.next(), IDS.next());
        Agreement reversed = payIn4();
        Money other = refundAt("2026-10-20T10:00:00Z", reversed, "a", eur(420_00)).refund().feeReturned()
                .plus(refundAt("2026-10-20T10:00:01Z", reversed, "b", eur(180_00)).refund().feeReturned());
        assertThat(other).isEqualTo(eur(24_00));
    }

    @Test
    @DisplayName("refunds never exceed the gross: one above what remains is refused 422 with nothing written, and a fully"
            + " refunded purchase 409 (INV-BNPL-07)")
    void refundsNeverExceedTheGross() {
        Agreement p4 = payIn4();
        refundAt("2026-10-20T10:00:00Z", p4, "first", eur(180_00));
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> refundAt("2026-10-20T11:00:00Z", p4, "over", eur(420_01)))
                .isInstanceOf(BnplRefunds.RefundExceedsPurchase.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.refunds.rows).hasSize(1);
        refundAt("2026-10-20T12:00:00Z", p4, "rest", eur(420_00));
        assertThatThrownBy(() -> refundAt("2026-10-20T13:00:00Z", p4, "again", eur(1)))
                .isInstanceOf(BnplRefunds.PurchaseFullyRefunded.class);
        assertThat(world.refunds.rows).hasSize(2);
    }

    @Test
    @DisplayName("a refund after a payout drives the payable below zero by at most the purchase's net - 172.80 then 576.00"
            + " = G - f - and is never refused for a short payable (B7, INV-MER-07 as amended)")
    void aRefundAfterPayoutIsBoundedByThePurchasesNet() {
        Agreement p4 = payIn4();
        LedgerAccountId clearing = world.book.external("payout-clearing", IDS.next());
        world.book.post(BnplApplicationFakes.UOW, new com.finapp.ledger.PostingCommand("merchant.payout:test",
                PURCHASE_DATE, PURCHASE_DATE, "payout", List.of(
                        new com.finapp.ledger.JournalLine(payable, com.finapp.ledger.Direction.DEBIT, eur(576_00)),
                        new com.finapp.ledger.JournalLine(clearing, com.finapp.ledger.Direction.CREDIT, eur(576_00)))));
        assertThat(world.book.balance(payable, false)).as("paid out").isEqualTo(eur(0));
        refundAt("2026-10-20T10:00:00Z", p4, "one", eur(180_00));
        assertThat(world.book.balance(payable, false)).isEqualTo(eur(-172_80));
        refundAt("2026-10-21T10:00:00Z", p4, "two", eur(420_00));
        assertThat(world.book.balance(payable, false)).as("exactly -(G - f), the bound").isEqualTo(eur(-576_00));
        Money debited = world.refunds.rows.values().stream().map(BnplRefund::payableDebit).reduce(eur(0), Money::plus);
        assertThat(debited).isEqualTo(eur(576_00));
    }

    @Test
    @DisplayName("the B7 bound holds by arithmetic for every split of the gross: the payable's debits never exceed G - f")
    void theB7BoundHoldsForEverySplit() {
        for (long firstMinor = 1_00; firstMinor < GROSS; firstMinor += 37_13) {
            init(IDS.next(), IDS.next());
            Agreement p4 = payIn4();
            refundAt("2026-10-20T10:00:00Z", p4, "a", eur(firstMinor));
            Money debited = world.refunds.rows.values().stream().map(BnplRefund::payableDebit).reduce(eur(0), Money::plus);
            assertThat(debited).isLessThanOrEqualTo(eur(576_00));
            refundAt("2026-10-20T10:00:01Z", p4, "b", eur(GROSS - firstMinor));
            debited = world.refunds.rows.values().stream().map(BnplRefund::payableDebit).reduce(eur(0), Money::plus);
            assertThat(debited).as("split at " + firstMinor).isEqualTo(eur(576_00));
        }
    }

    // ------------------------------------------------------------------ idempotency and the doors

    @Test
    @DisplayName("the same refund twice under one key refunds once - the second converges on the first, one entry"
            + " (scenario 8, INV-IDEM-01); the key with another amount is refused, nothing written")
    void theSameRefundTwiceRefundsOnce() {
        Agreement p4 = payIn4();
        BnplRefunds.Refunded first = refundAt("2026-10-20T10:00:00Z", p4, "once", eur(180_00));
        int keys = world.book.keys.size();
        BnplRefunds.Refunded again = refundAt("2026-10-20T10:00:01Z", p4, "once", eur(180_00));
        assertThat(again.replayed()).isTrue();
        assertThat(again.refund().id()).isEqualTo(first.refund().id());
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.refunds.rows).hasSize(1);
        assertThatThrownBy(() -> refundAt("2026-10-20T10:00:02Z", p4, "once", eur(181_00)))
                .isInstanceOf(BnplRefunds.RefundKeyReused.class);
        assertThat(world.refunds.rows).hasSize(1);
    }

    @Test
    @DisplayName("another merchant's purchase is the uniform not-found - its lock never taken, nothing read beyond it"
            + " (INV-MER-01)")
    void anotherMerchantsPurchaseIsNotFound() {
        Agreement p4 = payIn4();
        world.log.clear();
        world.now = Instant.parse("2026-10-20T10:00:00Z");
        assertThatThrownBy(() -> world.inTransaction(() -> refunds.atMerchant(BnplApplicationFakes.UOW, p4.purchase(),
                UUID.randomUUID(), request("other", eur(10_00)), merchantActor, CORRELATION)))
                .isInstanceOf(BnplRefunds.PurchaseNotFound.class);
        assertThat(world.log).containsExactly("B5");
        assertThat(world.refunds.rows).isEmpty();
    }

    @Test
    @DisplayName("the operator's door refunds under the operator's key namespace, audited with the reason; an operator of the"
            + " purchase's own party is refused with nothing written and the attempt recorded FAILED")
    void theOperatorsDoorRefusesSelfDealing() {
        Agreement p4 = payIn4();
        operatorParties.put(OPERATOR.id(), party);
        world.now = Instant.parse("2026-10-20T10:00:00Z");
        BnplRefunds.SelfDealingRefused refused = (BnplRefunds.SelfDealingRefused) org.assertj.core.api.Assertions
                .catchThrowable(() -> world.inTransaction(() -> refunds.byOperator(BnplApplicationFakes.UOW,
                        p4.purchase(), request("op", eur(100_00)), OPERATOR, CORRELATION)));
        assertThat(refused).isNotNull();
        assertThat(world.refunds.rows).isEmpty();
        world.inTransaction(() -> {
            refunds.recordSelfDealing(BnplApplicationFakes.UOW, refused, OPERATOR, CORRELATION);
            return null;
        });
        assertThat(world.audited).last().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
        });

        operatorParties.put(OPERATOR.id(), UUID.randomUUID());
        BnplRefund refund = world.inTransaction(() -> refunds.byOperator(BnplApplicationFakes.UOW, p4.purchase(),
                request("op", eur(100_00)), OPERATOR, CORRELATION)).refund();
        assertThat(refund.door()).isEqualTo(BnplRefund.Door.OPERATOR);
        assertThat(refund.requestedBy()).isEqualTo("OPERATOR:" + OPERATOR.id());
        assertThat(world.audited).last().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.REFUND_APPLIED);
            assertThat(record.actor()).isEqualTo(OPERATOR);
            assertThat(record.reason()).contains("the goods came back");
        });
    }

    @Test
    @DisplayName("a merchant key without the refund scope is recorded FAILED - the purchase named, the key id, nothing else")
    void aScopeRefusalIsRecorded() {
        world.inTransaction(() -> {
            refunds.recordScopeRefusal(BnplApplicationFakes.UOW, "a-purchase", "key-42", merchantActor, CORRELATION);
            return null;
        });
        assertThat(world.audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.REFUND_SCOPE_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("key-42"));
        });
    }

    @Test
    @DisplayName("a reason is required and screened before anything is locked; an amount in another scale is refused")
    void theReasonAndTheAmountAreJudgedFirst() {
        Agreement p4 = payIn4();
        world.log.clear();
        world.now = Instant.parse("2026-10-20T10:00:00Z");
        assertThatThrownBy(() -> world.inTransaction(() -> refunds.atMerchant(BnplApplicationFakes.UOW, p4.purchase(),
                merchant, new BnplRefunds.Request("k", eur(10_00), " ", Optional.empty()), merchantActor,
                CORRELATION))).isInstanceOf(BnplReasons.ReasonRequired.class);
        assertThatThrownBy(() -> world.inTransaction(() -> refunds.atMerchant(BnplApplicationFakes.UOW, p4.purchase(),
                merchant, new BnplRefunds.Request("k", eur(10_00), "card 4111 1111 1111 1111", Optional.empty()),
                merchantActor, CORRELATION))).isInstanceOf(BnplReasons.ReasonRequired.class);
        assertThat(world.log).doesNotContain("B5");
        assertThatThrownBy(() -> refundAt("2026-10-20T10:00:00Z", p4, "scale",
                Money.ofPersisted(10_000, BnplTermsFixtures.EUR, 3))).isInstanceOf(BnplRefunds.AmountInvalid.class);
        assertThat(world.refunds.rows).isEmpty();
    }

    // ------------------------------------------------------------------ the lock order, the races

    @Test
    @DisplayName("the refund takes the purchase, then the obligation, then the payable and wallet as one element in"
            + " UUID.compareTo order - in both id orders - then L8 and the entry (B5 -> B4 -> (P) -> L8)")
    void refundTakesThePurchaseThenTheObligationThenThePair() {
        for (boolean walletFirst : new boolean[] {true, false}) {
            UUID one = IDS.next();
            UUID two = IDS.next();
            UUID low = one.compareTo(two) < 0 ? one : two;
            UUID high = one.compareTo(two) < 0 ? two : one;
            init(walletFirst ? high : low, walletFirst ? low : high);
            Agreement p4 = payIn4();
            world.log.clear();
            refundAt("2026-10-20T10:00:00Z", p4, "order", eur(600_00));
            List<String> order = world.log.stream().filter(step -> step.equals("B5") || step.equals("B4")
                    || step.startsWith("lock ") || step.equals("L8") || step.startsWith("post bnpl.refund")).toList();
            List<String> expected = new ArrayList<>(List.of("B5", "B4"));
            expected.addAll(walletFirst ? List.of("lock wallet", "lock payable") : List.of("lock payable", "lock wallet"));
            expected.addAll(List.of("L8", "post bnpl.refund:"));
            assertThat(order.subList(0, expected.size())).as(walletFirst ? "wallet id first" : "payable id first")
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("a refund racing an instalment collection conserves in both orders: collected-then-refunded returns the"
            + " collected amount in the excess; refunded-then-collected leaves nothing to collect (scenario 7)")
    void aRefundRacingACollectionConservesInBothOrders() {
        Agreement collectedFirst = payIn4();
        serviceAt("2026-10-31T09:00:00Z", collectedFirst);
        repayAt("2026-11-01T10:00:00Z", collectedFirst, "collect", eur(150_00));
        BnplRefund afterCollection = refundAt("2026-11-02T10:00:00Z", collectedFirst, "full", eur(600_00)).refund();
        assertThat(afterCollection.cancelled()).isEqualTo(eur(300_00));
        assertThat(afterCollection.excess()).as("instalments 1 and 2, both paid").isEqualTo(eur(300_00));
        Money walletA = world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        Money payableA = world.book.balance(payable, false);

        init(IDS.next(), IDS.next());
        Agreement refundedFirst = payIn4();
        serviceAt("2026-10-31T09:00:00Z", refundedFirst);
        BnplRefund beforeCollection = refundAt("2026-11-01T10:00:00Z", refundedFirst, "full", eur(600_00)).refund();
        assertThat(beforeCollection.cancelled()).isEqualTo(eur(450_00));
        assertThat(beforeCollection.excess()).isEqualTo(eur(150_00));
        assertThatThrownBy(() -> repayAt("2026-11-02T10:00:00Z", refundedFirst, "collect", eur(150_00)))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).as("the customer whole either way")
                .isEqualTo(walletA).isEqualTo(eur(1_000_00));
        assertThat(world.book.balance(payable, false)).isEqualTo(payableA).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a failure after the posting rolls the whole refund back - the entry, the versions, the row, the edge, the"
            + " events; the key retries cleanly")
    void aFailureWritesNothing() {
        Agreement p4 = payIn4();
        int keys = world.book.keys.size();
        world.events.clear();
        world.failAt = "refund insert";
        assertThatThrownBy(() -> refundAt("2026-10-20T10:00:00Z", p4, "crash", eur(600_00)))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.events).isEmpty();
        assertThat(world.refunds.rows).isEmpty();
        assertThat(world.purchases.schedules.get(p4.obligation().value()).version()).isEqualTo(1);
        assertThat(world.purchases.agreements.get(p4.agreement().value()).version()).isEqualTo(1);
        assertThat(purchase(p4).status()).isEqualTo(BnplPurchaseStatus.FINANCED);
        world.failAt = null;
        assertThat(refundAt("2026-10-20T10:00:01Z", p4, "crash", eur(600_00)).closure()).isPresent();
    }

    @Test
    @DisplayName("BnplRefundApplied carries the refund's figures and the cancelled instalments - never the reason, the key,"
            + " the party or the wallet; the audit record carries the reason")
    void theEventAndTheAuditRecord() {
        Agreement p4 = payIn4();
        world.payloads.clear();
        BnplRefund refund = refundAt("2026-10-20T10:00:00Z", p4, "secret-refund-key", eur(180_00)).refund();
        String payload = world.payloads.stream().filter(body -> body.contains(refund.id().toString())).findFirst()
                .orElseThrow();
        assertThat(payload).contains("\"amountMinor\":\"18000\"").contains("\"instalmentsCancelled\":\"4_3\"")
                .contains("\"feeReturnedMinor\":\"720\"").doesNotContain("the goods came back")
                .doesNotContain("secret-refund-key").doesNotContain(party.toString())
                .doesNotContain(wallet.value().toString());
        assertThat(world.audited).last().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.REFUND_APPLIED);
            assertThat(record.targetId()).isEqualTo(refund.id().toString());
            assertThat(record.reason()).contains("the goods came back");
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary)
                    .contains("key=merchant-key-1").doesNotContain("18000"));
        });
    }

    @Test
    @DisplayName("the merchant reads its refunds beside the purchase - partially refunded derived, its own reason, nothing"
            + " of the customer's; another merchant's refund is empty at the transaction report's second rank")
    void theMerchantReadsItsRefunds() {
        Agreement p4 = payIn4();
        BnplRefund refund = refundAt("2026-10-20T10:00:00Z", p4, "read", eur(180_00)).refund();
        BnplPurchaseReads reads = new BnplPurchaseReads(world.purchases, world.refunds);
        MerchantBnplPurchaseView view = reads.forMerchant(BnplApplicationFakes.UOW, merchant, p4.purchase()).orElseThrow();
        assertThat(view.partiallyRefunded()).isTrue();
        assertThat(view.refunded()).isEqualTo(eur(180_00));
        assertThat(view.refunds()).singleElement().satisfies(read -> {
            assertThat(read.refund()).isEqualTo(refund.id());
            assertThat(read.payableDebit()).isEqualTo(eur(172_80));
            assertThat(read.reason()).contains("the goods came back");
        });
        assertThat(reads.forMerchant(BnplApplicationFakes.UOW, UUID.randomUUID(), p4.purchase())).isEmpty();
        assertThat(reads.refundAtMerchant(BnplApplicationFakes.UOW, merchant, refund.id())).contains(p4.purchase());
        assertThat(reads.refundAtMerchant(BnplApplicationFakes.UOW, UUID.randomUUID(), refund.id())).isEmpty();
    }

    // ------------------------------------------------------------------ pay-in-N (A11)

    @Test
    @DisplayName("a pay-in-N refund on a record behind its accrual writes nothing and asks for T-acc first; caught up, it"
            + " shortens the plan from its end by recalculateAtInstalment - the same due dates, version 2's principal the"
            + " first's less exactly the cancellation - the accrued interest still owed, and the next billing bills"
            + " version 2")
    void aPnRefundAccruesFirstAndRecalculatesAtTheInstalment() {
        Agreement pn = payInN();
        assertThatThrownBy(() -> refundAt("2026-10-20T10:00:00Z", pn, "pn", eur(150_00)))
                .isInstanceOf(BnplRefunds.BehindAccrual.class);
        assertThat(world.refunds.rows).isEmpty();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(LocalDate.of(2026, 10, 20)); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        BnplPurchaseStore.StoredSchedule v1 = world.purchases.schedules.get(pn.obligation().value());
        BnplRefund refund = refundAt("2026-10-20T10:00:00Z", pn, "pn", eur(150_00)).refund();
        assertThat(refund.adjustments()).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.sequence()).isEqualTo(3);
            assertThat(adjustment.kind()).isEqualTo(RefundAllocation.Cancellation.Kind.UNBILLED);
            assertThat(adjustment.principalCancelled()).isEqualTo(eur(150_00));
        });
        assertThat(refund.interestReleased()).as("interest accrued stays owed (A11)").isEqualTo(eur(0));
        assertThat(balance(accounts(pn).interestAccrued())).isEqualTo(eur(30));
        BnplPurchaseStore.StoredSchedule v2 = world.purchases.schedules.get(pn.obligation().value());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.principal()).isEqualTo(v1.principal().minus(eur(150_00)));
        assertThat(v2.start()).isEqualTo(PURCHASE_DATE);
        assertThat(v2.instalments().size()).isLessThanOrEqualTo(v1.instalments().size());
        for (BnplPurchaseStore.StoredInstalment row : v2.instalments()) {
            assertThat(row.dueDate()).isEqualTo(v1.instalments().get(row.sequence() - 1).dueDate());
            assertThat(row.amount()).isLessThanOrEqualTo(v1.instalments().get(0).amount());
        }
        LocalDate first = v2.instalments().get(0).dueDate();
        for (LocalDate day = LocalDate.of(2026, 10, 20); day.isBefore(first); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(7));
        }
        ServicingOutcome billed = serviceAt(first.atTime(9, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        assertThat(billed.billed()).isEqualTo(1);
        assertThat(world.purchases.billings.values()).singleElement()
                .satisfies(billing -> assertThat(billing.scheduleVersion()).isEqualTo(2));
    }

    @Test
    @DisplayName("a full pay-in-N refund cancels every unit of principal, releases the accrued interest, leaves an empty"
            + " version and closes REFUNDED with every account zero (A11, INV-BNPL-09)")
    void aFullPnRefundReleasesUnpaidInterest() {
        Agreement pn = payInN();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(LocalDate.of(2026, 10, 20)); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        BnplRefunds.Refunded refunded = refundAt("2026-10-20T10:00:00Z", pn, "pn-full", eur(600_00));
        assertThat(refunded.refund().interestAccruedReleased()).isEqualTo(eur(30));
        assertThat(refunded.refund().excess()).isEqualTo(eur(0));
        assertThat(world.purchases.schedules.get(pn.obligation().value()).instalments()).isEmpty();
        assertThat(refunded.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED);
        for (com.finapp.ledger.LedgerAccount account : accounts(pn).all()) {
            assertThat(balance(account)).as(account.purpose().name()).isEqualTo(eur(0));
        }
        LedgerAccountId income = world.book.operational.get(AccountPurpose.BNPL_INTEREST_INCOME);
        assertThat(world.book.balance(income, false)).as("the interest forgone").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a pay-in-N refund after the first billing that cancels every unit of principal releases the billed and the"
            + " accrued interest too, and the billed item reads settled")
    void aPnRefundReleasesBilledInterestOnlyAtAFullCancellation() {
        Agreement pn = payInN();
        BnplPurchaseStore.StoredSchedule v1 = world.purchases.schedules.get(pn.obligation().value());
        LocalDate first = v1.instalments().get(0).dueDate();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(first.plusDays(2)); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        serviceAt(first.atTime(9, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        Money billedInterest = balance(accounts(pn).interestDue());
        assertThat(billedInterest.isPositive()).isTrue();
        BnplRefunds.Refunded refunded = refundAt(first.plusDays(2).atTime(10, 0).toInstant(ZoneOffset.UTC).toString(), pn,
                "pn-full", eur(600_00));
        assertThat(refunded.refund().interestDueReleased()).isEqualTo(billedInterest);
        assertThat(refunded.refund().interestAccruedReleased()).isEqualTo(eur(20));
        assertThat(refunded.closure()).map(BnplClosure::reason).contains(BnplClosureReason.REFUNDED);
        for (com.finapp.ledger.LedgerAccount account : accounts(pn).all()) {
            assertThat(balance(account)).as(account.purpose().name()).isEqualTo(eur(0));
        }
    }

    // ------------------------------------------------------------------ the position

    @Test
    @DisplayName("the position composes from the rows: every slot's principal sums to the gross, earlier cancellations"
            + " carried, a slot with nothing ever owed left out")
    void thePositionComposesFromTheRows() {
        Agreement p4 = payIn4();
        refundAt("2026-10-20T10:00:00Z", p4, "first", eur(180_00));
        BnplPurchaseStore.StoredSchedule v1 = world.purchases.versions.get(p4.obligation().value() + ":1");
        BnplPurchaseStore.StoredSchedule latest = world.purchases.schedules.get(p4.obligation().value());
        BnplRefunds.Derivation derived = BnplRefunds.derive(v1, latest,
                world.purchases.billedItems(BnplApplicationFakes.UOW, p4.obligation()),
                world.refunds.adjustmentsOf(BnplApplicationFakes.UOW, p4.obligation()),
                balance(accounts(p4).principal()), eur(GROSS), eur(24_00), eur(180_00));
        assertThat(derived.position().instalments()).extracting(instalment -> instalment.principal().minorUnits() + "/"
                + instalment.cancelled().minorUnits() + "/" + instalment.unpaid().minorUnits())
                .containsExactly("15000/0/0", "15000/0/15000", "15000/3000/12000", "15000/15000/0");
        assertThat(derived.position().unpaid()).isEqualTo(eur(270_00));
    }

    // ------------------------------------------------------------------ helpers

    /** An accepted purchase: its id, its agreement and obligation. */
    record Agreement(BnplPurchaseId purchase, BnplAgreementId agreement, BnplObligationId obligation) {}

    private BnplRefunds.Request request(String key, Money amount) {
        return new BnplRefunds.Request(key, amount, "the goods came back", Optional.of("merchant-key-1"));
    }

    private BnplRefunds.Refunded refundAt(String instant, Agreement agreement, String key, Money amount) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> refunds.atMerchant(BnplApplicationFakes.UOW, agreement.purchase(), merchant,
                request(key, amount), merchantActor, CORRELATION));
    }

    private BnplRepayments.Repaid repayAt(String instant, Agreement agreement, String key, Money amount) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> repayments.repay(BnplApplicationFakes.UOW, agreement.agreement(), party,
                new BnplRepayments.Request(key, amount), customer, CORRELATION));
    }

    private ServicingOutcome serviceAt(String instant, Agreement agreement) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, agreement.obligation()));
    }

    private Agreement payIn4() {
        return accept(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
    }

    private Agreement payInN() {
        return accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
    }

    private Agreement accept(BnplProduct product, BnplPlan plan) {
        world.now = Instant.parse("2026-10-17T10:10:00Z");
        String token = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                eur(GROSS), Instant.parse("2026-10-17T11:00:00Z"), true, false));
        BnplApplication application = world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, party,
                new BnplApplications.Submission(Sensitive.of(token), product, plan), customer, CORRELATION));
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(), SYSTEM));
        BnplOffer offer = world.offers.ofApplication(BnplApplicationFakes.UOW, application.id()).orElseThrow();
        String hash = world.offers.terms.get(offer.id().value()).termsSha256Hex();
        BnplAcceptanceEvidence evidence = new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                "MULTI_FACTOR", Optional.empty(), Optional.empty());
        BnplAcceptances.Accepted accepted = world.inTransaction(() -> acceptances.accept(BnplApplicationFakes.UOW,
                offer.id(), party, new BnplAcceptances.Acceptance(hash, evidence), customer,
                Correlation.startingWith(CORRELATION)));
        return new Agreement(accepted.purchase().id(), accepted.agreement().id(), accepted.obligation().id());
    }

    private BnplObligation obligation(Agreement agreement) {
        return world.purchases.obligations.get(agreement.obligation().value());
    }

    private BnplPurchase purchase(Agreement agreement) {
        return world.purchases.rows.get(agreement.purchase().value());
    }

    private LedgerAccountId feeIncome() {
        return world.book.operational.get(AccountPurpose.BNPL_MERCHANT_FEE_INCOME);
    }

    private List<String> labelled(List<BnplApplicationFakes.Book.Line> lines) {
        return lines.stream().map(line -> world.book.labels.get(line.account()) + " " + line.direction() + " "
                + line.amount().minorUnits()).toList();
    }

    private BnplLedgerAccounts accounts(Agreement agreement) {
        return new BnplReceivables(world.purchases, world.book).accounts(BnplApplicationFakes.UOW, agreement.obligation());
    }

    private Money balance(com.finapp.ledger.LedgerAccount account) {
        return world.book.balanceOf(BnplApplicationFakes.UOW, account);
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
