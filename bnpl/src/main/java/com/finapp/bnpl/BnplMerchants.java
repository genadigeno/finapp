package com.finapp.bnpl;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The merchant, as bnpl needs it - a port {@code app} implements over {@code merchant} (`P12-TSK-006`; OQ-8, ADR-0101
 * decision 6's port table, ADR-0103 decision 7, ADR-0105 decision 3, PHASE_12_PLAN.md sections 3.3 and 12.5;
 * {@code INV-MER-03}, {@code INV-MER-04}, {@code INV-BNPL-04}). bnpl has no build edge to {@code merchant}
 * ({@code BnplModuleIsolationTest}); {@code app}'s {@code MerchantBnplMerchants} adapts it. The merchant is referenced by
 * value - its id as a {@code UUID}, the way checkout's session carries it (ADR-0029). Every method runs on the caller's
 * unit of work: bnpl's own transaction.
 *
 * <p><strong>One fee arithmetic.</strong> The split and the returned fee are merchant's {@code FeeCalculation} under the
 * purchase's pinned version - the card capture's arithmetic - never a BNPL copy. Unconsumed until the application
 * (`P12-TSK-010`), the purchase (`-013`) and the refund (`-021`).
 */
public interface BnplMerchants {

    /** Whether a merchant may offer a BNPL product now: {@link #ENABLED}, or the first failing condition. */
    enum Standing {

        /** Trading ({@code ACTIVE}), BNPL-enabled by four eyes, and the product priced by a version in force. */
        ENABLED,

        /** Unknown, suspended or closed. */
        NOT_TRADING,

        /** Trading, but with no active BNPL enablement. */
        NOT_ENABLED,

        /** Enabled, but the product has no BNPL fee schedule, no version in force, or one that retains its fee. */
        NOT_PRICED
    }

    /**
     * The merchant's standing for {@code product}, its merchant row and its active enablement held {@code FOR SHARE} to
     * the caller's commit ({@code CheckoutSessions.requireTrading}'s read) - so a suspension, a close, a withdrawal or a
     * re-pricing either waits for the caller or committed before it was read.
     */
    Standing standing(Connection unitOfWork, UUID merchantRef, BnplProduct product);

    /**
     * Whether a sale of {@code gross} covers the merchant's fee for {@code product} now (`P12-TSK-010`, the application's
     * eligibility read; ADR-0058's rule) - judged as {@link #pinFee} judges it, under the same share locks, and with
     * nothing written: no pin. {@code false} when the merchant may not offer the product now. A reading for the
     * application door; the purchase's {@link #pinFee} re-judges.
     */
    boolean coversFee(Connection unitOfWork, UUID merchantRef, BnplProduct product, Money gross);

    /** The merchant's {@code MERCHANT_PAYABLE} ledger account in {@code currency}, unlocked, or empty. */
    Optional<LedgerAccountId> payableAccount(Connection unitOfWork, UUID merchantRef, CurrencyCode currency);

    /**
     * Prices one purchase and pins the version that priced it - born in the caller's (the purchase's) transaction, so a
     * rollback leaves no pin; the standing re-judged under its locks first; the split from one version read once.
     *
     * @throws NotOffered the merchant may not offer the product now
     * @throws BelowFee the fee meets or exceeds the gross ({@code SaleBelowFeeException}'s rule, ADR-0058)
     * @throws AlreadyPinned the purchase is already priced - never a second version
     */
    MerchantFeeSplit pinFee(
            Connection unitOfWork, UUID merchantRef, BnplProduct product, BnplPurchaseId purchaseId, Money gross);

    /**
     * The merchant fee a refund of {@code refunded} returns, {@code refundedBefore} having been refunded already - the
     * cumulative difference under the purchase's pinned version, which telescopes to exactly f across any full refund and
     * never exceeds it in any order (ADR-0105 decision 3; exact, since a BNPL refund's {@code refundedBefore} is
     * committed).
     *
     * @throws NotPinned no pin names the purchase
     * @throws IllegalArgumentException the refunds would exceed the gross
     */
    Money returnedFee(Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore, Money refunded);

    // ------------------------------------------------------------------ refusals

    /** The merchant may not offer the product now. */
    final class NotOffered extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final Standing standing;

        public NotOffered(Standing standing) {
            super("the merchant may not offer this BNPL product: " + standing.name());
            this.standing = standing;
        }

        public Standing standing() {
            return standing;
        }
    }

    /** The sale does not cover its fee. No amount in the message. */
    final class BelowFee extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public BelowFee() {
            super("the purchase does not cover the merchant's fee");
        }
    }

    /** The purchase is already priced. */
    final class AlreadyPinned extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public AlreadyPinned() {
            super("the BNPL purchase is already priced");
        }
    }

    /** No fee pin names the purchase. */
    final class NotPinned extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NotPinned() {
            super("no merchant fee pin names the BNPL purchase");
        }
    }
}
