package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-acc, hermetic (`P12-TSK-017`; {@code PHASE_12_PLAN.md} sections 7.1 and 12.11 F2a, scenario 18; {@code INV-LND-02},
 * {@code INV-LND-09} and {@code INV-LND-10} by analogy, {@code INV-IDEM-02}, {@code INV-BAL-05}, {@code INV-BNPL-01}):
 * every elapsed date of a pay-in-N obligation's accrual span accrues exactly once, oldest first and never early on the
 * database clock, on the principal outstanding derived from journal lines, in a call that takes no wallet and no payable -
 * and every other act checks the record caught up instead of accruing. Over {@link BnplApplicationFakes}: in-memory
 * {@code bnpl V006}/{@code V008}/{@code V009} (the accrual's unique, its ranks, the billing's), a ledger that refuses an
 * unbalanced or a second posting, and a unit of work that rolls every fake back. The races across instances, the
 * database's own ranks and the income row's {@code pg_locks} are {@code BnplAccrualDatabaseTest}'s.
 */
@DisplayName("pay-in-N interest accrual - T-acc and the caught-up check (P12-TSK-017)")
class BnplAccrualsTest {

    /** The instance clock - deliberately far from every database instant the cases stand on. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-017-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;
    private static final String RATE = "0.1999";
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplAcceptances acceptances;
    private BnplServicing servicing;
    private BnplAccruals accruals;
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
        servicing = new BnplServicing(world.servicing, world.purchases, world.terms.store(), world.book, world.outbox,
                IDS, CLOCK);
        accruals = new BnplAccruals(world.servicing, world.accruals, world.purchases, world.terms.store(), world.book,
                IDS);
        party = UUID.randomUUID();
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.merchants.payables.put(merchant, world.book.external("payable", IDS.next()));
        wallet = world.book.external("wallet", IDS.next());
        world.book.funds.put(wallet, eur(10_000_00));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN(RATE).build(), 3);
    }

    // ------------------------------------------------------------------ once (scenario 18)

    @Test
    @DisplayName("scenario 18: a crash between a date's entry and its row writes neither; ten sweepers then accrue each"
            + " date once - one row, one balanced F2a entry under bnpl.accrual:<obligation>:<date> - and a re-run nothing")
    void accruesOncePerDate() {
        BnplObligationId id = payInN();
        world.now = Instant.parse("2026-10-23T08:00:00Z");
        int keys = world.book.keys.size();
        // The restart mid-run: the first date's entry posted, its row's insert crashes - the whole call rolls back.
        world.failAt = "accrual insert";
        assertThatThrownBy(() -> world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id)))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        assertThat(world.accruals.rows).isEmpty();
        assertThat(world.book.keys).hasSize(keys);
        world.failAt = null;

        int accrued = 0;
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            BnplAccruals.Outcome outcome = world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id));
            assertThat(outcome.caughtUp()).isTrue();
            accrued += outcome.posted() + outcome.zero();
        }
        assertThat(accrued).as("17-22 October, once each").isEqualTo(6);
        assertThat(world.accruals.of(id)).extracting(row -> row.accrual().date())
                .containsExactly(PURCHASE_DATE, PURCHASE_DATE.plusDays(1), PURCHASE_DATE.plusDays(2),
                        PURCHASE_DATE.plusDays(3), PURCHASE_DATE.plusDays(4), PURCHASE_DATE.plusDays(5));
        List<String> accrualKeys = world.book.keys.stream().filter(key -> key.startsWith(BnplAccruals.KEY_PREFIX)).toList();
        assertThat(accrualKeys).hasSize(6).doesNotHaveDuplicates();
        LedgerAccountId income = world.book.operational(BnplApplicationFakes.UOW, AccountPurpose.BNPL_INTEREST_INCOME,
                BnplTermsFixtures.EUR);
        for (BnplInterestAccrual row : world.accruals.of(id)) {
            assertThat(row.entry()).isPresent();
            List<BnplApplicationFakes.Book.Line> f2a = world.book.linesOf(BnplAccruals.key(id, row.accrual().date()));
            assertThat(f2a).hasSize(2);
            assertThat(f2a.get(0).account()).isEqualTo(accounts(id).interestAccrued().id());
            assertThat(f2a.get(0).direction()).isEqualTo(Direction.DEBIT);
            assertThat(f2a.get(1).account()).isEqualTo(income);
            assertThat(f2a.get(1).direction()).isEqualTo(Direction.CREDIT);
            assertThat(f2a).allSatisfy(line -> {
                assertThat(line.amount()).isEqualTo(row.accrual().posted());
                assertThat(line.valueDate()).isEqualTo(row.accrual().date());
                assertThat(line.postingDate()).isEqualTo(LocalDate.of(2026, 10, 23));
            });
            assertThat(row.agreementVersion()).isEqualTo(1);
            assertThat(row.accrual().engine().name()).isEqualTo("ACCRUAL_ENGINE_V1");
        }
        assertThat(balance(accounts(id).interestAccrued())).as("BNPL_INTEREST_ACCRUED = the posted accruals")
                .isEqualTo(sumPosted(world.accruals.of(id)));
    }

    @Test
    @DisplayName("the database clock decides, never the instance's: five seconds before midnight nothing has elapsed;"
            + " five seconds after, the date accrues once")
    void skewNeverAccruesEarlyOrTwice() {
        BnplObligationId id = payInN();
        assertThat(accrueAt("2026-10-17T23:59:55Z", id).zero() + accrueAt("2026-10-17T23:59:55Z", id).posted())
                .isZero();
        assertThat(world.accruals.rows).isEmpty();
        BnplAccruals.Outcome after = accrueAt("2026-10-18T00:00:05Z", id);
        assertThat(after.posted()).isEqualTo(1);
        assertThat(accrueAt("2026-10-18T23:59:55Z", id).posted()).isZero();
        assertThat(world.accruals.of(id)).extracting(row -> row.accrual().date()).containsExactly(PURCHASE_DATE);
    }

    @Test
    @DisplayName("the scheduler down three days: every missed date is caught up oldest first, none skipped, each valued"
            + " its own date and posted on the day it is accrued")
    void threeMissedDaysAreCaughtUpOldestFirst() {
        BnplObligationId id = payInN();
        accrueAt("2026-10-18T00:01:00Z", id);
        BnplAccruals.Outcome caughtUp = accrueAt("2026-10-22T00:01:00Z", id);
        assertThat(caughtUp.posted() + caughtUp.zero()).isEqualTo(4);
        assertThat(world.accruals.rows).extracting(row -> row.accrual().date()).as("in the order they were born")
                .containsExactly(PURCHASE_DATE, PURCHASE_DATE.plusDays(1), PURCHASE_DATE.plusDays(2),
                        PURCHASE_DATE.plusDays(3), PURCHASE_DATE.plusDays(4));
        for (BnplInterestAccrual row : world.accruals.rows.subList(1, 5)) {
            assertThat(world.book.linesOf(BnplAccruals.key(id, row.accrual().date()))).allSatisfy(line -> {
                assertThat(line.valueDate()).isEqualTo(row.accrual().date());
                assertThat(line.postingDate()).isEqualTo(LocalDate.of(2026, 10, 22));
            });
        }
    }

    @Test
    @DisplayName("after a long outage one call accrues at most 92 dates and answers not caught up; the next call accrues"
            + " the rest, oldest first, none skipped")
    void aLongOutageIsCaughtUpInBoundedCalls() {
        BnplObligationId id = accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        world.now = PURCHASE_DATE.plusDays(100).atTime(1, 0).toInstant(ZoneOffset.UTC);
        BnplAccruals.Outcome first = world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id));
        assertThat(first.posted() + first.zero()).isEqualTo(BnplAccruals.MAX_ACCRUALS_PER_STEP);
        assertThat(first.caughtUp()).isFalse();
        BnplAccruals.Outcome second = world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id));
        assertThat(second.posted() + second.zero()).isEqualTo(100 - BnplAccruals.MAX_ACCRUALS_PER_STEP);
        assertThat(second.caughtUp()).isTrue();
        List<LocalDate> dates = world.accruals.of(id).stream().map(row -> row.accrual().date()).toList();
        assertThat(dates).hasSize(100).doesNotHaveDuplicates().isSorted();
        assertThat(dates.get(0)).isEqualTo(PURCHASE_DATE);
        assertThat(dates.get(99)).isEqualTo(PURCHASE_DATE.plusDays(99));
    }

    // ------------------------------------------------------------------ the base and the arithmetic

    @Test
    @DisplayName("the base is BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE over journal lines valued on or before the date - a"
            + " billing moves none of it, interest never joins it, a principal fact lowers it from its value date - so"
            + " period 2's interest is the postings', never the projection's")
    void theBaseIsDerivedFromPostingsNotTheProjection() {
        BnplObligationId id = payInN();
        BnplPurchaseStore.StoredSchedule schedule = schedule(id);
        BnplPurchaseStore.StoredInstalment first = schedule.instalments().get(0);
        LocalDate d1 = first.dueDate();
        catchUpAt(d1.atTime(0, 30).toInstant(ZoneOffset.UTC), id);
        assertThat(serviceAt(d1.atTime(1, 0).toInstant(ZoneOffset.UTC), id).billed()).isEqualTo(1);
        // The billed principal moved to BNPL_PRINCIPAL_DUE and interest is due: the base is still the whole gross.
        LocalDate fact = d1.plusDays(5);
        principalFact(id, eur(100_00), fact);
        catchUpAt(d1.plusDays(9).atTime(0, 30).toInstant(ZoneOffset.UTC), id);
        for (BnplInterestAccrual row : world.accruals.of(id)) {
            Money expected = row.accrual().date().isBefore(fact) ? eur(GROSS) : eur(GROSS - 100_00);
            assertThat(row.accrual().base()).as("the base of " + row.accrual().date()).isEqualTo(expected);
        }
        // Nobody repaid instalment 1's principal, so period 2's accrual exceeds the schedule's projection, and the
        // billing bills what was accrued.
        BnplObligationId again = payInN();
        BnplPurchaseStore.StoredSchedule fresh = schedule(again);
        catchUpAt(fresh.instalments().get(0).dueDate().atTime(0, 30).toInstant(ZoneOffset.UTC), again);
        serviceAt(fresh.instalments().get(0).dueDate().atTime(1, 0).toInstant(ZoneOffset.UTC), again);
        LocalDate d2 = fresh.instalments().get(1).dueDate();
        catchUpAt(d2.atTime(0, 30).toInstant(ZoneOffset.UTC), again);
        serviceAt(d2.atTime(1, 0).toInstant(ZoneOffset.UTC), again);
        Money period2 = sumPosted(world.accruals.of(again).stream()
                .filter(row -> !row.accrual().date().isBefore(fresh.instalments().get(0).dueDate())).toList());
        assertThat(period2).as("never the projection").isNotEqualTo(fresh.instalments().get(1).interest());
        assertThat(billings(again).get(1).interest()).isEqualTo(period2);
    }

    @Test
    @DisplayName("on the on-time path period 1's postings telescope to the schedule's interest exactly (cumulative"
            + " rounding once per period), and period 2's first date resets the cumulative figure")
    void eachPeriodRoundsOnce() {
        BnplObligationId id = payInN();
        BnplPurchaseStore.StoredInstalment first = schedule(id).instalments().get(0);
        catchUpAt(first.dueDate().plusDays(1).atTime(0, 30).toInstant(ZoneOffset.UTC), id);
        List<BnplInterestAccrual> rows = world.accruals.of(id);
        Money period1 = sumPosted(rows.stream().filter(row -> row.accrual().date().isBefore(first.dueDate())).toList());
        assertThat(period1).isEqualTo(first.interest());
        BnplInterestAccrual reset = rows.get(rows.size() - 1);
        assertThat(reset.accrual().date()).isEqualTo(first.dueDate());
        assertThat(reset.accrual().period()).isEqualTo(new AccrualPeriod(first.dueDate(),
                schedule(id).instalments().get(1).dueDate()));
        assertThat(reset.accrual().cumulativeExact()).isEqualTo(oneDay(reset.accrual().base()));
    }

    @Test
    @DisplayName("29 February accrues one ordinary day at /365 - once, between the 28th and 1 March")
    void leapDayAccruesOneDay() {
        BnplObligationId id = accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS,
                Instant.parse("2028-02-10T10:10:00Z"));
        world.now = Instant.parse("2028-03-02T00:30:00Z");
        world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id));
        List<BnplInterestAccrual> rows = world.accruals.of(id);
        assertThat(rows).hasSize(21);
        BnplInterestAccrual leap = rows.stream().filter(row -> row.accrual().date().equals(LocalDate.of(2028, 2, 29)))
                .findFirst().orElseThrow();
        BnplInterestAccrual eve = rows.stream().filter(row -> row.accrual().date().equals(LocalDate.of(2028, 2, 28)))
                .findFirst().orElseThrow();
        assertThat(leap.accrual().cumulativeExact().subtract(eve.accrual().cumulativeExact()))
                .isEqualTo(oneDay(leap.accrual().base()));
    }

    @Test
    @DisplayName("a date whose posting rounds to zero writes its row and no entry; the period's postings still sum to its"
            + " once-rounded exact interest")
    void zeroDaysWriteARowAndNoEntry() {
        world.terms.activate(BnplTermsFixtures.payInN("0.0001").build(), 4);
        BnplObligationId id = payInN();
        world.now = Instant.parse("2026-10-24T00:30:00Z");
        BnplAccruals.Outcome outcome = world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id));
        assertThat(outcome.zero()).isPositive();
        List<BnplInterestAccrual> rows = world.accruals.of(id);
        assertThat(rows).hasSize(7);
        for (BnplInterestAccrual row : rows) {
            assertThat(row.entry().isPresent()).isEqualTo(row.accrual().posted().isPositive());
            if (row.entry().isEmpty()) {
                assertThat(world.book.linesOf(BnplAccruals.key(id, row.accrual().date()))).isEmpty();
            }
        }
        BigDecimal exact = rows.get(rows.size() - 1).accrual().cumulativeExact();
        assertThat(sumPosted(rows)).isEqualTo(Money.of(exact, BnplTermsFixtures.EUR,
                com.finapp.sharedkernel.money.RoundingPolicy.HALF_EVEN));
    }

    @Test
    @DisplayName("a pay-in-4 obligation never accrues: nothing written, always caught up, never behind")
    void aP4ObligationNeverAccrues() {
        BnplObligationId id = accept(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplAccruals.Outcome outcome = accrueAt("2026-10-25T00:30:00Z", id);
        assertThat(outcome.kind()).isEqualTo(BnplAccruals.Outcome.Kind.NOT_ACCRUING);
        assertThat(outcome.product()).contains(BnplProduct.BNPL_PAY_IN_4);
        assertThat(world.accruals.rows).isEmpty();
        assertThat(world.book.keys).noneMatch(key -> key.startsWith(BnplAccruals.KEY_PREFIX));
        assertThat(serviceAt(Instant.parse("2026-10-31T00:30:00Z"), id).kind())
                .isEqualTo(ServicingOutcome.Kind.SERVICED);
        assertThat(accrueAt("2026-10-25T00:30:00Z", BnplObligationId.of(IDS.next())).kind())
                .isEqualTo(BnplAccruals.Outcome.Kind.NOT_ACCRUING);
    }

    @Test
    @DisplayName("nothing accrues at or after the final due date: caught up past maturity, the obligation is serviced -"
            + " never BEHIND_ACCRUAL for ever")
    void nothingAccruesAfterTheFinalDueDate() {
        BnplObligationId id = payInN();
        BnplPurchaseStore.StoredSchedule schedule = schedule(id);
        LocalDate last = schedule.instalments().get(schedule.instalments().size() - 1).dueDate();
        catchUpAt(last.plusDays(10).atTime(0, 30).toInstant(ZoneOffset.UTC), id);
        List<BnplInterestAccrual> rows = world.accruals.of(id);
        assertThat(rows.get(rows.size() - 1).accrual().date()).isEqualTo(last.minusDays(1));
        assertThat(rows).hasSize((int) (last.toEpochDay() - PURCHASE_DATE.toEpochDay()));
        ServicingOutcome serviced = serviceAt(last.plusDays(10).atTime(1, 0).toInstant(ZoneOffset.UTC), id);
        assertThat(serviced.kind()).isEqualTo(ServicingOutcome.Kind.SERVICED);
        assertThat(serviced.billed()).isEqualTo(schedule.instalments().size());
        assertThat(accrueAt(last.plusDays(11).atTime(0, 30).toInstant(ZoneOffset.UTC).toString(), id).posted())
                .isZero();
    }

    // ------------------------------------------------------------------ the act's own transaction

    @Test
    @DisplayName("an act on a behind record writes nothing and answers BEHIND_ACCRUAL; once T-acc has run, the same act"
            + " bills the period's accrued interest")
    void anActOnABehindRecordRunsTheAccrualFirst() {
        BnplObligationId id = payInN();
        BnplPurchaseStore.StoredInstalment first = schedule(id).instalments().get(0);
        Instant due = first.dueDate().atTime(1, 0).toInstant(ZoneOffset.UTC);
        int keys = world.book.keys.size();
        assertThat(serviceAt(due, id).kind()).isEqualTo(ServicingOutcome.Kind.BEHIND_ACCRUAL);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.accruals.rows).isEmpty();
        assertThat(world.purchases.billings).isEmpty();
        assertThat(world.inTransaction(() -> accruals.caughtUp(BnplApplicationFakes.UOW,
                world.servicing.lock(BnplApplicationFakes.UOW, id).orElseThrow(), first.dueDate()))).isFalse();

        catchUpAt(due, id);
        ServicingOutcome serviced = serviceAt(due, id);
        assertThat(serviced.kind()).isEqualTo(ServicingOutcome.Kind.SERVICED);
        assertThat(serviced.billed()).isEqualTo(1);
        assertThat(billings(id).get(0).interest()).isEqualTo(sumPosted(world.accruals.of(id)));
        assertThat(balance(accounts(id).interestAccrued())).as("the period's accrued interest made due")
                .isEqualTo(eur(0));
    }

    @Test
    @DisplayName("T-acc takes B4, then only the accrued-interest and income rows at L8 - never a wallet, a payable or"
            + " an availability read; and T-svc never accrues inside its own transaction, behind or caught up")
    void theAccrualHoldsNoWalletAndServicingNeverAccrues() {
        BnplObligationId id = payInN();
        world.log.clear();
        world.book.projections.clear();
        accrueAt("2026-10-20T00:30:00Z", id);
        assertThat(world.log).startsWith("B4");
        assertThat(world.log).containsSubsequence("B4", "dated lines", "L8", "post bnpl.accrual:", "accrual insert");
        assertThat(world.log).noneMatch(entry -> entry.startsWith("lock ") || entry.startsWith("available "));
        assertThat(world.book.projections).singleElement().isEqualTo(Set.of(accounts(id).interestAccrued().id(),
                world.book.operational(BnplApplicationFakes.UOW, AccountPurpose.BNPL_INTEREST_INCOME,
                        BnplTermsFixtures.EUR)));

        for (String at : List.of("2026-10-25T00:30:00Z", "2026-10-25T01:00:00Z")) {
            world.log.clear();
            int rows = world.accruals.rows.size();
            serviceAt(Instant.parse(at), id);
            assertThat(world.log).as("T-svc at " + at)
                    .noneMatch(entry -> entry.startsWith("post bnpl.accrual:") || entry.equals("accrual insert"));
            assertThat(world.accruals.rows).hasSize(rows);
        }
    }

    @Test
    @DisplayName("a failure anywhere in a call rolls back every date of it - rows and entries - and the next call catches"
            + " up from where the record stands; another obligation's call is untouched")
    void aFailureRollsTheWholeCallBack() {
        BnplObligationId failing = payInN();
        BnplObligationId healthy = payInN();
        world.now = Instant.parse("2026-10-21T00:30:00Z");
        world.at.put("accrual insert", () -> world.failAt = "accrual insert");
        assertThatThrownBy(() -> world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, failing)))
                .isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        world.failAt = null;
        assertThat(world.accruals.rows).isEmpty();
        assertThat(world.book.keys).noneMatch(key -> key.startsWith(BnplAccruals.KEY_PREFIX));
        assertThat(world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, healthy)).posted()).isEqualTo(4);
        assertThat(world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, failing)).posted()).isEqualTo(4);
        assertThat(world.accruals.of(failing)).hasSize(4);
    }

    // ------------------------------------------------------------------ the clock stepped

    @Test
    @DisplayName("a database clock stepped back across midnight accrues nothing twice and leaves principal facts at the"
            + " floor (the day after the latest accrual); stepped forward and back, nothing is re-dated")
    void aClockStepNeverMisdatesAnAccrualOrPrincipal() {
        BnplObligationId id = payInN();
        LocalDate d = PURCHASE_DATE.plusDays(3);
        catchUpAt(d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(5), id);
        assertThat(world.accruals.of(id).get(world.accruals.of(id).size() - 1).accrual().date()).isEqualTo(d);
        // Back across midnight: today is d again, d already accrued.
        BnplAccruals.Outcome back = accrueAt(d.atTime(23, 59, 58).toInstant(ZoneOffset.UTC).toString(), id);
        assertThat(back.posted() + back.zero()).isZero();
        assertThat(back.caughtUp()).isTrue();
        assertThat(floor(id)).isEqualTo(d.plusDays(1));
        assertThat(serviceAt(d.atTime(23, 59, 59).toInstant(ZoneOffset.UTC), id).kind())
                .isEqualTo(ServicingOutcome.Kind.SERVICED);
        // Forward five days and back again: the dates accrued on the jump stay accrued once, the floor past them.
        catchUpAt(d.plusDays(6).atTime(0, 0, 5).toInstant(ZoneOffset.UTC), id);
        BnplAccruals.Outcome returned = accrueAt(d.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC).toString(), id);
        assertThat(returned.posted() + returned.zero()).isZero();
        assertThat(world.accruals.of(id)).extracting(row -> row.accrual().date()).doesNotHaveDuplicates().isSorted()
                .last().isEqualTo(d.plusDays(5));
        assertThat(floor(id)).isEqualTo(d.plusDays(6));
    }

    // ------------------------------------------------------------------ helpers

    private BnplAccruals.Outcome accrueAt(String instant, BnplObligationId id) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id));
    }

    /** T-acc until caught up, at {@code instant} - the sweep's bounded loop. */
    private void catchUpAt(Instant instant, BnplObligationId id) {
        world.now = instant;
        for (int call = 0; call < 10; call++) {
            if (world.inTransaction(() -> accruals.accrue(BnplApplicationFakes.UOW, id)).caughtUp()) {
                return;
            }
        }
        throw new AssertionError("T-acc never caught up");
    }

