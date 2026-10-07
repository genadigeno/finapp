package com.finapp.kyc;

import com.finapp.kyc.CounterpartyScreeningVocabulary.EntityType;
import com.finapp.kyc.CounterpartyScreeningVocabulary.PayeeVerdict;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReviewReason;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CountryCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-JDBC storage for counterparty screenings (ADR-0033, `P9-TSK-016`). Every statement runs in the
 * caller's unit of work; {@code kyc V009}'s CHECKs and edge trigger are the rank below the service.
 */
public final class JdbcCounterpartyScreeningStore implements CounterpartyScreeningStore {

    private static final String TABLE = "kyc.counterparty_screening";
    private static final String ATTEMPTS = "kyc.counterparty_screening_attempt";

    /**
     * Every decision's instant, the DATABASE's (the Phase 9 to 10 transition gate): a clearance's lapse is judged against
     * {@code DatabaseTime.now}, so its start must be on the same clock - an instance running behind would otherwise
     * stamp a clearance that lapses early, one running ahead a clearance that outlives its validity. Never before
     * {@code requested_at} (V009's {@code counterparty_screening_decided_after_requested}), which the requesting
     * instance stamped.
     */
    private static final String DECIDED_NOW = "GREATEST(statement_timestamp(), requested_at)";

    private static final String COLUMNS =
            "id, request_reference, subject_ciphertext, subject_nonce, subject_key_version, country,"
                    + " entity_type, payee_verdict, status, review_reason, decision_basis, policy_version,"
                    + " decided_at, decided_by, decision_reason_code, attempts, next_attempt_at, requested_at,"
                    + " requested_by";

