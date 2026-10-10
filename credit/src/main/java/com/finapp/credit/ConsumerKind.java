package com.finapp.credit;

/**
 * What kind of thing takes up an approved credit decision (`P11-TSK-001`; ADR-0091; credit {@code V021}'s
 * {@code credit_decision_consumption_kind_is_known} CHECK holds the same closed set).
 *
 * <p>Phase 11 has one consumer: the loan or credit line agreement lending births when a customer accepts an offer. A
 * buy-now-pay-later plan is reserved for Phase 12 and is deliberately <strong>not</strong> a member yet - adding it is a
 * change to this enum and to the CHECK in one migration, never a free-text kind.
 */
public enum ConsumerKind {

    /** A loan or credit line agreement (lending, Phase 11) - the agreement's id is the consumer's reference. */
    LOAN_AGREEMENT
}
