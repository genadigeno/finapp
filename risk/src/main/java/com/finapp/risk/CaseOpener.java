package com.finapp.risk;

import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * The case a decision calls for, opened in T-eval's own transaction (`P13-TSK-004` declares it; `P13-TSK-016`
 * implements it; {@code PHASE_13_PLAN.md} section 12.3, OQ-2; ADR-0108 decision 3) - a {@code REVIEW} refuses now and
 * opens a case of the operation's kind with the assessment as its first evidence; a {@code HOLD} opens one beside the
 * held withdrawal. The case row is risk's and takes no money lock (R4 after R3), so opening it inside the caller's
 * transaction adds no cycle.
 *
 * <p><strong>Until `-016` lands the composition wires {@link #NONE}</strong>, which records nothing: no case is opened
 * for any decision, and the assessment itself - sealed, carrying its decision - is the record `-016`'s folding can
 * start from. A {@code DENY} opens a case "when the policy says so" (ADR-0108): the policy version carries no such
 * field today, so T-eval calls this for {@code REVIEW} and {@code HOLD} alone - the field and its call are `-016`'s.
 */
@FunctionalInterface
public interface CaseOpener {

    /** Opens nothing - the composition's wiring until `P13-TSK-016`, recorded as such. */
    CaseOpener NONE = (unitOfWork, decided) -> { };

    /** Opens (or folds into) the case {@code decided} calls for, on the caller's unit of work. */
    void open(Connection unitOfWork, Decided decided);

    /** What a case is opened from - identifiers and codes only. */
    record Decided(RiskAssessmentId assessment, RiskOperation operation, CommandRef commandRef, UUID party,
            RiskDecision decision) {
        public Decided {
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(commandRef, "commandRef");
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(decision, "decision");
        }
    }
}
