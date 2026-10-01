package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V013` reconciled against the vocabularies that generate it, and proven at the database rank
 * against the real schema (`P8-TSK-023`, ADR-0065 §10, ADR-0070 §10, ADR-0071; INV-REV-01,
 * INV-REC-07, INV-REC-09): the resolution's {@code REPUDIATE_BATCH} kind and its batch subject -
 * exactly one subject, the plan's digest, one proposal and one approval per batch, both frozen
 * with the proposal; the counter-allocation bound once to its exact mirror; the item's two new
 * edges and no others; the expectation's reopening recorded; the suspense item's fourth opener;
 * and the closure record, append-only, naming only a repudiation.
 *
 * <p>The text cases are hermetic in substance; the class is tagged {@code database} because the
 * taxonomy places a class by its heaviest member. Every live probe runs inside one transaction
 * per test, refusals behind savepoints, and is rolled back at the end - nothing is committed, so
 * the deferred allocation sums judge only where a probe asks them to ({@code SET CONSTRAINTS ALL
 * IMMEDIATE}), and no other suite ever sees a row of this one. Each test seeds a private source.
 */
@Tag("database")
@DisplayName("reconciliation V013 reconciliation and its live rank (P8-TSK-023)")
class ReconciliationV013MigrationTest {

    private static final String V013 =
            "db/migration/reconciliation/V013__the_repudiated_batch.sql";
    private static final String V009 =
            "db/migration/reconciliation/V009__scheme_items_and_the_learned_cycle.sql";

    /** The PSP's seeded rule set v1 (V002): referenced by foreign key only, never by status. */
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-09-29");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EDGE =
            Pattern.compile("\\(OLD\\.status = '([A-Z_]+)' AND NEW\\.status IN \\(([^)]*)\\)\\)");

    // ================================================================= the text

    @Test
    @DisplayName("the resolution's kind, reason and pairing CHECKs are the enums' whole lists,"
            + " REPUDIATE_BATCH pairs with EVIDENCE_REPUDIATED alone, and the batch subject is"
            + " stated exactly once")
    void theResolutionVocabularyIsRegenerated() {
        String sql = normalized(migration(V013));
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT resolution_kind CHECK (kind IN ("
                                + ResolutionKind.sqlValueList() + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_reason_code CHECK (reason_code IN ("
                                + ResolutionReasonCode.sqlValueList() + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_kind_reason_pairing CHECK ("
                                + ResolutionKind.sqlReasonPairingRule(
                                        EnumSet.allOf(ResolutionKind.class))
                                + ")"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_one_subject CHECK ("
                                + " num_nonnulls(break_id, settlement_batch_id) = 1)"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_batch_subject_is_repudiation CHECK ("
                                + " (settlement_batch_id IS NOT NULL) = (kind ="
                                + " 'REPUDIATE_BATCH'))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_repudiation_carries_its_digest CHECK ("
                                + " (kind = 'REPUDIATE_BATCH') = (subject_digest IS NOT NULL))"))
                .contains("octet_length(subject_digest) = 32")
                .contains("CREATE UNIQUE INDEX resolution_one_proposed_per_batch ON"
                        + " reconciliation.resolution (settlement_batch_id) WHERE status ="
                        + " 'PROPOSED' AND settlement_batch_id IS NOT NULL;")
                .contains("CREATE UNIQUE INDEX resolution_batch_repudiated_once ON"
                        + " reconciliation.resolution (settlement_batch_id) WHERE status ="
                        + " 'APPROVED' AND settlement_batch_id IS NOT NULL;")
                .contains("OR NEW.settlement_batch_id IS DISTINCT FROM OLD.settlement_batch_id")
                .contains("OR NEW.subject_digest IS DISTINCT FROM OLD.subject_digest");
        assertThat(ResolutionKind.REPUDIATE_BATCH.admittedReasonCodes())
                .containsExactly(ResolutionReasonCode.EVIDENCE_REPUDIATED);
        assertThat(ResolutionKind.sqlValueList()).endsWith("'REPUDIATE_BATCH'");
        assertThat(ResolutionReasonCode.sqlValueList()).endsWith("'EVIDENCE_REPUDIATED'");
    }

    @Test
    @DisplayName("the item trigger is ItemStatus.sqlTransitionRule(), and V013 adds to V009's"
            + " machine exactly PARKED -> UNMATCHED and RESOLVED -> REPUDIATED, removing none")
    void theItemMachineGainsExactlyTwoEdges() {
        String v013 = normalized(migration(V013));
        assertThat(v013)
                .contains(normalized("AND NOT (" + ItemStatus.sqlTransitionRule() + ") THEN"))
                .doesNotContain(normalized(
                        "AND NOT (" + ItemStatus.sqlTransitionRuleBeforeV013() + ") THEN"));
        assertThat(ItemStatus.sqlTransitionRule())
                .isNotEqualTo(ItemStatus.sqlTransitionRuleBeforeV013());

        Set<String> after = itemEdges(ItemStatus.sqlTransitionRule());
        Set<String> before = itemEdges(ItemStatus.sqlTransitionRuleBeforeV013());
        assertThat(itemEdges(v013)).as("the migration's own trigger").isEqualTo(after);
        assertThat(itemEdges(migration(V009)))
                .as("V009's trigger was the rule before V013")
                .isEqualTo(before);
        assertThat(after).as("nothing removed").containsAll(before);

        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        assertThat(added).containsExactlyInAnyOrder("PARKED->UNMATCHED", "RESOLVED->REPUDIATED");

        Set<String> addedByTheEnum = new TreeSet<>();
        for (ItemStatus from : ItemStatus.values()) {
            for (ItemStatus to : ItemStatus.values()) {
                if (ItemStatus.addedByV013(from, to)) {
                    assertThat(from.permittedTransitions()).contains(to);
                    addedByTheEnum.add(from + "->" + to);
                }
            }
        }
        assertThat(addedByTheEnum).isEqualTo(added);
    }

    @Test
    @DisplayName("the suspense origin CHECK is SuspenseOrigin's whole list - REPUDIATION its one"
            + " addition, with its shape - and the expectation's history gains REOPENED")
    void theOriginAndTheReopeningAreGenerated() {
        String sql = normalized(migration(V013));
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT suspense_item_origin CHECK (origin IN ("
                                + SuspenseOrigin.sqlValueList() + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT suspense_item_repudiation_shape CHECK ( origin <>"
                                + " 'REPUDIATION' OR (external_item_id IS NULL AND park_id IS"
                                + " NULL AND position_account_id IS NULL AND entry_id IS NOT"
                                + " NULL))"))
                .contains(normalized(
                        "ADD CONSTRAINT expectation_event_type CHECK (event_type IN ("
                                + " 'OPENED', 'KEY_COLLISION', 'ALLOCATED', 'RESOLVED',"
                                + " 'REOPENED'))"));
        assertThat(SuspenseOrigin.sqlValueList())
                .isEqualTo(SuspenseOrigin.sqlValueListBeforeV013() + ", 'REPUDIATION'");
    }

    @Test
    @DisplayName("the counter-allocation is bound once by a UNIQUE and an insert trigger, and the"
            + " closure is insert-only for the application and refused UPDATE and DELETE for"
            + " every writer")
    void theCounterAndTheClosureAreBoundForEveryWriter() {
        String sql = normalized(migration(V013));
        assertThat(sql)
                .contains("ADD CONSTRAINT allocation_reversed_once UNIQUE"
                        + " (reverses_allocation_id);")
                .contains("BEFORE INSERT ON reconciliation.allocation FOR EACH ROW EXECUTE"
                        + " FUNCTION reconciliation.allocation_counter_mirrors_its_original();")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.repudiation_closure FOR EACH"
                        + " ROW EXECUTE FUNCTION"
                        + " reconciliation.repudiation_closure_is_append_only();")
                .contains("BEFORE INSERT ON reconciliation.repudiation_closure FOR EACH ROW"
                        + " EXECUTE FUNCTION"
                        + " reconciliation.repudiation_closure_names_a_repudiation();")
                .contains("GRANT SELECT, INSERT ON reconciliation.repudiation_closure TO"
                        + " finapp_app;")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE");
    }

    // ================================================================= the live rank

    @Test
    @DisplayName("a batch-subject REPUDIATE_BATCH is admitted with its digest and no break; one"
            + " subject, the kind's subject, the digest's presence and shape, the derived"
            + " four-eyes and the pairing are refused otherwise; one proposal and one approval"
            + " per batch")
    void aBatchSubjectResolutionIsBoundAtTheDatabase() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID owner = seedItemBreak(app, source, seedItem(app, run, source, 1, 100_00));
                UUID batch = UUID.randomUUID();

                UUID proposed = UUID.randomUUID();
                admitted(app, RESOLUTION, resolution(proposed, null, batch, digest(32),
                        "REPUDIATE_BATCH", "PROPOSED", "EVIDENCE_REPUDIATED", true, null));
                assertThat(text(app, "SELECT kind || '|' || status || '|' || four_eyes || '|'"
                                + " || (break_id IS NULL) || '|' || settlement_batch_id || '|'"
                                + " || octet_length(subject_digest) FROM"
                                + " reconciliation.resolution WHERE id = ?", proposed))
                        .isEqualTo("REPUDIATE_BATCH|PROPOSED|true|true|" + batch + "|32");

                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), owner,
                                UUID.randomUUID(), digest(32), "REPUDIATE_BATCH", "PROPOSED",
                                "EVIDENCE_REPUDIATED", true, null)))
                        .as("a break AND a batch")
                        .hasMessageContaining("resolution_one_subject");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null, null,
                                null, "ACKNOWLEDGE", "PROPOSED", "TIMING_CONFIRMED", false,
                                null)))
                        .as("neither: break_id is nullable now, the subject is not")
                        .hasMessageContaining("resolution_one_subject");
                assertThat(refused(app, RESOLUTION, resolution(UUID.randomUUID(), null, null,
                                digest(32), "REPUDIATE_BATCH", "PROPOSED",
                                "EVIDENCE_REPUDIATED", true, null)).getMessage())
                        .as("a repudiation with no subject")
                        .containsAnyOf("resolution_one_subject",
                                "resolution_batch_subject_is_repudiation");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null,
                                UUID.randomUUID(), null, "ACKNOWLEDGE", "PROPOSED",
                                "TIMING_CONFIRMED", false, null)))
                        .as("a batch is the repudiation's subject alone")
                        .hasMessageContaining("resolution_batch_subject_is_repudiation");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null,
                                UUID.randomUUID(), null, "REPUDIATE_BATCH", "PROPOSED",
                                "EVIDENCE_REPUDIATED", true, null)))
                        .as("a repudiation without the plan's digest")
                        .hasMessageContaining("resolution_repudiation_carries_its_digest");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), owner, null,
                                digest(32), "ACKNOWLEDGE", "PROPOSED", "TIMING_CONFIRMED",
                                false, null)))
                        .as("a digest on anything but a repudiation")
                        .hasMessageContaining("resolution_repudiation_carries_its_digest");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null,
                                UUID.randomUUID(), digest(31), "REPUDIATE_BATCH", "PROPOSED",
                                "EVIDENCE_REPUDIATED", true, null)))
                        .as("a 31-byte digest")
                        .hasMessageContaining("resolution_digest_shape");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null,
                                UUID.randomUUID(), digest(32), "REPUDIATE_BATCH", "PROPOSED",
                                "EVIDENCE_REPUDIATED", false, null)))
                        .as("a repudiation is four-eyed, derived")
                        .hasMessageContaining("resolution_four_eyes_derived");
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null,
                                UUID.randomUUID(), digest(32), "REPUDIATE_BATCH", "PROPOSED",
                                "INTERNAL_PROCESSING_ERROR", true, null)))
                        .as("the repudiation's reason alone")
                        .hasMessageContaining("resolution_kind_reason_pairing");

                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null, batch,
                                digest(32), "REPUDIATE_BATCH", "PROPOSED",
                                "EVIDENCE_REPUDIATED", true, null)))
                        .as("one live proposal per batch")
                        .hasMessageContaining("resolution_one_proposed_per_batch");
                admitted(app, RESOLUTION, resolution(UUID.randomUUID(), null, batch, digest(32),
                        "REPUDIATE_BATCH", "REJECTED", "EVIDENCE_REPUDIATED", true,
                        "op-approver"));

                UUID repudiated = UUID.randomUUID();
                admitted(app, RESOLUTION, resolution(UUID.randomUUID(), null, repudiated,
                        digest(32), "REPUDIATE_BATCH", "APPROVED", "EVIDENCE_REPUDIATED", true,
                        "op-approver"));
                assertThat((Throwable) refused(app, RESOLUTION, resolution(UUID.randomUUID(), null,
                                repudiated, digest(32), "REPUDIATE_BATCH", "APPROVED",
                                "EVIDENCE_REPUDIATED", true, "op-second-approver")))
                        .as("a batch is repudiated once")
                        .hasMessageContaining("resolution_batch_repudiated_once");
                assertThat(count(app, "SELECT count(*) FROM reconciliation.resolution WHERE"
                                + " settlement_batch_id IN (?, ?)", batch, repudiated))
                        .as("the proposal, its rejected sibling, the one approval")
                        .isEqualTo(3);
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the batch subject and the digest are frozen with the proposal: the application"
            + " holds no grant on them, and the trigger refuses the owner too - while the"
            + " machine's own edge still moves")
    void theBatchSubjectIsFrozenForEveryWriter() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID id = UUID.randomUUID();
                admitted(app, RESOLUTION, resolution(id, null, UUID.randomUUID(), digest(32),
                        "REPUDIATE_BATCH", "PROPOSED", "EVIDENCE_REPUDIATED", true, null));
                assertThat((Throwable) refused(app, "UPDATE reconciliation.resolution SET"
                                + " settlement_batch_id = ? WHERE id = ?", UUID.randomUUID(), id))
                        .hasMessageContaining("permission denied");
                assertThat((Throwable) refused(app, "UPDATE reconciliation.resolution SET subject_digest = ?"
                                + " WHERE id = ?", digest(32), id))
                        .hasMessageContaining("permission denied");
                admitted(app, "UPDATE reconciliation.resolution SET status = 'WITHDRAWN',"
                        + " decided_by = 'op-proposer', decided_by_type = 'EMPLOYEE',"
                        + " decided_at = now(), status_changed_at = now() WHERE id = ?", id);
            } finally {
                app.rollback();
            }
        }
        try (Connection owner = migrator()) {
            try {
                UUID id = UUID.randomUUID();
                admitted(owner, RESOLUTION, resolution(id, null, UUID.randomUUID(), digest(32),
                        "REPUDIATE_BATCH", "PROPOSED", "EVIDENCE_REPUDIATED", true, null));
                assertThat((Throwable) refused(owner, "UPDATE reconciliation.resolution SET"
                                + " settlement_batch_id = ? WHERE id = ?", UUID.randomUUID(), id))
                        .hasMessageContaining("frozen when proposed");
                assertThat((Throwable) refused(owner, "UPDATE reconciliation.resolution SET"
                                + " settlement_batch_id = NULL WHERE id = ?", id))
                        .hasMessageContaining("frozen when proposed");
                assertThat((Throwable) refused(owner, "UPDATE reconciliation.resolution SET subject_digest = ?"
                                + " WHERE id = ?", digest(32), id))
                        .hasMessageContaining("frozen when proposed");
                assertThat((Throwable) refused(owner, "DELETE FROM reconciliation.resolution WHERE id = ?", id))
                        .hasMessageContaining("never deleted");
                admitted(owner, "UPDATE reconciliation.resolution SET status = 'APPROVED',"
                        + " decided_by = 'op-approver', decided_by_type = 'EMPLOYEE',"
                        + " decided_at = now(), status_changed_at = now() WHERE id = ?", id);
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("a counter-allocation mirroring its original exactly is admitted and nets the"
            + " sums to zero; a second counter, a different amount, currency or expectation, a"
            + " counter of a counter and a counter of nothing are refused")
    void aCounterAllocationMirrorsItsOriginalOnce() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID item = seedItem(app, run, source, 1, 100_00);
                UUID expectation = seedExpectation(app, source, 100_00);
                UUID otherExpectation = seedExpectation(app, source, 100_00);
                UUID decision = seedDecision(app, item, run, 100_00);
                UUID original = UUID.randomUUID();
                admitted(app, ALLOCATION,
                        original, decision, item, expectation, 100_00L, "EUR", null);
                admitted(app, "UPDATE reconciliation.external_item SET allocated_minor = ?"
                        + " WHERE id = ?", 100_00L, item);
                admitted(app, "UPDATE reconciliation.expectation SET allocated_minor = ?"
                        + " WHERE id = ?", 100_00L, expectation);

                assertThat((Throwable) refused(app, ALLOCATION, UUID.randomUUID(), decision, item,
                                expectation, 50_00L, "EUR", original))
                        .as("a different amount")
                        .hasMessageContaining("mirrors its original exactly");
                assertThat((Throwable) refused(app, ALLOCATION, UUID.randomUUID(), decision, item,
                                expectation, 100_00L, "GBP", original))
                        .as("a different currency")
                        .hasMessageContaining("mirrors its original exactly");
                assertThat((Throwable) refused(app, ALLOCATION, UUID.randomUUID(), decision, item,
                                otherExpectation, 100_00L, "EUR", original))
                        .as("a different expectation")
                        .hasMessageContaining("mirrors its original exactly");
                assertThat((Throwable) refused(app, ALLOCATION, UUID.randomUUID(), decision, item,
                                expectation, 100_00L, "EUR", UUID.randomUUID()))
                        .as("a counter of nothing")
                        .hasMessageContaining("names an existing allocation");

                UUID counter = UUID.randomUUID();
                admitted(app, ALLOCATION,
                        counter, decision, item, expectation, 100_00L, "EUR", original);

                assertThat((Throwable) refused(app, ALLOCATION, UUID.randomUUID(), decision, item,
                                expectation, 100_00L, "EUR", original))
                        .as("an original is reversed once")
                        .hasMessageContaining("allocation_reversed_once");
                assertThat((Throwable) refused(app, ALLOCATION, UUID.randomUUID(), decision, item,
                                expectation, 100_00L, "EUR", counter))
                        .as("a counter is never itself countered")
                        .hasMessageContaining("never itself countered");

                // The mirror restores the remainder exactly: with both denormalised columns
                // back at zero, the deferred sums (V005) hold when asked now.
                admitted(app, "UPDATE reconciliation.external_item SET allocated_minor = 0"
                        + " WHERE id = ?", item);
                admitted(app, "UPDATE reconciliation.expectation SET allocated_minor = 0"
                        + " WHERE id = ?", expectation);
                execute(app, "SET CONSTRAINTS ALL IMMEDIATE");
                assertThat(count(app, "SELECT count(*) FROM reconciliation.allocation WHERE"
                                + " external_item_id = ?", item))
                        .isEqualTo(2);
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the item's two new edges are admitted - PARKED -> UNMATCHED and"
            + " RESOLVED -> REPUDIATED - and RESOLVED -> UNMATCHED and PENDING -> REPUDIATED are"
            + " still refused")
    void theItemTakesExactlyItsTwoNewEdges() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID reopened = seedItem(app, run, source, 1, 10_00);
                UUID repudiated = seedItem(app, run, source, 2, 10_00);
                UUID resolved = seedItem(app, run, source, 3, 10_00);
                UUID pending = seedItem(app, run, source, 4, 10_00);

                admitted(app, MOVE, "PARKED", reopened);
                admitted(app, MOVE, "UNMATCHED", reopened);

                admitted(app, MOVE, "PARKED", repudiated);
                admitted(app, MOVE, "RESOLVED", repudiated);
                admitted(app, MOVE, "REPUDIATED", repudiated);

                admitted(app, MOVE, "PARKED", resolved);
                admitted(app, MOVE, "RESOLVED", resolved);
                assertThat((Throwable) refused(app, MOVE, "UNMATCHED", resolved))
                        .hasMessageContaining("not an external item edge: RESOLVED -> UNMATCHED");
                assertThat((Throwable) refused(app, MOVE, "REPUDIATED", pending))
                        .hasMessageContaining("not an external item edge: PENDING -> REPUDIATED");

                assertThat(text(app, "SELECT string_agg(status, ',' ORDER BY line_no) FROM"
                                + " reconciliation.external_item WHERE run_id = ?", run))
                        .isEqualTo("UNMATCHED,REPUDIATED,RESOLVED,PENDING");
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the expectation's history records a REOPENED event, and its list stays closed")
    void theReopeningIsRecorded() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID expectation = seedExpectation(app, UUID.randomUUID(), 10_00);
                String event = "INSERT INTO reconciliation.expectation_event (expectation_id,"
                        + " event_type, detail, actor, actor_type, occurred_at, correlation_id)"
                        + " VALUES (?, ?, 'p8t23 probe', 'system', 'SYSTEM', now(),"
                        + " 'p8t23-v013')";
                admitted(app, event, expectation, "REOPENED");
                assertThat((Throwable) refused(app, event, expectation, "REPUDIATED"))
                        .hasMessageContaining("expectation_event_type");
                assertThat(count(app, "SELECT count(*) FROM reconciliation.expectation_event"
                                + " WHERE expectation_id = ? AND event_type = 'REOPENED'",
                                expectation))
                        .isEqualTo(1);
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("a REPUDIATION suspense item is born with its PROCESSING_ERROR owner through the"
            + " deferred subject key, with no external item, park or position and its entry;"
            + " an external item or a missing entry is refused")
    void theRepudiationOpensSuspense() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID externalItem = seedItem(app, run, source, 1, 10_00);
                UUID standingOwner = seedItemBreak(app, source, externalItem);

                // The production shape: the owner stands on the item it owns, both born in
                // one transaction under the one deferred key (V011), checked again at once.
                UUID suspenseItem = UUID.randomUUID();
                UUID owner = UUID.randomUUID();
                UUID entry = UUID.randomUUID();
                String releasedItem = UUID.randomUUID().toString();
                execute(app, "SET CONSTRAINTS reconciliation.break_suspense_item_fk DEFERRED");
                admitted(app, "INSERT INTO reconciliation.break (id, type, cause, severity,"
                        + " source_id, rule_set_id, suspense_item_id, value_at_issue_minor,"
                        + " currency, scale, raised_at, status_changed_at, correlation_id)"
                        + " VALUES (?, 'PROCESSING_ERROR', 'EVIDENCE_REPUDIATED', 'HIGH', ?, ?,"
                        + " ?, 1000, 'EUR', 2, now(), now(), 'p8t23-v013')",
                        owner, source, RULE_SET, suspenseItem);
                admitted(app, SUSPENSE, suspenseItem, owner, null, "REPUDIATION", releasedItem,
                        BUSINESS_DATE, entry);
                execute(app, "SET CONSTRAINTS reconciliation.break_suspense_item_fk IMMEDIATE");
                assertThat(text(app, "SELECT s.origin || '|' || s.origin_ref || '|'"
                                + " || (s.external_item_id IS NULL) || '|' || (s.park_id IS NULL)"
                                + " || '|' || (s.position_account_id IS NULL) || '|' || s.entry_id"
                                + " || '|' || b.type || '|' || b.suspense_item_id FROM"
                                + " reconciliation.suspense_item s JOIN reconciliation.break b"
                                + " ON b.id = s.break_id WHERE s.id = ?", suspenseItem))
                        .isEqualTo("REPUDIATION|" + releasedItem + "|true|true|true|" + entry
                                + "|PROCESSING_ERROR|" + suspenseItem);

                assertThat((Throwable) refused(app, SUSPENSE, UUID.randomUUID(), standingOwner, externalItem,
                                "REPUDIATION", UUID.randomUUID().toString(), BUSINESS_DATE,
                                UUID.randomUUID()))
                        .as("a repudiation answers a released value, never an external item")
                        .hasMessageContaining("suspense_item_repudiation_shape");
                SQLException noEntry = refused(app, SUSPENSE, UUID.randomUUID(), standingOwner,
                        null, "REPUDIATION", UUID.randomUUID().toString(), BUSINESS_DATE, null);
                assertThat(noEntry.getSQLState())
                        .as("entry_id is NOT NULL since V004: the shape's clause restates it")
                        .isEqualTo("23502");
                assertThat((Throwable) noEntry).hasMessageContaining("entry_id");
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("a closure names a REPUDIATE_BATCH resolution, once per break; a non-repudiation"
            + " is refused; UPDATE and DELETE are refused - by grant for the application, by"
            + " trigger for the owner")
    void aClosureNamesARepudiationAndIsAppendOnly() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID acknowledged =
                        seedItemBreak(app, source, seedItem(app, run, source, 1, 10_00));
                UUID closed = seedItemBreak(app, source, seedItem(app, run, source, 2, 10_00));
                UUID repudiation = UUID.randomUUID();
                admitted(app, RESOLUTION, resolution(repudiation, null, UUID.randomUUID(),
                        digest(32), "REPUDIATE_BATCH", "APPROVED", "EVIDENCE_REPUDIATED", true,
                        "op-approver"));
                UUID acknowledgement = UUID.randomUUID();
                admitted(app, RESOLUTION, resolution(acknowledgement, acknowledged, null, null,
                        "ACKNOWLEDGE", "PROPOSED", "TIMING_CONFIRMED", false, null));
                assertThat((Throwable) refused(app, CLOSURE, acknowledged, repudiation))
                        .as("a closure records a break the repudiation RESOLVED, never an open one")
                        .hasMessageContaining("records a break it RESOLVED");
                admitted(app, "UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at"
                        + " = now(), status_changed_at = now() WHERE id = ?", closed);

                assertThat((Throwable) refused(app, CLOSURE, closed, acknowledgement))
                        .hasMessageContaining("names a REPUDIATE_BATCH resolution");
                admitted(app, CLOSURE, closed, repudiation);
                assertThat((Throwable) refused(app, CLOSURE, closed, repudiation))
                        .as("one closure per break")
                        .hasMessageContaining("repudiation_closure_pk");
                assertThat((Throwable) refused(app, "UPDATE reconciliation.repudiation_closure SET"
                                + " correlation_id = 'edited' WHERE break_id = ?", closed))
                        .hasMessageContaining("permission denied");
                assertThat((Throwable) refused(app, "DELETE FROM reconciliation.repudiation_closure WHERE"
                                + " break_id = ?", closed))
                        .hasMessageContaining("permission denied");
            } finally {
                app.rollback();
            }
        }
        try (Connection owner = migrator()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(owner, source);
                UUID closed = seedItemBreak(owner, source, seedItem(owner, run, source, 1, 10_00));
                UUID repudiation = UUID.randomUUID();
                admitted(owner, RESOLUTION, resolution(repudiation, null, UUID.randomUUID(),
                        digest(32), "REPUDIATE_BATCH", "APPROVED", "EVIDENCE_REPUDIATED", true,
                        "op-approver"));
                admitted(owner, "UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at"
                        + " = now(), status_changed_at = now() WHERE id = ?", closed);
                admitted(owner, CLOSURE, closed, repudiation);
                assertThat((Throwable) refused(owner, "UPDATE reconciliation.repudiation_closure SET"
                                + " correlation_id = 'edited' WHERE break_id = ?", closed))
                        .hasMessageContaining("append-only");
                assertThat((Throwable) refused(owner, "DELETE FROM reconciliation.repudiation_closure WHERE"
                                + " break_id = ?", closed))
                        .hasMessageContaining("append-only");
            } finally {
                owner.rollback();
            }
        }
    }

    // ================================================================= seeding

    private static final String RESOLUTION =
            "INSERT INTO reconciliation.resolution (id, break_id, settlement_batch_id,"
                    + " subject_digest, kind, status, reason_code, narrative, four_eyes,"
                    + " proposed_amount_minor, currency, scale, residual_version, rule_set_id,"
                    + " proposed_by, proposed_by_type, proposed_at, decided_by, decided_by_type,"
                    + " decided_at, created_at, status_changed_at, correlation_id) VALUES (?, ?,"
                    + " ?, ?, ?, ?, ?, 'p8t23 raw probe', ?, 0, 'EUR', 2, 0, ?, 'op-proposer',"
                    + " 'EMPLOYEE', now(), ?, ?, ?, now(), now(), 'p8t23-v013')";

    private static final String ALLOCATION =
            "INSERT INTO reconciliation.allocation (id, decision_id, external_item_id,"
                    + " expectation_id, amount_minor, currency, scale, reverses_allocation_id,"
                    + " created_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, 2, ?, now(),"
                    + " 'p8t23-v013')";

    private static final String MOVE =
            "UPDATE reconciliation.external_item SET status = ?, status_changed_at = now()"
                    + " WHERE id = ?";

    private static final String SUSPENSE =
            "INSERT INTO reconciliation.suspense_item (id, break_id, external_item_id, origin,"
                    + " origin_ref, side, amount_minor, currency, scale, opened_on, entry_id,"
                    + " park_id, position_account_id, status_changed_at, correlation_id) VALUES"
                    + " (?, ?, ?, ?, ?, 'DEBIT', 1000, 'EUR', 2, ?, ?, NULL, NULL, now(),"
                    + " 'p8t23-v013')";

    private static final String CLOSURE =
            "INSERT INTO reconciliation.repudiation_closure (break_id, resolution_id, closed_at,"
                    + " correlation_id) VALUES (?, ?, now(), 'p8t23-v013')";

    /** One resolution row's parameters, in {@link #RESOLUTION}'s order; amount 0, EUR. */
    private static Object[] resolution(
            UUID id, UUID breakId, UUID batchId, byte[] digest, String kind, String status,
            String reason, boolean fourEyes, String decidedBy) {
        return new Object[] {
            id, breakId, batchId, digest, kind, status, reason, fourEyes, RULE_SET, decidedBy,
            decidedBy == null ? null : "EMPLOYEE",
            decidedBy == null ? null : Timestamp.from(Instant.now())
        };
    }

    /** A private source's BATCH run: its sequence is unique within the private source. */
    private static UUID seedRun(Connection connection, UUID source) throws SQLException {
        UUID run = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.reconciliation_batch (id, source_id,"
                + " batch_id, kind, rule_set_id, business_date, source_sequence, item_count,"
                + " created_at, status_changed_at, correlation_id) VALUES (?, ?, ?, 'BATCH', ?,"
                + " ?, 1, 4, now(), now(), 'p8t23-v013')",
                run, source, UUID.randomUUID(), RULE_SET, BUSINESS_DATE);
        return run;
    }

    private static UUID seedItem(
            Connection connection, UUID run, UUID source, int lineNo, long minor)
            throws SQLException {
        UUID item = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.external_item (id, run_id, source_id,"
                + " settlement_line_id, line_no, line_type, direction, amount_minor, currency,"
                + " scale, position_purpose, business_date, settlement_date, value_date,"
                + " canonical_fingerprint, created_at, status_changed_at, correlation_id)"
                + " VALUES (?, ?, ?, ?, ?, 'CAPTURE', 'INBOUND', ?, 'EUR', 2,"
                + " 'SETTLEMENT_CLEARING', ?, ?, ?, ?, now(), now(), 'p8t23-v013')",
                item, run, source, UUID.randomUUID(), lineNo, minor, BUSINESS_DATE,
                BUSINESS_DATE, BUSINESS_DATE, digest(32));
        return item;
    }

    /** A REMITTANCE: the one kind recording no posting of ours, so no journal entry to forge. */
    private static UUID seedExpectation(Connection connection, UUID source, long minor)
            throws SQLException {
        UUID expectation = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.expectation (id, kind, operation_ref,"
                + " posting_key, source_id, position_purpose, ledger_account_id, direction,"
                + " amount_minor, currency, scale, journal_entry_id, posting_date, expected_by,"
                + " rule_set_id, opened_at, status_changed_at, correlation_id) VALUES (?,"
                + " 'REMITTANCE', ?, ?, ?, 'SETTLEMENT_CLEARING', ?, 'INBOUND', ?, 'EUR', 2, NULL,"
                + " ?, ?, ?, now(), now(), 'p8t23-v013')",
                expectation, "p8t23-op-" + expectation, "p8t23-remittance:" + expectation,
                source, UUID.randomUUID(), minor, BUSINESS_DATE, BUSINESS_DATE.plusDays(2),
                RULE_SET);
        return expectation;
    }

    /** A RUN decision carrying its replay inputs (V012's insert trigger). */
    private static UUID seedDecision(Connection connection, UUID item, UUID run, long minor)
            throws SQLException {
        UUID decision = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                + " run_id, origin, rule_set_id, rule_priority, strategy, matched_key_kind,"
                + " outcome, decided_by, decided_by_type, decided_at, decided_on, correlation_id,"
                + " verdict, judged_status, judged_minor, fingerprint_seen_earlier) VALUES (?, ?,"
                + " ?, 'RUN', ?, 1, 'ONE_TO_ONE', 'REMITTANCE_REF', 'MATCHED', 'system',"
                + " 'SYSTEM', now(), ?, 'p8t23-v013', 'ALLOCATE', 'PENDING', ?, false)",
                decision, item, run, RULE_SET, BUSINESS_DATE, minor);
        return decision;
    }

    /** An open PROCESSING_ERROR break on an item: a suspense-owning type, any break's subject. */
    private static UUID seedItemBreak(Connection connection, UUID source, UUID item)
            throws SQLException {
        UUID breakId = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.break (id, type, cause, severity,"
                + " source_id, rule_set_id, external_item_id, value_at_issue_minor, currency,"
                + " scale, raised_at, status_changed_at, correlation_id) VALUES (?,"
                + " 'PROCESSING_ERROR', 'ITEM_ERRORED', 'HIGH', ?, ?, ?, 1000, 'EUR', 2, now(),"
                + " now(), 'p8t23-v013')",
                breakId, source, RULE_SET, item);
        return breakId;
    }

    private static byte[] digest(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    // ================================================================= plumbing

    private static Connection application() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(connection);
        return connection;
    }

    private static Connection migrator() throws SQLException {
        Connection connection = DatabaseRoles.migrator();
        connection.setAutoCommit(false);
        return connection;
    }

    /** Runs a statement that must succeed, kept in the transaction. */
    private static void admitted(Connection connection, String sql, Object... args)
            throws SQLException {
        execute(connection, sql, args);
    }

    /** Runs a statement that must be refused, behind a savepoint the refusal rolls back. */
    private static SQLException refused(Connection connection, String sql, Object... args)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        try {
            execute(connection, sql, args);
        } catch (SQLException refusal) {
            connection.rollback(before);
            return refusal;
        }
        connection.rollback(before);
        throw new AssertionError("admitted, but expected a refusal: " + sql);
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        if (args.length == 0) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object one(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static String text(Connection connection, String sql, Object... args)
            throws SQLException {
        Object value = one(connection, sql, args);
        return value == null ? null : value.toString();
    }

    private static long count(Connection connection, String sql, Object... args)
            throws SQLException {
        return ((Number) one(connection, sql, args)).longValue();
    }

    // ================================================================= text

    /** The item machine's edges named in {@code sql}, as FROM->TO, other machines ignored. */
    private static Set<String> itemEdges(String sql) {
        Set<String> states =
                Arrays.stream(ItemStatus.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> edges = new TreeSet<>();
        Matcher edge = EDGE.matcher(normalized(sql));
        while (edge.find()) {
            String from = edge.group(1);
            if (!states.contains(from)) {
                continue;
            }
            for (String to : edge.group(2).split(",")) {
                edges.add(from + "->" + to.trim().replace("'", ""));
            }
        }
        return edges;
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration(String resource) {
        try (InputStream in =
                ReconciliationV013MigrationTest.class.getClassLoader()
                        .getResourceAsStream(resource)) {
            assertThat(in).as("the migration is on the classpath: %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
