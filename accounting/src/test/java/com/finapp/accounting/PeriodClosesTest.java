package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryPeriodCloseStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close and the reopen, hermetic (`P14-TSK-012`; {@code PHASE_14_PLAN.md} sections 6, 8.3 rows 3, 4 and 9, 13.5; {@code INV-ACC-09}, {@code INV-ACC-10}, {@code INV-AUD-04}): the machine's rules over the in-memory
 * stores, which hold the database's ranks. Both failures in view: a close that lands when it should not, and a month that can never close. The real ranks, and the races over ten connections, are {@code
 * PeriodCloseDatabaseTest}'s and {@code PeriodReopenDatabaseTest}'s (owed, C5).
 */
@DisplayName("the four-eyes close, its rejection and the reopen (P14-TSK-012)")
class PeriodClosesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final YearMonth APRIL = MARCH.plusMonths(1);
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final Actor OTHER_CONTROLLER = new Actor("controller-2", ActorType.CUSTOMER);
    private static final String REASON = "the books are read and agree";
    /** An id no reopen has: what an act names when none was ever proposed, refused by name whatever it names. */
    private static final PeriodReopenId NONE_READ = PeriodReopenId.next(IDS);
    private static final java.util.UUID PSP = java.util.UUID.nameUUIDFromBytes(new byte[] {7});
    private static final String DISCLOSURE = "suspense of two items, aged under a week, explained by the bank's holiday";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore runStore = new InMemoryCloseRunStore(periods);
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final InMemoryValuationRateSetStore setStore = new InMemoryValuationRateSetStore().watching(periods);
    private final InMemoryPeriodCloseStore closes = new InMemoryPeriodCloseStore(periods, runStore);
    private final com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore priorPeriod = new com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore(periods);
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final CloseRuns runs = new CloseRuns(runStore, periods, breaks, CloseRuns.Leases.STANDARD, (uow, record) -> audit.add(record), IDS, CLOCK);
    private Optional<LocalDate> closedThrough = Optional.empty();
    private boolean cutoffBusy;

    private final PostingCutoff cutoff = new PostingCutoff() {
        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return closedThrough;
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            closedThrough = Optional.of(through);
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            if (cutoffBusy) {
                throw new PostingCutoff.CutoffBusy("busy", new RuntimeException());
            }
            if (!closedThrough.equals(Optional.of(from))) {
                throw new IllegalStateException("the posting cutoff stands elsewhere");
            }
            order.add("cutoff");
            closedThrough = to;
            return true;
        }
    };

    private final ReportRunSupersession supersession = (uow, period, actor, correlation) -> {
        order.add("supersede");
        return 3;
    };

    private List<ReconciliationRunCoverage.Line> monthLines = List.of(pspLine(false));
    private final ReconciliationRunCoverage coverage = (connection, month) -> {
        // Never a second connection while the period row is held: under load that waits on a pool every holder of a period row is waiting on.
        assertThat(connection).as("the machine's own connection").isSameAs(UOW);
        return new ReconciliationRunCoverage.Coverage(month, monthLines,
                List.of(new ReconciliationRunCoverage.Source(PSP, "simulated-psp.settlement", ReconciliationRunCoverage.SourceState.ACTIVE)));
    };

    private final PeriodCloses machine = new PeriodCloses(periods, closes, runs, breaks, setStore, coverage, cutoff, supersession, priorPeriod, (uow, record) -> audit.add(record),
            (uow, envelope, payload, mediaType) -> {
                events.add(envelope);
                payloads.add(payload);
            }, IDS, CLOCK);
    private final ValuationRateSets sets = new ValuationRateSets(setStore, periods, new OneRate(), ValuationStaleness.standard(), (uow, record) -> { }, IDS, CLOCK);
    private final AccountingBreaks breakMachine = new AccountingBreaks(breaks, new com.finapp.accounting.testing.InMemoryJournal(), (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);

    private AccountingPeriodId march;

    @BeforeEach
    void marchIsLockedAndClosedThroughItsEnd() {
        march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, correlation()));
        lock(march);
    }

    // ------------------------------------------------------------------ propose: readiness

    @Test
    @DisplayName("#anIncompleteRunCannotBeProposed: no run yet, a check FAILED, a check PENDING - CloseNotReady, naming codes; nothing written")
    void anIncompleteRunCannotBeProposed() {
        assertThatThrownBy(() -> machine.propose(UOW, march, CloseRunId.next(IDS), List.of(), ACCOUNTANT, REASON, correlation()))
                .as("the owner's (3): a lock with no run yet").isInstanceOf(PeriodCloses.CloseNotReady.class).hasMessageContaining("no close run yet");

        CloseRunId run = started();
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .as("every attempt PENDING").isInstanceOf(PeriodCloses.CloseNotReady.class).hasMessageContaining("OPEN_ACCOUNTING_BREAKS");

        concludeAll(Map.of(CloseCheckCode.PROJECTION_VERIFIED, CloseCheckStatus.FAILED));
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .as("a check FAILED").isInstanceOf(PeriodCloses.CloseNotReady.class).hasMessageContaining("PROJECTION_VERIFIED");

        assertThat(closes.closes()).isEmpty();
        assertThat(period().status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a break of the month OPEN refuses the proposal, read live: a stale attempt can never make a run ready")
    void anOpenBreakRefusesTheProposal() {
        CloseRunId run = readyRun();
        raise("LOAN_SUBLEDGER:loan-1", "fails=[A]");
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .isInstanceOf(PeriodCloses.CloseNotReady.class).hasMessageContaining("open breaks 1");
    }

    @Test
    @DisplayName("M-4: readiness is read holding the period row: the row taken first, every conclusion and re-run behind it; a pending unlock refuses, an OPEN or CLOSED period is stale")
    void theProposalHoldsTheRow() {
        CloseRunId run = readyRun();
        int before = periods.rowLocks();
        machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation());
        assertThat(periods.rowLocks()).as("the row FOR UPDATE, before the reading").isEqualTo(before + 1);

        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, APRIL, correlation()));
        assertThatThrownBy(() -> machine.propose(UOW, april, run, List.of(), ACCOUNTANT, REASON, correlation())).as("OPEN")
                .isInstanceOf(AccountingPeriods.PeriodStale.class);
    }

    @Test
    @DisplayName("an unlock pending refuses the proposal - V003's CHECK could not hold it past LOCKED - and the way on is to decide the unlock, never a wedge: since P14-TSK-024 a rejection or a withdrawal ends it, the same lock and run then proposable")
    void aPendingUnlockRefusesTheProposal() {
        CloseRunId run = readyRun();
        periods.proposeUnlock(UOW, march, period().revision(), ACCOUNTANT, "a mistake was found");
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("unlock").hasMessageContaining("rejected or withdrawn");
        assertThat(periods.endUnlock(UOW, new AccountingPeriodStore.NewUnlockEnd(UUID.randomUUID(), march, period().revision(), ACCOUNTANT.id(), PeriodRequestEnd.REJECTED, CONTROLLER, "not needed",
                correlation()))).isTrue();
        assertThat(machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()).status()).as("the same lock and run, no new run needed").isEqualTo(PeriodStatus.CLOSE_PROPOSED);
    }

    // ------------------------------------------------------------------ propose: a person's reason per DISCLOSED attempt

    @Test
    @DisplayName("INV-ACC-09, unamended: a DISCLOSED attempt with no reason is ReasonRequired, naming it; an instrument-shaped or blank reason too; nothing written")
    void everyDisclosedAttemptCarriesAReason() {
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE, CloseCheckCode.ACCRUALS_COMPLETE);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .isInstanceOf(AccountingReasons.ReasonRequired.class).hasMessageContaining("ACCRUALS_COMPLETE");
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(reasonFor(CloseCheckCode.ACCRUALS_COMPLETE)), ACCOUNTANT, REASON, correlation()))
                .as("one short").isInstanceOf(AccountingReasons.ReasonRequired.class).hasMessageContaining("RECONCILIATION_DISCLOSURE");
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(new PeriodCloses.Disclosure(CloseCheckCode.ACCRUALS_COMPLETE, 1, " "),
                reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, REASON, correlation())).as("blank").isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(new PeriodCloses.Disclosure(CloseCheckCode.ACCRUALS_COMPLETE, 1, "card 4111 1111 1111 1111"),
                reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, REASON, correlation())).as("an instrument").isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(reasonFor(CloseCheckCode.ACCRUALS_COMPLETE), reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, " ", correlation()))
                .as("the act's own").isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThat(closes.closes()).isEmpty();
    }

    @Test
    @DisplayName("#aRerunWhileWritingIsStale: a reason is bound to the attempt read - a re-run landing while it was written, another run, a PASSED check, a check twice are refused, never covered")
    void aReasonIsBoundToTheAttemptRead() {
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        PeriodCloses.Disclosure read = reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE);

        runs.rerun(UOW, march, CloseCheckCode.RECONCILIATION_DISCLOSURE, ACCOUNTANT, "a batch arrived", correlation());
        concludeAll(Map.of(CloseCheckCode.RECONCILIATION_DISCLOSURE, CloseCheckStatus.DISCLOSED));
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(read), ACCOUNTANT, REASON, correlation()))
                .as("attempt 1 read; attempt 2 stands").isInstanceOf(PeriodCloses.CloseProposalStale.class).hasMessageContaining("attempt 1");

        PeriodCloses.Disclosure latest = new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, 2, DISCLOSURE);
        assertThatThrownBy(() -> machine.propose(UOW, march, CloseRunId.next(IDS), List.of(latest), ACCOUNTANT, REASON, correlation()))
                .as("another run").isInstanceOf(PeriodCloses.CloseProposalStale.class);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(latest, new PeriodCloses.Disclosure(CloseCheckCode.PROJECTION_VERIFIED, 1, DISCLOSURE)), ACCOUNTANT, REASON, correlation()))
                .as("a PASSED check").isInstanceOf(PeriodCloses.CloseProposalStale.class);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(latest, latest), ACCOUNTANT, REASON, correlation()))
                .as("a check twice").isInstanceOf(PeriodCloses.CloseProposalStale.class);
        assertThat(closes.closes()).isEmpty();

        assertThat(machine.propose(UOW, march, run, List.of(latest), ACCOUNTANT, REASON, correlation()).status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);
    }

    @Test
    @DisplayName("what was disclosed is frozen in the close record - the attempt, a copy of its evidence, the person's reason; the nine attempts named; the run, pin, rate set and the month's breaks recorded")
    void theDisclosureIsFrozen() {
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        PeriodCloses.Acted acted = machine.propose(UOW, march, run, List.of(reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, REASON, correlation());

        assertThat(acted.status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);
        assertThat(acted.replayed()).isFalse();
        PeriodCloseStore.CloseRow proposal = closes.closes().get(0);
        assertThat(proposal.run()).isEqualTo(run);
        assertThat(proposal.lockedAt()).isEqualTo(period().lockedAt().orElseThrow());
        assertThat(proposal.pinned()).isEqualTo(period().pinned().orElseThrow().id());
        assertThat(proposal.rateSet()).isEqualTo(setStore.approvedFor(UOW, march).orElseThrow().id());
        List<PeriodCloseStore.CheckRow> frozen = closes.checks(UOW, proposal.id());
        assertThat(frozen).hasSize(CloseCheckCode.values().length).extracting(PeriodCloseStore.CheckRow::check).containsExactly(CloseCheckCode.values());
        PeriodCloseStore.CheckRow disclosed = frozen.stream().filter(row -> row.status() == CloseCheckStatus.DISCLOSED).findFirst().orElseThrow();
        assertThat(disclosed.check()).isEqualTo(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        assertThat(disclosed.disclosureReason()).contains(DISCLOSURE);
        assertThat(disclosed.evidence()).contains(attemptOf(CloseCheckCode.RECONCILIATION_DISCLOSURE).evidence().orElseThrow());
        assertThat(frozen.stream().filter(row -> row.status() == CloseCheckStatus.PASSED)).allSatisfy(row -> {
            assertThat(row.disclosureReason()).isEmpty();
            assertThat(row.evidence()).isEmpty();
        });
    }

    @Test
    @DisplayName("the proposal is audited, reasoned, in history and evented in its transaction; no reason in a summary, a payload, an exception or a text form")
    void theProposalIsOnTheRecordAndHoldsNoReason() {
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        machine.propose(UOW, march, run, List.of(reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, REASON, correlation());

        assertThat(lastEvent().eventType()).isEqualTo(PeriodCloses.CLOSE_PROPOSED_EVENT);
        assertThat(lastEvent().aggregateType()).isEqualTo(AccountingPeriods.TARGET_TYPE);
        String payload = new String(payloads.get(payloads.size() - 1), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"period\":\"2097-03\"", "\"closeRun\":\"" + run.value() + "\"").doesNotContain(DISCLOSURE).doesNotContain(REASON);
        AuditRecord record = lastAudit(AccountingAuditAction.PERIOD_CLOSE_PROPOSED);
        assertThat(record.actor()).isEqualTo(ACCOUNTANT);
        assertThat(record.reason()).contains(REASON);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(record.changeSummary().orElseThrow()).contains("RECONCILIATION_DISCLOSURE#1").doesNotContain(DISCLOSURE);
        assertThat(periods.history()).extracting(AccountingPeriodStore.EventRow::act).contains(AccountingPeriodStore.Act.CLOSE_PROPOSED);
        assertThat(closes.closes().get(0).toString()).doesNotContain(REASON);
        assertThat(closes.checks(UOW, closes.closes().get(0).id()).toString()).doesNotContain(DISCLOSURE);
        assertThat(new PeriodCloses.Disclosure(CloseCheckCode.ACCRUALS_COMPLETE, 1, DISCLOSURE).toString()).doesNotContain(DISCLOSURE);
    }

    @Test
    @DisplayName("the same person saying the same thing under a new key converges and writes nothing; saying another thing, or another person, is refused while the proposal stands")
    void aRepeatedProposalConverges() {
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        List<PeriodCloses.Disclosure> reasons = List.of(reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE));
        machine.propose(UOW, march, run, reasons, ACCOUNTANT, REASON, correlation());
        int written = events.size();

        assertThat(machine.propose(UOW, march, run, reasons, ACCOUNTANT, REASON, correlation()).replayed()).isTrue();
        assertThat(events).hasSize(written);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, reasons, new Actor("accountant-2", ActorType.CUSTOMER), REASON, correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, reasons, ACCOUNTANT, "another reason", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, 1, "another disclosure")), ACCOUNTANT, REASON, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThat(closes.closes()).hasSize(1);
    }

    @Test
    @DisplayName("the independent review, I-2: a batch repudiated after its check concluded refuses the proposal, CloseNotReady naming the source, nothing written; a new counting run lets it through")
    void aRepudiationRefusesTheProposal() {
        CloseRunId run = readyRun();
        monthLines = List.of(pspLine(true));
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation())).isInstanceOf(PeriodCloses.CloseNotReady.class)
                .hasMessageContaining("simulated-psp.settlement");
        assertThat(period().status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(closes.closes()).isEmpty();

        monthLines = List.of(pspLine(true), pspLine(false));
        assertThat(machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()).status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);
    }

    @Test
    @DisplayName("a repeat of a proposal converges whatever the month now says: the re-judge stands after the replay, so a timeout's retry is never refused by what came since")
    void aRepeatedProposalIsNotReJudged() {
        CloseRunId run = readyRun();
        machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation());
        monthLines = List.of(pspLine(true));
        assertThat(machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()).replayed()).isTrue();
    }

    // ------------------------------------------------------------------ approve

    @Test
    @DisplayName("the independent review, I-2: a batch repudiated since the proposal refuses the approval, stale, and the approver's read says so; a new counting run lets the same proposal through, nothing else having moved")
    void aRepudiationRefusesTheApproval() {
        PeriodCloseId proposal = proposed();
        monthLines = List.of(pspLine(true));
        assertThat(machine.proposalOf(UOW, march).orElseThrow().stillStands()).as("the approver's read, by the same question").isFalse();
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation())).isInstanceOf(PeriodCloses.CloseProposalStale.class)
                .hasMessageContaining("simulated-psp.settlement");
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);

        monthLines = List.of(pspLine(true), pspLine(false));
        assertThat(machine.proposalOf(UOW, march).orElseThrow().stillStands()).isTrue();
        assertThat(machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation()).status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("a repeat of an approval converges whatever the month now says; and only `ran` refuses - counts moving, a second run or one unfinished, while a counting run stands do not, or a close would chase traffic")
    void onlyTheRunRefusesAndAReplayIsNotReJudged() {
        PeriodCloseId proposal = proposed();
        monthLines = List.of(pspLine(false), pspLine(false), new ReconciliationRunCoverage.Line(PSP, "BATCH", "BLOCKED", MARCH.atDay(3), false, 1),
                new ReconciliationRunCoverage.Line(PSP, "REPROCESS", "OPEN", MARCH.atDay(3), false, 1));
        assertThat(machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation()).status()).isEqualTo(PeriodStatus.CLOSED);

        monthLines = List.of(pspLine(true));
        assertThat(machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation()).replayed()).isTrue();
    }

    @Test
    @DisplayName("a month that cannot be read refuses, never a pass: no source at all, and a read that throws")
    void aMonthUnreadRefuses() {
        CloseRunId run = readyRun();
        PeriodCloses unreadable = new PeriodCloses(periods, closes, runs, breaks, setStore, (connection, month) -> {
            throw new IllegalStateException("unreadable");
        }, cutoff, supersession, priorPeriod, (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> events.add(envelope), IDS, CLOCK);
        assertThatThrownBy(() -> unreadable.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation())).isInstanceOf(IllegalStateException.class);
        PeriodCloses noSource = new PeriodCloses(periods, closes, runs, breaks, setStore, (connection, month) -> new ReconciliationRunCoverage.Coverage(month, List.of(), List.of()), cutoff, supersession, priorPeriod,
                (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> events.add(envelope), IDS, CLOCK);
        assertThatThrownBy(() -> noSource.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation())).isInstanceOf(PeriodCloses.CloseNotReady.class);
        assertThat(closes.closes()).isEmpty();
    }

    @Test
    @DisplayName("#theProposerCannotApprove: a SelfApprovalRefused, nothing moved; the record FAILED kept beside it; the store's four-eyes rank beneath the domain's refusal")
    void theProposerCannotApprove() {
        PeriodCloseId proposal = proposed();
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, ACCOUNTANT, REASON, correlation())).isInstanceOfSatisfying(AccountingPeriods.SelfApprovalRefused.class, refused -> {
            assertThat(refused.summary()).contains("act=period:close");
            machine.recordRefusal(UOW, refused, ACCOUNTANT, correlation());
        });
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);
        assertThat(lastAudit(AccountingAuditAction.SELF_APPROVAL_REFUSED).outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThatThrownBy(() -> closes.decide(UOW, proposal, PeriodCloseStatus.APPROVED, ACCOUNTANT, REASON)).as("the row's CHECK, beneath").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("period_close_four_eyes");
        assertThatThrownBy(() -> periods.appendEvent(UOW, new AccountingPeriodStore.EventRow(IDS.next(), march, AccountingPeriodStore.Act.CLOSED, Optional.of(PeriodStatus.CLOSE_PROPOSED), PeriodStatus.CLOSED,
                1, ACCOUNTANT, Optional.of(ACCOUNTANT.id()), Optional.empty(), Optional.of(REASON), correlation()))).as("the history's CHECK, beneath").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("period_event_four_eyes");
    }

    @Test
    @DisplayName("#twoApprovalsOneClose: CLOSED once; the same approver's repeat converges writing nothing; another approver finds it stale; one event, one audit record, the history naming the proposer")
    void twoApprovalsOneClose() {
        PeriodCloseId proposal = proposed();
        PeriodCloses.Acted first = machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation());
        assertThat(first.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(first.replayed()).isFalse();
        int written = events.size();

        assertThat(machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, OTHER_CONTROLLER, REASON, correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);

        assertThat(events).hasSize(written);
        assertThat(lastEvent().eventType()).isEqualTo(PeriodCloses.CLOSED_EVENT);
        assertThat(audit.stream().filter(record -> record.operation() == AccountingAuditAction.PERIOD_CLOSED)).hasSize(1);
        AccountingPeriodStore.EventRow closed = periods.history().stream().filter(row -> row.act() == AccountingPeriodStore.Act.CLOSED).findFirst().orElseThrow();
        assertThat(closed.proposedBy()).contains(ACCOUNTANT.id());
        assertThat(closed.actor()).isEqualTo(CONTROLLER);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(period().lockedAt()).as("the lock a FINAL run reproduces under stands (ADR-0115)").isPresent();
        assertThat(period().pinned()).isPresent();
        assertThat(closedThrough).contains(MARCH.atEndOfMonth());
    }

    @Test
    @DisplayName("what the approver approves: a later attempt than the one read refuses it (M-1's re-judgement landing while proposed) - stale, and the way on is rejection, a re-run and a new proposal")
    void aLaterAttemptRefusesTheApproval() {
        PeriodCloseId proposal = proposed();
        CloseRunId run = closes.closes().get(0).run();
        runStore.insertAttempt(UOW, new CloseRunStore.NewAttempt(CloseCheckId.next(IDS), run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS, 2, Actor.SYSTEM, Optional.empty(), correlation()));

        assertThat(machine.proposalOf(UOW, march).orElseThrow().stillStands()).isFalse();
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation())).isInstanceOf(PeriodCloses.CloseProposalStale.class);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);

        // What the machine cannot reach, held all the same, as V008's trigger holds it for every writer: a claim asks LOCKED, so that attempt stays PENDING until a rejection.
        // Concluded behind the machine's back, readiness holds again, every check PASSED, and only the frozen attempt against the latest can tell it is not what was read.
        periods.markCloseRejected(UOW, march, 1);
        runs.concludeOpenBreaks(UOW, runs.claim(UOW, Actor.SYSTEM).orElseThrow());
        periods.markCloseProposed(UOW, march, 1);
        assertThat(machine.proposalOf(UOW, march).orElseThrow().stillStands()).isFalse();
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation())).isInstanceOf(PeriodCloses.CloseProposalStale.class)
                .hasMessageContaining("OPEN_ACCOUNTING_BREAKS has a later attempt");

        machine.reject(UOW, march, proposal, CONTROLLER, "the attempts moved", correlation());
        concludeAll(Map.of());
        PeriodCloses.Acted again = machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation());
        assertThat(again.proposal()).isNotEqualTo(Optional.of(proposal));
        assertThat(machine.approve(UOW, march, again.proposal().orElseThrow(), CONTROLLER, REASON, correlation()).status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("a break raised, acknowledged or resolved since the proposal refuses the approval, whatever the attempts say: the month's breaks are not what was read")
    void aBreakMovedRefusesTheApproval() {
        PeriodCloseId proposal = proposed();
        raise("LOAN_SUBLEDGER:loan-1", "fails=[A]");
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation())).as("an overtaken worker's raise").isInstanceOf(PeriodCloses.CloseProposalStale.class);

        AccountingBreakId raised = breaks.rows().get(0).id();
        breakMachine.proposeAcknowledgement(UOW, raised, ACCOUNTANT, "disclosed", correlation());
        breakMachine.approveAcknowledgement(UOW, raised, CONTROLLER, "approved", correlation());
        assertThatThrownBy(() -> machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation())).as("acknowledged since: read as none").isInstanceOf(PeriodCloses.CloseProposalStale.class);
    }

    @Test
    @DisplayName("a re-run, a set's capture and an unlock's proposal are refused while CLOSE_PROPOSED: nothing but the approver's or rejecter's act moves what was proposed")
    void nothingMovesWhileProposed() {
        proposed();
        assertThatThrownBy(() -> runs.rerun(UOW, march, CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT, "again", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> sets.capture(UOW, march, ACCOUNTANT, null, correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThat(periods.proposeUnlock(UOW, march, 1, ACCOUNTANT, "no")).isFalse();
    }

    @Test
    @DisplayName("the approval names the proposal read: another period's, a rejected one's, and a malformed state are stale - the approver never closes something other than what they read")
    void theApprovalNamesTheProposalRead() {
        PeriodCloseId first = proposed();
        CloseRunId run = closes.closes().get(0).run();
        assertThatThrownBy(() -> machine.approve(UOW, march, PeriodCloseId.next(IDS), CONTROLLER, REASON, correlation())).as("no such proposal").isInstanceOf(PeriodCloses.CloseProposalStale.class);
        machine.reject(UOW, march, first, CONTROLLER, "not yet", correlation());
        PeriodCloseId second = machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();

        assertThatThrownBy(() -> machine.approve(UOW, march, first, CONTROLLER, REASON, correlation())).as("what they read was rejected; another stands").isInstanceOf(PeriodCloses.CloseProposalStale.class);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);
        assertThat(machine.approve(UOW, march, second, CONTROLLER, REASON, correlation()).status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("the controller's read: the latest proposal, its nine attempts with their evidence, the reason per DISCLOSED attempt and whether it still stands; recorded; none is empty")
    void theApproversRead() {
        assertThat(machine.proposalOf(UOW, march)).isEmpty();
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        machine.propose(UOW, march, run, List.of(reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, REASON, correlation());

        PeriodCloses.ProposalView view = machine.proposalOf(UOW, march).orElseThrow();
        assertThat(view.stillStands()).isTrue();
        assertThat(view.pendingReopen()).isEmpty();
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length);
        assertThat(view.checks()).as("each attempt as it concluded, read from its own row").allSatisfy(check -> {
            CloseRunStore.AttemptRow attempt = attemptOf(check.check().check());
            assertThat(check.attempt().evidence()).isEqualTo(attempt.evidence().orElseThrow());
            assertThat(check.attempt().concludedAt()).isEqualTo(attempt.concludedAt().orElseThrow());
        });
        assertThat(view.checks()).filteredOn(check -> check.check().check() == CloseCheckCode.RECONCILIATION_DISCLOSURE).singleElement()
                .satisfies(check -> assertThat(check.check().disclosureReason()).contains(DISCLOSURE));

        machine.recordRead(UOW, view, Optional.empty(), CONTROLLER, correlation());
        AuditRecord read = lastAudit(AccountingAuditAction.CLOSE_PROPOSAL_READ);
        assertThat(read.changeSummary().orElseThrow()).contains("stillStands=true").contains("reopenPending=false").doesNotContain("overtakenReturns").doesNotContain(DISCLOSURE);

        assertThatThrownBy(() -> machine.proposalOf(UOW, AccountingPeriodId.next(IDS))).isInstanceOf(AccountingPeriods.PeriodNotFound.class);
    }

    // ------------------------------------------------------------------ reject

    @Test
    @DisplayName("a rejection returns the period to LOCKED, the same lock and run, the proposal kept REJECTED; it may be the proposer's; the same rejecter's repeat converges; a next proposal is a new row with its own reasons")
    void aRejectionKeepsTheLockAndCanBeProposedAgain() {
        CloseRunId run = readyRun(CloseCheckCode.RECONCILIATION_DISCLOSURE);
        Instant locked = period().lockedAt().orElseThrow();
        PeriodCloseId proposal = machine.propose(UOW, march, run, List.of(reasonFor(CloseCheckCode.RECONCILIATION_DISCLOSURE)), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();

        PeriodCloses.Acted rejected = machine.reject(UOW, march, proposal, ACCOUNTANT, "my own mistake", correlation());
        assertThat(rejected.status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(period().lockedAt()).contains(locked);
        assertThat(CloseRuns.readinessOf(runs.view(UOW, march)).run()).as("the same run").contains(run);
        assertThat(closes.closes().get(0).status()).isEqualTo(PeriodCloseStatus.REJECTED);
        assertThat(lastAudit(AccountingAuditAction.PERIOD_CLOSE_REJECTED).changeSummary().orElseThrow()).contains("byProposer=true");
        assertThat(machine.reject(UOW, march, proposal, ACCOUNTANT, "my own mistake", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.reject(UOW, march, proposal, CONTROLLER, "and mine", correlation())).as("another's repeat").isInstanceOf(AccountingPeriods.PeriodStale.class);

        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation())).as("reasons anew").isInstanceOf(AccountingReasons.ReasonRequired.class);
        PeriodCloses.Acted again = machine.propose(UOW, march, run, List.of(new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, 1, "written anew")), ACCOUNTANT, REASON, correlation());
        assertThat(again.status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);
        assertThat(closes.closes()).hasSize(2);
    }

    @Test
    @DisplayName("a rejection publishes nothing (the entry names three events); it stays history and audit")
    void aRejectionPublishesNothing() {
        PeriodCloseId proposal = proposed();
        int published = events.size();
        machine.reject(UOW, march, proposal, CONTROLLER, "not yet", correlation());
        assertThat(events).hasSize(published);
        assertThat(periods.history()).extracting(AccountingPeriodStore.EventRow::act).contains(AccountingPeriodStore.Act.CLOSE_REJECTED);
    }

    // ------------------------------------------------------------------ period.open.age

    @Test
    @DisplayName("period.open.age falls on close: the oldest unclosed moves on, and a reopen brings it back")
    void theOpenAgeFallsOnClose() {
        AccountingPeriods read = new AccountingPeriods(periods, new com.finapp.accounting.testing.InMemoryGlMappingStore(), cutoff, LockGrace.standard(), (uow, record) -> { },
                (uow, envelope, payload, mediaType) -> { }, IDS, Clock.fixed(Instant.parse("2097-06-20T09:00:00Z"), ZoneOffset.UTC), supersession);
        periods.databaseToday(java.time.LocalDate.parse("2097-06-20"));
        long before = read.overdueDays(UOW);
        assertThat(before).isPositive();

        PeriodCloseId proposal = proposed();
        assertThat(read.overdueDays(UOW)).as("proposed is not closed").isEqualTo(before);
        machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation());
        assertThat(read.overdueDays(UOW)).as("nothing unclosed").isZero();

        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation());
        assertThat(read.overdueDays(UOW)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ reopen

    @Test
    @DisplayName("#aReopenSupersedesAndDeletesNothing: the proposal moves nothing; the approval: FINAL runs superseded before the cutoff, back exactly one period, OPEN at revision + 1, lock and pin released; every row kept")
    void aReopenSupersedesAndDeletesNothing() {
        closed();
        PeriodCloses.Acted proposed = machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction was found", correlation());
        assertThat(proposed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closedThrough).contains(MARCH.atEndOfMonth());
        assertThat(order).isEmpty();
        assertThat(lastAudit(AccountingAuditAction.PERIOD_REOPEN_PROPOSED).reason()).contains("a correction was found");

        PeriodCloses.Acted acted = machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation());

        assertThat(acted.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(acted.revision()).isEqualTo(2);
        assertThat(period().revision()).isEqualTo(2);
        assertThat(period().lockedAt()).isEmpty();
        assertThat(period().pinned()).isEmpty();
        assertThat(order).as("the unlock's order: postings do not wait on the supersession").containsExactly("supersede", "cutoff");
        assertThat(closedThrough).as("no earlier period: unset").isEmpty();
        assertThat(lastEvent().eventType()).isEqualTo(PeriodCloses.REOPENED_EVENT);
        assertThat(new String(payloads.get(payloads.size() - 1), StandardCharsets.UTF_8)).contains("\"revision\":\"2\"", "\"supersededRuns\":\"3\"");
        AccountingPeriodStore.EventRow reopened = periods.history().stream().filter(row -> row.act() == AccountingPeriodStore.Act.REOPENED).findFirst().orElseThrow();
        assertThat(reopened.proposedBy()).contains(ACCOUNTANT.id());
        assertThat(reopened.revision()).isEqualTo(2);
        assertThat(lastAudit(AccountingAuditAction.PERIOD_REOPENED).changeSummary().orElseThrow()).contains("supersededRuns=3");
        assertThat(closes.closes()).as("nothing deleted").hasSize(1).first().satisfies(row -> assertThat(row.status()).isEqualTo(PeriodCloseStatus.APPROVED));
        assertThat(closes.reopens()).hasSize(1).first().satisfies(row -> assertThat(row.status()).isEqualTo(PeriodReopenStatus.APPROVED));
        assertThat(runStore.runs()).hasSize(1);
    }

    @Test
    @DisplayName("the cutoff moves back to the previous period's end when there is one - exactly one period")
    void theCutoffMovesBackOnePeriod() {
        closed();
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, APRIL, correlation()));
        lock(april);
        closeUp(april);

        machine.proposeReopen(UOW, april, ACCOUNTANT, Set.of(), "a correction", correlation());
        machine.approveReopen(UOW, april, read(april), CONTROLLER, Set.of(), "agreed", correlation());
        assertThat(closedThrough).contains(MARCH.atEndOfMonth());
    }

    @Test
    @DisplayName("#onlyTheLatest: not a period past which the next has left OPEN, at proposal and at approval; only a CLOSED one; the next merely OPEN is fine; no reopen proposed is stale")
    void onlyTheLatest() {
        closed();
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, APRIL, correlation()));

        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation())).as("none proposed").isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, april, ACCOUNTANT, Set.of(), "open", correlation())).as("not CLOSED").isInstanceOf(AccountingPeriods.PeriodStale.class);

        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        lock(april);
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation())).as("the next locked since").isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("latest closed");
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(order).as("nothing moved").isEmpty();

        closeUp(april);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, new Actor("accountant-2", ActorType.CUSTOMER), Set.of(), "again", correlation())).as("the next closed").isInstanceOf(AccountingPeriods.PeriodStale.class);
    }

    // ------------------------------------------------------------------ a reopen's end (P14-TSK-024, the owner's acceptance of P14-TSK-012's (d))

    /** The reopen a person read before acting (`P14-TSK-024`): the one pending, else the period's latest - what a repeat names - else none. */
    private PeriodReopenId read(AccountingPeriodId period) {
        return machine.pendingOf(UOW, period).reopen().map(PeriodCloseStore.ReopenRow::id)
                .or(() -> closes.reopens().stream().filter(reopen -> reopen.period().equals(period)).reduce((earlier, later) -> later).map(PeriodCloseStore.ReopenRow::id))
                .orElse(NONE_READ);
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1): each act on a pending reopen names the one read - withdrawn and another proposed, the approval, the rejection and the withdrawal naming the first are each RequestStale, nothing superseded or moved, the second still pending; naming the second, it is approved")
    void anActNamesTheReopenItRead() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        PeriodReopenId first = machine.pendingOf(UOW, march).reopen().orElseThrow().id();
        machine.withdrawReopen(UOW, march, first, ACCOUNTANT, "proposed in error", correlation());
        Actor dave = new Actor("accountant-2", ActorType.CUSTOMER);
        machine.proposeReopen(UOW, march, dave, Set.of(), "another correction", correlation());
        PeriodReopenId second = machine.pendingOf(UOW, march).reopen().orElseThrow().id();
        int revision = period().revision();

        assertThatThrownBy(() -> machine.approveReopen(UOW, march, first, CONTROLLER, Set.of(), "the one I read", correlation()))
                .as("Carol's approval, meant for the first, does not approve the second").isInstanceOfSatisfying(AccountingPeriods.RequestStale.class, stale -> {
                    assertThat(stale).hasMessageContaining("is not the one pending").hasMessageContaining(second.value().toString()).hasMessageContaining("nothing was changed");
                    assertThat(stale.summary()).isEqualTo("period=" + MARCH + ", act=period:reopen-approval");
                    assertThat(stale.action()).isEqualTo(AccountingAuditAction.REQUEST_STALE);
                });
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, first, CONTROLLER, "the one I read", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.RequestStale.class, stale -> assertThat(stale.summary()).endsWith("act=period:reopen-rejection"));
        assertThatThrownBy(() -> machine.withdrawReopen(UOW, march, first, dave, "the one I read", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.RequestStale.class, stale -> assertThat(stale.summary()).endsWith("act=period:reopen-withdrawal"));
        assertThat(order).as("nothing superseded, no cutoff moved").isEmpty();
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(period().revision()).isEqualTo(revision);
        assertThat(machine.pendingOf(UOW, march).reopen()).map(PeriodCloseStore.ReopenRow::id).as("the second still pending").contains(second);

        assertThat(machine.approveReopen(UOW, march, second, CONTROLLER, Set.of(), "the one I read", correlation()).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(machine.approveReopen(UOW, march, second, CONTROLLER, Set.of(), "again", correlation()).replayed()).as("the same approver's repeat, naming it, converges").isTrue();
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, first, CONTROLLER, Set.of(), "again", correlation())).as("naming another is no repeat")
                .isInstanceOf(AccountingPeriods.PeriodStale.class);
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1, M-2): the close's machine records a kept refusal under its own action - another person's withdrawal of a reopen, a request other than the one pending - FAILED, naming the period and the act")
    void aReopensKeptRefusalIsRecordedAsItself() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        PeriodReopenId pending = read(march);
        List<AccountingPeriods.RecordedRefusal> refused = new ArrayList<>();
        for (Runnable act : List.<Runnable>of(() -> machine.withdrawReopen(UOW, march, pending, CONTROLLER, "theirs", correlation()),
                () -> machine.rejectReopen(UOW, march, NONE_READ, CONTROLLER, "another", correlation()))) {
            try {
                act.run();
            } catch (AccountingPeriods.RecordedRefusal kept) {
                refused.add(kept);
            }
        }
        int before = audit.size();
        refused.forEach(kept -> machine.recordRefusal(UOW, kept, CONTROLLER, correlation()));
        assertThat(audit.subList(before, audit.size())).extracting(record -> record.operation().code() + " " + record.outcome() + " " + record.changeSummary().orElseThrow())
                .containsExactly("accounting.NotTheProposer FAILED period=" + MARCH + ", act=period:reopen-withdrawal", "accounting.RequestStale FAILED period=" + MARCH + ", act=period:reopen-rejection");
    }

    @Test
    @DisplayName("P14-TSK-024 (d): a second person rejects a pending reopen: REJECTED, the period still CLOSED at its revision, nothing superseded and the cutoff unmoved, nothing published; history names the proposer, the audit record its reason; the month free for a new proposal")
    void aSecondPersonRejectsAPendingReopen() {
        closed();
        int revision = period().revision();
        Optional<LocalDate> cut = closedThrough;
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        int published = events.size();

        PeriodCloses.Acted rejected = machine.rejectReopen(UOW, march, read(march), CONTROLLER, "the correction is a prior-period adjustment", correlation());

        assertThat(rejected.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(rejected.revision()).isEqualTo(revision);
        assertThat(rejected.replayed()).isFalse();
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(period().revision()).as("no revision moved (INV-ACC-10)").isEqualTo(revision);
        assertThat(order).as("nothing superseded, no cutoff moved").isEmpty();
        assertThat(closedThrough).isEqualTo(cut);
        assertThat(events).as("an end publishes nothing").hasSize(published);
        assertThat(closes.reopens()).singleElement().satisfies(reopen -> {
            assertThat(reopen.status()).isEqualTo(PeriodReopenStatus.REJECTED);
            assertThat(reopen.endedBy()).contains(CONTROLLER.id());
            assertThat(reopen.approvedBy()).isEmpty();
        });
        AccountingPeriodStore.EventRow history = periods.history().get(periods.history().size() - 1);
        assertThat(history.act()).isEqualTo(AccountingPeriodStore.Act.REOPEN_REJECTED);
        assertThat(history.from()).contains(PeriodStatus.CLOSED);
        assertThat(history.to()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(history.proposedBy()).contains(ACCOUNTANT.id());
        AuditRecord record = lastAudit(AccountingAuditAction.PERIOD_REOPEN_REJECTED);
        assertThat(record.actor()).isEqualTo(CONTROLLER);
        assertThat(record.reason()).contains("the correction is a prior-period adjustment");
        assertThat(record.changeSummary().orElseThrow()).contains("period=" + MARCH).contains("reopen=" + closes.reopens().get(0).id().value()).doesNotContain("prior-period adjustment");

        machine.proposeReopen(UOW, march, new Actor("accountant-2", ActorType.CUSTOMER), Set.of(), "another correction", correlation());
        assertThat(machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation()).status()).as("free for a new one, which carries its own effect").isEqualTo(PeriodStatus.OPEN);
        assertThat(closes.reopens()).extracting(PeriodCloseStore.ReopenRow::status).containsExactly(PeriodReopenStatus.REJECTED, PeriodReopenStatus.APPROVED);
    }

    @Test
    @DisplayName("P14-TSK-024 (d): four eyes as the acts: the proposer cannot reject their own reopen (SelfApprovalRefused) and withdraws it instead; nobody else withdraws it (NotTheProposer); a person; a reason")
    void aReopensProposerWithdrawsAndNeverRejects() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), ACCOUNTANT, "mine", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.SelfApprovalRefused.class, self -> assertThat(self.summary()).contains("act=period:reopen-rejection"));
        assertThatThrownBy(() -> machine.withdrawReopen(UOW, march, read(march), CONTROLLER, "theirs", correlation())).isInstanceOf(AccountingPeriods.NotTheProposer.class);
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), Actor.SYSTEM, "the platform", correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), CONTROLLER, "", correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThat(closes.reopens()).extracting(PeriodCloseStore.ReopenRow::status).containsExactly(PeriodReopenStatus.PROPOSED);

        assertThat(machine.withdrawReopen(UOW, march, read(march), ACCOUNTANT, "proposed in error", correlation()).status()).isEqualTo(PeriodStatus.CLOSED);

        assertThat(closes.reopens()).singleElement().satisfies(reopen -> {
            assertThat(reopen.status()).isEqualTo(PeriodReopenStatus.WITHDRAWN);
            assertThat(reopen.endedBy()).contains(ACCOUNTANT.id());
        });
        AccountingPeriodStore.EventRow history = periods.history().get(periods.history().size() - 1);
        assertThat(history.act()).isEqualTo(AccountingPeriodStore.Act.REOPEN_WITHDRAWN);
        assertThat(history.proposedBy()).as("the proposer named, who is the actor").contains(ACCOUNTANT.id());
        assertThat(lastAudit(AccountingAuditAction.PERIOD_REOPEN_WITHDRAWN).actor()).isEqualTo(ACCOUNTANT);
    }

    @Test
    @DisplayName("P14-TSK-024 (d): the same reopen ended twice converges for its ender and is refused by name to anyone else; the approval after an end is told it was REJECTED, an end after the approval that it was approved - in sequence here, serialised on the period row (the race is PeriodReopenDatabaseTest's)")
    void aReopensEndAndItsApprovalTellTheLoserByName() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        machine.rejectReopen(UOW, march, read(march), CONTROLLER, "not needed", correlation());

        assertThat(machine.rejectReopen(UOW, march, read(march), CONTROLLER, "not needed", correlation()).replayed()).as("the same person's repeat converges, writing nothing").isTrue();
        assertThat(closes.reopens()).hasSize(1);
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), OTHER_CONTROLLER, "me too", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("was REJECTED").hasMessageContaining("nothing was changed");
        assertThatThrownBy(() -> machine.withdrawReopen(UOW, march, read(march), ACCOUNTANT, "mine", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("was REJECTED");
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), OTHER_CONTROLLER, Set.of(), "late", correlation())).as("the approval's loser, told by name")
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("was REJECTED");
        assertThat(order).isEmpty();

        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "after all", correlation());
        machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation());
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), OTHER_CONTROLLER, "too late", correlation())).as("the end's loser, told by name")
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("the reopen named was approved");
        assertThatThrownBy(() -> machine.withdrawReopen(UOW, march, read(march), ACCOUNTANT, "too late", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("the reopen named was approved");
    }

    @Test
    @DisplayName("P14-TSK-024 (d): a reopen's end takes the period row FOR UPDATE before the reopen's, as the approval does, so the two serialise on the period row: the hermetic half; the race on ten connections is PeriodReopenDatabaseTest#aRejectionRacingTheApprovalOneWins, owed")
    void aReopensEndTakesThePeriodRow() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        int before = periods.rowLocks();
        machine.rejectReopen(UOW, march, read(march), CONTROLLER, "not needed", correlation());
        assertThat(periods.rowLocks()).as("the period row FOR UPDATE, and no neighbour").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("P14-TSK-024 (d): an end needs a pending reopen - a CLOSED month with none proposed, a LOCKED one - refused by name; a reopen stuck behind the next month's lock, which no approval can ever take, is ended, and only that")
    void aReopenStuckBehindTheNextLockIsEnded() {
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), CONTROLLER, "none", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("only a CLOSED period's pending reopen is ended");
        closed();
        assertThatThrownBy(() -> machine.rejectReopen(UOW, march, read(march), CONTROLLER, "none", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("no reopen of the period is pending");
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, APRIL, correlation()));
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        lock(april);
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("latest closed");

        assertThat(machine.rejectReopen(UOW, march, read(march), CONTROLLER, "it can never be approved", correlation()).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closes.pendingReopen(UOW, march)).as("nothing stands for good").isEmpty();
        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("P14-TSK-024 (-023's a): the approver's read names the reopen pending - none on a CLOSED month before a proposal or after an end - and its record says so and how many returns were listed, never a return")
    void theApproversReadNamesThePendingReopen() {
        closed();
        PeriodCloses.ProposalView before = machine.proposalOf(UOW, march).orElseThrow();
        assertThat(before.periodStatus()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(before.pendingReopen()).isEmpty();

        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        PeriodCloses.ProposalView pending = machine.proposalOf(UOW, march).orElseThrow();
        assertThat(pending.pendingReopen()).map(PeriodCloseStore.ReopenRow::id).contains(closes.reopens().get(0).id());
        assertThat(pending.stillStands()).as("a CLOSED month's approved proposal stands for nothing more").isFalse();
        machine.recordRead(UOW, pending, Optional.of(3L), CONTROLLER, correlation());
        assertThat(lastAudit(AccountingAuditAction.CLOSE_PROPOSAL_READ).changeSummary().orElseThrow()).contains("reopenPending=true").contains("overtakenReturns=3");

        machine.withdrawReopen(UOW, march, read(march), ACCOUNTANT, "proposed in error", correlation());
        assertThat(machine.proposalOf(UOW, march).orElseThrow().pendingReopen()).isEmpty();
    }

    @Test
    @DisplayName("P14-TSK-024 (-012's j): concludedAt is the frozen attempt's own, never a later one's: a check judged again after a rejection leaves the rejected proposal reading its attempt as of then, and the next proposal its new one")
    void concludedAtIsTheFrozenAttempts() {
        CloseRunId run = readyRun();
        PeriodCloseId first = machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();
        java.time.Instant judged = attemptOf(CloseCheckCode.SOURCE_COMPLETENESS).concludedAt().orElseThrow();
        machine.reject(UOW, march, first, CONTROLLER, "judge it again", correlation());
        runStore.advance(java.time.Duration.ofHours(6));
        runs.rerun(UOW, march, CloseCheckCode.SOURCE_COMPLETENESS, ACCOUNTANT, "judge it again", correlation());
        concludeAll(Map.of());
        java.time.Instant again = attemptOf(CloseCheckCode.SOURCE_COMPLETENESS).concludedAt().orElseThrow();
        assertThat(again).as("the suite's clock moved").isAfter(judged);

        PeriodCloses.ProposalView rejected = machine.proposalOf(UOW, march).orElseThrow();
        assertThat(rejected.checks()).filteredOn(check -> check.check().check() == CloseCheckCode.SOURCE_COMPLETENESS).singleElement()
                .satisfies(check -> assertThat(check.attempt().concludedAt()).as("as of the attempt the proposal froze").isEqualTo(judged));

        machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation());
        assertThat(machine.proposalOf(UOW, march).orElseThrow().checks()).filteredOn(check -> check.check().check() == CloseCheckCode.SOURCE_COMPLETENESS).singleElement()
                .satisfies(check -> assertThat(check.attempt().concludedAt()).isEqualTo(again));
    }

    // ------------------------------------------------------------------ what a reopen names (P14-TSK-022, the owner's ruling (m))

    /** An adjustment of {@code march} at its present revision; {@code posted} takes it to {@code POSTED}. */
    private UUID adjustment(boolean posted) {
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, APRIL, correlation()));
        AccountingPeriodId target = periods.lockShared(UOW, APRIL).orElseThrow().id();
        PriorPeriodAdjustmentId id = PriorPeriodAdjustmentId.next(IDS);
        priorPeriod.insert(UOW, new PriorPeriodAdjustmentStore.NewAdjustment(id, march, period().revision(), target, 1, APRIL.atDay(5), UUID.randomUUID(), ACCOUNTANT, "a correction", correlation()));
        if (posted) {
            assertThat(priorPeriod.markPosted(UOW, id, UUID.randomUUID(), CONTROLLER, "agreed")).isTrue();
        }
        return id.value();
    }

    @Test
    @DisplayName("a reopen's proposal names what has posted: naming none, or another, is the adjustment's own Stale with nothing written; only POSTED counts, never PROPOSED or REJECTED; the act's audit says how many")
    void aReopensProposalNamesWhatHasPosted() {
        closed();
        UUID posted = adjustment(true);
        adjustment(false);
        UUID rejected = adjustment(false);
        priorPeriod.rows().stream().filter(row -> row.id().value().equals(rejected)).findFirst().ifPresent(row -> priorPeriod.markRejected(UOW, row.id(), CONTROLLER, "no"));

        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation())).as("none named").isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class).hasMessageContaining("1 stand");
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(rejected), "a correction", correlation())).as("a rejected one corrected nothing").isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(posted, UUID.randomUUID()), "a correction", correlation())).as("one too many").isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThat(closes.reopens()).as("nothing written").isEmpty();

        PeriodCloses.Acted acted = machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(posted), "a correction", correlation());

        assertThat(acted.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closes.reopens()).hasSize(1);
        assertThat(lastAudit(AccountingAuditAction.PERIOD_REOPEN_PROPOSED).changeSummary().orElseThrow()).contains("postedAdjustments=1").doesNotContain(posted.toString());
    }

    @Test
    @DisplayName("the owner's (n): an adjustment posted while a reopen is pending; the approval is compared with what stands now, never with the proposal: naming the proposal's list is Stale with nothing moved, naming what stands lands; the same approver's repeat converges without re-judging")
    void aReopensApprovalNamesWhatStandsNow() {
        closed();
        UUID first = adjustment(true);
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(first), "a correction", correlation());
        UUID second = adjustment(true);
        assertThat(machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(first), "a correction", correlation()).replayed()).as("the proposer's repeat converges: it re-judges nothing").isTrue();

        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(first), "agreed", correlation())).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class).hasMessageContaining("2 stand");
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(period().revision()).isEqualTo(1);
        assertThat(order).as("refused before the runs and the cutoff").isEmpty();

        PeriodCloses.Acted reopened = machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(first, second), "agreed", correlation());

        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(order).containsExactly("supersede", "cutoff");
        assertThat(lastAudit(AccountingAuditAction.PERIOD_REOPENED).changeSummary().orElseThrow()).contains("postedAdjustments=2");
        assertThat(machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation()).replayed()).as("the approver's repeat converges").isTrue();
    }

    @Test
    @DisplayName("every revision's adjustments are named: one posted at revision 1 still stands against the month at revision 2, because the month's figures at the next revision still show what it corrected")
    void everyRevisionsAdjustmentsAreNamed() {
        closed();
        UUID earlier = adjustment(true);
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(earlier), "a correction", correlation());
        machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(earlier), "agreed", correlation());
        assertThat(period().revision()).isEqualTo(2);
        lock(march);
        CloseRunId second = runs.startMissing(UOW, Actor.SYSTEM).get(0);
        concludeAll(Map.of());
        PeriodCloseId again = machine.propose(UOW, march, second, List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();
        machine.approve(UOW, march, again, CONTROLLER, REASON, correlation());

        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "again", correlation())).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThat(machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(earlier), "again", correlation()).revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("a reopen's four eyes: refused to its proposer, recorded FAILED, nothing moved; the row's and the history's rank beneath; another person's proposal stands and a repeat converges")
    void theProposerCannotApproveAReopen() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        assertThat(machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, new Actor("accountant-2", ActorType.CUSTOMER), Set.of(), "mine", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "another reason", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);

        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), ACCOUNTANT, Set.of(), "agreed", correlation())).isInstanceOfSatisfying(AccountingPeriods.SelfApprovalRefused.class,
                refused -> assertThat(refused.summary()).contains("act=period:reopen"));
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(order).isEmpty();
        assertThatThrownBy(() -> closes.approveReopen(UOW, closes.reopens().get(0).id(), ACCOUNTANT, "agreed")).hasMessageContaining("period_reopen_four_eyes");
    }

    @Test
    @DisplayName("two approvers of a reopen, one edge: the same approver's repeat converges, writing nothing; another finds it stale")
    void twoReopenApprovalsOneEdge() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation());
        int published = events.size();

        assertThat(machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), OTHER_CONTROLLER, Set.of(), "agreed", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThat(events).hasSize(published);
        assertThat(period().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("postings held the cutoff: the act is the ledger's bounded refusal, CutoffBusy, retryable; the caller's transaction rolls it all back")
    void aBusyCutoffRefuses() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        cutoffBusy = true;
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation())).isInstanceOf(PostingCutoff.CutoffBusy.class);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("a re-close is a new lock, so a new run at the new revision; the rejected, approved and reopened rows all stand, and the close record of revision 1 is untouched")
    void aReCloseIsANewRun() {
        closed();
        CloseRunId first = runStore.runs().get(0).id();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation());

        lock(march);
        assertThat(CloseRuns.readinessOf(runs.view(UOW, march)).run()).as("the old run does not answer the new lock").isEmpty();
        assertThatThrownBy(() -> machine.propose(UOW, march, first, List.of(), ACCOUNTANT, REASON, correlation())).isInstanceOf(PeriodCloses.CloseNotReady.class);

        CloseRunId second = runs.startMissing(UOW, Actor.SYSTEM).get(0);
        assertThat(second).isNotEqualTo(first);
        assertThat(runStore.runs()).extracting(CloseRunStore.RunRow::periodRevision).containsExactly(1, 2);
        concludeAll(Map.of());
        PeriodCloseId again = machine.propose(UOW, march, second, List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();
        assertThat(machine.approve(UOW, march, again, CONTROLLER, REASON, correlation()).revision()).isEqualTo(2);
        assertThat(closes.closes()).extracting(PeriodCloseStore.CloseRow::revision).containsExactly(1, 2);
        assertThat(closes.reopens()).hasSize(1);
        assertThat(machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "once more", correlation()).status()).as("the next revision has its own").isEqualTo(PeriodStatus.CLOSED);
        assertThat(closes.reopens()).extracting(PeriodCloseStore.ReopenRow::revision).containsExactly(1, 2);
    }

    @Test
    @DisplayName("the gauge reads the machine: a rejected close returns LOCKED with its run; CLOSE_PROPOSED, CLOSED and reopened are not stalled; the next lock is, past the bound, until its run")
    void theStalledGaugeThroughTheMachine() {
        assertThat(runStore.stalledLocks(UOW, Duration.ZERO)).as("LOCKED with no run").isEqualTo(1);
        PeriodCloseId proposal = proposed();
        assertThat(runStore.stalledLocks(UOW, Duration.ZERO)).as("CLOSE_PROPOSED").isZero();
        machine.reject(UOW, march, proposal, CONTROLLER, "not yet", correlation());
        assertThat(runStore.stalledLocks(UOW, Duration.ZERO)).as("LOCKED again, its run stands").isZero();
        PeriodCloseId again = machine.propose(UOW, march, closes.closes().get(0).run(), List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();
        machine.approve(UOW, march, again, CONTROLLER, REASON, correlation());
        assertThat(runStore.stalledLocks(UOW, Duration.ZERO)).as("CLOSED").isZero();
        machine.proposeReopen(UOW, march, ACCOUNTANT, Set.of(), "a correction", correlation());
        machine.approveReopen(UOW, march, read(march), CONTROLLER, Set.of(), "agreed", correlation());
        assertThat(runStore.stalledLocks(UOW, Duration.ZERO)).as("reopened").isZero();
        lock(march);
        assertThat(runStore.stalledLocks(UOW, Duration.ZERO)).as("the new lock has no run").isEqualTo(1);
    }

    @Test
    @DisplayName("an actor who is no person is refused every act")
    void onlyAPersonActs() {
        assertThatThrownBy(() -> machine.propose(UOW, march, CloseRunId.next(IDS), List.of(), Actor.SYSTEM, REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.approve(UOW, march, PeriodCloseId.next(IDS), Actor.SYSTEM, REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.reject(UOW, march, PeriodCloseId.next(IDS), Actor.SYSTEM, REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, Actor.SYSTEM, Set.of(), REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, read(march), Actor.SYSTEM, Set.of(), REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ plumbing

    private AccountingPeriodStore.PeriodRow period() {
        return periods.find(UOW, march).orElseThrow();
    }

    private void lock(AccountingPeriodId id) {
        AccountingPeriodStore.PeriodRow row = periods.find(UOW, id).orElseThrow();
        periods.markLocked(UOW, id, row.revision(), ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(GlMappingVersionId.next(IDS), 1)));
        closedThrough = Optional.of(row.end());
    }

    private CloseRunId started() {
        return runs.startMissing(UOW, Actor.SYSTEM).get(0);
    }

    /** The period's run started, a set approved, every attempt concluded {@code PASSED} but those named {@code DISCLOSED}. */
    private CloseRunId readyRun(CloseCheckCode... disclosed) {
        CloseRunId run = started();
        approveASet(march);
        Map<CloseCheckCode, CloseCheckStatus> statuses = new java.util.EnumMap<>(CloseCheckCode.class);
        for (CloseCheckCode check : disclosed) {
            statuses.put(check, CloseCheckStatus.DISCLOSED);
        }
        concludeAll(statuses);
        return run;
    }

    private void approveASet(AccountingPeriodId id) {
        if (setStore.approvedFor(UOW, id).isPresent()) {
            return;
        }
        ValuationRateSets.Valued captured = sets.capture(UOW, id, ACCOUNTANT, null, correlation());
        sets.approve(UOW, captured.id(), CONTROLLER, "the rates agree", correlation());
    }

    private void concludeAll(Map<CloseCheckCode, CloseCheckStatus> statuses) {
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            CloseRuns.ClaimedCheck claimed = next.get();
            if (claimed.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
                runs.concludeOpenBreaks(UOW, claimed);
                continue;
            }
            CanonicalJson.Obj evidence = CanonicalJson.object().put("attempt", claimed.attempt()).put("check", claimed.check().name());
            CheckVerdict verdict = switch (statuses.getOrDefault(claimed.check(), CloseCheckStatus.PASSED)) {
                case DISCLOSED -> CheckVerdict.disclosed(claimed.check(), "judge:v1", evidence);
                case FAILED -> CheckVerdict.failed(claimed.check(), "judge:v1", List.of("SOMETHING"), evidence);
                default -> CheckVerdict.passed(claimed.check(), "judge:v1", evidence);
            };
            runs.conclude(UOW, claimed, verdict);
        }
    }

    @Test
    @DisplayName("the independent review, M-3: no reason and no evidence in a text form, whichever record a log line were handed")
    void aRecordsTextHoldsNoReasonAndNoEvidence() {
        String reason = "a-persons-reason";
        String evidence = "{\"attempts-evidence\":\"1\"}";
        PeriodCloseId id = PeriodCloseId.next(IDS);
        PeriodCloseStore.CheckRow disclosed = new PeriodCloseStore.CheckRow(id, CloseCheckCode.RECONCILIATION_DISCLOSURE, CloseCheckId.next(IDS), 1, CloseCheckStatus.DISCLOSED, "judge:v1", Optional.of(evidence), Optional.of(reason));
        Actor person = ACCOUNTANT;
        AccountingPeriodId period = AccountingPeriodId.next(IDS);
        CloseRunId run = CloseRunId.next(IDS);
        PeriodCloseStore.CloseRow row = new PeriodCloseStore.CloseRow(id, period, MARCH, 1, Instant.EPOCH, run, GlMappingVersionId.next(IDS), ValuationRateSetId.next(IDS), 0, 0, PeriodCloseStatus.PROPOSED, "someone",
                Instant.EPOCH, reason, Optional.empty(), Optional.empty(), correlation());
        PeriodCloseStore.FrozenAttempt frozen = new PeriodCloseStore.FrozenAttempt(evidence, Instant.EPOCH);
        PeriodCloseStore.ReopenRow reopen = new PeriodCloseStore.ReopenRow(PeriodReopenId.next(IDS), period, 1, PeriodReopenStatus.PROPOSED, "someone", Instant.EPOCH, reason, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        for (Object record : List.of(new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, 1, reason), disclosed, new PeriodCloses.CheckView(disclosed, frozen), frozen, row,
                new PeriodCloses.ProposalView(row, PeriodStatus.CLOSE_PROPOSED, List.of(new PeriodCloses.CheckView(disclosed, frozen)), true, Optional.of(reopen)),
                new PeriodCloseStore.NewClose(id, period, 1, Instant.EPOCH, run, GlMappingVersionId.next(IDS), ValuationRateSetId.next(IDS), 0, 0, person, reason, correlation()),
                new PeriodCloseStore.NewReopen(PeriodReopenId.next(IDS), period, 1, person, reason, correlation()), reopen,
                new AccountingPeriodStore.NewUnlockEnd(UUID.randomUUID(), period, 1, "someone", PeriodRequestEnd.REJECTED, person, reason, correlation()))) {
            assertThat(record.toString()).as(record.getClass().getSimpleName()).doesNotContain(reason).doesNotContain("attempts-evidence");
        }
    }

    private static ReconciliationRunCoverage.Line pspLine(boolean repudiated) {
        return new ReconciliationRunCoverage.Line(PSP, "BATCH", "COMPLETED", MARCH.atDay(2), repudiated, 1);
    }

    private CloseRunStore.AttemptRow attemptOf(CloseCheckCode check) {
        return runs.view(UOW, march).orElseThrow().checks().stream().filter(view -> view.check() == check).findFirst().orElseThrow().latest();
    }

    private PeriodCloses.Disclosure reasonFor(CloseCheckCode check) {
        return new PeriodCloses.Disclosure(check, 1, DISCLOSURE);
    }

    private PeriodCloseId proposed() {
        return machine.propose(UOW, march, readyRun(), List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();
    }

    private void closed() {
        machine.approve(UOW, march, proposed(), CONTROLLER, REASON, correlation());
    }

    /** {@code id}, already locked: run, set, checks, proposal and approval. */
    private void closeUp(AccountingPeriodId id) {
        CloseRunId run = runs.startMissing(UOW, Actor.SYSTEM).get(0);
        approveASet(id);
        concludeAll(Map.of());
        PeriodCloseId proposal = machine.propose(UOW, id, run, List.of(), ACCOUNTANT, REASON, correlation()).proposal().orElseThrow();
        machine.approve(UOW, id, proposal, CONTROLLER, REASON, correlation());
    }

    private void raise(String subject, String evidence) {
        breakMachine.raise(UOW, MARCH, new BreakFinding(CloseCheckCode.SOURCE_COMPLETENESS, BreakType.SOURCE_DISCREPANCY, subject, evidence, SourceCompleteness.JUDGED_BY), Actor.SYSTEM, correlation());
    }

    private EventEnvelope lastEvent() {
        return events.get(events.size() - 1);
    }

    private AuditRecord lastAudit(AccountingAuditAction action) {
        return audit.stream().filter(record -> record.operation() == action).reduce((earlier, later) -> later).orElseThrow();
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** The reference: one valued pair, observed the day before the cutoff. */
    private static final class OneRate implements ValuationRates {

        private static final CurrencyCode USD = CurrencyCode.of("USD");

        @Override
        public List<CurrencyCode> valued() {
            return List.of(USD);
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            return List.of(new Observed(USD, Optional.of(new Observation(UUID.randomUUID(), "simulated-reference", CurrencyCode.of("EUR"), USD, new BigDecimal("1.1734000000"),
                    before.minusSeconds(3_600), before.minusSeconds(3_598)))));
        }
    }
}
