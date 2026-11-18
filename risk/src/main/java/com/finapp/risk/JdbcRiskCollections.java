package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RiskCollections} in explicit SQL (`P17-TSK-020`). The assessment window walks {@code V022}'s
 * {@code (assessed_at, id)} index backwards from the cursor; a party's assessments ride {@code V007}'s
 * {@code (party_id, assessed_at)}, the id ordering only the rows that share an instant. The other filters narrow what the
 * window's range already bounds. The two administrative lists are small tables - a few versions an operation or a list a year
 * - read whole in order.
 */
public final class JdbcRiskCollections implements RiskCollections {

    static final String ASSESSMENT_COLUMNS = "SELECT id, assessed_at, operation, decision, score, policy_version_id, policy_version,"
            + " party_id FROM risk.risk_assessment";

    @Override
    public List<AssessmentHead> assessments(Connection unitOfWork, AssessmentFilter filter, UUID withheldParty,
            Optional<Cursor> after, int limit) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(withheldParty, "withheldParty");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder(ASSESSMENT_COLUMNS)
                .append(" WHERE assessed_at >= ? AND assessed_at < ? AND party_id <> ?");
        List<Object> arguments = new ArrayList<>(List.of(
                Timestamp.from(filter.from()), Timestamp.from(filter.to()), withheldParty));
        filter.party().ifPresent(party -> {
            sql.append(" AND party_id = ?");
            arguments.add(party);
        });
        filter.operation().ifPresent(operation -> {
            sql.append(" AND operation = ?");
            arguments.add(operation.name());
        });
        filter.decision().ifPresent(decision -> {
            sql.append(" AND decision = ?");
            arguments.add(decision.name());
        });
        keyset(sql, arguments, "assessed_at", after);
        sql.append(" ORDER BY assessed_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "searching the sealed assessments", rows -> new AssessmentHead(
                RiskAssessmentId.of(rows.getObject("id", UUID.class)),
                rows.getTimestamp("assessed_at").toInstant(),
                RiskOperation.valueOf(rows.getString("operation")),
                RiskDecision.valueOf(rows.getString("decision")),
                rows.getInt("score"),
                Optional.ofNullable(rows.getObject("policy_version_id", UUID.class)).map(RiskPolicyVersionId::of),
                Optional.ofNullable((Integer) rows.getObject("policy_version")),
                rows.getObject("party_id", UUID.class)));
    }

    @Override
    public List<RiskPolicyStore.VersionRow> policyVersions(Connection unitOfWork, Optional<RiskOperation> operation,
            Optional<RiskPolicyVersionStatus> status, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT " + JdbcRiskPolicyStore.ROW_COLUMNS
                + " FROM risk.risk_policy_version WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        operation.ifPresent(value -> {
            sql.append(" AND operation = ?");
            arguments.add(value.name());
        });
        status.ifPresent(value -> {
            sql.append(" AND status = ?");
            arguments.add(value.name());
        });
        keyset(sql, arguments, "proposed_at", after);
        sql.append(" ORDER BY proposed_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the policy versions", JdbcRiskPolicyStore::versionRow);
    }

    @Override
    public List<ScreeningListVersion> screeningListVersions(Connection unitOfWork, Optional<ScreeningList> list,
            Optional<Cursor> after, int limit) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(after, "after");
        StringBuilder sql = new StringBuilder("SELECT " + JdbcScreeningListStore.COLUMNS
                + " FROM risk.screening_list_version WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        list.ifPresent(value -> {
            sql.append(" AND list = ?");
            arguments.add(value.name());
        });
        keyset(sql, arguments, "recorded_at", after);
        sql.append(" ORDER BY recorded_at DESC, id DESC LIMIT ?");
        arguments.add(bounded(limit));
        return read(unitOfWork, sql.toString(), arguments, "listing the screening list versions", JdbcScreeningListStore::read);
    }

    /** Strictly before the cursor in the collection's order: the row comparison the index walks. */
    private static void keyset(StringBuilder sql, List<Object> arguments, String stamp, Optional<Cursor> after) {
        after.ifPresent(cursor -> {
            sql.append(" AND (").append(stamp).append(", id) < (?, ?)");
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
            try (ResultSet rows = select.executeQuery()) {
                List<T> read = new ArrayList<>();
                while (rows.next()) {
                    read.add(row.of(rows));
                }
                return read;
            }
        } catch (SQLException failure) {
            throw new RiskStorageException("risk storage failed " + what + ": SQLSTATE " + failure.getSQLState());
        }
    }
}
