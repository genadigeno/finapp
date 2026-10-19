package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-rev, hermetic (`P12-TSK-024`; {@code PHASE_12_PLAN.md} sections 7.1, 7.2, 12.11 F8 and 13.2 scenario 12; lending's
 * {@code RepaymentReversals} mirrored; {@code INV-REV-01}, {@code INV-REV-02}, {@code INV-AUD-04}, {@code INV-HIST-01},
 * {@code INV-CRD-09}, {@code INV-LND-13}, {@code INV-BNPL-10}): an operator's mistake in applying money is undone by the
 * EXACT swap of the repayment's F3 entry under four eyes - reasoned, in the Phase 12 lock order, never an edit; the reversed
 * amount due again and every later allocation untouched; refused for a closed obligation, a payoff, a repayment whose held
 * credit was applied, beyond the capital headroom, to a wallet that cannot be credited, by its own proposer (audited
 * {@code FAILED}) and by an operator of the customer's own party. Over {@link BnplCorrectionWorld}; the races across
 * instances and the database's own ranks are {@code BnplRepaymentReversalDatabaseTest}'s.
 */
@DisplayName("repayment reversal under four eyes (P12-TSK-024)")
class BnplRepaymentReversalsTest {

    private BnplCorrectionWorld world;
    private BnplConditionWorld base;
    private BnplApplicationFakes fakes;

    @BeforeEach
    void setUp() {
        world = new BnplCorrectionWorld();
        base = world.base;
        fakes = world.fakes;
    }

    /** Instalment 2 billed and repaid whole on its due date (R1), instalment 3 billed and repaid in part (R2). */
    private record Repaid(BnplConditionWorld.Agreement agreement, BnplPurchaseStore.Repayment first,
            BnplPurchaseStore.Repayment second) {}

