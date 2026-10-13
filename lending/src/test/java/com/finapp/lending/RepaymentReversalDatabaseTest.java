package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.BALANCES;
import static com.finapp.lending.DisbursementWorld.BILLING_STORE;
import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.CREDIT;
import static com.finapp.lending.DisbursementWorld.EUR;
import static com.finapp.lending.DisbursementWorld.HEADROOM;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LEDGER_ACCOUNTS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.REPAYMENT_STORE;
import static com.finapp.lending.DisbursementWorld.TERMS_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.awaitBlockedBy;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.employee;
import static com.finapp.lending.DisbursementWorld.execute;
import static com.finapp.lending.DisbursementWorld.fund;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.pid;
import static com.finapp.lending.DisbursementWorld.post;
import static com.finapp.lending.DisbursementWorld.race;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.DisbursementWorld.walletOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.advance;
import static com.finapp.lending.InstalmentBillingDatabaseTest.billings;
import static com.finapp.lending.InstalmentBillingDatabaseTest.daysTo;
import static com.finapp.lending.InstalmentBillingDatabaseTest.dues;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InterestAccrualDatabaseTest.backdate;
import static com.finapp.lending.InterestAccrualDatabaseTest.disbursed;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.RepaymentDatabaseTest.assertSubledgerProves;
import static com.finapp.lending.RepaymentDatabaseTest.billedLoan;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.events;
import static com.finapp.lending.RepaymentDatabaseTest.held;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.lock;
import static com.finapp.lending.RepaymentDatabaseTest.lockableNoWait;
import static com.finapp.lending.RepaymentDatabaseTest.loanStatus;
import static com.finapp.lending.RepaymentDatabaseTest.refusedAlone;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentDatabaseTest.topUp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.OverReversalException;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Repayment reversal under four eyes against a real database (`P11-TSK-020`; {@code lending V016}, {@code PHASE_11_PLAN.md}
 * sections 7.1 T-rev, 7.4, 12.5 and 12.8 rule 10, section 13.2 scenario 6; ADR-0099 points 1, 2 and 4;
 * {@code INV-LND-11}, {@code INV-REV-01}, {@code INV-REV-02}, {@code INV-AUD-04}, {@code INV-HIST-01}, {@code INV-CRD-09},
 * {@code INV-LND-13}): loans disbursed by the real T-dis, billed by the real T-svc and repaid by the real T-rep; the
 * reversal through the real {@link RepaymentReversals} over the real ledger and its {@link ReversalService}. Credit's
 * profile lock is the world's advisory stand-in; the acting party - whose party an operator is - is this suite's map
 * (identity's real read is {@code app}'s). Every case closes on the subledger proof's arms, reversals included.
 */
