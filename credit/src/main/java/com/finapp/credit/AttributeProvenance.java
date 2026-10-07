package com.finapp.credit;

import java.util.Objects;

/**
 * Where a credit attribute came from (`P10-TSK-005`; PHASE_10_PLAN.md section 12.2,
 * {@code INV-CRD-07}): every attribute carries it, so an explanation names the source of every
 * figure and a replay reads the stored attribute rather than re-normalising it.
 *
 * <p>Sealed, and growing with the sources: a provider's normalised answer now; the stored record's
 * id when collection lands (`-006`), the applicant's declaration and the platform's ports later.
 */
public sealed interface AttributeProvenance permits AttributeProvenance.Provider {

    /**
     * Normalised from a provider's answer.
     *
     * @param kind the source kind
     * @param providerCode the provider's code - its declaration's and its evidence's
     * @param normaliserVersion the adapter's normaliser version - a change to it never moves a past
     *     decision, because replay reads the stored attributes ({@code INV-CRD-01})
     */
    record Provider(CreditSourceKind kind, String providerCode, int normaliserVersion) implements AttributeProvenance {
        public Provider {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(providerCode, "providerCode");
            if (normaliserVersion < 1) {
                throw new IllegalArgumentException("a normaliser version counts from 1");
            }
        }
    }
}
