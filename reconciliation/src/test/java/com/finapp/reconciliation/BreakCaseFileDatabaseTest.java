package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakTrace.NodeKind;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The investigator's case file and reads against the real schema (`P8-TSK-014`, ADR-0069 §7):
 * a trace from a break of EVERY subject kind reaching the settlement file and the journal
 * entries by stored identifiers alone — unchanged by sibling rows that share every timestamp
 * (one chunk, one fixed clock) — the settlement statuses reachable here, and the four commands
 * with their refusals and their ten-way races.
 *
 * <p>Settlement and payments live outside this module, so their side of the trace is a fake
 * {@link TraceEvidence} over the identifiers this suite's own seeding minted — the app tier's
 * composed suite walks the real ones. The suite owns a private source (the
 * `MatchingDatabaseTest` discipline).
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the break case file, trace and settlement status (P8-TSK-014)")
class BreakCaseFileDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final Actor INVESTIGATOR = new Actor("op-investigator-1", ActorType.EMPLOYEE);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID SOURCE =
            UUID.fromString("01a0e2bc-8200-7015-8000-000000000015");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7015-8000-000000000015");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 70_000);

    /** Settlement's side, as this suite minted it: line -> batch, batch -> (file, entry). */
    private static final Map<UUID, UUID> LINES = new ConcurrentHashMap<>();
    private static final Map<UUID, TraceEvidence.BatchFacts> BATCHES = new ConcurrentHashMap<>();
    /** Payments' side: operation reference -> the one evidence row this suite pretends exists. */
    private static final Map<String, UUID> EVIDENCE = new ConcurrentHashMap<>();
    /** External link targets this suite declares to exist. */
    private static final Set<UUID> KNOWN_TARGETS = ConcurrentHashMap.newKeySet();

    private static final TraceEvidence TRACE_EVIDENCE =
            new TraceEvidence() {
                @Override
                public Optional<BatchFacts> batch(Connection unitOfWork, UUID batchId) {
                    return Optional.ofNullable(BATCHES.get(batchId));
                }

                @Override
                public Optional<UUID> batchOfLine(Connection unitOfWork, UUID lineId) {
                    return Optional.ofNullable(LINES.get(lineId));
                }

                @Override
                public List<UUID> providerEvidence(
                        Connection unitOfWork, ExpectationKind kind, String operationRef) {
                    UUID evidence = EVIDENCE.get(operationRef);
                    return evidence == null ? List.of() : List.of(evidence);
                }
            };

    private static final EvidenceTargets TARGETS =
            (unitOfWork, kind, id) -> KNOWN_TARGETS.contains(id);

    /** The identities this suite declares active investigators - SEC-06's port, faked. */
    private static final UUID ALICE = IDS.next();
    private static final UUID BOB = IDS.next();
    private static final List<UUID> RACERS =
            java.util.stream.IntStream.range(0, 10).mapToObj(racer -> IDS.next()).toList();
    private static final Set<UUID> INVESTIGATORS =
            java.util.stream.Stream.concat(java.util.stream.Stream.of(ALICE, BOB),
                            RACERS.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Investigators ACTIVE_INVESTIGATORS =
            (unitOfWork, principal) -> INVESTIGATORS.contains(principal);

    private static Connection application;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static JdbcBreakRegister register;
    private static BreakCaseFile caseFile;
    private static BreakTraces traces;
    private static SettlementStatuses statuses;

    // The world order 1 builds, one run, one chunk, one fixed clock.
    private static UUID runId;
    private static UUID batchId;
    private static UUID fileId;
    private static UUID recognitionEntry;
    private static Seeded under;
    private static Seeded over;
    private static Seeded late;
    private static UUID breakOnExpectation;
    private static UUID breakOnItem;
    private static UUID breakOnSuspense;
    private static UUID breakOnDecision;
    private static UUID breakOnRun;

    private record Seeded(UUID expectationId, String operationRef, UUID entryId, String key) {}

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        register = new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        caseFile =
                new BreakCaseFile(
                        new JdbcBreakCaseStore(), TARGETS, ACTIVE_INVESTIGATORS,
                        new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
        traces = new BreakTraces(new JdbcBreakInquiries(), TRACE_EVIDENCE);
        statuses = new SettlementStatuses(new JdbcExpectationInquiries());
        seedPrivateRuleSet();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ----------------------------------------------------------------- the trace

    @Test
    @Order(1)
    @DisplayName("a trace from a break of EVERY subject kind reaches the settlement file and the"
            + " journal entries by stored identifiers - never a sibling that merely shares"
            + " its timestamps")
    void everySubjectKindTracesToTheFileAndTheEntries() throws Exception {
        under = openExpectation("CF-U", 100_00, SETTLED_ON.plusDays(3));
        over = openExpectation("CF-O", 60_00, SETTLED_ON.plusDays(3));
        late = openExpectation("CF-T", 25_00, SETTLED_ON.minusDays(10));
        runId =
                seedRun(
                        line(1, 60_00, under.key()),
                        line(2, 100_00, over.key()),
                        line(3, 25_00, late.key()));
        matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                runId)).isEqualTo("COMPLETED");

        breakOnExpectation = id("SELECT id FROM reconciliation.break WHERE expectation_id = ?"
                + " AND type = 'AMOUNT_MISMATCH'", under.expectationId());
        UUID overItem = itemOf(runId, 2);
        breakOnItem = id("SELECT id FROM reconciliation.break WHERE external_item_id = ?"
                + " AND type = 'AMOUNT_MISMATCH'", overItem);
        UUID suspenseItem = id("SELECT id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", overItem);
        UUID lateDecision = id("SELECT id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", itemOf(runId, 3));
        breakOnDecision = id("SELECT id FROM reconciliation.break WHERE decision_id = ?",
                lateDecision);
        breakOnSuspense = raise(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                new BreakRegister.Subject(Optional.empty(), Optional.empty(),
                        Optional.of(suspenseItem), Optional.empty(), Optional.empty()),
                40_00);
        breakOnRun = raise(BreakType.PROCESSING_ERROR, BreakCause.RUN_BLOCKED,
                BreakRegister.Subject.run(runId), 0);

        // The expectation subject: allocation -> item -> line -> batch -> file, and the
        // expectation's own completing entry and operation evidence.
        BreakTrace onExpectation = trace(breakOnExpectation);
        assertThat(reaches(onExpectation, NodeKind.SETTLEMENT_FILE, fileId)).isTrue();
        assertThat(reaches(onExpectation, NodeKind.JOURNAL_ENTRY, under.entryId())).isTrue();
        assertThat(reaches(onExpectation, NodeKind.JOURNAL_ENTRY, recognitionEntry)).isTrue();
        assertThat(reaches(onExpectation, NodeKind.OPERATION,
                "CARD_CAPTURE:" + under.operationRef())).isTrue();
        assertThat(reaches(onExpectation, NodeKind.PROVIDER_EVIDENCE,
                EVIDENCE.get(under.operationRef()))).isTrue();

        // The item subject: its line, run, batch, file, its park and the park's entry.
        BreakTrace onItem = trace(breakOnItem);
        UUID parkEntry = id("SELECT p.journal_entry_id FROM reconciliation.park p JOIN"
                + " reconciliation.suspense_item s ON s.park_id = p.id WHERE s.id = ?",
                suspenseItem);
        assertThat(reaches(onItem, NodeKind.SETTLEMENT_FILE, fileId)).isTrue();
        assertThat(reaches(onItem, NodeKind.SUSPENSE_ITEM, suspenseItem)).isTrue();
        assertThat(reaches(onItem, NodeKind.JOURNAL_ENTRY, parkEntry)).isTrue();
        assertThat(reaches(onItem, NodeKind.JOURNAL_ENTRY, over.entryId())).isTrue();

        // The suspense subject: back to its item and file, forward to its park, owner named.
        BreakTrace onSuspense = trace(breakOnSuspense);
        assertThat(reaches(onSuspense, NodeKind.SETTLEMENT_FILE, fileId)).isTrue();
        assertThat(reaches(onSuspense, NodeKind.JOURNAL_ENTRY, parkEntry)).isTrue();
        assertThat(reaches(onSuspense, NodeKind.BREAK, breakOnItem))
                .as("the suspense item's owning break is named, not walked")
                .isTrue();

        // The decision subject: the item it decided, its allocation, the expectation's entry.
        BreakTrace onDecision = trace(breakOnDecision);
        assertThat(reaches(onDecision, NodeKind.SETTLEMENT_FILE, fileId)).isTrue();
        assertThat(reaches(onDecision, NodeKind.JOURNAL_ENTRY, late.entryId())).isTrue();

        // The run subject: its batch, the file and the recognition entry.
        BreakTrace onRun = trace(breakOnRun);
        assertThat(reaches(onRun, NodeKind.SETTLEMENT_FILE, fileId)).isTrue();
        assertThat(reaches(onRun, NodeKind.JOURNAL_ENTRY, recognitionEntry)).isTrue();

        // THE TIMESTAMP DECOYS: all three decisions were written by one chunk under one fixed
        // clock - identical decided_at - so a time-joined walk would drag the siblings in.
        Set<String> decisionsOnTheLateTrace = nodes(onDecision, NodeKind.DECISION);
        assertThat(decisionsOnTheLateTrace)
                .as("only the decision the break names - never its same-instant siblings")
                .containsExactly(lateDecision.toString());
        assertThat(nodes(onItem, NodeKind.DECISION))
                .containsExactlyElementsOf(
                        decisionsOf(overItem).stream().map(UUID::toString).toList());
        assertThat(nodes(onExpectation, NodeKind.EXTERNAL_ITEM))
                .containsExactly(itemOf(runId, 1).toString());

        // More rows at the same instants - another run, another expectation - change nothing.
        Seeded decoy = openExpectation("CF-DECOY", 60_00, SETTLED_ON.plusDays(3));
        Seeded decoyToo = openExpectation("CF-DECOY2", 30_00, SETTLED_ON.minusDays(10));
        seedRun(line(1, 60_00, decoy.key()), line(2, 30_00, decoyToo.key()));
        matching().sweep();
        assertThat(trace(breakOnDecision)).isEqualTo(onDecision);
        assertThat(trace(breakOnItem)).isEqualTo(onItem);
        assertThat(trace(breakOnRun)).isEqualTo(onRun);
    }

    // ----------------------------------------------------------------- settlement status

    @Test
    @Order(2)
    @DisplayName("settlement status for each state reachable here, derived with its trail:"
            + " PENDING, REPORTED, OVERDUE, RESOLVED")
    void settlementStatusIsDerivedWithItsTrail() throws SQLException {
        SettlementStatuses.Trail reported = status(over.operationRef());
        assertThat(reported.status()).isEqualTo(SettlementStatus.REPORTED);
        assertThat(reported.itemIds()).containsExactly(itemOf(runId, 2));
        assertThat(reported.batchIds()).containsExactly(batchId);
        assertThat(reported.remittanceIds())
                .as("no remittance for this batch yet: reported, not cash-confirmed")
                .isEmpty();

        Seeded pending = openExpectation("CF-P", 10_00, SETTLED_ON.plusDays(3));
        assertThat(status(pending.operationRef()).status()).isEqualTo(SettlementStatus.PENDING);
        assertThat(status(pending.operationRef()).allocationIds()).isEmpty();

        Seeded overdue = openExpectation("CF-D", 10_00, SETTLED_ON.minusDays(60));
        inCommittedTransaction(uow -> {
            execute(uow, "UPDATE reconciliation.expectation SET"
                + " overdue_since = now(), status_changed_at = now() WHERE id = ?",
                overdue.expectationId());
            return null;
        });
        assertThat(status(overdue.operationRef()).status()).isEqualTo(SettlementStatus.OVERDUE);

        Seeded resolved = openExpectation("CF-R", 10_00, SETTLED_ON.plusDays(3));
        inCommittedTransaction(uow -> {
            execute(uow, "UPDATE reconciliation.expectation SET"
                + " status = 'RESOLVED_BY_ADJUSTMENT', resolved_minor = amount_minor,"
                + " status_changed_at = now() WHERE id = ?", resolved.expectationId());
            return null;
        });
        assertThat(status(resolved.operationRef()).status())
                .isEqualTo(SettlementStatus.RESOLVED);

        // A partially reported operation inside its window is still PENDING.
        assertThat(status(under.operationRef()).status()).isEqualTo(SettlementStatus.PENDING);

        Optional<SettlementStatuses.Trail> none = inReadTransaction(uow -> statuses.of(
                uow, ExpectationKind.CARD_CAPTURE, "op-nobody-" + UUID.randomUUID()));
        assertThat(none).as("an operation no expectation tracks has no status").isEmpty();
        assertThatThrownBy(() -> inReadTransaction(uow -> statuses.of(
                        uow, ExpectationKind.REMITTANCE, batchId.toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- assignment

    @Test
    @Order(3)
    @DisplayName("the first assignment opens the investigation once; a repeat converges, its"
            + " identifier canonical; a reassignment appends history and publishes nothing; an"
            + " assignee that is no active investigator's identifier - a card number, a free-text"
            + " id, an unknown identity - is refused with nothing written or published (SEC-06)")
    void assignmentOpensTheInvestigationOnce() throws SQLException {
        CorrelationId opening = CorrelationId.generate(IDS);
        BreakCaseFile.Assigned first = command(uow -> caseFile.assign(
                uow, breakOnItem, ALICE.toString(), INVESTIGATOR, opening));
        assertThat(first.changed()).isTrue();
        assertThat(first.started()).isTrue();
        assertThat(first.row().status()).isEqualTo(BreakStatus.INVESTIGATING);
        assertThat(first.row().assignee()).contains(ALICE.toString());
        assertThat(started(breakOnItem)).isEqualTo(1);
        assertThat(string("SELECT causation_id FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakInvestigationStarted' AND aggregate_id = ?",
                breakOnItem))
                .as("the assigning flow is the cause, never the break itself (ARCH-P8-04)")
                .isEqualTo(opening.value());

        BreakCaseFile.Assigned repeat = command(uow -> caseFile.assign(
                uow, breakOnItem, ALICE.toString().toUpperCase(java.util.Locale.ROOT),
                INVESTIGATOR, CorrelationId.generate(IDS)));
        assertThat(repeat.changed())
                .as("the standing assignee converges, whatever the identifier's case")
                .isFalse();

        BreakCaseFile.Assigned handover = command(uow -> caseFile.assign(
                uow, breakOnItem, BOB.toString(), INVESTIGATOR, CorrelationId.generate(IDS)));
        assertThat(handover.started()).isFalse();
        assertThat(handover.row().assignee()).contains(BOB.toString());
        assertThat(started(breakOnItem))
                .as("a reassignment publishes nothing")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                + " AND event_type = 'ASSIGNED'", breakOnItem)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakAssigned' AND target_id = ?",
                breakOnItem.toString())).isEqualTo(2);

        // SEC-06: what is no active investigator's identifier never reaches the row or an event.
        String pan = "4111111111111111";
        for (String refused : List.of("not an identifier!", pan, "op-alice",
                IDS.next().toString())) {
            assertThatThrownBy(() -> command(uow -> caseFile.assign(
                            uow, breakOnItem, refused, INVESTIGATOR,
                            CorrelationId.generate(IDS))))
                    .as(refused)
                    .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                    .hasMessageNotContaining(refused);
        }
        assertThat(string("SELECT assignee FROM reconciliation.break WHERE id = ?", breakOnItem))
                .as("the refused assignees wrote nothing")
                .isEqualTo(BOB.toString());
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                + " AND event_type = 'ASSIGNED'", breakOnItem)).isEqualTo(2);
        assertThat(started(breakOnItem)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                + " convert_from(payload, 'UTF8') LIKE '%" + pan + "%'"))
                .as("the card number reached no event")
                .isZero();
        assertThatThrownBy(() -> command(uow -> caseFile.assign(
                        uow, UUID.randomUUID(), ALICE.toString(), INVESTIGATOR,
                        CorrelationId.generate(IDS))))
                .isInstanceOf(BreakCaseFile.BreakNotFound.class);
    }

    @Test
    @Order(4)
    @DisplayName("ten racing first assignments publish ONE BreakInvestigationStarted - the"
            + " break row serialises them, every loser a recorded handover")
    void tenRacingFirstAssignmentsPublishOnce() throws Exception {
        List<Future<BreakCaseFile.Assigned>> outcomes = race(10, racer -> command(uow ->
                caseFile.assign(uow, breakOnRun, RACERS.get(racer).toString(), INVESTIGATOR,
                        CorrelationId.generate(IDS))));
        int startedCount = 0;
        for (Future<BreakCaseFile.Assigned> outcome : outcomes) {
            if (outcome.get().started()) {
                startedCount++;
            }
        }
        assertThat(startedCount).as("one winner opens the investigation").isEqualTo(1);
        assertThat(started(breakOnRun)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                + " AND event_type = 'ASSIGNED'", breakOnRun)).isEqualTo(10);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakOnRun))
                .isEqualTo("INVESTIGATING");
    }

    // ----------------------------------------------------------------- notes

    @Test
    @Order(5)
    @DisplayName("a note is appended with its body in the note row alone; a card-number or"
            + " account shape is refused at the domain AND by a raw INSERT for any writer")
    void notesAreScreenedAtBothRanks() throws SQLException {
        String needle = "needle-" + UUID.randomUUID();
        BreakCaseFile.NoteAdded added = command(uow -> caseFile.addNote(
                uow, breakOnItem, "PSP says the excess is a duplicate " + needle,
                INVESTIGATOR, CorrelationId.generate(IDS)));
        assertThat(string("SELECT body FROM reconciliation.break_note WHERE id = ?",
                added.noteId())).contains(needle);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE"
                + " change_summary LIKE '%" + needle + "%' OR reason LIKE '%" + needle + "%'"))
                .as("the body never reaches an audit record")
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                + " convert_from(payload, 'UTF8') LIKE '%" + needle + "%'"))
                .as("the body never reaches an event")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE"
                + " detail LIKE '%" + needle + "%' OR reason LIKE '%" + needle + "%'"))
                .isZero();

        long before = count("SELECT count(*) FROM reconciliation.break_note WHERE"
                + " break_id = ?", breakOnItem);
        assertThatThrownBy(() -> command(uow -> caseFile.addNote(
                        uow, breakOnItem, "card 4111111111111111", INVESTIGATOR,
                        CorrelationId.generate(IDS))))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> command(uow -> caseFile.addNote(
                        uow, breakOnItem, "to GB82WESTABCDEFGHIJKLM", INVESTIGATOR,
                        CorrelationId.generate(IDS))))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class);
        assertThat(count("SELECT count(*) FROM reconciliation.break_note WHERE"
                + " break_id = ?", breakOnItem))
                .as("nothing stored for a refused body")
                .isEqualTo(before);

        // The database rank, for a writer that bypasses the domain.
        for (String body : List.of("card 4111111111111111", "to GB82WESTABCDEFGHIJKLM")) {
            try (Connection raw = DatabaseRoles.application()) {
                raw.setAutoCommit(false);
                assertThatThrownBy(() -> execute(raw,
                                "INSERT INTO reconciliation.break_note (id, break_id, body,"
                                        + " author, author_type, added_at, correlation_id)"
                                        + " VALUES (?, ?, ?, 'raw', 'EMPLOYEE', now(), 'raw')",
                                IDS.next(), breakOnItem, body))
                        .hasStackTraceContaining("break_note_no_");
                raw.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- evidence links

    @Test
    @Order(6)
    @DisplayName("evidence links by identifier, each target verified to exist - a link to"
            + " nothing is refused, never stored dangling")
    void evidenceLinksAreVerified() throws SQLException {
        KNOWN_TARGETS.add(over.entryId());
        BreakCaseFile.EvidenceLinked entry = command(uow -> caseFile.link(
                uow, breakOnItem, EvidenceTargetKind.JOURNAL_ENTRY, over.entryId().toString(),
                INVESTIGATOR, CorrelationId.generate(IDS)));
        assertThat(entry.linkId()).isNotNull();
        command(uow -> caseFile.link(uow, breakOnItem, EvidenceTargetKind.RUN,
                runId.toString(), INVESTIGATOR, CorrelationId.generate(IDS)));
        command(uow -> caseFile.link(uow, breakOnItem, EvidenceTargetKind.OPERATION,
                "CARD_CAPTURE:" + over.operationRef(), INVESTIGATOR,
                CorrelationId.generate(IDS)));
        assertThat(count("SELECT count(*) FROM reconciliation.break_evidence_link WHERE"
                + " break_id = ?", breakOnItem)).isEqualTo(3);

        for (Function<Connection, BreakCaseFile.EvidenceLinked> dangling : List.<Function<Connection, BreakCaseFile.EvidenceLinked>>of(
                uow -> caseFile.link(uow, breakOnItem, EvidenceTargetKind.JOURNAL_ENTRY,
                        UUID.randomUUID().toString(), INVESTIGATOR, CorrelationId.generate(IDS)),
                uow -> caseFile.link(uow, breakOnItem, EvidenceTargetKind.DECISION,
                        UUID.randomUUID().toString(), INVESTIGATOR, CorrelationId.generate(IDS)),
                uow -> caseFile.link(uow, breakOnItem, EvidenceTargetKind.OPERATION,
                        "CARD_CAPTURE:op-nobody", INVESTIGATOR, CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> command(dangling))
                    .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                    .hasMessageContaining("never stored dangling");
        }
        assertThat(count("SELECT count(*) FROM reconciliation.break_evidence_link WHERE"
                + " break_id = ?", breakOnItem)).isEqualTo(3);
    }

    // ----------------------------------------------------------------- reclassification

    @Test
    @Order(7)
    @DisplayName("reclassification: reasoned, severity never lower, residual version moved; only"
            + " onto a type that stands on the subject and parks as the subject does; never"
            + " onto an occupied seat")
    void reclassificationFollowsTheTaxonomy() throws SQLException {
        BreakCaseStore.BreakRow before = breakRow(breakOnItem);
        BreakCaseFile.Reclassified moved = command(uow -> caseFile.reclassify(
                uow, breakOnItem, BreakType.UNKNOWN_EXTERNAL,
                "the PSP knows no such capture", INVESTIGATOR, CorrelationId.generate(IDS)));
        assertThat(moved.changed()).isTrue();
        assertThat(moved.row().type()).isEqualTo(BreakType.UNKNOWN_EXTERNAL);
        assertThat(moved.row().cause()).as("the cause is frozen").isEqualTo(before.cause());
        assertThat(moved.row().valueAtIssueMinor()).isEqualTo(before.valueAtIssueMinor());
        assertThat(moved.row().severity().ordinal())
                .isGreaterThanOrEqualTo(before.severity().ordinal());
        assertThat(moved.row().residualVersion()).isEqualTo(before.residualVersion() + 1);
        assertThat(string("SELECT reason FROM reconciliation.break_event WHERE break_id = ?"
                + " AND event_type = 'RECLASSIFIED'", breakOnItem))
                .isEqualTo("the PSP knows no such capture");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakReclassified' AND target_id = ? AND reason IS NOT NULL",
                breakOnItem.toString())).isEqualTo(1);

        BreakCaseFile.Reclassified again = command(uow -> caseFile.reclassify(
                uow, breakOnItem, BreakType.UNKNOWN_EXTERNAL, "again", INVESTIGATOR,
                CorrelationId.generate(IDS)));
        assertThat(again.changed()).as("the standing type converges").isFalse();

        assertThatThrownBy(() -> command(uow -> caseFile.reclassify(
                        uow, breakOnItem, BreakType.MISSING_EXTERNAL, "wrong subject",
                        INVESTIGATOR, CorrelationId.generate(IDS))))
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                .hasMessageContaining("does not stand on");
        assertThatThrownBy(() -> command(uow -> caseFile.reclassify(
                        uow, breakOnItem, BreakType.FEE_MISMATCH, "a fee owns no suspense",
                        INVESTIGATOR, CorrelationId.generate(IDS))))
                .as("the subject holds parked value; a fee type would orphan it (INV-REC-09)")
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                .hasMessageContaining("INV-REC-09");
        assertThatThrownBy(() -> command(uow -> caseFile.reclassify(
                        uow, breakOnItem, BreakType.CURRENCY_MISMATCH, "card 4111111111111111",
                        INVESTIGATOR, CorrelationId.generate(IDS))))
                .as("the reason is screened like a note")
                .isInstanceOf(BreakCaseFile.CaseFileRefused.class);

        // The occupied seat: a DUPLICATE_EXTERNAL already stands open on the same item.
        raise(BreakType.DUPLICATE_EXTERNAL, BreakCause.EXPECTATION_EXHAUSTED,
                BreakRegister.Subject.externalItem(itemOf(runId, 2)), 1_00);
        assertThatThrownBy(() -> command(uow -> caseFile.reclassify(
                        uow, breakOnItem, BreakType.DUPLICATE_EXTERNAL, "a duplicate",
                        INVESTIGATOR, CorrelationId.generate(IDS))))
                .isInstanceOf(BreakCaseFile.CaseFileConflict.class)
                .hasMessageContaining("already stands open");
    }

    @Test
    @Order(8)
    @DisplayName("reclassification only in OPEN and INVESTIGATING: a proposed resolution's"
            + " frozen lines depend on the type")
    void reclassificationIsRefusedOnceAResolutionIsProposed() throws SQLException {
        inCommittedTransaction(uow -> {
            execute(uow, "UPDATE reconciliation.break SET status ="
                + " 'RESOLUTION_PROPOSED', status_changed_at = now() WHERE id = ?",
                breakOnRun);
            return null;
        });
        assertThatThrownBy(() -> command(uow -> caseFile.reclassify(
                        uow, breakOnRun, BreakType.SETTLEMENT_MISMATCH, "a statement gap",
                        INVESTIGATOR, CorrelationId.generate(IDS))))
                .isInstanceOf(BreakCaseFile.CaseFileConflict.class)
                .hasMessageContaining("resolution proposed");
        assertThat(string("SELECT type FROM reconciliation.break WHERE id = ?", breakOnRun))
                .isEqualTo("PROCESSING_ERROR");
    }

    @Test
    @Order(9)
    @DisplayName("ten racing reclassifications of one break move it ONCE - one event, one"
            + " residual step - the losers converge on the type they asked for")
    void tenRacingReclassificationsMoveOnce() throws Exception {
        BreakCaseStore.BreakRow before = breakRow(breakOnDecision);
        List<Future<BreakCaseFile.Reclassified>> outcomes = race(10, racer -> command(uow ->
                caseFile.reclassify(uow, breakOnDecision, BreakType.PROCESSING_ERROR,
                        "replayed decision diverged " + racer, INVESTIGATOR,
                        CorrelationId.generate(IDS))));
        int changed = 0;
        for (Future<BreakCaseFile.Reclassified> outcome : outcomes) {
            if (outcome.get().changed()) {
                changed++;
            }
        }
        assertThat(changed).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                + " AND event_type = 'RECLASSIFIED'", breakOnDecision)).isEqualTo(1);
        BreakCaseStore.BreakRow after = breakRow(breakOnDecision);
        assertThat(after.type()).isEqualTo(BreakType.PROCESSING_ERROR);
        assertThat(after.residualVersion()).isEqualTo(before.residualVersion() + 1);
    }

    @Test
    @Order(10)
    @DisplayName("a RESOLVED break takes no write: assignment, note, link and reclassification"
            + " all answer BreakTerminal")
    void aResolvedBreakTakesNoWrite() throws SQLException {
        // The under-payment's remaining 40.00 arrives as the PSP's correction: the break
        // resolves EVIDENCED (P8-TSK-012), terminal from here.
        seedRun(new Line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT, 40_00, ItemKeyKind.ORIGINAL_REF,
                under.key()));
        matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                breakOnExpectation)).isEqualTo("RESOLVED");

        List<Function<Connection, Object>> writes = List.of(
                uow -> caseFile.assign(uow, breakOnExpectation, ALICE.toString(), INVESTIGATOR,
                        CorrelationId.generate(IDS)),
                uow -> caseFile.addNote(uow, breakOnExpectation, "too late", INVESTIGATOR,
                        CorrelationId.generate(IDS)),
                uow -> caseFile.link(uow, breakOnExpectation, EvidenceTargetKind.RUN,
                        runId.toString(), INVESTIGATOR, CorrelationId.generate(IDS)),
                uow -> caseFile.reclassify(uow, breakOnExpectation,
                        BreakType.SETTLEMENT_MISMATCH, "too late", INVESTIGATOR,
                        CorrelationId.generate(IDS)));
        for (Function<Connection, Object> write : writes) {
            assertThatThrownBy(() -> command(write))
                    .isInstanceOf(BreakCaseFile.BreakTerminal.class);
        }
        BreakTrace resolved = trace(breakOnExpectation);
        assertThat(nodes(resolved, NodeKind.RESOLUTION))
                .as("the trace reaches the resolution that closed it")
                .hasSize(1);
    }

    @Test
    @Order(11)
    @DisplayName("a reclassification is refused - 422, nothing written - unless the frozen cause"
            + " keeps an exit on the target type: a STATEMENT_GAP onto PROCESSING_ERROR, a"
            + " RUN_BLOCKED onto SETTLEMENT_MISMATCH, an EXPECTATION_OVERDUE onto"
            + " SETTLEMENT_MISMATCH (the Phase 8 -> 9 transition); a type the cause's own"
            + " evidence still finds is admitted")
    void aReclassificationNeverStrandsTheBreak() throws Exception {
        // Two runs of their own, each completed by the matcher (a line no key reaches), so no
        // other break holds a seat on them and only the exit rule can refuse.
        UUID gapRun = seedRun(line(1, 3_00, "CF-RC-GAP-" + UUID.randomUUID()));
        UUID blockedRun = seedRun(line(1, 4_00, "CF-RC-BLK-" + UUID.randomUUID()));
        matching().sweep();
        for (UUID run : List.of(gapRun, blockedRun)) {
            assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE"
                    + " id = ?", run)).isEqualTo("COMPLETED");
        }
        UUID gap = raise(BreakType.SETTLEMENT_MISMATCH, BreakCause.STATEMENT_GAP,
                BreakRegister.Subject.run(gapRun), 7_00);
        UUID blocked = raise(BreakType.PROCESSING_ERROR, BreakCause.RUN_BLOCKED,
                BreakRegister.Subject.run(blockedRun), 0);
        Seeded overdueCapture = openExpectation("CF-RC-OD", 35_00, SETTLED_ON.plusDays(3));
        UUID overdue = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(overdueCapture.expectationId()), 35_00);

        record Shape(String name, UUID breakId, BreakType from, BreakType to) {}
        for (Shape shape : List.of(
                // (a) the gap's closer selects SETTLEMENT_MISMATCH: as PROCESSING_ERROR the
                // filling statement passes it by, and no kind admits a statement cause.
                new Shape("(a) STATEMENT_GAP", gap, BreakType.SETTLEMENT_MISMATCH,
                        BreakType.PROCESSING_ERROR),
                // (b) the requeued run's completion selects PROCESSING_ERROR.
                new Shape("(b) RUN_BLOCKED", blocked, BreakType.PROCESSING_ERROR,
                        BreakType.SETTLEMENT_MISMATCH),
                // (c) the settling allocation's closers select MISSING_EXTERNAL and the
                // capture's AMOUNT_MISMATCH; SETTLEMENT_MISMATCH admits no kind for the cause.
                new Shape("(c) EXPECTATION_OVERDUE", overdue, BreakType.MISSING_EXTERNAL,
                        BreakType.SETTLEMENT_MISMATCH))) {
            BreakCaseStore.BreakRow before = breakRow(shape.breakId());
            assertThatThrownBy(() -> command(uow -> caseFile.reclassify(
                            uow, shape.breakId(), shape.to(), "looks like another kind of break",
                            INVESTIGATOR, CorrelationId.generate(IDS))))
                    .as(shape.name() + " onto " + shape.to() + " would strand the break: refused")
                    .isInstanceOf(BreakCaseFile.CaseFileRefused.class)
                    .hasMessageContaining("no exit");
            BreakCaseStore.BreakRow after = breakRow(shape.breakId());
            assertThat(after.type()).as(shape.name() + ": the type stands").isEqualTo(shape.from());
            assertThat(after.residualVersion())
                    .as(shape.name() + ": nothing moved").isEqualTo(before.residualVersion());
            assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                    + " AND event_type = 'RECLASSIFIED'", shape.breakId()))
                    .as(shape.name() + ": no history row").isZero();
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                    + " 'reconciliation.BreakReclassified' AND target_id = ?",
                    shape.breakId().toString()))
                    .as(shape.name() + ": no audit record").isZero();
        }

        // A type the cause's own evidence still finds keeps the exit: the capture's settling
        // allocation selects its AMOUNT_MISMATCH, and its remainder admits a write-off there.
        BreakCaseFile.Reclassified kept = command(uow -> caseFile.reclassify(
                uow, overdue, BreakType.AMOUNT_MISMATCH, "a short payment, not a missing one",
                INVESTIGATOR, CorrelationId.generate(IDS)));
        assertThat(kept.changed()).isTrue();
        assertThat(kept.row().type()).isEqualTo(BreakType.AMOUNT_MISMATCH);
    }

    // ----------------------------------------------------------------- seeding

    private record Line(
            int lineNo, ExternalLineType type, long minor, ItemKeyKind keyKind, String key) {}

    private static Line line(int lineNo, long minor, String key) {
        return new Line(lineNo, ExternalLineType.CAPTURE, minor, ItemKeyKind.PSP_CAPTURE_REF, key);
    }

    private static UUID seedRun(Line... lines) throws SQLException {
        UUID newRun = IDS.next();
        UUID newBatch = IDS.next();
        UUID newFile = IDS.next();
        UUID newEntry = IDS.next();
        BATCHES.put(newBatch, new TraceEvidence.BatchFacts(newFile, Optional.of(newEntry)));
        if (batchId == null) {
            batchId = newBatch;
            fileId = newFile;
            recognitionEntry = newEntry;
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            newRun, SOURCE, Optional.of(newBatch), RunKind.BATCH, RULE_SET,
                            SETTLED_ON, Optional.of(SEQUENCES.incrementAndGet()), lines.length,
                            Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                byte[] fingerprint = new byte[32];
                new SecureRandom().nextBytes(fingerprint);
                UUID lineId = IDS.next();
                LINES.put(lineId, newBatch);
                ExpectationDirection direction =
                        line.type() == ExternalLineType.CAPTURE
                                        || line.type() == ExternalLineType.COUNTERPARTY_ADJUSTMENT
                                ? ExpectationDirection.INBOUND
                                : ExpectationDirection.OUTBOUND;
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(), newRun, SOURCE, lineId, line.lineNo(), line.type(),
                                direction, Money.ofPersisted(line.minor(), EUR, 2),
                                AccountPurpose.SETTLEMENT_CLEARING, SETTLED_ON,
                                Optional.of(SETTLED_ON), Optional.of(SETTLED_ON), fingerprint,
                                Map.of(line.keyKind(), line.key()), Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            items.birthAll(app, PLATFORM, newItems);
            app.commit();
        }
        return newRun;
    }

    private static Seeded openExpectation(String prefix, long minor, LocalDate expectedBy)
            throws SQLException {
        String key = prefix + "-" + UUID.randomUUID();
        String operationRef = UUID.randomUUID().toString();
        UUID entry = IDS.next();
        EVIDENCE.put(operationRef, IDS.next());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            expectations.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE, operationRef, "p8t14:" + operationRef,
                            SOURCE, AccountPurpose.SETTLEMENT_CLEARING, position,
                            ExpectationDirection.INBOUND, Money.ofPersisted(minor, EUR, 2),
                            Optional.of(entry), SETTLED_ON, Optional.empty(), expectedBy,
                            RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.PSP_CAPTURE_REF, key)),
                            PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
        UUID id = (UUID) one("SELECT id FROM reconciliation.expectation WHERE operation_ref = ?"
                + " AND kind = 'CARD_CAPTURE'", operationRef);
        return new Seeded(id, operationRef, entry, key);
    }

    private static UUID raise(BreakType type, BreakCause cause, BreakRegister.Subject subject,
            long minor) throws SQLException {
        UUID breakId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            register.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, type, cause, subject, SOURCE, RULE_SET,
                            Money.ofPersisted(minor, EUR, 2), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
        }
        return breakId;
    }

    private static void seedPrivateRuleSet() throws SQLException {
        inCommittedTransaction(app -> {
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?, 'test', NULL,"
                            + " 'BreakCaseFileDatabaseTest private rule set', now(),"
                            + " 'p8-tsk-014-test') ON CONFLICT (id) DO NOTHING",
                    RULE_SET, SOURCE, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES"
                            + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 2, 'COUNTERPARTY_ADJUSTMENT', 'ORIGINAL_REF', NULL,"
                            + " 'CORRECTION', false, 48) ON CONFLICT DO NOTHING",
                    RULE_SET, RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL,"
                            + " NULL, 2) ON CONFLICT DO NOTHING",
                    RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    RULE_SET);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                            + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'",
                    RULE_SET);
            return null;
        });
    }

    private static Matching matching() {
        return new Matching(
                new JdbcMatchingStore(), new MatchingRules(), register,
                new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS),
                ResolutionFixtures.resolutions(IDS, CLOCK),
                (unitOfWork, subject) -> InternalReferenceLookup.InternalReference.unknown(),
                new JdbcLedgerAccountStore(), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS,
                CLOCK, new Matching.Config(200, 2), runner());
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
                        Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private static TransactionRunner runner() {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return inCommittedTransaction(work::apply);
            }
        };
    }

    // ----------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface Work<R> {
        R apply(Connection unitOfWork) throws SQLException;
    }

    private static <R> R inCommittedTransaction(Work<R> work) {
        try (Connection unitOfWork = DatabaseRoles.application()) {
            unitOfWork.setAutoCommit(false);
            try {
                R result = work.apply(unitOfWork);
                unitOfWork.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                unitOfWork.rollback();
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : new ReconciliationStorageException("test transaction failed", failure);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("test transaction failed", failure);
        }
    }

    private static <R> R command(Function<Connection, R> work) {
        return inCommittedTransaction(work::apply);
    }

    private static <R> R inReadTransaction(Function<Connection, R> work) {
        try (Connection unitOfWork = DatabaseRoles.application()) {
            unitOfWork.setAutoCommit(false);
            unitOfWork.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return work.apply(unitOfWork);
            } finally {
                unitOfWork.rollback();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("test read failed", failure);
        }
    }

    private static <R> List<Future<R>> race(int racers, java.util.function.IntFunction<R> act)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<R>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < racers; racer++) {
                int index = racer;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return act.apply(index);
                }));
            }
            start.countDown();
            for (Future<R> outcome : outcomes) {
                outcome.get();
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static BreakTrace trace(UUID breakId) {
        return inReadTransaction(uow -> traces.trace(uow, breakId)).orElseThrow();
    }

    private static SettlementStatuses.Trail status(String operationRef) {
        return inReadTransaction(uow -> statuses.of(uow, ExpectationKind.CARD_CAPTURE,
                operationRef)).orElseThrow();
    }

    private static BreakCaseStore.BreakRow breakRow(UUID breakId) {
        return inReadTransaction(uow -> new JdbcBreakInquiries().breakById(uow, breakId))
                .orElseThrow();
    }

    private static boolean reaches(BreakTrace trace, NodeKind kind, Object id) {
        return trace.steps().stream()
                .anyMatch(step -> step.toKind() == kind && step.toId().equals(id.toString()));
    }

    private static Set<String> nodes(BreakTrace trace, NodeKind kind) {
        return trace.steps().stream()
                .filter(step -> step.toKind() == kind)
                .map(BreakTrace.Step::toId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static List<UUID> decisionsOf(UUID itemId) throws SQLException {
        List<UUID> found = new ArrayList<>();
        try (PreparedStatement read = application.prepareStatement(
                "SELECT id FROM reconciliation.match_decision WHERE external_item_id = ?"
                        + " ORDER BY id")) {
            read.setObject(1, itemId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return found;
    }

    private static UUID itemOf(UUID run, int lineNo) throws SQLException {
        return id("SELECT id FROM reconciliation.external_item WHERE run_id = ? AND line_no = "
                + lineNo, run);
    }

    private static long started(UUID breakId) throws SQLException {
        return count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakInvestigationStarted' AND aggregate_id = ?", breakId);
    }

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    private static UUID id(String sql, Object... args) throws SQLException {
        return (UUID) one(sql, args);
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        return (String) one(sql, args);
    }

    private static Object one(String sql, Object... args) throws SQLException {
        application.rollback();
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }
}