@Tag("database")
@DisplayName("repayment reversal under four eyes (P11-TSK-020)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class RepaymentReversalDatabaseTest {

    /** Operators the suite places in a party - an insider; every other operator is a party of their own. */
    static final Map<String, UUID> ACTING = new ConcurrentHashMap<>();

    /** Operators the platform cannot place in any party. */
    static final Set<String> UNPLACEABLE = ConcurrentHashMap.newKeySet();

    static final LoanActingParty ACTING_PARTY = (unitOfWork, actor) -> UNPLACEABLE.contains(actor.id())
            ? java.util.Optional.empty()
            : java.util.Optional.of(ACTING.getOrDefault(actor.id(),
                    UUID.nameUUIDFromBytes(("operator:" + actor.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8))));

    static final JdbcRepaymentReversalStore REVERSAL_STORE = new JdbcRepaymentReversalStore();

    static final ReversalService REVERSAL_SERVICE = new ReversalService(
            new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
            new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS,
            CLOCK, PostingObserver.NONE);

    static final RepaymentReversals REVERSALS = new RepaymentReversals(LOAN_STORE, REPAYMENT_STORE, REVERSAL_STORE,
            BILLING_STORE, TERMS_STORE, CREDIT, HEADROOM, LEDGER_ACCOUNTS, BALANCES, new JdbcJournalEntryStore(IDS),
            REVERSAL_SERVICE, ACTING_PARTY, new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);

    // ------------------------------------------------------------------ scenario 6

    @Test
    @DisplayName("scenario 6 - a reversal after allocation reopens what it paid: a repayment on the first bill, the second"
            + " bill, a later repayment over both, then the first reversed under four eyes - the entry swapped exactly"
            + " (reverses_entry_id the original), each of its allocations negated by a row, the later repayment's rows"
            + " untouched, the first bill due again by exactly what it paid, the wallet credited the amount; a repayment"
            + " after it allocates the re-opened amount oldest first; the subledger proof zero throughout")
    void aReversalAfterAllocationReopensWhatItPaid() throws Exception {
        LoanId loan = billedLoan(5_000_00);
        UUID party = partyOf(loan);
        long firstBill = billings(loan).get(0).billing().billed().minorUnits();
        Repayment first = repay(loan, key(), firstBill * 6 / 10).repayment();
        List<LocalDate> dues = dues(loan);
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1))));
        assertThat(service(loan).billed()).as("the second bill, after the first repayment").isEqualTo(1);
        long leftOfFirst = billings(loan).get(0).outstanding().minorUnits();
        Repayment later = repay(loan, key(), leftOfFirst + 100_00).repayment();
        assertThat(billings(loan).get(0).outstanding().isZero()).as("the later repayment finished the first bill").isTrue();
        long secondOutstanding = billings(loan).get(1).outstanding().minorUnits();
        String laterRows = allocationRows(later.id());
        long dueBefore = due(loan);
        long walletBefore = -netDebit(walletOf(party)).minorUnits();
        assertSubledgerProves(loan);

        Decided decided = proposeAndApprove(loan, first);
        RepaymentReversal approved = decided.reversal();
        assertThat(approved.status()).isEqualTo(RepaymentReversalStatus.APPROVED);
        UUID entry = approved.journalEntry().orElseThrow().value();

        assertThat(lines(entry)).as("rule 10: the original's lines, every direction swapped, exactly")
                .containsExactlyInAnyOrderElementsOf(swapped(lines(first.journalEntry().value())));
        assertThat(scalar("SELECT reverses_entry_id::text FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo(first.journalEntry().value().toString());
        assertThat(scalar("SELECT idempotency_scope FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo("ledger.reverse:" + RepaymentReversals.REVERSAL_KEY_PREFIX + approved.id());
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation_reversal n JOIN lending.repayment_allocation a"
                + " ON a.id = n.allocation_id WHERE a.repayment_id = ? AND n.amount_minor = a.amount_minor"
                + " AND n.reversal_id = ?", first.id(), approved.id()))
                .as("each allocation of the reversed repayment negated by a row of the same amount")
                .isEqualTo(first.allocations().size());
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation_reversal n JOIN lending.repayment_allocation a"
                + " ON a.id = n.allocation_id WHERE a.repayment_id = ?", later.id())).as("later allocations untouched")
                .isZero();
        assertThat(allocationRows(later.id())).as("the later repayment's rows, byte for byte").isEqualTo(laterRows);
        assertThat(billings(loan).get(0).outstanding().minorUnits()).as("the first bill due again by what it paid")
                .isEqualTo(first.amount().minorUnits());
        assertThat(billings(loan).get(1).outstanding().minorUnits()).as("the second bill untouched")
                .isEqualTo(secondOutstanding);
        assertThat(due(loan)).isEqualTo(dueBefore + first.amount().minorUnits());
        assertThat(-netDebit(walletOf(party)).minorUnits()).as("the wallet credited back the amount")
                .isEqualTo(walletBefore + first.amount().minorUnits());
        assertThat(events(loan, RepaymentReversals.REVERSED_EVENT)).isEqualTo(1);
        assertThat(audits(approved.id(), "lending.RepaymentReversalApproved")).isEqualTo(1);
        assertThat(reversed(first.id(), later.id())).as("REVERSED derived from the approved reversal")
                .containsExactly(first.id());
        assertSubledgerProves(loan);

        Repayment again = repay(loan, key(), first.amount().minorUnits()).repayment();
        long againOnFirstBill = again.allocations().stream()
                .filter(row -> row.line().item().map(item -> item.value().equals(billings(loan).get(0).billing().id()))
                        .orElse(false))
                .mapToLong(row -> row.line().amount().minorUnits()).sum();
        assertThat(againOnFirstBill).as("the re-opened amount allocated again, oldest first")
                .isEqualTo(first.amount().minorUnits());
        assertThat(billings(loan).get(0).outstanding().isZero()).isTrue();
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ four eyes and the races

    @Test
    @DisplayName("ten approvers, one reversal: ten different operators race to approve one proposal - one approves, nine"
            + " 409 ProposalNotPending; one entry, one event, one audit record, one negating row per allocation; the"
            + " winner's own retry converges and writes nothing")
    void tenApproversOneReversal() throws Exception {
        LoanId loan = billedLoan(5_050_00);
        Repayment repayment = repay(loan, key(), 200_00).repayment();
        fund(repayment.paid(AllocationComponent.PRINCIPAL));
        RepaymentReversal proposal = propose(loan, repayment.id(), employee());
        List<String> outcomes = race(10, index -> {
            try {
                return approve(loan, repayment.id(), proposal.id(), employee()).replayed() ? "REPLAYED" : "APPROVED";
            } catch (RepaymentReversals.ProposalNotPending decided) {
                return "NOT_PENDING";
            }
        });
        assertThat(outcomes).filteredOn("APPROVED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("NOT_PENDING"::equals).hasSize(9);
        assertReversedOnce(loan, repayment, proposal.id());
        String winner = scalar("SELECT decided_by FROM lending.repayment_reversal WHERE id = ?", proposal.id());
        Decided retry = approve(loan, repayment.id(), proposal.id(),
                new Actor(winner, com.finapp.platform.security.ActorType.EMPLOYEE));
        assertThat(retry.replayed()).as("the same approver's retry converges").isTrue();
        assertReversedOnce(loan, repayment, proposal.id());
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("one live proposal per repayment: ten proposers race - one PROPOSED, nine 409 ProposalPending; the"
            + " proposer withdraws it (REJECTED) and it may be proposed again; once approved the repayment is not"
            + " reversible again; a raw second live proposal is refused by the partial unique")
    void oneLiveProposalPerRepayment() throws Exception {
        LoanId loan = billedLoan(5_100_00);
        Repayment repayment = repay(loan, key(), 150_00).repayment();
        List<String> outcomes = race(10, index -> {
            try {
                propose(loan, repayment.id(), employee());
                return "PROPOSED";
            } catch (RepaymentReversals.ProposalPending pending) {
                return "PENDING";
            }
        });
        assertThat(outcomes).filteredOn("PROPOSED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("PENDING"::equals).hasSize(9);
        assertThat(count("SELECT count(*) FROM lending.repayment_reversal WHERE repayment_id = ?", repayment.id()))
                .isEqualTo(1);
        UUID first = UUID.fromString(scalar("SELECT id::text FROM lending.repayment_reversal WHERE repayment_id = ?",
                repayment.id()));
        Actor proposer = new Actor(scalar("SELECT proposed_by FROM lending.repayment_reversal WHERE id = ?", first),
                com.finapp.platform.security.ActorType.EMPLOYEE);
        Decided withdrawn = reject(loan, repayment.id(), first, proposer);
        assertThat(withdrawn.reversal().status()).isEqualTo(RepaymentReversalStatus.REJECTED);
        assertThat(audits(first, "lending.RepaymentReversalRejected")).isEqualTo(1);
        assertThatExceptionOfType(RepaymentReversals.ProposalNotPending.class)
                .isThrownBy(() -> approve(loan, repayment.id(), first, employee()));

        fund(repayment.paid(AllocationComponent.PRINCIPAL));
        RepaymentReversal second = propose(loan, repayment.id(), employee());
        approve(loan, repayment.id(), second.id(), employee());
        assertThatExceptionOfType(RepaymentReversals.RepaymentNotReversible.class)
                .isThrownBy(() -> propose(loan, repayment.id(), employee()));
        refusedAlone(rawProposal(loan, repayment.id(), "raw"), "23505", "repayment_reversal_one_live");
        assertThat(count("SELECT count(*) FROM lending.repayment_reversal WHERE repayment_id = ?", repayment.id()))
                .isEqualTo(2);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("self-approval refused at both ranks: the proposer's approval 403 SelfApprovalRefused, nothing written;"
            + " a raw self-approval - with every negating row - refused by the four-eyes CHECK, while the same raw"
            + " approval by another decider passes it (rolled back); reasons screened for instrument shapes by the domain"
            + " and by the database")
    void selfApprovalRefusedAtBothRanks() throws Exception {
        LoanId loan = billedLoan(5_150_00);
        Repayment repayment = repay(loan, key(), 180_00).repayment();
        Actor proposer = employee();
        RepaymentReversal proposal = propose(loan, repayment.id(), proposer);
        assertThatExceptionOfType(RepaymentReversals.SelfApprovalRefused.class)
                .isThrownBy(() -> approve(loan, repayment.id(), proposal.id(), proposer));
        assertThat(status(proposal.id())).isEqualTo("PROPOSED");
        assertThat(reversalEntries(proposal.id())).isZero();
        assertThat(audits(proposal.id(), "lending.RepaymentReversalApproved")).isZero();

        refusedAlone(rawApproval(proposal.id(), repayment.id(), "proposed_by", "COMMIT"), "23514",
                "repayment_reversal_four_eyes");
        try (Connection app = DatabaseRoles.application()) {
            execute(app, rawApproval(proposal.id(), repayment.id(), "'another-decider'", "ROLLBACK"));
        }
        assertThat(status(proposal.id())).isEqualTo("PROPOSED");

        assertThatExceptionOfType(LendingCapitalAdministration.ReasonRequired.class)
                .isThrownBy(() -> propose(loan, repayment.id(), employee(), "card 4111 1111 1111 1111 refund"));
        assertThatExceptionOfType(LendingCapitalAdministration.ReasonRequired.class)
                .isThrownBy(() -> propose(loan, repayment.id(), employee(), " "));
        refusedAlone("UPDATE lending.repayment_reversal SET status = 'REJECTED', decided_by = 'x', decided_by_type ="
                + " 'EMPLOYEE', decision_reason = 'to DE89 3704 0044 0532 0130 00' WHERE id = '" + proposal.id() + "'",
                "23514", "no_instrument_shape");
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("an employee never services their own party's loan: an insider's proposal, approval and rejection each"
            + " SelfDealingRefused before anything is written, the attempt recordable FAILED; an operator the platform"
            + " cannot place in a party is refused too (fail closed)")
    void anEmployeeNeverServicesTheirOwnPartysLoan() throws Exception {
        LoanId loan = billedLoan(5_200_00);
        Repayment repayment = repay(loan, key(), 160_00).repayment();
        Actor insider = employee();
        ACTING.put(insider.id(), partyOf(loan));
        RepaymentReversals.SelfDealingRefused refused = null;
        try {
            propose(loan, repayment.id(), insider);
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            refused = selfDealing;
        }
        assertThat(refused).as("the insider's proposal refused").isNotNull();
        RepaymentReversals.SelfDealingRefused attempt = refused;
        assertThat(count("SELECT count(*) FROM lending.repayment_reversal WHERE loan_id = ?", loan.value())).isZero();
        inOneTransaction(uow -> {
            REVERSALS.recordRefusal(uow, attempt, insider, correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = ? AND actor_id = ?", loan.value().toString(), insider.id()))
                .isEqualTo(1);

        RepaymentReversal proposal = propose(loan, repayment.id(), employee());
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> approve(loan, repayment.id(), proposal.id(), insider));
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> reject(loan, repayment.id(), proposal.id(), insider));
        Actor nobody = employee();
        UNPLACEABLE.add(nobody.id());
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class)
                .isThrownBy(() -> approve(loan, repayment.id(), proposal.id(), nobody));
        assertThat(status(proposal.id())).isEqualTo("PROPOSED");
        assertThat(reversalEntries(proposal.id())).isZero();
    }

    // ------------------------------------------------------------------ the closed loan (A12)

    @Test
    @DisplayName("a closing repayment is not reversible: on a loan closed by its last repayment, a proposal for the"
            + " closing repayment or an earlier one is 409 LoanClosed; a proposal standing from before the closure cannot"
            + " be approved (LoanClosed, still PROPOSED) and can be rejected; a raw proposal on the closed loan refused")
    void aClosingRepaymentIsNotReversible() throws Exception {
        LoanId loan = disbursed(3_000_00, 6);
        topUp(partyOf(loan), 500_00);
        List<LocalDate> dues = dues(loan);
        List<Repayment> paid = new ArrayList<>();
        RepaymentReversal standing = null;
        for (int j = 0; j < dues.size(); j++) {
            if (j == 0) {
                backdate(loan, daysTo(loan, 1));
            } else {
                advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(j - 1), dues.get(j))));
            }
            assertThat(service(loan).billed()).isEqualTo(1);
            long owed = billings(loan).get(j).outstanding().minorUnits();
            Repayments.Repaid repaid = repay(loan, key(), owed);
            paid.add(repaid.repayment());
            assertThat(repaid.closure().isPresent()).isEqualTo(j == dues.size() - 1);
            if (j == 0) {
                standing = propose(loan, repaid.repayment().id(), employee());
            }
        }
        assertThat(loanStatus(loan)).isEqualTo("CLOSED");
        Repayment closing = paid.get(paid.size() - 1);
        assertThatExceptionOfType(RepaymentReversals.LoanClosed.class)
                .isThrownBy(() -> propose(loan, closing.id(), employee()));
        assertThatExceptionOfType(RepaymentReversals.LoanClosed.class)
                .isThrownBy(() -> propose(loan, paid.get(1).id(), employee()));
        RepaymentReversal before = standing;
        assertThatExceptionOfType(RepaymentReversals.LoanClosed.class)
                .isThrownBy(() -> approve(loan, paid.get(0).id(), before.id(), employee()));
        assertThat(status(before.id())).isEqualTo("PROPOSED");
        assertThat(reject(loan, paid.get(0).id(), before.id(), employee()).reversal().status())
                .isEqualTo(RepaymentReversalStatus.REJECTED);
        refusedAlone(rawProposal(loan, closing.id(), "raw"), "P0001", "never reversed");
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation_reversal n JOIN lending.repayment_allocation a"
                + " ON a.id = n.allocation_id JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ?",
                loan.value())).isZero();
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ history and the bound

    @Test
    @DisplayName("the original is byte-identical after: the repayment's entry, its lines, the repayment row and its"
            + " allocation rows hash the same before and after the reversal; the credit it held debited back, the"
            + " credit balance zero again")
    void theOriginalIsByteIdenticalAfter() throws Exception {
        LoanId loan = billedLoan(5_250_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        Repayment repayment = repay(loan, key(), bill + 50_00).repayment();
        assertThat(held(loan)).isEqualTo(50_00);
        String before = original(repayment);
        proposeAndApprove(loan, repayment);
        assertThat(original(repayment)).as("INV-HIST-01: the original untouched").isEqualTo(before);
        assertThat(held(loan)).as("the held credit taken back exactly").isZero();
        assertThat(due(loan)).isEqualTo(bill);
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("the reversal is bounded by the original: a second reversal of the entry, whole or in part, refused by the"
            + " ledger's bound; the negating rows only for an approved reversal, mirroring an allocation exactly, once each;"
            + " an approval negating only some rows refused at commit; the proposal's identity and edges frozen; nothing"
            + " deleted or truncated")
    void theReversalIsBoundedByTheOriginal() throws Exception {
        LoanId loan = billedLoan(5_300_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        Repayment repayment = repay(loan, key(), bill).repayment();
        assertThat(repayment.allocations()).hasSizeGreaterThan(1);
        RepaymentReversal approved = proposeAndApprove(loan, repayment).reversal();
        List<JournalLine> whole = swappedLines(repayment);
        assertThatExceptionOfType(OverReversalException.class).isThrownBy(() -> reverseDirectly(repayment, whole));
        JournalLine walletCredit = whole.stream().filter(line -> line.direction() == Direction.CREDIT).findFirst()
                .orElseThrow();
        JournalLine dueDebit = whole.stream().filter(line -> line.direction() == Direction.DEBIT).findFirst()
                .orElseThrow();
        assertThatExceptionOfType(OverReversalException.class).as("even one minor unit more, in part")
                .isThrownBy(() -> reverseDirectly(repayment, List.of(
                        new JournalLine(walletCredit.account(), Direction.CREDIT, eur(1)),
                        new JournalLine(dueDebit.account(), Direction.DEBIT, eur(1)))));
        UUID allocation = repayment.allocations().get(0).id();
        long amount = repayment.allocations().get(0).line().amount().minorUnits();
        refusedAlone(rawNegation(approved.id(), allocation, amount), "23505", "allocation_unique");

        Repayment other = repay(loan, key(), 10_00).repayment();
        RepaymentReversal proposed = propose(loan, other.id(), employee());
        refusedAlone(rawNegation(proposed.id(), other.allocations().get(0).id(),
                other.allocations().get(0).line().amount().minorUnits()), "P0001", "only an APPROVED");
        Repayment third = repay(loan, key(), bill).repayment();
        RepaymentReversal partial = propose(loan, third.id(), employee());
        refusedAlone("BEGIN; UPDATE lending.repayment_reversal SET status = 'APPROVED', decided_by = 'another-decider',"
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw', journal_entry_id = gen_random_uuid() WHERE id = '"
                + partial.id() + "'; " + rawNegation(partial.id(), third.allocations().get(0).id(),
                        third.allocations().get(0).line().amount().minorUnits()) + "; COMMIT;", "P0001",
                "negates every allocation row");
        refusedAlone("BEGIN; UPDATE lending.repayment_reversal SET status = 'APPROVED', decided_by = 'another-decider',"
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw', journal_entry_id = gen_random_uuid() WHERE id = '"
                + partial.id() + "'; " + rawNegation(partial.id(), third.allocations().get(0).id(),
                        third.allocations().get(0).line().amount().minorUnits() + 1) + "; COMMIT;", "P0001", "mirrors");

        refusedAlone("UPDATE lending.repayment_reversal SET proposal_reason = 'edited' WHERE id = '" + approved.id() + "'",
                "42501", "");
        try (Connection migrator = DatabaseRoles.migrator()) {
            InterestAccrualDatabaseTest.refused(migrator, "UPDATE lending.repayment_reversal SET proposal_reason ="
                    + " 'edited' WHERE id = '" + approved.id() + "'", "P0001", "frozen");
        }
        refusedAlone("UPDATE lending.repayment_reversal SET status = 'REJECTED' WHERE id = '" + approved.id() + "'",
                "P0001", "not an edge");
        refusedAlone("INSERT INTO lending.repayment_reversal (id, loan_id, repayment_id, agreement_version, status,"
                + " proposed_by, proposed_by_type, proposed_at, proposal_reason, decided_by, decided_by_type, decided_at,"
                + " decision_reason, journal_entry_id, correlation_id) VALUES (gen_random_uuid(), '" + loan.value() + "', '"
                + third.id() + "', 1, 'APPROVED', 'a', 'EMPLOYEE', now(), 'raw', 'b', 'EMPLOYEE', now(), 'raw',"
                + " gen_random_uuid(), 'raw')", "P0001", "born PROPOSED");
        refusedAlone("DELETE FROM lending.repayment_reversal WHERE id = '" + approved.id() + "'", "42501", "");
        refusedAlone("UPDATE lending.repayment_allocation_reversal SET amount_minor = 1 WHERE reversal_id = '"
                + approved.id() + "'", "42501", "");
        try (Connection migrator = DatabaseRoles.migrator()) {
            InterestAccrualDatabaseTest.refused(migrator, "DELETE FROM lending.repayment_reversal WHERE id = '"
                    + approved.id() + "'", "P0001", "never deleted");
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            InterestAccrualDatabaseTest.refused(migrator, "DELETE FROM lending.repayment_allocation_reversal WHERE"
                    + " reversal_id = '" + approved.id() + "'", "P0001", "immutable");
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            InterestAccrualDatabaseTest.refused(migrator, "TRUNCATE lending.repayment_allocation_reversal", "P0001", "");
        }
        assertReversedOnce(loan, repayment, approved.id());
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ the lock order and the exposure

    @Test
    @DisplayName("the reversal takes the profile first (L0): behind a transaction holding the party's profile the approval"
            + " waits on it holding no loan row; behind a transaction holding the loan (L3) it waits there holding the"
            + " profile")
    void reversalTakesTheProfileFirst() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            LoanId loan = billedLoan(5_350_00);
            Repayment repayment = repay(loan, key(), 220_00).repayment();
            fund(repayment.paid(AllocationComponent.PRINCIPAL));
            RepaymentReversal proposal = propose(loan, repayment.id(), employee());
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                profileLock(holder, partyOf(loan));
                Future<Decided> approving = pool.submit(() -> approve(loan, repayment.id(), proposal.id(), employee()));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("the approval waits on the profile (L0)")
                        .contains("pg_advisory_xact_lock");
                assertThat(lockableNoWait("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", loan.value()))
                        .as("holding no loan row while it waits").isTrue();
                holder.commit();
                assertThat(approving.get(60, TimeUnit.SECONDS).reversal().status())
                        .isEqualTo(RepaymentReversalStatus.APPROVED);
            }

            LoanId second = billedLoan(5_360_00);
            Repayment other = repay(second, key(), 220_00).repayment();
            fund(other.paid(AllocationComponent.PRINCIPAL));
            RepaymentReversal next = propose(second, other.id(), employee());
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                lock(holder, "SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE", second.value());
                Future<Decided> approving = pool.submit(() -> approve(second, other.id(), next.id(), employee()));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("the approval waits on the loan (L3)")
                        .contains("lending.loan");
                assertThat(profileFree(partyOf(second))).as("holding the profile while it waits on the loan").isFalse();
                holder.commit();
                assertThat(approving.get(60, TimeUnit.SECONDS).reversal().status())
                        .isEqualTo(RepaymentReversalStatus.APPROVED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("exposure rises by the principal reopened: the census's input - principal outstanding over the loan's"
            + " journal lines - rises by exactly the principal the reversed repayment paid and capital headroom falls by"
            + " it; a reversal of interest alone moves neither, and takes no capital lock")
    void exposureRisesByThePrincipalReopened() throws Exception {
        LoanId loan = billedLoan(5_400_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        Repayment repayment = repay(loan, key(), bill).repayment();
        Money principal = repayment.paid(AllocationComponent.PRINCIPAL);
        assertThat(principal.isPositive()).isTrue();
        fund(principal);
        Money outstandingBefore = outstanding(loan);
        Money headroomBefore = headroomNow();
        proposeAndApprove(loan, repayment);
        assertThat(outstanding(loan)).as("INV-CRD-09: outstanding rises by the principal re-opened")
                .isEqualTo(outstandingBefore.plus(principal));
        assertThat(headroomNow()).as("INV-LND-13: the re-instated principal is deployed capital again")
                .isEqualTo(headroomBefore.minus(principal));

        LoanId interestOnly = billedLoan(5_410_00);
        long interest = billings(interestOnly).get(0).billing().interestDue().minorUnits();
        Repayment small = repay(interestOnly, key(), interest / 2).repayment();
        assertThat(small.paid(AllocationComponent.PRINCIPAL).isZero()).isTrue();
        RepaymentReversal proposal = propose(interestOnly, small.id(), employee());
        Money outstandingAtRest = outstanding(interestOnly);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            HEADROOM.underLock(holder, EUR);
            Money headroomHeld = HEADROOM.atRest(holder, EUR).headroom();
            Future<Decided> approving = pool.submit(() -> approve(interestOnly, small.id(), proposal.id(), employee()));
            assertThat(approving.get(60, TimeUnit.SECONDS).reversal().status()).as("no capital lock without principal")
                    .isEqualTo(RepaymentReversalStatus.APPROVED);
            holder.commit();
            assertThat(headroomNow()).isEqualTo(headroomHeld);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outstanding(interestOnly)).isEqualTo(outstandingAtRest);
        assertSubledgerProves(loan);
        assertSubledgerProves(interestOnly);
    }

    @Test
    @DisplayName("a reversal never over-deploys capital: behind a transaction holding the capital row a principal reversal"
            + " waits on it holding its loan; then three reversals and three acceptances race for exactly one's headroom -"
            + " one admitted, five 422 CapitalUnavailable, headroom never below zero, the refused proposals still PROPOSED")
    void aReversalNeverOverDeploysCapital() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            LoanId waiter = billedLoan(5_450_00);
            Repayment repaid = repay(waiter, key(), billings(waiter).get(0).billing().billed().minorUnits()).repayment();
            fund(repaid.paid(AllocationComponent.PRINCIPAL));
            RepaymentReversal waiting = propose(waiter, repaid.id(), employee());
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                HEADROOM.underLock(holder, EUR);
                Future<Decided> approving = pool.submit(() -> approve(waiter, repaid.id(), waiting.id(), employee()));
                assertThat(awaitBlockedBy(pid(holder)).query()).as("the approval waits on the capital row (L6)")
                        .contains("ledger.ledger_account");
                assertThat(lockableNoWait("SELECT 1 FROM lending.loan WHERE id = ? FOR UPDATE NOWAIT", waiter.value()))
                        .as("holding its loan (L3) while it waits on L6").isFalse();
                holder.commit();
                assertThat(approving.get(60, TimeUnit.SECONDS).reversal().status())
                        .isEqualTo(RepaymentReversalStatus.APPROVED);
            }
        } finally {
            pool.shutdownNow();
        }

        List<LoanId> loans = new ArrayList<>();
        List<Repayment> repayments = new ArrayList<>();
        List<RepaymentReversal> proposals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LoanId loan = billedLoan(20_000_00);
            Repayment repayment = repay(loan, key(), billings(loan).get(0).billing().billed().minorUnits()).repayment();
            loans.add(loan);
            repayments.add(repayment);
            proposals.add(propose(loan, repayment.id(), employee()));
        }
        long last = repayments.stream().mapToLong(r -> r.paid(AllocationComponent.PRINCIPAL).minorUnits()).min()
                .orElseThrow();
        List<CreditLineDrawDatabaseTest.Offered> offers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            offers.add(CreditLineDrawDatabaseTest.offeredLoan(last));
        }
        // Exactly one act's headroom left: a surplus deployed by a filler line's draw, a shortfall planted.
        long surplus = headroomNow().minorUnits() - last;
        if (surplus > 0) {
            DisbursementWorld.Accepted filler = acceptedLine(surplus);
            CreditLineDrawDatabaseTest.draw(filler, key(), surplus);
        } else if (surplus < 0) {
            plantCapital(-surplus);
        }
        assertThat(headroomNow().minorUnits()).isEqualTo(last);
        List<String> outcomes = race(6, index -> {
            try {
                if (index % 2 == 0) {
                    approve(loans.get(index / 2), repayments.get(index / 2).id(), proposals.get(index / 2).id(),
                            employee());
                } else {
                    CreditLineDrawDatabaseTest.accept(offers.get(index / 2));
                }
                return "ADMITTED";
            } catch (RepaymentReversals.CapitalUnavailable | LoanAcceptances.CapitalUnavailable short_) {
                return "CAPITAL_UNAVAILABLE";
            }
        });
        assertThat(outcomes).as("exactly one act takes the last capital").filteredOn("ADMITTED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("CAPITAL_UNAVAILABLE"::equals).hasSize(5);
        assertThat(headroomNow().minorUnits()).as("INV-LND-13: never over-deployed").isGreaterThanOrEqualTo(0);
        long approvedCount = proposals.stream().filter(p -> status(p.id()).equals("APPROVED")).count();
        assertThat(proposals.stream().filter(p -> status(p.id()).equals("PROPOSED")).count())
                .as("a refused approval leaves its proposal PROPOSED").isEqualTo(3 - approvedCount);
        for (LoanId loan : loans) {
            assertSubledgerProves(loan);
        }
    }

    // ------------------------------------------------------------------ the refusals that keep the proposal

    @Test
    @DisplayName("a repayment whose held credit was applied is not reversible: proposed while held, the next billing's"
            + " credit-balance application spends it, and the approval is 409 RepaymentNotReversible - nothing posted, the"
            + " proposal still PROPOSED; a new proposal refused alike; later allocations never re-cut")
    void aReversalWhoseHeldCreditWasAppliedIsRefused() throws Exception {
        LoanId loan = billedLoan(5_500_00);
        long bill = billings(loan).get(0).billing().billed().minorUnits();
        Repayment repayment = repay(loan, key(), bill + 300_00).repayment();
        RepaymentReversal proposal = propose(loan, repayment.id(), employee());
        List<LocalDate> dues = dues(loan);
        advance(loan, Math.toIntExact(ChronoUnit.DAYS.between(dues.get(0), dues.get(1))));
        assertThat(service(loan).billed()).isEqualTo(1);
        assertThat(held(loan)).as("the second bill's application spent the credit").isLessThan(300_00);
        fund(repayment.paid(AllocationComponent.PRINCIPAL));
        assertThatExceptionOfType(RepaymentReversals.RepaymentNotReversible.class)
                .isThrownBy(() -> approve(loan, repayment.id(), proposal.id(), employee()));
        assertThat(status(proposal.id())).isEqualTo("PROPOSED");
        assertThat(reversalEntries(proposal.id())).isZero();
        reject(loan, repayment.id(), proposal.id(), employee());
        assertThatExceptionOfType(RepaymentReversals.RepaymentNotReversible.class)
                .isThrownBy(() -> propose(loan, repayment.id(), employee()));
        assertSubledgerProves(loan);
    }

    @Test
    @DisplayName("a wallet that cannot be credited refuses the approval: the wallet's posting suspended - 409"
            + " WalletUnavailable, nothing posted, the proposal still PROPOSED; once it accepts postings again the same"
            + " proposal is approved")
    void aWalletThatCannotBeCreditedRefusesTheApproval() throws Exception {
        LoanId loan = billedLoan(5_550_00);
        Repayment repayment = repay(loan, key(), 140_00).repayment();
        fund(repayment.paid(AllocationComponent.PRINCIPAL));
        RepaymentReversal proposal = propose(loan, repayment.id(), employee());
        LedgerAccountId wallet = walletOf(partyOf(loan));
        asOwner("UPDATE ledger.ledger_account SET status = 'POSTING_SUSPENDED' WHERE id = '" + wallet.value() + "'");
        try {
            assertThatExceptionOfType(RepaymentReversals.WalletUnavailable.class)
                    .isThrownBy(() -> approve(loan, repayment.id(), proposal.id(), employee()));
            assertThat(status(proposal.id())).isEqualTo("PROPOSED");
            assertThat(reversalEntries(proposal.id())).isZero();
        } finally {
            asOwner("UPDATE ledger.ledger_account SET status = 'ACTIVE' WHERE id = '" + wallet.value() + "'");
        }
        assertThat(approve(loan, repayment.id(), proposal.id(), employee()).reversal().status())
                .isEqualTo(RepaymentReversalStatus.APPROVED);
        assertReversedOnce(loan, repayment, proposal.id());
        assertSubledgerProves(loan);
    }

    // ------------------------------------------------------------------ the world's door

    record Decided(RepaymentReversal reversal, boolean replayed) {}

    static Decided proposeAndApprove(LoanId loan, Repayment repayment) {
        fund(repayment.paid(AllocationComponent.PRINCIPAL).plus(repayment.to(AllocationLine.Target.NOT_DUE_PRINCIPAL)));
        RepaymentReversal proposal = propose(loan, repayment.id(), employee());
        return approve(loan, repayment.id(), proposal.id(), employee());
    }

    static RepaymentReversal propose(LoanId loan, UUID repayment, Actor actor) {
        return propose(loan, repayment, actor, "the operator applied the customer's transfer twice");
    }

    static RepaymentReversal propose(LoanId loan, UUID repayment, Actor actor, String reason) {
        return as(actor, correlation -> inOneTransaction(uow ->
                REVERSALS.propose(uow, loan, repayment, reason, actor, correlation)));
    }

    static Decided approve(LoanId loan, UUID repayment, UUID reversal, Actor actor) {
        RepaymentReversals.Decided decided = as(actor, correlation -> inOneTransaction(uow ->
                REVERSALS.approve(uow, loan, repayment, reversal, "checked against the bank statement", actor,
                        correlation)));
        return new Decided(decided.reversal(), decided.replayed());
    }

    static Decided reject(LoanId loan, UUID repayment, UUID reversal, Actor actor) {
        RepaymentReversals.Decided decided = as(actor, correlation -> inOneTransaction(uow ->
                REVERSALS.reject(uow, loan, repayment, reversal, "not a mistake after all", actor, correlation)));
        return new Decided(decided.reversal(), decided.replayed());
    }

    /** {@code work} inside {@code actor}'s security scope and a fresh correlation scope - the door's. */
    static <R> R as(Actor actor, Function<CorrelationId, R> work) {
        CorrelationId correlation = correlation();
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return work.apply(correlation);
        }
    }

    /** A second reversal of the repayment's entry under another key, straight through the ledger. */
    private static void reverseDirectly(Repayment repayment, List<JournalLine> lines) {
        Actor actor = employee();
        LocalDate today = DisbursementWorld.businessDate(); // the database clock's, never the instance's (P11-TSK-032)
        as(actor, correlation -> inOneTransaction(uow -> REVERSAL_SERVICE.reverse(uow, new ReversalCommand(
                "p11t20-probe:" + UUID.randomUUID(), repayment.journalEntry(), today, today, "probe", lines))));
    }

    // ------------------------------------------------------------------ the assertions

    /** One approved reversal of the repayment: one entry, one event per reversal, one audit record, every row negated. */
    private static void assertReversedOnce(LoanId loan, Repayment repayment, UUID reversal) {
        assertThat(status(reversal)).isEqualTo("APPROVED");
        assertThat(reversalEntries(reversal)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reverses_entry_id = ?",
                repayment.journalEntry().value())).as("the original reversed once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE ?", loan.value(), RepaymentReversals.REVERSED_EVENT,
                "%" + reversal + "%")).isEqualTo(1);
        assertThat(audits(reversal, "lending.RepaymentReversalApproved")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.repayment_allocation_reversal WHERE reversal_id = ?", reversal))
                .isEqualTo(repayment.allocations().size());
    }

    // ------------------------------------------------------------------ plumbing

    private static String status(UUID reversal) {
        return scalar("SELECT status FROM lending.repayment_reversal WHERE id = ?", reversal);
    }

    private static long reversalEntries(UUID reversal) {
        return count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.reverse:" + RepaymentReversals.REVERSAL_KEY_PREFIX + reversal);
    }

    private static long audits(UUID reversal, String action) {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?", action,
                reversal.toString());
    }

    private static Set<UUID> reversed(UUID... repayments) {
        return inOneTransaction(uow -> REVERSAL_STORE.reversedAmong(uow, List.of(repayments)));
    }

    /** An entry's lines as account:direction:amount. */
    private static List<String> lines(UUID entry) {
        List<String> lines = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT ledger_account_id, direction, amount_minor FROM ledger.journal_line WHERE entry_id = ?")) {
            select.setObject(1, entry);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1) + ":" + row.getString(2) + ":" + row.getLong(3));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return lines;
    }

    private static List<String> swapped(List<String> lines) {
        return lines.stream().map(line -> line.contains(":DEBIT:") ? line.replace(":DEBIT:", ":CREDIT:")
                : line.replace(":CREDIT:", ":DEBIT:")).toList();
    }

    private static List<JournalLine> swappedLines(Repayment repayment) {
        List<JournalLine> lines = new ArrayList<>();
        for (String line : lines(repayment.journalEntry().value())) {
            String[] parts = line.split(":");
            lines.add(new JournalLine(LedgerAccountId.of(UUID.fromString(parts[0])),
                    Direction.valueOf(parts[1]).opposite(), eur(Long.parseLong(parts[2]))));
        }
        return lines;
    }

    private static String allocationRows(UUID repayment) {
        return scalar("SELECT string_agg(row_to_json(a)::text, '|' ORDER BY a.ordinal) FROM lending.repayment_allocation a"
                + " WHERE a.repayment_id = ?", repayment);
    }

    /** The repayment's entry, its lines, the repayment row and its allocation rows, as the database holds them. */
    private static String original(Repayment repayment) {
        UUID entry = repayment.journalEntry().value();
        return scalar("SELECT string_agg(t, '|' ORDER BY t) FROM ("
                + " SELECT row_to_json(e)::text AS t FROM ledger.journal_entry e WHERE e.id = ?"
                + " UNION ALL SELECT row_to_json(l)::text FROM ledger.journal_line l WHERE l.entry_id = ?"
                + " UNION ALL SELECT row_to_json(r)::text FROM lending.repayment r WHERE r.id = ?"
                + " UNION ALL SELECT row_to_json(a)::text FROM lending.repayment_allocation a WHERE a.repayment_id = ?) s",
                entry, entry, repayment.id(), repayment.id());
    }

    /** Principal outstanding - LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE over journal lines: the exposure census's input. */
    private static Money outstanding(LoanId loan) {
        return netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL))
                .plus(netDebit(loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL_DUE)));
    }

    private static Money headroomNow() {
        return inOneTransaction(uow -> HEADROOM.atRest(uow, EUR)).headroom();
    }

    private static void plantCapital(long minor) {
        post("p11t20-capital:" + UUID.randomUUID(), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, eur(minor)),
                new JournalLine(inOneTransaction(uow -> HEADROOM.capitalAccount(uow, EUR).id()), Direction.CREDIT,
                        eur(minor))));
    }

    /** Credit's profile lock's stand-in, held by {@code holder} (the world's namespace). */
    private static void profileLock(Connection holder, UUID party) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, DisbursementWorld.Credit.PROFILE_STAND_IN);
            lock.setString(2, party.toString());
            lock.executeQuery().close();
        }
    }

    private static boolean profileFree(UUID party) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement("SELECT pg_try_advisory_xact_lock(?, hashtext(?))")) {
                lock.setInt(1, DisbursementWorld.Credit.PROFILE_STAND_IN);
                lock.setString(2, party.toString());
                try (ResultSet row = lock.executeQuery()) {
                    row.next();
                    return row.getBoolean(1);
                }
            } finally {
                probe.rollback();
            }
        }
    }

    /** An application-role proposal for {@code repayment} as raw SQL. */
    static String rawProposal(LoanId loan, UUID repayment, String reason) {
        return "INSERT INTO lending.repayment_reversal (id, loan_id, repayment_id, agreement_version, status, proposed_by,"
                + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES (gen_random_uuid(), '"
                + loan.value() + "', '" + repayment + "', 1, 'PROPOSED', 'raw-proposer', 'EMPLOYEE', now(), '" + reason
                + "', 'p11t20-raw')";
    }

    /**
     * An application-role approval of {@code reversal} by {@code decider} (a column or a literal) as raw SQL, with every
     * negating row, ended by {@code end} ({@code COMMIT} or {@code ROLLBACK}).
     */
    static String rawApproval(UUID reversal, UUID repayment, String decider, String end) {
        return "BEGIN; UPDATE lending.repayment_reversal SET status = 'APPROVED', decided_by = " + decider + ","
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw', journal_entry_id = gen_random_uuid() WHERE id = '"
                + reversal + "'; INSERT INTO lending.repayment_allocation_reversal (id, reversal_id, allocation_id,"
                + " currency, scale, amount_minor) SELECT gen_random_uuid(), '" + reversal + "', a.id, a.currency, a.scale,"
                + " a.amount_minor FROM lending.repayment_allocation a WHERE a.repayment_id = '" + repayment + "'; " + end
                + ";";
    }

    static String rawNegation(UUID reversal, UUID allocation, long amount) {
        return "INSERT INTO lending.repayment_allocation_reversal (id, reversal_id, allocation_id, currency, scale,"
                + " amount_minor) VALUES (gen_random_uuid(), '" + reversal + "', '" + allocation + "', 'EUR', 2, " + amount
                + ")";
    }
}
