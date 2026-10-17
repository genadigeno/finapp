package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProductBounds;
import com.finapp.credit.CreditProduct;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * bnpl's {@link BnplProductBounds} over credit's published products (`P12-TSK-004`; A16, lending's
 * {@code CreditLoanProductBounds} precedent): a BNPL product's purchase amount bounds and admitted terms are its credit
 * counterpart's - the {@link CreditProduct} of the same name, refused unless it is of credit's BNPL family - so the terms
 * door judges against the one truth a decision is requested within. {@code BnplVocabularyTest} holds the two vocabularies
 * to one shape; {@code CreditBnplProductBoundsTest} holds this adapter to credit's figures.
 */
final class CreditBnplProductBounds implements BnplProductBounds {

    @Override
    public Bounds of(BnplProduct product) {
        CreditProduct credit = CreditProduct.valueOf(Objects.requireNonNull(product, "product").name());
        if (credit.family() != CreditProduct.Family.BNPL) {
            throw new IllegalStateException(product + " maps onto a " + credit.family() + " product - bnpl prices only"
                    + " credit's BNPL family");
        }
        List<Integer> terms = credit.termBounds().map(CreditBnplProductBounds::admitted).orElse(List.of());
        return new Bounds(credit.minimumAmount(), credit.maximumAmount(), terms);
    }

    /** Every term the bounds admit - the admitted set, or each month of a range. */
    private static List<Integer> admitted(CreditProduct.TermBounds bounds) {
        if (!bounds.admittedMonths().isEmpty()) {
            return bounds.admittedMonths();
        }
        return IntStream.rangeClosed(bounds.minimumMonths(), bounds.maximumMonths()).boxed().toList();
    }
}
