package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * {@link RiskProofReads} over risk's own tables (`P13-TSK-023`; {@code DESIGN-023} D1-D9): {@code SELECT} only, risk's
 * schema only ({@code INV-RSK-02}), on the caller's snapshot, no lock. A day is entered through each table's primary key
 * - {@code id >= ? AND id < ?} over the writer's UUIDv7 ({@link RiskDay}) - so no read needs an index the migrations do
 * not already hold: {@code risk_assessment_pk}, {@code risk_alert_pk}, {@code risk_case_pk}, {@code
 * risk_assessment_command_once}, {@code risk_alert_born_once}, {@code monitored_transaction_born_once},
 * {@code regulatory_report_due}. Only the day's dispositions scan {@code risk_case} (a table of human investigations).
 * A sum is read exact ({@link BigDecimal#longValueExact()} - refused, never wrapped). A failure is named by its operation
 * and SQLSTATE only.
 */
public final class JdbcRiskProofReads implements RiskProofReads {

    static final String NOW = "SELECT clock_timestamp()";

    static final String ASSESSED = "SELECT command_ref, decision, challenge_satisfied FROM risk.risk_assessment"
            + " WHERE id >= ? AND id < ? AND operation = ? ORDER BY id";

    static final String ASSESSED_AMONG = "SELECT command_ref FROM risk.risk_assessment"
            + " WHERE operation = ? AND command_ref = ANY (?)";

    static final String DAY_IDS = "SELECT id FROM risk.risk_assessment WHERE id >= ? AND id < ? ORDER BY id LIMIT ?";

    static final String DAY_IDS_AFTER = "SELECT id FROM risk.risk_assessment WHERE id > ? AND id < ?"
            + " ORDER BY id LIMIT ?";

    static final String HEADS = "SELECT " + JdbcRiskAssessmentStore.HEAD_COLUMNS
            + " FROM risk.risk_assessment WHERE id = ANY (?) ORDER BY id";

    static final String INPUTS = "SELECT assessment_id, inputs_canonical FROM risk.risk_assessment_input"
            + " WHERE assessment_id = ANY (?)";

    static final String HITS = "SELECT assessment_id, ordinal, kind, outcome, contribution, hard_decision, judged"
            + " FROM risk.risk_rule_hit WHERE assessment_id = ANY (?) ORDER BY assessment_id, ordinal";

    static final String REASONS_OF = "SELECT assessment_id, code FROM risk.risk_reason WHERE assessment_id = ANY (?)"
            + " ORDER BY assessment_id, position";

    static final String DECISIONS = "SELECT operation, decision, unavailable_cause, challenge_satisfied,"
            + " velocity_outcome, currency, scale, count(*) AS assessments, sum(amount_minor) AS judged"
            + " FROM risk.risk_assessment WHERE id >= ? AND id < ?"
            + " GROUP BY operation, decision, unavailable_cause, challenge_satisfied, velocity_outcome, currency, scale"
            + " ORDER BY operation, decision, unavailable_cause, challenge_satisfied, velocity_outcome, currency, scale";

    static final String REASONS = "SELECT a.operation, r.code, count(*) AS assessments"
            + " FROM risk.risk_assessment a JOIN risk.risk_reason r ON r.assessment_id = a.id"
            + " WHERE a.id >= ? AND a.id < ? GROUP BY a.operation, r.code ORDER BY a.operation, r.code";

    static final String VERSIONS = "SELECT operation, policy_version, count(*) AS assessments FROM risk.risk_assessment"
            + " WHERE id >= ? AND id < ? GROUP BY operation, policy_version ORDER BY operation, policy_version";

    static final String ALERTS = "SELECT kind, severity, status, count(*) AS alerts FROM risk.risk_alert"
            + " WHERE id >= ? AND id < ? GROUP BY kind, severity, status ORDER BY kind, severity, status";

    static final String ALERT_RULES = "SELECT kind, rule_code, count(*) AS alerts FROM risk.risk_alert"
            + " WHERE id >= ? AND id < ? GROUP BY kind, rule_code ORDER BY kind, rule_code";

    /**
     * The born-once census: the day's alerts grouped by their key, then, per kind and window kind, the alerts raised,
     * the keys among them and every row of those keys any day holds - each key probed through {@code
     * risk_alert_born_once} itself, so a second row is found wherever and whenever it was born.
     */
    static final String BORN_ONCE = "WITH keyed AS (SELECT kind, window_kind, subject_ref, rule_code, window_start,"
            + " count(*) AS raised FROM risk.risk_alert WHERE id >= ? AND id < ?"
            + " GROUP BY kind, window_kind, subject_ref, rule_code, window_start)"
            + " SELECT k.kind, k.window_kind, sum(k.raised) AS raised, count(*) AS keys,"
            + " sum((SELECT count(*) FROM risk.risk_alert a WHERE a.subject_ref = k.subject_ref"
            + " AND a.rule_code = k.rule_code AND a.window_start = k.window_start)) AS held"
            + " FROM keyed k GROUP BY k.kind, k.window_kind ORDER BY k.kind, k.window_kind";

    static final String CASES_OPENED = "SELECT kind, status, count(*) AS cases FROM risk.risk_case"
            + " WHERE id >= ? AND id < ? GROUP BY kind, status ORDER BY kind, status";

    static final String CASES_DISPOSED = "SELECT kind, disposition, count(*) AS cases FROM risk.risk_case"
            + " WHERE status = 'DISPOSED' AND disposed_at >= ? AND disposed_at < ?"
            + " GROUP BY kind, disposition ORDER BY kind, disposition";

    /** The one-open census: the open cases per kind and subject, re-proven by query rather than by the unique. */
    static final String OPEN_CASES = "SELECT kind, sum(held) AS open, count(*) AS subjects,"
            + " count(*) FILTER (WHERE held > 1) AS over_one FROM (SELECT kind, subject_ref, count(*) AS held"
            + " FROM risk.risk_case WHERE status <> 'DISPOSED' GROUP BY kind, subject_ref) per_subject"
            + " GROUP BY kind ORDER BY kind";

    static final String FILINGS = "SELECT count(*) AS filed,"
            + " count(*) FILTER (WHERE next_inquiry_at <= clock_timestamp()) AS due,"
            + " count(*) FILTER (WHERE next_inquiry_at <= clock_timestamp() - make_interval(secs => ?)) AS overdue"
            + " FROM risk.regulatory_report WHERE next_inquiry_at IS NOT NULL";

    static final String MONITORED_BETWEEN = "SELECT journal_entry_id FROM risk.monitored_transaction"
            + " WHERE journal_entry_id >= ? AND journal_entry_id < ? ORDER BY journal_entry_id";

    static final String MONITORED_ONE = "SELECT id, journal_entry_id, journal_sequence, entry_type, posting_namespace,"
            + " kind, subject_kind, subject_ref, direction, counterparty_kind, counterparty_ref, counterparty_purpose,"
            + " amount_minor, currency, scale, value_date, posted_at, reverses_journal_entry_id"
            + " FROM risk.monitored_transaction WHERE journal_entry_id = ?";

    static final String SIGNAL_ONE = "SELECT " + JdbcRiskSignalStore.COLUMNS + " FROM risk.risk_signal WHERE id = ?";

    @Override
    public Instant databaseNow(Connection snapshot) {
        try (PreparedStatement select = snapshot.prepareStatement(NOW); ResultSet row = select.executeQuery()) {
            row.next();
            return row.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public List<Assessed> assessed(Connection snapshot, RiskOperation operation, RiskDay day) {
        Objects.requireNonNull(operation, "operation");
        List<Assessed> assessed = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(ASSESSED)) {
            select.setObject(1, day.fromId());
            select.setObject(2, day.toId());
            select.setString(3, operation.name());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    Object satisfied = row.getObject("challenge_satisfied");
                    assessed.add(new Assessed(CommandRef.of(row.getString("command_ref")), VelocityWindows.permits(
                            RiskDecision.valueOf(row.getString("decision")), Boolean.TRUE.equals(satisfied))));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading the day's assessments", failure);
        }
        return assessed;
    }

    @Override
    public Set<CommandRef> assessedAmong(Connection snapshot, RiskOperation operation, Collection<CommandRef> refs) {
        Objects.requireNonNull(operation, "operation");
        Set<CommandRef> found = new LinkedHashSet<>();
        if (refs.isEmpty()) {
            return found;
        }
        try (PreparedStatement select = snapshot.prepareStatement(ASSESSED_AMONG)) {
            select.setString(1, operation.name());
            select.setArray(2, snapshot.createArrayOf("text", refs.stream().map(CommandRef::value).toArray()));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    found.add(CommandRef.of(row.getString(1)));
                }
            }
        } catch (SQLException failure) {
            throw failed("probing assessments by command", failure);
        }
        return found;
    }

    @Override
    public List<RiskAssessment> assessmentsOfDay(Connection snapshot, RiskDay day, Optional<RiskAssessmentId> after,
            int limit) {
        Objects.requireNonNull(after, "after");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one assessment");
        }
        try {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement select = snapshot.prepareStatement(after.isEmpty() ? DAY_IDS : DAY_IDS_AFTER)) {
                select.setObject(1, after.map(RiskAssessmentId::value).orElse(day.fromId()));
                select.setObject(2, day.toId());
                select.setInt(3, limit);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        ids.add(row.getObject(1, UUID.class));
                    }
                }
            }
            if (ids.isEmpty()) {
                return List.of();
            }
            java.sql.Array keys = snapshot.createArrayOf("uuid", ids.toArray());
            Map<UUID, String> inputs = new HashMap<>();
            try (PreparedStatement select = snapshot.prepareStatement(INPUTS)) {
                select.setArray(1, keys);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        inputs.put(row.getObject("assessment_id", UUID.class), row.getString("inputs_canonical"));
                    }
                }
            }
            Map<UUID, List<RuleHit>> hits = new HashMap<>();
            try (PreparedStatement select = snapshot.prepareStatement(HITS)) {
                select.setArray(1, keys);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        hits.computeIfAbsent(row.getObject("assessment_id", UUID.class), id -> new ArrayList<>())
                                .add(new RuleHit(row.getInt("ordinal"), RuleKind.valueOf(row.getString("kind")),
                                        RuleOutcome.valueOf(row.getString("outcome")), row.getInt("contribution"),
                                        Optional.ofNullable(row.getString("hard_decision")).map(RiskDecision::valueOf),
                                        row.getString("judged")));
                    }
                }
            }
            Map<UUID, List<ReasonCode>> reasons = new HashMap<>();
            try (PreparedStatement select = snapshot.prepareStatement(REASONS_OF)) {
                select.setArray(1, keys);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        reasons.computeIfAbsent(row.getObject("assessment_id", UUID.class), id -> new ArrayList<>())
                                .add(ReasonCode.valueOf(row.getString("code")));
                    }
                }
            }
            List<RiskAssessment> page = new ArrayList<>(ids.size());
            try (PreparedStatement select = snapshot.prepareStatement(HEADS)) {
                select.setArray(1, keys);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        UUID id = row.getObject("id", UUID.class);
                        String sealed = inputs.get(id);
                        if (sealed == null) {
                            throw new IllegalStateException("a sealed risk assessment has its inputs");
                        }
                        page.add(JdbcRiskAssessmentStore.assessment(row, sealed, hits.getOrDefault(id, List.of()),
                                reasons.getOrDefault(id, List.of())));
                    }
                }
            }
            return page;
        } catch (SQLException failure) {
            throw failed("reading a page of the day's assessments", failure);
        }
    }

    @Override
    public List<DecisionRow> decisions(Connection snapshot, RiskDay day) {
        List<DecisionRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, DECISIONS, day); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                Object satisfied = row.getObject("challenge_satisfied");
                BigDecimal judged = row.getBigDecimal("judged");
                rows.add(new DecisionRow(RiskOperation.valueOf(row.getString("operation")),
                        RiskDecision.valueOf(row.getString("decision")),
                        Optional.ofNullable(row.getString("unavailable_cause")).map(UnavailableCause::valueOf),
                        satisfied == null ? Optional.empty() : Optional.of((Boolean) satisfied),
                        VelocityOutcome.valueOf(row.getString("velocity_outcome")),
                        CurrencyCode.of(row.getString("currency")), row.getInt("scale"), row.getLong("assessments"),
                        judged == null ? 0L : judged.longValueExact()));
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's decisions", failure);
        }
        return rows;
    }

    @Override
    public List<ReasonRow> reasons(Connection snapshot, RiskDay day) {
        List<ReasonRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, REASONS, day); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                rows.add(new ReasonRow(RiskOperation.valueOf(row.getString(1)), ReasonCode.valueOf(row.getString(2)),
                        row.getLong(3)));
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's reason codes", failure);
        }
        return rows;
    }

    @Override
    public List<VersionRow> versions(Connection snapshot, RiskDay day) {
        List<VersionRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, VERSIONS, day); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                int version = row.getInt(2);
                rows.add(new VersionRow(RiskOperation.valueOf(row.getString(1)),
                        row.wasNull() ? OptionalInt.empty() : OptionalInt.of(version), row.getLong(3)));
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's pinned versions", failure);
        }
        return rows;
    }

    @Override
    public List<AlertRow> alerts(Connection snapshot, RiskDay day) {
        List<AlertRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, ALERTS, day); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                rows.add(new AlertRow(AlertKind.valueOf(row.getString(1)), AlertSeverity.valueOf(row.getString(2)),
                        AlertStatus.valueOf(row.getString(3)), row.getLong(4)));
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's alerts", failure);
        }
        return rows;
    }

    @Override
    public List<RuleRow> alertRules(Connection snapshot, RiskDay day) {
        List<RuleRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, ALERT_RULES, day); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                rows.add(new RuleRow(AlertKind.valueOf(row.getString(1)), row.getString(2), row.getLong(3)));
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's alert rules", failure);
        }
        return rows;
    }

    @Override
    public List<BornOnceRow> bornOnce(Connection snapshot, RiskDay day) {
        List<BornOnceRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, BORN_ONCE, day); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                rows.add(new BornOnceRow(AlertKind.valueOf(row.getString("kind")),
                        WindowKind.valueOf(row.getString("window_kind")), row.getLong("raised"), row.getLong("keys"),
                        row.getLong("held")));
            }
        } catch (SQLException failure) {
            throw failed("taking the born-once census", failure);
        }
        return rows;
    }

    @Override
    public List<CaseRow> casesOpened(Connection snapshot, RiskDay day) {
        List<CaseRow> rows = new ArrayList<>();
        try (PreparedStatement select = dayStatement(snapshot, CASES_OPENED, day);
                ResultSet row = select.executeQuery()) {
            while (row.next()) {
                rows.add(new CaseRow(CaseKind.valueOf(row.getString(1)), CaseStatus.valueOf(row.getString(2)),
                        row.getLong(3)));
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's cases", failure);
        }
        return rows;
    }

    @Override
    public List<DisposedRow> casesDisposed(Connection snapshot, RiskDay day) {
        List<DisposedRow> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(CASES_DISPOSED)) {
            select.setObject(1, OffsetDateTime.ofInstant(day.start(), ZoneOffset.UTC));
            select.setObject(2, OffsetDateTime.ofInstant(day.end(), ZoneOffset.UTC));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(new DisposedRow(CaseKind.valueOf(row.getString(1)), Disposition.valueOf(row.getString(2)),
                            row.getLong(3)));
                }
            }
        } catch (SQLException failure) {
            throw failed("grouping the day's dispositions", failure);
        }
        return rows;
    }

    @Override
    public List<OpenRow> openCases(Connection snapshot) {
        List<OpenRow> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(OPEN_CASES); ResultSet row = select.executeQuery()) {
            while (row.next()) {
                rows.add(new OpenRow(CaseKind.valueOf(row.getString("kind")), row.getLong("open"),
                        row.getLong("subjects"), row.getLong("over_one")));
            }
        } catch (SQLException failure) {
            throw failed("taking the one-open census", failure);
        }
        return rows;
    }

    @Override
    public FilingRow filings(Connection snapshot, Duration overdueAfter) {
        Objects.requireNonNull(overdueAfter, "overdueAfter");
        try (PreparedStatement select = snapshot.prepareStatement(FILINGS)) {
            select.setLong(1, overdueAfter.toSeconds());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return new FilingRow(row.getLong("filed"), row.getLong("due"), row.getLong("overdue"));
            }
        } catch (SQLException failure) {
            throw failed("taking the filing census", failure);
        }
    }

    @Override
    public List<UUID> monitoredBetween(Connection snapshot, UUID fromInclusive, UUID toExclusive) {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(MONITORED_BETWEEN)) {
            select.setObject(1, Objects.requireNonNull(fromInclusive, "fromInclusive"));
            select.setObject(2, Objects.requireNonNull(toExclusive, "toExclusive"));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    ids.add(row.getObject(1, UUID.class));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading the monitored range", failure);
        }
        return ids;
    }

    @Override
    public Optional<MonitoredTransaction> monitored(Connection snapshot, UUID journalEntryId) {
        try (PreparedStatement select = snapshot.prepareStatement(MONITORED_ONE)) {
            select.setObject(1, Objects.requireNonNull(journalEntryId, "journalEntryId"));
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(JdbcVelocityEvidence.monitored(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a monitored transaction", failure);
        }
    }

    @Override
    public Optional<RiskSignal> signal(Connection snapshot, RiskSignalId id) {
        try (PreparedStatement select = snapshot.prepareStatement(SIGNAL_ONE)) {
            select.setObject(1, Objects.requireNonNull(id, "id").value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(JdbcRiskSignalStore.read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a signal", failure);
        }
    }

    private static PreparedStatement dayStatement(Connection snapshot, String sql, RiskDay day) throws SQLException {
        Objects.requireNonNull(day, "day");
        PreparedStatement select = snapshot.prepareStatement(sql);
        try {
            select.setObject(1, day.fromId());
            select.setObject(2, day.toId());
            return select;
        } catch (SQLException failure) {
            select.close();
            throw failure;
        }
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
