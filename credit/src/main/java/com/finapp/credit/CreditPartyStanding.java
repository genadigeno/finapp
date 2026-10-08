package com.finapp.credit;

import com.finapp.sharedkernel.money.CountryCode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The party facts a snapshot records (`P10-TSK-008`; ADR-0084 section 3) - the applicant's age and country of
 * residence, read on the caller's unit of work and answered with the answering implementation's version - and, since
 * `P10-TSK-014`, the party's standing.
 *
 * <p>An empty fact is recorded {@code ABSENT}, never defaulted: the platform holds neither fact today (unresolved
 * question #13), and the policy reasons about the absence.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditPartyStanding<T> {

    /** The party's facts as the platform holds them now. */
    PartyFacts facts(T unitOfWork, UUID partyId);

    /**
     * Whether the party is in good standing to apply for credit now (`P10-TSK-014`): a verified customer - {@code ACTIVE},
     * which KYC's approval alone produces - read authoritatively on the caller's unit of work, never cached, so a
     * suspension on another instance refuses this one's very next read.
     */
    boolean inGoodStanding(T unitOfWork, UUID partyId);

    /**
     * What is known of a party.
     *
     * @param ageYears the party's age in whole years on the database clock, when known
     * @param residenceCountry their country of residence, when known
     * @param version the answering implementation's version
     */
    record PartyFacts(Optional<Integer> ageYears, Optional<CountryCode> residenceCountry, int version) {
        public PartyFacts {
            Objects.requireNonNull(ageYears, "ageYears");
            Objects.requireNonNull(residenceCountry, "residenceCountry");
            ageYears.ifPresent(age -> {
                if (age < 0 || age > 150) {
                    throw new IllegalArgumentException("an age is between 0 and 150");
                }
            });
            if (version < 1) {
                throw new IllegalArgumentException("a version counts from 1");
            }
        }

        @Override
        public String toString() {
            return "PartyFacts[redacted, version=" + version + "]";
        }
    }
}