    private ServicingOutcome serviceAt(Instant instant, BnplObligationId id) {
        world.now = instant;
        return world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, id));
    }

    private LocalDate floor(BnplObligationId id) {
        return world.servicing.principalValueFloor(BnplApplicationFakes.UOW, id);
    }

    private BnplObligationId payInN() {
        return accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
    }

    private BnplObligationId accept(BnplProduct product, BnplPlan plan) {
        return accept(product, plan, Instant.parse("2026-10-17T10:10:00Z"));
    }

    private BnplObligationId accept(BnplProduct product, BnplPlan plan, Instant at) {
        world.now = at;
        String token = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                eur(GROSS), at.plus(Duration.ofMinutes(50)), true, false));
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

    /** A principal-moving fact valued {@code date} - the stand-in for P12-TSK-018's repayment: DR wallet / CR due. */
    private void principalFact(BnplObligationId id, Money amount, LocalDate date) {
        String key = "fixture:principal:" + IDS.next();
        world.book.lines.add(new BnplApplicationFakes.Book.Line(key, wallet, Direction.DEBIT, amount, date, date));
        world.book.lines.add(new BnplApplicationFakes.Book.Line(key, accounts(id).principalDue().id(), Direction.CREDIT,
                amount, date, date));
    }

    private BigDecimal oneDay(Money base) {
        return base.toBigDecimal().multiply(new BigDecimal(RATE)).divide(BigDecimal.valueOf(365), 20,
                RoundingMode.HALF_EVEN);
    }

    private static Money sumPosted(List<BnplInterestAccrual> rows) {
        Money sum = eur(0);
        for (BnplInterestAccrual row : rows) {
            sum = sum.plus(row.accrual().posted());
        }
        return sum;
    }

    private BnplPurchaseStore.StoredSchedule schedule(BnplObligationId id) {
        return world.purchases.schedules.get(id.value());
    }

    private List<BnplBilling> billings(BnplObligationId id) {
        List<BnplBilling> rows = new ArrayList<>(world.purchases.billings.values().stream()
                .filter(row -> row.obligation().equals(id)).toList());
        rows.sort(java.util.Comparator.comparing(BnplBilling::dueDate));
        return rows;
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
