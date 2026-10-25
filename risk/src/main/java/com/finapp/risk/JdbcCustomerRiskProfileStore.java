package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plain-JDBC storage for the customer risk profile (`P13-TSK-007`; ADR-0033) over {@code risk.customer_risk_profile}
 * and {@code risk.customer_risk_profile_version}: the hint's waiting upsert, the sweep's claim
 * {@code FOR UPDATE SKIP LOCKED} judged on {@code clock_timestamp()} (the watermark's idiom), the waiting lock (R8),
 * the lock-free read, the born-once version and the head's conditional moves with every stamp from the database's
 * clock. {@code created_at} and {@code computed_at} are never named in a write that matters: the triggers stamp them.
 */
public final class JdbcCustomerRiskProfileStore implements CustomerRiskProfileStore {

    private static final String HEAD = "risk.customer_risk_profile";
    private static final String VERSIONS = "risk.customer_risk_profile_version";
    private static final String HEAD_COLUMNS = "party_id, current_version, rating, next_recompute_at, recomputed_at";
    private static final String VERSION_COLUMNS = "party_id, version, rating, cause, signal_score, reason_codes,"
            + " policy_version_id, policy_operation, policy_version, engine, inputs_canonical, inputs_sha256,"
            + " disposition_id, correlation_id, computed_at";

    @Override
    public void pullForward(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        try (PreparedStatement upsert = unitOfWork.prepareStatement(
                // ONE statement that WAITS on a holder: a recompute in flight re-stamps the permit at its commit, and
                // this then pulls it back to now - a skipped hint would be lost for a whole cadence.
                "INSERT INTO " + HEAD + " AS head (party_id, current_version, rating, next_recompute_at, created_at)"
                        + " VALUES (?, 0, NULL, clock_timestamp(), clock_timestamp())"
                        + " ON CONFLICT (party_id) DO UPDATE"
                        + " SET next_recompute_at = LEAST(head.next_recompute_at, clock_timestamp())")) {
            upsert.setObject(1, partyId);
            upsert.executeUpdate();
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("pulling a customer risk profile's permit forward", failure);
        }
    }

    @Override
    public Optional<CustomerRiskProfile> claimDue(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + HEAD_COLUMNS + " FROM " + HEAD + " WHERE next_recompute_at <= clock_timestamp()"
                        + " ORDER BY next_recompute_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                ResultSet row = select.executeQuery()) {
            return row.next() ? Optional.of(head(row)) : Optional.empty();
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("claiming a customer risk profile", failure);
        }
    }

    @Override
    public CustomerRiskProfile lockOrCreate(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + HEAD + " (party_id, current_version, rating, next_recompute_at, created_at)"
                        + " VALUES (?, 0, NULL, clock_timestamp(), clock_timestamp()) ON CONFLICT (party_id) DO NOTHING")) {
            insert.setObject(1, partyId);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("enrolling a customer risk profile", failure);
        }
        return select(unitOfWork, partyId, "SELECT " + HEAD_COLUMNS + " FROM " + HEAD + " WHERE party_id = ? FOR UPDATE",
                "locking a customer risk profile")
                .orElseThrow(() -> new IllegalStateException("a profile just enrolled is there to lock"));
    }

    @Override
    public Optional<CustomerRiskProfile> read(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        return select(unitOfWork, partyId, "SELECT " + HEAD_COLUMNS + " FROM " + HEAD + " WHERE party_id = ?",
                "reading a customer risk profile");
    }

    @Override
    public void insertVersion(Connection unitOfWork, CustomerRiskProfileVersion version) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(version, "version");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + VERSIONS + " (" + VERSION_COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())")) {
            insert.setObject(1, version.partyId());
            insert.setInt(2, version.version());
            insert.setString(3, version.rating().name());
            insert.setString(4, version.cause().name());
            Optional<CustomerRiskProfileVersion.Evaluation> evaluation = version.evaluation();
            if (evaluation.isPresent()) {
                CustomerRiskProfileVersion.Evaluation e = evaluation.get();
                insert.setInt(5, e.signalScore());
                insert.setObject(7, e.policyVersionId().value());
                insert.setString(8, RiskOperation.MONITORING.name());
                insert.setInt(9, e.policyVersion());
                insert.setString(10, e.engine().name());
                insert.setString(11, e.inputsCanonical());
                insert.setString(12, e.inputsSha256());
            } else {
                insert.setNull(5, Types.INTEGER);
                insert.setNull(7, Types.OTHER);
                insert.setNull(8, Types.VARCHAR);
                insert.setNull(9, Types.INTEGER);
                insert.setNull(10, Types.VARCHAR);
                insert.setNull(11, Types.VARCHAR);
                insert.setNull(12, Types.VARCHAR);
            }
            insert.setString(6, version.reasons().stream().map(ReasonCode::code).collect(Collectors.joining(",")));
            if (version.dispositionId().isPresent()) {
                insert.setObject(13, version.dispositionId().get());
            } else {
                insert.setNull(13, Types.OTHER);
            }
            insert.setString(14, version.correlationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("recording a customer risk profile version", failure);
        }
    }

    @Override
    public void advance(Connection unitOfWork, UUID partyId, int fromVersion, int toVersion, RiskRating rating,
            Duration permitDelay) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(rating, "rating");
        requireDelay(permitDelay);
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + HEAD + " SET current_version = ?, rating = ?, recomputed_at = clock_timestamp(),"
                        + " next_recompute_at = clock_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE party_id = ? AND current_version = ?")) {
            update.setInt(1, toVersion);
            update.setString(2, rating.name());
            update.setLong(3, permitDelay.toMillis());
            update.setObject(4, partyId);
            update.setInt(5, fromVersion);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("the profile being advanced is not at the version its lock read");
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("advancing a customer risk profile", failure);
        }
    }

    @Override
    public void restamp(Connection unitOfWork, UUID partyId, Duration permitDelay, boolean evaluated) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        requireDelay(permitDelay);
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + HEAD + " SET next_recompute_at = clock_timestamp() + ? * interval '1 millisecond'"
                        + (evaluated ? ", recomputed_at = clock_timestamp()" : "") + " WHERE party_id = ?")) {
            update.setLong(1, permitDelay.toMillis());
            update.setObject(2, partyId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("the profile being re-stamped is not there");
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("re-stamping a customer risk profile's permit", failure);
        }
    }

    @Override
    public List<CustomerRiskProfileVersion> versions(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + VERSION_COLUMNS + " FROM " + VERSIONS + " WHERE party_id = ? ORDER BY version")) {
            select.setObject(1, partyId);
            try (ResultSet row = select.executeQuery()) {
                List<CustomerRiskProfileVersion> versions = new ArrayList<>();
                while (row.next()) {
                    versions.add(version(row));
                }
                return List.copyOf(versions);
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("reading a customer risk profile's versions", failure);
        }
    }

    @Override
    public Optional<CustomerRiskProfileVersion> versionOfDisposition(Connection unitOfWork, UUID dispositionId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(dispositionId, "dispositionId");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + VERSION_COLUMNS + " FROM " + VERSIONS + " WHERE disposition_id = ?")) {
            select.setObject(1, dispositionId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(version(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("reading a disposition's customer risk profile version", failure);
        }
    }

    private static Optional<CustomerRiskProfile> select(Connection unitOfWork, UUID partyId, String sql,
            String operation) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, partyId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(head(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed(operation, failure);
        }
    }

    private static CustomerRiskProfile head(ResultSet row) throws SQLException {
        String rating = row.getString("rating");
        Timestamp recomputed = row.getTimestamp("recomputed_at");
        return new CustomerRiskProfile(row.getObject("party_id", UUID.class), row.getInt("current_version"),
                Optional.ofNullable(rating).map(RiskRating::valueOf), row.getTimestamp("next_recompute_at").toInstant(),
                Optional.ofNullable(recomputed).map(Timestamp::toInstant));
    }

    private static CustomerRiskProfileVersion version(ResultSet row) throws SQLException {
        String reasons = row.getString("reason_codes");
        UUID policy = row.getObject("policy_version_id", UUID.class);
        Optional<CustomerRiskProfileVersion.Evaluation> evaluation = policy == null
                ? Optional.empty()
                : Optional.of(new CustomerRiskProfileVersion.Evaluation(RiskPolicyVersionId.of(policy),
                        row.getInt("policy_version"), RiskEngine.valueOf(row.getString("engine")),
                        row.getInt("signal_score"), row.getString("inputs_canonical"), row.getString("inputs_sha256")));
        return new CustomerRiskProfileVersion(
                row.getObject("party_id", UUID.class),
                row.getInt("version"),
                RiskRating.valueOf(row.getString("rating")),
                ProfileCause.valueOf(row.getString("cause")),
                reasons.isEmpty() ? List.of() : Arrays.stream(reasons.split(",", -1)).map(ReasonCode::valueOf).toList(),
                evaluation,
                Optional.ofNullable(row.getObject("disposition_id", UUID.class)),
                row.getString("correlation_id"),
                Optional.of(row.getTimestamp("computed_at").toInstant()));
    }

    private static void requireDelay(Duration permitDelay) {
        Objects.requireNonNull(permitDelay, "permitDelay");
        if (permitDelay.isNegative()) {
            throw new IllegalArgumentException("a permit delay is not negative");
        }
    }
}
