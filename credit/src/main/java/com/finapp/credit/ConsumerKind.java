package com.finapp.credit;

import java.util.Objects;

/**
 * What kind of thing takes up an approved credit decision (`P11-TSK-001`, ADR-0091; `P12-TSK-001`, ADR-0102 point 6;
 * credit {@code V021}'s {@code credit_decision_consumption_kind_is_known} CHECK, widened by {@code V022}, holds the same
 * closed set).
 *
 * <p>Two consumers, one per product family: the loan or credit line agreement lending births when a customer accepts an
 * offer (Phase 11), and the BNPL agreement {@code bnpl} births when a customer accepts a BNPL offer at checkout (Phase 12).
 * A new kind is a change to this enum and to the CHECK in one migration, never a free-text kind.
 *
 * <p><strong>A kind takes up only its own family's decisions</strong> ({@link #takesUp}; `P12-TSK-001`'s design): a loan
 * agreement consuming a BNPL approval - or the reverse - is a caller's defect, refused by the port and by
 * {@code credit.consume_decision} before any lock, and never a consumption. One decision still funds at most one agreement
 * of any kind ({@code UNIQUE (decision_id)}, {@code INV-LND-06} generalised, {@code INV-BNPL-03}).
 */
public enum ConsumerKind {

    /** A loan or credit line agreement (lending, Phase 11) - the agreement's id is the consumer's reference. */
    LOAN_AGREEMENT(CreditProduct.Family.LENDING),

    /** A buy-now-pay-later agreement (bnpl, Phase 12) - the agreement's id is the consumer's reference. */
    BNPL_AGREEMENT(CreditProduct.Family.BNPL);

    private final CreditProduct.Family family;

    ConsumerKind(CreditProduct.Family family) {
        this.family = family;
    }

    /** Whether this kind of consumer may take up a decision for {@code product}. */
    public boolean takesUp(CreditProduct product) {
        return Objects.requireNonNull(product, "product").family() == family;
    }
}
