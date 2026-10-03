package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link ReportReadings} over JDBC (ADR-0033: explicit SQL, no mapper); no locks, no
 * {@code SUM}. Counts are {@code count(*)}; every amount is read as its stored triple and built
 * into {@code Money} per row. The streamed reads set a fetch size, so inside the caller's
 * transaction the driver walks a cursor instead of materialising every row.
 */
public final class JdbcReportReadings implements ReportReadings {

    /** Rows per round trip when streaming — a cursor, never the whole open set in memory. */
    private static final int FETCH_SIZE = 500;

    /**
     * The worst open break naming the item or owning its suspense item: CRITICAL first, then the
     * oldest raise, then the id — one total order, so the same break answers every time.
     */
    private static final String WORST_OPEN_BREAK_OF_ITEM =
            "LEFT JOIN LATERAL ("
                    + " SELECT b.id, b.type, b.severity FROM reconciliation.break b"
                    + " WHERE b.status <> 'RESOLVED'"
                    + " AND (b.external_item_id = i.id OR b.id IN ("
                    + " SELECT s.break_id FROM reconciliation.suspense_item s"
                    + " WHERE s.external_item_id = i.id))"
                    + " ORDER BY CASE b.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1"
                    + " WHEN 'MEDIUM' THEN 2 ELSE 3 END, b.raised_at, b.id"
                    + " LIMIT 1) ob ON true";

    private static final String BREAK_COLUMNS =
            "SELECT id, source_id, type, severity, status, value_at_issue_minor, currency,"
                    + " scale, raised_at, resolved_at FROM reconciliation.break";

