package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/** {@link MatchingStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcMatchingStore implements MatchingStore {

    // ------------------------------------------------------------------ runs

    private static final String RUN_COLUMNS =
            "id, source_id, batch_id, status, rule_set_id, source_sequence, item_count,"
                    + " cursor, failures, business_date, correlation_id, settlement_cycle";

    @Override
    public List<UUID> sourcesWithWork(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT source_id FROM reconciliation.reconciliation_batch"
                                + " WHERE kind = 'BATCH' AND status IN ('OPEN',"
                                + " 'IN_PROGRESS') ORDER BY source_id")) {
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> sources = new ArrayList<>();
                while (rows.next()) {
                    sources.add(rows.getObject("source_id", UUID.class));
                }
                return List.copyOf(sources);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not list the worklist", failure);
        }
    }

    @Override
    public Optional<RunRow> eligibleRun(Connection unitOfWork, UUID sourceId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RUN_COLUMNS
                                + " FROM reconciliation.reconciliation_batch r"
                                + " WHERE r.source_id = ? AND r.kind = 'BATCH'"
                                + " AND r.status IN ('OPEN', 'IN_PROGRESS')"
                                // Source-sequence order, and a BLOCKED (or any incomplete)
                                // lower sequence holds the source (ADR-0068 section 4).
                                + " AND NOT EXISTS (SELECT 1 FROM"
                                + " reconciliation.reconciliation_batch e"
                                + " WHERE e.source_id = r.source_id AND e.kind = 'BATCH'"
                                + " AND e.source_sequence < r.source_sequence"
                                + " AND e.status <> 'COMPLETED')"
                                + " ORDER BY r.source_sequence LIMIT 1")) {
            read.setObject(1, sourceId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(runRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the eligible run", failure);
        }
    }

    private RunRow runRow(ResultSet row) throws SQLException {
        Long sequence = row.getObject("source_sequence", Long.class);
        return new RunRow(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getObject("batch_id", UUID.class),
                RunStatus.valueOf(row.getString("status")),
                row.getObject("rule_set_id", UUID.class),
                sequence == null ? 0L : sequence,
                row.getInt("item_count"),
                row.getLong("cursor"),
                row.getInt("failures"),
                row.getObject("business_date", LocalDate.class),
                row.getString("correlation_id"),
                Optional.ofNullable(row.getString("settlement_cycle")));
    }

    @Override
    public boolean markRunInProgress(
            Connection unitOfWork, UUID runId, Actor actor, Instant at) {
        return moveRun(unitOfWork, runId, "OPEN", "IN_PROGRESS", actor, at);
    }

    @Override
    public boolean completeRun(Connection unitOfWork, UUID runId, Actor actor, Instant at) {
        boolean fromInProgress =
                moveRun(unitOfWork, runId, "IN_PROGRESS", "COMPLETED", actor, at);
        // The empty run's own edge: OPEN -> COMPLETED (item_count = 0).
        return fromInProgress || moveRun(unitOfWork, runId, "OPEN", "COMPLETED", actor, at);
    }

    @Override
    public boolean blockRun(Connection unitOfWork, UUID runId, Actor actor, Instant at) {
        return moveRun(unitOfWork, runId, "IN_PROGRESS", "BLOCKED", actor, at)
                || moveRun(unitOfWork, runId, "OPEN", "BLOCKED", actor, at);
    }

    private boolean moveRun(
            Connection unitOfWork,
            UUID runId,
            String from,
            String to,
            Actor actor,
            Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.reconciliation_batch SET status = ?,"
                                + " status_changed_at = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to);
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, runId);
            update.setString(4, from);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not move the run", failure);
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.reconciliation_batch_event (run_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")) {
            insert.setObject(1, runId);
            insert.setString(2, from);
            insert.setString(3, to);
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.setString(7, "run:" + runId);
            insert.executeUpdate();
            return true;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the run's history", failure);
        }
    }

    @Override
    public void advanceCursor(
            Connection unitOfWork, UUID runId, long lastLineNo, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.reconciliation_batch SET cursor = ?,"
                                + " failures = 0, status_changed_at = ?"
                                + " WHERE id = ? AND cursor < ?")) {
            update.setLong(1, lastLineNo);
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, runId);
            update.setLong(4, lastLineNo);
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not advance the cursor", failure);
        }
    }

    @Override
    public int bumpRunFailures(Connection unitOfWork, UUID runId, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.reconciliation_batch SET failures ="
                                + " failures + 1, status_changed_at = ? WHERE id = ?"
                                + " RETURNING failures")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, runId);
            try (ResultSet row = update.executeQuery()) {
                row.next();
                return row.getInt("failures");
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not record the run's failure", failure);
        }
    }

    // ------------------------------------------------------------------ the chunk

    @Override
    public List<ChunkItem> chunkItems(
            Connection unitOfWork, UUID runId, long cursor, int limit) {
        Map<UUID, ChunkItemBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT i.id, i.line_no, i.line_type, i.direction, i.amount_minor,"
                                + " i.position_purpose, i.attributed_source_id, i.currency,"
                                + " i.scale, i.business_date, i.settlement_date,"
                                + " i.value_date, i.canonical_fingerprint,"
                                + " r.source_sequence"
                                + " FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r"
                                + " ON r.id = i.run_id"
                                // PENDING only: an item born disposed (an unattributed bank
                                // line, PARKED at acceptance - P8-TSK-016) is never decided.
                                // The LIMIT applies after the filter, so a short chunk still
                                // means the run is exhausted.
                                + " WHERE i.run_id = ? AND i.line_no > ?"
                                + " AND i.status = 'PENDING'"
                                + " ORDER BY i.line_no LIMIT ?")) {
            read.setObject(1, runId);
            read.setLong(2, cursor);
            read.setInt(3, limit);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    ChunkItemBuilder builder = itemFacts(rows);
                    builders.put(builder.id, builder);
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the chunk", failure);
        }
        if (builders.isEmpty()) {
            return List.of();
        }
        attachKeys(unitOfWork, builders);
        return builders.values().stream().map(ChunkItemBuilder::build).toList();
    }

    private void attachKeys(Connection unitOfWork, Map<UUID, ChunkItemBuilder> builders) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT item_id, key_kind, key_value FROM"
                                + " reconciliation.external_item_key"
                                + " WHERE item_id = ANY (?)")) {
            read.setArray(
                    1, unitOfWork.createArrayOf("uuid", builders.keySet().toArray()));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    builders.get(rows.getObject("item_id", UUID.class))
                            .keys
                            .put(
                                    ItemKeyKind.valueOf(rows.getString("key_kind")),
                                    rows.getString("key_value"));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the items' keys", failure);
        }
    }

    /**
     * One item row's facts, the chunk's and the residual readers' shared shape. A NULL
     * position (a bank fee, an unattributed bank line - `V008`) reads as empty.
     */
    private static ChunkItemBuilder itemFacts(ResultSet rows) throws SQLException {
        ChunkItemBuilder builder = new ChunkItemBuilder();
        builder.id = rows.getObject("id", UUID.class);
        builder.lineNo = rows.getLong("line_no");
        builder.lineType = ExternalLineType.valueOf(rows.getString("line_type"));
        builder.direction = ExpectationDirection.valueOf(rows.getString("direction"));
        builder.amount =
                Money.ofPersisted(
                        rows.getLong("amount_minor"),
                        CurrencyCode.of(rows.getString("currency").trim()),
                        rows.getInt("scale"));
        builder.positionPurpose =
                Optional.ofNullable(rows.getString("position_purpose"))
                        .map(com.finapp.ledger.AccountPurpose::valueOf);
        builder.attributedSourceId =
                Optional.ofNullable(rows.getObject("attributed_source_id", UUID.class));
        builder.businessDate = rows.getObject("business_date", LocalDate.class);
        builder.settlementDate =
                Optional.ofNullable(rows.getObject("settlement_date", LocalDate.class));
        builder.valueDate = Optional.ofNullable(rows.getObject("value_date", LocalDate.class));
        builder.fingerprint = rows.getBytes("canonical_fingerprint");
        Long sequence = rows.getObject("source_sequence", Long.class);
        builder.sourceSequence = sequence == null ? 0L : sequence;
        return builder;
    }

    /** The residual readers' shared shape: locked item rows plus run facts and keys. */
    private List<ResidualItem> lockedResiduals(
            Connection unitOfWork,
            String sql,
            UUID sourceId,
            Collection<UUID> heldAttributions,
            int limit) {
        return lockedResiduals(unitOfWork, sql, List.of(sourceId, heldAttributions, limit));
    }

    /** The parameters in order; a collection binds as the {@code uuid[]} it names. */
    private List<ResidualItem> lockedResiduals(
            Connection unitOfWork, String sql, List<Object> parameters) {
        record RunFacts(
                UUID runId, UUID sourceId, UUID ruleSetId, String correlationId,
                Optional<String> cycle) {}
        Map<UUID, ChunkItemBuilder> builders = new LinkedHashMap<>();
        Map<UUID, RunFacts> facts = new HashMap<>();
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            int index = 1;
            for (Object parameter : parameters) {
                if (parameter instanceof Collection<?> held) {
                    read.setArray(
                            index++,
                            unitOfWork.createArrayOf(
                                    "uuid", new TreeSet<Object>(held).toArray()));
                } else {
                    read.setObject(index++, parameter);
                }
            }
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    ChunkItemBuilder builder = itemFacts(rows);
                    builders.put(builder.id, builder);
                    facts.put(
                            builder.id,
                            new RunFacts(
                                    rows.getObject("run_id", UUID.class),
                                    rows.getObject("source_id", UUID.class),
                                    rows.getObject("rule_set_id", UUID.class),
                                    rows.getString("correlation_id"),
                                    Optional.ofNullable(rows.getString("settlement_cycle"))));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not lock the residual items", failure);
        }
        if (builders.isEmpty()) {
            return List.of();
        }
        attachKeys(unitOfWork, builders);
        return builders.values().stream()
                .map(builder -> {
                    RunFacts fact = facts.get(builder.id);
                    return new ResidualItem(
                            builder.build(), fact.runId(), fact.sourceId(),
                            fact.ruleSetId(), fact.correlationId(), fact.cycle());
                })
                .toList();
    }

    private static final String RESIDUAL_COLUMNS =
            "SELECT i.id, i.line_no, i.line_type, i.direction, i.amount_minor,"
                    + " i.position_purpose, i.attributed_source_id, i.currency, i.scale,"
                    + " i.business_date, i.settlement_date, i.value_date,"
                    + " i.canonical_fingerprint, i.run_id, i.source_id, r.source_sequence,"
                    + " r.rule_set_id, r.correlation_id, r.settlement_cycle"
                    + " FROM reconciliation.external_item i"
                    + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id";

    /**
     * The grace worklist's predicate for one source ({@code ?}): the window judged in SQL on
     * the DATABASE clock against the stored timestamp (INV-SET-02).
     */
    private static final String EXPIRED_PREDICATE =
            " i.source_id = ? AND i.status = 'UNMATCHED'"
                    + " AND i.grace_until IS NOT NULL AND i.grace_until <= now()";

    /** The item's latest decision instant, or minus infinity before any. */
    private static final String LATEST_DECISION =
            "COALESCE((SELECT max(d.decided_at) FROM reconciliation.match_decision d"
                    + " WHERE d.external_item_id = i.id), '-infinity'::timestamptz)";

    /**
     * The rematch predicate (`P8-TSK-013`, widened by `P8-TSK-016` and `P8-TSK-019`): a residual
     * whose keys, judged in its KEY SCOPE (its attributed source, else its own), reach an
     * expectation opened after its latest decision — directly, or through an operation-anchored
     * rule's anchor to its operation's expectation of the rule's kind that no decision of the
     * item has yet seen (judged on rows, never across two instances' clocks); or an attributed
     * waiting item for which an untouched candidate of a value-date group rule opened after its
     * latest decision. Both rules are read from the source's ACTIVE rule set, never the item's
     * run's pinned version — the version the rematch leg decides under (`P8-TSK-022`). *(Corrected
     * 2026-10-01, `P8-DOC-001`: this read "its run's value-date group rule".)* A PARKED
     * item leaves here only by the park's exact inverse, so an item owning a suspense item of
     * another origin (an unattributed bank line's {@code BANK_UNATTRIBUTED}) is never read.
     */
    private static final String REMATCH_PREDICATE =
            " i.status IN ('UNMATCHED', 'PARKED')"
                    + " AND NOT EXISTS (SELECT 1 FROM reconciliation.suspense_item s"
                    + " WHERE s.external_item_id = i.id AND s.origin <> 'RECON_PARK')"
                    + " AND (EXISTS (SELECT 1 FROM reconciliation.external_item_key ik"
                    + " JOIN reconciliation.expectation_key ek"
                    + " ON ek.source_id = COALESCE(i.attributed_source_id, i.source_id)"
                    + " AND ek.key_value = ik.key_value"
                    + " JOIN reconciliation.expectation e ON e.id = ek.expectation_id"
                    + " WHERE ik.item_id = i.id AND e.opened_at > " + LATEST_DECISION + ")"
                    // The operation-anchored rule's reach (P8-TSK-019, P8-TSK-018's recorded
                    // design input): a PAYOUT_RETURN opens no key of its own, so the keys above
                    // never see it - the item's key reaches the ANCHOR (the payout's
                    // MERCHANT_PAYOUT), and the anchored rule's kind for the same operation,
                    // under the anchor's source, that NO decision of the item has seen as a
                    // candidate and still holding a remainder - a spent return (a duplicate's
                    // reach) leaves the worklist instead of being re-locked on every tick.
                    // "Not yet seen", never "opened after the latest decision": the return is
                    // opened on the worker's instance clock and the decision stamped on the
                    // matcher's, and comparing two instances' clocks left a return opened
                    // within their skew of the decision waiting for its 72-hour grace
                    // (P8-TST-001's correction). The anchored rule's reach is one expectation
                    // per operation, and a decision that judges the line against it records it
                    // as a candidate. The two readings differ for a decision that records no
                    // candidates - a DUPLICATE verdict's empty snapshot - so a later report's
                    // repeat of a returned line, parked as a duplicate after the return opened,
                    // is on this worklist where the old reading left it off, and claimant order
                    // (line_no across runs) may let it take the return before the genuine line:
                    // value conserved, attribution wrong - recorded debt (P8-TST-001's second
                    // gate pass). A rematch that reaches the return and allocates nothing writes
                    // no decision, which leaves the line on the worklist under either reading.
                    + " OR EXISTS (SELECT 1 FROM reconciliation.external_item_key ak"
                    + " JOIN reconciliation.expectation_key aek"
                    + " ON aek.source_id = COALESCE(i.attributed_source_id, i.source_id)"
                    + " AND aek.key_value = ak.key_value"
                    + " JOIN reconciliation.expectation anchor ON anchor.id = aek.expectation_id"
                    + " JOIN reconciliation.rule_set ar ON ar.source_id = i.source_id"
                    + " AND ar.status = 'ACTIVE'"
                    + " JOIN reconciliation.rule arule ON arule.rule_set_id = ar.id"
                    + " AND arule.line_type = i.line_type AND arule.operation_anchored"
                    + " JOIN reconciliation.expectation reached"
                    + " ON reached.operation_ref = anchor.operation_ref"
                    + " AND reached.kind = arule.expectation_kind"
                    + " AND reached.source_id = anchor.source_id AND reached.id <> anchor.id"
                    + " AND reached.status IN ('OPEN', 'PARTIALLY_SETTLED')"
                    + " WHERE ak.item_id = i.id AND NOT EXISTS (SELECT 1 FROM"
                    + " reconciliation.match_candidate seen JOIN reconciliation.match_decision sd"
                    + " ON sd.id = seen.decision_id WHERE sd.external_item_id = i.id"
                    + " AND seen.expectation_id = reached.id))"
                    + " OR (i.status = 'UNMATCHED' AND i.attributed_source_id IS NOT NULL"
                    + " AND EXISTS (SELECT 1 FROM reconciliation.rule g"
                    + " JOIN reconciliation.rule_set gr"
                    + " ON gr.id = g.rule_set_id AND gr.status = 'ACTIVE'"
                    + " JOIN reconciliation.expectation e"
                    + " ON e.source_id = i.attributed_source_id"
                    + " AND e.kind = g.expectation_kind"
                    + " WHERE gr.source_id = i.source_id AND g.line_type = i.line_type"
                    + " AND g.cardinality = 'GROUP_BY_VALUE_DATE'"
                    + " AND e.status = 'OPEN' AND e.allocated_minor = 0"
                    + " AND e.resolved_minor = 0 AND e.direction = i.direction"
                    + " AND e.currency = i.currency"
                    + " AND e.expected_by = COALESCE(i.value_date, i.business_date)"
                    + " AND e.opened_at > " + LATEST_DECISION + ")))";

    /** A locking residual read admits the unattributed and the held attributions only. */
    private static final String HELD_ATTRIBUTION =
            " AND (i.attributed_source_id IS NULL OR i.attributed_source_id = ANY (?))";

    @Override
    public List<UUID> sourcesWithExpiredGrace(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT source_id FROM reconciliation.external_item"
                                + " WHERE status = 'UNMATCHED' AND grace_until IS NOT NULL"
                                + " AND grace_until <= now() ORDER BY source_id")) {
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> sources = new ArrayList<>();
                while (rows.next()) {
                    sources.add(rows.getObject("source_id", UUID.class));
                }
                return List.copyOf(sources);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not list the grace worklist", failure);
        }
    }

    @Override
    public List<UUID> attributedSourcesWithExpiredGrace(Connection unitOfWork, UUID sourceId) {
        return distinctAttributions(
                unitOfWork,
                "SELECT DISTINCT i.attributed_source_id FROM reconciliation.external_item i"
                        + " WHERE" + EXPIRED_PREDICATE
                        + " AND i.attributed_source_id IS NOT NULL",
                sourceId);
    }

    @Override
    public List<ResidualItem> lockExpiredItems(
            Connection unitOfWork, UUID sourceId, Collection<UUID> heldAttributions,
            int limit) {
        // FOR UPDATE OF i so the judgement is made on the locked row (ADR-0073 section 7).
        return lockedResiduals(
                unitOfWork,
                RESIDUAL_COLUMNS
                        + " WHERE" + EXPIRED_PREDICATE + HELD_ATTRIBUTION
                        + " ORDER BY i.grace_until, i.id LIMIT ? FOR UPDATE OF i",
                sourceId,
                heldAttributions,
                limit);
    }

    @Override
    public List<UUID> sourcesWithRematchWork(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT i.source_id FROM reconciliation.external_item i"
                                + " WHERE" + REMATCH_PREDICATE
                                + " ORDER BY i.source_id")) {
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> sources = new ArrayList<>();
                while (rows.next()) {
                    sources.add(rows.getObject("source_id", UUID.class));
                }
                return List.copyOf(sources);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not list the rematch worklist", failure);
        }
    }

    @Override
    public List<UUID> attributedSourcesWithRematchWork(Connection unitOfWork, UUID sourceId) {
        return distinctAttributions(
                unitOfWork,
                "SELECT DISTINCT i.attributed_source_id FROM reconciliation.external_item i"
                        + " WHERE i.source_id = ? AND i.attributed_source_id IS NOT NULL"
                        + " AND" + REMATCH_PREDICATE,
                sourceId);
    }

    @Override
    public List<ResidualItem> lockRematchCandidates(
            Connection unitOfWork, UUID sourceId, Collection<UUID> heldAttributions,
            int limit) {
        return lockedResiduals(
                unitOfWork,
                RESIDUAL_COLUMNS
                        + " WHERE i.source_id = ? AND" + REMATCH_PREDICATE + HELD_ATTRIBUTION
                        + " ORDER BY i.line_no, i.id LIMIT ? FOR UPDATE OF i",
                sourceId,
                heldAttributions,
                limit);
    }

    private static List<UUID> distinctAttributions(
            Connection unitOfWork, String sql, UUID sourceId) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, sourceId);
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> attributions = new ArrayList<>();
                while (rows.next()) {
                    attributions.add(rows.getObject("attributed_source_id", UUID.class));
                }
                return List.copyOf(attributions);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the worklist's attributed sources", failure);
        }
    }

    @Override
    public boolean markItemMatchedFrom(
            Connection unitOfWork,
            UUID itemId,
            String fromStatus,
            long allocatedMinor,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET status = 'MATCHED',"
                                + " allocated_minor = allocated_minor + ?,"
                                + " parked_minor = parked_minor - CASE WHEN ? = 'PARKED'"
                                + " THEN ? ELSE 0 END,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setLong(1, allocatedMinor);
            update.setString(2, fromStatus);
            update.setLong(3, allocatedMinor);
            update.setTimestamp(4, Timestamp.from(at));
            update.setObject(5, itemId);
            update.setString(6, fromStatus);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not match the item", failure);
        }
        appendItemEvent(unitOfWork, itemId, fromStatus, "MATCHED", actor, at, correlation);
        return true;
    }

    @Override
    public List<OverdueCandidate> overdueCandidates(Connection unitOfWork, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT e.id, e.kind, e.direction, e.operation_ref,"
                                + " e.amount_minor - e.allocated_minor - e.resolved_minor"
                                + " AS remainder_minor, e.currency, e.scale, e.source_id,"
                                + " e.rule_set_id, e.expected_by, e.correlation_id"
                                + " FROM reconciliation.expectation e"
                                + " LEFT JOIN reconciliation.tolerance t"
                                + " ON t.rule_set_id = e.rule_set_id"
                                + " AND t.comparison = 'SETTLEMENT_DATE_DAYS'"
                                + " WHERE e.status IN ('OPEN', 'PARTIALLY_SETTLED')"
                                + " AND e.overdue_since IS NULL"
                                + " AND e.expected_by + COALESCE(t.days, 0) < current_date"
                                + " ORDER BY e.expected_by LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<OverdueCandidate> candidates = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency =
                            CurrencyCode.of(rows.getString("currency").trim());
                    candidates.add(
                            new OverdueCandidate(
                                    rows.getObject("id", UUID.class),
                                    ExpectationKind.valueOf(rows.getString("kind")),
                                    ExpectationDirection.valueOf(
                                            rows.getString("direction")),
                                    rows.getString("operation_ref"),
                                    Money.ofPersisted(
                                            Math.max(0L, rows.getLong("remainder_minor")),
                                            currency,
                                            rows.getInt("scale")),
                                    rows.getObject("source_id", UUID.class),
                                    rows.getObject("rule_set_id", UUID.class),
                                    rows.getObject("expected_by", LocalDate.class),
                                    rows.getString("correlation_id")));
                }
                return List.copyOf(candidates);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not list the overdue candidates", failure);
        }
    }

    @Override
    public boolean lockAndMarkOverdue(
            Connection unitOfWork, UUID expectationId, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.expectation e SET overdue_since = ?,"
                                + " status_changed_at = ?"
                                + " WHERE e.id = ? AND e.overdue_since IS NULL"
                                + " AND e.status IN ('OPEN', 'PARTIALLY_SETTLED')"
                                + " AND EXISTS (SELECT 1 FROM reconciliation.expectation x"
                                + " LEFT JOIN reconciliation.tolerance t"
                                + " ON t.rule_set_id = x.rule_set_id"
                                + " AND t.comparison = 'SETTLEMENT_DATE_DAYS'"
                                + " WHERE x.id = e.id AND x.expected_by"
                                + " + COALESCE(t.days, 0) < current_date)")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, expectationId);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not mark the expectation overdue", failure);
        }
    }

    @Override
    public List<EscalationRow> unresolvedBreaks(Connection unitOfWork, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT b.id, b.source_id, b.severity,"
                                + " GREATEST(0, EXTRACT(day FROM now() - b.raised_at))"
                                + " AS days_since,"
                                + " (SELECT count(*) FROM reconciliation.break_event ev"
                                + " WHERE ev.break_id = b.id AND ev.event_type ="
                                + " 'SEVERITY_ESCALATED') AS escalations"
                                + " FROM reconciliation.break b"
                                + " WHERE b.status <> 'RESOLVED'"
                                + " AND b.severity <> 'CRITICAL'"
                                + " ORDER BY b.raised_at LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<EscalationRow> breaks = new ArrayList<>();
                while (rows.next()) {
                    breaks.add(
                            new EscalationRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("source_id", UUID.class),
                                    Severity.valueOf(rows.getString("severity")),
                                    rows.getLong("days_since"),
                                    rows.getLong("escalations")));
                }
                return List.copyOf(breaks);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not list the unresolved breaks", failure);
        }
    }

    @Override
    public boolean escalate(
            Connection unitOfWork,
            UUID breakId,
            Severity from,
            Severity to,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET severity = ?,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND severity = ?"
                                + " AND status <> 'RESOLVED'")) {
            update.setString(1, to.name());
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, breakId);
            update.setString(4, from.name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not escalate the break", failure);
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_event (break_id, event_type,"
                                + " actor, actor_type, reason, detail, occurred_at,"
                                + " correlation_id) VALUES (?, 'SEVERITY_ESCALATED', ?,"
                                + " ?, NULL, ?, ?, ?)")) {
            insert.setObject(1, breakId);
            insert.setString(2, actor.id());
            insert.setString(3, actor.type().name());
            insert.setString(4, "from=" + from.name() + ", to=" + to.name());
            insert.setTimestamp(5, Timestamp.from(at));
            insert.setString(6, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the escalation", failure);
        }
        return true;
    }

    @Override
    public List<RunRow> runsAtFailureBound(Connection unitOfWork, int bound) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RUN_COLUMNS
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE status IN ('OPEN', 'IN_PROGRESS')"
                                + " AND failures >= ? ORDER BY id")) {
            read.setInt(1, bound);
            try (ResultSet rows = read.executeQuery()) {
                List<RunRow> runs = new ArrayList<>();
                while (rows.next()) {
                    runs.add(runRow(rows));
                }
                return List.copyOf(runs);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not list the runs at the failure bound", failure);
        }
    }

    @Override
    public String itemStatus(Connection unitOfWork, UUID itemId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT status FROM reconciliation.external_item WHERE id = ?")) {
            read.setObject(1, itemId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException(
                            "item " + itemId + " vanished under its lock");
                }
                return row.getString("status");
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the item's status", failure);
        }
    }

    @Override
    public boolean openBreakExistsOn(
            Connection unitOfWork, UUID expectationId, BreakType type) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT 1 FROM reconciliation.break WHERE expectation_id = ?"
                                + " AND type = ? AND status <> 'RESOLVED' LIMIT 1")) {
            read.setObject(1, expectationId);
            read.setString(2, type.name());
            try (ResultSet row = read.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation's open breaks", failure);
        }
    }

    private static final class ChunkItemBuilder {
        UUID id;
        long lineNo;
        ExternalLineType lineType;
        ExpectationDirection direction;
        Money amount;
        Optional<com.finapp.ledger.AccountPurpose> positionPurpose;
        Optional<UUID> attributedSourceId;
        LocalDate businessDate;
        Optional<LocalDate> settlementDate;
        Optional<LocalDate> valueDate;
        byte[] fingerprint;
        long sourceSequence;
        final Map<ItemKeyKind, String> keys = new EnumMap<>(ItemKeyKind.class);

        ChunkItem build() {
            return new ChunkItem(
                    id, lineNo, lineType, direction, amount, positionPurpose,
                    attributedSourceId, businessDate, settlementDate, valueDate, fingerprint,
                    sourceSequence, keys);
        }
    }

    @Override
    public boolean fingerprintSeenEarlier(
            Connection unitOfWork,
            UUID sourceId,
            byte[] fingerprint,
            long sourceSequence,
            long lineNo) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT 1 FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r"
                                + " ON r.id = i.run_id"
                                + " WHERE i.source_id = ? AND i.canonical_fingerprint = ?"
                                // A repudiated line is no longer evidence: the genuine
                                // line that replaces it is never its duplicate (P8-TSK-023).
                                + " AND i.status <> 'REPUDIATED'"
                                + " AND (r.source_sequence < ? OR (r.source_sequence = ?"
                                + " AND i.line_no < ?)) LIMIT 1")) {
            read.setObject(1, sourceId);
            read.setBytes(2, fingerprint);
            read.setLong(3, sourceSequence);
            read.setLong(4, sourceSequence);
            read.setLong(5, lineNo);
            try (ResultSet row = read.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not judge the fingerprint's claimant order", failure);
        }
    }

    @Override
    public List<UUID> expectationsByKey(
            Connection unitOfWork, UUID sourceId, KeyKind kind, String value) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT expectation_id FROM reconciliation.expectation_key"
                                + " WHERE source_id = ? AND key_kind = ? AND"
                                + " key_value = ?")) {
            read.setObject(1, sourceId);
            read.setString(2, kind.name());
            read.setString(3, value);
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> hits = new ArrayList<>();
                while (rows.next()) {
                    hits.add(rows.getObject("expectation_id", UUID.class));
                }
                return List.copyOf(hits);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not resolve the key", failure);
        }
    }

    @Override
    public Optional<Map.Entry<KeyKind, String>> aliasAnchor(
            Connection unitOfWork, UUID sourceId, KeyKind kind, String value) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT anchor_kind, anchor_value FROM"
                                + " reconciliation.reference_alias"
                                + " WHERE source_id = ? AND key_kind = ? AND"
                                + " key_value = ?")) {
            read.setObject(1, sourceId);
            read.setString(2, kind.name());
            read.setString(3, value);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new AbstractMap.SimpleImmutableEntry<>(
                                KeyKind.valueOf(row.getString("anchor_kind")),
                                row.getString("anchor_value")));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not resolve the alias", failure);
        }
    }

    @Override
    public List<UUID> groupCandidates(
            Connection unitOfWork,
            UUID sourceId,
            ExpectationKind kind,
            ExpectationDirection direction,
            CurrencyCode currency,
            LocalDate expectedBy) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.expectation"
                                + " WHERE source_id = ? AND kind = ? AND direction = ?"
                                + " AND currency = ? AND expected_by = ?"
                                // Untouched: nothing allocated, nothing resolved - an
                                // allocation's own transaction sees its uncommitted update.
                                + " AND status = 'OPEN' AND allocated_minor = 0"
                                + " AND resolved_minor = 0"
                                + " ORDER BY id")) {
            read.setObject(1, sourceId);
            read.setString(2, kind.name());
            read.setString(3, direction.name());
            read.setString(4, currency.code());
            read.setObject(5, expectedBy);
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> candidates = new ArrayList<>();
                while (rows.next()) {
                    candidates.add(rows.getObject("id", UUID.class));
                }
                return List.copyOf(candidates);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the value-date group's candidates", failure);
        }
    }

    @Override
    public List<MatchEngine.HitFacts> lockExpectations(
            Connection unitOfWork,
            Collection<UUID> expectationIds,
            Map<UUID, KeyKind> reachedBy) {
        List<MatchEngine.HitFacts> facts = new ArrayList<>();
        for (UUID id : new TreeSet<>(expectationIds)) {
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT kind, direction, amount_minor, currency, scale,"
                                    + " allocated_minor, resolved_minor, opened_at,"
                                    + " expected_by, status, operation_ref, settlement_cycle"
                                    + " FROM reconciliation.expectation WHERE id = ?"
                                    + " FOR UPDATE")) {
                read.setObject(1, id);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        throw new ReconciliationStorageException(
                                "expectation " + id + " vanished under a key that names"
                                        + " it: the register is append-only");
                    }
                    long remainder =
                            "RESOLVED_BY_ADJUSTMENT".equals(row.getString("status"))
                                    ? 0L
                                    : row.getLong("amount_minor")
                                            - row.getLong("allocated_minor")
                                            - row.getLong("resolved_minor");
                    facts.add(
                            new MatchEngine.HitFacts(
                                    id,
                                    ExpectationKind.valueOf(row.getString("kind")),
                                    ExpectationDirection.valueOf(
                                            row.getString("direction")),
                                    Money.ofPersisted(
                                            row.getLong("amount_minor"),
                                            CurrencyCode.of(
                                                    row.getString("currency").trim()),
                                            row.getInt("scale")),
                                    Math.max(0L, remainder),
                                    row.getTimestamp("opened_at").toInstant(),
                                    row.getObject("expected_by", LocalDate.class),
                                    Optional.ofNullable(reachedBy.get(id)),
                                    row.getString("operation_ref"),
                                    // The cycle the completion announced - frozen on the row
                                    // (V002's trigger), so the live read IS the snapshot.
                                    Optional.ofNullable(row.getString("settlement_cycle"))));
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException(
                        "could not lock the candidates", failure);
            }
        }
        return List.copyOf(facts);
    }

    @Override
    public void lockItems(Connection unitOfWork, Collection<UUID> itemIds) {
        for (UUID id : new TreeSet<>(itemIds)) {
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT id FROM reconciliation.external_item WHERE id = ?"
                                    + " FOR UPDATE")) {
                read.setObject(1, id);
                read.executeQuery().close();
            } catch (SQLException failure) {
                throw new ReconciliationStorageException(
                        "could not lock the chunk's items", failure);
            }
        }
    }

    // ------------------------------------------------------------------ writes

    @Override
    public void insertDecision(Connection unitOfWork, NewDecision decision) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                                + " run_id, origin, rule_set_id, rule_priority, strategy,"
                                + " matched_key_kind, outcome, claimant_rank,"
                                + " claimant_count, date_deviation_days,"
                                + " timing_tolerance_days, fee_expected_minor,"
                                + " fee_reported_minor, fee_tolerance_minor, decided_by,"
                                + " decided_by_type, decided_at, decided_on,"
                                + " correlation_id, verdict, judged_status, judged_minor,"
                                + " fingerprint_seen_earlier, group_membership_complete,"
                                + " fee_gross_minor)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, decision.id());
            insert.setObject(2, decision.externalItemId());
            insert.setObject(3, decision.runId());
            insert.setString(4, decision.origin().name());
            insert.setObject(5, decision.ruleSetId());
            insert.setObject(6, decision.rulePriority().orElse(null));
            insert.setString(7, decision.strategy().map(Enum::name).orElse(null));
            insert.setString(8, decision.matchedKeyKind().map(Enum::name).orElse(null));
            insert.setString(9, decision.outcome().name());
            insert.setObject(10, decision.claimantRank().orElse(null));
            insert.setObject(11, decision.claimantCount().orElse(null));
            insert.setObject(12, decision.dateDeviationDays().orElse(null));
            insert.setObject(13, decision.timingToleranceDays().orElse(null));
            insert.setObject(14, decision.feeExpectedMinor().orElse(null));
            insert.setObject(15, decision.feeReportedMinor().orElse(null));
            insert.setObject(16, decision.feeToleranceMinor().orElse(null));
            insert.setString(17, decision.decidedBy().id());
            insert.setString(18, decision.decidedBy().type().name());
            insert.setTimestamp(19, Timestamp.from(decision.decidedAt()));
            insert.setObject(20, decision.decidedOn());
            insert.setString(21, decision.correlation().value());
            MatchingStore.Basis basis = decision.basis();
            insert.setString(22, basis.verdict().name());
            insert.setString(23, basis.judgedStatus().name());
            insert.setLong(24, basis.judgedMinor());
            insert.setObject(25, basis.fingerprintSeenEarlier().orElse(null));
            insert.setObject(26, basis.groupMembershipComplete().orElse(null));
            insert.setObject(27, basis.feeGrossMinor().orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the decision", failure);
        }
    }

    @Override
    public void insertParkedOriginals(
            Connection unitOfWork,
            UUID decisionId,
            List<CorrectionEngine.ParkedOriginal> parkedOriginals) {
        if (parkedOriginals.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.match_parked_original (decision_id,"
                                + " ordinal, original_item_id, suspense_item_id, break_id,"
                                + " side, remainder_minor, currency, scale)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int ordinal = 0;
            for (CorrectionEngine.ParkedOriginal original : parkedOriginals) {
                insert.setObject(1, decisionId);
                insert.setInt(2, ordinal++);
                insert.setObject(3, original.originalItemId());
                insert.setObject(4, original.suspenseItemId());
                insert.setObject(5, original.breakId());
                insert.setString(6, original.side().name());
                insert.setLong(7, original.remainderMinor());
                insert.setString(8, original.currency().code());
                insert.setInt(9, original.scale());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the correction's parked originals", failure);
        }
    }

    @Override
    public void insertCandidates(
            Connection unitOfWork, UUID decisionId, List<MatchEngine.HitFacts> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.match_candidate (decision_id,"
                                + " expectation_id, key_kind, amount_minor, currency,"
                                + " scale, direction, remainder_before_minor, opened_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (MatchEngine.HitFacts candidate : candidates) {
                insert.setObject(1, decisionId);
                insert.setObject(2, candidate.expectationId());
                // NULL exactly for a value-date group's candidate (V008).
                insert.setString(3, candidate.reachedBy().map(Enum::name).orElse(null));
                insert.setLong(4, candidate.amount().minorUnits());
                insert.setString(5, candidate.amount().currency().code());
                insert.setInt(6, candidate.amount().scale());
                insert.setString(7, candidate.direction().name());
                insert.setLong(8, candidate.remainderMinor());
                insert.setTimestamp(9, Timestamp.from(candidate.openedAt()));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the candidate snapshot", failure);
        }
    }

    @Override
    public void insertAllocation(Connection unitOfWork, NewAllocation allocation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.allocation (id, decision_id,"
                                + " external_item_id, expectation_id, amount_minor,"
                                + " currency, scale, reverses_allocation_id, created_at,"
                                + " correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)")) {
            insert.setObject(1, allocation.id());
            insert.setObject(2, allocation.decisionId());
            insert.setObject(3, allocation.externalItemId());
            insert.setObject(4, allocation.expectationId());
            insert.setLong(5, allocation.amount().minorUnits());
            insert.setString(6, allocation.amount().currency().code());
            insert.setInt(7, allocation.amount().scale());
            insert.setTimestamp(8, Timestamp.from(allocation.at()));
            insert.setString(9, allocation.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the allocation", failure);
        }
    }

    @Override
    public ExpectationStatus allocateToExpectation(
            Connection unitOfWork,
            UUID expectationId,
            Money amount,
            String detail,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        String status;
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.expectation SET"
                                + " allocated_minor = allocated_minor + ?,"
                                + " status = CASE WHEN allocated_minor + ? +"
                                + " resolved_minor >= amount_minor THEN 'SETTLED'"
                                + " ELSE 'PARTIALLY_SETTLED' END,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? RETURNING status")) {
            update.setLong(1, amount.minorUnits());
            update.setLong(2, amount.minorUnits());
            update.setTimestamp(3, Timestamp.from(at));
            update.setObject(4, expectationId);
            try (ResultSet row = update.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException(
                            "expectation " + expectationId + " vanished under its lock");
                }
                status = row.getString("status");
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not allocate to the expectation", failure);
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.expectation_event (expectation_id,"
                                + " event_type, detail, actor, actor_type, occurred_at,"
                                + " correlation_id) VALUES (?, 'ALLOCATED', ?, ?, ?, ?,"
                                + " ?)")) {
            insert.setObject(1, expectationId);
            insert.setString(2, detail);
            insert.setString(3, actor.id());
            insert.setString(4, actor.type().name());
            insert.setTimestamp(5, Timestamp.from(at));
            insert.setString(6, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the expectation's history", failure);
        }
        return ExpectationStatus.valueOf(status);
    }

    @Override
    public void bumpResidualOnSubjects(
            Connection unitOfWork, UUID expectationId, UUID itemId) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET residual_version ="
                                + " residual_version + 1 WHERE status <> 'RESOLVED'"
                                + " AND (expectation_id = ? OR external_item_id = ?)")) {
            update.setObject(1, expectationId);
            update.setObject(2, itemId);
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not bump the touched breaks' residual", failure);
        }
    }

    @Override
    public void recordItemAllocation(
            Connection unitOfWork, UUID itemId, long allocatedMinor) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET allocated_minor ="
                                + " allocated_minor + ? WHERE id = ?")) {
            update.setLong(1, allocatedMinor);
            update.setObject(2, itemId);
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not record the item's allocation", failure);
        }
    }

    @Override
    public boolean markItemChecked(
            Connection unitOfWork, UUID itemId, Actor actor, Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET status = 'CHECKED',"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = 'PENDING'")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, itemId);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not check the item", failure);
        }
        appendItemEvent(unitOfWork, itemId, "PENDING", "CHECKED", actor, at, correlation);
        return true;
    }

    @Override
    public boolean markItemOffset(
            Connection unitOfWork,
            UUID itemId,
            long offsetMinor,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET status = 'OFFSET',"
                                + " offset_minor = offset_minor + ?, status_changed_at = ?"
                                + " WHERE id = ? AND status = 'PENDING'")) {
            update.setLong(1, offsetMinor);
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, itemId);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not offset the item", failure);
        }
        appendItemEvent(unitOfWork, itemId, "PENDING", "OFFSET", actor, at, correlation);
        return true;
    }

    @Override
    public boolean markParkedItemResolved(
            Connection unitOfWork, UUID itemId, Actor actor, Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET status = 'RESOLVED',"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = 'PARKED'")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, itemId);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not resolve the parked item", failure);
        }
        appendItemEvent(unitOfWork, itemId, "PARKED", "RESOLVED", actor, at, correlation);
        return true;
    }

    @Override
    public List<UUID> itemsByKey(
            Connection unitOfWork, UUID sourceId, ItemKeyKind kind, String value) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT k.item_id FROM reconciliation.external_item_key k"
                                + " JOIN reconciliation.external_item i"
                                + " ON i.id = k.item_id"
                                + " WHERE i.source_id = ? AND k.key_kind = ? AND"
                                + " k.key_value = ? ORDER BY k.item_id")) {
            read.setObject(1, sourceId);
            read.setString(2, kind.name());
            read.setString(3, value);
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> items = new ArrayList<>();
                while (rows.next()) {
                    items.add(rows.getObject("item_id", UUID.class));
                }
                return List.copyOf(items);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not resolve the original items", failure);
        }
    }

    @Override
    public List<CorrectionEngine.ParkedOriginal> lockParkedOriginals(
            Connection unitOfWork, Collection<UUID> originalItemIds) {
        if (originalItemIds.isEmpty()) {
            return List.of();
        }
        record Candidate(UUID suspenseItemId, UUID breakId, UUID originalItemId) {}
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, break_id, external_item_id"
                                + " FROM reconciliation.suspense_item"
                                + " WHERE external_item_id = ANY (?)"
                                + " AND status <> 'RELEASED'"
                                + " ORDER BY opened_on, id")) {
            read.setArray(
                    1,
                    unitOfWork.createArrayOf(
                            "uuid", new TreeSet<>(originalItemIds).toArray()));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    candidates.add(
                            new Candidate(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("break_id", UUID.class),
                                    rows.getObject("external_item_id", UUID.class)));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not find the parked originals", failure);
        }
        List<CorrectionEngine.ParkedOriginal> parked = new ArrayList<>();
        for (Candidate candidate : candidates) {
            try {
                // The section-3 order for the contended rows: the break row first, then
                // the suspense item, then the original external item.
                try (PreparedStatement lockBreak =
                        unitOfWork.prepareStatement(
                                "SELECT status FROM reconciliation.break WHERE id = ?"
                                        + " FOR UPDATE")) {
                    lockBreak.setObject(1, candidate.breakId());
                    try (ResultSet row = lockBreak.executeQuery()) {
                        row.next();
                        if ("RESOLVED".equals(row.getString("status"))) {
                            continue; // A resolved owner takes no new disposition.
                        }
                    }
                }
                try (PreparedStatement lockSuspense =
                        unitOfWork.prepareStatement(
                                "SELECT side, amount_minor, released_minor, currency,"
                                        + " scale, position_account_id"
                                        + " FROM reconciliation.suspense_item"
                                        + " WHERE id = ? AND status <> 'RELEASED'"
                                        + " FOR UPDATE")) {
                    lockSuspense.setObject(1, candidate.suspenseItemId());
                    try (ResultSet row = lockSuspense.executeQuery()) {
                        if (!row.next()) {
                            continue; // Released since the lock-free read: converge.
                        }
                        long remainder =
                                row.getLong("amount_minor") - row.getLong("released_minor");
                        if (remainder <= 0) {
                            continue;
                        }
                        try (PreparedStatement lockItem =
                                unitOfWork.prepareStatement(
                                        "SELECT id FROM reconciliation.external_item"
                                                + " WHERE id = ? AND status = 'PARKED'"
                                                + " FOR UPDATE")) {
                            lockItem.setObject(1, candidate.originalItemId());
                            try (ResultSet item = lockItem.executeQuery()) {
                                if (!item.next()) {
                                    continue; // Already re-allocated or resolved.
                                }
                            }
                        }
                        parked.add(
                                new CorrectionEngine.ParkedOriginal(
                                        candidate.originalItemId(),
                                        candidate.suspenseItemId(),
                                        candidate.breakId(),
                                        SuspenseSide.valueOf(row.getString("side")),
                                        remainder,
                                        CurrencyCode.of(
                                                row.getString("currency").trim()),
                                        row.getInt("scale"),
                                        row.getObject(
                                                "position_account_id", UUID.class)));
                    }
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException(
                        "could not lock the parked original", failure);
            }
        }
        return List.copyOf(parked);
    }

    @Override
    public Optional<Money> expectationAmount(Connection unitOfWork, UUID expectationId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT amount_minor, currency, scale FROM"
                                + " reconciliation.expectation WHERE id = ?")) {
            read.setObject(1, expectationId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        Money.ofPersisted(
                                row.getLong("amount_minor"),
                                CurrencyCode.of(row.getString("currency").trim()),
                                row.getInt("scale")));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation's amount", failure);
        }
    }

    @Override
    public List<UUID> anchoredExpectations(
            Connection unitOfWork, List<UUID> anchorIds, ExpectationKind kind) {
        if (anchorIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT reached.id FROM reconciliation.expectation anchor"
                                + " JOIN reconciliation.expectation reached"
                                + " ON reached.operation_ref = anchor.operation_ref"
                                + " AND reached.source_id = anchor.source_id"
                                + " AND reached.kind = ? AND reached.id <> anchor.id"
                                + " WHERE anchor.id = ANY (?) ORDER BY reached.id")) {
            read.setString(1, kind.name());
            read.setArray(2, unitOfWork.createArrayOf("uuid", anchorIds.toArray()));
            List<UUID> reached = new ArrayList<>();
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    reached.add(rows.getObject(1, UUID.class));
                }
            }
            return reached;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read an anchored rule's expectations", failure);
        }
    }

    @Override
    public void recordLearnedCycle(Connection unitOfWork, UUID itemId, String cycle) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET learned_cycle = ?"
                                + " WHERE id = ? AND learned_cycle IS NULL")) {
            update.setString(1, cycle);
            update.setObject(2, itemId);
            if (update.executeUpdate() != 1) {
                throw new ReconciliationStorageException(
                        "item " + itemId + " already learned a cycle under a held lock",
                        new SQLException("learned_cycle conditional missed"));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not record the learned cycle", failure);
        }
    }

    @Override
    public UUID expectationRuleSet(Connection unitOfWork, UUID expectationId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT rule_set_id FROM reconciliation.expectation WHERE id = ?")) {
            read.setObject(1, expectationId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException(
                            "expectation " + expectationId + " vanished under its lock");
                }
                return row.getObject("rule_set_id", UUID.class);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation's rule set", failure);
        }
    }

    @Override
    public long breakResidualVersion(Connection unitOfWork, UUID breakId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT residual_version FROM reconciliation.break"
                                + " WHERE id = ?")) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException(
                            "break " + breakId + " vanished under its lock");
                }
                return row.getLong("residual_version");
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the break's residual version", failure);
        }
    }

    @Override
    public Optional<UUID> lockOpenBreakOn(
            Connection unitOfWork, UUID expectationId, BreakType type) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.break WHERE expectation_id = ?"
                                + " AND type = ? AND status <> 'RESOLVED' FOR UPDATE")) {
            read.setObject(1, expectationId);
            read.setString(2, type.name());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("id", UUID.class))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not lock the expectation's open break", failure);
        }
    }

    @Override
    public List<FeeDecisionRow> feeDecisionsOf(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT i.id, i.line_no, i.currency, i.scale,"
                                + " d.fee_expected_minor, d.fee_reported_minor"
                                + " FROM reconciliation.match_decision d"
                                + " JOIN reconciliation.external_item i"
                                + " ON i.id = d.external_item_id"
                                + " WHERE d.run_id = ? AND d.outcome = 'CHECKED'"
                                + " AND d.fee_reported_minor IS NOT NULL"
                                // The per-batch bound is the processing fee's alone: a
                                // bank fee is judged per line (P8-TSK-016).
                                + " AND i.line_type = 'PROCESSING_FEE'"
                                + " ORDER BY i.line_no")) {
            read.setObject(1, runId);
            try (ResultSet rows = read.executeQuery()) {
                List<FeeDecisionRow> decisions = new ArrayList<>();
                while (rows.next()) {
                    decisions.add(
                            new FeeDecisionRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getLong("line_no"),
                                    rows.getString("currency").trim(),
                                    rows.getInt("scale"),
                                    rows.getLong("fee_expected_minor"),
                                    rows.getLong("fee_reported_minor")));
                }
                return List.copyOf(decisions);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the run's fee decisions", failure);
        }
    }

    @Override
    public boolean markItemUnmatched(
            Connection unitOfWork,
            UUID itemId,
            Optional<Integer> graceHours,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.external_item SET status = 'UNMATCHED',"
                                // The window is judged on the database clock, from now:
                                // stored once, compared in SQL (ADR-0068 section 6).
                                + " grace_until = CASE WHEN ?::int IS NULL THEN NULL"
                                + " ELSE statement_timestamp() +"
                                + " make_interval(hours => ?) END,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = 'PENDING'")) {
            update.setObject(1, graceHours.orElse(null));
            update.setObject(2, graceHours.orElse(null));
            update.setTimestamp(3, Timestamp.from(at));
            update.setObject(4, itemId);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not leave the item waiting", failure);
        }
        appendItemEvent(unitOfWork, itemId, "PENDING", "UNMATCHED", actor, at, correlation);
        return true;
    }

    private void appendItemEvent(
            Connection unitOfWork,
            UUID itemId,
            String fromStatus,
            String toStatus,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.external_item_event (item_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")) {
            insert.setObject(1, itemId);
            insert.setString(2, fromStatus);
            insert.setString(3, toStatus);
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.setString(7, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the item's history", failure);
        }
    }

    // ------------------------------------------------------------------ the doors' reads

    @Override
    public Optional<DecisionRow> decision(Connection unitOfWork, UUID decisionId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, external_item_id, run_id, origin, rule_set_id,"
                                + " rule_priority, strategy, matched_key_kind, outcome,"
                                + " claimant_rank, claimant_count, date_deviation_days,"
                                + " timing_tolerance_days, fee_expected_minor,"
                                + " fee_reported_minor, fee_tolerance_minor, decided_at,"
                                + " decided_on"
                                + " FROM reconciliation.match_decision WHERE id = ?")) {
            read.setObject(1, decisionId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new DecisionRow(
                                row.getObject("id", UUID.class),
                                row.getObject("external_item_id", UUID.class),
                                row.getObject("run_id", UUID.class),
                                row.getString("origin"),
                                row.getObject("rule_set_id", UUID.class),
                                Optional.ofNullable(
                                        row.getObject("rule_priority", Integer.class)),
                                Optional.ofNullable(row.getString("strategy")),
                                Optional.ofNullable(row.getString("matched_key_kind")),
                                row.getString("outcome"),
                                Optional.ofNullable(
                                        row.getObject("claimant_rank", Integer.class)),
                                Optional.ofNullable(
                                        row.getObject("claimant_count", Integer.class)),
                                Optional.ofNullable(
                                        row.getObject(
                                                "date_deviation_days", Integer.class)),
                                Optional.ofNullable(
                                        row.getObject(
                                                "timing_tolerance_days", Integer.class)),
                                Optional.ofNullable(
                                        row.getObject("fee_expected_minor", Long.class)),
                                Optional.ofNullable(
                                        row.getObject("fee_reported_minor", Long.class)),
                                Optional.ofNullable(
                                        row.getObject("fee_tolerance_minor", Long.class)),
                                row.getTimestamp("decided_at").toInstant(),
                                row.getObject("decided_on", LocalDate.class)));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the decision", failure);
        }
    }

    @Override
    public List<CandidateRow> candidatesOf(Connection unitOfWork, UUID decisionId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT expectation_id, key_kind, amount_minor, currency, scale,"
                                + " direction, remainder_before_minor, opened_at"
                                + " FROM reconciliation.match_candidate"
                                + " WHERE decision_id = ? ORDER BY expectation_id")) {
            read.setObject(1, decisionId);
            try (ResultSet rows = read.executeQuery()) {
                List<CandidateRow> candidates = new ArrayList<>();
                while (rows.next()) {
                    candidates.add(
                            new CandidateRow(
                                    rows.getObject("expectation_id", UUID.class),
                                    rows.getString("key_kind"),
                                    Money.ofPersisted(
                                            rows.getLong("amount_minor"),
                                            CurrencyCode.of(
                                                    rows.getString("currency").trim()),
                                            rows.getInt("scale")),
                                    rows.getString("direction"),
                                    rows.getLong("remainder_before_minor"),
                                    rows.getTimestamp("opened_at").toInstant()));
                }
                return List.copyOf(candidates);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the candidates", failure);
        }
    }

    @Override
    public List<AllocationRow> allocationsOfDecision(
            Connection unitOfWork, UUID decisionId) {
        return allocations(unitOfWork, "decision_id = ?", decisionId);
    }

    @Override
    public Optional<AllocationRow> allocation(Connection unitOfWork, UUID allocationId) {
        List<AllocationRow> rows = allocations(unitOfWork, "id = ?", allocationId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<AllocationRow> allocations(
            Connection unitOfWork, String predicate, UUID argument) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, decision_id, external_item_id, expectation_id,"
                                + " amount_minor, currency, scale, reverses_allocation_id,"
                                + " created_at FROM reconciliation.allocation WHERE "
                                + predicate + " ORDER BY created_at")) {
            read.setObject(1, argument);
            try (ResultSet rows = read.executeQuery()) {
                List<AllocationRow> allocations = new ArrayList<>();
                while (rows.next()) {
                    allocations.add(
                            new AllocationRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("decision_id", UUID.class),
                                    rows.getObject("external_item_id", UUID.class),
                                    rows.getObject("expectation_id", UUID.class),
                                    Money.ofPersisted(
                                            rows.getLong("amount_minor"),
                                            CurrencyCode.of(
                                                    rows.getString("currency").trim()),
                                            rows.getInt("scale")),
                                    Optional.ofNullable(
                                            rows.getObject(
                                                    "reverses_allocation_id", UUID.class)),
                                    rows.getTimestamp("created_at").toInstant()));
                }
                return List.copyOf(allocations);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the allocations", failure);
        }
    }

    @Override
    public Optional<RunRow> run(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RUN_COLUMNS
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE id = ?")) {
            read.setObject(1, runId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(runRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the run", failure);
        }
    }

    // ------------------------------------------------------------------ P8-TSK-022

    /**
     * The reprocess worklist (`P8-TSK-022`, ADR-0068 §9.2): residual items only - the rematch
     * predicate's first two clauses without its "something new opened" condition - that hold no
     * decision of THIS run ({@code ?}), so the run's progress is the existence of its decisions:
     * a crash resumes exactly where the last commit left off, on any instance.
     */
    private static final String REPROCESS_PREDICATE =
            " i.status IN ('UNMATCHED', 'PARKED')"
                    + " AND NOT EXISTS (SELECT 1 FROM reconciliation.suspense_item s"
                    + " WHERE s.external_item_id = i.id AND s.origin <> 'RECON_PARK')"
                    + " AND NOT EXISTS (SELECT 1 FROM reconciliation.match_decision d"
                    + " WHERE d.external_item_id = i.id AND d.run_id = ?)";

    @Override
    public UUID activeRuleSetId(Connection unitOfWork, UUID sourceId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.rule_set"
                                + " WHERE source_id = ? AND status = 'ACTIVE'")) {
            read.setObject(1, sourceId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException(
                            "a source always has exactly one active rule set: " + sourceId);
                }
                return row.getObject("id", UUID.class);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the active rule set", failure);
        }
    }

    @Override
    public List<UUID> sourcesWithOpenReprocess(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT source_id FROM reconciliation.reconciliation_batch"
                                + " WHERE kind = 'REPROCESS' AND status IN ('OPEN',"
                                + " 'IN_PROGRESS') ORDER BY source_id")) {
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> sources = new ArrayList<>();
                while (rows.next()) {
                    sources.add(rows.getObject("source_id", UUID.class));
                }
                return List.copyOf(sources);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not list the reprocess worklist", failure);
        }
    }

    @Override
    public Optional<RunRow> openReprocessRun(Connection unitOfWork, UUID sourceId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RUN_COLUMNS
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE source_id = ? AND kind = 'REPROCESS'"
                                + " AND status IN ('OPEN', 'IN_PROGRESS')")) {
            read.setObject(1, sourceId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(runRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the open reprocess run", failure);
        }
    }

    @Override
    public int reprocessWorklistSize(Connection unitOfWork, UUID sourceId, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM reconciliation.external_item i"
                                + " WHERE i.source_id = ? AND" + REPROCESS_PREDICATE)) {
            read.setObject(1, sourceId);
            read.setObject(2, runId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not count the reprocess worklist", failure);
        }
    }

    @Override
    public List<UUID> attributedSourcesWithReprocessWork(
            Connection unitOfWork, UUID sourceId, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT i.attributed_source_id"
                                + " FROM reconciliation.external_item i"
                                + " WHERE i.source_id = ? AND i.attributed_source_id IS NOT NULL"
                                + " AND" + REPROCESS_PREDICATE)) {
            read.setObject(1, sourceId);
            read.setObject(2, runId);
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> attributions = new ArrayList<>();
                while (rows.next()) {
                    attributions.add(rows.getObject("attributed_source_id", UUID.class));
                }
                return List.copyOf(attributions);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the reprocess worklist's attributed sources", failure);
        }
    }

    @Override
    public List<ResidualItem> lockReprocessCandidates(
            Connection unitOfWork,
            UUID sourceId,
            UUID runId,
            Collection<UUID> heldAttributions,
            int limit) {
        // Claimant order across the source's runs (ADR-0068 section 4); FOR UPDATE OF i so the
        // judgement is made on the locked row.
        return lockedResiduals(
                unitOfWork,
                RESIDUAL_COLUMNS
                        + " WHERE i.source_id = ? AND" + REPROCESS_PREDICATE + HELD_ATTRIBUTION
                        + " ORDER BY r.source_sequence, i.line_no, i.id LIMIT ? FOR UPDATE OF i",
                List.of(sourceId, runId, heldAttributions, limit));
    }

    @Override
    public long itemParkedMinor(Connection unitOfWork, UUID itemId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT parked_minor FROM reconciliation.external_item WHERE id = ?")) {
            read.setObject(1, itemId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException("no such item: " + itemId);
                }
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the item's parked value", failure);
        }
    }

    @Override
    public Optional<UUID> lockOpenBreakOnRun(Connection unitOfWork, UUID runId, BreakType type) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.break WHERE run_id = ?"
                                + " AND type = ? AND status <> 'RESOLVED' FOR UPDATE")) {
            read.setObject(1, runId);
            read.setString(2, type.name());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("id", UUID.class))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not lock the run's open break", failure);
        }
    }

    @Override
    public int pendingRematchOf(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM reconciliation.external_item i"
                                + " WHERE i.run_id = ? AND" + REMATCH_PREDICATE)) {
            read.setObject(1, runId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not count the run's pending rematches", failure);
        }
    }

    @Override
    public Optional<Instant> runBornAt(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT created_at FROM reconciliation.reconciliation_batch WHERE id = ?")) {
            read.setObject(1, runId);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("created_at", java.time.OffsetDateTime.class)
                                .toInstant())
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the run's birth", failure);
        }
    }

    @Override
    public Optional<RunRow> lockRun(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RUN_COLUMNS
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, runId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(runRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not lock the run", failure);
        }
    }

    @Override
    public boolean requeueRun(
            Connection unitOfWork,
            UUID runId,
            Actor actor,
            String reason,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.reconciliation_batch SET status = 'IN_PROGRESS',"
                                + " failures = 0, status_changed_at = ?"
                                + " WHERE id = ? AND status = 'BLOCKED'")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, runId);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not requeue the run", failure);
        }
        try (PreparedStatement event =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.reconciliation_batch_event (run_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, 'BLOCKED', 'IN_PROGRESS', ?, ?, ?, ?, ?)")) {
            event.setObject(1, runId);
            event.setString(2, actor.id());
            event.setString(3, actor.type().name());
            event.setString(4, reason);
            event.setTimestamp(5, Timestamp.from(at));
            event.setString(6, correlation.value());
            event.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the requeue's history", failure);
        }
        return true;
    }

    @Override
    public List<RunRow> runs(Connection unitOfWork, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RUN_COLUMNS
                                + " FROM reconciliation.reconciliation_batch"
                                + " ORDER BY created_at DESC LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<RunRow> runs = new ArrayList<>();
                while (rows.next()) {
                    runs.add(runRow(rows));
                }
                return List.copyOf(runs);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not list the runs", failure);
        }
    }

    @Override
    public Map<DecisionOutcome, Long> outcomeCounts(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT outcome, count(*) AS n FROM reconciliation.match_decision"
                                + " WHERE run_id = ? GROUP BY outcome")) {
            read.setObject(1, runId);
            try (ResultSet rows = read.executeQuery()) {
                Map<DecisionOutcome, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(
                            DecisionOutcome.valueOf(rows.getString("outcome")),
                            rows.getLong("n"));
                }
                return Map.copyOf(counts);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not count the run's outcomes", failure);
        }
    }

    @Override
    public Map<DecisionOutcome, Long> firstDecisionCounts(Connection unitOfWork, UUID runId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT outcome, count(*) AS n FROM reconciliation.match_decision"
                                + " WHERE run_id = ? AND origin = 'RUN' GROUP BY outcome")) {
            read.setObject(1, runId);
            try (ResultSet rows = read.executeQuery()) {
                Map<DecisionOutcome, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(
                            DecisionOutcome.valueOf(rows.getString("outcome")),
                            rows.getLong("n"));
                }
                return Map.copyOf(counts);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not count the run's outcomes", failure);
        }
    }
}
