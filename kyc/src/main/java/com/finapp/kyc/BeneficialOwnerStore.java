package com.finapp.kyc;

import java.util.List;

/**
 * Storage for a KYB case's beneficial-ownership graph (`P2-TSK-015`).
 *
 * <p>A port, on ADR-0033's recorded reasoning; every write joins the caller's transaction, so
 * the audit record of a declaration commits or rolls back with the row.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface BeneficialOwnerStore<T> {

    /**
     * What a declaration attempt came to. {@code OwnerDeclaration} maps these; `P2-TSK-016`'s
     * endpoint maps them again onto HTTP.
     */
    enum Declared {
        /** The row was written; the graph grew by this owner. */
        DECLARED,
        /**
         * This party is already on this case's graph — the idempotent answer a lost-response
         * retry needs ({@code UNIQUE (case_id, owner_party_id)}'s semantics).
         */
        ALREADY_DECLARED,
        /**
         * The case is not accepting owners: missing, not a {@code KYB} case, or already at
         * {@code READY_FOR_DECISION} or terminal — the <strong>frozen set</strong>. From RFD on,
         * the graph is part of what the decision rests on ({@code INV-KYC-02}), and a set that
         * could still grow would make the ownership predicate's answer perishable.
         */
        CASE_NOT_ACCEPTING,
        /**
         * The declared stake would take the case's total past 10000 basis points. A graph
         * claiming more than the whole organisation is invalid evidence — the
         * value-is-neither-created-nor-destroyed instinct applied to ownership.
         */
        STAKE_EXCEEDS_WHOLE
    }

    /**
     * Declares an owner, under the case-row lock that makes the answer trustworthy.
     *
     * <p><strong>The lock-then-look protocol, and why a predicate alone is not enough</strong>
     * (`P2-TSK-015`'s distributed edge): the declaration inserts into {@code beneficial_owner}
     * while the readiness transition updates {@code kyc_case} with a {@code NOT EXISTS} over
     * that table — different rows, so under {@code READ COMMITTED} the two exhibit write skew:
     * a blocked {@code UPDATE} re-evaluates its quals on resume, but its subqueries re-run
     * against the <em>statement's original snapshot</em> and cannot see an owner committed
     * after the statement began. So both sides take {@code SELECT … FOR UPDATE} on the case row
     * <em>first</em> and act in a fresh statement afterwards: whichever side locks second
     * begins its real statement after the other committed, and sees it. The declaration's
     * status check, the duplicate check and the stake sum all run under that lock, which is
     * what makes them checks rather than guesses.
     */
    Declared declare(T unitOfWork, BeneficialOwner owner);

    /**
     * The KYB cases whose graphs pin this case as an owner's verification.
     *
     * <p>The re-route read: when a verification case reaches its terminal decision, the parents
     * waiting on it must be re-assessed ({@code CaseAssessment.reRouteParentsOf}). Structurally
     * at most depth one — V008's composite FK admits only {@code KYC}-kind cases here, and only
     * {@code KYB}-kind cases as parents, so a parent is never itself somebody's verification.
     */
    List<KycCaseId> parentCasesOf(T unitOfWork, KycCaseId verificationCaseId);

    /**
     * The case's declared owners with each verification's <em>current</em> status
     * (`P2-TSK-016`) — the read behind both KYB views.
     *
     * <p>One join, one snapshot: the owner rows and the verification statuses come from the
     * same statement, so a view can never pair an owner with a status another transaction has
     * already replaced. The status is served raw here; <strong>shaping is the caller's</strong>
     * — the acting person's view collapses it to a pending boolean (tipping-off,
     * {@code INV-IDN-07}'s reasoning), the reviewer's view shows it whole, and a shaped store
     * would force the reviewer to ask a second question.
     */
    List<DeclaredOwner> ownersOf(T unitOfWork, KycCaseId caseId);

    /** An owner row paired with its verification case's status, from one snapshot. */
    record DeclaredOwner(BeneficialOwner owner, KycCaseStatus verificationStatus) {
        public DeclaredOwner {
            java.util.Objects.requireNonNull(owner, "owner must not be null");
            java.util.Objects.requireNonNull(
                    verificationStatus, "verificationStatus must not be null");
        }
    }
}
