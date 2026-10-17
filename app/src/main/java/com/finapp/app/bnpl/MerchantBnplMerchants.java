package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.MerchantFeeSplit;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeAssessment;
import com.finapp.merchant.FeeCurrencyMismatchException;
import com.finapp.merchant.MerchantBnplProduct;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.SaleBelowFeeException;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplMerchants} over merchant's {@link BnplPricing} and the ledger's account store (`P12-TSK-006`; the
 * crossborder, lending and {@code LendingBnplCapital} port precedent): the standing judged under the merchant row's and
 * the enablement's {@code FOR SHARE} on bnpl's connection; the payable resolved by owner, purpose and currency
 * ({@code CheckoutPaymentParticipants}' read); the pin and the split through {@link BnplPricing#pin} - merchant's
 * {@code FeeCalculation}, one arithmetic - and the returned fee through {@link BnplPricing#returnedFee}. The products
 * cross by name ({@code BnplVocabularyTest} holds merchant's names to bnpl's). merchant's refusals become the port's; a
 * merchant reference that is not a merchant identifier is a merchant that is not trading - one answer, no oracle.
 */
@RequiredArgsConstructor
final class MerchantBnplMerchants implements BnplMerchants {

    @NonNull private final BnplPricing pricing;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;

    @Override
    public Standing standing(Connection unitOfWork, UUID merchantRef, BnplProduct product) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Optional<MerchantId> merchant = merchant(merchantRef);
        if (merchant.isEmpty()) {
            return Standing.NOT_TRADING;
        }
        return Standing.valueOf(pricing.standing(unitOfWork, merchant.get(), product(product)).name());
    }

    @Override
    public Optional<LedgerAccountId> payableAccount(Connection unitOfWork, UUID merchantRef, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantRef, "merchantRef must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        return ledgerAccounts.findOwned(unitOfWork, merchantRef, AccountPurpose.MERCHANT_PAYABLE, currency)
                .map(LedgerAccount::id);
    }

    @Override
    public MerchantFeeSplit pinFee(
            Connection unitOfWork, UUID merchantRef, BnplProduct product, BnplPurchaseId purchaseId, Money gross) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purchaseId, "purchaseId must not be null");
        Objects.requireNonNull(gross, "gross must not be null");
        MerchantId merchant = merchant(merchantRef).orElseThrow(() -> new NotOffered(Standing.NOT_TRADING));
        try {
            return split(pricing.pin(unitOfWork, merchant, product(product), purchaseId.value(), gross,
                    SecurityContext.require()));
        } catch (BnplPricing.NotOffered refused) {
            throw new NotOffered(Standing.valueOf(refused.standing().name()));
        } catch (FeeCurrencyMismatchException mismatch) {
            // A gross the schedule does not price: the product is not priced for this money.
            throw new NotOffered(Standing.NOT_PRICED);
        } catch (SaleBelowFeeException below) {
            throw new BelowFee();
        } catch (BnplPricing.AlreadyPinned pinned) {
            throw new AlreadyPinned();
        }
    }

    @Override
    public Money returnedFee(Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore, Money refunded) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purchaseId, "purchaseId must not be null");
        try {
            return pricing.returnedFee(unitOfWork, purchaseId.value(), refundedBefore, refunded);
        } catch (BnplPricing.NotPinned unpinned) {
            throw new NotPinned();
        }
    }

    /** merchant's assessment as bnpl's split - the conservation re-asserted by {@link MerchantFeeSplit} itself. */
    static MerchantFeeSplit split(FeeAssessment assessment) {
        return new MerchantFeeSplit(assessment.gross(), assessment.fee(), assessment.net(),
                assessment.version().value());
    }

    /** bnpl's product by merchant's name for it. */
    static MerchantBnplProduct product(BnplProduct product) {
        Objects.requireNonNull(product, "product must not be null");
        return MerchantBnplProduct.valueOf(product.name());
    }

    private static Optional<MerchantId> merchant(UUID merchantRef) {
        Objects.requireNonNull(merchantRef, "merchantRef must not be null");
        try {
            return Optional.of(MerchantId.of(merchantRef));
        } catch (IllegalArgumentException notAMerchantId) {
            return Optional.empty();
        }
    }
}
