package com.finapp.merchant;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a merchant API key may do beyond the merchant's own reads and payouts (`P12-TSK-021`; ADR-0105 decision 9,
 * ADR-0052's issuance door extended; {@code INV-MER-01}). A scope is an <strong>insert-time fact</strong> of the key,
 * chosen by the operator who issues it and frozen for every role by {@code merchant V011}'s trigger - a key's powers
 * change only by issuing another and revoking this one. A key issued before {@code merchant V011} carries none, so it
 * fails closed: no credential gained a money-moving power by a schema change.
 *
 * <p>The schema {@code CHECK} is generated from {@link #sqlArray()}; {@code MerchantApiKeyMigrationTest} fails the build
 * if this enum and {@code V011} disagree.
 */
public enum MerchantApiKeyScope {

    /**
     * Refund a BNPL purchase made at the merchant - {@code POST /v1/merchant/bnpl-purchases/{id}/refunds}, the platform's
     * first merchant-key refund door - <em>and accept a BNPL dispute</em>, {@code POST
     * /v1/merchant/bnpl-disputes/{id}/response} with {@code ACCEPT}, which is a refund of the disputed amount at once (the
     * Phase 12 -> 13 gate's A5-F1/F6; contesting needs no scope). A refund moves money out of the merchant's payable, so a
     * read-and-payout key must not reach it by default.
     *
     * <p><strong>Issuing it is a money-operating act</strong> (A5-F1): the operator who issues a key carrying this scope
     * sees its secret once, so issuance asks {@code PAYMENT_REFUND} beside {@code MERCHANT_ADMINISTER} - an operator
     * holding only the latter could otherwise mint their own refund power - and both refund doors refuse a key whose
     * issuer is the purchase's own party, or one the platform cannot place in a party ({@code bnpl.SelfDealingRefused}).
     */
    BNPL_REFUND;

    /** Every scope as a SQL text array literal, for the {@code CHECK}. */
    public static String sqlArray() {
        return Arrays.stream(values())
                .map(scope -> "'" + scope.name() + "'")
                .collect(Collectors.joining(", ", "ARRAY[", "]::text[]"));
    }
}
