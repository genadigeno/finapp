package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditCollections} in explicit SQL (`P17-TSK-035`). The decision requests walk {@code V026}'s
 * {@code (submitted_at, id)} index backwards from the cursor, the filters narrowing the range; the decision rides
 * {@code V011}'s {@code UNIQUE (decision_request_id)}. The two version lists are small tables - a few versions a product or
 * family a year - read whole in order.
 */
public final class JdbcCreditCollections implements CreditCollections {

    private static final String REQUEST_COLUMNS = "SELECT r.id, r.party_id, r.product, r.origin, r.status, r.closure_reason,"
            + " r.currency, r.requested_minor, r.term_months, r.submitted_at, r.expires_at, r.pinned_policy_version_id,"
            + " r.pinned_model_version_id, d.id AS decision_id, d.outcome"
            + " FROM credit.decision_request r LEFT JOIN credit.credit_decision d ON d.decision_request_id = r.id";

    private static final String VERSION_COLUMNS = " version, status, proposed_by, proposed_at, proposal_reason, decided_at,"
            + " decision_reason, effective_from, effective_to";

    @Override
    public List<DecisionRequestHead> decisionRequests(Connection unitOfWork, Optional<DecisionRequestStatus> status,
            Optional<CreditProduct> product, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder(REQUEST_COLUMNS).append(" WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        status.ifPresent(value -> {
            sql.append(" AND r.status = ?");
            arguments.add(value.name());
        });
        product.ifPresent(value -> {
            sql.append(" AND r.product = ?");
            arguments.add(value.name());
        });
        keyset(sql, arguments, "r.submitted_at", "r.id", after);
        sql.append(" ORDER BY r.submitted_at DESC, r.id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the decision requests", rows -> new DecisionRequestHead(
                DecisionRequestId.of(rows.getObject("id", UUID.class)),
                rows.getObject("party_id", UUID.class),
                CreditProduct.valueOf(rows.getString("product")),
                RequestOrigin.valueOf(rows.getString("origin")),
                DecisionRequestStatus.valueOf(rows.getString("status")),
                Optional.ofNullable(rows.getString("closure_reason")).map(ClosureReason::valueOf),
                Money.ofMinorUnits(rows.getLong("requested_minor"), CurrencyCode.of(rows.getString("currency").strip())),
                Optional.ofNullable((Integer) rows.getObject("term_months")),
                rows.getTimestamp("submitted_at").toInstant(),
                rows.getTimestamp("expires_at").toInstant(),
                Optional.ofNullable(rows.getObject("pinned_policy_version_id", UUID.class)).map(CreditPolicyVersionId::of),
                Optional.ofNullable(rows.getObject("pinned_model_version_id", UUID.class)).map(ScorecardModelVersionId::of),
                Optional.ofNullable(rows.getObject("decision_id", UUID.class)).map(CreditDecisionId::of),
                Optional.ofNullable(rows.getString("outcome")).map(DecisionOutcome::valueOf)));
    }

    @Override
    public List<VersionHead> policyVersions(Connection unitOfWork, Optional<CreditProduct> product,
            Optional<CreditPolicyStatus> status, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT id, product AS subject," + VERSION_COLUMNS
                + " FROM credit.credit_policy_version WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        product.ifPresent(value -> {
            sql.append(" AND product = ?");
            arguments.add(value.name());
        });
        status.ifPresent(value -> {
            sql.append(" AND status = ?");
            arguments.add(value.name());
        });
        keyset(sql, arguments, "proposed_at", "id", after);
        sql.append(" ORDER BY proposed_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the credit policy versions", JdbcCreditCollections::head);
    }

    @Override
    public Optional<VersionHead> policyVersion(Connection unitOfWork, CreditPolicyVersionId id) {
        Objects.requireNonNull(id, "id");
        return read(unitOfWork, "SELECT id, product AS subject," + VERSION_COLUMNS
                        + " FROM credit.credit_policy_version WHERE id = ?", List.of(id.value()),
                "reading a credit policy version", JdbcCreditCollections::head).stream().findFirst();
    }

    @Override
    public List<VersionHead> scorecardVersions(Connection unitOfWork, Optional<ScorecardStatus> status, Optional<Cursor> after,
            int limit) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT id, family AS subject," + VERSION_COLUMNS
                + " FROM credit.scorecard_model_version WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        status.ifPresent(value -> {
            sql.append(" AND status = ?");
            arguments.add(value.name());
        });
        keyset(sql, arguments, "proposed_at", "id", after);
        sql.append(" ORDER BY proposed_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the scorecard versions", JdbcCreditCollections::head);
    }

    @Override
    public Optional<VersionHead> scorecardVersion(Connection unitOfWork, ScorecardModelVersionId id) {
        Objects.requireNonNull(id, "id");
        return read(unitOfWork, "SELECT id, family AS subject," + VERSION_COLUMNS
                        + " FROM credit.scorecard_model_version WHERE id = ?", List.of(id.value()),
                "reading a scorecard version", JdbcCreditCollections::head).stream().findFirst();
    }

    @Override
    public List<VersionHead> pendingPolicyVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
        return pending(unitOfWork, "SELECT id, product AS subject," + VERSION_COLUMNS + " FROM credit.credit_policy_version",
                after, limit, "listing the credit policy versions awaiting a decision");
    }

    @Override
    public List<VersionHead> pendingScorecardVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
        return pending(unitOfWork, "SELECT id, family AS subject," + VERSION_COLUMNS + " FROM credit.scorecard_model_version",
                after, limit, "listing the scorecard versions awaiting a decision");
    }

    @Override
    public List<PendingSecondApproval> pendingSecondApprovals(Connection unitOfWork, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT id, first_decided_by, first_decided_at, first_reason, decision_request_id"
                + " FROM credit.underwriting_case WHERE status = 'AWAITING_SECOND'");
        List<Object> arguments = new ArrayList<>();
        after.ifPresent(cursor -> {
            sql.append(" AND (first_decided_at, id) > (?, ?)");
            arguments.add(Timestamp.from(cursor.at()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY first_decided_at ASC, id ASC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the review cases awaiting a second approval",
                rows -> new PendingSecondApproval(
                        rows.getObject("id", UUID.class),
                        rows.getString("first_decided_by"),
                        rows.getTimestamp("first_decided_at").toInstant(),
                        rows.getString("first_reason"),
                        rows.getObject("decision_request_id", UUID.class)));
    }

    /** The {@code PROPOSED} versions of {@code select}'s table, oldest first, strictly after the cursor (`P17-TSK-040`). */
    private static List<VersionHead> pending(Connection unitOfWork, String select, Optional<Cursor> after, int limit,
            String what) {
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder(select).append(" WHERE status = 'PROPOSED'");
        List<Object> arguments = new ArrayList<>();
        after.ifPresent(cursor -> {
            sql.append(" AND (proposed_at, id) > (?, ?)");
            arguments.add(Timestamp.from(cursor.at()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY proposed_at ASC, id ASC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, what, JdbcCreditCollections::head);
    }

    private static VersionHead head(ResultSet rows) throws SQLException {
        return new VersionHead(
                rows.getObject("id", UUID.class),
                rows.getString("subject"),
                rows.getInt("version"),
                rows.getString("status"),
                rows.getString("proposed_by"),
                rows.getTimestamp("proposed_at").toInstant(),
                rows.getString("proposal_reason"),
                instant(rows.getTimestamp("decided_at")),
                Optional.ofNullable(rows.getString("decision_reason")),
                instant(rows.getTimestamp("effective_from")),
                instant(rows.getTimestamp("effective_to")));
    }

    private static Optional<Instant> instant(Timestamp stamp) {
        return Optional.ofNullable(stamp).map(Timestamp::toInstant);
    }

    /** Strictly before the cursor in the collection's descending order: the row comparison the index walks. */
    private static void keyset(StringBuilder sql, List<Object> arguments, String stamp, String id, Optional<Cursor> after) {
        after.ifPresent(cursor -> {
            sql.append(" AND (").append(stamp).append(", ").append(id).append(") < (?, ?)");
            arguments.add(Timestamp.from(cursor.at()));
            arguments.add(cursor.id());
        });
    }

    private static int bounded(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one row, not " + limit);
        }
        return limit;
    }

    @FunctionalInterface
    private interface Row<T> {
        T of(ResultSet rows) throws SQLException;
    }

    private static <T> List<T> read(Connection unitOfWork, String sql, List<Object> arguments, String what, Row<T> row) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < arguments.size(); i++) {
                select.setObject(i + 1, arguments.get(i));
            }
            List<T> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(row.of(rows));
                }
            }
            return found;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe(what, failure));
        }
    }
}
