package com.finapp.risk;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * An alert is born once per (subject, rule, window) (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.2 scenarios
 * 1, 2, 8, 12, 13.3; {@code INV-RSK-06}; F8; {@code DESIGN-023} D5) - <strong>the unique's truth re-proven by
 * query</strong>, not assumed from {@code risk_alert_born_once}: a dropped or altered constraint, a migration accident or
 * a hand-made row would otherwise pass in silence. For the alerts raised on a day, per kind and window kind: how many,
 * over how many keys, and how many rows any day holds of those keys - every row past the first of a key is an alert born
 * twice. The {@link AlertKind#AML} rows are `-020`'s census of AML alerts per window. One snapshot; nothing written.
 */
public final class AlertBornOnceCensus {

    /** One kind and window kind of the day. */
    public record Row(AlertKind kind, WindowKind window, long raised, long keys, long held) {
        public Row {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(window, "window");
        }

        /** The rows past each key's first - alerts born twice. */
        public long duplicates() {
            return Math.max(0, held - keys);
        }
    }

    /** The day's census. */
    public record Result(RiskDay day, List<Row> rows) {
        public Result {
            Objects.requireNonNull(day, "day");
            rows = List.copyOf(rows);
        }

        public long raised() {
            return rows.stream().mapToLong(Row::raised).sum();
        }

        public long duplicates() {
            return rows.stream().mapToLong(Row::duplicates).sum();
        }

        public ProofVerdict verdict() {
            return ProofVerdict.of(raised(), duplicates());
        }
    }

    private final RiskProofReads reads;

    public AlertBornOnceCensus(RiskProofReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** {@code day}'s census on {@code snapshot}. */
    public Result run(Connection snapshot, RiskDay day) {
        RiskProofs.requireSnapshot(snapshot);
        Objects.requireNonNull(day, "day");
        return new Result(day, reads.bornOnce(snapshot, day).stream()
                .map(row -> new Row(row.kind(), row.window(), row.raised(), row.keys(), row.rows())).toList());
    }
}
