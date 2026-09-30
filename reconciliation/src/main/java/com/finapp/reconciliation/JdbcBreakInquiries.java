package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BreakInquiries} over JDBC (ADR-0033). Lock-free reads of reconciliation's own
 * schema; every statement reaches its row by a STORED identifier — a subject column, a
 * foreign key, an id a previous step read — and none joins by a timestamp.
 */
public final class JdbcBreakInquiries implements BreakInquiries {

    @Override
    public List<BreakCaseStore.BreakRow> breaks(
            Connection unitOfWork, BreakFilter filter, int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT " + JdbcBreakCaseStore.BREAK_COLUMNS
                                + " FROM reconciliation.break WHERE TRUE");
        List<Object> args = new ArrayList<>();
        filter.type().ifPresent(type -> {
            sql.append(" AND type = ?");
            args.add(type.name());
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND status = ?");
            args.add(status.name());
        });
        filter.severity().ifPresent(severity -> {
            sql.append(" AND severity = ?");
            args.add(severity.name());
        });
        filter.sourceId().ifPresent(source -> {
            sql.append(" AND source_id = ?");
            args.add(source);
        });
        filter.assignee().ifPresent(assignee -> {
            sql.append(" AND assignee = ?");
            args.add(assignee);
        });
        filter.agedOverDays().ifPresent(days -> {
            // Judged on the DATABASE clock against the stored raised_at (INV-SET-02's rule).
            sql.append(" AND raised_at < now() - make_interval(days => ?)");
            args.add(days);
        });
        sql.append(" ORDER BY raised_at DESC, id DESC LIMIT ?");
        args.add(limit);
        try (PreparedStatement read = unitOfWork.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                read.setObject(i + 1, args.get(i));
            }
            try (ResultSet rows = read.executeQuery()) {
                List<BreakCaseStore.BreakRow> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(JdbcBreakCaseStore.breakRow(rows));
                }
                return List.copyOf(found);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not list the breaks", failure);
        }
    }

    @Override
    public Optional<BreakCaseStore.BreakRow> breakById(Connection unitOfWork, UUID breakId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + JdbcBreakCaseStore.BREAK_COLUMNS
                                + " FROM reconciliation.break WHERE id = ?")) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(JdbcBreakCaseStore.breakRow(row))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the break", failure);
        }
    }

    @Override
    public List<EventRow> history(Connection unitOfWork, UUID breakId) {
        return list(
                unitOfWork,
                "SELECT seq, event_type, actor, actor_type, reason, detail, occurred_at"
                        + " FROM reconciliation.break_event WHERE break_id = ? ORDER BY seq",
                breakId,
                row ->
                        new EventRow(
                                row.getLong("seq"),
                                row.getString("event_type"),
                                row.getString("actor"),
                                row.getString("actor_type"),
                                Optional.ofNullable(row.getString("reason")),
                                Optional.ofNullable(row.getString("detail")),
                                instant(row, "occurred_at")));
    }

    @Override
    public List<NoteRow> notes(Connection unitOfWork, UUID breakId) {
        return list(
                unitOfWork,
                "SELECT id, body, author, author_type, added_at FROM reconciliation.break_note"
                        + " WHERE break_id = ? ORDER BY added_at, id",
                breakId,
                row ->
                        new NoteRow(
                                row.getObject("id", UUID.class),
                                row.getString("body"),
                                row.getString("author"),
                                row.getString("author_type"),
                                instant(row, "added_at")));
    }

    @Override
    public List<LinkRow> links(Connection unitOfWork, UUID breakId) {
        return list(
                unitOfWork,
                "SELECT id, target_kind, target_ref, added_by, added_by_type, added_at"
                        + " FROM reconciliation.break_evidence_link WHERE break_id = ?"
                        + " ORDER BY added_at, id",
                breakId,
                row ->
                        new LinkRow(
                                row.getObject("id", UUID.class),
                                row.getString("target_kind"),
                                row.getString("target_ref"),
                                row.getString("added_by"),
                                row.getString("added_by_type"),
                                instant(row, "added_at")));
    }

    @Override
    public List<ResolutionRow> resolutions(Connection unitOfWork, UUID breakId) {
        return list(
                unitOfWork,
                "SELECT id, kind, status, reason_code, narrative, proposed_amount_minor,"
                        + " currency, scale, decision_id, park_id, adjustment_proposal_id,"
                        + " journal_entry_id, proposed_by, proposed_by_type, proposed_at,"
                        + " decided_by, decided_at FROM reconciliation.resolution"
                        + " WHERE break_id = ? ORDER BY proposed_at, id",
                breakId,
                row ->
                        new ResolutionRow(
                                row.getObject("id", UUID.class),
                                row.getString("kind"),
                                row.getString("status"),
                                row.getString("reason_code"),
                                row.getString("narrative"),
                                row.getLong("proposed_amount_minor"),
                                row.getString("currency").trim(),
                                row.getInt("scale"),
                                Optional.ofNullable(row.getObject("decision_id", UUID.class)),
                                Optional.ofNullable(row.getObject("park_id", UUID.class)),
                                Optional.ofNullable(
                                        row.getObject("adjustment_proposal_id", UUID.class)),
                                Optional.ofNullable(
                                        row.getObject("journal_entry_id", UUID.class)),
                                row.getString("proposed_by"),
                                row.getString("proposed_by_type"),
                                instant(row, "proposed_at"),
                                Optional.ofNullable(row.getString("decided_by")),
                                Optional.ofNullable(
                                                row.getObject(
                                                        "decided_at", OffsetDateTime.class))
                                        .map(OffsetDateTime::toInstant)));
    }

    @Override
    public List<BreakCaseStore.BreakRow> predecessors(Connection unitOfWork, UUID breakId) {
        // The chain by its stored pointer, nearest first; depth-bounded so a planted cycle
        // (impossible under the frozen column, but cheap to refuse) cannot run away.
        return list(
                unitOfWork,
                "WITH RECURSIVE chain(id, depth) AS ("
                        + " SELECT follows_break_id, 1 FROM reconciliation.break WHERE id = ?"
                        + " AND follows_break_id IS NOT NULL"
                        + " UNION ALL SELECT b.follows_break_id, c.depth + 1"
                        + " FROM reconciliation.break b JOIN chain c ON b.id = c.id"
                        + " WHERE b.follows_break_id IS NOT NULL AND c.depth < 50)"
                        + " SELECT " + prefixed("b.")
                        + " FROM chain c JOIN reconciliation.break b ON b.id = c.id"
                        + " ORDER BY c.depth",
                breakId,
                JdbcBreakCaseStore::breakRow);
    }

    // ------------------------------------------------------------------ the chain

    @Override
    public Optional<ItemLink> item(Connection unitOfWork, UUID itemId) {
        return one(
                unitOfWork,
                "SELECT id, run_id, settlement_line_id FROM reconciliation.external_item"
                        + " WHERE id = ?",
                itemId,
                row ->
                        new ItemLink(
                                row.getObject("id", UUID.class),
                                row.getObject("run_id", UUID.class),
                                row.getObject("settlement_line_id", UUID.class)));
    }

    @Override
    public Optional<UUID> batchOfRun(Connection unitOfWork, UUID runId) {
        return one(
                        unitOfWork,
                        "SELECT batch_id FROM reconciliation.reconciliation_batch WHERE id = ?",
                        runId,
                        row -> Optional.ofNullable(row.getObject("batch_id", UUID.class)))
                .flatMap(batch -> batch);
    }

    @Override
    public List<UUID> decisionsOfItem(Connection unitOfWork, UUID itemId) {
        return list(
                unitOfWork,
                "SELECT id FROM reconciliation.match_decision WHERE external_item_id = ?"
                        + " ORDER BY id",
                itemId,
                row -> row.getObject("id", UUID.class));
    }

    @Override
    public Optional<UUID> itemOfDecision(Connection unitOfWork, UUID decisionId) {
        return one(
                unitOfWork,
                "SELECT external_item_id FROM reconciliation.match_decision WHERE id = ?",
                decisionId,
                row -> row.getObject("external_item_id", UUID.class));
    }

    private static final String ALLOCATION_COLUMNS =
            "SELECT id, decision_id, external_item_id, expectation_id"
                    + " FROM reconciliation.allocation";

    private static AllocationLink allocationLink(ResultSet row) throws SQLException {
        return new AllocationLink(
                row.getObject("id", UUID.class),
                row.getObject("decision_id", UUID.class),
                row.getObject("external_item_id", UUID.class),
                row.getObject("expectation_id", UUID.class));
    }

    @Override
    public List<AllocationLink> allocationsOfItem(Connection unitOfWork, UUID itemId) {
        return list(
                unitOfWork,
                ALLOCATION_COLUMNS + " WHERE external_item_id = ? ORDER BY id",
                itemId,
                JdbcBreakInquiries::allocationLink);
    }

    @Override
    public List<AllocationLink> allocationsOfDecision(Connection unitOfWork, UUID decisionId) {
        return list(
                unitOfWork,
                ALLOCATION_COLUMNS + " WHERE decision_id = ? ORDER BY id",
                decisionId,
                JdbcBreakInquiries::allocationLink);
    }

    @Override
    public List<AllocationLink> allocationsOfExpectation(
            Connection unitOfWork, UUID expectationId, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        ALLOCATION_COLUMNS + " WHERE expectation_id = ? ORDER BY id LIMIT ?")) {
            read.setObject(1, expectationId);
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<AllocationLink> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(allocationLink(rows));
                }
                return List.copyOf(found);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation's allocations", failure);
        }
    }

    private static final String SUSPENSE_COLUMNS =
            "SELECT id, break_id, external_item_id, park_id, entry_id, origin, origin_ref"
                    + " FROM reconciliation.suspense_item";

    private static SuspenseLink suspenseLink(ResultSet row) throws SQLException {
        return new SuspenseLink(
                row.getObject("id", UUID.class),
                row.getObject("break_id", UUID.class),
                Optional.ofNullable(row.getObject("external_item_id", UUID.class)),
                Optional.ofNullable(row.getObject("park_id", UUID.class)),
                row.getObject("entry_id", UUID.class),
                row.getString("origin"),
                row.getString("origin_ref"));
    }

    @Override
    public List<SuspenseLink> suspenseOfItem(Connection unitOfWork, UUID itemId) {
        return list(
                unitOfWork,
                SUSPENSE_COLUMNS + " WHERE external_item_id = ? ORDER BY id",
                itemId,
                JdbcBreakInquiries::suspenseLink);
    }

    @Override
    public Optional<SuspenseLink> suspenseItem(Connection unitOfWork, UUID suspenseItemId) {
        return one(
                unitOfWork,
                SUSPENSE_COLUMNS + " WHERE id = ?",
                suspenseItemId,
                JdbcBreakInquiries::suspenseLink);
    }

    @Override
    public Optional<UUID> parkingExpectationOf(Connection unitOfWork, UUID entryId) {
        return one(
                unitOfWork,
                "SELECT id FROM reconciliation.expectation WHERE journal_entry_id = ?"
                        + " AND kind = 'UNMATCHED_CONFIRMATION'",
                entryId,
                row -> row.getObject("id", UUID.class));
    }

    @Override
    public List<ParkLink> releasesOf(Connection unitOfWork, UUID suspenseItemId) {
        return list(
                unitOfWork,
                "SELECT r.park_id, p.journal_entry_id FROM reconciliation.suspense_release r"
                        + " JOIN reconciliation.park p ON p.id = r.park_id"
                        + " WHERE r.item_id = ? ORDER BY r.seq",
                suspenseItemId,
                row ->
                        new ParkLink(
                                row.getObject("park_id", UUID.class),
                                row.getObject("journal_entry_id", UUID.class)));
    }

    @Override
    public Optional<UUID> entryOfPark(Connection unitOfWork, UUID parkId) {
        return one(
                unitOfWork,
                "SELECT journal_entry_id FROM reconciliation.park WHERE id = ?",
                parkId,
                row -> row.getObject("journal_entry_id", UUID.class));
    }

    @Override
    public Optional<ExpectationLink> expectation(Connection unitOfWork, UUID expectationId) {
        return one(
                unitOfWork,
                "SELECT id, kind, operation_ref, journal_entry_id"
                        + " FROM reconciliation.expectation WHERE id = ?",
                expectationId,
                row ->
                        new ExpectationLink(
                                row.getObject("id", UUID.class),
                                ExpectationKind.valueOf(row.getString("kind")),
                                row.getString("operation_ref"),
                                Optional.ofNullable(
                                        row.getObject("journal_entry_id", UUID.class))));
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    interface RowMapper<R> {
        R map(ResultSet row) throws SQLException;
    }

    private static String prefixed(String alias) {
        StringBuilder columns = new StringBuilder();
        for (String column : JdbcBreakCaseStore.BREAK_COLUMNS.split(",")) {
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append(alias).append(column.trim());
        }
        return columns.toString();
    }

    static <R> List<R> list(Connection unitOfWork, String sql, UUID id, RowMapper<R> mapper) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet rows = read.executeQuery()) {
                List<R> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(mapper.map(rows));
                }
                return List.copyOf(found);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the break's chain", failure);
        }
    }

    static <R> Optional<R> one(Connection unitOfWork, String sql, UUID id, RowMapper<R> mapper) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(mapper.map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the break's chain", failure);
        }
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }
}
