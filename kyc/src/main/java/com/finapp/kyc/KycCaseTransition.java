package com.finapp.kyc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The KYC case machine's transition table, each edge named for what drives it (`P17-TSK-126`, ADR-0155 decision 1;
 * {@code INV-LIFE-02}).
 *
 * <p><strong>This table is the machine.</strong> {@link KycCaseStatus#permittedTransitions()} is derived from it, the
 * aggregate's methods each apply one named edge, and {@code KycCaseLifecycleTest} sweeps every pair of statuses against it -
 * so an edge exists because a row here says what drives it, and a pair no row names is refused by the aggregate.
 *
 * <pre>
 * OPEN ──BEGIN_CHECKS──► CHECKS_IN_PROGRESS ──CHECKS_CLEAR──────────────────────────► READY_FOR_DECISION ──APPROVE──► APPROVED
 *                              │                                                         ▲             └──REJECT───► REJECTED
 *                              └──CHECKS_RAISE_REVIEW──► IN_REVIEW ──REVIEW_COMPLETE─────┘
 *                                                         │     ▲
 *                                  INFORMATION_REQUESTED  │     │  CUSTOMER_ANSWERED | REQUEST_EXPIRED
 *                                                         ▼     │
 *                                                    AWAITING_CUSTOMER
 * </pre>
 *
 * <p>{@code AWAITING_CUSTOMER} leads back to {@code IN_REVIEW} and nowhere else: nothing is ready or decided while the customer
 * holds the case, and a return re-asks readiness rather than skipping it ({@code INV-KYC-04}: no exit past an open review task).
 * Two rows share that edge on purpose - an answer and a lapse are different facts with one effect on the case.
 */
public enum KycCaseTransition {

    /** The first verification check was dispatched. */
    BEGIN_CHECKS(KycCaseStatus.OPEN, KycCaseStatus.CHECKS_IN_PROGRESS),

    /** Every check terminal and clear (and, for KYB, the ownership graph complete). */
    CHECKS_CLEAR(KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.READY_FOR_DECISION),

    /** A check hit, or stayed indeterminate past its retry budget: a person resolves it ({@code INV-KYC-04}). */
    CHECKS_RAISE_REVIEW(KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.IN_REVIEW),

    /** No review task is left open (and, for KYB, the ownership graph complete). */
    REVIEW_COMPLETE(KycCaseStatus.IN_REVIEW, KycCaseStatus.READY_FOR_DECISION),

    /** A reviewer asked the customer for more information (ADR-0155 decision 3). */
    INFORMATION_REQUESTED(KycCaseStatus.IN_REVIEW, KycCaseStatus.AWAITING_CUSTOMER),

    /** The customer answered the open request (ADR-0155 decision 6). */
    CUSTOMER_ANSWERED(KycCaseStatus.AWAITING_CUSTOMER, KycCaseStatus.IN_REVIEW),

    /** The open request's deadline passed on the database clock (ADR-0155 decision 7). */
    REQUEST_EXPIRED(KycCaseStatus.AWAITING_CUSTOMER, KycCaseStatus.IN_REVIEW),

    /** The decision: the party may be onboarded. Terminal ({@code INV-LIFE-04}). */
    APPROVE(KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.APPROVED),

    /** The decision: the party may not be onboarded. Terminal ({@code INV-LIFE-04}). */
    REJECT(KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.REJECTED);

    private final KycCaseStatus from;
    private final KycCaseStatus to;

    KycCaseTransition(KycCaseStatus from, KycCaseStatus to) {
        this.from = from;
        this.to = to;
    }

    /** The one status this edge leaves. */
    public KycCaseStatus from() {
        return from;
    }

    /** The status this edge reaches. */
    public KycCaseStatus to() {
        return to;
    }

    /** Every status some named edge reaches from {@code status}: the machine's permitted set, derived. */
    static Set<KycCaseStatus> targetsFrom(KycCaseStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        return Arrays.stream(values())
                .filter(transition -> transition.from == status)
                .map(KycCaseTransition::to)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(KycCaseStatus.class)));
    }
}
