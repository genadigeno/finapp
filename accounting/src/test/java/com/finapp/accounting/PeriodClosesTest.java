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
    private static final java.util.UUID PSP = java.util.UUID.nameUUIDFromBytes(new byte[] {7});
    private static final String DISCLOSURE = "suspense of two items, aged under a week, explained by the bank's holiday";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore runStore = new InMemoryCloseRunStore(periods);
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final InMemoryValuationRateSetStore setStore = new InMemoryValuationRateSetStore().watching(periods);
    private final InMemoryPeriodCloseStore closes = new InMemoryPeriodCloseStore(periods);
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
    private final ReconciliationRunCoverage coverage = (connection, month) -> new ReconciliationRunCoverage.Coverage(month, monthLines,
            List.of(new ReconciliationRunCoverage.Source(PSP, "simulated-psp.settlement", ReconciliationRunCoverage.SourceState.ACTIVE)));

    private final PeriodCloses machine = new PeriodCloses(periods, closes, runs, breaks, setStore, coverage, cutoff, supersession, (uow, record) -> audit.add(record),
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
    @DisplayName("an unlock pending refuses the proposal - V003's CHECK could not hold it past LOCKED - and the way on is to approve the unlock and lock again, never a wedge")
    void aPendingUnlockRefusesTheProposal() {
        CloseRunId run = readyRun();
        periods.proposeUnlock(UOW, march, period().revision(), ACCOUNTANT, "a mistake was found");
        assertThatThrownBy(() -> machine.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("unlock");
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
        }, cutoff, supersession, (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> events.add(envelope), IDS, CLOCK);
        assertThatThrownBy(() -> unreadable.propose(UOW, march, run, List.of(), ACCOUNTANT, REASON, correlation())).isInstanceOf(IllegalStateException.class);
        PeriodCloses noSource = new PeriodCloses(periods, closes, runs, breaks, setStore, (connection, month) -> new ReconciliationRunCoverage.Coverage(month, List.of(), List.of()), cutoff, supersession,
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
        closes.attemptEvidenceIs(CloseCheckCode.PROJECTION_VERIFIED, "{\"complete\":true}");

        PeriodCloses.ProposalView view = machine.proposalOf(UOW, march).orElseThrow();
        assertThat(view.stillStands()).isTrue();
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length);
        assertThat(view.checks()).filteredOn(check -> check.check().check() == CloseCheckCode.PROJECTION_VERIFIED).singleElement()
                .satisfies(check -> assertThat(check.attemptEvidence()).contains("{\"complete\":true}"));
        assertThat(view.checks()).filteredOn(check -> check.check().check() == CloseCheckCode.RECONCILIATION_DISCLOSURE).singleElement()
                .satisfies(check -> assertThat(check.check().disclosureReason()).contains(DISCLOSURE));

        machine.recordRead(UOW, view, CONTROLLER, correlation());
        AuditRecord read = lastAudit(AccountingAuditAction.CLOSE_PROPOSAL_READ);
        assertThat(read.changeSummary().orElseThrow()).contains("stillStands=true").doesNotContain(DISCLOSURE);

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
        long before = read.overdueDays(UOW);
        assertThat(before).isPositive();

        PeriodCloseId proposal = proposed();
        assertThat(read.overdueDays(UOW)).as("proposed is not closed").isEqualTo(before);
        machine.approve(UOW, march, proposal, CONTROLLER, REASON, correlation());
        assertThat(read.overdueDays(UOW)).as("nothing unclosed").isZero();

        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation());
        assertThat(read.overdueDays(UOW)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ reopen

    @Test
    @DisplayName("#aReopenSupersedesAndDeletesNothing: the proposal moves nothing; the approval: FINAL runs superseded before the cutoff, back exactly one period, OPEN at revision + 1, lock and pin released; every row kept")
    void aReopenSupersedesAndDeletesNothing() {
        closed();
        PeriodCloses.Acted proposed = machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction was found", correlation());
        assertThat(proposed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closedThrough).contains(MARCH.atEndOfMonth());
        assertThat(order).isEmpty();
        assertThat(lastAudit(AccountingAuditAction.PERIOD_REOPEN_PROPOSED).reason()).contains("a correction was found");

        PeriodCloses.Acted acted = machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation());

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

        machine.proposeReopen(UOW, april, ACCOUNTANT, "a correction", correlation());
        machine.approveReopen(UOW, april, CONTROLLER, "agreed", correlation());
        assertThat(closedThrough).contains(MARCH.atEndOfMonth());
    }

    @Test
    @DisplayName("#onlyTheLatest: not a period past which the next has left OPEN, at proposal and at approval; only a CLOSED one; the next merely OPEN is fine; no reopen proposed is stale")
    void onlyTheLatest() {
        closed();
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, APRIL, correlation()));

        assertThatThrownBy(() -> machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation())).as("none proposed").isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, april, ACCOUNTANT, "open", correlation())).as("not CLOSED").isInstanceOf(AccountingPeriods.PeriodStale.class);

        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        lock(april);
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation())).as("the next locked since").isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("latest closed");
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(order).as("nothing moved").isEmpty();

        closeUp(april);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, new Actor("accountant-2", ActorType.CUSTOMER), "again", correlation())).as("the next closed").isInstanceOf(AccountingPeriods.PeriodStale.class);
    }

    @Test
    @DisplayName("a reopen's four eyes: refused to its proposer, recorded FAILED, nothing moved; the row's and the history's rank beneath; another person's proposal stands and a repeat converges")
    void theProposerCannotApproveAReopen() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        assertThat(machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, new Actor("accountant-2", ActorType.CUSTOMER), "mine", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, ACCOUNTANT, "another reason", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);

        assertThatThrownBy(() -> machine.approveReopen(UOW, march, ACCOUNTANT, "agreed", correlation())).isInstanceOfSatisfying(AccountingPeriods.SelfApprovalRefused.class,
                refused -> assertThat(refused.summary()).contains("act=period:reopen"));
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(order).isEmpty();
        assertThatThrownBy(() -> closes.approveReopen(UOW, closes.reopens().get(0).id(), ACCOUNTANT, "agreed")).hasMessageContaining("period_reopen_four_eyes");
    }

    @Test
    @DisplayName("two approvers of a reopen, one edge: the same approver's repeat converges, writing nothing; another finds it stale")
    void twoReopenApprovalsOneEdge() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation());
        int published = events.size();

        assertThat(machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, OTHER_CONTROLLER, "agreed", correlation())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThat(events).hasSize(published);
        assertThat(period().revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("postings held the cutoff: the act is the ledger's bounded refusal, CutoffBusy, retryable; the caller's transaction rolls it all back")
    void aBusyCutoffRefuses() {
        closed();
        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        cutoffBusy = true;
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation())).isInstanceOf(PostingCutoff.CutoffBusy.class);
        assertThat(period().status()).isEqualTo(PeriodStatus.CLOSED);
    }

    @Test
    @DisplayName("a re-close is a new lock, so a new run at the new revision; the rejected, approved and reopened rows all stand, and the close record of revision 1 is untouched")
    void aReCloseIsANewRun() {
        closed();
        CloseRunId first = runStore.runs().get(0).id();
        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation());

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
        assertThat(machine.proposeReopen(UOW, march, ACCOUNTANT, "once more", correlation()).status()).as("the next revision has its own").isEqualTo(PeriodStatus.CLOSED);
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
        machine.proposeReopen(UOW, march, ACCOUNTANT, "a correction", correlation());
        machine.approveReopen(UOW, march, CONTROLLER, "agreed", correlation());
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
        assertThatThrownBy(() -> machine.proposeReopen(UOW, march, Actor.SYSTEM, REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.approveReopen(UOW, march, Actor.SYSTEM, REASON, correlation())).isInstanceOf(IllegalArgumentException.class);
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
