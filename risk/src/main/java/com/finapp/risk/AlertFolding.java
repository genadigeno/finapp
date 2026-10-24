package com.finapp.risk;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * The folding of a born alert into its subject's case (`P13-TSK-015` declares it; `P13-TSK-016` implements it over its
 * case tables; {@code PHASE_13_PLAN.md} sections 5, 7.2, 12.8; {@code RISK_LIFECYCLES.md} sections 4.5, 4.6;
 * ADR-0110 decision 3; {@code INV-RSK-06}) - <strong>the system's dedupe is {@code ATTACHED}, never
 * {@code SUPPRESSED}</strong>: an alert raised while its subject has an open case of its kind's
 * {@link AlertKind#caseKind} attaches to it; with none, it opens one and attaches.
 *
 * <p><strong>What `-016` implements</strong>, in the raising transaction (T-eval's or T-mon's), on the caller's unit of
 * work, after every birth of that transaction ({@link Alerts#raiseAll}): (R4) the subject's open case of the kind
 * {@code FOR UPDATE} if any - else {@code Cases.open} under the partial unique one-open per (subject, kind), a loser
 * finding the winner's case - then (R5) the alert {@code OPEN → ATTACHED} through the alert's own edge,
 * {@link RiskAlertStore#attach} (what {@link Alerts#attach} wraps for a case door: a folding holds the store, not
 * {@code Alerts}, so the composition has no cycle), the case's {@code revision} advanced and its event row; a case
 * disposed between the read and the lock opens a new case (OQ-6:
 * never re-opened). It answers {@link Folding#ATTACHED} with the case. It never suppresses, never writes a born alert's
 * facts, and is never called for an operational kind (which opens no case) nor for a raise that found its alert already
 * standing (that alert was folded, or queued, by its own birth). It also owes the backlog: every alert born
 * {@code OPEN} before it landed is queued in {@code risk_alert_open_queue}, oldest first, for it to fold.
 *
 * <p><strong>Until `-016` lands the composition wires {@link #NONE}</strong>: nothing is written, the alert stays
 * {@code OPEN} - queued and counted ({@code finapp.risk.alert{kind,severity}}) - and the answer is {@link Folding#QUEUED}.
 */
@FunctionalInterface
public interface AlertFolding {

    /** Folds nothing: the alert stays {@code OPEN}, queued until a case exists - the wiring until `P13-TSK-016`. */
    AlertFolding NONE = (unitOfWork, born) -> Folded.QUEUED;

    /** Folds {@code born} - an alert this transaction raised, {@code OPEN}, of a kind that opens a case. */
    Folded fold(Connection unitOfWork, RiskAlert born);

    /** What a folding did. */
    enum Folding {
        /** No case: the alert stays {@code OPEN} in the queue. */
        QUEUED,
        /** The alert attached to its subject's open case of the kind - found, or opened for it. */
        ATTACHED
    }

    /** The folding's answer: what it did and, for an attach, the case. */
    record Folded(Folding outcome, Optional<RiskCaseId> caseId) {

        /** Nothing folded. */
        public static final Folded QUEUED = new Folded(Folding.QUEUED, Optional.empty());

        public Folded {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(caseId, "caseId");
            if ((outcome == Folding.ATTACHED) != caseId.isPresent()) {
                throw new IllegalArgumentException("an attach names its case, and only an attach");
            }
        }

        /** Attached to {@code caseId}. */
        public static Folded attached(RiskCaseId caseId) {
            return new Folded(Folding.ATTACHED, Optional.of(Objects.requireNonNull(caseId, "caseId")));
        }
    }
}
