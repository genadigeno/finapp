package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link RiskProofReads} over lists the suites plant (`P13-TSK-023`) - each read answers what was planted for its day,
 * keyed by the day an identifier was minted ({@link RiskDay}), so the proofs are judged without a database. Every call
 * is journalled in {@link #calls}; nothing here can write.
 */
final class FakeRiskProofReads implements RiskProofReads {

    /** One planted assessment as the census reads it, with the identifier whose millisecond places its day. */
    record Planted(UUID id, RiskOperation operation, CommandRef commandRef, boolean permits) {}

    Instant now = Instant.parse("2026-10-23T12:00:00Z");
    final List<Planted> assessed = new ArrayList<>();
    final List<RiskAssessment> assessments = new ArrayList<>();
    final Map<RiskDay, List<DecisionRow>> decisions = new HashMap<>();
    final Map<RiskDay, List<ReasonRow>> reasons = new HashMap<>();
    final Map<RiskDay, List<VersionRow>> versions = new HashMap<>();
    final Map<RiskDay, List<AlertRow>> alerts = new HashMap<>();
    final Map<RiskDay, List<RuleRow>> rules = new HashMap<>();
    final Map<RiskDay, List<BornOnceRow>> bornOnce = new HashMap<>();
    final Map<RiskDay, List<CaseRow>> opened = new HashMap<>();
    final Map<RiskDay, List<DisposedRow>> disposed = new HashMap<>();
    final List<OpenRow> open = new ArrayList<>();
    FilingRow filing = new FilingRow(0, 0, 0);
    Duration filingAsked;
    final List<UUID> monitored = new ArrayList<>();
    final Map<UUID, MonitoredTransaction> transactions = new HashMap<>();
    final Map<RiskSignalId, RiskSignal> signals = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    @Override
    public Instant databaseNow(Connection snapshot) {
        calls.add("now");
        return now;
    }

    @Override
    public List<Assessed> assessed(Connection snapshot, RiskOperation operation, RiskDay day) {
        calls.add("assessed " + operation);
        return assessed.stream().filter(one -> one.operation() == operation && on(one.id(), day))
                .map(one -> new Assessed(one.commandRef(), one.permits())).toList();
    }

    @Override
    public Set<CommandRef> assessedAmong(Connection snapshot, RiskOperation operation, Collection<CommandRef> refs) {
        calls.add("assessedAmong " + operation);
        Set<CommandRef> found = new LinkedHashSet<>();
        for (Planted one : assessed) {
            if (one.operation() == operation && refs.contains(one.commandRef())) {
                found.add(one.commandRef());
            }
        }
        return found;
    }

    @Override
    public List<RiskAssessment> assessmentsOfDay(Connection snapshot, RiskDay day, Optional<RiskAssessmentId> after,
            int limit) {
        calls.add("assessmentsOfDay");
        return assessments.stream()
                .filter(assessment -> on(assessment.id().value(), day))
                .filter(assessment -> after.isEmpty() || RiskProofs.order(assessment.id().value(), after.get().value()) > 0)
                .sorted(Comparator.comparing(assessment -> assessment.id().value(), RiskProofs::order))
                .limit(limit)
                .toList();
    }

    @Override
    public List<DecisionRow> decisions(Connection snapshot, RiskDay day) {
        calls.add("decisions");
        return decisions.getOrDefault(day, List.of());
    }

    @Override
    public List<ReasonRow> reasons(Connection snapshot, RiskDay day) {
        calls.add("reasons");
        return reasons.getOrDefault(day, List.of());
    }

    @Override
    public List<VersionRow> versions(Connection snapshot, RiskDay day) {
        calls.add("versions");
        return versions.getOrDefault(day, List.of());
    }

    @Override
    public List<AlertRow> alerts(Connection snapshot, RiskDay day) {
        calls.add("alerts");
        return alerts.getOrDefault(day, List.of());
    }

    @Override
    public List<RuleRow> alertRules(Connection snapshot, RiskDay day) {
        calls.add("alertRules");
        return rules.getOrDefault(day, List.of());
    }

    @Override
    public List<BornOnceRow> bornOnce(Connection snapshot, RiskDay day) {
        calls.add("bornOnce " + day);
        return bornOnce.getOrDefault(day, List.of());
    }

    @Override
    public List<CaseRow> casesOpened(Connection snapshot, RiskDay day) {
        calls.add("casesOpened");
        return opened.getOrDefault(day, List.of());
    }

    @Override
    public List<DisposedRow> casesDisposed(Connection snapshot, RiskDay day) {
        calls.add("casesDisposed");
        return disposed.getOrDefault(day, List.of());
    }

    @Override
    public List<OpenRow> openCases(Connection snapshot) {
        calls.add("openCases");
        return List.copyOf(open);
    }

    @Override
    public FilingRow filings(Connection snapshot, Duration overdueAfter) {
        calls.add("filings");
        filingAsked = overdueAfter;
        return filing;
    }

    @Override
    public List<UUID> monitoredBetween(Connection snapshot, UUID fromInclusive, UUID toExclusive) {
        calls.add("monitoredBetween");
        return monitored.stream()
                .filter(id -> RiskProofs.order(id, fromInclusive) >= 0 && RiskProofs.order(id, toExclusive) < 0)
                .sorted(RiskProofs::order).toList();
    }

    @Override
    public Optional<MonitoredTransaction> monitored(Connection snapshot, UUID journalEntryId) {
        calls.add("monitored");
        return Optional.ofNullable(transactions.get(journalEntryId));
    }

    @Override
    public Optional<RiskSignal> signal(Connection snapshot, RiskSignalId id) {
        calls.add("signal");
        return Optional.ofNullable(signals.get(id));
    }

    /** True when {@code id} was minted on {@code day}. */
    static boolean on(UUID id, RiskDay day) {
        return RiskProofs.order(id, day.fromId()) >= 0 && RiskProofs.order(id, day.toId()) < 0;
    }

    /** A fresh UUIDv7 minted at {@code instant} - its millisecond places its day. */
    static UUID mintedAt(Instant instant) {
        UUID position = MonitoringWatermark.positionAt(instant.toEpochMilli());
        UUID random = UUID.randomUUID();
        return new UUID(position.getMostSignificantBits() | (random.getMostSignificantBits() & 0x0FFFL),
                0x8000_0000_0000_0000L | (random.getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL));
    }
}
