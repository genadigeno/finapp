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
 * declared rail can carry. The beneficiary grant exchange joins this port with `P9-TSK-017`.
 */
public interface CorridorDirectory {

    /** Every corridor rail this build declares - configured adapter or not. */
    Set<DeclaredRail> declaredRails();

    /** The declared corridor rail {@code rail}, if this build declares it. */
    default Optional<DeclaredRail> declared(String rail) {
        Objects.requireNonNull(rail, "rail must not be null");
        return declaredRails().stream().filter(declared -> declared.rail().equals(rail)).findFirst();
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
