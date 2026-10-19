package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auto-collection from the wallet, hermetic (`P12-TSK-019`; {@code PHASE_12_PLAN.md} sections 7.1 T-svc, 7.2 and 12.11 F3;
 * A7, owner decision B6; {@code INV-IDEM-02}, {@code INV-LND-04} by analogy, {@code INV-BAL-04}, {@code INV-BNPL-01}): the
 * servicing step collects what each billed instalment has due from the customer's wallet alone, once per instalment and
 * business date, oldest first, partially only under partial terms, retried on the pinned cadence while due, never more than
 * due, and closes the obligation a collection settles in the same step. Over {@link BnplApplicationFakes} - in-memory
 * {@code bnpl V006}/{@code V008}/{@code V010}/{@code V011} (the attempt's uniques and ranks, the deferred "every automatic
 * repayment has its attempt"), a ledger that refuses an unbalanced or a second posting, and a unit of work that rolls every
 * fake back. The races across instances and the database's own ranks are {@code BnplCollectionDatabaseTest}'s; the wallet's
 * real row lock, the income row and the deadlock probe are {@code app}'s {@code BnplCollectionLedgerDatabaseTest}'s.
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
@DisplayName("auto-collection from the wallet in the servicing step (P12-TSK-019)")
class BnplCollectionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-019-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);
    /** Pay-in-4's due dates for a purchase on {@link #PURCHASE_DATE}: instalment 1 at purchase, then every 14 days. */
    private static final LocalDate DUE_2 = LocalDate.of(2026, 10, 31);
    private static final LocalDate DUE_3 = LocalDate.of(2026, 11, 14);
    private static final LocalDate DUE_4 = LocalDate.of(2026, 11, 28);

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
    private int termsVersion = 10;

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
        collecting(true, 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").autoCollection(true).build(), 3);
    }

    // ------------------------------------------------------------------ the collection and its posting

    @Test
    @DisplayName("on its due date an instalment is collected from the wallet in the step that bills it: one COLLECTED"
            + " attempt, one AUTO_COLLECTION repayment under <instalment>:<date> - F3 balanced DR wallet / CR"
            + " BNPL_PRINCIPAL_DUE, value-dated today - and InstalmentPaid; nothing due is left")
    void collectsOnTheDueDate() {
        Agreement p4 = payIn4();
        world.events.clear();
        ServicingOutcome step = serviceOn(DUE_2, p4);
        assertThat(step.billed()).isEqualTo(1);
        assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        BnplCollectionAttempt attempt = only(world.collections.of(p4.obligation()));
        assertThat(attempt.instalment()).isEqualTo(instalment(p4, 2).id());
        assertThat(attempt.attemptDate()).isEqualTo(DUE_2);
        assertThat(attempt.outcome()).isEqualTo(CollectionOutcome.COLLECTED);
        assertThat(attempt.due()).isEqualTo(eur(150_00));
        assertThat(attempt.collected()).isEqualTo(eur(150_00));
        BnplPurchaseStore.Repayment repayment = world.purchases.repayments.get(attempt.repayment().orElseThrow());
        assertThat(repayment.kind()).isEqualTo(BnplPurchaseStore.Repayment.AUTO_COLLECTION);
        assertThat(repayment.requestKey()).isEqualTo(instalment(p4, 2).id() + ":" + DUE_2);
        assertThat(repayment.valueDate()).isEqualTo(DUE_2);
        assertThat(world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repayment.id()))
                .extracting(line -> world.book.labels.get(line.account()) + " " + line.direction() + " "
                        + line.amount().minorUnits())
                .containsExactly("wallet DEBIT 15000", "BNPL_PRINCIPAL_DUE CREDIT 15000");
        assertThat(balance(accounts(p4).principalDue())).isEqualTo(eur(0));
        assertThat(available()).isEqualTo(eur(1_000_00 - 150_00 - 150_00));
        assertThat(world.events).extracting(EventEnvelope::eventType)
                .containsSubsequence("bnpl.InstalmentDue", "bnpl.InstalmentPaid")
                .doesNotContain(BnplCollections.FAILED_EVENT);
    }

    @Test
    @DisplayName("a second step on the same date collects nothing: the instalment was attempted today (once per"
            + " instalment and date) - and a raw second attempt for it is refused by UNIQUE (instalment_id, attempt_date)")
    void theSameDateCollectsOnce() {
        Agreement p4 = payIn4();
        serviceOn(DUE_2, p4);
        int keys = world.book.keys.size();
        world.now = Instant.parse("2026-10-31T18:00:00Z");
        ServicingOutcome again = world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, p4.obligation()));
        assertThat(again.collections()).isEmpty();
        assertThat(world.collections.of(p4.obligation())).hasSize(1);
        assertThat(world.book.keys).hasSize(keys);
        BnplCollectionAttempt first = only(world.collections.of(p4.obligation()));
        assertThatThrownBy(() -> world.inTransaction(() -> {
            world.collections.insertAttempt(BnplApplicationFakes.UOW, new BnplCollectionAttempt(UUID.randomUUID(),
                    p4.obligation(), first.instalment(), DUE_2, CollectionOutcome.NOTHING_DUE, eur(0), eur(0), 1,
                    Optional.empty(), CORRELATION));
            return null;
        })).isInstanceOf(BnplStorageException.class).hasMessageContaining("23505");
    }

    @Test
    @DisplayName("ten steps on one due date - one after another under B4 - collect once: one attempt, one repayment, one"
            + " entry")
    void tenStepsCollectOnce() {
        Agreement p4 = payIn4();
        for (int step = 0; step < 10; step++) {
            world.now = Instant.parse("2026-10-31T0" + step + ":00:00Z");
            world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, p4.obligation()));
        }
        assertThat(world.collections.of(p4.obligation())).hasSize(1);
        assertThat(autoRepayments(p4)).hasSize(1);
        assertThat(world.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX)).filteredOn(line ->
                world.book.labels.get(line.account()).equals("wallet")).hasSize(2); // instalment 1 at purchase, and this
    }

    @Test
    @DisplayName("short of the whole instalment under partial terms: as much as is available is collected - PARTIAL, the"
            + " rest still due, the wallet at zero")
    void partialCollectionWhenAllowed() {
        Agreement p4 = payIn4();
        availableTo(100_00);
        ServicingOutcome step = serviceOn(DUE_2, p4);
        assertThat(step.collected(CollectionOutcome.PARTIAL)).isEqualTo(1);
        BnplCollectionAttempt attempt = only(world.collections.of(p4.obligation()));
        assertThat(attempt.outcome()).isEqualTo(CollectionOutcome.PARTIAL);
        assertThat(attempt.due()).isEqualTo(eur(150_00));
        assertThat(attempt.collected()).isEqualTo(eur(100_00));
        assertThat(balance(accounts(p4).principalDue())).isEqualTo(eur(50_00));
        assertThat(available()).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("short of the whole instalment where the terms allow no partial collection: nothing is taken -"
            + " INSUFFICIENT_FUNDS, no repayment, no entry, BnplCollectionFailed")
    void allOrNothingWhenNot() {
        collecting(false, 1);
        Agreement p4 = payIn4();
        availableTo(100_00);
        int keys = world.book.keys.size();
        world.events.clear();
        world.payloads.clear();
        ServicingOutcome step = serviceOn(DUE_2, p4);
        assertThat(step.collected(CollectionOutcome.INSUFFICIENT_FUNDS)).isEqualTo(1);
        BnplCollectionAttempt attempt = only(world.collections.of(p4.obligation()));
        assertThat(attempt.outcome()).isEqualTo(CollectionOutcome.INSUFFICIENT_FUNDS);
        assertThat(attempt.collected()).isEqualTo(eur(0));
        assertThat(attempt.repayment()).isEmpty();
        assertThat(autoRepayments(p4)).isEmpty();
        assertThat(world.book.keys).hasSize(keys + 1); // the billing's entry alone
        assertThat(available()).isEqualTo(eur(100_00));
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(BnplCollections.FAILED_EVENT);
        String failed = world.payloads.get(world.events.stream().map(EventEnvelope::eventType).toList()
                .indexOf(BnplCollections.FAILED_EVENT));
        assertThat(failed).contains("\"outcome\":\"INSUFFICIENT_FUNDS\"", "\"dueMinor\":\"15000\"",
                instalment(p4, 2).id().toString()).doesNotContain(party.toString()).doesNotContain(wallet.value().toString());
    }

    @Test
    @DisplayName("an instalment the wallet could not cover is attempted again the next business date while due - a new"
            + " row, the money there by then collected - and never again once paid")
    void insufficientFundsRecordsAndRetriesTomorrow() {
        collecting(false, 1);
        Agreement p4 = payIn4();
        availableTo(100_00);
        serviceOn(DUE_2, p4);
        availableTo(500_00);
        ServicingOutcome tomorrow = serviceOn(DUE_2.plusDays(1), p4);
        assertThat(tomorrow.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        assertThat(world.collections.of(p4.obligation())).extracting(BnplCollectionAttempt::attemptDate,
                BnplCollectionAttempt::outcome).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DUE_2, CollectionOutcome.INSUFFICIENT_FUNDS),
                        org.assertj.core.groups.Tuple.tuple(DUE_2.plusDays(1), CollectionOutcome.COLLECTED));
        BnplPurchaseStore.Repayment repayment = only(autoRepayments(p4));
        assertThat(repayment.requestKey()).endsWith(":" + DUE_2.plusDays(1));
        assertThat(repayment.valueDate()).isEqualTo(DUE_2.plusDays(1));
        serviceOn(DUE_2.plusDays(2), p4);
        assertThat(world.collections.of(p4.obligation())).as("paid: never attempted again").hasSize(2);
    }

    @Test
    @DisplayName("the retry cadence is the pinned one: every three days, a missed date never back-filled - the attempt"
            + " is dated the day it is made")
    void theRetryCadenceIsPinned() {
        collecting(false, 3);
        Agreement p4 = payIn4();
        availableTo(0);
        serviceOn(DUE_2, p4);
        serviceOn(DUE_2.plusDays(1), p4);
        serviceOn(DUE_2.plusDays(2), p4);
        assertThat(world.collections.of(p4.obligation())).extracting(BnplCollectionAttempt::attemptDate)
                .containsExactly(DUE_2);
        // The sweeper down on the third day: the fourth's step is the next attempt, dated the fourth.
        serviceOn(DUE_2.plusDays(4), p4);
        assertThat(world.collections.of(p4.obligation())).extracting(BnplCollectionAttempt::attemptDate)
                .containsExactly(DUE_2, DUE_2.plusDays(4));
    }

    @Test
    @DisplayName("no wallet, or a wallet that cannot be debited now, is its own outcome - WALLET_NOT_POSTABLE, nothing"
            + " posted, BnplCollectionFailed")
    void aWalletNotPostableIsItsOwnOutcome() {
        Agreement p4 = payIn4();
        world.book.statuses.put(wallet, LedgerAccountStatus.POSTING_SUSPENDED);
        int keys = world.book.keys.size();
        world.events.clear();
        ServicingOutcome step = serviceOn(DUE_2, p4);
        assertThat(step.collected(CollectionOutcome.WALLET_NOT_POSTABLE)).isEqualTo(1);
        assertThat(only(world.collections.of(p4.obligation())).outcome()).isEqualTo(CollectionOutcome.WALLET_NOT_POSTABLE);
        assertThat(world.book.keys).hasSize(keys + 1);
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(BnplCollections.FAILED_EVENT);

        world.wallets.byParty.remove(party);
        serviceOn(DUE_2.plusDays(1), p4);
        assertThat(world.collections.of(p4.obligation())).extracting(BnplCollectionAttempt::outcome)
                .containsExactly(CollectionOutcome.WALLET_NOT_POSTABLE, CollectionOutcome.WALLET_NOT_POSTABLE);
    }

    @Test
    @DisplayName("never more than due: a wallet far above the bill collects exactly the bill, every line on the attempted"
            + " instalment - never the credit balance, never principal not yet due")
    void neverMoreThanDue() {
        Agreement p4 = payIn4();
        world.book.funds.put(wallet, eur(1_000_000_00));
        serviceOn(DUE_2, p4);
        BnplPurchaseStore.Repayment repayment = only(autoRepayments(p4));
        assertThat(repayment.amount()).isEqualTo(eur(150_00));
        assertThat(repayment.allocations()).allSatisfy(line -> {
            assertThat(line.target()).isEqualTo("DUE_ITEM");
            assertThat(line.instalment()).contains(instalment(p4, 2).id());
        });
        assertThat(balance(accounts(p4).creditBalance())).isEqualTo(eur(0));
        assertThat(balance(accounts(p4).principal())).as("principal not yet due untouched").isEqualTo(eur(300_00));
    }

    @Test
    @DisplayName("two instalments due (the sweeper down a fortnight) are collected oldest first, each its own attempt and"
            + " repayment")
    void oldestFirst() {
        Agreement p4 = payIn4();
        ServicingOutcome step = serviceOn(DUE_3, p4);
        assertThat(step.billed()).isEqualTo(2);
        assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(2);
        assertThat(world.collections.of(p4.obligation())).extracting(BnplCollectionAttempt::instalment)
                .containsExactly(instalment(p4, 2).id(), instalment(p4, 3).id());
        assertThat(balance(accounts(p4).principalDue())).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("once an instalment is not collected in full nothing younger is collected that day - the older one keeps"
            + " the money's priority (INSUFFICIENT_FUNDS for the younger)")
    void nothingYoungerAfterAShortOne() {
        Agreement p4 = payIn4();
        availableTo(200_00);
        serviceOn(DUE_2.minusDays(1), p4); // nothing due yet
        // Instalment 2 is collected short on its date; instalment 3 is due a fortnight later beside it.
        availableTo(100_00);
        serviceOn(DUE_2, p4);
        availableTo(120_00);
        ServicingOutcome step = serviceOn(DUE_3, p4);
        assertThat(world.collections.of(p4.obligation())).extracting(BnplCollectionAttempt::attemptDate,
                BnplCollectionAttempt::instalment, BnplCollectionAttempt::outcome).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DUE_2, instalment(p4, 2).id(), CollectionOutcome.PARTIAL),
                        org.assertj.core.groups.Tuple.tuple(DUE_3, instalment(p4, 2).id(), CollectionOutcome.COLLECTED),
                        org.assertj.core.groups.Tuple.tuple(DUE_3, instalment(p4, 3).id(), CollectionOutcome.PARTIAL));
        assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        assertThat(available()).isEqualTo(eur(0));

        // A short older one blocks the younger the same day: instalment 3 short, instalment 4 due beside it.
        availableTo(10_00);
        serviceOn(DUE_4, p4);
        assertThat(world.collections.of(p4.obligation())).filteredOn(row -> row.attemptDate().equals(DUE_4))
                .extracting(BnplCollectionAttempt::instalment, BnplCollectionAttempt::outcome).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(instalment(p4, 3).id(), CollectionOutcome.PARTIAL),
                        org.assertj.core.groups.Tuple.tuple(instalment(p4, 4).id(), CollectionOutcome.INSUFFICIENT_FUNDS));
    }

    @Test
    @DisplayName("an older instalment still due but not retried today (the pinned cadence) keeps its priority: a younger"
            + " one due today is INSUFFICIENT_FUNDS though the wallet could cover it - the allocation would pay the older"
            + " first - and nothing moves")
    void anOlderInstalmentNotRetriedTodayKeepsItsPriority() {
        collecting(false, 7);
        Agreement p4 = payIn4();
        availableTo(0);
        serviceOn(DUE_2, p4);
        serviceOn(DUE_2.plusDays(8), p4); // retried a week on: still short
        availableTo(150_00);
        int keys = world.book.keys.size();
        ServicingOutcome step = serviceOn(DUE_3, p4); // six days after its latest attempt: instalment 2 not retried
        assertThat(world.collections.of(p4.obligation())).filteredOn(row -> row.attemptDate().equals(DUE_3))
                .extracting(BnplCollectionAttempt::instalment, BnplCollectionAttempt::outcome).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(instalment(p4, 3).id(), CollectionOutcome.INSUFFICIENT_FUNDS));
        assertThat(step.collected(CollectionOutcome.INSUFFICIENT_FUNDS)).isEqualTo(1);
        assertThat(world.book.keys).as("the billing alone").hasSize(keys + 1);
        assertThat(available()).isEqualTo(eur(150_00));
    }

    @Test
    @DisplayName("an instalment the customer paid before its due date's attempt records NOTHING_DUE - the attempt still"
            + " happened, nothing moved")
    void anInstalmentAlreadyPaidRecordsNothingDue() {
        Agreement p4 = payIn4();
        // Held credit, applied at the billing (F3a) - so on its due date instalment 2 has nothing due by the collection.
        repayAt("2026-10-20T10:00:00Z", p4, "early", eur(150_00));
        ServicingOutcome step = serviceOn(DUE_2, p4);
        assertThat(step.appliedAtBilling()).isEqualTo(1);
        assertThat(step.collected(CollectionOutcome.NOTHING_DUE)).isEqualTo(1);
        BnplCollectionAttempt attempt = only(world.collections.of(p4.obligation()));
        assertThat(attempt.due()).isEqualTo(eur(0));
        assertThat(autoRepayments(p4)).isEmpty();
    }

    @Test
    @DisplayName("a customer's repayment racing the due date's collection - serialised on B4 - leaves the collection only"
            + " what is still due, in both orders: never more than due")
    void aRepaymentRacingTheCollectionNeverOverCollects() {
        // Collection first: the due date's step collects instalment 2; the customer's repayment that evening finds
        // nothing due and is held as credit - never a second payment of the item.
        Agreement collectedFirst = payIn4();
        serviceOn(DUE_2, collectedFirst);
        BnplRepayments.Repaid after = repayAt("2026-10-31T20:00:00Z", collectedFirst, "after", eur(150_00));
        assertThat(after.repayment().allocations()).extracting(BnplPurchaseStore.Allocation::target)
                .containsExactly("CREDIT_BALANCE");
        // Repayment first: the customer pays part of instalment 2 on its due date before the step (held - it is not yet
        // billed), the step's billing applies it, and the collection takes only the rest.
        Agreement repaidFirst = payIn4();
        repayAt("2026-10-31T00:30:00Z", repaidFirst, "before", eur(40_00));
        serviceOn(DUE_2, repaidFirst);
        BnplCollectionAttempt attempt = only(world.collections.of(repaidFirst.obligation()));
        assertThat(attempt.due()).isEqualTo(eur(110_00));
        assertThat(attempt.collected()).isEqualTo(eur(110_00));
        for (Agreement agreement : List.of(collectedFirst, repaidFirst)) {
            BnplLedgerAccounts accounts = accounts(agreement);
            assertThat(balance(accounts.principalDue())).as("never more than due, in either order").isEqualTo(eur(0));
            BnplBilling billing = world.purchases.billings.get(instalment(agreement, 2).id());
            assertThat(world.purchases.paid(billing, "PRINCIPAL", eur(0))).isEqualTo(eur(150_00));
        }
        assertThat(balance(accounts(collectedFirst).creditBalance())).isEqualTo(eur(150_00));
        assertThat(balance(accounts(repaidFirst).creditBalance())).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("two obligations of one customer each collect what they find available under the one wallet: the second"
            + " bounded by what the first left (partial), the wallet never below zero")
    void twoObligationsOfOneCustomerShareTheWallet() {
        Agreement one = payIn4();
        Agreement two = payIn4();
        availableTo(200_00);
        serviceOn(DUE_2, one);
        serviceOn(DUE_2, two);
        assertThat(only(world.collections.of(one.obligation())).outcome()).isEqualTo(CollectionOutcome.COLLECTED);
        BnplCollectionAttempt second = only(world.collections.of(two.obligation()));
        assertThat(second.outcome()).isEqualTo(CollectionOutcome.PARTIAL);
        assertThat(second.collected()).isEqualTo(eur(50_00));
        assertThat(available()).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a collection that settles the obligation closes it in its step: the closure names the collection's"
            + " repayment, CLOSED (REPAID), the permit never re-stamped")
    void aCollectionThatSettlesClosesInItsStep() {
        Agreement p4 = payIn4();
        serviceOn(DUE_2, p4);
        serviceOn(DUE_3, p4);
        world.events.clear();
        ServicingOutcome last;
        try (SecurityContext.Scope scope = SecurityContext.enter(SYSTEM)) {
            last = serviceOn(DUE_4, p4);
        }
        assertThat(last.closed()).isTrue();
        assertThat(last.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        BnplClosure closure = world.repayments.closures.get(p4.obligation().value());
        BnplCollectionAttempt attempt = world.collections.of(p4.obligation()).get(2);
        assertThat(closure.repayment()).isEqualTo(attempt.repayment());
        assertThat(closure.refund()).isEqualTo(eur(0));
        assertThat(world.purchases.obligations.get(p4.obligation().value()).status())
                .isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(world.events).extracting(EventEnvelope::eventType)
                .containsSubsequence("bnpl.InstalmentPaid", BnplClosures.CLOSED_EVENT);
        assertThat(available()).isEqualTo(eur(1_000_00 - 600_00));
    }

    @Test
    @DisplayName("pay-in-N: the instalment's accrued interest and its principal are collected together, interest first"
            + " (FEES -> INTEREST -> PRINCIPAL)")
    void payInNCollectsInterestThenPrincipal() {
        Agreement pn = accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate due = instalment(pn, 1).dueDate();
        for (LocalDate day = PURCHASE_DATE; day.isBefore(due); day = day.plusDays(1)) {
            world.servicing.accrue(pn.obligation(), day, eur(10));
        }
        ServicingOutcome step = serviceOn(due, pn);
        assertThat(step.collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
        BnplPurchaseStore.Repayment repayment = only(autoRepayments(pn));
        assertThat(repayment.allocations()).extracting(line -> line.component().orElseThrow())
                .containsExactly("INTEREST", "PRINCIPAL");
        assertThat(balance(accounts(pn).interestDue())).isEqualTo(eur(0));
        assertThat(balance(accounts(pn).principalDue())).isEqualTo(eur(0));
    }

    // ------------------------------------------------------------------ authority, order, atomicity

    @Test
    @DisplayName("terms that do not collect automatically collect nothing - no attempt, no wallet lock")
    void noAutoCollectionNoAttempt() {
        world.terms.activate(BnplTermsFixtures.payIn4().build(), termsVersion++);
        Agreement p4 = payIn4();
        world.log.clear();
        ServicingOutcome step = serviceOn(DUE_2, p4);
        assertThat(step.collections()).isEmpty();
        assertThat(world.collections.rows).isEmpty();
        assertThat(world.log).doesNotContain("lock wallet");
    }

    @Test
    @DisplayName("the only source is the wallet, in the Phase 12 lock order: B4, the obligation's rows (L8), the wallet"
            + " alone (P), then L8 over the entry's rows, the posting, the repayment, the attempt - no payable, no"
            + " capital, no profile")
    void theOnlySourceIsTheWalletInTheLockOrder() {
        Agreement p4 = payIn4();
        world.log.clear();
        serviceOn(DUE_2, p4);
        assertThat(world.log).startsWith("B4");
        assertThat(world.log).containsSubsequence("B4", "L8", "post bnpl.billing:", "attempts read", "lock wallet",
                "available wallet", "L8", "post bnpl.repayment:", "repayment insert", "attempt insert");
        assertThat(world.log).doesNotContain("lock payable", "L6", "lock profile");
        assertThat(world.log.stream().filter("lock wallet"::equals)).as("the wallet once a step").hasSize(1);
    }

    @Test
    @DisplayName("a failure at the attempt rolls the whole step back - the billing, the repayment, its entry, the events;"
            + " the retry collects once")
    void aFailureWritesNothing() {
        Agreement p4 = payIn4();
        int keys = world.book.keys.size();
        world.events.clear();
        world.failAt = "attempt insert";
        assertThatThrownBy(() -> serviceOn(DUE_2, p4)).isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
        assertThat(world.book.keys).hasSize(keys);
        assertThat(world.events).isEmpty();
        assertThat(autoRepayments(p4)).isEmpty();
        assertThat(world.collections.rows).isEmpty();
        world.failAt = null;
        assertThat(serviceOn(DUE_2, p4).collected(CollectionOutcome.COLLECTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("an automatic repayment without its attempt is refused at commit (bnpl V011's deferred trigger) -"
            + " nothing written")
    void anAutomaticRepaymentWithoutItsAttemptIsRefused() {
        Agreement p4 = payIn4();
        serviceOn(DUE_2, p4);
        world.collections.rows.clear(); // the planted gap: the money's record gone
        assertThatThrownBy(() -> world.inTransaction(() -> null)).isInstanceOf(BnplStorageException.class)
                .hasMessageContaining("named by its collection attempt");
    }

    @Test
    @DisplayName("an attempt's outcome, figures and repayment agree - every other shape is refused at construction")
    void theAttemptsShape() {
        UUID id = UUID.randomUUID();
        BnplObligationId obligation = BnplObligationId.of(IDS.next());
        Optional<UUID> moved = Optional.of(UUID.randomUUID());
        for (Object[] bad : List.<Object[]>of(
                new Object[] {CollectionOutcome.COLLECTED, 100L, 90L, moved},
                new Object[] {CollectionOutcome.COLLECTED, 100L, 100L, Optional.empty()},
                new Object[] {CollectionOutcome.PARTIAL, 100L, 100L, moved},
                new Object[] {CollectionOutcome.INSUFFICIENT_FUNDS, 100L, 10L, Optional.empty()},
                new Object[] {CollectionOutcome.WALLET_NOT_POSTABLE, 0L, 0L, Optional.empty()},
                new Object[] {CollectionOutcome.NOTHING_DUE, 10L, 0L, Optional.empty()})) {
            @SuppressWarnings("unchecked")
            Optional<UUID> repayment = (Optional<UUID>) bad[3];
            assertThatThrownBy(() -> new BnplCollectionAttempt(id, obligation, id, DUE_2, (CollectionOutcome) bad[0],
                    eur((Long) bad[1]), eur((Long) bad[2]), 1, repayment, CORRELATION))
                    .as(bad[0].toString()).isInstanceOf(IllegalArgumentException.class);
        }
        BnplCollectionAttempt ok = new BnplCollectionAttempt(id, obligation, id, DUE_2, CollectionOutcome.PARTIAL,
                eur(100), eur(40), 1, moved, CORRELATION);
        assertThat(ok.toString()).as("identifiers and the outcome only - never a figure")
                .isEqualTo("BnplCollectionAttempt[" + id + ", obligation " + obligation.value() + ", 2026-10-31, PARTIAL]");
        assertThat(BnplCollectionAttempt.requestKey(id, DUE_2)).isEqualTo(id + ":2026-10-31");
        assertThat(CollectionOutcome.PARTIAL.collected()).isTrue();
        assertThat(CollectionOutcome.WALLET_NOT_POSTABLE.failed()).isTrue();
        assertThat(CollectionOutcome.NOTHING_DUE.failed()).isFalse();
    }

    @Test
    @DisplayName("the candidates: on the due date always; afterwards only while due, on the pinned cadence from the latest"
            + " attempt; never on or after a date already attempted")
    void theCandidates() {
        UUID instalment = UUID.randomUUID();
        BnplPurchaseStore.BilledItem due = item(instalment, DUE_2, 150_00);
        BnplPurchaseStore.BilledItem settled = item(instalment, DUE_2, 0);
        assertThat(BnplCollections.candidate(due, Map.of(), DUE_2, 1)).isTrue();
        assertThat(BnplCollections.candidate(settled, Map.of(), DUE_2, 1)).as("its due date always").isTrue();
        assertThat(BnplCollections.candidate(settled, Map.of(), DUE_2.plusDays(1), 1)).isFalse();
        assertThat(BnplCollections.candidate(due, Map.of(instalment, DUE_2), DUE_2, 1)).isFalse();
        assertThat(BnplCollections.candidate(due, Map.of(instalment, DUE_2), DUE_2.plusDays(1), 1)).isTrue();
        assertThat(BnplCollections.candidate(due, Map.of(instalment, DUE_2), DUE_2.plusDays(1), 2)).isFalse();
        assertThat(BnplCollections.candidate(due, Map.of(instalment, DUE_2), DUE_2.plusDays(2), 2)).isTrue();
        assertThat(BnplCollections.candidate(due, Map.of(instalment, DUE_2.plusDays(5)), DUE_2.plusDays(3), 1))
                .as("never before a later attempt (a clock stepped back)").isFalse();
        assertThat(BnplCollections.candidate(due, Map.of(), DUE_2.plusDays(9), 7)).as("a missed due date").isTrue();
    }

    // ------------------------------------------------------------------ helpers

    /** An accepted purchase's agreement and obligation. */
    record Agreement(BnplAgreementId agreement, BnplObligationId obligation) {}

    /** Pay-in-4 terms that collect automatically - partially or not, on the cadence given - for the next purchases. */
    private void collecting(boolean partial, int retryDays) {
        world.terms.activate(BnplTermsFixtures.payIn4().autoCollection(true).partialCollection(partial)
                .collectionRetryDays(retryDays).build(), termsVersion++);
    }

    private ServicingOutcome serviceOn(LocalDate date, Agreement agreement) {
        world.now = date.atTime(9, 0).toInstant(ZoneOffset.UTC);
        return world.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, agreement.obligation()));
    }

    private BnplRepayments.Repaid repayAt(String instant, Agreement agreement, String key, Money amount) {
        world.now = Instant.parse(instant);
        return world.inTransaction(() -> repayments.repay(BnplApplicationFakes.UOW, agreement.agreement(), party,
                new BnplRepayments.Request(key, amount), customer, CORRELATION));
    }

    private Agreement payIn4() {
        return accept(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
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

    private List<BnplPurchaseStore.Repayment> autoRepayments(Agreement agreement) {
        return world.purchases.repayments.values().stream().filter(row -> row.obligation().equals(agreement.obligation())
                && row.kind().equals(BnplPurchaseStore.Repayment.AUTO_COLLECTION)).toList();
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

    private Money available() {
        return world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
    }

    /** The wallet's available balance set to exactly {@code minor} - the fixture standing in for pay-ins and spending. */
    private void availableTo(long minor) {
        Money now = available();
        world.book.funds.put(wallet, world.book.funds.get(wallet).plus(eur(minor).minus(now)));
    }

    private static BnplPurchaseStore.BilledItem item(UUID instalment, LocalDate dueDate, long dueMinor) {
        Money zero = eur(0);
        return new BnplPurchaseStore.BilledItem(UUID.randomUUID(), instalment, dueDate, zero, zero, eur(150_00), zero,
                zero, eur(150_00 - dueMinor));
    }

    private static <T> T only(List<T> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