    private Repaid twoRepayments() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        base.serviceOn(DUE_2, p4);
        BnplPurchaseStore.Repayment first = base.repayOn(DUE_2, p4, "r1", 150_00).repayment();
        base.serviceOn(DUE_3, p4);
        BnplPurchaseStore.Repayment second = base.repayOn(DUE_3, p4, "r2", 100_00).repayment();
        return new Repaid(p4, first, second);
    }

    // ------------------------------------------------------------------ scenario 12

    @Test
    @DisplayName("scenario 12: a reversal after allocation, a later repayment and a later billing reopens exactly what it"
            + " paid - F8 the exact swap, line for line (CR wallet / DR BNPL_PRINCIPAL_DUE), later allocations untouched, the instalment"
            + " past due again and late from the next step (DPD from its own due date, its late fee assessed)")
    void aReversalReopensWhatItPaid() {
        Repaid repaid = twoRepayments();
        BnplConditionWorld.Agreement p4 = repaid.agreement();
        com.finapp.sharedkernel.money.Money walletBefore = fakes.book.availableUnderLock(BnplApplicationFakes.UOW,
                base.wallet);
        List<BnplApplicationFakes.Book.Line> originalBefore = List.copyOf(
                fakes.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repaid.first().id()));
        LocalDate day = DUE_3.plusDays(1);
        BnplRepaymentReversal proposed = world.propose(day, p4, repaid.first().id(), world.alice);
        assertThat(proposed.status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        assertThat(fakes.book.linesOf(BnplRepaymentReversals.REVERSAL_KEY_PREFIX)).as("nothing posted by a proposal")
                .isEmpty();
        fakes.events.clear();
        fakes.payloads.clear();
        BnplRepaymentReversals.Decided decided = world.approve(day, p4, repaid.first().id(), proposed.id(), world.bob);

        assertThat(decided.replayed()).isFalse();
        BnplRepaymentReversal approved = decided.reversal();
        assertThat(approved.status()).isEqualTo(BnplCorrectionStatus.APPROVED);
        assertThat(approved.decidedBy()).contains(world.bob.id());
        assertThat(approved.valueDate()).contains(day);
        assertThat(approved.agreementVersion()).isEqualTo(repaid.first().agreementVersion());
        String key = BnplRepaymentReversals.REVERSAL_KEY_PREFIX + proposed.id();
        assertThat(base.labelled(fakes.book.linesOf("ledger.reverse:" + key)))
                .containsExactly("wallet CREDIT 15000", "BNPL_PRINCIPAL_DUE DEBIT 15000");
        assertThat(fakes.book.reversalOf).containsEntry(approved.entry().orElseThrow(), repaid.first().entry());
        // Exactly what it paid is due again; the later repayment's allocation stands, never re-cut.
        assertThat(world.item(p4, 2).due(AllocationComponent.PRINCIPAL)).isEqualTo(eur(150_00));
        assertThat(world.item(p4, 3).due(AllocationComponent.PRINCIPAL)).isEqualTo(eur(50_00));
        assertThat(fakes.purchases.repayments.get(repaid.second().id()).allocations())
                .isEqualTo(repaid.second().allocations());
        assertThat(base.balance(base.accounts(p4).principalDue())).isEqualTo(eur(200_00));
        assertThat(fakes.corrections.negations).containsOnlyKeys(repaid.first().allocations().stream()
                .map(BnplPurchaseStore.Allocation::id).toList());
        // The rows explain the ledger after it.
        new BnplReceivables(fakes.purchases, fakes.book).dueState(BnplApplicationFakes.UOW, p4.obligation(),
                base.accounts(p4));
        // The money returned to the wallet; the original entry untouched.
        assertThat(fakes.book.availableUnderLock(BnplApplicationFakes.UOW, base.wallet))
                .isEqualTo(walletBefore.plus(eur(150_00)));
        assertThat(fakes.book.linesOf(BnplRepayments.POSTING_KEY_PREFIX + repaid.first().id()))
                .isEqualTo(originalBefore);
        // The event: ids and amounts, never the wallet, a party or a reason.
        assertThat(fakes.events).extracting(EventEnvelope::eventType)
                .containsExactly(BnplRepaymentReversals.REVERSED_EVENT);
        assertThat(fakes.payloads.get(0)).contains("\"principalReopenedMinor\":\"15000\"")
                .contains("\"repaymentKind\":\"CUSTOMER\"").contains(repaid.first().id().toString())
                .doesNotContain(base.party.toString()).doesNotContain(base.wallet.value().toString())
                .doesNotContain("bank statement");
        // DPD recomputed by the next step from instalment 2's own due date; its late fee assessed (age 16 >= day 5).
        ServicingOutcome next = base.serviceOn(DUE_3.plusDays(2), p4);
        assertThat(next.fees()).isEqualTo(1);
        assertThat(base.fees(p4)).containsExactly("2 " + DUE_3.plusDays(2) + " 500");
        assertThat(base.history(p4)).last().asString()
                .startsWith(DUE_3.plusDays(2) + " DELINQUENCY " + (DUE_3.plusDays(2).toEpochDay() - DUE_2.toEpochDay()));
    }

    @Test
    @DisplayName("each act is audited with its reason, actor and outcome; the proposal posts nothing and the approval names"
            + " both entries - never an amount")
    void eachActIsAudited() {
        Repaid repaid = twoRepayments();
        fakes.audited.clear();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.bob);
        assertThat(fakes.audited).extracting(record -> record.operation().code(), AuditRecord::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("bnpl.RepaymentReversalProposed", AuditOutcome.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple("bnpl.RepaymentReversalApproved", AuditOutcome.SUCCEEDED));
        assertThat(fakes.audited.get(0).reason()).contains("applied to the wrong agreement");
        assertThat(fakes.audited.get(1).actor()).isEqualTo(world.bob);
        assertThat(fakes.audited.get(1).changeSummary().orElseThrow()).contains("reverses=" + repaid.first().entry().value())
                .doesNotContainPattern("\\b(15000|150\\.00)\\b");
    }

    // ------------------------------------------------------------------ four eyes, idempotency, one live proposal

    @Test
    @DisplayName("ten approvers, one reversal: the first approves, nine find it decided (ProposalNotPending) - one entry,"
            + " one set of negating rows; the same approver's retry converges")
    void tenApproversOneReversal() {
        Repaid repaid = twoRepayments();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        int approved = 0;
        int lost = 0;
        for (int approver = 0; approver < 10; approver++) {
            Actor other = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
            world.operatorParties.put(other.id(), UUID.randomUUID());
            try {
                world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), other);
                approved++;
            } catch (BnplRepaymentReversals.ProposalNotPending decided) {
                lost++;
            }
        }
        assertThat(approved).isEqualTo(1);
        assertThat(lost).isEqualTo(9);
        assertThat(fakes.book.keys).filteredOn(k -> k.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX)).hasSize(1);
        assertThat(fakes.corrections.negations).hasSize(repaid.first().allocations().size());
        // The winner's retry under a new key converges on its own decision.
        String winner = fakes.corrections.reversals.get(proposed.id()).decidedBy().orElseThrow();
        Actor same = new Actor(winner, ActorType.CUSTOMER);
        assertThat(world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), same).replayed())
                .isTrue();
        assertThat(fakes.book.keys).filteredOn(k -> k.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX)).hasSize(1);
    }

    @Test
    @DisplayName("one live proposal per repayment: a second is ProposalPending (and the partial unique inserts nothing);"
            + " after a rejection - the proposer's withdrawal - it may be proposed again; once approved, never again")
    void oneLiveProposalPerRepayment() {
        Repaid repaid = twoRepayments();
        UUID r1 = repaid.first().id();
        BnplRepaymentReversal first = world.propose(DUE_3, repaid.agreement(), r1, world.alice);
        assertThatThrownBy(() -> world.propose(DUE_3, repaid.agreement(), r1, world.bob))
                .isInstanceOf(BnplRepaymentReversals.ProposalPending.class);
        assertThat(fakes.corrections.insertReversal(BnplApplicationFakes.UOW, new BnplCorrectionStore.NewReversal(
                UUID.randomUUID(), repaid.agreement().obligation(), r1, repaid.first().agreement(),
                repaid.first().agreementVersion(), world.bob, "again", BnplConditionWorld.CORRELATION))).isEmpty();
        BnplRepaymentReversals.Decided withdrawn = world.reject(DUE_3, repaid.agreement(), r1, first.id(), world.alice);
        assertThat(withdrawn.reversal().status()).isEqualTo(BnplCorrectionStatus.REJECTED);
        assertThat(fakes.book.keys).noneMatch(k -> k.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX));
        BnplRepaymentReversal second = world.propose(DUE_3, repaid.agreement(), r1, world.bob);
        world.approve(DUE_3, repaid.agreement(), r1, second.id(), world.alice);
        assertThatThrownBy(() -> world.propose(DUE_3, repaid.agreement(), r1, world.bob))
                .isInstanceOf(BnplRepaymentReversals.RepaymentNotReversible.class)
                .hasMessageContaining("already reversed");
        assertThatThrownBy(() -> world.reject(DUE_3, repaid.agreement(), r1, second.id(), world.bob))
                .isInstanceOf(BnplRepaymentReversals.ProposalNotPending.class);
    }

    @Test
    @DisplayName("self-approval refused at both ranks: the domain refuses the proposer's approval before any write, the"
            + " caller records it FAILED in a transaction of its own; beneath it the four-eyes CHECK refuses the row")
    void selfApprovalRefusedAtBothRanksAndAudited() {
        Repaid repaid = twoRepayments();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        int keys = fakes.book.keys.size();
        fakes.audited.clear();
        BnplRepaymentReversals.SelfApprovalRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplRepaymentReversals.SelfApprovalRefused.class,
                () -> world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.alice));
        assertThat(fakes.book.keys).hasSize(keys);
        assertThat(fakes.corrections.reversals.get(proposed.id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        assertThat(fakes.audited).isEmpty();
        world.on(DUE_3, world.alice, () -> {
            world.reversals.recordRefusal(BnplApplicationFakes.UOW, refused, world.alice,
                    BnplConditionWorld.CORRELATION);
            return null;
        });
        assertThat(fakes.audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.SELF_APPROVAL_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(proposed.id().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("act=repayment-reversal:approve");
        });
        // The database's rank: a decision row by its proposer is refused whatever the writer.
        assertThatThrownBy(() -> fakes.corrections.decideReversal(BnplApplicationFakes.UOW,
                new BnplCorrectionStore.ReversalDecision(proposed.id(), BnplCorrectionStatus.APPROVED, world.alice,
                        "mine", Optional.of(com.finapp.ledger.JournalEntryId.of(BnplConditionWorld.IDS.next())),
                        Optional.of(DUE_3))))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("four_eyes");
    }

    @Test
    @DisplayName("an employee never services their own party's agreement - nor one the platform cannot place in a party:"
            + " propose, approve and reject refused before any lock, recorded FAILED on request")
    void anEmployeeNeverServicesTheirOwnPartysAgreement() {
        Repaid repaid = twoRepayments();
        UUID r1 = repaid.first().id();
        assertThatThrownBy(() -> world.propose(DUE_3, repaid.agreement(), r1, world.insider))
                .isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        Actor nobody = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> world.propose(DUE_3, repaid.agreement(), r1, nobody))
                .isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), r1, world.alice);
        fakes.log.clear();
        BnplRepaymentReversals.SelfDealingRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplRepaymentReversals.SelfDealingRefused.class,
                () -> world.approve(DUE_3, repaid.agreement(), r1, proposed.id(), world.insider));
        assertThat(fakes.log).doesNotContain("L0", "B4", "B6");
        assertThatThrownBy(() -> world.reject(DUE_3, repaid.agreement(), r1, proposed.id(), world.insider))
                .isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        assertThat(fakes.corrections.reversals.get(proposed.id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        fakes.audited.clear();
        world.on(DUE_3, world.insider, () -> {
            world.reversals.recordRefusal(BnplApplicationFakes.UOW, refused, world.insider,
                    BnplConditionWorld.CORRELATION);
            return null;
        });
        assertThat(fakes.audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(repaid.agreement().agreement().value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("act=repayment-reversal:approve");
        });
    }

    // ------------------------------------------------------------------ what is never reversed

    @Test
    @DisplayName("a closing repayment is not reversible: its obligation is CLOSED (ObligationClosed) - and nothing of a"
            + " CLOSED obligation is, so a proposal left standing can only be rejected")
    void aClosingRepaymentIsNotReversible() {
        Repaid repaid = twoRepayments();
        BnplConditionWorld.Agreement p4 = repaid.agreement();
        BnplRepaymentReversal standing = world.propose(DUE_3, p4, repaid.first().id(), world.alice);
        base.serviceOn(BnplConditionWorld.DUE_4, p4);
        BnplRepayments.Repaid closing = base.repayEverythingDueOn(BnplConditionWorld.DUE_4, p4);
        assertThat(closing.closure()).isPresent();
        assertThatThrownBy(() -> world.propose(BnplConditionWorld.DUE_4, p4, closing.repayment().id(), world.alice))
                .isInstanceOf(BnplRepaymentReversals.ObligationClosed.class);
        assertThatThrownBy(() -> world.approve(BnplConditionWorld.DUE_4, p4, repaid.first().id(), standing.id(),
                world.bob)).isInstanceOf(BnplRepaymentReversals.ObligationClosed.class);
        assertThat(world.reject(BnplConditionWorld.DUE_4, p4, repaid.first().id(), standing.id(), world.bob)
                .reversal().status()).isEqualTo(BnplCorrectionStatus.REJECTED);
        assertThat(fakes.book.keys).noneMatch(k -> k.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX));
    }

    @Test
    @DisplayName("a payoff is never reversed: its obligation is CLOSED PAID_OFF_EARLY - and beneath, the trigger refuses a"
            + " reversal naming a PAYOFF repayment whatever the obligation's state")
    void aPayoffIsNotReversible() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        BnplServicing servicingStep = base.servicing;
        BnplRepayments posting = base.repayments;
        BnplPayoffs payoffs = fakes.payoffsOver(servicingStep, posting, base.closures, BnplConditionWorld.IDS);
        fakes.now = DUE_2.minusDays(3).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC);
        BnplPayoffQuote quote = fakes.inTransaction(() -> payoffs.quote(BnplApplicationFakes.UOW, p4.agreement(),
                base.party, new BnplPayoffs.QuoteRequest("q", Optional.empty()), BnplConditionWorld.CORRELATION)).quote();
        BnplPayoffs.Executed executed = fakes.inTransaction(() -> payoffs.execute(BnplApplicationFakes.UOW,
                p4.agreement(), base.party, "x", quote.id(), base.customer, BnplConditionWorld.CORRELATION));
        UUID payoff = executed.repayment().orElseThrow().id();
        assertThatThrownBy(() -> world.propose(DUE_2.minusDays(3), p4, payoff, world.alice))
                .isInstanceOf(BnplRepaymentReversals.ObligationClosed.class);
        BnplPurchaseStore.Repayment row = fakes.purchases.repayments.get(payoff);
        assertThatThrownBy(() -> fakes.corrections.insertReversal(BnplApplicationFakes.UOW,
                new BnplCorrectionStore.NewReversal(UUID.randomUUID(), p4.obligation(), payoff, row.agreement(),
                        row.agreementVersion(), world.alice, "r", BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplStorageException.class);
    }

    @Test
    @DisplayName("a repayment whose held credit was since applied is not reversible - the swap would drive the credit"
            + " balance below zero (RepaymentNotReversible), nothing written")
    void aRepaymentWhoseHeldCreditWasAppliedIsNotReversible() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        BnplPurchaseStore.Repayment early = base.repayOn(DUE_2.minusDays(3), p4, "early", 40_00).repayment();
        assertThat(early.allocations()).singleElement()
                .satisfies(line -> assertThat(line.target()).isEqualTo("CREDIT_BALANCE"));
        // Before the billing the credit is still held: reversible - proposed, then withdrawn.
        BnplRepaymentReversal proposed = world.propose(DUE_2.minusDays(2), p4, early.id(), world.alice);
        world.reject(DUE_2.minusDays(2), p4, early.id(), proposed.id(), world.alice);
        base.serviceOn(DUE_2, p4);
        assertThat(base.balance(base.accounts(p4).creditBalance())).isEqualTo(eur(0));
        assertThatThrownBy(() -> world.propose(DUE_2, p4, early.id(), world.alice))
                .isInstanceOf(BnplRepaymentReversals.RepaymentNotReversible.class)
                .hasMessageContaining("since been applied");
    }

    @Test
    @DisplayName("a held-credit repayment still held is reversed exactly - DR BNPL_CREDIT_BALANCE / CR wallet - and takes"
            + " no capital row (no principal re-instated)")
    void aHeldCreditRepaymentIsReversedWithoutCapital() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        BnplPurchaseStore.Repayment early = base.repayOn(DUE_2.minusDays(3), p4, "early", 40_00).repayment();
        BnplRepaymentReversal proposed = world.propose(DUE_2.minusDays(2), p4, early.id(), world.alice);
        fakes.log.clear();
        world.approve(DUE_2.minusDays(2), p4, early.id(), proposed.id(), world.bob);
        assertThat(fakes.log).doesNotContain("L6");
        assertThat(base.labelled(fakes.book.linesOf("ledger.reverse:" + BnplRepaymentReversals.REVERSAL_KEY_PREFIX)))
                .containsExactly("wallet CREDIT 4000", "BNPL_CREDIT_BALANCE DEBIT 4000");
        assertThat(base.balance(base.accounts(p4).creditBalance())).isEqualTo(eur(0));
    }

    // ------------------------------------------------------------------ the lock order, exposure and capital

    @Test
    @DisplayName("the approval takes the profile first, then B4, B6, L6 (principal re-instated), the wallet (P) and L8,"
            + " and only then reverses - the Phase 12 order")
    void reversalTakesTheProfileFirst() {
        Repaid repaid = twoRepayments();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        fakes.log.clear();
        world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.bob);
        List<String> order = new ArrayList<>();
        for (String entry : fakes.log) {
            if (List.of("L0", "B4", "B6", "L6", "lock wallet", "L8").contains(entry) || entry.startsWith("reverse ")
                    || entry.startsWith("reversal ")) {
                order.add(entry);
            }
        }
        assertThat(order).containsExactly("L0", "B4", "B6", "L6", "lock wallet", "L8", "reverse bnpl.repayment-reversal:",
                "reversal APPROVED");
    }

    @Test
    @DisplayName("exposure and deployed capital rise by exactly the principal reopened (INV-CRD-09, INV-BNPL-10)")
    void exposureRisesByThePrincipalReopened() {
        Repaid repaid = twoRepayments();
        com.finapp.sharedkernel.money.Money before = fakes.book.bnplPrincipal();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.bob);
        assertThat(fakes.book.bnplPrincipal()).isEqualTo(before.plus(eur(150_00)));
    }

    @Test
    @DisplayName("a reversal never over-deploys capital: headroom below the principal re-instated is CapitalUnavailable -"
            + " nothing posted, the proposal still PROPOSED - and it goes through once capital is free")
    void aReversalNeverOverDeploysCapital() {
        Repaid repaid = twoRepayments();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        fakes.capital.recognised = fakes.book.bnplPrincipal().plus(eur(149_99));
        assertThatThrownBy(() -> world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.bob))
                .isInstanceOf(BnplRepaymentReversals.CapitalUnavailable.class);
        assertThat(fakes.book.keys).noneMatch(k -> k.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX));
        assertThat(fakes.corrections.reversals.get(proposed.id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        fakes.capital.recognised = fakes.book.bnplPrincipal().plus(eur(150_00));
        world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.bob);
        assertThat(fakes.capital.recognised.minus(fakes.book.bnplPrincipal()).isNegative()).isFalse();
    }

    @Test
    @DisplayName("a wallet that cannot be credited now refuses the approval - WalletUnavailable, nothing posted, the"
            + " proposal still PROPOSED")
    void aWalletNotPostableRefusesTheApproval() {
        Repaid repaid = twoRepayments();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        fakes.book.statuses.put(base.wallet, com.finapp.ledger.LedgerAccountStatus.POSTING_SUSPENDED);
        assertThatThrownBy(() -> world.approve(DUE_3, repaid.agreement(), repaid.first().id(), proposed.id(), world.bob))
                .isInstanceOf(BnplRepaymentReversals.WalletUnavailable.class);
        assertThat(fakes.book.keys).noneMatch(k -> k.contains(BnplRepaymentReversals.REVERSAL_KEY_PREFIX));
        assertThat(fakes.corrections.reversals.get(proposed.id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
    }

    @Test
    @DisplayName("on a database clock stepped back behind the record the reversal is valued at the floor - never a date"
            + " the record has closed (the Phase 11 gate's R1)")
    void theReversalIsValuedNeverBeforeTheFloor() {
        Repaid repaid = twoRepayments();
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        BnplRepaymentReversal approved = world.approve(DUE_3.minusDays(2), repaid.agreement(), repaid.first().id(),
                proposed.id(), world.bob).reversal();
        assertThat(approved.valueDate()).contains(DUE_3);
        assertThat(fakes.book.linesOf("ledger.reverse:" + BnplRepaymentReversals.REVERSAL_KEY_PREFIX))
                .allSatisfy(line -> {
                    assertThat(line.valueDate()).isEqualTo(DUE_3);
                    assertThat(line.postingDate()).isEqualTo(DUE_3.minusDays(2));
                });
    }

    @Test
    @DisplayName("an automatic collection is reversed like any repayment - its instalment due again")
    void anAutoCollectionIsReversible() {
        base.withFees(BnplTermsFixtures.payIn4().autoCollection(true));
        BnplConditionWorld.Agreement p4 = base.payIn4();
        base.serviceOn(DUE_2, p4);
        BnplPurchaseStore.Repayment collected = fakes.purchases.repayments.values().stream()
                .filter(row -> row.kind().equals(BnplPurchaseStore.Repayment.AUTO_COLLECTION)).findFirst().orElseThrow();
        BnplRepaymentReversal proposed = world.propose(DUE_2, p4, collected.id(), world.alice);
        world.approve(DUE_2, p4, collected.id(), proposed.id(), world.bob);
        assertThat(world.item(p4, 2).due(AllocationComponent.PRINCIPAL)).isEqualTo(eur(150_00));
    }

    @Test
    @DisplayName("pay-in-4's instalment 1 collected at purchase is an ordinary repayment - reversible while ACTIVE")
    void theAtPurchaseCollectionIsReversible() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        BnplPurchaseStore.Repayment first = fakes.purchases.repayments.values().stream()
                .filter(row -> row.kind().equals(BnplPurchaseStore.Repayment.AT_PURCHASE)).findFirst().orElseThrow();
        BnplRepaymentReversal proposed = world.propose(BnplConditionWorld.PURCHASE_DATE, p4, first.id(), world.alice);
        world.approve(BnplConditionWorld.PURCHASE_DATE, p4, first.id(), proposed.id(), world.bob);
        assertThat(world.item(p4, 1).due(AllocationComponent.PRINCIPAL)).isEqualTo(eur(150_00));
    }

    // ------------------------------------------------------------------ the uniform 404 and the reason

    @Test
    @DisplayName("an unknown agreement, another agreement's repayment and another repayment's proposal are one NotFound;"
            + " a missing or card-bearing reason is ReasonRequired - nothing written")
    void unknownTargetsAndReasons() {
        Repaid repaid = twoRepayments();
        BnplConditionWorld.Agreement other = base.payIn4();
        assertThatThrownBy(() -> world.on(DUE_3, world.alice, () -> world.reversals.propose(BnplApplicationFakes.UOW,
                BnplAgreementId.of(BnplConditionWorld.IDS.next()), repaid.first().id(), "r", world.alice,
                BnplConditionWorld.CORRELATION))).isInstanceOf(BnplRepaymentReversals.NotFound.class);
        assertThatThrownBy(() -> world.propose(DUE_3, other, repaid.first().id(), world.alice))
                .isInstanceOf(BnplRepaymentReversals.NotFound.class);
        BnplRepaymentReversal proposed = world.propose(DUE_3, repaid.agreement(), repaid.first().id(), world.alice);
        assertThatThrownBy(() -> world.approve(DUE_3, repaid.agreement(), repaid.second().id(), proposed.id(),
                world.bob)).isInstanceOf(BnplRepaymentReversals.NotFound.class);
        assertThatThrownBy(() -> world.on(DUE_3, world.bob, () -> world.reversals.propose(BnplApplicationFakes.UOW,
                repaid.agreement().agreement(), repaid.second().id(), " ", world.bob, BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplReasons.ReasonRequired.class);
        assertThatThrownBy(() -> world.on(DUE_3, world.bob, () -> world.reversals.propose(BnplApplicationFakes.UOW,
                repaid.agreement().agreement(), repaid.second().id(), "card 4111 1111 1111 1111", world.bob,
                BnplConditionWorld.CORRELATION))).isInstanceOf(BnplReasons.ReasonRequired.class);
        assertThat(fakes.corrections.reversals).hasSize(1);
    }
}
