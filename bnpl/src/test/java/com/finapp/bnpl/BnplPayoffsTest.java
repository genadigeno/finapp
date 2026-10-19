package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Early payoff, hermetic (`P12-TSK-022`; {@code PHASE_12_PLAN.md} sections 7.1 T-pay, 7.4 scenario 11, 12.11 F6; A21, L8;
 * {@code INV-LND-08} by analogy, {@code INV-LND-02}, {@code INV-IDEM-01}, {@code INV-LIFE-04}, {@code INV-BNPL-01},
 * {@code INV-BNPL-10}): the quote - pay-in-4 its unpaid instalments, pay-in-N the derived outstanding and the projection to
 * {@code G - 1}, which T-acc then posts to the cent - and its execution: every unbilled instalment billed, the held credit
 * applied first, the amount recomputed under B4 (stale, never adjusted), one {@code PAYOFF} repayment, the credit balance
 * refunded, {@code CLOSED (PAID_OFF_EARLY)}, every fact of the payoff dated {@code max(today, floor)}; once per quote and per
 * obligation; and both orders of scenario 11. Over {@link BnplApplicationFakes} - in-memory {@code bnpl V006}...{@code V014}
 * (the billing's early arm beside its execution, the floor's payoff arm, the {@code PAYOFF} repayment and the
 * {@code PAID_OFF_EARLY} closure only beside an execution, the execution's date and value-date ranks, and at commit the
 * execution closed), a ledger that refuses an unbalanced or a second posting, and a unit of work that rolls every fake
 * back. The races across instances and the database's own ranks are {@code BnplPayoffDatabaseTest}'s.
 */
