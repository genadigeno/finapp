package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore;
import com.finapp.accounting.testing.InMemoryPriorPeriodPostings;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * The prior-period adjustment's machine (`P14-TSK-013`; ADR-0114 decision 5; {@code PHASE_14_PLAN.md} sections 8.1 T-ppa, 13.5; {@code INV-ACC-10}, {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-LED-01}), hermetic: locks are counted, not held, so a
 * <em>sequence</em> here is what two racers meet once the row has serialised them, and the real races are {@code PriorPeriodAdjustmentDatabaseTest}'s (owed, C5). The ledger's half is {@link InMemoryPriorPeriodPostings}, whose cutoff refuses a date it covers as the
 * ledger would.
 */
@DisplayName("the prior-period adjustment's machine (P14-TSK-013)")
class PriorPeriodAdjustmentsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth JANUARY = YearMonth.of(2026, 1);
    private static final YearMonth FEBRUARY = YearMonth.of(2026, 2);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final LocalDate TODAY = MARCH.atDay(10);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final Actor OTHER = new Actor("controller-2", ActorType.CUSTOMER);
    private static final CorrelationId FLOW = CorrelationId.of("flow-1");
    private static final String REASON = "the secret reason: a fee was booked twice";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore(Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    private final InMemoryPriorPeriodAdjustmentStore store = new InMemoryPriorPeriodAdjustmentStore(periods);
    private final InMemoryPriorPeriodPostings ledger = new InMemoryPriorPeriodPostings();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<String> payloads = new ArrayList<>();
    private UUID wallet;
    private UUID fees;

    private PriorPeriodAdjustments machine() {
        return machine(store, ledger, periods, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    /** The machine as of {@code clock}: this instance's clock and the database's agree unless a case then sets the database's apart. */
    private PriorPeriodAdjustments machine(PriorPeriodAdjustmentStore adjustments, PriorPeriodPostings postings, AccountingPeriodStore periodStore, Clock clock) {
        periods.databaseToday(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        return new PriorPeriodAdjustments(periodStore, adjustments, postings, mappings, (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(new String(payload, StandardCharsets.UTF_8));
        }, IDS, clock);
    }

    @BeforeEach
    void januaryClosedFebruaryAndMarchOpen() {
        wallet = ledger.account("CUSTOMER_WALLET", "CUSTOMER");
        fees = ledger.account("FEE_REVENUE", "OPERATIONAL");
        open(JANUARY);
        open(FEBRUARY);
        open(MARCH);
        periods.closeForTest(JANUARY);
        activate(ReferenceGlMapping.version(JANUARY));
    }

    private void open(YearMonth month) {
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(AccountingPeriodId.next(IDS), month, FLOW));
    }

    private void activate(GlMappingVersion content) {
        GlMappingVersionId id = GlMappingVersionId.next(IDS);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(id, 1, content, ACCOUNTANT, "the reference", FLOW, UUID.randomUUID()));
        assertThat(mappings.decide(null, id, GlMappingVersionStatus.ACTIVE, CONTROLLER, "approved")).isTrue();
    }

    private AccountingPeriodId id(YearMonth month) {
        return periods.month(month).orElseThrow().id();
    }

    private List<PriorPeriodPostings.Line> lines() {
        return List.of(line(fees, Side.DEBIT, 1500), line(wallet, Side.CREDIT, 1500));
    }

    private static PriorPeriodPostings.Line line(UUID account, Side side, long minor) {
        return new PriorPeriodPostings.Line(account, side, Money.ofMinorUnits(minor, EUR));
    }

    private PriorPeriodAdjustments.Acted propose() {
        return machine().propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW);
    }

    private PriorPeriodAdjustments.Approval reading(PriorPeriodAdjustments.Acted proposed, UUID... posted) {
        return new PriorPeriodAdjustments.Approval(proposed.postingDate(), proposed.correctedRevision(), Set.of(posted));
    }

    private PriorPeriodAdjustments.Acted approve(PriorPeriodAdjustments.Acted proposed, Actor approver, UUID... posted) {
        return machine().approve(UOW, proposed.id(), reading(proposed, posted), approver, "read and agreed", FLOW);
    }

    private PriorPeriodAdjustmentStore.Row row(PriorPeriodAdjustments.Acted acted) {
        return store.find(UOW, acted.id()).orElseThrow();
    }

    /** Nothing written anywhere since {@code before}: no row, no ledger proposal, no record. */
    private void nothingProposed() {
        assertThat(store.rows()).isEmpty();
        assertThat(ledger.proposals()).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("it posts in the open month referencing the closed one: dated today, in the ledger's one date, the row and the proposal moving together; the entry named; both acts audited; one event, ids and codes only")
    void postsInTheOpenPeriodReferencingTheClosedOne() {
        PriorPeriodAdjustments.Acted proposed = propose();

        assertThat(proposed.status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
        assertThat(proposed.correctedMonth()).isEqualTo(JANUARY);
        assertThat(proposed.correctedRevision()).isEqualTo(1);
        assertThat(proposed.targetMonth()).isEqualTo(MARCH);
        assertThat(proposed.postingDate()).isEqualTo(TODAY);
        assertThat(proposed.entry()).isEmpty();
        InMemoryPriorPeriodPostings.Held held = ledger.proposal(proposed.proposal());
        assertThat(held.date()).as("the entry's one date, today, never into the month it corrects").isEqualTo(TODAY).isAfter(JANUARY.atEndOfMonth());
        assertThat(held.status()).as("nothing posts at the proposal").isEqualTo("PROPOSED");
        assertThat(held.reference()).as("served on a customer's and a merchant's statement line: no adjustment, month or proposal in it").isEqualTo(PriorPeriodAdjustments.ENTRY_REFERENCE)
                .doesNotContain(proposed.id().value().toString()).doesNotContain(proposed.proposal().toString()).doesNotContain("2026").doesNotContain("period");
        assertThat(held.reason()).as("the ledger's reason is identifiers only; the person's stays on accounting's row")
                .contains(proposed.id().value().toString()).contains("2026-01").doesNotContain("secret");
        assertThat(row(proposed).proposalReason()).isEqualTo(REASON);
        assertThat(events).as("a proposal publishes nothing").isEmpty();

        PriorPeriodAdjustments.Acted posted = approve(proposed, CONTROLLER);

        assertThat(posted.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        assertThat(posted.replayed()).isFalse();
        assertThat(posted.entry()).isEqualTo(ledger.proposal(proposed.proposal()).entry()).isPresent();
        assertThat(row(proposed).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        assertThat(row(proposed).entry()).isEqualTo(posted.entry());
        assertThat(row(proposed).correctedPeriod()).as("traced to the period it corrects").isEqualTo(id(JANUARY));
        assertThat(audit).extracting(record -> record.operation().code())
                .containsExactly("accounting.PriorPeriodAdjustmentProposed", "accounting.PriorPeriodAdjustmentPosted");
        assertThat(audit).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetType()).isEqualTo(PriorPeriodAdjustments.TARGET_TYPE);
            assertThat(record.targetId()).isEqualTo(proposed.id().value().toString());
            assertThat(record.changeSummary().orElseThrow()).doesNotContain("secret").doesNotContain("1500").doesNotContain("15.00");
        });
        assertThat(audit.get(0).reason()).contains(REASON);
        assertThat(audit.get(1).reason()).contains("read and agreed");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(PriorPeriodAdjustments.POSTED_EVENT);
            assertThat(event.aggregateId()).isEqualTo(proposed.id());
        });
        assertThat(payloads.get(0)).contains("\"correctedPeriod\"").contains("2026-01").contains("\"targetPeriod\"").contains("2026-03").contains("\"journalEntry\"")
                .doesNotContain("secret").doesNotContain("1500").doesNotContain("accountant-1").doesNotContain("controller-1");
    }

    // ------------------------------------------------------------------ which month is corrected, and where the entry lands


    @Test
    @DisplayName("a reopened month is the first open one and not where it lands: January closed, February reopened, today in March, it lands in March")
    void itLandsInTheMonthHoldingToday() {
        periods.closeForTest(FEBRUARY);
        periods.markReopened(UOW, id(FEBRUARY), 1);

        assertThat(periods.firstOpen(UOW)).map(AccountingPeriodStore.PeriodRow::month).contains(FEBRUARY);
        PriorPeriodAdjustments.Acted proposed = propose();

        assertThat(proposed.targetMonth()).isEqualTo(MARCH);
        assertThat(proposed.postingDate()).isEqualTo(TODAY);
        assertThat(row(proposed).targetPeriod()).isEqualTo(id(MARCH));
    }

    @Test
    @DisplayName("a month with no row yet is refused and births nothing: the schedule opens it")
    void aMonthWithNoRowIsRefused() {
        Clock april = Clock.fixed(Instant.parse("2026-04-01T00:05:00Z"), ZoneOffset.UTC);
        int rows = periods.all().size();

        assertThatThrownBy(() -> machine(store, ledger, periods, april).propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("has no period yet");

        assertThat(periods.all()).hasSize(rows);
        nothingProposed();
    }

    @Test
    @DisplayName("the month a correction lands in, and its date, are the database's whatever this instance's clock reads: an instance a month ahead lands it where the database stands, and one behind cannot land it in a month the database has left (P14-REM-009)")
    void theMonthIsTheDatabases() {
        Clock april = Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);
        PriorPeriodAdjustments ahead = machine(store, ledger, periods, april);
        periods.databaseToday(TODAY);

        PriorPeriodAdjustments.Acted proposed = ahead.propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW);

        assertThat(proposed.targetMonth()).as("April has no row: on this instance's clock it was refused").isEqualTo(MARCH);
        assertThat(proposed.postingDate()).isEqualTo(TODAY);

        Clock february = Clock.fixed(Instant.parse("2026-02-27T09:00:00Z"), ZoneOffset.UTC);
        PriorPeriodAdjustments behind = machine(store, ledger, periods, february);
        periods.databaseToday(LocalDate.of(2026, 4, 1));
        assertThatThrownBy(() -> behind.propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW)).isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("has no period yet");
    }

    @Test
    @DisplayName("the date is read holding the corrected month's row, and names the target's: corrected FOR SHARE, the date, target FOR SHARE (P14-REM-009)")
    void theDateIsReadHoldingTheCorrectedRow() {
        List<String> order = new ArrayList<>();
        PriorPeriodAdjustments recording = machine(recording(PriorPeriodAdjustmentStore.class, store, order), recording(PriorPeriodPostings.class, ledger, order), recording(AccountingPeriodStore.class, periods, order),
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        recording.propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW);

        assertThat(order).containsSubsequence("lockShared(2026-01)", "today", "lockShared(2026-03)");
    }

    @Test
    @DisplayName("a clock far ahead finds no row; a clock behind, in or before the corrected month, is refused: never in or before what it corrects")
    void neverInOrBeforeTheCorrectedMonth() {
        Clock future = Clock.fixed(Instant.parse("2030-01-15T09:00:00Z"), ZoneOffset.UTC);
        Clock inJanuary = Clock.fixed(Instant.parse("2026-01-20T09:00:00Z"), ZoneOffset.UTC);
        Clock beforeIt = Clock.fixed(Instant.parse("2025-12-20T09:00:00Z"), ZoneOffset.UTC);
        for (Clock clock : List.of(future, inJanuary, beforeIt)) {
            assertThatThrownBy(() -> machine(store, ledger, periods, clock).propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                    .isInstanceOf(AccountingPeriods.PeriodStale.class);
        }
        nothingProposed();
    }

    @Test
    @DisplayName("the month holding today not OPEN is refused: a clock behind reaching a locked month")
    void theTargetMustBeOpen() {
        periods.markLocked(UOW, id(FEBRUARY), 1, ACCOUNTANT, Optional.empty());
        Clock inFebruary = Clock.fixed(Instant.parse("2026-02-27T09:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> machine(store, ledger, periods, inFebruary).propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("OPEN");
        nothingProposed();
    }

    @Test
    @DisplayName("the corrected month must be CLOSED: OPEN and LOCKED are refused, a LOCKED month's way back being its unlock (INV-ACC-10); an unknown period is not found")
    void theCorrectedMonthMustBeClosed() {
        assertThatThrownBy(() -> machine().propose(UOW, id(FEBRUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("OPEN");
        periods.markLocked(UOW, id(FEBRUARY), 1, ACCOUNTANT, Optional.empty());
        assertThatThrownBy(() -> machine().propose(UOW, id(FEBRUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("LOCKED").hasMessageContaining("unlocked");
        assertThatThrownBy(() -> machine().propose(UOW, AccountingPeriodId.next(IDS), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(AccountingPeriods.PeriodNotFound.class);
        nothingProposed();
    }

    @Test
    @DisplayName("it names the revision of the figures it corrects: another is stale at the proposal")
    void theRevisionIsNamed() {
        assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 2, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        nothingProposed();
    }

    // ------------------------------------------------------------------ what can move between the proposal and the approval is read again, and a proposal that can no longer post has a door out


    private void refusedAndStanding(PriorPeriodAdjustments.Acted proposed, Class<? extends RuntimeException> refusal, PriorPeriodAdjustments.Blocked blocked) {
        assertThatThrownBy(() -> approve(proposed, CONTROLLER)).isInstanceOf(refusal);
        assertThat(row(proposed).status()).as("it stands").isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
        assertThat(ledger.proposal(proposed.proposal()).status()).isEqualTo("PROPOSED");
        assertThat(ledger.calls()).as("the ledger is not asked to post").doesNotContain("approve");
        assertThat(events).isEmpty();
        PriorPeriodAdjustments.View view = machine().view(UOW, proposed.id());
        assertThat(view.stillStands()).isFalse();
        assertThat(view.blockedBy()).contains(blocked);
        assertThat(machine().pending(UOW)).as("it can be found").extracting(PriorPeriodAdjustmentStore.Row::id).contains(proposed.id());

        PriorPeriodAdjustments.Acted rejected = machine().reject(UOW, proposed.id(), CONTROLLER, "it can no longer post", FLOW);

        assertThat(rejected.status()).isEqualTo(PriorPeriodAdjustmentStatus.REJECTED);
        assertThat(ledger.proposal(proposed.proposal()).status()).as("the ledger's with it").isEqualTo("REJECTED");
        assertThat(machine().view(UOW, proposed.id()).blockedBy()).contains(PriorPeriodAdjustments.Blocked.DECIDED);
    }

    @Test
    @DisplayName("the month reopened: the approval is refused, both rows stand PROPOSED, and the rejection, which asks nothing of the months, is the way on")
    void aReopenBetweenProposalAndApproval() {
        PriorPeriodAdjustments.Acted proposed = propose();
        periods.markReopened(UOW, id(JANUARY), 1);

        refusedAndStanding(proposed, AccountingPeriods.PeriodStale.class, PriorPeriodAdjustments.Blocked.CORRECTED_NOT_CLOSED);
    }

    @Test
    @DisplayName("reopened and closed again: CLOSED, but the figures it read were superseded - refused, and the way on is to propose against the new revision")
    void aReCloseBetweenProposalAndApproval() {
        PriorPeriodAdjustments.Acted proposed = propose();
        periods.markReopened(UOW, id(JANUARY), 1);
        periods.closeForTest(JANUARY);

        refusedAndStanding(proposed, PriorPeriodAdjustments.AdjustmentStale.class, PriorPeriodAdjustments.Blocked.CORRECTED_REVISION_MOVED);

        PriorPeriodAdjustments.Acted again = machine().propose(UOW, id(JANUARY), 2, lines(), ACCOUNTANT, REASON, FLOW);
        assertThat(again.correctedRevision()).isEqualTo(2);
        assertThat(approve(again, CONTROLLER).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @DisplayName("the month it lands in locked meanwhile: refused, it stands, it is rejected and proposed again in the next (the backlog's failure handling)")
    void theTargetLockedMeanwhile() {
        PriorPeriodAdjustments.Acted proposed = propose();
        periods.closeForTest(FEBRUARY);
        periods.markLocked(UOW, id(MARCH), 1, ACCOUNTANT, Optional.empty());

        refusedAndStanding(proposed, AccountingPeriods.PeriodStale.class, PriorPeriodAdjustments.Blocked.TARGET_NOT_OPEN);
    }

    @Test
    @DisplayName("the month it lands in closed and reopened meanwhile: OPEN again, its revision moved, the corrected month untouched: refused for the revision, it stands, the rejection is the way on (INV-ACC-10)")
    void theTargetReopenedMeanwhile() {
        PriorPeriodAdjustments.Acted proposed = propose();
        assertThat(row(proposed).targetRevision()).as("what the proposal read of the month it lands in").isEqualTo(1);
        periods.closeForTest(MARCH);
        assertThat(periods.markReopened(UOW, id(MARCH), 1)).isTrue();
        assertThat(periods.month(MARCH).orElseThrow().status()).isEqualTo(PeriodStatus.OPEN);

        refusedAndStanding(proposed, PriorPeriodAdjustments.AdjustmentStale.class, PriorPeriodAdjustments.Blocked.TARGET_REVISION_MOVED);
    }

    @Test
    @DisplayName("the month it lands in locked and unlocked meanwhile: it issued nothing and its revision did not move, so it stands again and posts")
    void theTargetUnlockedMeanwhile() {
        PriorPeriodAdjustments.Acted proposed = propose();
        periods.closeForTest(FEBRUARY);
        periods.markLocked(UOW, id(MARCH), 1, ACCOUNTANT, Optional.empty());
        assertThat(machine().view(UOW, proposed.id()).blockedBy()).contains(PriorPeriodAdjustments.Blocked.TARGET_NOT_OPEN);

        assertThat(periods.proposeUnlock(UOW, id(MARCH), 1, ACCOUNTANT, "a reason")).isTrue();
        assertThat(periods.approveUnlock(UOW, id(MARCH), 1, CONTROLLER, "a reason")).isTrue();

        assertThat(machine().view(UOW, proposed.id()).stillStands()).isTrue();
        assertThat(approve(proposed, CONTROLLER).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @DisplayName("the month holding today already reopened - a clock far out, a month locking only past its end and grace: refused like any month it cannot land in, nothing written")
    void theMonthHoldingTodayAlreadyReopened() {
        periods.closeForTest(MARCH);
        assertThat(periods.markReopened(UOW, id(MARCH), 1)).isTrue();

        assertThatThrownBy(this::propose).isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("reopened");

        assertThat(store.rows()).isEmpty();
        assertThat(ledger.calls()).doesNotContain("propose");
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a side whose sum is more than the ledger holds is an incoherent proposal, refused before anything is read and naming no value: never the ledger's overflow, whichever side; a sum at the bound is not refused for that")
    void aSideBeyondWhatTheLedgerHolds() {
        long half = Long.MAX_VALUE / 2 + 1;
        List<PriorPeriodPostings.Line> balancedBeyond = List.of(line(fees, Side.DEBIT, half), line(fees, Side.DEBIT, half), line(wallet, Side.CREDIT, half), line(wallet, Side.CREDIT, half));
        List<PriorPeriodPostings.Line> creditsBeyond = List.of(line(fees, Side.DEBIT, Long.MAX_VALUE), line(wallet, Side.CREDIT, half), line(wallet, Side.CREDIT, half));
        List<PriorPeriodPostings.Line> debitsBeyond = List.of(line(fees, Side.DEBIT, half), line(fees, Side.DEBIT, half), line(wallet, Side.CREDIT, Long.MAX_VALUE));
        for (List<PriorPeriodPostings.Line> lines : List.of(balancedBeyond, creditsBeyond, debitsBeyond)) {
            assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, lines, ACCOUNTANT, REASON, FLOW)).isInstanceOf(PriorPeriodAdjustments.AdjustmentInvalid.class)
                    .hasMessageContaining("EUR@2").hasMessageNotContaining(Long.toString(half));
        }
        assertThat(ledger.calls()).as("nothing read, nothing written").isEmpty();

        List<PriorPeriodPostings.Line> atTheBound = List.of(line(fees, Side.DEBIT, half), line(fees, Side.DEBIT, Long.MAX_VALUE - half), line(wallet, Side.CREDIT, Long.MAX_VALUE));
        assertThat(machine().propose(UOW, id(JANUARY), 1, atTheBound, ACCOUNTANT, REASON, FLOW).status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
    }

    @Test
    @DisplayName("what an approver reads says what each line touches: its account's purpose and owner kind, codes, in the lines' order, never whose and no amount in a log line")
    void theApproverReadsWhatEachLineTouches() {
        PriorPeriodAdjustments.View view = machine().view(UOW, propose().id());

        assertThat(view.lines()).extracting(read -> read.line().ledgerAccount(), PriorPeriodAdjustments.ReadLine::purpose, PriorPeriodAdjustments.ReadLine::ownerKind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(fees, "FEE_REVENUE", "OPERATIONAL"), org.assertj.core.groups.Tuple.tuple(wallet, "CUSTOMER_WALLET", "CUSTOMER"));
        assertThat(view.lines().get(0).toString()).contains("FEE_REVENUE").doesNotContain("1500").doesNotContain("15.00");
    }

    @Test
    @DisplayName("a purpose a release closed since the proposal is refused at the approval, the owning door named")
    void aPurposeClosedSinceTheProposal() {
        PriorPeriodAdjustments.Acted proposed = propose();
        ledger.reclassify(fees, "LOAN_FEE_INCOME", "OPERATIONAL");
        assertThat(machine().view(UOW, proposed.id()).stillStands()).as("its doc says it: the months only, no promise about a purpose").isTrue();

        assertThatThrownBy(() -> approve(proposed, CONTROLLER)).isInstanceOf(PriorPeriodAdjustments.PurposeClosed.class).hasMessageContaining("lending");
        assertThat(row(proposed).status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
        assertThat(ledger.calls()).doesNotContain("approve");
    }

    @Test
    @DisplayName("a purpose no longer mapped in the version in force for the target is refused at the approval: that month could not close")
    void aPurposeUnmappedSinceTheProposal() {
        PriorPeriodAdjustments.Acted proposed = propose();
        GlMappingStore without = withContent(ReferenceGlMapping.without(JANUARY, Set.of("FEE_REVENUE")));

        assertThatThrownBy(() -> new PriorPeriodAdjustments(periods, store, ledger, without, (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> { }, IDS,
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)).approve(UOW, proposed.id(), reading(proposed), CONTROLLER, "agreed", FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentInvalid.class)
                .hasMessageContaining("FEE_REVENUE/OPERATIONAL");
        assertThat(ledger.calls()).doesNotContain("approve");
    }

    @Test
    @DisplayName("the ledger refusing the date rolls back with the row PROPOSED: its own exception, untranslated")
    void theLedgersRefusalPassesThrough() {
        PriorPeriodAdjustments.Acted proposed = propose();
        ledger.closedThrough(MARCH.atEndOfMonth());

        assertThatThrownBy(() -> approve(proposed, CONTROLLER)).isInstanceOf(InMemoryPriorPeriodPostings.LedgerRefused.class);
        assertThat(row(proposed).status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
    }

    @Test
    @DisplayName("the ledger answering a proposal already approved while this row stands PROPOSED is a defect, and loud: the two rows move together")
    void twoRowsThatDisagreeAreLoud() {
        PriorPeriodAdjustments.Acted proposed = propose();
        ledger.answerReplayed();

        assertThatThrownBy(() -> approve(proposed, CONTROLLER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("move together");
        assertThat(row(proposed).status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
    }

    // ------------------------------------------------------------------ what the approver read is what posts


    @Test
    @DisplayName("a date other than the one it posts on, or another revision, is stale: nothing posts")
    void aDifferentDateOrRevisionIsStale() {
        PriorPeriodAdjustments.Acted proposed = propose();

        assertThatThrownBy(() -> machine().approve(UOW, proposed.id(), new PriorPeriodAdjustments.Approval(TODAY.plusDays(1), 1, Set.of()), CONTROLLER, "agreed", FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class).hasMessageContaining(TODAY.toString());
        assertThatThrownBy(() -> machine().approve(UOW, proposed.id(), new PriorPeriodAdjustments.Approval(TODAY, 2, Set.of()), CONTROLLER, "agreed", FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);

        assertThat(ledger.calls()).doesNotContain("approve");
        assertThat(row(proposed).status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
    }

    @Test
    @DisplayName("the view is what the approval names: the date, the revision, the lines, the proposer's reason, and it still stands")
    void theViewIsWhatTheApprovalNames() {
        PriorPeriodAdjustments.Acted proposed = propose();
        PriorPeriodAdjustments.View view = machine().view(UOW, proposed.id());

        assertThat(view.stillStands()).isTrue();
        assertThat(view.row().postingDate()).isEqualTo(TODAY);
        assertThat(view.row().correctedRevision()).isEqualTo(1);
        assertThat(view.row().proposalReason()).isEqualTo(REASON);
        assertThat(view.lines()).hasSize(2);
        assertThat(view.earlierCorrections()).isEmpty();
        assertThat(machine().approve(UOW, proposed.id(), new PriorPeriodAdjustments.Approval(view.row().postingDate(), view.row().correctedRevision(), Set.of()), CONTROLLER, "agreed", FLOW).status())
                .isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    // ------------------------------------------------------------------ correcting twice cannot happen unknowingly


    @Test
    @DisplayName("a proposal's receipt and the approver's read list the month's other corrections; the approval names the posted ones, and one that does not is refused")
    void anApprovalNamesThePostedCorrections() {
        PriorPeriodAdjustments.Acted first = propose();
        UUID firstId = first.id().value();
        approve(first, CONTROLLER);

        PriorPeriodAdjustments.Acted second = propose();

        assertThat(second.earlierCorrections()).as("the proposer is told").singleElement().satisfies(correction -> {
            assertThat(correction.id()).isEqualTo(first.id());
            assertThat(correction.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
            assertThat(correction.entry()).isPresent();
        });
        assertThat(machine().view(UOW, second.id()).earlierCorrections()).as("the approver is told").singleElement()
                .satisfies(correction -> assertThat(correction.id()).isEqualTo(first.id()));
        assertThatThrownBy(() -> approve(second, OTHER)).as("knowing nothing of the first").isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class).hasMessageContaining("1 stand");
        assertThat(ledger.proposal(second.proposal()).status()).isEqualTo("PROPOSED");

        assertThat(approve(second, OTHER, firstId).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @DisplayName("once the first approval is through, the second, which read it PROPOSED, is refused: what two racing approvals meet once the rows have serialised them (the race itself: the database suite)")
    void theSecondApproverFindsTheFirstPosted() {
        PriorPeriodAdjustments.Acted first = propose();
        PriorPeriodAdjustments.Acted second = propose();
        assertThat(machine().view(UOW, second.id()).earlierCorrections()).singleElement().satisfies(correction ->
                assertThat(correction.status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED));

        approve(first, CONTROLLER);

        assertThatThrownBy(() -> approve(second, OTHER)).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
    }

    @Test
    @DisplayName("a rejected correction and a standing one are not named: only what has posted corrects anything")
    void onlyThePostedAreNamed() {
        PriorPeriodAdjustments.Acted rejected = propose();
        machine().reject(UOW, rejected.id(), CONTROLLER, "no", FLOW);
        propose();
        PriorPeriodAdjustments.Acted third = propose();

        assertThat(approve(third, CONTROLLER).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @DisplayName("a correction at revision 1 stands against the month at revision 2: the person correcting the re-closed figures is told, and names it; every correction traced to its period")
    void aCorrectionOfAnEarlierRevisionIsStillNamed() {
        PriorPeriodAdjustments.Acted first = propose();
        approve(first, CONTROLLER);
        periods.markReopened(UOW, id(JANUARY), 1);
        assertThat(machine().postedAgainst(UOW, id(JANUARY))).as("what a reopen's receipt lists").containsExactly(first.id().value());
        periods.closeForTest(JANUARY);

        PriorPeriodAdjustments.Acted second = machine().propose(UOW, id(JANUARY), 2, lines(), ACCOUNTANT, REASON, FLOW);

        assertThat(second.earlierCorrections()).singleElement().satisfies(correction -> assertThat(correction.correctedRevision()).isEqualTo(1));
        assertThat(approve(second, CONTROLLER, first.id().value()).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        assertThat(machine().correctionsOf(UOW, id(JANUARY))).extracting(PriorPeriodAdjustments.Correction::correctedRevision).containsExactly(1, 2);
        assertThat(machine().correctionsOf(UOW, id(FEBRUARY))).as("a month nothing corrected").isEmpty();
        assertThatThrownBy(() -> machine().correctionsOf(UOW, AccountingPeriodId.next(IDS))).isInstanceOf(AccountingPeriods.PeriodNotFound.class);
    }

    // ------------------------------------------------------------------ what may be corrected


    @Test
    @DisplayName("a purpose closed to free adjustments is refused at the proposal, the owning domain's door named; nothing written")
    void aClosedToFreeAdjustmentsPurposeIsRefused() {
        record Closed(String purpose, String ownerKind, String door) {}
        for (Closed closed : List.of(new Closed("LOAN_PRINCIPAL", "LOAN", "lending"), new Closed("BNPL_FEES_DUE", "BNPL_OBLIGATION", "bnpl"), new Closed("FX_POSITION", "OPERATIONAL", "fx"),
                new Closed("ROUNDING_RESIDUAL", "OPERATIONAL", "fx"), new Closed("SETTLEMENT_CLEARING", "OPERATIONAL", "reconciliation"), new Closed("CASH_AT_BANK", "OPERATIONAL", "reconciliation"),
                new Closed("SUSPENSE_UNMATCHED", "OPERATIONAL", "reconciliation"), new Closed("FX_PROVIDER_CLEARING", "COUNTERPARTY", "reconciliation"))) {
            UUID account = ledger.account(closed.purpose(), closed.ownerKind());
            assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, List.of(line(account, Side.DEBIT, 10), line(wallet, Side.CREDIT, 10)), ACCOUNTANT, REASON, FLOW))
                    .as(closed.purpose())
                    .isInstanceOf(PriorPeriodAdjustments.PurposeClosed.class)
                    .hasMessageContaining(closed.purpose())
                    .hasMessageContaining(closed.door());
        }
        nothingProposed();
    }

    @Test
    @DisplayName("a purpose this build has never heard of is refused too: the list is of what is open")
    void anUnknownPurposeFailsClosed() {
        UUID account = ledger.account("A_PURPOSE_FROM_A_LATER_RELEASE", "OPERATIONAL");
        assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, List.of(line(account, Side.DEBIT, 10), line(wallet, Side.CREDIT, 10)), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(PriorPeriodAdjustments.PurposeClosed.class);
        nothingProposed();
    }

    @Test
    @DisplayName("each purpose open to free adjustment may be corrected")
    void theOpenPurposes() {
        for (String[] open : List.of(new String[] {"CUSTOMER_WALLET", "CUSTOMER"}, new String[] {"MERCHANT_PAYABLE", "MERCHANT"}, new String[] {"CHARGEBACK_RECOVERABLE", "OPERATIONAL"},
                new String[] {"DISPUTE_COSTS", "OPERATIONAL"}, new String[] {"FEE_REVENUE", "OPERATIONAL"})) {
            UUID account = ledger.account(open[0], open[1]);
            assertThat(machine().propose(UOW, id(JANUARY), 1, List.of(line(account, Side.DEBIT, 10), line(wallet, Side.CREDIT, 10)), ACCOUNTANT, REASON, FLOW).status())
                    .as(open[0]).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
        }
    }

    @Test
    @DisplayName("a purpose unmapped in the version in force for the month it lands in is refused at the proposal, and so is no version at all: that month could not close")
    void anUnmappedPurposeIsRefused() {
        GlMappingStore without = withContent(ReferenceGlMapping.without(JANUARY, Set.of("FEE_REVENUE")));
        assertThatThrownBy(() -> new PriorPeriodAdjustments(periods, store, ledger, without, (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> { }, IDS,
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)).propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentInvalid.class).hasMessageContaining("FEE_REVENUE/OPERATIONAL");
        assertThatThrownBy(() -> new PriorPeriodAdjustments(periods, store, ledger, new InMemoryGlMappingStore(), (uow, record) -> audit.add(record), (uow, envelope, payload, mediaType) -> { }, IDS,
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)).propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentInvalid.class).hasMessageContaining("no GL mapping version");
        nothingProposed();
    }

    @Test
    @DisplayName("the lines: two at least, each positive, balanced per currency (INV-LED-01), and the same numbers in two currencies do not balance; an account the ledger does not hold is refused; each before anything is locked or written")
    void theLinesAreCoherent() {
        int locks = periods.rowLocks();
        PriorPeriodPostings.Line usd = new PriorPeriodPostings.Line(wallet, Side.CREDIT, Money.ofMinorUnits(1500, USD));
        for (List<PriorPeriodPostings.Line> bad : List.of(
                List.<PriorPeriodPostings.Line>of(),
                List.of(line(fees, Side.DEBIT, 1500)),
                List.of(line(fees, Side.DEBIT, 1500), line(wallet, Side.CREDIT, 1400)),
                List.of(line(fees, Side.DEBIT, 1500), usd),
                List.of(line(fees, Side.DEBIT, 0), line(wallet, Side.CREDIT, 0)),
                List.of(line(fees, Side.DEBIT, -5), line(wallet, Side.CREDIT, -5)))) {
            assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, bad, ACCOUNTANT, REASON, FLOW)).isInstanceOf(PriorPeriodAdjustments.AdjustmentInvalid.class);
        }
        assertThat(periods.rowLocks()).isEqualTo(locks);
        assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, List.of(line(UUID.randomUUID(), Side.DEBIT, 10), line(wallet, Side.CREDIT, 10)), ACCOUNTANT, REASON, FLOW))
                .isInstanceOf(PriorPeriodAdjustments.AdjustmentInvalid.class).hasMessageContaining("does not hold");
        assertThatThrownBy(() -> new PriorPeriodPostings.Line(wallet, Side.EITHER, Money.ofMinorUnits(1, EUR))).isInstanceOf(IllegalArgumentException.class);
        nothingProposed();

        PriorPeriodAdjustments.Acted twoCurrencies = machine().propose(UOW, id(JANUARY), 1,
                List.of(line(fees, Side.DEBIT, 10), line(wallet, Side.CREDIT, 10), new PriorPeriodPostings.Line(fees, Side.CREDIT, Money.ofMinorUnits(7, USD)),
                        new PriorPeriodPostings.Line(wallet, Side.DEBIT, Money.ofMinorUnits(7, USD))), ACCOUNTANT, REASON, FLOW);
        assertThat(twoCurrencies.status()).as("each currency balanced").isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
    }

    @Test
    @DisplayName("a reason is required at each act, screened for instrument shapes; only a person acts")
    void reasonsAndPersons() {
        for (String reason : new String[] {null, " ", "x".repeat(1001), "card 4111 1111 1111 1111"}) {
            assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, reason, FLOW)).isInstanceOf(AccountingReasons.ReasonRequired.class);
        }
        nothingProposed();
        assertThatThrownBy(() -> machine().propose(UOW, id(JANUARY), 1, lines(), Actor.SYSTEM, REASON, FLOW)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a person's");
        PriorPeriodAdjustments.Acted proposed = propose();
        assertThatThrownBy(() -> machine().approve(UOW, proposed.id(), reading(proposed), CONTROLLER, " ", FLOW)).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine().reject(UOW, proposed.id(), CONTROLLER, null, FLOW)).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine().approve(UOW, proposed.id(), reading(proposed), Actor.SYSTEM, "agreed", FLOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(row(proposed).status()).isEqualTo(PriorPeriodAdjustmentStatus.PROPOSED);
    }

    // ------------------------------------------------------------------ four eyes and keyed acts


    @Test
    @DisplayName("the proposer cannot approve: refused before anything is asked of the ledger, the attempt recordable FAILED; the row stands for a second person")
    void theProposerCannotApprove() {
        PriorPeriodAdjustments.Acted proposed = propose();

        assertThatThrownBy(() -> approve(proposed, ACCOUNTANT)).isInstanceOfSatisfying(PriorPeriodAdjustments.SelfApprovalRefused.class, refused -> {
            assertThat(refused.target()).isEqualTo(proposed.id().value().toString());
            assertThat(refused.summary()).contains("prior-period-adjustment:approve").doesNotContain("secret");
            machine().recordRefusal(UOW, refused, ACCOUNTANT, FLOW);
        });
        assertThat(ledger.calls()).doesNotContain("approve");
        assertThat(audit).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetType()).isEqualTo(PriorPeriodAdjustments.TARGET_TYPE);
        });
        assertThat(approve(proposed, CONTROLLER).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @DisplayName("a second look at an approval already through: the same person converges and writes nothing, another is stale; what a replayed approval and ten approvers meet once the row has serialised them (the real race: the database suite)")
    void aReplayedApprovalPostsOnce() {
        PriorPeriodAdjustments.Acted proposed = propose();
        PriorPeriodAdjustments.Acted posted = approve(proposed, CONTROLLER);
        int records = audit.size();
        long approvals = ledger.calls().stream().filter("approve"::equals).count();

        PriorPeriodAdjustments.Acted again = approve(proposed, CONTROLLER);

        assertThat(again.replayed()).isTrue();
        assertThat(again.entry()).isEqualTo(posted.entry());
        assertThatThrownBy(() -> approve(proposed, OTHER)).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class).hasMessageContaining("POSTED");
        assertThat(audit).hasSize(records);
        assertThat(events).hasSize(1);
        assertThat(ledger.calls().stream().filter("approve"::equals).count()).isEqualTo(approvals).isEqualTo(1);
    }

    @Test
    @DisplayName("an approval and a rejection: whichever the row admits first stands, the other is stale; a rejection may be the proposer's; the same rejecter converges")
    void anApprovalAndARejection() {
        PriorPeriodAdjustments.Acted rejected = propose();
        PriorPeriodAdjustments.Acted withdrawn = machine().reject(UOW, rejected.id(), ACCOUNTANT, "withdrawn", FLOW);
        assertThat(withdrawn.status()).isEqualTo(PriorPeriodAdjustmentStatus.REJECTED);
        assertThat(machine().reject(UOW, rejected.id(), ACCOUNTANT, "withdrawn", FLOW).replayed()).isTrue();
        assertThatThrownBy(() -> machine().reject(UOW, rejected.id(), CONTROLLER, "no", FLOW)).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThatThrownBy(() -> approve(rejected, CONTROLLER)).as("rejected, it drops out of the locked rows and is read again").isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class).hasMessageContaining("REJECTED");
        assertThat(ledger.proposal(rejected.proposal()).status()).isEqualTo("REJECTED");

        PriorPeriodAdjustments.Acted posted = propose();
        approve(posted, CONTROLLER);
        assertThatThrownBy(() -> machine().reject(UOW, posted.id(), CONTROLLER, "no", FLOW)).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThat(ledger.proposal(posted.proposal()).status()).isEqualTo("APPROVED");

        assertThat(audit).extracting(record -> record.operation().code()).contains("accounting.PriorPeriodAdjustmentRejected");
        assertThat(events).as("no event at a rejection").hasSize(1);
    }

    @Test
    @DisplayName("nothing has this id: not found at each door")
    void unknownIsNotFound() {
        PriorPeriodAdjustmentId nobody = PriorPeriodAdjustmentId.next(IDS);
        assertThatThrownBy(() -> machine().approve(UOW, nobody, new PriorPeriodAdjustments.Approval(TODAY, 1, Set.of()), CONTROLLER, "agreed", FLOW)).isInstanceOf(PriorPeriodAdjustments.AdjustmentNotFound.class);
        assertThatThrownBy(() -> machine().reject(UOW, nobody, CONTROLLER, "no", FLOW)).isInstanceOf(PriorPeriodAdjustments.AdjustmentNotFound.class);
        assertThatThrownBy(() -> machine().view(UOW, nobody)).isInstanceOf(PriorPeriodAdjustments.AdjustmentNotFound.class);
    }

    // ------------------------------------------------------------------ the locks


    @Test
    @DisplayName("an approval: the corrected period FOR SHARE, the target FOR SHARE, oldest first; the month's adjustments in one statement; then the ledger; the row's edge after the entry, its lock already held")
    void anApprovalsOrder() {
        PriorPeriodAdjustments.Acted proposed = propose();
        List<String> order = new ArrayList<>();
        PriorPeriodAdjustments recording = machine(recording(PriorPeriodAdjustmentStore.class, store, order), recording(PriorPeriodPostings.class, ledger, order), recording(AccountingPeriodStore.class, periods, order),
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        recording.approve(UOW, proposed.id(), reading(proposed), CONTROLLER, "agreed", FLOW);

        assertThat(order).containsSubsequence("lockShared(2026-01)", "lockShared(2026-03)", "lockCorrectionsOf", "approve", "markPosted");
        assertThat(order).filteredOn(call -> call.startsWith("lock")).containsExactly("lockShared(2026-01)", "lockShared(2026-03)", "lockCorrectionsOf");
        assertThat(order).doesNotContain("lock");
    }

    @Test
    @DisplayName("a proposal: the two periods FOR SHARE oldest first, then the ledger's proposal, then the row")
    void aProposalsOrder() {
        List<String> order = new ArrayList<>();
        PriorPeriodAdjustments recording = machine(recording(PriorPeriodAdjustmentStore.class, store, order), recording(PriorPeriodPostings.class, ledger, order), recording(AccountingPeriodStore.class, periods, order),
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        recording.propose(UOW, id(JANUARY), 1, lines(), ACCOUNTANT, REASON, FLOW);

        assertThat(order).containsSubsequence("lockShared(2026-01)", "lockShared(2026-03)", "propose", "insert");
    }

    @Test
    @DisplayName("a rejection takes no period row: nothing about either month can hold the way out shut")
    void aRejectionTakesNoPeriodRow() {
        PriorPeriodAdjustments.Acted proposed = propose();
        periods.closeForTest(FEBRUARY);
        periods.markLocked(UOW, id(MARCH), 1, ACCOUNTANT, Optional.empty());
        int locks = periods.rowLocks();

        machine().reject(UOW, proposed.id(), CONTROLLER, "no", FLOW);

        assertThat(periods.rowLocks()).isEqualTo(locks);
    }

    @Test
    @DisplayName("no generated text carries a reason or an amount: the row, the new row, a line and a view")
    void noTextCarriesAReasonOrAnAmount() {
        PriorPeriodAdjustments.Acted proposed = propose();
        PriorPeriodPostings.Line line = line(fees, Side.DEBIT, 1500);

        assertThat(row(proposed).toString()).doesNotContain("secret").contains("2026-01");
        assertThat(new PriorPeriodAdjustmentStore.NewAdjustment(proposed.id(), id(JANUARY), 1, id(MARCH), 1, TODAY, UUID.randomUUID(), ACCOUNTANT, REASON, FLOW).toString()).doesNotContain("secret");
        assertThat(line.toString()).doesNotContain("1500").doesNotContain("15.00");
        assertThat(machine().view(UOW, proposed.id()).toString()).doesNotContain("secret").doesNotContain("1500").doesNotContain("15.00");
        assertThat(proposed.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("an owning domain's door is named for every closed purpose the ledger holds apart: lending, bnpl, fx, else a break's resolution")
    void theOwningDoors() {
        assertThat(PriorPeriodAdjustments.owningDoor("LOAN_INTEREST_INCOME")).contains("lending");
        assertThat(PriorPeriodAdjustments.owningDoor("BNPL_WRITE_OFF_EXPENSE")).contains("bnpl");
        assertThat(PriorPeriodAdjustments.owningDoor("FX_REALISED_GAINS")).contains("fx");
        assertThat(PriorPeriodAdjustments.owningDoor("LENDING_CAPITAL")).contains("reconciliation");
        assertThat(PriorPeriodAdjustments.owningDoor("CORRIDOR_CLEARING")).contains("reconciliation");
    }

    // ------------------------------------------------------------------

    /** The mapping store, answering {@code content} with {@code version} - a release's or an activation's stand-in. */
    private GlMappingStore withContent(GlMappingVersion version) {
        return (GlMappingStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {GlMappingStore.class}, (proxy, call, args) -> {
            if (call.getName().equals("content")) {
                return version;
            }
            try {
                return call.invoke(mappings, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    /** {@code target}, each call's name appended to {@code order} - a month for {@code lockShared}. */
    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<T> type, T target, List<String> order) {
        InvocationHandler handler = (proxy, call, args) -> {
            order.add(call.getName().equals("lockShared") ? "lockShared(" + args[1] + ")" : call.getName());
            try {
                return call.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
