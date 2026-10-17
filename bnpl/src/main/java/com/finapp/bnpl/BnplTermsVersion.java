package com.finapp.bnpl;

import java.util.Objects;

/**
 * A BNPL terms version - its money-free machine row and its whole, immutable term set, one id for both
 * (`P12-TSK-004`; {@code bnpl V002}'s {@code bnpl_terms_version} and {@code bnpl_terms}, lending {@code V003}'s split
 * along the money line). An agreement pins it by id for life ({@code INV-HIST-04}).
 */
public record BnplTermsVersion(BnplTermsStore.VersionRow row, BnplTerms terms) {

    public BnplTermsVersion {
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(terms, "terms must not be null");
        if (row.product() != terms.product()) {
            throw new IllegalStateException("a version's term set is its own product's");
        }
    }

    /** The version's id. */
    public BnplTermsVersionId id() {
        return row.id();
    }

    @Override
    public String toString() {
        return "BnplTermsVersion[" + row + "]";
    }
}
