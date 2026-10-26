package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RiskAssessmentStore} over {@code risk V007} (`P13-TSK-004`): the head born once by
 * {@code ON CONFLICT (operation, command_ref) DO NOTHING RETURNING id}, then its sealed inputs, hits and reasons in the
 * same transaction (the deferred {@code risk_assessment_has_its_inputs} holds the whole at commit); reads with no lock.
 * Risk's own tables only - no other schema is named ({@code INV-RSK-02}). A failure is named by its operation and
 * SQLSTATE only, never the driver's message.
 */
public final class JdbcRiskAssessmentStore implements RiskAssessmentStore {

    static final String HEAD_COLUMNS = "id, operation, command_ref, party_id, identity_id, source_account_id,"
            + " destination_kind, destination_id, device_hash, merchant_subject_id, session_id, amount_minor, currency,"
            + " scale,"
            + " policy_version_id, policy_version, engine, score, decision, unavailable_cause, challenge_satisfied,"
            + " velocity_outcome, velocity_cut_at, inputs_sha256, verdict_sha256, latency_micros, correlation_id,"
            + " causation_id, assessed_at";

    static final String INSERT_HEAD = "INSERT INTO risk.risk_assessment (id, operation, command_ref, party_id,"
            + " identity_id, source_account_id, destination_kind, destination_id, device_hash, merchant_subject_id,"
            + " session_id, amount_minor, currency, scale, policy_version_id, policy_operation, policy_version, engine,"
            + " score, decision, fail_safe_reason, unavailable_cause, challenge_satisfied, velocity_outcome,"
            + " velocity_cut_at, canonical_format, inputs_sha256, verdict_sha256, latency_micros, correlation_id,"
            + " causation_id, assessed_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " clock_timestamp())"
            + " ON CONFLICT (operation, command_ref) DO NOTHING RETURNING id";

    static final String INSERT_INPUT = "INSERT INTO risk.risk_assessment_input (assessment_id, inputs_canonical,"
            + " inputs_sha256) VALUES (?, ?, ?)";

    static final String INSERT_HIT = "INSERT INTO risk.risk_rule_hit (assessment_id, ordinal, kind, outcome,"
            + " contribution, hard_decision, judged) VALUES (?, ?, ?, ?, ?, ?, ?)";

    static final String INSERT_REASON = "INSERT INTO risk.risk_reason (assessment_id, position, code) VALUES (?, ?, ?)";

    static final String COUNTED = "SELECT operation, party_id, identity_id, source_account_id, destination_kind, destination_id,"
            + " device_hash,"
            + " merchant_subject_id, session_id, amount_minor, currency, scale, velocity_cut_at FROM risk.risk_assessment"
            + " WHERE velocity_outcome = 'COUNTED' AND velocity_cut_at >= ? ORDER BY velocity_cut_at, id";

    /** The permitted, uncounted movements since an id position - the primary key's range (`P13-TSK-023`). */
    static final String UNCOUNTED = "SELECT operation, party_id, identity_id, source_account_id, destination_kind,"
            + " destination_id, device_hash, merchant_subject_id, session_id, amount_minor, currency, scale,"
            + " coalesce(velocity_cut_at, assessed_at) AS counted_at FROM risk.risk_assessment"
            + " WHERE id >= ? AND velocity_outcome = 'UNCOUNTED' ORDER BY id";

    /**
     * The first transfer ever judged - the primary key in minting order, the first {@code TRANSFER} row met
     * (`P13-TSK-023`, the velocity proof's fixed floor).
     */
    static final String FIRST_TRANSFER = "SELECT coalesce(velocity_cut_at, assessed_at) AS judged_at"
            + " FROM risk.risk_assessment WHERE operation = 'TRANSFER' ORDER BY id LIMIT 1";

    static final String JUDGED_PAYMENT ="SELECT party_id, merchant_subject_id FROM risk.risk_assessment"
            + " WHERE operation IN ('CARD_PAYMENT', 'WALLET_PAYMENT') AND command_ref = ?"
            + " AND merchant_subject_id IS NOT NULL ORDER BY assessed_at DESC, id DESC LIMIT 1";

    @Override
    public Optional<RiskAssessment> find(Connection unitOfWork, RiskOperation operation, CommandRef commandRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(commandRef, "commandRef");
        return head(unitOfWork, "SELECT " + HEAD_COLUMNS + " FROM risk.risk_assessment"
                + " WHERE operation = ? AND command_ref = ?", operation.name(), commandRef.value());
    }

    @Override
    public Optional<RiskAssessment> read(Connection unitOfWork, RiskAssessmentId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        return head(unitOfWork, "SELECT " + HEAD_COLUMNS + " FROM risk.risk_assessment WHERE id = ?", id.value());
    }

    @Override
    public boolean insert(Connection unitOfWork, RiskAssessment assessment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(assessment, "assessment");
        UUID id = assessment.id().value();
        try (PreparedStatement head = unitOfWork.prepareStatement(INSERT_HEAD)) {
            RiskAssessment.Subjects subjects = assessment.subjects();
            int i = 0;
            head.setObject(++i, id);
            head.setString(++i, assessment.operation().name());
            head.setString(++i, assessment.commandRef().value());
            head.setObject(++i, subjects.party());
            head.setObject(++i, subjects.identity().orElse(null), Types.OTHER);
            head.setObject(++i, subjects.sourceAccount().orElse(null), Types.OTHER);
            head.setString(++i, subjects.destination().map(to -> to.kind().name()).orElse(null));
            head.setObject(++i, subjects.destination().map(RiskEvaluationRequest.Destination::id).orElse(null),
                    Types.OTHER);
            head.setString(++i, subjects.device().map(DeviceHash::value).orElse(null));
            head.setObject(++i, subjects.merchant().orElse(null), Types.OTHER);
            head.setObject(++i, subjects.session().orElse(null), Types.OTHER);
            head.setLong(++i, assessment.amount().minorUnits());
            head.setString(++i, assessment.amount().currency().code());
            head.setInt(++i, assessment.amount().scale());
            head.setObject(++i, assessment.pin().map(pin -> pin.id().value()).orElse(null), Types.OTHER);
            head.setString(++i, assessment.pin().isPresent() ? assessment.operation().name() : null);
            if (assessment.pin().isPresent()) {
                head.setInt(++i, assessment.pin().get().version());
            } else {
                head.setNull(++i, Types.INTEGER);
            }
            head.setString(++i, assessment.engine().name());
            head.setInt(++i, assessment.score().value());
            head.setString(++i, assessment.decision().name());
            head.setString(++i, assessment.failSafeReason().map(ReasonCode::name).orElse(null));
            head.setString(++i, assessment.unavailable().map(UnavailableCause::name).orElse(null));
            if (assessment.challengeSatisfied().isPresent()) {
                head.setBoolean(++i, assessment.challengeSatisfied().get());
            } else {
                head.setNull(++i, Types.BOOLEAN);
            }
            head.setString(++i, assessment.velocity().name());
            head.setObject(++i, assessment.cutAt().map(at -> OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).orElse(null),
                    Types.TIMESTAMP_WITH_TIMEZONE);
            head.setInt(++i, CanonicalEvaluation.FORMAT);
            head.setString(++i, assessment.inputsSha256());
            head.setString(++i, assessment.verdictSha256());
            head.setLong(++i, assessment.latencyMicros());
            head.setString(++i, assessment.correlationId());
            head.setString(++i, assessment.causationId());
            try (ResultSet born = head.executeQuery()) {
                if (!born.next()) {
                    return false;
                }
            }
        } catch (SQLException failure) {
            throw failed("sealing a risk assessment", failure);
        }
        try (PreparedStatement input = unitOfWork.prepareStatement(INSERT_INPUT)) {
            input.setObject(1, id);
            input.setString(2, assessment.inputsCanonical());
            input.setString(3, assessment.inputsSha256());
            input.executeUpdate();
        } catch (SQLException failure) {
            throw failed("sealing a risk assessment's inputs", failure);
        }
        if (!assessment.hits().isEmpty()) {
            try (PreparedStatement hit = unitOfWork.prepareStatement(INSERT_HIT)) {
                for (RuleHit each : assessment.hits()) {
                    hit.setObject(1, id);
                    hit.setInt(2, each.ordinal());
                    hit.setString(3, each.kind().name());
                    hit.setString(4, each.outcome().name());
                    hit.setInt(5, each.contribution());
                    hit.setString(6, each.hardDecision().map(RiskDecision::name).orElse(null));
                    hit.setString(7, each.judged());
                    hit.addBatch();
                }
                hit.executeBatch();
            } catch (SQLException failure) {
                throw failed("sealing a risk assessment's rule hits", failure);
            }
        }
        if (!assessment.reasons().isEmpty()) {
            try (PreparedStatement reason = unitOfWork.prepareStatement(INSERT_REASON)) {
                int position = 0;
                for (ReasonCode code : assessment.reasons()) {
                    reason.setObject(1, id);
                    reason.setInt(2, ++position);
                    reason.setString(3, code.name());
                    reason.addBatch();
                }
                reason.executeBatch();
            } catch (SQLException failure) {
                throw failed("sealing a risk assessment's reasons", failure);
            }
        }
        return true;
    }

    @Override
    public List<VelocityEvidence.Counted> countedSince(Connection unitOfWork, Instant from) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(from, "from");
        try (PreparedStatement select = unitOfWork.prepareStatement(COUNTED)) {
            select.setObject(1, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
            return movements(select, "velocity_cut_at");
        } catch (SQLException failure) {
            throw failed("reading the counted assessments", failure);
        }
    }

    @Override
    public List<VelocityEvidence.Counted> uncountedSince(Connection unitOfWork, Instant from) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(from, "from");
        try (PreparedStatement select = unitOfWork.prepareStatement(UNCOUNTED)) {
            select.setObject(1, MonitoringWatermark.positionAt(from.toEpochMilli()));
            return movements(select, "counted_at");
        } catch (SQLException failure) {
            throw failed("reading the uncounted assessments", failure);
        }
    }

    @Override
    public Optional<Instant> firstTransferAt(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(FIRST_TRANSFER);
                ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            return Optional.ofNullable(row.getObject("judged_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant);
        } catch (SQLException failure) {
            throw failed("reading the first judged transfer", failure);
        }
    }

    private static List<VelocityEvidence.Counted> movements(PreparedStatement select, String instant)
            throws SQLException {
        List<VelocityEvidence.Counted> counted = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                RiskAssessment.Subjects subjects = subjects(row);
                Money amount = Money.ofPersisted(row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency")), row.getInt("scale"));
                counted.add(new VelocityEvidence.Counted(subjects.movement(amount),
                        row.getObject(instant, OffsetDateTime.class).toInstant(),
                        Optional.of(RiskOperation.valueOf(row.getString("operation")))));
            }
        }
        return counted;
    }

    @Override
    public Optional<JudgedPayment> judgedPayment(Connection unitOfWork, CommandRef commandRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(commandRef, "commandRef");
        // Two probes of UNIQUE (operation, command_ref); risk's own table only (INV-RSK-02).
        try (PreparedStatement select = unitOfWork.prepareStatement(JUDGED_PAYMENT)) {
            select.setString(1, commandRef.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new JudgedPayment(row.getObject("party_id", UUID.class),
                        row.getObject("merchant_subject_id", UUID.class)));
            }
        } catch (SQLException failure) {
            throw failed("reading a judged payment's subjects", failure);
        }
    }

    // ------------------------------------------------------------------ the reads

    private Optional<RiskAssessment> head(Connection unitOfWork, String sql, Object... arguments) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID id = row.getObject("id", UUID.class);
                String inputs = inputs(unitOfWork, id);
                List<RuleHit> hits = hits(unitOfWork, id);
                List<ReasonCode> reasons = reasons(unitOfWork, id);
                return Optional.of(assessment(row, inputs, hits, reasons));
            }
        } catch (SQLException failure) {
            throw failed("reading a risk assessment", failure);
        }
    }

    static RiskAssessment assessment(ResultSet row, String inputs, List<RuleHit> hits, List<ReasonCode> reasons)
            throws SQLException {
        RiskOperation operation = RiskOperation.valueOf(row.getString("operation"));
        UUID policyVersionId = row.getObject("policy_version_id", UUID.class);
        Optional<RiskAssessment.Pin> pin = policyVersionId == null ? Optional.empty()
                : Optional.of(new RiskAssessment.Pin(RiskPolicyVersionId.of(policyVersionId), row.getInt("policy_version")));
        Object satisfied = row.getObject("challenge_satisfied");
        OffsetDateTime cutAt = row.getObject("velocity_cut_at", OffsetDateTime.class);
        return new RiskAssessment(
                RiskAssessmentId.of(row.getObject("id", UUID.class)),
                operation,
                CommandRef.of(row.getString("command_ref")),
                subjects(row),
                Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("currency")),
                        row.getInt("scale")),
                pin,
                RiskEngine.valueOf(row.getString("engine")),
                new Score(row.getInt("score")),
                RiskDecision.valueOf(row.getString("decision")),
                Optional.ofNullable(row.getString("unavailable_cause")).map(UnavailableCause::valueOf),
                satisfied == null ? Optional.empty() : Optional.of((Boolean) satisfied),
                VelocityOutcome.valueOf(row.getString("velocity_outcome")),
                Optional.ofNullable(cutAt).map(OffsetDateTime::toInstant),
                inputs,
                row.getString("inputs_sha256"),
                row.getString("verdict_sha256"),
                hits,
                reasons,
                row.getLong("latency_micros"),
                row.getString("correlation_id"),
                row.getString("causation_id"),
                Optional.of(row.getObject("assessed_at", OffsetDateTime.class).toInstant()));
    }

    private static RiskAssessment.Subjects subjects(ResultSet row) throws SQLException {
        String destinationKind = row.getString("destination_kind");
        Optional<RiskEvaluationRequest.Destination> destination = destinationKind == null ? Optional.empty()
                : Optional.of(new RiskEvaluationRequest.Destination(
                        RiskEvaluationRequest.DestinationKind.valueOf(destinationKind),
                        row.getObject("destination_id", UUID.class)));
        return new RiskAssessment.Subjects(
                row.getObject("party_id", UUID.class),
                Optional.ofNullable(row.getObject("identity_id", UUID.class)),
                Optional.ofNullable(row.getObject("source_account_id", UUID.class)),
                destination,
                Optional.ofNullable(row.getString("device_hash")).map(DeviceHash::parse),
                Optional.ofNullable(row.getObject("merchant_subject_id", UUID.class)),
                Optional.ofNullable(row.getObject("session_id", UUID.class)));
    }

    private static String inputs(Connection unitOfWork, UUID id) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT inputs_canonical FROM risk.risk_assessment_input WHERE assessment_id = ?")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("a sealed risk assessment has its inputs");
                }
                return row.getString(1);
            }
        }
    }

    private static List<RuleHit> hits(Connection unitOfWork, UUID id) throws SQLException {
        List<RuleHit> hits = new ArrayList<>();
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT ordinal, kind, outcome, contribution,"
                + " hard_decision, judged FROM risk.risk_rule_hit WHERE assessment_id = ? ORDER BY ordinal")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    hits.add(new RuleHit(row.getInt("ordinal"), RuleKind.valueOf(row.getString("kind")),
                            RuleOutcome.valueOf(row.getString("outcome")), row.getInt("contribution"),
                            Optional.ofNullable(row.getString("hard_decision")).map(RiskDecision::valueOf),
                            row.getString("judged")));
                }
            }
        }
        return hits;
    }

    private static List<ReasonCode> reasons(Connection unitOfWork, UUID id) throws SQLException {
        List<ReasonCode> reasons = new ArrayList<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT code FROM risk.risk_reason WHERE assessment_id = ? ORDER BY position")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    reasons.add(ReasonCode.valueOf(row.getString(1)));
                }
            }
        }
        return reasons;
    }

    static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