    @Override
    public boolean insertRequested(Connection unitOfWork, NewScreening fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + TABLE + " (id, request_reference, subject_ciphertext, subject_nonce,"
                        + " subject_key_version, country, entity_type, payee_verdict, status, attempts,"
                        + " next_attempt_at, requested_at, requested_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 0, ?, statement_timestamp(), ?)"
                        + " ON CONFLICT (request_reference) DO NOTHING")) {
            insert.setObject(1, fresh.id().value());
            insert.setString(2, fresh.requestReference());
            insert.setBytes(3, fresh.subject().ciphertext());
            insert.setBytes(4, fresh.subject().nonce());
            insert.setInt(5, fresh.subject().keyVersion());
            insert.setString(6, fresh.country().code());
            insert.setString(7, fresh.entityType().name());
            insert.setString(8, fresh.payeeVerdict().name());
            insert.setTimestamp(9, Timestamp.from(fresh.dueAt()));
            insert.setString(10, fresh.requestedBy().orElse(null));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("requesting a counterparty screening", failure));
        }
    }

    @Override
    public Optional<Row> byRequest(Connection unitOfWork, String requestReference) {
        Objects.requireNonNull(requestReference, "requestReference must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE request_reference = ?",
                statement -> statement.setString(1, requestReference), "reading a counterparty screening by request");
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, CounterpartyScreeningId id) {
        Objects.requireNonNull(id, "id must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?",
                statement -> statement.setObject(1, id.value()), "reading counterparty screening " + id);
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, CounterpartyScreeningId id) {
        Objects.requireNonNull(id, "id must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE",
                statement -> statement.setObject(1, id.value()), "locking counterparty screening " + id);
    }

    @Override
    public void insertAttempt(Connection unitOfWork, Attempt attempt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + ATTEMPTS + " (screening_id, attempt, verdict, evidence_ciphertext, evidence_nonce,"
                        + " evidence_key_version, evidence_checksum, evidence_length, answered_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, attempt.screening().value());
            insert.setInt(2, attempt.attempt());
            insert.setString(3, attempt.verdict().name());
            if (attempt.evidence().isPresent()) {
                CounterpartySubjectCipher.Encrypted evidence = attempt.evidence().get();
                insert.setBytes(4, evidence.ciphertext());
                insert.setBytes(5, evidence.nonce());
                insert.setInt(6, evidence.keyVersion());
                insert.setBytes(7, attempt.checksum().orElseThrow());
                insert.setInt(8, attempt.evidenceLength());
            } else {
                insert.setNull(4, Types.BINARY);
                insert.setNull(5, Types.BINARY);
                insert.setNull(6, Types.INTEGER);
                insert.setNull(7, Types.BINARY);
                insert.setNull(8, Types.INTEGER);
            }
            insert.setTimestamp(9, Timestamp.from(attempt.answeredAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe(
                    "recording attempt " + attempt.attempt() + " of counterparty screening " + attempt.screening(), failure));
        }
    }

    @Override
    public boolean decideAutomatically(
            Connection unitOfWork, CounterpartyScreeningId id, CounterpartyScreeningStatus expected, AutomaticOutcome outcome) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET status = ?, review_reason = ?, decision_basis = 'AUTOMATIC',"
                        + " policy_version = ?, decided_at = " + DECIDED_NOW + ", attempts = ?, next_attempt_at = ?"
                        + " WHERE id = ? AND status = ?")) {
            update.setString(1, outcome.status().name());
            update.setString(2, outcome.reviewReason().map(Enum::name).orElse(null));
            update.setString(3, outcome.policyVersion());
            update.setInt(4, outcome.attempts());
            update.setTimestamp(5, outcome.nextAttemptAt().map(Timestamp::from).orElse(null));
            update.setObject(6, id.value());
            update.setString(7, expected.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("deciding counterparty screening " + id, failure));
        }
    }

    @Override
    public boolean decideByReviewer(Connection unitOfWork, CounterpartyScreeningId id, ReviewerOutcome outcome) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET status = ?, decision_basis = 'REVIEWER', decided_by = ?,"
                        + " decision_reason_code = ?, decision_narrative = ?, policy_version = ?, decided_at = " + DECIDED_NOW
                        + " WHERE id = ? AND status = 'IN_REVIEW'")) {
            update.setString(1, outcome.status().name());
            update.setString(2, outcome.decidedBy());
            update.setString(3, outcome.reasonCode().name());
            update.setString(4, outcome.narrative());
            update.setString(5, outcome.policyVersion());
            update.setObject(6, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("reviewing counterparty screening " + id, failure));
        }
    }

    @Override
    public List<CounterpartyScreeningId> claimDue(Connection unitOfWork, java.time.Duration permit, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(permit, "permit must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one screening");
        }
        if (permit.isNegative() || permit.isZero()) {
            throw new IllegalArgumentException("a claim's permit is positive");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET next_attempt_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN ("
                        + " SELECT id FROM " + TABLE
                        + " WHERE status IN ('REQUESTED', 'UNAVAILABLE') AND next_attempt_at <= statement_timestamp()"
                        + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<CounterpartyScreeningId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(CounterpartyScreeningId.of(rows.getObject("id", UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("claiming due counterparty screenings", failure));
        }
    }

    @Override
    public ReviewBacklog reviewBacklog(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT count(*) AS pending, min(decided_at) AS oldest FROM " + TABLE + " WHERE status = 'IN_REVIEW'");
                ResultSet rows = select.executeQuery()) {
            rows.next();
            Timestamp oldest = rows.getTimestamp("oldest");
            return new ReviewBacklog(rows.getLong("pending"), Optional.ofNullable(oldest).map(Timestamp::toInstant));
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe("reading the counterparty review backlog", failure));
        }
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static Optional<Row> one(Connection unitOfWork, String sql, Binder binder, String doing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            binder.bind(select);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(rehydrate(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new KycStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    private static Row rehydrate(ResultSet row) throws SQLException {
        return new Row(
                CounterpartyScreeningId.of(row.getObject("id", UUID.class)),
                row.getString("request_reference"),
                new CounterpartySubjectCipher.Encrypted(
                        row.getBytes("subject_ciphertext"), row.getBytes("subject_nonce"), row.getInt("subject_key_version")),
                CountryCode.of(row.getString("country")),
                EntityType.valueOf(row.getString("entity_type")),
                PayeeVerdict.valueOf(row.getString("payee_verdict")),
                CounterpartyScreeningStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("review_reason")).map(ReviewReason::valueOf),
                Optional.ofNullable(row.getString("decision_basis")).map(DecisionBasis::valueOf),
                Optional.ofNullable(row.getString("policy_version")),
                Optional.ofNullable(row.getTimestamp("decided_at")).map(Timestamp::toInstant),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(row.getString("decision_reason_code")).map(ReasonCode::valueOf),
                row.getInt("attempts"),
                Optional.ofNullable(row.getTimestamp("next_attempt_at")).map(Timestamp::toInstant),
                row.getTimestamp("requested_at").toInstant(),
                Optional.ofNullable(row.getString("requested_by")));
    }
}
