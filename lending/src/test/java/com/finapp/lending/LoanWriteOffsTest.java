package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.DueItemId;
import com.finapp.creditmath.DueState;
import com.finapp.creditmath.EngineVersion;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A defaulted loan written off under four eyes (`P14-TSK-021`; owner decision C6 (A); ADR-0096 rule 14; {@code INV-LND-01},
 * {@code INV-AUD-04}, {@code INV-REV-04}, {@code INV-BAL-03}), hermetic: the real {@link LoanWriteOffs}, the real {@link PostingService}
 * and {@link DelinquencyState} over a world in memory - {@code lending V032} and the ledger's lines. Every refusal asserts nothing
 * posted, and the proposal standing. The races, the migration's clauses and the door over HTTP are {@code app}'s
 * {@code LoanWriteOffDatabaseTest}, owed.
 */
@DisplayName("a loan written off under four eyes (P14-TSK-021)")
class LoanWriteOffsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(LoanWriteOffsTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final CorrelationId CORRELATION = CorrelationId.of("write-off-test");
    private static final UUID BILLED_ITEM = IDS.next();

    private final World world = new World();
    private final Actor proposer = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private final Actor approver = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private CorrelationContext.Scope correlationScope;
    private SecurityContext.Scope actorScope;

    @BeforeEach
    void scopes() {
        world.parties.put(proposer.id(), UUID.randomUUID());
        world.parties.put(approver.id(), UUID.randomUUID());
        correlationScope = CorrelationContext.enter(Correlation.startingWith(CORRELATION).causing(
                com.finapp.sharedkernel.correlation.CausationId.of(CORRELATION.value())));
        actorScope = SecurityContext.enter(approver);
    }

    @AfterEach
    void closeScopes() {
        actorScope.close();
        correlationScope.close();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("the whole of each receivable to expense, each to zero, one balanced entry dated today by the server; WRITTEN_OFF and never"
            + " closed; ids and codes in the event; both acts audited")
    void aDefaultedLoanIsWrittenOffWhole() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        LoanWriteOffPosition read = proposed.writeOff().position();
        assertThat(read.principal()).isEqualTo(eur(700_00));
        assertThat(read.principalDue()).isEqualTo(eur(100_00));
        assertThat(read.interestAccrued()).isEqualTo(eur(3_00));
        assertThat(read.interestDue()).isEqualTo(eur(20_00));
        assertThat(read.feesDue()).isEqualTo(eur(15_00));
        assertThat(read.total()).isEqualTo(eur(838_00));
        assertThat(read.principalTotal()).isEqualTo(eur(800_00));
        assertThat(world.appended).as("a proposal posts nothing").isEmpty();

        LoanWriteOffs.Decided decided = approve(proposed.writeOff().id(), read);

        assertThat(decided.writeOff().status()).isEqualTo(LoanWriteOffStatus.APPROVED);
        assertThat(decided.writeOff().valueDate()).contains(TODAY);
        JournalEntry entry = world.appended.get(0);
        assertThat(entry.postingDate()).isEqualTo(TODAY);
        assertThat(entry.valueDate()).as("the value date too: today, by the server").isEqualTo(TODAY);
        assertThat(entry.lines()).hasSize(6);
        assertThat(entry.lines().get(0).account()).isEqualTo(world.expense);
        assertThat(entry.lines().get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(entry.lines().get(0).amount()).isEqualTo(eur(838_00));
        assertThat(world.balance(AccountPurpose.LOAN_PRINCIPAL)).isEqualTo(eur(0));
        assertThat(world.balance(AccountPurpose.LOAN_PRINCIPAL_DUE)).isEqualTo(eur(0));
        assertThat(world.balance(AccountPurpose.LOAN_INTEREST_ACCRUED)).isEqualTo(eur(0));
        assertThat(world.balance(AccountPurpose.LOAN_INTEREST_DUE)).isEqualTo(eur(0));
        assertThat(world.balance(AccountPurpose.LOAN_FEES_DUE)).isEqualTo(eur(0));

        assertThat(world.status).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(world.history).containsExactly("ACTIVE->WRITTEN_OFF WRITTEN_OFF " + approver.id());
        assertThat(world.events).singleElement().isEqualTo(LoanWriteOffs.WRITTEN_OFF_EVENT);
        assertThat(world.payloads.get(0)).doesNotContain("amount", "Minor", "reason", "party", "uncollectable");
        assertThat(world.audited).extracting(record -> record.operation().code())
                .contains("lending.WriteOffProposed", "lending.WriteOffApproved");
        AuditRecord approved = world.audited.stream().filter(record -> record.operation() == LendingAuditAction.WRITE_OFF_APPROVED)
                .findFirst().orElseThrow();
        assertThat(approved.reason()).contains("read and agreed");
    }

    @Test
    @DisplayName("a zero receivable takes no line: never partial, never a line for nothing, still balanced")
    void aZeroReceivableTakesNoLine() {
        world.defaulted();
        world.reset(AccountPurpose.LOAN_FEES_DUE);
        world.dueFees = 0;
        LoanWriteOffs.Proposed proposed = propose();
        approve(proposed.writeOff().id(), proposed.writeOff().position());
        assertThat(world.appended.get(0).lines()).hasSize(5);
    }

    @Test
    @DisplayName("a line going CLOSING is written off from CLOSING")
    void aClosingLineIsWrittenOff() {
        world.defaulted();
        world.status = LoanStatus.CLOSING;
        LoanWriteOffs.Proposed proposed = propose();
        approve(proposed.writeOff().id(), proposed.writeOff().position());
        assertThat(world.status).isEqualTo(LoanStatus.WRITTEN_OFF);
    }

    // ------------------------------------------------------------------ four eyes

    @Test
    @DisplayName("the proposer cannot approve: refused, nothing posted, the proposal standing")
    void theProposerCannotApprove() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        assertThatThrownBy(() -> world.writeOffs.approve(UOW, world.id, proposed.writeOff().id(), proposed.writeOff().position(), "mine",
                proposer, CORRELATION)).isInstanceOf(LoanWriteOffs.SelfApprovalRefused.class);
        assertThat(world.appended).isEmpty();
        assertThat(world.status).isEqualTo(LoanStatus.ACTIVE);
        assertThat(world.rows.get(proposed.writeOff().id()).status()).isEqualTo(LoanWriteOffStatus.PROPOSED);
    }

    @Test
    @DisplayName("an operator of the borrower's own party is refused, before anything is written")
    void selfDealingIsRefused() {
        world.defaulted();
        world.parties.put(proposer.id(), world.party);
        assertThatThrownBy(this::propose).isInstanceOf(RepaymentReversals.SelfDealingRefused.class);
        assertThat(world.rows).isEmpty();
    }

    @Test
    @DisplayName("a reason is required, and nothing a generated toString would print reaches a log line")
    void aReasonIsRequiredAndNeverPrinted() {
        world.defaulted();
        assertThatThrownBy(() -> world.writeOffs.propose(UOW, world.id, " ", proposer, CORRELATION))
                .isInstanceOf(LendingCapitalAdministration.ReasonRequired.class);
        LoanWriteOff row = propose().writeOff();
        assertThat(row.toString()).doesNotContain(row.proposalReason(), row.proposedBy(), "700");
        assertThat(row.position().toString()).doesNotContain("70000", "700.00");
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("not defaulted is refused at the proposal: never recorded, or recorded and cleared")
    void aNonDefaultedLoanIsRefused() {
        world.defaulted();
        world.defaultFlag = false;
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.NotDefaulted.class);
        world.latestDefault = Optional.empty();
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.NotDefaulted.class);
        assertThat(world.rows).isEmpty();
    }

    @Test
    @DisplayName("cured since the flag was recorded: the condition derived now refuses it, before any servicing step records the cure, at the"
            + " proposal and again under the lock")
    void aCureIsRefusedUnderTheLock() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        world.payEverythingDue();
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(LoanWriteOffs.NotDefaulted.class);
        assertThat(world.appended).isEmpty();
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.NotDefaulted.class);
    }

    @Test
    @DisplayName("not ACTIVE or CLOSING is refused, and an account already written off answers its own refusal")
    void notInServicingIsRefused() {
        world.defaulted();
        world.status = LoanStatus.CLOSED;
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.NotInServicing.class);
        world.status = LoanStatus.WRITTEN_OFF;
        assertThatThrownBy(this::propose).isInstanceOf(LoanWrittenOffRefused.class);
    }

    @Test
    @DisplayName("anything moved since the proposal is stale, never silently re-read: an accrual, a late fee, a repayment and its reversal -"
            + " the last leaving the figures where they were and the revision higher")
    void anyMoveIsStale() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        UUID id = proposed.writeOff().id();
        LoanWriteOffPosition read = proposed.writeOff().position();

        // A repayment and its reversal round-trip every figure; the revision does not round-trip.
        world.post(AccountPurpose.LOAN_FEES_DUE, Direction.CREDIT, eur(1_00), TODAY);
        world.post(AccountPurpose.LOAN_FEES_DUE, Direction.DEBIT, eur(1_00), TODAY);
        assertThat(world.balance(AccountPurpose.LOAN_FEES_DUE)).isEqualTo(eur(15_00));
        assertThatThrownBy(() -> approve(id, read)).isInstanceOf(LoanWriteOffs.Stale.class);
        assertThat(world.appended).isEmpty();
        assertThat(world.status).isEqualTo(LoanStatus.ACTIVE);
        assertThat(world.rows.get(id).status()).as("the proposal stands").isEqualTo(LoanWriteOffStatus.PROPOSED);
    }

    @Test
    @DisplayName("a figure or a revision the approver names that is not the proposal's is stale: what they read is what they attest")
    void anotherFigureNamedIsStale() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        LoanWriteOffPosition read = proposed.writeOff().position();
        LoanWriteOffPosition lower = new LoanWriteOffPosition(read.principal().minus(eur(1)), read.principalDue(), read.interestAccrued(),
                read.interestDue(), read.feesDue(), read.revision());
        LoanWriteOffPosition otherRevision = new LoanWriteOffPosition(read.principal(), read.principalDue(), read.interestAccrued(),
                read.interestDue(), read.feesDue(), read.revision() + 1);
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), lower)).isInstanceOf(LoanWriteOffs.Stale.class);
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), otherRevision)).isInstanceOf(LoanWriteOffs.Stale.class);
    }

    @Test
    @DisplayName("cured and defaulted again between the two acts: the status, the figures and the revision read the same, and it is stale"
            + " because the DEFAULT change is another - compared by what cannot round-trip")
    void aDefaultCuredAndSetAgainIsStale() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        world.latestDefault = Optional.of(world.defaultChange(TODAY));
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(LoanWriteOffs.Stale.class);
        assertThat(world.appended).isEmpty();
    }

    @Test
    @DisplayName("a credit balance is the customer's money and is never written off: refused until its servicing step applies it")
    void aCreditBalanceIsRefused() {
        world.defaulted();
        world.post(AccountPurpose.LOAN_CREDIT_BALANCE, Direction.CREDIT, eur(1_00), TODAY);
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.CreditHeld.class);
        assertThat(world.rows).isEmpty();
    }

    @Test
    @DisplayName("an elapsed date not accrued is refused: no interest earned is forgone and ACCRUALS_COMPLETE sees no gap ever after; nor a"
            + " date the record has closed, on a clock stepped back behind it")
    void behindAccrualIsRefused() {
        world.defaulted();
        world.accrualStart = TODAY.minusDays(5);
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.BehindAccrual.class);
        world.accrualStart = TODAY;
        world.valueDateFloor = TODAY.plusDays(1);
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.BehindAccrual.class);
    }

    @Test
    @DisplayName("rows that do not explain the due accounts refuse it: nothing is written off over a difference (INV-LND-01)")
    void aDifferenceIsNeverWrittenOffOver() {
        world.defaulted();
        world.duePrincipal = 1;
        assertThatThrownBy(this::propose).isInstanceOf(AllocationAnomaly.class);
    }

    // ------------------------------------------------------------------ one decision

    @Test
    @DisplayName("one live proposal per loan; decided once: the same approver's retry converges, another approver and a rejection find it"
            + " decided, and one entry stands")
    void decidedOnce() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        assertThatThrownBy(this::propose).isInstanceOf(LoanWriteOffs.ProposalPending.class);
        UUID id = proposed.writeOff().id();
        LoanWriteOffPosition read = proposed.writeOff().position();
        LoanWriteOffs.Decided first = approve(id, read);
        LoanWriteOffs.Decided again = approve(id, read);
        assertThat(again.replayed()).isTrue();
        assertThat(again.writeOff().journalEntry()).isEqualTo(first.writeOff().journalEntry());
        assertThat(world.appended).hasSize(1);

        Actor third = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        world.parties.put(third.id(), UUID.randomUUID());
        assertThatThrownBy(() -> world.writeOffs.approve(UOW, world.id, id, read, "me too", third, CORRELATION))
                .isInstanceOf(LoanWriteOffs.ProposalNotPending.class);
        assertThatThrownBy(() -> world.writeOffs.reject(UOW, world.id, id, "no", third, CORRELATION))
                .isInstanceOf(LoanWriteOffs.ProposalNotPending.class);
    }

    @Test
    @DisplayName("the customer's read after it names what stands owed, never zero and never closed: the ledger's accounts are empty, the figures"
            + " are the row's, and no condition is derived out of servicing")
    void theCustomersReadNamesWhatStandsOwed() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        LoanWriteOffPosition position = proposed.writeOff().position();
        approve(proposed.writeOff().id(), position);
        Loans.Read read = world.customersRead().read(UOW, world.id, world.party).orElseThrow();
        assertThat(read.balances().principalOutstanding()).isEqualTo(position.principalTotal());
        assertThat(read.balances().interestAccrued()).isEqualTo(position.interestAccrued());
        assertThat(read.balances().interestDue()).isEqualTo(position.interestDue());
        assertThat(read.balances().feesDue()).isEqualTo(position.feesDue());
        assertThat(read.balances().principalOutstanding().isPositive()).isTrue();
        assertThat(read.owned().loan().status()).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(read.owned().loan().closedAt()).isEmpty();
        assertThat(read.condition()).isEmpty();
    }

    @Test
    @DisplayName("after it a repayment is refused at its door under L3, nothing posted: what is owed is unchanged and the money stays in the"
            + " wallet; a key already born replays first")
    void aRepaymentAfterItIsRefused() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        approve(proposed.writeOff().id(), proposed.writeOff().position());
        int entries = world.appended.size();
        Repayments door = world.repaymentDoor();
        Actor customer = new Actor(world.party.toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> door.repay(UOW, world.id, world.party, new Repayments.Request("key-after", eur(1_00)), customer, CORRELATION))
                .isInstanceOf(LoanWrittenOffRefused.class);
        assertThat(world.appended).hasSize(entries);
    }

    @Test
    @DisplayName("the rejection is the door out, on any account status: a proposal that can no longer post can always be cleared, and a new"
            + " one can follow it; approving it afterwards finds it decided")
    void rejectionIsTheDoorOut() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        world.post(AccountPurpose.LOAN_FEES_DUE, Direction.DEBIT, eur(1_00), TODAY);
        world.dueFees = 16_00;
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), proposed.writeOff().position())).isInstanceOf(LoanWriteOffs.Stale.class);
        LoanWriteOffs.Decided rejected = world.writeOffs.reject(UOW, world.id, proposed.writeOff().id(), "it moved", proposer, CORRELATION);
        assertThat(rejected.writeOff().status()).isEqualTo(LoanWriteOffStatus.REJECTED);
        assertThat(world.audited).extracting(record -> record.operation().code()).contains("lending.WriteOffRejected");
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(LoanWriteOffs.ProposalNotPending.class);

        LoanWriteOffs.Proposed again = propose();
        world.status = LoanStatus.CLOSED;
        assertThat(world.writeOffs.reject(UOW, world.id, again.writeOff().id(), "closed meanwhile", approver, CORRELATION).writeOff().status())
                .as("the door stays open on a closed account").isEqualTo(LoanWriteOffStatus.REJECTED);
    }

    @Test
    @DisplayName("a lock on today's month refuses the approval and moves it nowhere: the ledger's refusal passes through and no date is chosen"
            + " for it - C3's floor cannot touch a person's act")
    void aLockedMonthRefusesAndMovesNothing() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        world.cutoffCovers = true;
        assertThatThrownBy(() -> approve(proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(PeriodClosedException.class);
        assertThat(world.status).isEqualTo(LoanStatus.ACTIVE);
        assertThat(world.rows.get(proposed.writeOff().id()).status()).isEqualTo(LoanWriteOffStatus.PROPOSED);
        assertThat(world.attemptedDating).containsExactly("AS_STATED");
    }

    // ------------------------------------------------------------------ the approver's reads

    @Test
    @DisplayName("the approver reads what they attest: the figures, the revision, the default's date, what the account holds now, the date it"
            + " posts on - and that it no longer stands once anything moved")
    void theApproverReadsWhatTheyAttest() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        LoanWriteOffs.Attestation stands = world.writeOffs.read(UOW, world.id, proposed.writeOff().id(), approver, CORRELATION);
        assertThat(stands.blockers()).isEmpty();
        assertThat(stands.live()).isEqualTo(proposed.writeOff().position());
        assertThat(stands.postsOn()).isEqualTo(TODAY);
        assertThat(stands.defaultedOn()).contains(TODAY.minusDays(1));
        assertThat(stands.product()).isEqualTo(LoanProduct.PERSONAL_LOAN);
        assertThat(world.audited).extracting(record -> record.operation().code()).contains("lending.WriteOffRead");

        world.post(AccountPurpose.LOAN_FEES_DUE, Direction.DEBIT, eur(1_00), TODAY);
        world.dueFees = 16_00;
        assertThat(world.writeOffs.read(UOW, world.id, proposed.writeOff().id(), approver, CORRELATION).blockers())
                .containsExactly(LoanWriteOffs.Blocker.MOVED);
        assertThat(world.writeOffs.pending(UOW, approver, CORRELATION)).hasSize(1);
    }

    @Test
    @DisplayName("a written-off account's read of the approval is harmless: rows no longer explain the ledger there, and it says why it cannot post")
    void aWrittenOffAccountIsReadWithoutTheDueProof() {
        world.defaulted();
        LoanWriteOffs.Proposed proposed = propose();
        approve(proposed.writeOff().id(), proposed.writeOff().position());
        LoanWriteOffs.Attestation read = world.writeOffs.read(UOW, world.id, proposed.writeOff().id(), approver, CORRELATION);
        assertThat(read.blockers()).containsExactly(LoanWriteOffs.Blocker.NOT_IN_SERVICING);
        assertThat(read.accountStatus()).isEqualTo(LoanStatus.WRITTEN_OFF);
    }

    // ------------------------------------------------------------------ plumbing

    private LoanWriteOffs.Proposed propose() {
        return world.writeOffs.propose(UOW, world.id, "uncollectable after collections", proposer, CORRELATION);
    }

    private LoanWriteOffs.Decided approve(UUID id, LoanWriteOffPosition attested) {
        return world.writeOffs.approve(UOW, world.id, id, attested, "read and agreed", approver, CORRELATION);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** One loan account in memory, with {@code lending V032}'s row and edge, the ledger's lines and the condition history. */
    private static final class World {

        final LoanId id = LoanId.next(IDS);
        final UUID party = UUID.randomUUID();
        final Map<String, UUID> parties = new LinkedHashMap<>();
        final LoanTerms terms = LoanTermsFixtures.personalLoan("0.12").build();
        final LoanTermsVersionId version = LoanTermsVersionId.of(IDS.next());
        final Map<AccountPurpose, LedgerAccountId> accounts = new EnumMap<>(AccountPurpose.class);
        final LedgerAccountId expense = LedgerAccountId.of(IDS.next());
        final Map<LedgerAccountId, List<PurposeBalances.DatedLine>> lines = new LinkedHashMap<>();
        final Map<UUID, LoanWriteOff> rows = new LinkedHashMap<>();
        final List<JournalEntry> appended = new ArrayList<>();
        final List<String> attemptedDating = new ArrayList<>();
        final List<String> history = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        final List<AuditRecord> audited = new ArrayList<>();
        LoanStatus status = LoanStatus.ACTIVE;
        Optional<LoanConditionEvent> latestDefault = Optional.empty();
        boolean defaultFlag = true;
        LocalDate accrualStart = TODAY;
        LocalDate valueDateFloor = TODAY;
        long duePrincipal;
        long dueInterest;
        long dueFees;
        boolean cutoffCovers;
        final LoanWriteOffs writeOffs;

        World() {
            for (AccountPurpose purpose : LoanSubledgerProof.PURPOSES) {
                accounts.put(purpose, LedgerAccountId.of(IDS.next()));
            }
            PurposeBalances<Connection> balances = balances();
            LedgerAccountStore<Connection> chart = chart();
            writeOffs = new LoanWriteOffs(loans(), store(), conditions(), termsStore(), billings(), chart, balances,
                    new LoanClosures(loans(), servicing(), billings(), refuse(LoanWallets.class), chart, balances, posting(),
                            refuse(OutboxWriter.class), IDS, CLOCK),
                    posting(), (uow, actor) -> Optional.ofNullable(parties.get(actor.id())),
                    outbox(), audit(), IDS, CLOCK);
        }

        /** The customer's read over this world's loan. */
        Loans customersRead() {
            return new Loans(loans(), chart(), balances(), fake(LoanPayoutStore.class, (proxy, method, args) -> Optional.empty()),
                    new LoanConditions(billings(), conditions(), termsStore(), refuse(OutboxWriter.class), IDS, CLOCK), store());
        }

        /** The customer's repayment over this world's loan - everything past the door's L3 refuses to run. */
        Repayments repaymentDoor() {
            PurposeBalances<Connection> balances = balances();
            LedgerAccountStore<Connection> chart = chart();
            return new Repayments(loans(), fake(RepaymentStore.class, (proxy, method, args) -> {
                if (method.getName().equals("findByKey")) {
                    return Optional.empty();
                }
                throw new UnsupportedOperationException(method.getName());
            }), billings(), termsStore(), refuse(LoanWallets.class), new com.finapp.ledger.AvailableBalance<Connection>(
                    World.<com.finapp.ledger.BalanceDerivation<Connection>>refuse(com.finapp.ledger.BalanceDerivation.class),
                    World.<com.finapp.ledger.HoldStore<Connection>>refuse(com.finapp.ledger.HoldStore.class)), chart, balances,
                    posting(), new LoanClosures(loans(), servicing(), billings(), refuse(LoanWallets.class), chart, balances, posting(),
                            refuse(OutboxWriter.class), IDS, CLOCK),
                    refuse(OutboxWriter.class), IDS, CLOCK);
        }

        void defaulted() {
            post(AccountPurpose.LOAN_PRINCIPAL, Direction.DEBIT, eur(700_00), TODAY.minusDays(100));
            post(AccountPurpose.LOAN_PRINCIPAL_DUE, Direction.DEBIT, eur(100_00), TODAY.minusDays(100));
            post(AccountPurpose.LOAN_INTEREST_ACCRUED, Direction.DEBIT, eur(3_00), TODAY.minusDays(1));
            post(AccountPurpose.LOAN_INTEREST_DUE, Direction.DEBIT, eur(20_00), TODAY.minusDays(100));
            post(AccountPurpose.LOAN_FEES_DUE, Direction.DEBIT, eur(15_00), TODAY.minusDays(90));
            duePrincipal = 100_00;
            dueInterest = 20_00;
            dueFees = 15_00;
            latestDefault = Optional.of(defaultChange(TODAY.minusDays(1)));
        }

        LoanConditionEvent defaultChange(LocalDate on) {
            return new LoanConditionEvent(IDS.next(), id, on, ConditionKind.DEFAULT,
                    new DelinquencyState(95, DelinquencyBucket.of(95, terms.delinquencyBounds()), true), Optional.empty(),
                    LoanAgreementId.of(IDS.next()), 1);
        }

        void payEverythingDue() {
            post(AccountPurpose.LOAN_PRINCIPAL_DUE, Direction.CREDIT, eur(duePrincipal), TODAY);
            post(AccountPurpose.LOAN_INTEREST_DUE, Direction.CREDIT, eur(dueInterest), TODAY);
            post(AccountPurpose.LOAN_FEES_DUE, Direction.CREDIT, eur(dueFees), TODAY);
            duePrincipal = 0;
            dueInterest = 0;
            dueFees = 0;
        }

        void reset(AccountPurpose purpose) {
            lines.remove(accounts.get(purpose));
        }

        void post(AccountPurpose purpose, Direction direction, Money amount, LocalDate valueDate) {
            LedgerAccountId account = accounts.get(purpose);
            lines.computeIfAbsent(account, key -> new ArrayList<>()).add(new PurposeBalances.DatedLine(
                    JournalEntryId.of(IDS.next()), account, valueDate, direction, amount));
        }

        Money balance(AccountPurpose purpose) {
            Money net = eur(0);
            for (PurposeBalances.DatedLine line : lines.getOrDefault(accounts.get(purpose), List.of())) {
                net = line.direction() == Direction.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
            }
            return net;
        }

        // -------------------------------------------------------------- the ports

        private Optional<LoanStore.Owned> owned() {
            Money principal = eur(1_000_00);
            Loan loan = new Loan(id, party, LoanProduct.PERSONAL_LOAN, status, EUR, LoanOfferId.of(IDS.next()), IDS.next(),
                    Instant.parse("2026-01-01T00:00:00Z"), Optional.empty(), CORRELATION);
            LoanAgreement agreement = new LoanAgreement(LoanAgreementId.of(IDS.next()), id, 1, Optional.of(loan.offer()), Optional.empty(),
                    Optional.empty(), loan.decision(), version, LoanProduct.PERSONAL_LOAN, principal, Optional.of(12),
                    new BigDecimal("0.12"), 5, Optional.of(EngineVersion.SCHEDULE_ENGINE_V1), EngineVersion.ACCRUAL_ENGINE_V1,
                    EngineVersion.ALLOCATION_ENGINE_V1, Optional.empty(), terms.agreementTemplate(), "terms", new byte[32],
                    Instant.parse("2026-01-01T00:00:00Z"));
            return Optional.of(new LoanStore.Owned(loan, agreement));
        }

        LoanStore loans() {
            return fake(LoanStore.class, (proxy, method, args) -> switch (method.getName()) {
                case "partyOf" -> Optional.of(party);
                case "lock", "ownedBy", "lockOwnedBy" -> owned();
                case "principalValueDate" -> valueDateFloor;
                case "appendEvent" -> {
                    LoanStore.HistoryRow row = (LoanStore.HistoryRow) args[1];
                    history.add(row.from().map(Enum::name).orElse("-") + "->" + row.to() + " " + row.reason() + " " + row.actor().id());
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        LoanServicingStore servicing() {
            return fake(LoanServicingStore.class, (proxy, method, args) -> switch (method.getName()) {
                case "accrualStartOf" -> Optional.of(accrualStart);
                case "latestAccrual" -> Optional.empty();
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        InstalmentBillingStore billings() {
            return fake(InstalmentBillingStore.class, (proxy, method, args) -> {
                if (!method.getName().equals("dueItems")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                return duePrincipal + dueInterest + dueFees == 0 && !hasBill() ? List.of() : List.of(new DueState.DueItem(
                        DueItemId.of(BILLED_ITEM), TODAY.minusDays(95), eur(dueFees), eur(dueInterest), eur(duePrincipal)));
            });
        }

        private boolean hasBill() {
            return true;
        }

        LoanConditionStore conditions() {
            return fake(LoanConditionStore.class, (proxy, method, args) -> {
                if (!method.getName().equals("latest")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                return latestDefault.filter(change -> defaultFlag).map(change -> (Object) change)
                        .or(() -> latestDefault.map(change -> (Object) new LoanConditionEvent(change.id(), change.loan(),
                                change.businessDate(), change.kind(), DelinquencyState.CURRENT, Optional.empty(), change.agreement(),
                                change.agreementVersion())));
            });
        }

        LoanTermsStore termsStore() {
            LoanTermsStore.VersionRow row = new LoanTermsStore.VersionRow(version, LoanProduct.PERSONAL_LOAN, 1,
                    LoanTermsVersionStatus.ACTIVE, "officer", Instant.EPOCH, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
            return fake(LoanTermsStore.class, (proxy, method, args) -> {
                if (!method.getName().equals("version")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                return Optional.of(new LoanTermsStore.LoanTermsVersion(row, terms));
            });
        }

        @SuppressWarnings("unchecked")
        LedgerAccountStore<Connection> chart() {
            return (LedgerAccountStore<Connection>) fake(LedgerAccountStore.class, (proxy, method, args) -> switch (method.getName()) {
                case "findOwned" -> Optional.of(account(accounts.get((AccountPurpose) args[2]), (AccountPurpose) args[2], OwnerKind.LOAN));
                case "findOperational" -> Optional.of(account(expense, (AccountPurpose) args[1], OwnerKind.OPERATIONAL));
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private static LedgerAccount account(LedgerAccountId account, AccountPurpose purpose, OwnerKind owner) {
            AccountType type = purpose == AccountPurpose.LOAN_WRITE_OFF_EXPENSE ? AccountType.EXPENSE : AccountType.ASSET;
            return LedgerAccount.rehydrate(account, type, type.normalBalance(), EUR, owner,
                    owner == OwnerKind.LOAN ? BILLED_ITEM : null, purpose, null, LedgerAccountStatus.ACTIVE, CLOCK.instant(), CLOCK.instant());
        }

        PurposeBalances<Connection> balances() {
            return new PurposeBalances<>() {
                @Override
                public Money netDebit(Connection unitOfWork, Set<AccountPurpose> purposes, CurrencyCode currency) {
                    throw new UnsupportedOperationException("the write-off folds its own accounts' lines");
                }

                @Override
                public Money netCreditBeside(Connection unitOfWork, Set<AccountPurpose> purposes, Set<AccountPurpose> beside,
                        CurrencyCode currency) {
                    throw new UnsupportedOperationException("the headroom's, never the write-off's");
                }

                @Override
                public List<EntryLine> linesOn(Connection unitOfWork, LedgerAccountId account) {
                    return lines.getOrDefault(account, List.of()).stream()
                            .map(line -> new EntryLine(line.entry(), line.direction(), line.amount())).toList();
                }

                @Override
                public List<DatedLine> datedLinesOn(Connection unitOfWork, Set<LedgerAccountId> asked, LocalDate through) {
                    List<DatedLine> found = new ArrayList<>();
                    asked.forEach(account -> lines.getOrDefault(account, List.of()).stream()
                            .filter(line -> !line.valueDate().isAfter(through)).forEach(found::add));
                    found.sort(java.util.Comparator.comparing(DatedLine::valueDate));
                    return found;
                }
            };
        }

        LoanWriteOffStore store() {
            return new LoanWriteOffStore() {
                @Override
                public Instant databaseNow(Connection unitOfWork) {
                    return TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC);
                }

                @Override
                public Instant insert(Connection unitOfWork, NewProposal proposal) {
                    Instant now = databaseNow(unitOfWork);
                    rows.put(proposal.id(), new LoanWriteOff(proposal.id(), proposal.loan(), proposal.position(), proposal.defaultEvent(),
                            proposal.agreementVersion(), LoanWriteOffStatus.PROPOSED, proposal.proposer().id(), now, proposal.reason(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
                    return now;
                }

                @Override
                public boolean decide(Connection unitOfWork, Decision decision) {
                    LoanWriteOff row = rows.get(decision.writeOff());
                    if (row.status() != LoanWriteOffStatus.PROPOSED) {
                        return false;
                    }
                    if (decision.outcome() == LoanWriteOffStatus.APPROVED && decision.decider().id().equals(decision.proposedBy())) {
                        throw new LendingStorageException("loan_write_off_decision_four_eyes: SQLSTATE 23514");
                    }
                    rows.put(row.id(), new LoanWriteOff(row.id(), row.loan(), row.position(), row.defaultEvent(), row.agreementVersion(),
                            decision.outcome(), row.proposedBy(), row.proposedAt(), row.proposalReason(), Optional.of(decision.decider().id()),
                            Optional.of(databaseNow(unitOfWork)), Optional.of(decision.reason()), decision.journalEntry(), decision.valueDate()));
                    return true;
                }

                @Override
                public boolean markWrittenOff(Connection unitOfWork, LoanId loan, LoanStatus from) {
                    if (status != from || approvedOf(unitOfWork, loan).isEmpty()) {
                        return false;
                    }
                    status = LoanStatus.WRITTEN_OFF;
                    return true;
                }

                @Override
                public Optional<LoanWriteOff> find(Connection unitOfWork, LoanId loan, UUID found) {
                    return Optional.ofNullable(rows.get(found));
                }

                @Override
                public boolean pending(Connection unitOfWork, LoanId loan) {
                    return rows.values().stream().anyMatch(row -> row.status() == LoanWriteOffStatus.PROPOSED);
                }

                @Override
                public Optional<LoanWriteOff> approvedOf(Connection unitOfWork, LoanId loan) {
                    return rows.values().stream().filter(row -> row.status() == LoanWriteOffStatus.APPROVED).findFirst();
                }

                @Override
                public List<LoanWriteOff> oldestPending(Connection unitOfWork, int limit) {
                    return rows.values().stream().filter(row -> row.status() == LoanWriteOffStatus.PROPOSED).limit(limit).toList();
                }
            };
        }

        @SuppressWarnings("unchecked")
        OutboxWriter<Connection> outbox() {
            return (OutboxWriter<Connection>) fake(OutboxWriter.class, (proxy, method, args) -> {
                com.finapp.sharedkernel.event.EventEnvelope envelope = (com.finapp.sharedkernel.event.EventEnvelope) args[1];
                if (envelope.eventType().startsWith("lending.")) {
                    events.add(envelope.eventType());
                    payloads.add(new String((byte[]) args[2], java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            });
        }

        @SuppressWarnings("unchecked")
        AuditWriter<Connection> audit() {
            return (AuditWriter<Connection>) fake(AuditWriter.class, (proxy, method, args) -> {
                audited.add((AuditRecord) args[1]);
                return null;
            });
        }

        @SuppressWarnings("unchecked")
        PostingService posting() {
            IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) fake(IdempotencyRecordStore.class,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "claim" -> IdempotencyRecordStore.ClaimOutcome.CLAIMED;
                        case "complete" -> true;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            JournalEntryStore<Connection> journal = (JournalEntryStore<Connection>) fake(JournalEntryStore.class, (proxy, method, args) -> {
                JournalEntry entry = (JournalEntry) args[1];
                attemptedDating.add("AS_STATED");
                if (cutoffCovers) {
                    throw new PeriodClosedException("the posting date is in a locked accounting period");
                }
                appended.add(entry);
                for (JournalLine line : entry.lines()) {
                    lines.computeIfAbsent(line.account(), key -> new ArrayList<>()).add(new PurposeBalances.DatedLine(entry.id(), line.account(),
                            entry.valueDate(), line.direction(), line.amount()));
                }
                return null;
            });
            return new PostingService(new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)), journal, audit(),
                    outbox(), (BalanceProjection<Connection>) fake(BalanceProjection.class, (proxy, method, args) -> null), IDS, CLOCK,
                    (outcome, elapsed) -> { });
        }

        @SuppressWarnings("unchecked")
        private static <T> T fake(Class<?> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(LoanWriteOffsTest.class.getClassLoader(), new Class<?>[] {type}, handler);
        }

        private static <T> T refuse(Class<?> type) {
            return fake(type, (proxy, method, args) -> {
                throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
            });
        }
    }
}
