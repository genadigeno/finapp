package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A case is open at most once per (subject, kind) (`P13-TSK-023`; {@code PHASE_13_PLAN.md} section 13.3;
 * {@code INV-RSK-06}; ADR-0110 decision 2; F8; {@code DESIGN-023} D5) - the partial unique {@code risk_case_one_open}'s
 * truth re-proven by query at the snapshot: per kind, the open cases ({@code OPEN}, {@code IN_INVESTIGATION},
 * {@code ESCALATED}), over how many subjects, and how many subjects hold two or more. One snapshot; nothing written.
 */
public final class CaseOneOpenCensus {

    /** One kind's open cases. */
    public record Row(CaseKind kind, long open, long subjects, long subjectsOverOne) {
        public Row {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The census at {@code asOf}. */
    public record Result(Instant asOf, List<Row> rows) {
        public Result {
            Objects.requireNonNull(asOf, "asOf");
            rows = List.copyOf(rows);
        }

        public long open() {
            return rows.stream().mapToLong(Row::open).sum();
        }

        public long subjectsOverOne() {
            return rows.stream().mapToLong(Row::subjectsOverOne).sum();
        }

        public ProofVerdict verdict() {
            return ProofVerdict.of(open(), subjectsOverOne());
        }
    }

    private final RiskProofReads reads;

    public CaseOneOpenCensus(RiskProofReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** The census on {@code snapshot}. */
    public Result run(Connection snapshot) {
        RiskProofs.requireSnapshot(snapshot);
        Instant asOf = reads.databaseNow(snapshot);
        return new Result(asOf, reads.openCases(snapshot).stream()
                .map(row -> new Row(row.kind(), row.open(), row.subjects(), row.subjectsOverOne())).toList());
    }
}
