package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-svc, hermetic (`P12-TSK-015`; {@code PHASE_12_PLAN.md} sections 7.1, 7.5 and 12.11 F2, F3a; {@code INV-BNPL-01},
 * {@code INV-BNPL-05}, {@code INV-LND-02} and {@code -04} by analogy, {@code INV-IDEM-02}): on each due date exactly what is
 * due becomes due - pay-in-4's instalments every fourteen days, pay-in-N's with the interest actually accrued - and money
 * already held pays it, at a billing's birth and whenever something is already past due (the Phase 11 gate's R2), every
 * application value-dated at the floor (R1). Over {@link BnplApplicationFakes} - in-memory {@code bnpl V006}/{@code V008}
 * (the billing's uniques and its trigger's ranks, the application's uniques, next number, floor and conservation), the
 * owner-role accrual and credit fixtures, a ledger that refuses an unbalanced or a second posting, and a unit of work that
 * rolls every fake back. The races across instances and the database's own ranks are {@code BnplBillingDatabaseTest}'s.
 */
@DisplayName("instalment billing, the catch-up application and the servicing step (P12-TSK-015)")
class BnplServicingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-015-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);
    private static final Duration RETRY = Duration.ofMinutes(15);

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplAcceptances acceptances;
    private BnplServicing servicing;
    private UUID party;
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
                world.collectionsOver(closures, IDS, CLOCK), world.conditionsOver(IDS, CLOCK), world.outbox, IDS,
                CLOCK);
        party = UUID.randomUUID();
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.merchants.payables.put(merchant, world.book.external("payable", IDS.next()));
        wallet = world.book.external("wallet", IDS.next());
        world.book.funds.put(wallet, eur(1_000_00));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    // ------------------------------------------------------------------ billing (F2)

    @Test
    @DisplayName("on its due date an instalment is billed once - F2 balanced, value-dated its due date and posted on the"
            + " business date; a second step that day bills nothing (INV-IDEM-02)")
    void billedOnTheDueDateOnce() {
        BnplObligationId id = payIn4();
        ServicingOutcome first = serviceAt("2026-10-31T00:00:05Z", id);
        assertThat(first.kind()).isEqualTo(ServicingOutcome.Kind.SERVICED);
        assertThat(first.billed()).isEqualTo(1);
        BnplBilling billed = billingOf(id, 2);
        assertThat(billed.dueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(billed.principal()).isEqualTo(eur(150_00));
        assertThat(billed.interest()).isEqualTo(eur(0));
        assertThat(billed.periodStart()).isEqualTo(PURCHASE_DATE);
        assertThat(billed.scheduleVersion()).isEqualTo(1);
        assertThat(billed.agreementVersion()).isEqualTo(1);
        assertThat(billed.accrualEngine()).isEmpty();
        List<BnplApplicationFakes.Book.Line> f2 = world.book.linesOf(BnplServicing.BILLING_KEY_PREFIX + billed.instalment());
        assertThat(f2).extracting(BnplApplicationFakes.Book.Line::direction)
                .containsExactly(Direction.DEBIT, Direction.CREDIT);
        assertThat(f2).allSatisfy(line -> {
            assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(line.amount()).isEqualTo(eur(150_00));
        });
        BnplLedgerAccounts accounts = accounts(id);
        assertThat(balance(accounts.principal())).isEqualTo(eur(300_00));
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(150_00));

        ServicingOutcome again = serviceAt("2026-10-31T18:00:00Z", id);
        assertThat(again.billed()).isZero();
        assertThat(world.purchases.billings).hasSize(2);
        assertThat(world.book.keys).filteredOn(key -> key.startsWith(BnplServicing.BILLING_KEY_PREFIX)).hasSize(2);
    }

    @Test
    @DisplayName("ten sweepers - five claiming, five handed the id - bill each instalment once, one entry and one"
            + " InstalmentDue each (B4, UNIQUE (instalment_id), the ledger key)")
    void tenSweepersBillOnce() {
        BnplObligationId id = payIn4();
        world.now = Instant.parse("2026-11-28T09:00:00Z");
        world.events.clear();
        List<ServicingOutcome> outcomes = new ArrayList<>();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            List<BnplObligationId> page = sweeper < 5
                    ? world.inTransaction(() -> servicing.claim(BnplApplicationFakes.UOW, 20, RETRY))
                    : List.of(id);
            for (BnplObligationId claimed : page) {
                outcomes.add(world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, claimed)));
            }
        }
        assertThat(outcomes).extracting(ServicingOutcome::billed).containsOnly(3, 0);
        assertThat(outcomes.stream().mapToInt(ServicingOutcome::billed).sum()).isEqualTo(3);
        assertThat(world.purchases.billings).hasSize(4);
        assertThat(world.book.keys).filteredOn(key -> key.startsWith(BnplServicing.BILLING_KEY_PREFIX))
                .hasSize(4).doesNotHaveDuplicates();
        // And the condition the first step recorded - instalments 2 and 3 past due (`P12-TSK-020`) - once, however many swept.
        assertThat(world.events).extracting(EventEnvelope::eventType)
                .containsExactly("bnpl.InstalmentDue", "bnpl.InstalmentDue", "bnpl.InstalmentDue",
                        BnplConditions.DELINQUENCY_CHANGED_EVENT);
    }

    @Test
    @DisplayName("pay-in-4 bills instalments 2-4 on D1 + 14, + 28 and + 42, each on its own day and never the day before")
    void p4BillsEveryFourteenDays() {
        BnplObligationId id = payIn4();
        List<LocalDate> billedOn = new ArrayList<>();
        for (LocalDate day = PURCHASE_DATE; !day.isAfter(PURCHASE_DATE.plusDays(50)); day = day.plusDays(1)) {
            int before = world.purchases.billings.size();
            serviceAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC).toString(), id);
            if (world.purchases.billings.size() > before) {
                billedOn.add(day);
            }
        }
        assertThat(billedOn).containsExactly(PURCHASE_DATE.plusDays(14), PURCHASE_DATE.plusDays(28),
                PURCHASE_DATE.plusDays(42));
        assertThat(world.purchases.billings.values()).extracting(BnplBilling::dueDate)
                .containsExactlyInAnyOrder(PURCHASE_DATE, PURCHASE_DATE.plusDays(14), PURCHASE_DATE.plusDays(28),
                        PURCHASE_DATE.plusDays(42));
    }

    @Test
    @DisplayName("pay-in-4's instalment 1, billed in T-pur, is never billed again - the step on the purchase's day bills"
            + " nothing")
    void instalmentOneIsNeverBilledTwice() {
        BnplObligationId id = payIn4();
        ServicingOutcome outcome = serviceAt("2026-10-17T23:00:00Z", id);
        assertThat(outcome.kind()).isEqualTo(ServicingOutcome.Kind.SERVICED);
        assertThat(outcome.billed()).isZero();
        assertThat(world.purchases.billings).hasSize(1);
        assertThat(world.book.keys).filteredOn(key -> key.startsWith(BnplServicing.BILLING_KEY_PREFIX)).hasSize(1);
    }

    @Test
    @DisplayName("pay-in-N bills the interest actually accrued in the period - Σ its posted accruals, never the"
            + " projection - and principal = amount - that interest (INV-LND-02 by analogy)")
    void pnBillsTheAccruedInterestNotTheProjection() {
        BnplObligationId id = payInN();
        BnplPurchaseStore.StoredInstalment first = schedule(id).instalments().get(0);
        LocalDate due = first.dueDate();
        accrueThrough(id, due.minusDays(1), 30);
        long days = due.toEpochDay() - PURCHASE_DATE.toEpochDay();
        Money accrued = eur(30 * days);
        assertThat(accrued).as("the fixture differs from the projection").isNotEqualTo(first.interest());

        ServicingOutcome outcome = serviceAt(due.atTime(1, 0).toInstant(ZoneOffset.UTC).toString(), id);
        assertThat(outcome.billed()).isEqualTo(1);
        BnplBilling billed = billingOf(id, 1);
        assertThat(billed.interest()).isEqualTo(accrued);
        assertThat(billed.principal()).isEqualTo(first.amount().minus(accrued));
        assertThat(billed.accrualEngine()).contains("ACCRUAL_ENGINE_V1");
        BnplLedgerAccounts accounts = accounts(id);
        assertThat(balance(accounts.interestDue())).isEqualTo(accrued);
        assertThat(balance(accounts.interestAccrued())).as("everything accrued in the period made due").isEqualTo(eur(0));
        assertThat(balance(accounts.principalDue())).isEqualTo(first.amount().minus(accrued));
    }

    @Test
    @DisplayName("pay-in-N waits for every accrual of the period: one elapsed date unaccrued - nothing written,"
            + " BEHIND_ACCRUAL, the permit left at the claim's cadence")
    void billingWaitsForEveryAccrualOfThePeriod() {
        BnplObligationId id = payInN();
        LocalDate due = schedule(id).instalments().get(0).dueDate();
        accrueThrough(id, due.minusDays(2), 30);
        world.now = due.atTime(1, 0).toInstant(ZoneOffset.UTC);
        List<BnplObligationId> claimed = world.inTransaction(() -> servicing.claim(BnplApplicationFakes.UOW, 20, RETRY));
        assertThat(claimed).contains(id);
        int keys = world.book.keys.size();
        ServicingOutcome outcome = world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, id));
        assertThat(outcome.kind()).isEqualTo(ServicingOutcome.Kind.BEHIND_ACCRUAL);
        assertThat(world.purchases.billings).isEmpty();
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.servicing.permits.get(id.value())).isEqualTo(world.now.plus(RETRY));

        // Once the eve is accrued the same step bills.
        world.servicing.accrue(id, due.minusDays(1), eur(30));
        assertThat(world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, id)).billed()).isEqualTo(1);
    }

    @Test
    @DisplayName("on the purchase's own day nothing has elapsed: a pay-in-N step is caught up with no accrual at all")
    void aPayInNStepOnThePurchaseDayIsCaughtUp() {
        BnplObligationId id = payInN();
        assertThat(serviceAt("2026-10-17T20:00:00Z", id).kind()).isEqualTo(ServicingOutcome.Kind.SERVICED);
        assertThat(serviceAt("2026-10-18T00:00:01Z", id).kind()).isEqualTo(ServicingOutcome.Kind.BEHIND_ACCRUAL);
    }

    @Test
    @DisplayName("the last instalment bills the remainder: Σ billed principal = the financed gross, pay-in-4 and"
            + " pay-in-N alike (INV-BNPL-05)")
    void theLastInstalmentBillsTheRemainder() {
        BnplObligationId p4 = payIn4();
        serviceAt("2026-12-31T12:00:00Z", p4);
        assertThat(billedPrincipal(p4)).isEqualTo(eur(GROSS));
        assertThat(balance(accounts(p4).principal())).isEqualTo(eur(0));

        BnplObligationId pn = payInN();
        BnplPurchaseStore.StoredSchedule schedule = schedule(pn);
        LocalDate last = schedule.instalments().get(schedule.instalments().size() - 1).dueDate();
        accrueThrough(pn, last.minusDays(1), 17);
        serviceAt(last.atTime(12, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        assertThat(billedPrincipal(pn)).isEqualTo(eur(GROSS));
        assertThat(balance(accounts(pn).principal())).isEqualTo(eur(0));
        Money billedInterest = eur(0);
        for (BnplBilling billing : billings(pn)) {
            billedInterest = billedInterest.plus(billing.interest());
        }
        assertThat(billedInterest).as("billed interest = accrued interest")
                .isEqualTo(eur(17 * (last.toEpochDay() - PURCHASE_DATE.toEpochDay())));
    }

    @Test
    @DisplayName("the last instalment bills the remainder even when the accrued interest outran the projection - the"
            + " amount rule would leave principal unbilled (INV-BNPL-05)")
    void theLastInstalmentBillsTheRemainderWhateverTheInterest() {
        BnplObligationId pn = payInN();
        BnplPurchaseStore.StoredSchedule schedule = schedule(pn);
        BnplPurchaseStore.StoredInstalment last = schedule.instalments().get(schedule.instalments().size() - 1);
        accrueThrough(pn, last.dueDate().minusDays(1), 50);
        serviceAt(last.dueDate().atTime(12, 0).toInstant(ZoneOffset.UTC).toString(), pn);
        BnplBilling billedLast = billingOf(pn, last.sequence());
        assertThat(billedLast.principal()).as("more than the amount rule would bill")
                .isGreaterThan(last.amount().minus(billedLast.interest()));
        assertThat(billedPrincipal(pn)).isEqualTo(eur(GROSS));
        assertThat(balance(accounts(pn).principal())).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("the scheduler down three due dates: each missed instalment is billed at its own due date, oldest first,"
            + " posted on the day it is billed")
    void threeMissedDueDatesBillAtTheirDueDates() {
        BnplObligationId id = payIn4();
        ServicingOutcome outcome = serviceAt("2026-11-29T08:00:00Z", id);
        assertThat(outcome.billed()).isEqualTo(3);
        assertThat(billings(id)).extracting(BnplBilling::dueDate).containsExactly(PURCHASE_DATE,
                PURCHASE_DATE.plusDays(14), PURCHASE_DATE.plusDays(28), PURCHASE_DATE.plusDays(42));
        for (BnplBilling billing : billings(id).subList(1, 4)) {
            assertThat(world.book.linesOf(BnplServicing.BILLING_KEY_PREFIX + billing.instalment())).allSatisfy(line -> {
                assertThat(line.valueDate()).isEqualTo(billing.dueDate());
                assertThat(line.postingDate()).isEqualTo(LocalDate.of(2026, 11, 29));
            });
        }
    }

    @Test
    @DisplayName("the database clock decides: five seconds before midnight bills nothing, five seconds after bills")
    void skewNeverBillsEarly() {
        BnplObligationId id = payIn4();
        assertThat(serviceAt("2026-10-30T23:59:55Z", id).billed()).isZero();
        assertThat(world.purchases.billings).hasSize(1);
        assertThat(serviceAt("2026-10-31T00:00:05Z", id).billed()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ money held (F3a)

    @Test
    @DisplayName("a credit balance pays the bill at its birth - FEES -> INTEREST -> PRINCIPAL, keyed by the billing,"
            + " what exceeds it held")
    void aCreditBalancePaysTheBill() {
        BnplObligationId id = payIn4();
        BnplLedgerAccounts accounts = accounts(id);
        creditFixture(accounts, eur(200_00));
        ServicingOutcome outcome = serviceAt("2026-10-31T09:00:00Z", id);
        assertThat(outcome.billed()).isEqualTo(1);
        assertThat(outcome.appliedAtBilling()).isEqualTo(1);
        assertThat(outcome.caughtUp()).as("nothing past due is left for a catch-up").isFalse();
        BnplBilling billed = billingOf(id, 2);
        BnplCreditBalanceApplication applied = world.servicing.applications.values().iterator().next();
        assertThat(applied.billing()).contains(billed.id());
        assertThat(applied.catchUp()).isEmpty();
        assertThat(applied.amount()).isEqualTo(eur(150_00));
        assertThat(applied.valueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(applied.lines()).singleElement().satisfies(line -> {
            assertThat(line.billing()).isEqualTo(billed.id());
            assertThat(line.component()).isEqualTo(com.finapp.creditmath.AllocationComponent.PRINCIPAL);
        });
        assertThat(world.book.keys).contains(BnplCreditBalanceApplications.KEY_PREFIX + billed.id());
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts.creditBalance())).as("the rest stays held").isEqualTo(eur(50_00));
    }

    @Test
    @DisplayName("held credit settles what is ALREADY past due: credit arriving after a billing is applied by the next"
            + " step's catch-up, numbered 1, 2 - never left beside arrears (the Phase 11 gate's R2)")
    void heldCreditSettlesWhatIsAlreadyPastDue() {
        BnplObligationId id = payIn4();
        serviceAt("2026-10-31T09:00:00Z", id);
        BnplLedgerAccounts accounts = accounts(id);
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(150_00));

        creditFixture(accounts, eur(100_00));
        ServicingOutcome first = serviceAt("2026-11-02T09:00:00Z", id);
        assertThat(first.billed()).isZero();
        assertThat(first.caughtUp()).isTrue();
        assertThat(balance(accounts.creditBalance())).as("no credit beside arrears").isEqualTo(eur(0));
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(50_00));

        creditFixture(accounts, eur(80_00));
        assertThat(serviceAt("2026-11-03T09:00:00Z", id).caughtUp()).isTrue();
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts.creditBalance())).isEqualTo(eur(30_00));
        assertThat(world.servicing.applications.values()).extracting(BnplCreditBalanceApplication::catchUp)
                .containsExactly(Optional.of(1), Optional.of(2));
        assertThat(world.book.keys).contains(
                BnplCreditBalanceApplications.KEY_PREFIX + id.value() + BnplCreditBalanceApplications.CATCH_UP_INFIX + 1,
                BnplCreditBalanceApplications.KEY_PREFIX + id.value() + BnplCreditBalanceApplications.CATCH_UP_INFIX + 2);
        // Nothing due: the held 30.00 is never applied to principal not yet due.
        assertThat(serviceAt("2026-11-04T09:00:00Z", id).caughtUp()).isFalse();
        assertThat(balance(accounts.creditBalance())).isEqualTo(eur(30_00));
        assertThat(balance(accounts.principal())).isEqualTo(eur(300_00));
    }

    @Test
    @DisplayName("no application below the floor (R1): the clock stepped back across midnight values the application at"
            + " the floor - the latest billing's date - and a raw writer below it is refused")
    void noApplicationBelowTheFloor() {
        BnplObligationId id = payIn4();
        serviceAt("2026-10-31T00:00:05Z", id);
        BnplLedgerAccounts accounts = accounts(id);
        creditFixture(accounts, eur(40_00));
        // The database clock steps back behind the record: today is the 30th, the floor the 31st.
        ServicingOutcome stepped = serviceAt("2026-10-30T23:59:58Z", id);
        assertThat(stepped.caughtUp()).isTrue();
        BnplCreditBalanceApplication applied = world.servicing.applications.values().iterator().next();
        assertThat(applied.valueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(world.book.linesOf(BnplCreditBalanceApplications.KEY_PREFIX))
                .allSatisfy(line -> assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 10, 31)));

        BnplBilling billed = billingOf(id, 2);
        BnplCreditBalanceApplication below = new BnplCreditBalanceApplication(IDS.next(), id, Optional.empty(),
                Optional.of(2), eur(1_00), applied.agreement(), 1, "ALLOCATION_ENGINE_V1", LocalDate.of(2026, 10, 30),
                List.of(new BnplCreditBalanceApplication.Line(IDS.next(), billed.id(),
                        com.finapp.creditmath.AllocationComponent.PRINCIPAL, eur(1_00))),
                JournalEntryId.of(IDS.next()), CORRELATION);
        assertThatThrownBy(() -> world.servicing.insertApplication(BnplApplicationFakes.UOW, below))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("floor");
    }

    @Test
    @DisplayName("an application never pays a component beyond what it billed, nor principal not yet due")
    void anApplicationSettlesWhatIsDueAndNothingElse() {
        BnplObligationId id = payIn4();
        creditFixture(accounts(id), eur(900_00));
        serviceAt("2026-11-14T09:00:00Z", id);
        BnplLedgerAccounts accounts = accounts(id);
        assertThat(balance(accounts.principalDue())).isEqualTo(eur(0));
        assertThat(balance(accounts.principal())).as("instalment 4 not yet due, untouched").isEqualTo(eur(150_00));
        assertThat(balance(accounts.creditBalance())).isEqualTo(eur(900_00 - 300_00));
    }

    // ------------------------------------------------------------------ the skeleton

    @Test
    @DisplayName("servicing takes the obligation's lock first, then its own projection rows, then posts - the step's"
            + " fixed order: bill, apply at the billing, catch up")
    void servicingTakesTheObligationLock() {
        BnplObligationId id = payIn4();
        world.log.clear();
        serviceAt("2026-10-31T09:00:00Z", id);
        assertThat(world.log).startsWith("B4");
        assertThat(world.log).containsSubsequence("B4", "L8", "post bnpl.billing:", "billing insert");
    }

    @Test
    @DisplayName("a completed step moves the permit to the start of the next business date; an obligation not ACTIVE or"
            + " gone is not serviced")
    void thePermitMovesToTheNextBusinessDate() {
        BnplObligationId id = payIn4();
        serviceAt("2026-10-20T15:00:00Z", id);
        assertThat(world.servicing.permits.get(id.value())).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
        world.now = Instant.parse("2026-10-20T16:00:00Z");
        assertThat(world.inTransaction(() -> servicing.claim(BnplApplicationFakes.UOW, 20, RETRY))).isEmpty();
        world.now = Instant.parse("2026-10-21T00:00:00Z");
        assertThat(world.inTransaction(() -> servicing.claim(BnplApplicationFakes.UOW, 20, RETRY))).containsExactly(id);
        assertThat(world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW,
                BnplObligationId.of(IDS.next()))).kind()).isEqualTo(ServicingOutcome.Kind.NOT_SERVICED);
    }

    @Test
    @DisplayName("a failure anywhere in the step rolls the whole step back - the billing, its entry, the application and"
            + " the event - and the claim's permit retries it")
    void aFailureWritesNothing() {
        BnplObligationId id = payIn4();
        creditFixture(accounts(id), eur(10_00));
        world.now = Instant.parse("2026-10-31T09:00:00Z");
        world.inTransaction(() -> servicing.claim(BnplApplicationFakes.UOW, 20, RETRY));
        int keys = world.book.keys.size();
        world.events.clear();
        world.failAt = "application insert";
        assertThatThrownBy(() -> world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, id)))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        assertThat(world.purchases.billings).hasSize(1);
        assertThat(world.servicing.applications).isEmpty();
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.events).isEmpty();
        assertThat(world.servicing.permits.get(id.value())).isEqualTo(world.now.plus(RETRY));
        world.failAt = null;
        assertThat(world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, id)).billed()).isEqualTo(1);
    }

    @Test
    @DisplayName("rows that do not explain the ledger are an anomaly - nothing is applied (INV-BNPL-01)")
    void anUnexplainedDueAccountIsAnAnomaly() {
        BnplObligationId id = payIn4();
        serviceAt("2026-10-31T09:00:00Z", id);
        BnplLedgerAccounts accounts = accounts(id);
        world.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:stray", accounts.principalDue().id(),
                Direction.DEBIT, eur(1_00), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)));
        world.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:stray", wallet, Direction.CREDIT, eur(1_00),
                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)));
        creditFixture(accounts, eur(10_00));
        assertThatThrownBy(() -> serviceAt("2026-11-01T09:00:00Z", id)).isInstanceOf(BnplReceivables.Anomaly.class);
        assertThat(world.servicing.applications).isEmpty();
    }

    @Test
    @DisplayName("InstalmentDue names the instalment, its due date and what it made due - never a party")
    void instalmentDueIsPublished() {
        BnplObligationId id = payIn4();
        world.events.clear();
        world.payloads.clear();
        serviceAt("2026-10-31T09:00:00Z", id);
        assertThat(world.events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("bnpl.InstalmentDue");
            assertThat(event.aggregateId()).isEqualTo(id);
        });
        BnplBilling billed = billingOf(id, 2);
        assertThat(world.payloads.get(0)).contains(billed.instalment().toString()).contains("2026-10-31")
                .contains("15000").doesNotContain(party.toString());
    }

    // ------------------------------------------------------------------ helpers

    private ServicingOutcome serviceAt(String instant, BnplObligationId id) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, id));
    }

    private BnplObligationId payIn4() {
        return accept(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
    }

    private BnplObligationId payInN() {
        return accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
    }

    private BnplObligationId accept(BnplProduct product, BnplPlan plan) {
        world.now = Instant.parse("2026-10-17T10:10:00Z");
        String token = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                eur(GROSS), Instant.parse("2026-10-17T11:00:00Z"), true, false));
        BnplApplication application = world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, party,
                new BnplApplications.Submission(Sensitive.of(token), product, plan),
                new Actor(party.toString(), ActorType.CUSTOMER), CORRELATION));
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(), SYSTEM));
        BnplOffer offer = world.offers.ofApplication(BnplApplicationFakes.UOW, application.id()).orElseThrow();
        String hash = world.offers.terms.get(offer.id().value()).termsSha256Hex();
        BnplAcceptanceEvidence evidence = new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                "MULTI_FACTOR", Optional.empty(), Optional.empty());
        BnplAcceptances.Accepted accepted = world.inTransaction(() -> acceptances.accept(BnplApplicationFakes.UOW,
                offer.id(), party, new BnplAcceptances.Acceptance(hash, evidence),
                new Actor(party.toString(), ActorType.CUSTOMER), Correlation.startingWith(CORRELATION)));
        return accepted.obligation().id();
    }

    /** The owner-role accrual fixture: {@code perDayMinor} posted on every date from the purchase's through {@code last}. */
    private void accrueThrough(BnplObligationId id, LocalDate last, long perDayMinor) {
        for (LocalDate day = PURCHASE_DATE; !day.isAfter(last); day = day.plusDays(1)) {
            world.servicing.accrue(id, day, eur(perDayMinor));
        }
    }

    /** The owner-role credit fixture: {@code amount} held for the customer - DR wallet / CR BNPL_CREDIT_BALANCE. */
    private void creditFixture(BnplLedgerAccounts accounts, Money amount) {
        String key = "fixture:credit:" + IDS.next();
        LocalDate date = LocalDate.ofInstant(world.now, ZoneOffset.UTC);
        world.book.lines.add(new BnplApplicationFakes.Book.Line(key, wallet, Direction.DEBIT, amount, date, date));
        world.book.lines.add(new BnplApplicationFakes.Book.Line(key, accounts.creditBalance().id(), Direction.CREDIT,
                amount, date, date));
    }

    private BnplPurchaseStore.StoredSchedule schedule(BnplObligationId id) {
        return world.purchases.schedules.get(id.value());
    }

    private List<BnplBilling> billings(BnplObligationId id) {
        return world.purchases.billings.values().stream().filter(row -> row.obligation().equals(id))
                .sorted(java.util.Comparator.comparing(BnplBilling::dueDate)).toList();
    }

    private BnplBilling billingOf(BnplObligationId id, int sequence) {
        return billings(id).stream().filter(row -> row.sequence() == sequence).findFirst().orElseThrow();
    }

    private Money billedPrincipal(BnplObligationId id) {
        Money principal = eur(0);
        for (BnplBilling billing : billings(id)) {
            principal = principal.plus(billing.principal());
        }
        return principal;
    }

    private BnplLedgerAccounts accounts(BnplObligationId id) {
        return new BnplReceivables(world.purchases, world.book).accounts(BnplApplicationFakes.UOW, id);
    }

    private Money balance(com.finapp.ledger.LedgerAccount account) {
        return world.book.balanceOf(BnplApplicationFakes.UOW, account);
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
