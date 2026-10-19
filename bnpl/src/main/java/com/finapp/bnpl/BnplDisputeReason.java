package com.finapp.bnpl;

/**
 * Why a customer contests a BNPL purchase - the category beside the reason they write (`P12-TSK-023`; ADR-0106 decision 1;
 * {@code bnpl V015}'s {@code bnpl_dispute_reason_category_is_known}). The merchant sees the category, never the text.
 */
public enum BnplDisputeReason {

    /** The goods or the service never arrived. */
    NOT_RECEIVED,

    /** What arrived is not what was sold. */
    NOT_AS_DESCRIBED,

    /** The goods went back and the merchant has not refunded them. */
    RETURNED_NOT_REFUNDED,

    /** Anything else - the text says what. */
    OTHER
}
