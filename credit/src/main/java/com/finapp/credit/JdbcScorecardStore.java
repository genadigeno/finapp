package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link ScorecardStore} over {@code credit V006} (`P10-TSK-011`). Stateless. */
public final class JdbcScorecardStore implements ScorecardStore {

    /** The scorecard family's proposal lock - credit's own (DISTRIBUTED_EXECUTION.md section 3). */
    static final int NAMESPACE = 10;

    private static final String ROW_COLUMNS = "id, family, version, status, proposed_by, decided_by";

    @Override
    public void lockFamily(Connection unitOfWork, ScorecardFamily family) {
        try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, family.name());
            lock.execute();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("locking a scorecard family", failure));
        }
    }

    @Override
    public boolean proposalPending(Connection unitOfWork, ScorecardFamily family) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM credit.scorecard_model_version WHERE family = ? AND status = 'PROPOSED')")) {
            select.setString(1, family.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a scorecard family's proposal", failure));
        }
    }

    @Override
    public int maxVersion(Connection unitOfWork, ScorecardFamily family) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT coalesce(max(version), 0) FROM credit.scorecard_model_version WHERE family = ?")) {
            select.setString(1, family.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("numbering a scorecard version", failure));
        }
    }

    @Override
    public void insertProposal(
            Connection unitOfWork,
            ScorecardModelVersionId id,
            ScorecardFamily family,
            int version,
            Scorecard scorecard,
            String proposedBy,
            String reason) {
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.scorecard_model_version (id, family, version, status, base_points, proposed_by,"
                            + " proposed_at, proposal_reason) VALUES (?, ?, ?, 'PROPOSED', ?, ?, transaction_timestamp(), ?)")) {
                insert.setObject(1, id.value());
                insert.setString(2, family.name());
                insert.setInt(3, version);
                insert.setInt(4, scorecard.basePoints());
                insert.setString(5, proposedBy);
                insert.setString(6, reason);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.scorecard_band (model_version_id, attribute_code, ordinal, kind, lower_bound,"
                            + " upper_bound, codes, points) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Scorecard.AttributeBands attribute : scorecard.attributes()) {
                    band(insert, id, attribute.code(), 0, "ABSENT", null, null, null, attribute.absentPoints());
                    int ordinal = 1;
                    for (Scorecard.Band band : attribute.bands()) {
                        switch (band) {
                            case Scorecard.Range range -> band(insert, id, attribute.code(), ordinal, "RANGE",
                                    range.lower(), range.upper(), null, range.points());
                            case Scorecard.Codes codes -> band(insert, id, attribute.code(), ordinal, "CODES", null, null,
                                    unitOfWork.createArrayOf("text", codes.sorted().toArray()), codes.points());
                        }
                        ordinal++;
                    }
                }
                insert.executeBatch();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("proposing a scorecard version", failure));
        }
    }

    private static void band(PreparedStatement insert, ScorecardModelVersionId id, CreditAttributeCode code, int ordinal,
            String kind, Long lower, Long upper, Array codes, int points) throws SQLException {
        insert.setObject(1, id.value());
        insert.setString(2, code.name());
        insert.setInt(3, ordinal);
        insert.setString(4, kind);
        if (lower == null) {
            insert.setNull(5, Types.BIGINT);
        } else {
            insert.setLong(5, lower);
        }
        if (upper == null) {
            insert.setNull(6, Types.BIGINT);
        } else {
            insert.setLong(6, upper);
        }
        if (codes == null) {
            insert.setNull(7, Types.ARRAY);
        } else {
            insert.setArray(7, codes);
        }
        insert.setInt(8, points);
        insert.addBatch();
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            UUID eventId,
            ScorecardModelVersionId id,
            Optional<ScorecardStatus> from,
            ScorecardStatus to,
            String actorId,
            String reason) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.scorecard_model_event (id, model_version_id, from_status, to_status, actor_id, reason,"
                        + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, transaction_timestamp())")) {
            insert.setObject(1, eventId);
            insert.setObject(2, id.value());
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, actorId);
            insert.setString(6, reason);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a scorecard version's history", failure));
        }
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, ScorecardModelVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.scorecard_model_version WHERE id = ? FOR UPDATE",
                id.value(), "locking a scorecard version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork, ScorecardFamily family) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.scorecard_model_version"
                + " WHERE family = ? AND status = 'ACTIVE' FOR UPDATE", family.name(), "locking the active scorecard");
    }

    @Override
    public boolean retire(Connection unitOfWork, ScorecardModelVersionId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE credit.scorecard_model_version SET status = 'RETIRED' WHERE id = ? AND status = 'ACTIVE'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("retiring a scorecard version", failure));
        }
    }

    @Override
    public Optional<Optional<Instant>> decide(
            Connection unitOfWork, ScorecardModelVersionId id, ScorecardStatus to, String decidedBy, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE credit.scorecard_model_version SET status = ?, decided_by = ?, decided_at = transaction_timestamp(),"
                        + " decision_reason = ? WHERE id = ? AND status = 'PROPOSED' RETURNING effective_from")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setString(3, reason);
            update.setObject(4, id.value());
            try (ResultSet row = update.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                Timestamp from = row.getTimestamp(1);
                return Optional.of(Optional.ofNullable(from).map(Timestamp::toInstant));
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("deciding a scorecard version", failure));
        }
    }

    @Override
    public Optional<ModelVersion> model(Connection unitOfWork, ScorecardModelVersionId id) {
        try {
            VersionRow row;
            int base;
            Instant from;
            Instant to;
            try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + ROW_COLUMNS
                    + ", base_points, effective_from, effective_to FROM credit.scorecard_model_version WHERE id = ?")) {
                select.setObject(1, id.value());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    row = versionRow(result);
                    base = result.getInt("base_points");
                    from = instant(result.getTimestamp("effective_from"));
                    to = instant(result.getTimestamp("effective_to"));
                }
            }
            Map<CreditAttributeCode, Integer> absent = new LinkedHashMap<>();
            Map<CreditAttributeCode, List<Scorecard.Band>> bands = new LinkedHashMap<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT attribute_code, ordinal, kind, lower_bound, upper_bound, codes, points"
                            + " FROM credit.scorecard_band WHERE model_version_id = ? ORDER BY attribute_code, ordinal")) {
                select.setObject(1, id.value());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        CreditAttributeCode code = CreditAttributeCode.valueOf(result.getString("attribute_code"));
                        int points = result.getInt("points");
                        bands.computeIfAbsent(code, ignored -> new ArrayList<>());
                        switch (result.getString("kind")) {
                            case "ABSENT" -> absent.put(code, points);
                            case "RANGE" -> bands.get(code).add(new Scorecard.Range(
                                    (Long) result.getObject("lower_bound"), (Long) result.getObject("upper_bound"), points));
                            case "CODES" -> bands.get(code).add(new Scorecard.Codes(
                                    Set.of((String[]) result.getArray("codes").getArray()), points));
                            default -> throw new IllegalStateException("an unknown band kind");
                        }
                    }
                }
            }
            List<Scorecard.AttributeBands> attributes = new ArrayList<>();
            bands.forEach((code, values) -> attributes.add(new Scorecard.AttributeBands(code, absent.get(code), values)));
            return Optional.of(new ModelVersion(row, new Scorecard(base, attributes), Optional.ofNullable(from),
                    Optional.ofNullable(to)));
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a scorecard version", failure));
        }
    }

    @Override
    public Optional<ScorecardModelVersionId> activeAt(Connection unitOfWork, ScorecardFamily family, Instant instant) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM credit.scorecard_model_version WHERE family = ? AND effective_from <= ?"
                        + " AND (effective_to IS NULL OR effective_to > ?)")) {
            select.setString(1, family.name());
            select.setTimestamp(2, Timestamp.from(instant));
            select.setTimestamp(3, Timestamp.from(instant));
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                ScorecardModelVersionId id = ScorecardModelVersionId.of(row.getObject(1, UUID.class));
                if (row.next()) {
                    throw new IllegalStateException("two scorecard versions effective at one instant");
                }
                return Optional.of(id);
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the scorecard active at an instant", failure));
        }
    }

    private static Optional<VersionRow> row(Connection unitOfWork, String sql, Object key, String what) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(versionRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe(what, failure));
        }
    }

    private static VersionRow versionRow(ResultSet row) throws SQLException {
        return new VersionRow(
                ScorecardModelVersionId.of(row.getObject("id", UUID.class)),
                ScorecardFamily.valueOf(row.getString("family")),
                row.getInt("version"),
                ScorecardStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                Optional.ofNullable(row.getString("decided_by")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public Optional<ScorecardModelVersionId> shareActive(Connection unitOfWork, ScorecardFamily family) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.scorecard_model_version"
                + " WHERE family = ? AND status = 'ACTIVE' FOR SHARE", family.name(), "sharing the active version")
                .map(VersionRow::id);
    }

    @Override
    public boolean sharePinned(Connection unitOfWork, ScorecardModelVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.scorecard_model_version WHERE id = ? FOR SHARE", id.value(),
                "sharing a pinned version").isPresent();
    }
}
