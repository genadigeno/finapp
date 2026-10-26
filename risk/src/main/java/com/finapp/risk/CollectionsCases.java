package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A collections case opened on default - the thin slice of collections case management (`P13-TSK-025`; owner decision
 * C3; {@code PHASE_13_PLAN.md} sections 3.1, 12.8; ADR-0110 decision 9; {@code INV-RSK-06}, {@code INV-RSK-02}).
 *
 * <p><strong>The fact.</strong> `-005` records lending's {@code LoanDefaulted} and bnpl's {@code BnplDefaulted} as their
 * born-once signals - {@link SignalKind#LOAN_DEFAULTED}, {@link SignalKind#BNPL_DEFAULTED}: the obligation's kind is the
 * signal's kind, its id the {@link SubjectKind#CREDIT_AGREEMENT} subject (the entry's "{@code OBLIGATION_DEFAULTED}
 * carrying the obligation's kind and id", as built). Each such signal raises, in T-sig's own transaction, one
 * {@link AlertKind#OBLIGATION_DEFAULTED} alert ({@link #alertFor}): about the agreement, its own code as its rule and no
 * policy (a default is a fact, not a policy's finding - it alerts whether or not a {@code MONITORING} version is
 * active), its evidence the signal alone, on the {@code D1} window of the default's business date. <strong>Born once
 * per default of an obligation</strong> - (agreement, {@code OBLIGATION_DEFAULTED}, business date) is the alert's
 * unique: a duplicate delivery, a replay or the same default re-delivered under another id is that one row; an
 * agreement belongs to one party for ever, so this is the entry's "once per (party, obligation)", and a re-default
 * after a cure (lending's A8 - {@code LoanDefaultCleared}, then a later {@code LoanDefaulted}) is a second default and
 * a second alert.
 *
 * <p><strong>The case.</strong> The raise folds the alert ({@link Cases#fold}) into <em>the agreement's party's</em>
 * open {@link CaseKind#COLLECTIONS} case, or opens one - one open per (party, {@code COLLECTIONS}) by
 * {@code risk_case_one_open}: a second default of one party attaches. The kind's rules are {@link Cases}' and
 * {@link DispositionRules}': its subject a {@code PARTY}, never opened by hand, disposed {@code CLOSED_NO_ACTION} or
 * {@code REFERRED} only - facts, never an action ({@link ActionRules} admits none), never a money effect: nothing here
 * or in the case writes lending's, bnpl's or the ledger's rows, and neither module changes (contact strategy, promises
 * to pay, placement, agencies, write-off and provisioning are Phase 14's and a later collections context's).
 *
 * <p><strong>The party first.</strong> The agreement's party is placed ({@link RiskSubjectParties#accountablePartyOf})
 * <em>before</em> the alert is raised: a borrower that cannot be placed refuses the delivery before anything is born,
 * announced or counted - only {@link CollectionsObserver#unplaced} - so a stalled partition's every retry leaves no
 * trace but its own meter ({@code finapp.risk.alert} never climbs for an alert that does not exist). {@link Cases#fold}
 * places it again under the case's lock, the kind's rule for every folding (the drain's included).
 *
 * <p>The raise runs on the caller's unit of work under the acting principal the handler entered (the platform's: an
 * inbox delivery has no caller). Nothing here logs or reads a clock.
 */
public final class CollectionsCases implements SignalAlerts {

    /** The signals that record a default - each raises the default's alert. */
    public static final Set<SignalKind> DEFAULTS = EnumSet.of(SignalKind.LOAN_DEFAULTED, SignalKind.BNPL_DEFAULTED);

    /** A default's severity - a credit fact to be worked within the kind's SLA, not a seizure (owner item). */
    public static final AlertSeverity SEVERITY = AlertSeverity.MEDIUM;

    private final AlertRaiser raiser;
    private final RiskSubjectParties parties;
    private final CollectionsObserver observer;

    public CollectionsCases(AlertRaiser raiser, RiskSubjectParties parties, CollectionsObserver observer) {
        this.raiser = Objects.requireNonNull(raiser, "raiser");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public boolean raises(SignalKind kind) {
        return DEFAULTS.contains(Objects.requireNonNull(kind, "kind"));
    }

    /**
     * Raises {@code standing}'s alert - after its agreement's party is placed.
     *
     * @throws IllegalStateException the agreement's party cannot be placed - counted
     *     {@link CollectionsObserver#unplaced}, nothing raised: the caller's transaction (T-sig's) is refused whole
     */
    @Override
    public void raise(Connection unitOfWork, RiskSignal standing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        NewAlert alert = alertFor(standing);
        if (parties.accountablePartyOf(unitOfWork, standing.subject()).isEmpty()) {
            observer.unplaced();
            throw new IllegalStateException("a defaulted agreement's party cannot be placed: no collections case opens"
                    + " without one");
        }
        raiser.raiseAll(unitOfWork, List.of(alert));
    }

    /**
     * The alert {@code standing} - a recorded default - calls for: {@link AlertKind#OBLIGATION_DEFAULTED} about its
     * agreement, the {@code D1} window of its business date (UTC midnight, the windows' anchor), the signal its one
     * reference, the producing flow's correlation, caused by the default event.
     *
     * @throws IllegalArgumentException a signal that records no default - a mapping or a producer's contract defect,
     *     refused so the delivery stalls rather than alerting on a guess
     */
    public static NewAlert alertFor(RiskSignal standing) {
        Objects.requireNonNull(standing, "standing");
        if (!DEFAULTS.contains(standing.kind())) {
            throw new IllegalArgumentException(standing.kind() + " records no default");
        }
        if (!standing.attributes().get("defaulted").filter("true"::equals).isPresent()) {
            throw new IllegalArgumentException("a " + standing.kind() + " signal records a default flag set");
        }
        LocalDate businessDate = LocalDate.parse(standing.attributes().get("business_date").orElseThrow(() ->
                new IllegalArgumentException("a " + standing.kind() + " signal carries its business date")));
        return NewAlert.ofFact(AlertKind.OBLIGATION_DEFAULTED, SEVERITY, standing.subject(),
                AlertWindow.containing(WindowKind.D1, businessDate.atStartOfDay(ZoneOffset.UTC).toInstant()),
                standing.id(), standing.source().correlation(),
                CausationId.of(standing.source().id().value().toString()));
    }
}
