package com.finapp.credit;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The risk seam (`P10-TSK-008`; ADR-0084 point 5, {@code INV-CRD-04}): the risk score is {@code risk}'s (Phase 13);
 * credit consumes a signal, recorded as given and never computed. Phase 10's composition answered {@code NOT_ASSESSED}
 * for every party with seam version 1, so a decision made before Phase 13 replays identically after it
 * ({@code INV-CRD-01}).
 *
 * <p><strong>Seam version 2</strong> (`P13-TSK-007`; {@code INV-RSK-10}): {@code app}'s {@code RiskCreditRiskSignal}
 * answers the party's customer risk rating - a closed code ({@code LOW}, {@code MEDIUM}, {@code HIGH},
 * {@code PROHIBITED}), never risk's number - with the version of the risk profile it was read from, or
 * {@code NOT_ASSESSED} with no version for a party risk has not assessed. The freezer records both in the snapshot
 * ({@code RISK_SIGNAL}'s provenance names the seam version and the profile version), so a decision made under profile
 * version n replays {@code IDENTICAL} after the profile moves - the replay reads the snapshot, never the seam.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditRiskSignal<T> {

    /** The signal for the party, as the seam answers it now. */
    RiskSignal signal(T unitOfWork, UUID partyId);

    /**
     * A risk signal.
     *
     * @param code the seam's answer - a closed code such as {@code NOT_ASSESSED} or a rating's
     * @param seamVersion the answering implementation's version
     * @param profileVersion the version of the record the answer was read from - risk's customer risk profile version,
     *     from seam version 2; empty when the answer was read from none (seam version 1, or a party not assessed)
     */
    record RiskSignal(String code, int seamVersion, OptionalLong profileVersion) {
        public RiskSignal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(profileVersion, "profileVersion");
            new AttributeValue.CodeValue(code);
            if (seamVersion < 1) {
                throw new IllegalArgumentException("a seam version counts from 1");
            }
            if (seamVersion == 1 && profileVersion.isPresent()) {
                throw new IllegalArgumentException("seam version 1 answers from no profile");
            }
            if (profileVersion.isPresent() && profileVersion.getAsLong() < 1) {
                throw new IllegalArgumentException("a profile version counts from 1");
            }
        }

        /** An answer read from no record - seam version 1's every answer, and a later seam's for an unassessed party. */
        public RiskSignal(String code, int seamVersion) {
            this(code, seamVersion, OptionalLong.empty());
        }
    }
}
