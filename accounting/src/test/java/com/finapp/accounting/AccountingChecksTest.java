package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three break-raising checks as the close run will call them (`P14-TSK-010`; ADR-0117 decisions 2-3;
 * {@code PHASE_14_PLAN.md} sections 8.3 row 10, 13.3; {@code INV-ACC-12}, {@code INV-REC-02}): every planted discrepancy
 * class - an unmapped purpose, a double count, an unregistered namespace, a shape mismatch, a source discrepancy - raises
 * exactly one break, and a second run raises none; a clean period passes; an unavailable proof, a judge that cannot read
 * and a month with no mapping version are {@code FAILED}, never passing; every outcome names its register version; a run
 * past its cap raises the rest on the next. Hermetic: the journal, the proofs and the store are in memory, every snapshot
 * the same stand-in connection; the database cases are {@code AccountingBreakDatabaseTest}'s (owed, C5).
 */
@DisplayName("the break-raising checks: one break per discrepancy, never a false pass (P14-TSK-010)")
class AccountingChecksTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final LocalDate DAY = MARCH.atDay(10);

    private final InMemoryAccountingBreakStore store = new InMemoryAccountingBreakStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryJournal journal = new InMemoryJournal();
    private final List<JournalShapes.EntryShape> entries = new ArrayList<>();
    private final Map<SourceProofs.Source, Function<Connection, List<SourceProofs.Reading>>> sources =
            new EnumMap<>(SourceProofs.Source.class);
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private int transactions;

    private final AccountingBreaks breaks = new AccountingBreaks(store, journal, (unitOfWork, record) -> audit.add(record),
            (unitOfWork, envelope, payload, mediaType) -> events.add(envelope), IDS,
            Clock.fixed(Instant.parse("2097-04-03T09:00:00Z"), ZoneOffset.UTC));

    private final AccountingChecks checks = new AccountingChecks(new GeneralLedger(journal),
            new GlPins(mappings, (snapshot, month) -> Optional.empty()), this::page, this::read,
            PostingRuleRegister.CURRENT, breaks, new AccountingChecks.Snapshots() {
                @Override
                public <R> R read(Function<Connection, R> work) {
                    return work.apply(SNAPSHOT);
                }
            }, new AccountingChecks.Transactions() {
                @Override
                public <R> R inTransaction(Function<Connection, R> work) {
                    transactions++;
                    return work.apply(SNAPSHOT);
                }
            });

    AccountingChecksTest() {
        for (SourceProofs.Source source : SourceProofs.Source.values()) {
            sources.put(source, snapshot -> clean(source));
        }
    }

    // ------------------------------------------------------------------ rule conformance

    @Test
    @DisplayName("a planted shape mismatch raises one break - FAILED, judged by the register's version; a second run"
            + " raises nothing and counts it known")
    void aPlantedShapeMismatchRaisesOneBreak() {
        conforming(3);
        JournalShapes.EntryShape planted = entry("transfer:", "CUSTOMER_WALLET", "FEE_REVENUE");

        CheckOutcome first = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());

        assertThat(first.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(first.failures()).containsExactly(CheckOutcome.DISCREPANCIES);
        assertThat(first.judgedBy()).isEqualTo("posting-rules:v" + PostingRuleRegister.CATALOGUE_VERSION);
        assertThat(first.raised()).isEqualTo(1);
        assertThat(store.rows()).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(BreakType.SHAPE_MISMATCH);
            assertThat(row.subject()).isEqualTo("shape:" + planted.id());
            assertThat(row.judgedBy()).isEqualTo(first.judgedBy());
        });

        CheckOutcome second = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(second.raised()).isZero();
        assertThat(second.knownOpen()).isEqualTo(1);
        assertThat(second.passed()).as("a known discrepancy still fails the check").isFalse();
        assertThat(store.rows()).hasSize(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("a known finding is counted by its break's status (the review's I-1): acknowledged is disclosed; a"
            + " discrepancy still present after its break was resolved is RECURRED - never folded into 'disclosed'")
    void aResolvedBreaksDiscrepancyStillPresentIsRecurred() {
        entry("transfer:", "CUSTOMER_WALLET", "FEE_REVENUE");
        checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        AccountingBreakId id = store.rows().get(0).id();
        Actor accountant = new Actor("accountant-1", ActorType.CUSTOMER);
        Actor controller = new Actor("controller-1", ActorType.CUSTOMER);
        breaks.proposeAcknowledgement(SNAPSHOT, id, accountant, "a fee line, corrected next month", correlation());
        breaks.approveAcknowledgement(SNAPSHOT, id, controller, "disclosed", correlation());

        CheckOutcome acknowledged = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(acknowledged.knownAcknowledged()).isEqualTo(1);
        assertThat(acknowledged.knownResolved()).isZero();
        assertThat(acknowledged.failures()).containsExactly(CheckOutcome.DISCREPANCIES);

        JournalReads.Entry correcting = journal.post(DAY.plusDays(5), "EUR", 2, 10, "FEE_REVENUE", "OPERATIONAL",
                "CUSTOMER_WALLET", "CUSTOMER");
        breaks.resolve(SNAPSHOT, id, controller, correcting.id(), "reversed", correlation());

        CheckOutcome recurred = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(recurred.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(recurred.knownResolved()).isEqualTo(1);
        assertThat(recurred.knownOpen()).isZero();
        assertThat(recurred.failures()).containsExactly(CheckOutcome.DISCREPANCIES, CheckOutcome.RECURRED);
        assertThat(recurred.raised()).as("the key and the re-raise rule are unchanged - an owner item").isZero();
        assertThat(store.rows()).hasSize(1);
    }

    @Test
    @DisplayName("I-1: an acknowledgement holds the finding, not only its subject: an acknowledged namespace with one more entry is"
            + " ACKNOWLEDGED_CHANGED, its subject named, nothing raised; the same facts walked again are not")
    void anAcknowledgedNamespaceThatGrewHasChanged() {
        for (int i = 0; i < 2; i++) {
            entry("p14-011-probe:", "CUSTOMER_WALLET", "FEE_REVENUE");
        }
        checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        acknowledge(store.rows().get(0).id());

        CheckOutcome same = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(same.failures()).containsExactly(CheckOutcome.DISCREPANCIES);
        assertThat(same.knownAcknowledged()).isEqualTo(1);
        assertThat(same.acknowledgedChanged()).isEmpty();

        entry("p14-011-probe:", "CUSTOMER_WALLET", "FEE_REVENUE");
        CheckOutcome grown = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(grown.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(grown.failures()).containsExactly(CheckOutcome.DISCREPANCIES, CheckOutcome.ACKNOWLEDGED_CHANGED);
        assertThat(grown.acknowledgedChanged()).containsExactly("namespace:POSTING p14-011-probe:");
        assertThat(grown.raised()).isZero();
        assertThat(store.rows()).hasSize(1);
    }

    @Test
    @DisplayName("I-1: an acknowledged proof subject whose evidence moved is ACKNOWLEDGED_CHANGED; the same evidence is not, and one still OPEN is"
            + " simply a discrepancy")
    void anAcknowledgedProofSubjectThatMovedHasChanged() {
        sources.put(SourceProofs.Source.LENDING, lending("fails=[UNEXPLAINED_ENTRIES]"));
        checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());

        sources.put(SourceProofs.Source.LENDING, lending("fails=[UNEXPLAINED_ENTRIES, NEGATIVE]"));
        CheckOutcome open = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());
        assertThat(open.failures()).as("awaiting a decision, whatever it reads").containsExactly(CheckOutcome.DISCREPANCIES);
        assertThat(open.acknowledgedChanged()).isEmpty();

        sources.put(SourceProofs.Source.LENDING, lending("fails=[UNEXPLAINED_ENTRIES]"));
        acknowledge(store.rows().get(0).id());
        assertThat(checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation()).failures())
                .containsExactly(CheckOutcome.DISCREPANCIES);

        sources.put(SourceProofs.Source.LENDING, lending("fails=[UNEXPLAINED_ENTRIES, NEGATIVE]"));
        CheckOutcome moved = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());
        assertThat(moved.failures()).containsExactly(CheckOutcome.DISCREPANCIES, CheckOutcome.ACKNOWLEDGED_CHANGED);
        assertThat(moved.acknowledgedChanged()).containsExactly("LOAN_SUBLEDGER:loan-9");
    }

    private static java.util.function.Function<Connection, List<SourceProofs.Reading>> lending(String evidence) {
        return snapshot -> List.of(
                new SourceProofs.Reading(SourceProofs.Proof.LOAN_SUBLEDGER, true, List.of(new SourceProofs.Discrepancy("loan-9", evidence))),
                new SourceProofs.Reading(SourceProofs.Proof.LOAN_REPLAY, true, List.of()));
    }

    /** Four eyes on {@code id}: an accountant's disclosure, a controller's approval. */
    private void acknowledge(AccountingBreakId id) {
        breaks.proposeAcknowledgement(SNAPSHOT, id, new Actor("accountant-1", ActorType.CUSTOMER), "disclosed for the probe", correlation());
        breaks.approveAcknowledgement(SNAPSHOT, id, new Actor("controller-1", ActorType.CUSTOMER), "disclosed", correlation());
    }

    @Test
    @DisplayName("an unregistered namespace raises one break however many entries carry it")
    void anUnregisteredNamespaceRaisesOneBreak() {
        for (int i = 0; i < 4; i++) {
            entry("p14-010-probe:", "CUSTOMER_WALLET", "FEE_REVENUE");
        }
        CheckOutcome outcome = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(outcome.findings()).isEqualTo(1);
        assertThat(store.rows()).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(BreakType.UNREGISTERED_POSTING);
            assertThat(row.subject()).isEqualTo("namespace:POSTING p14-010-probe:");
            assertThat(row.evidence()).contains("entries=4");
        });
    }

    @Test
    @DisplayName("the month is walked page by page to its end: a mismatch past the first page is found, and an entry"
            + " outside the month refuses the reading")
    void theMonthIsWalkedWhole() {
        conforming(AccountingChecks.PAGE + 7);
        JournalShapes.EntryShape late = entry("transfer:", "CUSTOMER_WALLET", "FEE_REVENUE", MARCH.atEndOfMonth());
        assertThat(checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation()).raised()).isEqualTo(1);
        assertThat(store.rows()).extracting(AccountingBreakStore.BreakRow::subject).containsExactly("shape:" + late.id());

        entry("transfer:", "CUSTOMER_WALLET", "CUSTOMER_WALLET", MARCH.plusMonths(1).atDay(1));
        servingOutsideTheMonth = true;
        assertThat(checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation()).failures())
                .containsExactly("UNAVAILABLE:RULE_CONFORMANCE");
    }

    @Test
    @DisplayName("a run keeps at most its cap of new findings - FAILED FINDINGS_CAPPED - and the next run raises the rest")
    void theCapRaisesTheRestNextRun() {
        for (int i = 0; i < AccountingChecks.MAX_NEW_FINDINGS + 3; i++) {
            entry("transfer:", "CUSTOMER_WALLET", "FEE_REVENUE");
        }
        CheckOutcome first = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(first.raised()).isEqualTo(AccountingChecks.MAX_NEW_FINDINGS);
        assertThat(first.failures()).containsExactly(CheckOutcome.DISCREPANCIES, CheckOutcome.FINDINGS_CAPPED);
        CheckOutcome second = checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation());
        assertThat(second.raised()).isEqualTo(3);
        assertThat(second.failures()).containsExactly(CheckOutcome.DISCREPANCIES);
        assertThat(store.rows()).hasSize(AccountingChecks.MAX_NEW_FINDINGS + 3);
    }

    // ------------------------------------------------------------------ GL = ledger

    @Test
    @DisplayName("a planted unmapped purpose raises one MAPPING_GAP, judged by the period's mapping version; a clean"
            + " period passes")
    void aPlantedUnmappedPurposeRaisesOneBreak() {
        activate();
        String[] debit = keyOf(0);
        String[] credit = keyOf(1);
        journal.post(DAY, "EUR", 2, 1000, debit[0], debit[1], credit[0], credit[1]);
        CheckOutcome clean = checks.run(MARCH, CloseCheckCode.GL_EQUALS_LEDGER, Actor.SYSTEM, correlation());
        assertThat(clean.passed()).isTrue();
        assertThat(clean.judgedBy()).isEqualTo("gl-mapping:v1");

        journal.post(DAY, "EUR", 2, 70, debit[0], debit[1], "UNMAPPED_PROBE", "OPERATIONAL");
        journal.post(DAY, "USD", 2, 70, debit[0], debit[1], "UNMAPPED_PROBE", "OPERATIONAL");
        CheckOutcome gap = checks.run(MARCH, CloseCheckCode.GL_EQUALS_LEDGER, Actor.SYSTEM, correlation());

        assertThat(gap.failures()).containsExactly(CheckOutcome.DISCREPANCIES);
        assertThat(store.rows()).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(BreakType.MAPPING_GAP);
            assertThat(row.subject()).isEqualTo("key:UNMAPPED_PROBE/OPERATIONAL");
            assertThat(row.judgedBy()).isEqualTo("gl-mapping:v1");
        });
        assertThat(checks.run(MARCH, CloseCheckCode.GL_EQUALS_LEDGER, Actor.SYSTEM, correlation()).raised()).isZero();
    }

    @Test
    @DisplayName("no mapping version for the month: GL_EQUALS_LEDGER is FAILED NO_MAPPING, never passing, nothing raised")
    void noMappingIsFailed() {
        CheckOutcome outcome = checks.run(MARCH, CloseCheckCode.GL_EQUALS_LEDGER, Actor.SYSTEM, correlation());
        assertThat(outcome.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(outcome.failures()).containsExactly(CheckOutcome.NO_MAPPING);
        assertThat(outcome.judgedBy()).isEqualTo("gl-mapping:none");
        assertThat(store.rows()).isEmpty();
    }

    // ------------------------------------------------------------------ source completeness

    @Test
    @DisplayName("a source discrepancy raises one SOURCE_DISCREPANCY on the proof's subject; a second run none; every"
            + " source read on a snapshot of its own")
    void aSourceDiscrepancyRaisesOneBreak() {
        sources.put(SourceProofs.Source.LENDING, snapshot -> List.of(
                new SourceProofs.Reading(SourceProofs.Proof.LOAN_SUBLEDGER, true,
                        List.of(new SourceProofs.Discrepancy("loan-9", "fails=[UNEXPLAINED_ENTRIES]"))),
                new SourceProofs.Reading(SourceProofs.Proof.LOAN_REPLAY, true, List.of())));

        CheckOutcome first = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());
        CheckOutcome second = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());

        assertThat(first.raised()).isEqualTo(1);
        assertThat(first.judgedBy()).isEqualTo("source-proofs:v1");
        assertThat(second.raised()).isZero();
        assertThat(second.knownOpen()).isEqualTo(1);
        assertThat(store.rows()).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(BreakType.SOURCE_DISCREPANCY);
            assertThat(row.subject()).isEqualTo("LOAN_SUBLEDGER:loan-9");
        });
        assertThat(sourceReads).isEqualTo(2 * SourceProofs.Source.values().length);
    }

    @Test
    @DisplayName("an unavailable proof is never a pass: a source that throws or answers incomplete fails the check, and"
            + " the other sources' findings are still raised")
    void anUnavailableProofFailsTheCheck() {
        CheckOutcome clean = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());
        assertThat(clean.passed()).as("every source answered, nothing found").isTrue();

        sources.put(SourceProofs.Source.LENDING, snapshot -> {
            throw new IllegalStateException("the lending proofs could not be read");
        });
        sources.put(SourceProofs.Source.PROJECTION, snapshot -> List.of(
                new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, false, List.of())));
        sources.put(SourceProofs.Source.FX_BOOKS, snapshot -> List.of(new SourceProofs.Reading(
                SourceProofs.Proof.FX_BOOKS, true, List.of(new SourceProofs.Discrepancy("FX_POSITION:EUR", "holds=false")))));

        CheckOutcome outcome = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());

        assertThat(outcome.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(outcome.failures()).containsExactly(CheckOutcome.DISCREPANCIES, "UNAVAILABLE:LOAN_SUBLEDGER",
                "UNAVAILABLE:LOAN_REPLAY", "UNAVAILABLE:PROJECTION");
        assertThat(store.rows()).extracting(AccountingBreakStore.BreakRow::subject)
                .containsExactly("FX_BOOKS:FX_POSITION:EUR");

        sources.put(SourceProofs.Source.FX_BOOKS, snapshot -> clean(SourceProofs.Source.FX_BOOKS));
        sources.put(SourceProofs.Source.PROJECTION, snapshot -> clean(SourceProofs.Source.PROJECTION));
        CheckOutcome unavailableAlone = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM,
                correlation());
        assertThat(unavailableAlone.failures()).as("nothing found any more, and lending still unread: FAILED all the same")
                .containsExactly("UNAVAILABLE:LOAN_SUBLEDGER", "UNAVAILABLE:LOAN_REPLAY");
        assertThat(unavailableAlone.result()).isEqualTo(CheckOutcome.Result.FAILED);
    }

    @Test
    @DisplayName("an unavailable proof alone - nothing found anywhere - still fails the check")
    void anUnavailableProofAloneFails() {
        sources.put(SourceProofs.Source.MERCHANT_PAYABLE, snapshot -> {
            throw new IllegalStateException("statement cancelled");
        });
        CheckOutcome outcome = checks.run(MARCH, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation());
        assertThat(outcome.findings()).isZero();
        assertThat(outcome.result()).isEqualTo(CheckOutcome.Result.FAILED);
        assertThat(outcome.failures()).containsExactly("UNAVAILABLE:MERCHANT_PAYABLE");
    }

    // ------------------------------------------------------------------ the callable's edges

    @Test
    @DisplayName("the close run's other six checks raise no break here; a person may run a check (a re-run); every raise"
            + " is its own transaction")
    void theCallablesEdges() {
        for (CloseCheckCode code : CloseCheckCode.values()) {
            if (!BreakFinding.CHECKS.contains(code)) {
                assertThatThrownBy(() -> checks.run(MARCH, code, Actor.SYSTEM, correlation()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("P14-TSK-011");
            }
        }
        entry("transfer:", "CUSTOMER_WALLET", "FEE_REVENUE");
        entry("transfer:", "CUSTOMER_WALLET", "FEE_REVENUE");
        checks.run(MARCH, CloseCheckCode.RULE_CONFORMANCE, new Actor("accountant-1", ActorType.CUSTOMER), correlation());
        assertThat(transactions).isEqualTo(2);
        assertThat(audit).allSatisfy(record -> assertThat(record.actor().id()).isEqualTo("accountant-1"));
    }

    // ------------------------------------------------------------------ fixtures

    private boolean servingOutsideTheMonth;
    private int sourceReads;

    /** The journal's entries of {@code range} after {@code after}, in keyset order - the adapter's contract. */
    private List<JournalShapes.EntryShape> page(Connection snapshot, JournalReads.Range range,
            Optional<JournalShapes.EntryCursor> after, int limit) {
        Comparator<JournalShapes.EntryShape> order = Comparator.comparing(JournalShapes.EntryShape::postingDate)
                .thenComparing((left, right) -> JournalReads.LineCursor.compareIds(left.id(), right.id()));
        return entries.stream()
                .filter(entry -> servingOutsideTheMonth || range.holds(entry.postingDate()))
                .filter(entry -> after.map(cursor -> entry.postingDate().isAfter(cursor.postingDate())
                        || (entry.postingDate().equals(cursor.postingDate())
                                && JournalReads.LineCursor.compareIds(entry.id(), cursor.entryId()) > 0)).orElse(true))
                .sorted(order)
                .limit(limit)
                .toList();
    }

    private List<SourceProofs.Reading> read(Connection snapshot, SourceProofs.Source source) {
        sourceReads++;
        return sources.get(source).apply(snapshot);
    }

    private static List<SourceProofs.Reading> clean(SourceProofs.Source source) {
        return source.proofs().stream().map(proof -> new SourceProofs.Reading(proof, true, List.of())).toList();
    }

    private void conforming(int count) {
        for (int i = 0; i < count; i++) {
            entry("transfer:", "CUSTOMER_WALLET", "CUSTOMER_WALLET");
        }
    }

    private JournalShapes.EntryShape entry(String namespace, String debit, String credit) {
        return entry(namespace, debit, credit, DAY);
    }

    private JournalShapes.EntryShape entry(String namespace, String debit, String credit, LocalDate day) {
        JournalShapes.EntryShape entry = new JournalShapes.EntryShape(IDS.next(), EntryType.POSTING, day,
                Optional.of(namespace), List.of(
                        new JournalShapes.ShapeLine(0, debit, Side.DEBIT, "EUR", 2, BigInteger.TEN),
                        new JournalShapes.ShapeLine(1, credit, Side.CREDIT, "EUR", 2, BigInteger.TEN)));
        entries.add(entry);
        return entry;
    }

    private void activate() {
        GlMappingVersionId id = GlMappingVersionId.next(IDS);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(id, 1, ReferenceGlMapping.version(MARCH),
                new Actor("accountant-1", ActorType.EMPLOYEE), "the reference", CorrelationId.of("flow-1"),
                UUID.randomUUID()));
        assertThat(mappings.decide(null, id, GlMappingVersionStatus.ACTIVE, new Actor("controller", ActorType.EMPLOYEE),
                "approved")).isTrue();
    }

    private static String[] keyOf(int rule) {
        return ReferenceGlMapping.version().rules().get(rule).key().split("/");
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
