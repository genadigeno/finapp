package com.finapp.risk.testing;

import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.VelocityEvidence;
import com.finapp.risk.VelocityOutcome;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link RiskAssessmentStore} in memory for the hermetic suites (`P13-TSK-004`) - {@code risk V007}'s born-once rule
 * ({@code UNIQUE (operation, command_ref)}: a second insert of one command writes nothing and answers false), the
 * database's stamp a value the suite sets, every call recorded in order. Single-threaded: the concurrency is the owed
 * {@code RiskEvaluationDatabaseTest}'s.
 */
public final class InMemoryRiskAssessmentStore implements RiskAssessmentStore {

    /** The database's clock at an insert, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-23T12:00:00Z");

    /** Each operation, in the order it was asked: {@code find}, {@code read}, {@code insert}, {@code counted}. */
    public final List<String> log = new ArrayList<>();

    /** When set, the next {@link #find} misses whatever is stored - a race the caller's lock did not serialise. */
    public boolean missNextFind;

    private final Map<RiskAssessmentId, RiskAssessment> rows = new LinkedHashMap<>();

    /** Every assessment stored, in insert order. */
    public List<RiskAssessment> all() {
        return List.copyOf(rows.values());
    }

    /** Stores {@code assessment} as a writer that bypassed nothing would - the suites' planted row. */
    public void plant(RiskAssessment assessment) {
        rows.put(assessment.id(), stamped(assessment));
    }

    @Override
    public Optional<RiskAssessment> find(Connection unitOfWork, RiskOperation operation, CommandRef commandRef) {
        log.add("find");
        if (missNextFind) {
            missNextFind = false;
            return Optional.empty();
        }
        return rows.values().stream()
                .filter(row -> row.operation() == operation && row.commandRef().equals(commandRef)).findFirst();
    }

    @Override
    public Optional<RiskAssessment> read(Connection unitOfWork, RiskAssessmentId id) {
        log.add("read");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean insert(Connection unitOfWork, RiskAssessment assessment) {
        log.add("insert");
        Objects.requireNonNull(assessment, "assessment");
        boolean taken = rows.values().stream().anyMatch(row -> row.operation() == assessment.operation()
                && row.commandRef().equals(assessment.commandRef()));
        if (taken) {
            return false;
        }
        rows.put(assessment.id(), stamped(assessment));
        return true;
    }

    @Override
    public List<VelocityEvidence.Counted> countedSince(Connection unitOfWork, Instant from) {
        log.add("counted");
        return rows.values().stream()
                .filter(row -> row.velocity() == VelocityOutcome.COUNTED && !row.cutAt().orElseThrow().isBefore(from))
                .sorted(Comparator.comparing(row -> row.cutAt().orElseThrow()))
                .map(row -> new VelocityEvidence.Counted(row.movement(), row.cutAt().orElseThrow(),
                        Optional.of(row.operation())))
                .toList();
    }

    @Override
    public List<VelocityEvidence.Counted> uncountedSince(Connection unitOfWork, Instant from) {
        log.add("uncounted");
        return rows.values().stream()
                .filter(row -> row.velocity() == VelocityOutcome.UNCOUNTED
                        && !row.cutAt().or(row::assessedAt).orElse(Instant.EPOCH).isBefore(from))
                .sorted(Comparator.comparing(row -> row.cutAt().or(row::assessedAt).orElse(Instant.EPOCH)))
                .map(row -> new VelocityEvidence.Counted(row.movement(), row.cutAt().or(row::assessedAt).orElseThrow(),
                        Optional.of(row.operation())))
                .toList();
    }

    @Override
    public Optional<JudgedPayment> judgedPayment(Connection unitOfWork, CommandRef commandRef) {
        log.add("judgedPayment");
        Objects.requireNonNull(commandRef, "commandRef");
        return rows.values().stream()
                .filter(row -> (row.operation() == RiskOperation.CARD_PAYMENT
                        || row.operation() == RiskOperation.WALLET_PAYMENT) && row.commandRef().equals(commandRef)
                        && row.subjects().merchant().isPresent())
                .reduce((first, later) -> later)
                .map(row -> new JudgedPayment(row.subjects().party(), row.subjects().merchant().orElseThrow()));
    }

    private RiskAssessment stamped(RiskAssessment assessment) {
        return new RiskAssessment(assessment.id(), assessment.operation(), assessment.commandRef(),
                assessment.subjects(), assessment.amount(), assessment.pin(), assessment.engine(), assessment.score(),
                assessment.decision(), assessment.unavailable(), assessment.challengeSatisfied(), assessment.velocity(),
                assessment.cutAt(), assessment.inputsCanonical(), assessment.inputsSha256(), assessment.verdictSha256(),
                assessment.hits(), assessment.reasons(), assessment.latencyMicros(), assessment.correlationId(),
                assessment.causationId(), Optional.of(assessment.assessedAt().orElse(now)));
    }
}
