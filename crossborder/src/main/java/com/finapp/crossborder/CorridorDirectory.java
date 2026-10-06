package com.finapp.crossborder;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The corridor rails the running build declares (`P9-TSK-015`; PHASE_9_PLAN.md section 3's ports
 * table) - declared by crossborder, implemented in {@code app} over payments' declared rails and their
 * {@code CorridorDeclaration}s, handed in as a required constructor parameter (ADR-0064 section 3).
 * crossborder never names a rail it was not told exists: a corridor policy may name only these rails,
 * each covering the corridor's (country, destination currency), and discovery offers only corridors a
 * declared rail can carry. Since `P9-TSK-017` it also exchanges a customer's beneficiary grant with a
 * declared rail's provider - called with no connection held - and says which declared rails are operable
 * in this deployment (an adapter configured to exchange through).
 */
public interface CorridorDirectory {

    /** Every corridor rail this build declares - configured adapter or not. */
    Set<DeclaredRail> declaredRails();

    /** The declared corridor rail {@code rail}, if this build declares it. */
    default Optional<DeclaredRail> declared(String rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        return declaredRails().stream().filter(declared -> declared.rail().equals(rail)).findFirst();
    }

    /** Whether {@code rail} is operable here: declared, and its adapter configured. */
    default boolean operable(String rail) {
        return false;
    }

    /**
     * Exchanges the customer's single-use {@code grant} with {@code rail}'s provider under our
     * {@code reference} (the provider's idempotency key: the same reference answers the same exchange).
     * Never called holding a connection; the answer is total.
     */
    default Exchange exchange(String rail, String reference, String grant) {
        return new Exchange.Unavailable();
    }

    /** A grant exchange's answer, in crossborder's words. */
    sealed interface Exchange {

        /** The provider's opaque reference and what it attests - never an account identifier. */
        record Exchanged(
                String destinationReference,
                String suffix,
                BeneficiaryVocabulary.PayeeCheck payeeCheck,
                CountryCode country,
                CurrencyCode currency,
                BeneficiaryVocabulary.EntityType entityType)
                implements Exchange {
            public Exchanged {
                Objects.requireNonNull(destinationReference, "destinationReference must not be null");
                Objects.requireNonNull(suffix, "suffix must not be null");
                Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
                Objects.requireNonNull(country, "country must not be null");
                Objects.requireNonNull(currency, "currency must not be null");
                Objects.requireNonNull(entityType, "entityType must not be null");
            }

            @Override
            public String toString() {
                return "Exchanged[destination=<redacted>, suffix=" + suffix + ", payeeCheck=" + payeeCheck
                        + ", country=" + country + ", currency=" + currency + ", entityType=" + entityType + "]";
            }
        }

        /** The provider refused the grant - invalid, expired or already used; nothing was registered. */
        record Refused() implements Exchange {}

        /** No answer could be read - nothing sent, a timeout, an unreadable body, or no adapter. */
        record Unavailable() implements Exchange {}
    }

    /** One declared corridor rail and where it delivers. */
    record DeclaredRail(String rail, Set<Coverage> coverage) {
        public DeclaredRail {
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(coverage, "coverage must not be null");
            coverage = Set.copyOf(coverage);
        }

        /** Whether the rail delivers {@code currency} in {@code country}. */
        public boolean covers(CountryCode country, CurrencyCode currency) {
            return coverage.contains(new Coverage(country, currency));
        }
    }

    /** One delivered (country, currency). */
    record Coverage(CountryCode country, CurrencyCode currency) {
        public Coverage {
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }
}
