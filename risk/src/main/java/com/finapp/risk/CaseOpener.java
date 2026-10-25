package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The case a decision calls for, opened in T-eval's own transaction (`P13-TSK-004` declares it; `P13-TSK-016`
 * implements it; {@code PHASE_13_PLAN.md} section 12.3, OQ-2; ADR-0108 decision 3) - a {@code REVIEW} refuses now and
 * opens a case of the operation's kind with the assessment as its first evidence; a {@code HOLD} opens one beside the
 * held withdrawal. The case row is risk's and takes no money lock (R4 after R3), so opening it inside the caller's
 * transaction adds no cycle.
 *
 * <p><strong>As built by `P13-TSK-016`</strong> ({@code DESIGN-016} D5): the composition wires {@code Cases::openFor} -
 * the party's open case of {@link Decided#kind()} locked (R4) or opened under the one-open unique, the assessment its
 * opening or an attached step. The kind is T-eval's to name: {@link CaseKind#ATO} when the sealed hits show the
 * account-takeover anchors ({@link AtoSequence#anchorsShown} - the predicate that raises the {@code ACCOUNT_TAKEOVER}
 * alert, so an anchor rule's {@code HOLD} and its alert are <em>one</em> case), else {@link CaseKind#FRAUD}. A failure
 * aborts the caller: a {@code REVIEW} or {@code HOLD} never commits without its case. A {@code DENY} opens a case "when
 * the policy says so" (ADR-0108): no policy version carries such a field, so T-eval calls this for {@code REVIEW} and
 * {@code HOLD} alone.
 */
@FunctionalInterface
public interface CaseOpener {

    /** Opens nothing - the suites' wiring (the composition's until `P13-TSK-016`). */
    CaseOpener NONE = (unitOfWork, decided) -> { };

    /** Opens (or folds into) the case {@code decided} calls for, on the caller's unit of work. */
    void open(Connection unitOfWork, Decided decided);

    /**
     * What a case is opened from - identifiers and codes only: the sealed assessment, its operation, command and
     * decision, the judged party, the case's kind and the judged command's correlation.
     */
    record Decided(RiskAssessmentId assessment, RiskOperation operation, CommandRef commandRef, UUID party,
            RiskDecision decision, CaseKind kind, CorrelationId correlation) {
        public Decided {
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(commandRef, "commandRef");
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(correlation, "correlation");
            if (kind != CaseKind.FRAUD && kind != CaseKind.ATO) {
                throw new IllegalArgumentException("a decision opens a FRAUD or an ATO case, never " + kind);
            }
        }

        /** The case kind a decision's sealed hits call for: {@code ATO} when they show the account-takeover anchors. */
        public static CaseKind kindFor(RiskOperation operation, List<RuleHit> hits) {
            return AtoSequence.anchorsShown(operation, hits).isEmpty() ? CaseKind.FRAUD : CaseKind.ATO;
        }
    }
}