    @Override
    public void eachOpenSuspenseItem(Connection unitOfWork, Consumer<OpenSuspenseLine> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT s.id, s.origin, s.side, s.amount_minor, s.released_minor,"
                                + " s.currency, s.scale, s.opened_on, s.break_id,"
                                + " b.type AS break_type, b.severity AS break_severity"
                                + " FROM reconciliation.suspense_item s"
                                // LEFT: an item whose owner row were missing must still be
                                // counted - never silently discarded (INV-REC-02).
                                + " LEFT JOIN reconciliation.break b ON b.id = s.break_id"
                                + " WHERE s.status <> 'RELEASED'"
                                + " ORDER BY s.opened_on, s.id")) {
            read.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    CurrencyCode currency = currencyOf(rows);
                    int scale = rows.getInt("scale");
                    Money amount =
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale);
                    Money released =
                            Money.ofPersisted(rows.getLong("released_minor"), currency, scale);
                    UUID breakId = rows.getObject("break_id", UUID.class);
                    visitor.accept(
                            new OpenSuspenseLine(
                                    rows.getObject("id", UUID.class),
                                    SuspenseOrigin.valueOf(rows.getString("origin")),
                                    SuspenseSide.valueOf(rows.getString("side")),
                                    amount.minus(released),
                                    rows.getObject("opened_on", LocalDate.class),
                                    breakId,
                                    openBreak(rows, breakId)));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the open suspense items for the report", failure);
        }
    }

    @Override
    public void eachUnexplainedItem(
            Connection unitOfWork, Consumer<UnexplainedItemLine> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT i.id, i.source_id, i.line_type, i.direction, i.status,"
                                + " i.amount_minor, i.allocated_minor, i.parked_minor,"
                                + " i.offset_minor, i.currency, i.scale, i.business_date,"
                                + " i.grace_until, ob.id AS break_id, ob.type AS break_type,"
                                + " ob.severity AS break_severity"
                                + " FROM reconciliation.external_item i "
                                + WORST_OPEN_BREAK_OF_ITEM
                                + " WHERE i.status IN ('UNMATCHED', 'PARKED')"
                                + " ORDER BY i.business_date, i.created_at, i.id")) {
            read.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    CurrencyCode currency = currencyOf(rows);
                    int scale = rows.getInt("scale");
                    ItemStatus status = ItemStatus.valueOf(rows.getString("status"));
                    Money parked =
                            Money.ofPersisted(rows.getLong("parked_minor"), currency, scale);
                    Money value =
                            status == ItemStatus.PARKED
                                    ? parked
                                    : Money.ofPersisted(
                                                    rows.getLong("amount_minor"), currency, scale)
                                            .minus(minor(rows, "allocated_minor", currency, scale))
                                            .minus(parked)
                                            .minus(minor(rows, "offset_minor", currency, scale));
                    visitor.accept(
                            new UnexplainedItemLine(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("source_id", UUID.class),
                                    ExternalLineType.valueOf(rows.getString("line_type")),
                                    ExpectationDirection.valueOf(rows.getString("direction")),
                                    status,
                                    value,
                                    rows.getObject("business_date", LocalDate.class),
                                    instantOf(rows, "grace_until"),
                                    openBreak(rows, rows.getObject("break_id", UUID.class))));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the unexplained items for the report", failure);
        }
    }

    @Override
    public void eachOpenExpectation(
            Connection unitOfWork, Consumer<OpenExpectationLine> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, source_id, kind, direction, amount_minor, allocated_minor,"
                                + " resolved_minor, currency, scale, posting_date, expected_by,"
                                + " overdue_since FROM reconciliation.expectation"
                                + " WHERE status IN ('OPEN', 'PARTIALLY_SETTLED')"
                                + " ORDER BY posting_date, id")) {
            read.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    CurrencyCode currency = currencyOf(rows);
                    int scale = rows.getInt("scale");
                    visitor.accept(
                            new OpenExpectationLine(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("source_id", UUID.class),
                                    ExpectationKind.valueOf(rows.getString("kind")),
                                    ExpectationDirection.valueOf(rows.getString("direction")),
                                    minor(rows, "amount_minor", currency, scale)
                                            .minus(minor(rows, "allocated_minor", currency, scale))
                                            .minus(minor(rows, "resolved_minor", currency, scale)),
                                    rows.getObject("posting_date", LocalDate.class),
                                    rows.getObject("expected_by", LocalDate.class),
                                    instantOf(rows, "overdue_since")));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the open expectations for the report", failure);
        }
    }

    @Override
    public void eachOpenBreak(Connection unitOfWork, Consumer<BreakLine> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        BREAK_COLUMNS + " WHERE status <> 'RESOLVED' ORDER BY raised_at, id")) {
            streamBreaks(read, visitor);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the open breaks for the report", failure);
        }
    }

    @Override
    public void eachBreakAround(
            Connection unitOfWork, Instant from, Instant until, Consumer<BreakLine> visitor) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(until, "until must not be null");
        Objects.requireNonNull(visitor, "visitor must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        BREAK_COLUMNS
                                + " WHERE (raised_at >= ? AND raised_at < ?)"
                                + " OR (resolved_at >= ? AND resolved_at < ?)"
                                + " OR (raised_at < ? AND (resolved_at IS NULL"
                                + " OR resolved_at >= ?))"
                                + " ORDER BY raised_at, id")) {
            OffsetDateTime start = OffsetDateTime.ofInstant(from, ZoneOffset.UTC);
            OffsetDateTime end = OffsetDateTime.ofInstant(until, ZoneOffset.UTC);
            read.setObject(1, start);
            read.setObject(2, end);
            read.setObject(3, start);
            read.setObject(4, end);
            read.setObject(5, end);
            read.setObject(6, end);
            streamBreaks(read, visitor);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the period's breaks for the report", failure);
        }
    }

    @Override
    public void eachItemOfBusinessDate(
            Connection unitOfWork, LocalDate businessDate, Consumer<DayItemLine> visitor) {
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(visitor, "visitor must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT i.source_id, i.status, i.amount_minor, i.allocated_minor,"
                                + " i.parked_minor, i.offset_minor, i.currency, i.scale"
                                + " FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r"
                                + " ON r.id = i.run_id"
                                + " WHERE r.business_date = ?"
                                + " ORDER BY i.id")) {
            read.setObject(1, businessDate);
            read.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    CurrencyCode currency = currencyOf(rows);
                    int scale = rows.getInt("scale");
                    visitor.accept(
                            new DayItemLine(
                                    rows.getObject("source_id", UUID.class),
                                    ItemStatus.valueOf(rows.getString("status")),
                                    minor(rows, "amount_minor", currency, scale),
                                    minor(rows, "allocated_minor", currency, scale),
                                    minor(rows, "parked_minor", currency, scale),
                                    minor(rows, "offset_minor", currency, scale)));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the business date's items for the report", failure);
        }
    }

    @Override
    public Map<UUID, Long> acceptedBatchesOfBusinessDate(
            Connection unitOfWork, LocalDate businessDate) {
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT source_id, count(*) AS n"
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE kind = 'BATCH' AND business_date = ?"
                                + " GROUP BY source_id")) {
            read.setObject(1, businessDate);
            try (ResultSet rows = read.executeQuery()) {
                Map<UUID, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(rows.getObject("source_id", UUID.class), rows.getLong("n"));
                }
                return Map.copyOf(counts);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not count the business date's accepted batches", failure);
        }
    }

    @Override
    public List<ApprovedResolutionLine> resolutionsApprovedBetween(
            Connection unitOfWork, Instant from, Instant until) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(until, "until must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT coalesce(b.source_id, r.source_id) AS source_id, res.kind,"
                                + " res.proposed_amount_minor, res.currency, res.scale,"
                                + " res.journal_entry_id IS NOT NULL AS posted"
                                + " FROM reconciliation.resolution res"
                                + " LEFT JOIN reconciliation.break b ON b.id = res.break_id"
                                // A batch-subject repudiation (P8-TSK-023) has no break: its
                                // source is its batch's own run's, by the stored identifier.
                                + " LEFT JOIN reconciliation.reconciliation_batch r"
                                + " ON r.batch_id = res.settlement_batch_id"
                                + " WHERE res.status = 'APPROVED'"
                                + " AND res.decided_at >= ? AND res.decided_at < ?"
                                + " ORDER BY res.decided_at, res.id")) {
            read.setObject(1, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
            read.setObject(2, OffsetDateTime.ofInstant(until, ZoneOffset.UTC));
            try (ResultSet rows = read.executeQuery()) {
                List<ApprovedResolutionLine> approved = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency = currencyOf(rows);
                    approved.add(
                            new ApprovedResolutionLine(
                                    Optional.ofNullable(rows.getObject("source_id", UUID.class)),
                                    ResolutionKind.valueOf(rows.getString("kind")),
                                    minor(rows, "proposed_amount_minor", currency,
                                            rows.getInt("scale")),
                                    rows.getBoolean("posted")));
                }
                return List.copyOf(approved);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the resolutions approved in the period", failure);
        }
    }

    @Override
    public List<RepudiationPosting> repudiationsApprovedBetween(
            Connection unitOfWork, Instant from, Instant until) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(until, "until must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, settlement_batch_id, journal_entry_id"
                                + " FROM reconciliation.resolution"
                                + " WHERE kind = 'REPUDIATE_BATCH' AND status = 'APPROVED'"
                                + " AND journal_entry_id IS NOT NULL"
                                + " AND decided_at >= ? AND decided_at < ?"
                                + " ORDER BY decided_at, id")) {
            read.setObject(1, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
            read.setObject(2, OffsetDateTime.ofInstant(until, ZoneOffset.UTC));
            try (ResultSet rows = read.executeQuery()) {
                List<RepudiationPosting> postings = new ArrayList<>();
                while (rows.next()) {
                    postings.add(
                            new RepudiationPosting(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("settlement_batch_id", UUID.class),
                                    rows.getObject("journal_entry_id", UUID.class)));
                }
                return List.copyOf(postings);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the repudiations approved in the period", failure);
        }
    }

    @Override
    public List<ExpectedFeeLine> expectedFeesOf(
            Connection unitOfWork, Collection<UUID> settlementBatchIds) {
        Objects.requireNonNull(settlementBatchIds, "settlementBatchIds must not be null");
        if (settlementBatchIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // The item's LATEST decision recording an expected fee: a reprocess or
                        // a manual decision supersedes, the history stays (INV-REC-04).
                        "SELECT DISTINCT ON (d.external_item_id) r.batch_id, i.direction,"
                                + " d.fee_expected_minor, i.currency, i.scale"
                                + " FROM reconciliation.match_decision d"
                                + " JOIN reconciliation.external_item i"
                                + " ON i.id = d.external_item_id"
                                + " JOIN reconciliation.reconciliation_batch r"
                                + " ON r.id = i.run_id"
                                + " WHERE r.batch_id = ANY (?)"
                                + " AND d.outcome = 'CHECKED'"
                                + " AND d.fee_expected_minor IS NOT NULL"
                                + " ORDER BY d.external_item_id, d.decided_at DESC, d.id DESC")) {
            read.setArray(
                    1, unitOfWork.createArrayOf("uuid", settlementBatchIds.toArray()));
            try (ResultSet rows = read.executeQuery()) {
                List<ExpectedFeeLine> fees = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency = currencyOf(rows);
                    fees.add(
                            new ExpectedFeeLine(
                                    rows.getObject("batch_id", UUID.class),
                                    ExpectationDirection.valueOf(rows.getString("direction")),
                                    minor(rows, "fee_expected_minor", currency,
                                            rows.getInt("scale"))));
                }
                return List.copyOf(fees);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expected fees of the batches", failure);
        }
    }

    // ----------------------------------------------------------------- row mapping

    private static void streamBreaks(PreparedStatement read, Consumer<BreakLine> visitor)
            throws SQLException {
        read.setFetchSize(FETCH_SIZE);
        try (ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                CurrencyCode currency = currencyOf(rows);
                visitor.accept(
                        new BreakLine(
                                rows.getObject("id", UUID.class),
                                rows.getObject("source_id", UUID.class),
                                BreakType.valueOf(rows.getString("type")),
                                Severity.valueOf(rows.getString("severity")),
                                BreakStatus.valueOf(rows.getString("status")),
                                minor(rows, "value_at_issue_minor", currency,
                                        rows.getInt("scale")),
                                rows.getObject("raised_at", OffsetDateTime.class).toInstant(),
                                instantOf(rows, "resolved_at")));
            }
        }
    }

    private static Optional<OpenBreak> openBreak(ResultSet rows, UUID breakId)
            throws SQLException {
        String type = rows.getString("break_type");
        return type == null
                ? Optional.empty()
                : Optional.of(
                        new OpenBreak(
                                breakId,
                                BreakType.valueOf(type),
                                Severity.valueOf(rows.getString("break_severity"))));
    }

    private static CurrencyCode currencyOf(ResultSet rows) throws SQLException {
        return CurrencyCode.of(rows.getString("currency").trim());
    }

    private static Money minor(ResultSet rows, String column, CurrencyCode currency, int scale)
            throws SQLException {
        return Money.ofPersisted(rows.getLong(column), currency, scale);
    }

    private static Optional<Instant> instantOf(ResultSet rows, String column)
            throws SQLException {
        return Optional.ofNullable(rows.getObject(column, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }
}
