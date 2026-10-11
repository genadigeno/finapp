package com.finapp.app.lending;

import com.finapp.credit.CreditProduct;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanProductBounds;
import java.util.Optional;

/**
 * Lending's {@link LoanProductBounds} over credit's published products (`P11-TSK-006`; A16, the {@code crossborder}
 * precedent): a loan product's amount and term bounds are its credit counterpart's - the {@link CreditProduct} of the
 * same name - so the amortisation judgement at the terms door reads the one truth a decision is requested within.
 * {@code LendingVocabularyTest} holds the two enums to one bijection; a revolving product, which credit gives no term,
 * has no amortisation bounds.
 */
final class CreditLoanProductBounds implements LoanProductBounds {

    @Override
    public Optional<Bounds> amortisationBounds(LoanProduct product) {
        if (product.kind() != LoanKind.INSTALMENT) {
            return Optional.empty();
        }
        CreditProduct credit = CreditProduct.valueOf(product.name());
        CreditProduct.TermBounds terms = credit.termBounds().orElseThrow(() -> new IllegalStateException(
                "an instalment product's credit counterpart declares its term bounds"));
        return Optional.of(new Bounds(
                credit.minimumAmount(), credit.maximumAmount(), terms.minimumMonths(), terms.maximumMonths()));
    }
}