@DisplayName("early payoff, both products (P12-TSK-022)")
class BnplPayoffsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-022-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplAcceptances acceptances;
    private BnplServicing servicing;
    private BnplRepayments repayments;
    private BnplRefunds refunds;
    private BnplAccruals accruals;
    private BnplPayoffs payoffs;
    private UUID party;
    private Actor customer;
    private UUID merchant;
    private Actor merchantActor;
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
                world.collectionsOver(closures, IDS, CLOCK), world.conditionsOver(IDS, CLOCK), world.outbox, IDS,
                CLOCK);
        repayments = new BnplRepayments(world.repayments, world.servicing, world.purchases, world.terms.store(),
                world.wallets, world.book, closures, world.outbox, IDS, CLOCK);
        refunds = new BnplRefunds(world.refunds, world.servicing, world.purchases, world.terms.store(), world.merchants,
                world.wallets, world.book, closures, (uow, actor) -> Optional.empty(), world.outbox, world.audit, IDS,
                CLOCK);
        accruals = new BnplAccruals(world.servicing, world.accruals, world.purchases, world.terms.store(), world.book,
                IDS);
        payoffs = world.payoffsOver(servicing, repayments, closures, IDS);
        party = UUID.randomUUID();
        customer = new Actor(party.toString(), ActorType.CUSTOMER);
        merchant = UUID.randomUUID();
        merchantActor = new Actor(merchant.toString(), ActorType.MERCHANT);
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.merchants.payables.put(merchant, world.book.external("payable", IDS.next()));
        wallet = world.book.external("wallet", IDS.next());
        world.book.funds.put(wallet, eur(1_000_00));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    // ------------------------------------------------------------------ pay-in-4: the unpaid instalments

    @Test
    @DisplayName("pay-in-4's payoff is its unpaid instalments: 450.00 on day 3 - instalments 2, 3 and 4 billed early (their"
            + " entries valued the payoff's date, never their due dates), one PAYOFF repayment of 450 (DR wallet / CR"
            + " BNPL_PRINCIPAL_DUE), CLOSED (PAID_OFF_EARLY), every BNPL account zero, no fee")
    void p4PayoffIsTheUnpaidInstalments() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "quote-1", Optional.empty()).quote();
        assertThat(quote.businessDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(quote.goodThrough()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(quote.principal()).isEqualTo(eur(450_00));
        assertThat(quote.principalDue()).isEqualTo(eur(0));
        assertThat(quote.interestAccrued().plus(quote.interestDue()).plus(quote.projectedInterest())).isEqualTo(eur(0));
        assertThat(quote.amount()).isEqualTo(eur(450_00));
        assertThat(quote.payable()).isEqualTo(eur(450_00));
        assertThat(quote.refund()).isEqualTo(eur(0));
        assertThat(quote.accrualEngine()).isEmpty();
        world.events.clear();

        BnplPayoffs.Executed executed = executeAt("2026-10-20T10:00:00Z", p4, "pay-1", quote.id());
        assertThat(executed.replayed()).isFalse();
        assertThat(executed.execution().businessDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(executed.execution().valueDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        BnplPurchaseStore.Repayment repayment = executed.repayment().orElseThrow();
        assertThat(repayment.kind()).isEqualTo(BnplPurchaseStore.Repayment.PAYOFF);
        assertThat(repayment.requestKey()).isEqualTo(quote.id().toString());
        assertThat(repayment.amount()).isEqualTo(eur(450_00));
        assertThat(repayment.valueDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(repayment.allocations()).extracting(line -> line.target() + " " + line.component().orElse("-") + " "
                + line.amount().minorUnits()).containsExactly("DUE_ITEM PRINCIPAL 15000", "DUE_ITEM PRINCIPAL 15000",
                "DUE_ITEM PRINCIPAL 15000");
        assertThat(labelled(world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repayment.id())))
                .containsExactly("wallet DEBIT 45000", "BNPL_PRINCIPAL_DUE CREDIT 45000");
        // The early billings: dated their due dates as rows (billed is judged by due date), their entries the payoff's.
        for (int sequence = 2; sequence <= 4; sequence++) {
            BnplPurchaseStore.StoredInstalment instalment = instalment(p4, sequence);
            assertThat(world.purchases.billedOn(p4.obligation(), instalment.dueDate())).isTrue();
            assertThat(world.book.linesOf(BnplServicing.BILLING_KEY_PREFIX + instalment.id()))
                    .allSatisfy(line -> assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 10, 20)));
        }
        assertThat(executed.closure().reason()).isEqualTo(BnplClosureReason.PAID_OFF_EARLY);
        assertThat(executed.closure().refund()).isEqualTo(eur(0));
        assertThat(executed.closure().repayment()).contains(repayment.id());
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(obligation(p4).closureReason()).contains(BnplClosureReason.PAID_OFF_EARLY);
        assertEveryAccountZero(p4);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(eur(1_000_00 - 600_00));
        assertThat(world.events).extracting(EventEnvelope::eventType).containsExactly("bnpl.InstalmentDue",
                "bnpl.InstalmentDue", "bnpl.InstalmentDue", "bnpl.InstalmentPaid", "bnpl.InstalmentPaid",
                "bnpl.InstalmentPaid", "bnpl.BnplAgreementClosed");
        assertThat(world.payloads.get(world.payloads.size() - 1)).contains("PAID_OFF_EARLY")
                .doesNotContain(party.toString()).doesNotContain(wallet.value().toString());
        // No fee (L8): nothing posted to any income or fee account.
        assertThat(world.book.lines).noneMatch(line -> world.book.labels.getOrDefault(line.account(), "")
                .equals(AccountPurpose.BNPL_FEE_INCOME.name()));
        // The capital headroom and the exposure count BNPL principal: none is left (INV-BNPL-10).
        assertThat(world.book.bnplPrincipal()).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("pay-in-4 mid-life: instalment 2 billed and unpaid (delinquent - a payoff is permitted in any condition)"
            + " and a held 40.00: the quote is 450 - 40 = 410; the execution applies the credit at instalment 3's billing"
            + " and debits the wallet 410 - never 450 with 40 refunded (A4-M1)")
    void payoffIsPermittedInAnyCondition() {
        Agreement p4 = payIn4();
        repayAt("2026-10-20T10:00:00Z", p4, "early", eur(40_00));
        serviceAt("2026-10-31T09:00:00Z", p4);
        assertThat(balance(accounts(p4).principalDue())).as("40 applied at billing, 110 overdue").isEqualTo(eur(110_00));
        repayAt("2026-11-01T09:00:00Z", p4, "more", eur(150_00));
        assertThat(balance(accounts(p4).creditBalance())).as("40 held again").isEqualTo(eur(40_00));
        BnplPayoffQuote quote = quoteAt("2026-11-10T09:00:00Z", p4, "q", Optional.empty()).quote();
        assertThat(quote.amount()).isEqualTo(eur(300_00 - 40_00));
        BnplPayoffs.Executed executed = executeAt("2026-11-10T09:30:00Z", p4, "x", quote.id());
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(eur(260_00));
        assertThat(executed.closure().refund()).isEqualTo(eur(0));
        assertEveryAccountZero(p4);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet))
                .isEqualTo(eur(1_000_00 - 150_00 - 40_00 - 150_00 - 260_00));
    }

    @Test
    @DisplayName("an assessed late fee is in the quote and paid by the payoff (P12-TSK-020's hand-off, board rule 4): EUR"
            + " 5.00 on instalment 2 at its age 5 - BNPL_FEES_DUE counted from the ledger, 300 + 150 + 5 = 455.00, the"
            + " PAYOFF repayment paying FEES first, every account zero, and no fee or condition after CLOSED")
    void anAssessedLateFeeIsPaidOff() {
        world.terms.activate(BnplTermsFixtures.payIn4().lateFee(eur(5_00)).build(), 5);
        Agreement p4 = payIn4();
        ServicingOutcome fee = serviceAt("2026-11-05T09:00:00Z", p4);
        assertThat(fee.fees()).isEqualTo(1);
        assertThat(balance(accounts(p4).feesDue())).isEqualTo(eur(5_00));
        BnplPayoffQuote quote = quoteAt("2026-11-05T10:00:00Z", p4, "q", Optional.empty()).quote();
        assertThat(quote.feesDue()).isEqualTo(eur(5_00));
        assertThat(quote.principalDue()).isEqualTo(eur(150_00));
        assertThat(quote.principal()).isEqualTo(eur(300_00));
        assertThat(quote.amount()).isEqualTo(eur(455_00));
        BnplPayoffs.Executed executed = executeAt("2026-11-05T10:01:00Z", p4, "x", quote.id());
        assertThat(executed.repayment().orElseThrow().allocations()).extracting(line -> line.component().orElse("-")
                + " " + line.amount().minorUnits()).containsExactly("FEES 500", "PRINCIPAL 15000", "PRINCIPAL 15000",
                "PRINCIPAL 15000");
        assertEveryAccountZero(p4);
        int fees = world.conditions.feesOf(p4.obligation()).size();
        int conditions = world.conditions.of(p4.obligation()).size();
        assertThat(serviceAt("2026-11-20T09:00:00Z", p4).kind()).isEqualTo(ServicingOutcome.Kind.NOT_SERVICED);
        assertThat(world.conditions.feesOf(p4.obligation())).hasSize(fees);
        assertThat(world.conditions.of(p4.obligation())).hasSize(conditions);
    }

    @Test
    @DisplayName("held credit beside an item already due - every instalment billed, so no billing applies it - is applied"
            + " by the catch-up application before the wallet is debited: the wallet pays 410, never 450 (the Phase 11"
            + " gate's A4-M1)")
    void heldCreditIsAppliedBeforeTheWalletIsDebited() {
        Agreement p4 = payIn4();
        serviceAt("2026-11-28T09:00:00Z", p4);
        assertThat(balance(accounts(p4).principalDue())).isEqualTo(eur(450_00));
        // A credit held beside the due items - as a refund's excess to an unpostable wallet leaves it (P12-TSK-021).
        plantCredit(p4, eur(40_00));
        BnplPayoffQuote quote = quoteAt("2026-11-28T10:00:00Z", p4, "q", Optional.empty()).quote();
        assertThat(quote.creditBalance()).isEqualTo(eur(40_00));
        assertThat(quote.amount()).isEqualTo(eur(410_00));
        Money before = world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        BnplPayoffs.Executed executed = executeAt("2026-11-28T11:00:00Z", p4, "x", quote.id());
        assertThat(world.servicing.applications.values()).filteredOn(row -> row.obligation().equals(p4.obligation())
                && row.catchUp().isPresent()).singleElement().satisfies(row -> {
                    assertThat(row.amount()).isEqualTo(eur(40_00));
                    assertThat(row.valueDate()).isEqualTo(LocalDate.of(2026, 11, 28));
                });
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(eur(410_00));
        assertThat(executed.closure().refund()).isEqualTo(eur(0));
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(before.minus(eur(410_00)));
        assertEveryAccountZero(p4);
    }

    @Test
    @DisplayName("a credit balance beyond everything owed: the quote is negative (-50.00: nothing payable, 50.00 back) and"
            + " the execution makes no repayment - the billings' applications settle everything and the closure refunds"
            + " the 50")
    void aCreditBeyondEverythingIsRefunded() {
        Agreement p4 = payIn4();
        repayAt("2026-10-20T10:00:00Z", p4, "over", eur(500_00));
        BnplPayoffQuote quote = quoteAt("2026-10-21T09:00:00Z", p4, "q", Optional.empty()).quote();
        assertThat(quote.amount()).isEqualTo(eur(-50_00));
        assertThat(quote.payable()).isEqualTo(eur(0));
        assertThat(quote.refund()).isEqualTo(eur(50_00));
        BnplPayoffs.Executed executed = executeAt("2026-10-21T10:00:00Z", p4, "x", quote.id());
        assertThat(executed.repayment()).isEmpty();
        assertThat(executed.closure().refund()).isEqualTo(eur(50_00));
        assertThat(executed.closure().reason()).isEqualTo(BnplClosureReason.PAID_OFF_EARLY);
        assertThat(world.purchases.repayments.values()).noneMatch(row -> row.kind().equals(BnplPurchaseStore.Repayment.PAYOFF));
        assertEveryAccountZero(p4);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet))
                .isEqualTo(eur(1_000_00 - 150_00 - 500_00 + 50_00));
    }

    // ------------------------------------------------------------------ pay-in-N: accrued first, projected to G - 1

    @Test
    @DisplayName("pay-in-N: a quote on a record behind its accrual writes nothing and asks for T-acc first; caught up, it"
            + " projects [today, G - 1] on the current base - 3.29 accrued, 1.64 projected, 604.93 - and on G T-acc posts"
            + " exactly the projection, the execution recomputes the same figure and pays it")
    void pnPayoffAccruesFirstAndProjectsToTheGoodThroughDate() {
        Agreement pn = payInN();
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> quoteAt("2026-10-27T10:00:00Z", pn, "q", Optional.of(LocalDate.of(2026, 11, 1))))
                .isInstanceOf(BnplPayoffs.BehindAccrual.class);
        assertThat(world.payoffs.quotes).isEmpty();
        assertThat(world.book.keys).hasSize(keys);
        catchUpAt("2026-10-27T10:00:00Z", pn);
        BnplPayoffQuote quote = quoteAt("2026-10-27T10:00:01Z", pn, "q", Optional.of(LocalDate.of(2026, 11, 1))).quote();
        assertThat(quote.principal()).isEqualTo(eur(600_00));
        assertThat(quote.interestAccrued()).as("10 dates, 600 x 19.99% / 365, cumulative").isEqualTo(eur(3_29));
        assertThat(quote.projectionBase()).isEqualTo(eur(600_00));
        assertThat(quote.projectedDays()).isEqualTo(5);
        assertThat(quote.projectedInterest()).isEqualTo(eur(1_64));
        assertThat(quote.amount()).isEqualTo(eur(604_93));
        assertThat(quote.accrualEngine()).contains("ACCRUAL_ENGINE_V1");

        assertThatThrownBy(() -> executeAt("2026-11-01T09:00:00Z", pn, "x", quote.id()))
                .isInstanceOf(BnplPayoffs.BehindAccrual.class);
        assertThat(world.payoffs.executions).isEmpty();
        catchUpAt("2026-11-01T09:00:00Z", pn);
        Money accruedAfterQuote = Money.ofMinorUnits(0, BnplTermsFixtures.EUR);
        for (BnplInterestAccrual row : world.accruals.of(pn.obligation())) {
            if (!row.accrual().date().isBefore(LocalDate.of(2026, 10, 27))) {
                accruedAfterQuote = accruedAfterQuote.plus(row.accrual().posted());
            }
        }
        assertThat(accruedAfterQuote).as("T-acc posts exactly what the quote projected").isEqualTo(quote.projectedInterest());
        BnplPayoffs.Executed executed = executeAt("2026-11-01T09:00:01Z", pn, "x", quote.id());
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(eur(604_93));
        assertThat(executed.repayment().orElseThrow().allocations()).extracting(line -> line.component().orElse("-")
                + " " + line.amount().minorUnits()).containsExactly("INTEREST 493", "PRINCIPAL " + firstPrincipal(pn),
                "PRINCIPAL " + instalment(pn, 2).amount().minorUnits(),
                "PRINCIPAL " + (600_00 - firstPrincipal(pn) - instalment(pn, 2).amount().minorUnits()));
        assertThat(executed.closure().reason()).isEqualTo(BnplClosureReason.PAID_OFF_EARLY);
        assertEveryAccountZero(pn);
        // The interest earned is exactly what accrued - 15 dates - and what the customer paid.
        assertThat(world.book.balance(world.book.operational.get(AccountPurpose.BNPL_INTEREST_INCOME), false))
                .isEqualTo(eur(4_93));
    }

    @Test
    @DisplayName("pay-in-N after a partial refund (schedule and agreement version 2, recalculated at the instalment, still"
            + " from the purchase date): the quote reads the latest version and the payoff bills its every instalment and"
            + " closes, every account zero")
    void aPnPayoffAfterAPartialRefundPaysTheLatestVersion() {
        Agreement pn = payInN();
        catchUpAt("2026-10-27T00:30:00Z", pn);
        refundAt("2026-10-27T09:00:00Z", pn, "r-pn", eur(200_00));
        assertThat(world.purchases.agreements.get(pn.agreement().value()).version()).isEqualTo(2);
        catchUpAt("2026-11-03T00:30:00Z", pn);
        BnplPayoffQuote quote = quoteAt("2026-11-03T09:00:00Z", pn, "q", Optional.empty()).quote();
        assertThat(quote.agreementVersion()).isEqualTo(2);
        assertThat(quote.principal()).isEqualTo(eur(400_00));
        executeAt("2026-11-03T09:01:00Z", pn, "x", quote.id());
        assertEveryAccountZero(pn);
        for (BnplPurchaseStore.StoredInstalment instalment : world.purchases.schedules.get(pn.obligation().value())
                .instalments()) {
            assertThat(world.purchases.billedOn(pn.obligation(), instalment.dueDate())).isTrue();
        }
    }

    @Test
    @DisplayName("no rebate arithmetic exists: a pay-in-N payoff pays the principal and exactly the interest accrued to its"
            + " eve - interest income is only ever credited (no debit, no reversal, no rebate line), and nothing of a"
            + " later period is billed")
    void noRebateArithmeticExists() {
        Agreement pn = payInN();
        catchUpAt("2026-12-01T00:30:00Z", pn);
        serviceAt("2026-12-01T00:31:00Z", pn);
        BnplPayoffQuote quote = quoteAt("2026-12-01T09:00:00Z", pn, "q", Optional.empty()).quote();
        Money accruedTotal = Money.ofMinorUnits(0, BnplTermsFixtures.EUR);
        for (BnplInterestAccrual row : world.accruals.of(pn.obligation())) {
            accruedTotal = accruedTotal.plus(row.accrual().posted());
        }
        assertThat(quote.interestAccrued().plus(quote.interestDue())).isEqualTo(accruedTotal);
        BnplPayoffs.Executed executed = executeAt("2026-12-01T09:01:00Z", pn, "x", quote.id());
        LedgerAccountId income = world.book.operational.get(AccountPurpose.BNPL_INTEREST_INCOME);
        assertThat(world.book.lines).filteredOn(line -> line.account().equals(income))
                .allSatisfy(line -> assertThat(line.direction()).isEqualTo(Direction.CREDIT));
        assertThat(world.book.balance(income, false)).isEqualTo(accruedTotal);
        assertThat(executed.repayment().orElseThrow().amount()).isEqualTo(eur(600_00).plus(accruedTotal)
                .minus(paidBefore(pn)));
        // Instalment 3's period [12-17, 01-17) has not begun: billed early with no interest.
        BnplBilling third = world.purchases.billings.get(instalment(pn, 3).id());
        assertThat(third.interest()).isEqualTo(eur(0));
        assertEveryAccountZero(pn);
    }

    @Test
    @DisplayName("the sweep and the payoff accrue a date once: T-acc run twice for one date (the sweep, then the payoff's"
            + " own catch-up) writes it once; the payoff over it is exact")
    void theSweepAndThePayoffAccrueTheSameDateOnce() {
        Agreement pn = payInN();
        BnplAccruals.Outcome sweep = accrueAt("2026-10-20T00:30:00Z", pn);
        BnplAccruals.Outcome payoffsOwn = accrueAt("2026-10-20T09:00:00Z", pn);
        assertThat(sweep.posted()).isEqualTo(3);
        assertThat(payoffsOwn.posted() + payoffsOwn.zero()).isZero();
        assertThat(world.accruals.of(pn.obligation())).extracting(row -> row.accrual().date()).doesNotHaveDuplicates()
                .hasSize(3);
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:01Z", pn, "q", Optional.empty()).quote();
        executeAt("2026-10-20T09:00:02Z", pn, "x", quote.id());
        assertEveryAccountZero(pn);
    }

    @Test
    @DisplayName("payoff zeroes every balance, both products: the five receivables and the credit balance at zero, the"
            + " obligation CLOSED, the agreement's every instalment billed - and a payoff on a record behind its accrual"
            + " is refused before it writes (T-acc first), never a closure over an unaccrued day")
    void payoffZeroesEveryBalance() {
        world.book.funds.put(wallet, eur(10_000_00));
        Agreement pn = payInN();
        catchUpAt("2026-11-20T00:30:00Z", pn);
        serviceAt("2026-11-20T00:31:00Z", pn);
        repayAt("2026-11-20T08:00:00Z", pn, "part", eur(50_00));
        BnplPayoffQuote quote = quoteAt("2026-11-20T09:00:00Z", pn, "q", Optional.of(LocalDate.of(2026, 11, 23))).quote();
        assertThatThrownBy(() -> executeAt("2026-11-23T09:00:00Z", pn, "x", quote.id()))
                .isInstanceOf(BnplPayoffs.BehindAccrual.class);
        catchUpAt("2026-11-23T09:00:00Z", pn);
        executeAt("2026-11-23T09:00:01Z", pn, "x", quote.id());
        assertEveryAccountZero(pn);
        assertThat(obligation(pn).closureReason()).contains(BnplClosureReason.PAID_OFF_EARLY);
        for (BnplPurchaseStore.StoredInstalment instalment : world.purchases.schedules.get(pn.obligation().value())
                .instalments()) {
            assertThat(world.purchases.billedOn(pn.obligation(), instalment.dueDate())).isTrue();
        }

        Agreement p4 = payIn4();
        BnplPayoffQuote p4Quote = quoteAt("2026-10-18T09:00:00Z", p4, "q4", Optional.empty()).quote();
        executeAt("2026-10-18T09:00:01Z", p4, "x4", p4Quote.id());
        assertEveryAccountZero(p4);
    }

    // ------------------------------------------------------------------ RG-M1: every payoff fact floored

    @Test
    @DisplayName("every payoff application is floored (RG-M1): on a database clock stepped back across midnight behind the"
            + " latest billing (floor 10-31, today 10-30), the early billings' entries, the billing application, the"
            + " repayment and the execution are all dated the floor - none dated today")
    void everyPayoffApplicationIsFloored() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T00:00:05Z", p4);
        repayAt("2026-10-31T00:00:10Z", p4, "over", eur(200_00));
        assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(50_00));
        // The clock steps back across midnight: today is 10-30, the floor (instalment 2's billing) 10-31.
        BnplPayoffQuote quote = quoteAt("2026-10-30T23:59:50Z", p4, "q", Optional.empty()).quote();
        assertThat(quote.businessDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(quote.amount()).isEqualTo(eur(250_00));
        BnplPayoffs.Executed executed = executeAt("2026-10-30T23:59:55Z", p4, "x", quote.id());
        LocalDate floor = LocalDate.of(2026, 10, 31);
        assertThat(executed.execution().valueDate()).isEqualTo(floor);
        for (int sequence = 3; sequence <= 4; sequence++) {
            assertThat(world.book.linesOf(BnplServicing.BILLING_KEY_PREFIX + instalment(p4, sequence).id()))
                    .isNotEmpty().allSatisfy(line -> assertThat(line.valueDate()).isEqualTo(floor));
        }
        BnplBilling third = world.purchases.billings.get(instalment(p4, 3).id());
        BnplCreditBalanceApplication atBilling = world.servicing.applications.values().stream()
                .filter(row -> row.billing().filter(third.id()::equals).isPresent()).findFirst().orElseThrow();
        assertThat(atBilling.valueDate()).isEqualTo(floor);
        assertThat(world.book.linesOf(BnplCreditBalanceApplications.KEY_PREFIX + third.id()))
                .allSatisfy(line -> assertThat(line.valueDate()).isEqualTo(floor));
        assertThat(executed.repayment().orElseThrow().valueDate()).isEqualTo(floor);
        assertThat(world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + executed.repayment().orElseThrow().id()))
                .allSatisfy(line -> assertThat(line.valueDate()).isEqualTo(floor));
        assertEveryAccountZero(p4);
    }

    // ------------------------------------------------------------------ scenario 11 and the races, both orders

    @Test
    @DisplayName("scenario 11: a refund that committed first makes the quote stale - 409, nothing posted (a refund of"
            + " billed principal, no new version: the recomputation catches it; one cancelling unbilled principal moves the"
            + " version too); a refund waiting behind the payoff finds it CLOSED and credits the customer only - never a"
            + " wrong payoff")
    void payoffBesideARefundIsStaleOrClosedNeverWrong() {
        world.book.funds.put(wallet, eur(10_000_00));
        // Refund first, no new version: every instalment billed, 100 cancelled of instalment 4's billed principal.
        Agreement first = payIn4();
        serviceAt("2026-11-28T09:00:00Z", first);
        BnplPayoffQuote stale = quoteAt("2026-11-28T09:10:00Z", first, "q", Optional.empty()).quote();
        assertThat(stale.amount()).isEqualTo(eur(450_00));
        refundAt("2026-11-28T09:20:00Z", first, "r", eur(100_00));
        assertThat(world.purchases.agreements.get(first.agreement().value()).version()).as("no new version").isEqualTo(1);
        int keys = world.book.keys.size();
        Money walletBefore = world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        assertThatThrownBy(() -> executeAt("2026-11-28T09:30:00Z", first, "x", stale.id()))
                .isInstanceOfSatisfying(BnplPayoffs.PayoffQuoteStale.class, refused ->
                        assertThat(refused.reason()).isEqualTo(BnplPayoffs.PayoffQuoteStale.Reason.AMOUNT_CHANGED));
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.payoffs.executions).isEmpty();
        assertThat(obligation(first).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(walletBefore);
        BnplPayoffQuote fresh = quoteAt("2026-11-28T09:40:00Z", first, "q2", Optional.empty()).quote();
        assertThat(fresh.amount()).isEqualTo(eur(350_00));
        executeAt("2026-11-28T09:41:00Z", first, "x2", fresh.id());
        assertEveryAccountZero(first);

        // Refund first, a new version: 150 cancels instalment 4 (unbilled) - version 2, the quote stale.
        Agreement versioned = payIn4();
        BnplPayoffQuote before = quoteAt("2026-10-20T09:00:00Z", versioned, "q", Optional.empty()).quote();
        refundAt("2026-10-20T09:10:00Z", versioned, "r-versioned", eur(150_00));
        assertThatThrownBy(() -> executeAt("2026-10-20T09:20:00Z", versioned, "x", before.id()))
                .isInstanceOf(BnplPayoffs.PayoffQuoteStale.class);
        // A new quote on schedule version 2 (instalment 4 cancelled) pays off what is left - 300.00 - and closes.
        BnplPayoffQuote onVersion2 = quoteAt("2026-10-20T09:30:00Z", versioned, "q2", Optional.empty()).quote();
        assertThat(onVersion2.agreementVersion()).isEqualTo(2);
        assertThat(onVersion2.amount()).isEqualTo(eur(300_00));
        executeAt("2026-10-20T09:31:00Z", versioned, "x2", onVersion2.id());
        assertEveryAccountZero(versioned);
        assertThat(obligation(versioned).closureReason()).contains(BnplClosureReason.PAID_OFF_EARLY);

        // Payoff first: the refund waits on B5 -> B4, finds CLOSED, and pays the customer only (OQ-15).
        Agreement paid = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T10:00:00Z", paid, "q", Optional.empty()).quote();
        executeAt("2026-10-20T10:01:00Z", paid, "x", quote.id());
        Money afterPayoff = world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        BnplRefunds.Refunded refunded = refundAt("2026-10-20T10:02:00Z", paid, "r-paid", eur(200_00));
        assertThat(refunded.refund().adjustments()).isEmpty();
        assertThat(refunded.refund().excess()).isEqualTo(eur(200_00));
        assertThat(refunded.closure()).isEmpty();
        assertThat(obligation(paid).closureReason()).contains(BnplClosureReason.PAID_OFF_EARLY);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(afterPayoff.plus(eur(200_00)));
        assertEveryAccountZero(paid);
    }

    @Test
    @DisplayName("a payoff beside a repayment: the repayment committed first makes the quote stale (409, nothing posted);"
            + " one after the payoff finds the obligation CLOSED (409 ObligationClosed, nothing posted)")
    void payoffDuringARepaymentIsStaleOrClosedNeverWrong() {
        Agreement p4 = payIn4();
        serviceAt("2026-10-31T09:00:00Z", p4);
        BnplPayoffQuote quote = quoteAt("2026-10-31T10:00:00Z", p4, "q", Optional.empty()).quote();
        repayAt("2026-10-31T10:01:00Z", p4, "rep", eur(50_00));
        assertThatThrownBy(() -> executeAt("2026-10-31T10:02:00Z", p4, "x", quote.id()))
                .isInstanceOf(BnplPayoffs.PayoffQuoteStale.class);
        assertThat(world.payoffs.executions).isEmpty();

        BnplPayoffQuote again = quoteAt("2026-10-31T10:03:00Z", p4, "q2", Optional.empty()).quote();
        assertThat(again.amount()).isEqualTo(eur(400_00));
        executeAt("2026-10-31T10:04:00Z", p4, "x2", again.id());
        int keys = world.book.keys.size();
        assertThatThrownBy(() -> repayAt("2026-10-31T10:05:00Z", p4, "late", eur(10_00)))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThat(world.book.keys).hasSize(keys);
        // The servicing step finds nothing to service either: no billing, no condition after CLOSED.
        assertThat(serviceAt("2026-11-14T09:00:00Z", p4).kind()).isEqualTo(ServicingOutcome.Kind.NOT_SERVICED);
    }

    @Test
    @DisplayName("ten executions of one quote - ten keys - make one closure: the first pays, nine converge on its execution"
            + " (replayed, no second entry); another quote then finds the obligation closed")
    void tenExecutionsOneClosure() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-25T09:00:00Z", p4, "q", Optional.empty()).quote();
        BnplPayoffQuote other = quoteAt("2026-10-25T09:00:01Z", p4, "other", Optional.empty()).quote();
        List<BnplPayoffs.Executed> executed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            executed.add(executeAt("2026-10-25T10:00:0" + i + "Z", p4, "key-" + i, quote.id()));
            if (i == 0) {
                assertThat(world.book.keys).isNotEmpty();
            }
        }
        int keys = world.book.keys.size();
        assertThat(executed).filteredOn(BnplPayoffs.Executed::replayed).hasSize(9);
        assertThat(executed).extracting(run -> run.execution().id()).containsOnly(executed.get(0).execution().id());
        assertThat(executed).extracting(run -> run.closure().id()).containsOnly(executed.get(0).closure().id());
        assertThat(executed).extracting(run -> run.repayment().orElseThrow().id())
                .containsOnly(executed.get(0).repayment().orElseThrow().id());
        assertThat(world.payoffs.executions).hasSize(1);
        assertThat(world.repayments.closures).hasSize(1);
        assertThat(world.purchases.repayments.values()).filteredOn(row -> row.kind().equals(BnplPurchaseStore.Repayment.PAYOFF))
                .hasSize(1);
        assertThatThrownBy(() -> executeAt("2026-10-25T10:01:00Z", p4, "k", other.id()))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
        assertThat(world.book.keys).hasSize(keys);
    }

    @Test
    @DisplayName("a quote is executable only on its date: good through 10-22, executed on 10-21 or 10-23 is stale (409,"
            + " NOT_ITS_DATE, nothing posted); on 10-22 it pays")
    void aQuoteIsExecutableOnlyOnItsDate() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.of(LocalDate.of(2026, 10, 22))).quote();
        assertThat(quote.projectedDays()).as("pay-in-4 projects nothing").isZero();
        for (String instant : List.of("2026-10-21T09:00:00Z", "2026-10-23T09:00:00Z")) {
            assertThatThrownBy(() -> executeAt(instant, p4, "x", quote.id()))
                    .isInstanceOfSatisfying(BnplPayoffs.PayoffQuoteStale.class, refused ->
                            assertThat(refused.reason()).isEqualTo(BnplPayoffs.PayoffQuoteStale.Reason.NOT_ITS_DATE));
        }
        assertThat(world.payoffs.executions).isEmpty();
        executeAt("2026-10-22T09:00:00Z", p4, "x", quote.id());
        assertThat(obligation(p4).closureReason()).contains(BnplClosureReason.PAID_OFF_EARLY);
    }

    @Test
    @DisplayName("insufficient funds post nothing: 422 before any write - no execution, billing, application or entry;"
            + " the obligation ACTIVE; topped up, the same quote pays on its date")
    void insufficientFundsPostsNothing() {
        Agreement p4 = payIn4();
        world.book.funds.put(wallet, eur(150_00 + 449_99));
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.empty()).quote();
        int keys = world.book.keys.size();
        int billings = world.purchases.billings.size();
        assertThatThrownBy(() -> executeAt("2026-10-20T09:01:00Z", p4, "x", quote.id()))
                .isInstanceOf(BnplRepayments.InsufficientFunds.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.purchases.billings).hasSize(billings);
        assertThat(world.payoffs.executions).isEmpty();
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        world.book.statuses.put(wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        world.book.funds.put(wallet, eur(1_000_00));
        assertThatThrownBy(() -> executeAt("2026-10-20T09:02:00Z", p4, "x", quote.id()))
                .as("a wallet that cannot be debited now").isInstanceOf(BnplRepayments.InsufficientFunds.class);
        world.book.statuses.put(wallet, LedgerAccountStatus.ACTIVE);
        executeAt("2026-10-20T09:03:00Z", p4, "x", quote.id());
        assertEveryAccountZero(p4);
    }

    @Test
    @DisplayName("a failure anywhere in T-pay - here at the closure - commits nothing: no execution, billing, repayment or"
            + " entry; the quote still executes on its date")
    void aFailureCommitsNothing() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.empty()).quote();
        int keys = world.book.keys.size();
        int events = world.events.size();
        world.failAt = "closure insert";
        assertThatThrownBy(() -> executeAt("2026-10-20T09:01:00Z", p4, "x", quote.id()))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        world.failAt = null;
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.events).hasSize(events);
        assertThat(world.payoffs.executions).isEmpty();
        assertThat(world.purchases.repayments.values()).noneMatch(row -> row.kind().equals(BnplPurchaseStore.Repayment.PAYOFF));
        assertThat(obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        executeAt("2026-10-20T09:02:00Z", p4, "x", quote.id());
        assertEveryAccountZero(p4);
    }

    @Test
    @DisplayName("T-pay's order is the Phase 12 lock order: B4, the execution, the obligation's own projection rows (L8),"
            + " the billings, then (P) the wallet, then L8 with the repayment - and no B5, no L6, no income row")
    void thePayoffTakesTheLockOrder() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.empty()).quote();
        world.log.clear();
        world.book.projections.clear();
        executeAt("2026-10-20T09:01:00Z", p4, "x", quote.id());
        List<String> order = world.log.stream().filter(step -> step.equals("B4") || step.equals("execution insert")
                || step.equals("L8") || step.equals("billing insert") || step.startsWith("lock ")
                || step.equals("repayment insert") || step.equals("closure insert") || step.equals("L6")
                || step.startsWith("B5")).toList();
        assertThat(order).startsWith("B4", "execution insert", "L8", "billing insert");
        assertThat(order.indexOf("lock wallet")).isGreaterThan(order.lastIndexOf("billing insert"));
        assertThat(order.indexOf("repayment insert")).isGreaterThan(order.indexOf("lock wallet"));
        assertThat(order).doesNotContain("L6").noneMatch(step -> step.startsWith("B5"));
        assertThat(world.book.projections).noneMatch(rows -> rows.stream().anyMatch(row -> world.book.labels
                .getOrDefault(row, "").endsWith("_INCOME")));
    }

    // ------------------------------------------------------------------ the quote's keys, its date, its owner

    @Test
    @DisplayName("a quote is born once per key and immutable with its inputs: the same key answers the same quote (its"
            + " figures as quoted, even after the obligation moved); the key with another good-through date is refused,"
            + " nothing written")
    void aQuoteIsBornOncePerKey() {
        Agreement p4 = payIn4();
        BnplPayoffs.Quoted first = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.of(LocalDate.of(2026, 10, 25)));
        repayAt("2026-10-20T09:30:00Z", p4, "rep", eur(10_00));
        BnplPayoffs.Quoted again = quoteAt("2026-10-20T10:00:00Z", p4, "q", Optional.empty());
        assertThat(again.replayed()).isTrue();
        assertThat(again.quote()).isEqualTo(first.quote());
        assertThat(again.quote().amount()).as("the quote keeps its inputs").isEqualTo(eur(450_00));
        assertThatThrownBy(() -> quoteAt("2026-10-20T10:01:00Z", p4, "q", Optional.of(LocalDate.of(2026, 10, 26))))
                .isInstanceOf(BnplPayoffs.QuoteKeyReused.class);
        assertThat(world.payoffs.quotes).hasSize(1);
        assertThat(quoteAt("2026-10-20T10:02:00Z", p4, "q2", Optional.empty()).quote().amount())
                .isEqualTo(eur(440_00));
    }

    @Test
    @DisplayName("the good-through date is today up to the pinned horizon (30 days): yesterday or day 31 is refused,"
            + " nothing written; day 30 is quoted")
    void theGoodThroughDateIsWithinTheHorizon() {
        Agreement p4 = payIn4();
        for (LocalDate invalid : List.of(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 20))) {
            assertThatThrownBy(() -> quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.of(invalid)))
                    .isInstanceOfSatisfying(BnplPayoffs.GoodThroughInvalid.class, refused ->
                            assertThat(refused.horizonDays()).isEqualTo(30));
        }
        assertThat(world.payoffs.quotes).isEmpty();
        assertThat(quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.of(LocalDate.of(2026, 11, 19))).quote()
                .goodThrough()).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    @DisplayName("another party's agreement is the uniform not-found on both doors - its lock never taken; another"
            + " obligation's quote is not found; a closed obligation is not quoted (409 ObligationClosed)")
    void anotherPartysAgreementIsNotFound() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.empty()).quote();
        UUID stranger = UUID.randomUUID();
        world.repayments.lockedAgreements.clear();
        world.now = Instant.parse("2026-10-20T09:01:00Z");
        assertThatThrownBy(() -> world.inTransaction(() -> payoffs.quote(BnplApplicationFakes.UOW, p4.agreement(),
                stranger, new BnplPayoffs.QuoteRequest("s", Optional.empty()), CORRELATION)))
                .isInstanceOf(BnplRepayments.AgreementNotFound.class);
        assertThatThrownBy(() -> world.inTransaction(() -> payoffs.execute(BnplApplicationFakes.UOW, p4.agreement(),
                stranger, "s", quote.id(), new Actor(stranger.toString(), ActorType.CUSTOMER), CORRELATION)))
                .isInstanceOf(BnplRepayments.AgreementNotFound.class);
        assertThat(world.repayments.lockedAgreements).isEmpty();

        Agreement second = payIn4();
        BnplPayoffQuote secondQuote = quoteAt("2026-10-20T09:02:00Z", second, "q", Optional.empty()).quote();
        assertThatThrownBy(() -> executeAt("2026-10-20T09:03:00Z", p4, "x", secondQuote.id()))
                .isInstanceOf(BnplPayoffs.QuoteNotFound.class);
        executeAt("2026-10-20T09:04:00Z", p4, "x", quote.id());
        assertThatThrownBy(() -> quoteAt("2026-10-20T09:05:00Z", p4, "q-after", Optional.empty()))
                .isInstanceOf(BnplRepayments.ObligationClosed.class);
    }

    @Test
    @DisplayName("the database's ranks hold for the payoff's own rows: an early billing without an execution, a PAYOFF"
            + " repayment without one, a PAID_OFF_EARLY closure without one, an execution off its date or off max(today,"
            + " floor) - each refused")
    void theRanksHoldWithoutTheExecution() {
        Agreement p4 = payIn4();
        world.now = Instant.parse("2026-10-20T09:00:00Z");
        BnplPurchaseStore.StoredSchedule schedule = world.purchases.schedules.get(p4.obligation().value());
        BnplPurchaseStore.StoredInstalment second = instalment(p4, 2);
        BnplAgreement signed = world.purchases.agreements.get(p4.agreement().value());
        BnplBilling early = new BnplBilling(IDS.next(), second.id(), 2, p4.obligation(), schedule.id(), schedule.version(),
                schedule.engine(), signed.id(), signed.version(), signed.accrualEngine(), PURCHASE_DATE,
                second.dueDate(), second.principal(), eur(0), Optional.of(com.finapp.ledger.JournalEntryId.of(IDS.next())),
                CORRELATION);
        assertThatThrownBy(() -> world.purchases.insertBilling(BnplApplicationFakes.UOW, early))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("due date");
        BnplPurchaseStore.Repayment payoff = new BnplPurchaseStore.Repayment(IDS.next(), p4.obligation(),
                BnplPurchaseStore.Repayment.PAYOFF, UUID.randomUUID().toString(), eur(1_00), signed.id(),
                signed.version(), signed.allocationEngine(), com.finapp.ledger.JournalEntryId.of(IDS.next()),
                LocalDate.of(2026, 10, 20), List.of(new BnplPurchaseStore.Allocation(IDS.next(), 1, "CREDIT_BALANCE",
                        Optional.empty(), Optional.empty(), eur(1_00))));
        assertThatThrownBy(() -> world.purchases.insertRepayment(BnplApplicationFakes.UOW, payoff))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("PAYOFF");
        BnplClosure closure = new BnplClosure(IDS.next(), p4.obligation(), BnplClosureReason.PAID_OFF_EARLY, eur(0),
                Optional.empty(), Optional.empty(), CORRELATION);
        assertThatThrownBy(() -> world.repayments.insertClosure(BnplApplicationFakes.UOW, closure))
                .isInstanceOf(BnplStorageException.class);
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "q", Optional.of(LocalDate.of(2026, 10, 21))).quote();
        assertThatThrownBy(() -> world.payoffs.insertExecution(BnplApplicationFakes.UOW, new BnplPayoffExecution(
                IDS.next(), quote.id(), p4.obligation(), "x", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21),
                Instant.now(), CORRELATION))).isInstanceOf(BnplStorageException.class).hasMessageContaining("good-through");
        world.now = Instant.parse("2026-10-21T09:00:00Z");
        assertThatThrownBy(() -> world.payoffs.insertExecution(BnplApplicationFakes.UOW, new BnplPayoffExecution(
                IDS.next(), quote.id(), p4.obligation(), "x", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22),
                Instant.now(), CORRELATION))).isInstanceOf(BnplStorageException.class).hasMessageContaining("floor");
        assertThat(world.payoffs.executions).isEmpty();
    }

    @Test
    @DisplayName("a payoff quote and its execution carry no key or figure in their text (INV-AUD-02)")
    void theRecordsRedact() {
        Agreement p4 = payIn4();
        BnplPayoffQuote quote = quoteAt("2026-10-20T09:00:00Z", p4, "secret-key", Optional.empty()).quote();
        assertThat(quote.toString()).doesNotContain("secret-key").doesNotContain("450");
        assertThat(new BnplPayoffs.QuoteRequest("secret-key", Optional.empty()).toString()).doesNotContain("secret-key");
        BnplPayoffs.Executed executed = executeAt("2026-10-20T09:01:00Z", p4, "secret-exec", quote.id());
        assertThat(executed.execution().toString()).doesNotContain("secret-exec");
    }

    // ------------------------------------------------------------------ helpers

    record Agreement(BnplPurchaseId purchase, BnplAgreementId agreement, BnplObligationId obligation) {}

    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);

    private BnplPayoffs.Quoted quoteAt(String instant, Agreement agreement, String key, Optional<LocalDate> goodThrough) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> payoffs.quote(BnplApplicationFakes.UOW, agreement.agreement(), party,
                new BnplPayoffs.QuoteRequest(key, goodThrough), CORRELATION));
    }

    private BnplPayoffs.Executed executeAt(String instant, Agreement agreement, String key, UUID quote) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> payoffs.execute(BnplApplicationFakes.UOW, agreement.agreement(), party, key,
                quote, customer, CORRELATION));
    }

    private BnplRepayments.Repaid repayAt(String instant, Agreement agreement, String key, Money amount) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> repayments.repay(BnplApplicationFakes.UOW, agreement.agreement(), party,
                new BnplRepayments.Request(key, amount), customer, CORRELATION));
    }

    private BnplRefunds.Refunded refundAt(String instant, Agreement agreement, String key, Money amount) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> refunds.atMerchant(BnplApplicationFakes.UOW, agreement.purchase(), merchant,
                new BnplRefunds.Request(key, amount, "the goods came back", Optional.of("merchant-key-1")),
                merchantActor, CORRELATION));
    }

    private ServicingOutcome serviceAt(String instant, Agreement agreement) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, agreement.obligation()));
    }

    private BnplAccruals.Outcome accrueAt(String instant, Agreement agreement) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, agreement.obligation()));
    }

    /** T-acc until caught up - the door's bounded loop. */
    private void catchUpAt(String instant, Agreement agreement) {
        for (int round = 0; round < 8; round++) {
            if (accrueAt(instant, agreement).caughtUp()) {
                return;
            }
        }
        throw new AssertionError("T-acc did not catch up");
    }

    /** A credit held on the obligation, posted as the book would hold it (wallet to the credit balance). */
    private void plantCredit(Agreement agreement, Money amount) {
        world.book.post(BnplApplicationFakes.UOW, new com.finapp.ledger.PostingCommand("test.credit:" + IDS.next(),
                LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 28), agreement.obligation().value().toString(),
                List.of(new com.finapp.ledger.JournalLine(wallet, Direction.DEBIT, amount),
                        new com.finapp.ledger.JournalLine(accounts(agreement).creditBalance().id(), Direction.CREDIT,
                                amount))));
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
        Supplier<BnplAcceptances.Accepted> accepting = () -> acceptances.accept(BnplApplicationFakes.UOW, offer.id(),
                party, new BnplAcceptances.Acceptance(hash, evidence), customer, Correlation.startingWith(CORRELATION));
        BnplAcceptances.Accepted accepted = world.inTransaction(accepting);
        return new Agreement(accepted.purchase().id(), accepted.agreement().id(), accepted.obligation().id());
    }

    private void assertEveryAccountZero(Agreement agreement) {
        for (com.finapp.ledger.LedgerAccount account : accounts(agreement).all()) {
            assertThat(balance(account)).as(account.purpose().name()).isEqualTo(eur(0));
        }
        assertThat(obligation(agreement).status()).isEqualTo(BnplObligationStatus.CLOSED);
    }

    /** What the customer paid towards the obligation before the payoff - every repayment but the payoff's. */
    private Money paidBefore(Agreement agreement) {
        Money paid = eur(0);
        for (BnplPurchaseStore.Repayment row : world.purchases.repayments.values()) {
            if (row.obligation().equals(agreement.obligation()) && !row.kind().equals(BnplPurchaseStore.Repayment.PAYOFF)) {
                paid = paid.plus(row.amount());
            }
        }
        return paid;
    }

    private long firstPrincipal(Agreement agreement) {
        return instalment(agreement, 1).amount().minorUnits() - 4_93;
    }

    private BnplObligation obligation(Agreement agreement) {
        return world.purchases.obligations.get(agreement.obligation().value());
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

    private List<String> labelled(List<BnplApplicationFakes.Book.Line> lines) {
        return lines.stream().map(line -> world.book.labels.get(line.account()) + " " + line.direction() + " "
                + line.amount().minorUnits()).toList();
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
