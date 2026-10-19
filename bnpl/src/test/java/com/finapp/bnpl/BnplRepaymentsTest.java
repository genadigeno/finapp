package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-rep and the closure, hermetic (`P12-TSK-018`; {@code PHASE_12_PLAN.md} sections 7.1, 7.2 and 12.11 F3, F6's refund arm;
 * {@code INV-BNPL-01}, {@code INV-LND-04} and {@code INV-LND-08} by analogy, {@code INV-IDEM-01}, {@code INV-BAL-04},
 * {@code INV-BAL-05}, {@code INV-LIFE-04}, {@code INV-BNPL-10}): the customer repays any amount from the wallet, allocated
 * exactly by the pinned order - oldest due first, {@code FEES -> INTEREST -> PRINCIPAL}, the rest held - once per key; the
 * transaction that zeroes the last receivable refunds the credit balance and closes the obligation, from the repayment and
 * from the servicing step alike. Over {@link BnplApplicationFakes} - in-memory {@code bnpl V006}/{@code V008}/{@code V010}
 * (the repayment's key, its floor and allocation ranks, no component over-paid, the closure's uniques, ranks and edge), a
 * ledger that refuses an unbalanced or a second posting, and a unit of work that rolls every fake back. The races across
 * instances and the database's own ranks are {@code BnplRepaymentDatabaseTest}'s.
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
@DisplayName("the customer's repayment from the wallet and the closure (P12-TSK-018)")
class BnplRepaymentsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-018-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplAcceptances acceptances;
    private BnplServicing servicing;
    private BnplRepayments repayments;
    private UUID party;
    private Actor customer;
    private UUID merchant;
    private LedgerAccountId wallet;

    @BeforeEach
    void setUp() {
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
                world.collectionsOver(closures, IDS, CLOCK), world.outbox, IDS, CLOCK);
        repayments = new BnplRepayments(world.repayments, world.servicing, world.purchases, world.terms.store(),
                world.wallets, world.book, closures, world.outbox, IDS, CLOCK);
        party = UUID.randomUUID();
        customer = new Actor(party.toString(), ActorType.CUSTOMER);
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.merchants.payables.put(merchant, world.book.external("payable", IDS.next()));
        wallet = world.book.external("wallet", IDS.next());
        world.book.funds.put(wallet, eur(1_000_00));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    // ------------------------------------------------------------------ F3: allocation and posting

    @Test
    @DisplayName("a partial repayment pays the oldest due item's principal and leaves the remainder due - F3 balanced,"
            + " DR wallet / CR BNPL_PRINCIPAL_DUE, value-dated today, no InstalmentPaid")
    void partialLeavesTheRemainderDue() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        world.events.clear();
        BnplRepayments.Repaid repaid = repayAt("2026-11-02T10:00:00Z", p4, "key-1", eur(100_00));
        assertThat(repaid.replayed()).isFalse();
        assertThat(repaid.closure()).isEmpty();
        BnplPurchaseStore.Repayment repayment = repaid.repayment();
        assertThat(repayment.kind()).isEqualTo(BnplPurchaseStore.Repayment.CUSTOMER);
        assertThat(repayment.valueDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(repayment.allocations()).singleElement().satisfies(line -> {
            assertThat(line.target()).isEqualTo("DUE_ITEM");
            assertThat(line.component()).contains("PRINCIPAL");
            assertThat(line.instalment()).contains(instalment(p4, 2).id());
            assertThat(line.amount()).isEqualTo(eur(100_00));
        });
        List<BnplApplicationFakes.Book.Line> f3 = world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repayment.id());
        assertThat(f3).extracting(line -> world.book.labels.get(line.account()) + " " + line.direction() + " "
                + line.amount().minorUnits()).containsExactly("wallet DEBIT 10000", "BNPL_PRINCIPAL_DUE CREDIT 10000");
        assertThat(f3).allSatisfy(line -> assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 11, 2)));
        BnplLedgerAccounts accounts = accounts(p4);
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(50_00));
        assertThat(balance(accounts.creditBalance())).isEqualTo(eur(0));
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(eur(1_000_00 - 150_00
                - 100_00));
        assertThat(world.events).extracting(EventEnvelope::eventType).doesNotContain("bnpl.InstalmentPaid");
    }

    @Test
    @DisplayName("a repayment that settles an item publishes InstalmentPaid with what it paid by component - never the"
            + " party, the wallet or the key")
    void settlingAnItemPublishesInstalmentPaid() {
        Agreement p4 = payIn4();
        serviceAt("2026-11-14T09:00:00Z", p4);
        world.events.clear();
        world.payloads.clear();
        BnplRepayments.Repaid repaid = repayAt("2026-11-14T12:00:00Z", p4, "secret-key-7", eur(200_00));
        assertThat(repaid.repayment().allocations()).extracting(BnplPurchaseStore.Allocation::instalment)
                .containsExactly(Optional.of(instalment(p4, 2).id()), Optional.of(instalment(p4, 3).id()));
        assertThat(world.events).extracting(EventEnvelope::eventType).containsExactly("bnpl.InstalmentPaid");
        assertThat(world.payloads.get(0)).contains(instalment(p4, 2).id().toString()).contains("\"principalMinor\":\"15000\"")
                .contains(repaid.repayment().id().toString()).doesNotContain(party.toString())
                .doesNotContain(wallet.value().toString()).doesNotContain("secret-key-7");
        assertThat(balance(accounts(p4).principalDue())).as("instalment 3 partly paid").isEqualTo(eur(100_00));
    }

    @Test
    @DisplayName("more than is due goes to the credit balance - never principal not yet due - and is applied at the next"
            + " billing (F3a at its birth)")
    void overGoesToTheCreditBalanceAndIsAppliedAtTheNextBilling() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        BnplRepayments.Repaid repaid = repayAt("2026-11-01T10:00:00Z", p4, "key-over", eur(200_00));
        assertThat(repaid.repayment().allocations()).extracting(BnplPurchaseStore.Allocation::target)
                .containsExactly("DUE_ITEM", "CREDIT_BALANCE");
        BnplLedgerAccounts accounts = accounts(p4);
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts.creditBalance())).isEqualTo(eur(50_00));
        assertThat(balance(accounts.principal())).as("principal not yet due untouched").isEqualTo(eur(300_00));
        assertThat(world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repaid.repayment().id()))
                .extracting(line -> world.book.labels.get(line.account()) + " " + line.direction() + " "
                        + line.amount().minorUnits())
                .containsExactly("wallet DEBIT 20000", "BNPL_PRINCIPAL_DUE CREDIT 15000",
                        "BNPL_CREDIT_BALANCE CREDIT 5000");

        ServicingOutcome next = serviceAt("2026-11-14T09:00:00Z", p4);
        assertThat(next.appliedAtBilling()).isEqualTo(1);
        assertThat(balance(accounts.creditBalance())).isEqualTo(eur(0));
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(100_00));
    }

    @Test
    @DisplayName("money before anything is due is held whole - a repayment never pays principal not yet due (B6, A7)")
    void earlyMoneyIsHeld() {
        Agreement p4 = payIn4();
        BnplRepayments.Repaid repaid = repayAt("2026-10-20T10:00:00Z", p4, "key-early", eur(40_00));
        assertThat(repaid.repayment().allocations()).singleElement()
                .satisfies(line -> assertThat(line.target()).isEqualTo("CREDIT_BALANCE"));
        assertThat(balance(accounts(p4).principal())).isEqualTo(eur(450_00));
        assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(40_00));
    }

    // ------------------------------------------------------------------ idempotency and conservation

    @Test
    @DisplayName("the same repayment twice under one key repays once - the second converges on the first, one entry"
            + " (INV-IDEM-01); the key with another amount is refused, nothing written")
    void theSameRepaymentTwiceRepaysOnce() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        BnplRepayments.Repaid first = repayAt("2026-11-01T10:00:00Z", p4, "once", eur(60_00));
        int keys = world.book.keys.size();
        BnplRepayments.Repaid again = repayAt("2026-11-01T10:00:01Z", p4, "once", eur(60_00));
        assertThat(again.replayed()).isTrue();
        assertThat(again.repayment().id()).isEqualTo(first.repayment().id());
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.purchases.repayments.values())
                .filteredOn(row -> row.kind().equals(BnplPurchaseStore.Repayment.CUSTOMER)).hasSize(1);
        assertThatThrownBy(() -> repayAt("2026-11-01T10:00:02Z", p4, "once", eur(61_00)))
                .isInstanceOf(BnplRepayments.RepaymentKeyReused.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(balance(accounts(p4).principalDue())).isEqualTo(eur(90_00));
    }

    @Test
    @DisplayName("two repayments on one instalment and ten keys on one obligation serialise on B4 - each allocates against"
            + " what it finds: Σ allocations = Σ amounts, no due account negative, never over-allocated (scenario 6)")
    void tenKeysOneObligationConserve() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        Money paid = eur(0);
        for (int key = 0; key < 10; key++) {
            BnplRepayments.Repaid repaid = repayAt("2026-11-01T10:00:0" + key + "Z", p4, "k-" + key, eur(20_00));
            Money allocated = eur(0);
            for (BnplPurchaseStore.Allocation line : repaid.repayment().allocations()) {
                allocated = allocated.plus(line.amount());
            }
            assertThat(allocated).isEqualTo(repaid.repayment().amount());
            paid = paid.plus(allocated);
            BnplLedgerAccounts accounts = accounts(p4);
            assertThat(balance(accounts.principalDue()).isNegative()).isFalse();
        }
        BnplLedgerAccounts accounts = accounts(p4);
        assertThat(paid).isEqualTo(eur(200_00));
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts.creditBalance())).as("200 - 150 held").isEqualTo(eur(50_00));
        Money toPrincipal = eur(0);
        for (BnplPurchaseStore.Repayment row : world.purchases.repayments.values()) {
            for (BnplPurchaseStore.Allocation line : row.allocations()) {
                if (line.instalment().filter(instalment(p4, 2).id()::equals).isPresent()) {
                    toPrincipal = toPrincipal.plus(line.amount());
                }
            }
        }
        assertThat(toPrincipal).as("instalment 2 paid exactly once in full").isEqualTo(eur(150_00));
    }

    @Test
    @DisplayName("a repayment racing the billing on the due date conserves in either order: before the billing it is"
            + " held and applied at the billing's birth; after it pays the bill")
    void aRepaymentRacingTheBillingOnTheDueDateConserves() {
        Agreement before = payIn4();
        repayAt("2026-10-31T00:00:01Z", before, "race", eur(150_00));
        assertThat(balance(accounts(before).creditBalance())).isEqualTo(eur(150_00));
        serviceAt("2026-10-31T00:00:02Z", before);
        assertThat(balance(accounts(before).principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts(before).creditBalance())).isEqualTo(eur(0));

        Agreement after = payIn4();
        serviceAt("2026-10-31T00:00:01Z", after);
        repayAt("2026-10-31T00:00:02Z", after, "race", eur(150_00));
        assertThat(balance(accounts(after).principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts(after).creditBalance())).isEqualTo(eur(0));
    }

    // ------------------------------------------------------------------ the closure

    @Test
    @DisplayName("the last repayment closes and refunds: CLOSED (REPAID), the excess refunded under"
            + " bnpl.closure-refund:<obligation>, all six balances zero, BnplAgreementClosed - nothing owed either way")
    void theLastRepaymentClosesAndRefunds() {
        Agreement p4 = payIn4();
        serviceAt("2026-11-28T09:00:00Z", p4);
        world.events.clear();
        BnplRepayments.Repaid repaid = repayAt("2026-11-28T12:00:00Z", p4, "last", eur(480_00));
        BnplClosure closure = repaid.closure().orElseThrow();
        assertThat(closure.reason()).isEqualTo(BnplClosureReason.REPAID);
        assertThat(closure.refund()).isEqualTo(eur(30_00));
        assertThat(closure.repayment()).contains(repaid.repayment().id());
        assertThat(world.book.linesOf(BnplClosures.REFUND_KEY_PREFIX + p4.obligation().value()))
                .extracting(line -> world.book.labels.get(line.account()) + " " + line.direction() + " "
                        + line.amount().minorUnits())
                .containsExactly("BNPL_CREDIT_BALANCE DEBIT 3000", "wallet CREDIT 3000");
        BnplLedgerAccounts accounts = accounts(p4);
        for (com.finapp.ledger.LedgerAccount account : accounts.all()) {
            assertThat(balance(account)).as(account.purpose().name()).isEqualTo(eur(0));
        }
        assertThat(world.purchases.obligations.get(p4.obligation().value()).status())
                .isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(world.purchases.history).contains("obligation CLOSED");
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).as("the customer paid the gross")
                .isEqualTo(eur(1_000_00 - GROSS));
        assertThat(world.events).extracting(EventEnvelope::eventType).contains("bnpl.BnplAgreementClosed");
        assertThat(world.payloads.get(world.payloads.size() - 1)).contains("REPAID").contains("\"refundMinor\":\"3000\"")
                .doesNotContain(party.toString()).doesNotContain(wallet.value().toString());
    }

    @Test
    @DisplayName("a credit balance that settles the obligation closes it at servicing - the last billing's application"
            + " pays it, the step closes it and refunds the rest, no repayment named")
    void aCreditBalanceThatSettlesTheObligationClosesAtServicing() {
        Agreement p4 = payIn4();
        repayAt("2026-10-20T10:00:00Z", p4, "ahead", eur(500_00));
        ServicingOutcome early = serviceAt("2026-11-14T09:00:00Z", p4);
        assertThat(early.closed()).as("instalment 4 not yet billed").isFalse();
        try (SecurityContext.Scope scope = SecurityContext.enter(SYSTEM)) {
            ServicingOutcome last = serviceAt("2026-11-28T09:00:00Z", p4);
            assertThat(last.closed()).isTrue();
        }
        BnplClosure closure = world.repayments.closures.get(p4.obligation().value());
        assertThat(closure.repayment()).isEmpty();
        assertThat(closure.refund()).isEqualTo(eur(50_00));
        assertThat(world.purchases.obligations.get(p4.obligation().value()).status())
                .isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(0));
        // A closed obligation is not claimed again: nothing services it.
        world.now = Instant.parse("2026-11-29T09:00:00Z");
        assertThat(world.inTransaction(() -> servicing.claim(BnplApplicationFakes.UOW, 20, Duration.ofMinutes(15))))
                .doesNotContain(p4.obligation());
        assertThat(world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, p4.obligation())).kind())
                .isEqualTo(ServicingOutcome.Kind.NOT_SERVICED);
    }

    @Test
    @DisplayName("a wallet that cannot be credited leaves a settled obligation open with its credit balance - nothing"
            + " written; the step closes it once it can")
    void aRefundWithNoWalletLeavesItOpen() {
        Agreement p4 = payIn4();
        repayAt("2026-10-20T10:00:00Z", p4, "ahead", eur(500_00));
        world.wallets.byParty.remove(party);
        try (SecurityContext.Scope scope = SecurityContext.enter(SYSTEM)) {
            assertThat(serviceAt("2026-11-28T09:00:00Z", p4).closed()).isFalse();
            assertThat(world.repayments.closures).isEmpty();
            assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(50_00));
            world.wallets.byParty.put(party, wallet);
            assertThat(serviceAt("2026-11-29T09:00:00Z", p4).closed()).isTrue();
        }
    }

    @Test
    @DisplayName("a pay-in-N obligation is not closed over an unaccrued day: every receivable zero and every instalment"
            + " billed, but the accrual record planted behind (dates of the span that ended with principal outstanding"
            + " unaccrued) - the repayment does not close it; with the record caught up the step does")
    void anObligationIsNotClosedOverAnUnaccruedDay() {
        Agreement pn = payInN();
        BnplPurchaseStore.StoredSchedule schedule = world.purchases.schedules.get(pn.obligation().value());
        LocalDate last = schedule.instalments().get(schedule.instalments().size() - 1).dueDate();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(last); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        serviceAt(last.atTime(9, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        BnplLedgerAccounts accounts = accounts(pn);
        Money owed = balance(accounts.principalDue()).plus(balance(accounts.interestDue()));
        world.book.funds.put(wallet, eur(10_000_00));
        // The planted record: the span's last ten dates' rows gone - their interest was billed, but the record says they
        // never accrued (a state no writer reaches - T-acc is oldest first and the billing waits on it - planted to
        // prove the closure asks the record and not only the ledger).
        List<BnplApplicationFakes.Servicing.Accrual> removed = world.servicing.accruals.stream()
                .filter(row -> row.obligation().equals(pn.obligation()) && !row.date().isBefore(last.minusDays(10)))
                .toList();
        world.servicing.accruals.removeAll(removed);
        BnplRepayments.Repaid repaid = repayAt(last.atTime(18, 0).toInstant(ZoneOffset.UTC).toString(), pn, "all", owed);
        assertThat(repaid.closure()).as("ten dates of the span ended with principal outstanding, unaccrued").isEmpty();
        assertThat(balance(accounts.principalDue()).plus(balance(accounts.interestDue()))).isEqualTo(eur(0));
        assertThat(world.purchases.obligations.get(pn.obligation().value()).status())
                .isEqualTo(BnplObligationStatus.ACTIVE);
        world.servicing.accruals.addAll(removed);
        try (SecurityContext.Scope scope = SecurityContext.enter(SYSTEM)) {
            assertThat(serviceAt(last.plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC).toString(), pn).closed())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a pay-in-N obligation repaid in full two days after its final due date closes at once - nothing accrues"
            + " at or after the final due date (P12-TSK-017's span), so no elapsed accruable date is open")
    void aPnRepaidAfterItsFinalDueDateClosesAtOnce() {
        Agreement pn = payInN();
        BnplPurchaseStore.StoredSchedule schedule = world.purchases.schedules.get(pn.obligation().value());
        LocalDate last = schedule.instalments().get(schedule.instalments().size() - 1).dueDate();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(last); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        serviceAt(last.atTime(9, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        BnplLedgerAccounts accounts = accounts(pn);
        Money owed = balance(accounts.principalDue()).plus(balance(accounts.interestDue()));
        world.book.funds.put(wallet, eur(10_000_00));
        BnplRepayments.Repaid repaid = repayAt(last.plusDays(2).atTime(10, 0).toInstant(ZoneOffset.UTC).toString(), pn,
                "all", owed);
        assertThat(repaid.closure()).isPresent();
        assertThat(world.purchases.obligations.get(pn.obligation().value()).status())
                .isEqualTo(BnplObligationStatus.CLOSED);
    }

    @Test
    @DisplayName("a pay-in-N obligation repaid on its last due date before the day ends closes at once - no date ended"
            + " unaccrued with principal outstanding")
    void aPnRepaidOnItsLastDueDateCloses() {
        Agreement pn = payInN();
        BnplPurchaseStore.StoredSchedule schedule = world.purchases.schedules.get(pn.obligation().value());
        LocalDate last = schedule.instalments().get(schedule.instalments().size() - 1).dueDate();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(last); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        serviceAt(last.atTime(9, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        BnplLedgerAccounts accounts = accounts(pn);
        Money owed = balance(accounts.principalDue()).plus(balance(accounts.interestDue()));
        world.book.funds.put(wallet, eur(10_000_00));
        BnplRepayments.Repaid repaid = repayAt(last.atTime(18, 0).toInstant(ZoneOffset.UTC).toString(), pn, "all", owed);
        assertThat(repaid.closure()).isPresent();
        assertThat(repaid.repayment().allocations()).extracting(BnplPurchaseStore.Allocation::component)
                .contains(Optional.of("INTEREST"), Optional.of("PRINCIPAL"));
    }

    @Test
    @DisplayName("a closed obligation refuses a repayment - 409 bnpl.ObligationClosed, nothing posted (INV-LIFE-04)")
    void aClosedObligationRefuses() {
        Agreement p4 = payIn4();
        serviceAt("2026-11-28T09:00:00Z", p4);
        repayAt("2026-11-28T12:00:00Z", p4, "last", eur(450_00));
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> repayAt("2026-11-29T12:00:00Z", p4, "after", eur(10_00)))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.purchases.repayments.values()).filteredOn(row -> row.requestKey().equals("after")).isEmpty();
    }

    // ------------------------------------------------------------------ the wallet, the floor, the standing

    @Test
    @DisplayName("insufficient funds post nothing - short by a cent, no wallet in the currency, a wallet not ACTIVE: 422"
            + " bnpl.InsufficientFunds, no entry, no row (INV-BAL-04)")
    void insufficientFundsPostsNothing() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        int keys = world.book.keys.size();
        Money available = world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        assertThatThrownBy(() -> repayAt("2026-11-01T10:00:00Z", p4, "short", available.plus(eur(1))))
                .isInstanceOf(BnplRepayments.InsufficientFunds.class);
        world.book.statuses.put(wallet, com.finapp.ledger.LedgerAccountStatus.POSTING_SUSPENDED);
        assertThatThrownBy(() -> repayAt("2026-11-01T10:00:01Z", p4, "frozen", eur(10_00)))
                .isInstanceOf(BnplRepayments.InsufficientFunds.class);
        world.book.statuses.put(wallet, com.finapp.ledger.LedgerAccountStatus.ACTIVE);
        world.wallets.byParty.remove(party);
        assertThatThrownBy(() -> repayAt("2026-11-01T10:00:02Z", p4, "none", eur(10_00)))
                .isInstanceOf(BnplRepayments.InsufficientFunds.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.purchases.repayments.values())
                .filteredOn(row -> row.kind().equals(BnplPurchaseStore.Repayment.CUSTOMER)).isEmpty();
        world.wallets.byParty.put(party, wallet);
        assertThat(repayAt("2026-11-01T10:00:03Z", p4, "exact", available).replayed()).as("exactly available is enough")
                .isFalse();
    }

    @Test
    @DisplayName("a suspended customer may still repay - no standing check")
    void aSuspendedCustomerMayStillRepay() {
        Agreement p4 = payIn4();
        world.inGoodStanding = false;
        world.log.clear();
        assertThat(repayAt("2026-10-20T10:00:00Z", p4, "suspended", eur(10_00)).replayed()).isFalse();
        assertThat(world.log).doesNotContain("standing");
    }

    @Test
    @DisplayName("the repayment is floored: the clock stepped back behind the latest billing values it at the floor and"
            + " posts it on today (the Phase 11 gate's R1)")
    void theRepaymentIsFloored() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T00:00:05Z", p4);
        BnplRepayments.Repaid repaid = repayAt("2026-10-30T23:59:58Z", p4, "behind", eur(10_00));
        assertThat(repaid.repayment().valueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repaid.repayment().id())).allSatisfy(line -> {
            assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(line.postingDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        });
    }

    @Test
    @DisplayName("exposure and capital headroom fall by the principal repaid, at commit - never by what is held"
            + " (INV-BNPL-10)")
    void exposureAndHeadroomFallByThePrincipalRepaid() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        Money outstanding = world.book.bnplPrincipal();
        Money headroom = world.capital.underLock(BnplApplicationFakes.UOW, BnplTermsFixtures.EUR).headroom();
        repayAt("2026-11-01T10:00:00Z", p4, "exposure", eur(200_00));
        assertThat(world.book.bnplPrincipal()).as("150 principal repaid, 50 held").isEqualTo(outstanding.minus(eur(150_00)));
        assertThat(world.capital.underLock(BnplApplicationFakes.UOW, BnplTermsFixtures.EUR).headroom())
                .isEqualTo(headroom.plus(eur(150_00)));
    }

    @Test
    @DisplayName("the due state is derived from rows explained by the ledger: a stray line on a due account is an"
            + " anomaly - nothing allocated, nothing written (INV-BNPL-01, INV-BAL-05)")
    void theDueStateIsDerivedFromRowsNotTheProjection() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        BnplLedgerAccounts accounts = accounts(p4);
        world.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:stray", accounts.principalDue().id(),
                Direction.DEBIT, eur(1_00), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)));
        world.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:stray", wallet, Direction.CREDIT, eur(1_00),
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)));
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> repayAt("2026-11-01T10:00:00Z", p4, "drift", eur(151_00)))
                .isInstanceOf(AllocationAnomaly.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.purchases.repayments.values())
                .filteredOn(row -> row.kind().equals(BnplPurchaseStore.Repayment.CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("the repayment takes the obligation (B4), then the wallet alone (P), then the projection rows (L8), then"
            + " posts - the Phase 12 lock order; no payable, no capital, no profile")
    void repaymentTakesTheObligationThenTheWallet() {
        Agreement p4 = payIn4();
        serviceAt("2026-11-28T09:00:00Z", p4);
        world.log.clear();
        repayAt("2026-11-28T12:00:00Z", p4, "order", eur(460_00));
        assertThat(world.log).startsWith("B4");
        assertThat(world.log).containsSubsequence("B4", "lock wallet", "available wallet", "L8", "post bnpl.repayment:",
                "repayment insert", "post bnpl.closure-refund:", "closure insert", "obligation CLOSED");
        assertThat(world.log).doesNotContain("lock payable", "L6", "lock profile");
        assertThat(world.book.projections.get(world.book.projections.size() - 2)).as("the repayment's L8")
                .contains(wallet, accounts(p4).creditBalance().id(), accounts(p4).principalDue().id());
    }

    @Test
    @DisplayName("another party's agreement is not found and never locked; an unknown one alike")
    void anotherPartysAgreementIsNotFound() {
        Agreement p4 = payIn4();
        UUID stranger = UUID.randomUUID();
        world.now = Instant.parse("2026-10-20T10:00:00Z");
        assertThatThrownBy(() -> world.inTransaction(() -> repayments.repay(BnplApplicationFakes.UOW, p4.agreement(),
                stranger, new BnplRepayments.Request("k", eur(10_00)), new Actor(stranger.toString(), ActorType.CUSTOMER),
                CORRELATION))).isInstanceOf(BnplRepayments.AgreementNotFound.class);
        assertThat(world.repayments.lockedAgreements).isEmpty();
        assertThat(world.inTransaction(() -> repayments.history(BnplApplicationFakes.UOW, p4.agreement(), stranger)))
                .isEmpty();
    }

    @Test
    @DisplayName("an amount not positive, or not in the agreement's currency and scale, is refused before any write")
    void anInvalidAmountIsRefused() {
        Agreement p4 = payIn4();
        for (Money invalid : List.of(eur(0), Money.of(new BigDecimal("1.00"), CurrencyCode.of("USD")))) {
            assertThatThrownBy(() -> repayAt("2026-10-20T10:00:00Z", p4, "bad", invalid))
                    .isInstanceOf(BnplRepayments.AmountInvalid.class);
        }
        assertThat(world.purchases.repayments.values())
                .filteredOn(row -> row.kind().equals(BnplPurchaseStore.Repayment.CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("the history is the owner's repayments newest first - instalment 1's at purchase among them")
    void theHistoryIsNewestFirst() {
        Agreement p4 = payIn4();
        repayAt("2026-10-20T10:00:00Z", p4, "a", eur(10_00));
        repayAt("2026-10-21T10:00:00Z", p4, "b", eur(20_00));
        List<BnplPurchaseStore.Repayment> history = world.inTransaction(() -> repayments.history(
                BnplApplicationFakes.UOW, p4.agreement(), party)).orElseThrow();
        assertThat(history).extracting(BnplPurchaseStore.Repayment::requestKey).startsWith("b", "a");
        assertThat(history).extracting(BnplPurchaseStore.Repayment::kind)
                .contains(BnplPurchaseStore.Repayment.AT_PURCHASE);
    }

    @Test
    @DisplayName("a failure after the posting rolls the whole repayment back - the entry, the row, the event, the closure")
    void aFailureWritesNothing() {
        Agreement p4 = payIn4();
        serviceAt("2026-11-28T09:00:00Z", p4);
        int keys = world.book.keys.size();
        world.events.clear();
        world.failAt = "closure insert";
        assertThatThrownBy(() -> repayAt("2026-11-28T12:00:00Z", p4, "crash", eur(450_00)))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.events).isEmpty();
        assertThat(world.repayments.closures).isEmpty();
        assertThat(world.purchases.obligations.get(p4.obligation().value()).status())
                .isEqualTo(BnplObligationStatus.ACTIVE);
        world.failAt = null;
        assertThat(repayAt("2026-11-28T12:00:01Z", p4, "crash", eur(450_00)).closure()).isPresent();
    }

    @Test
    @DisplayName("the allocation never pays principal not yet due, and conserves its amount - an engine line that would is"
            + " an anomaly")
    void theAllocationIsHeldToHoldAsCredit() {
        com.finapp.creditmath.DueState nothingDue = new com.finapp.creditmath.DueState(List.of(), eur(300_00));
        com.finapp.creditmath.AllocationRules paysDown = new com.finapp.creditmath.AllocationRules(
                com.finapp.creditmath.EngineVersion.ALLOCATION_ENGINE_V1,
                com.finapp.creditmath.AllocationSequence.OLDEST_DUE_FIRST,
                List.of(AllocationComponent.FEES, AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL),
                com.finapp.creditmath.OverpaymentTreatment.PAY_DOWN_PRINCIPAL);
        assertThatThrownBy(() -> BnplRepayments.allocate(eur(10_00), nothingDue, paysDown))
                .isInstanceOf(AllocationAnomaly.class).hasMessageContaining("not yet due");
    }

    // ------------------------------------------------------------------ helpers

    /** An accepted purchase's agreement and obligation. */
    record Agreement(BnplAgreementId agreement, BnplObligationId obligation) {}

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
        return new Agreement(accepted.agreement().id(), accepted.obligation().id());
    }

    private BnplPurchaseStore.StoredInstalment instalment(Agreement agreement, int sequence) {
        return world.purchases.schedules.get(agreement.obligation().value()).instalments().stream()
                .filter(row -> row.sequence() == sequence).findFirst().orElseThrow();
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
