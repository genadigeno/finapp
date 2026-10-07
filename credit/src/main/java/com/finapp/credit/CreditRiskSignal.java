package com.finapp.credit;

import java.util.Objects;
import java.util.UUID;

/**
 * The risk seam (`P10-TSK-008`; ADR-0084 point 5, {@code INV-CRD-04}): the risk score is {@code risk}'s (Phase 13);
 * credit consumes a signal, recorded as given and never computed. Phase 10's composition answers {@code NOT_ASSESSED}
 * for every party with the seam's version, so a decision made before Phase 13 replays identically after it
 * ({@code INV-CRD-01}).
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditRiskSignal<T> {

    /** The signal for the party, as the seam answers it now. */
    RiskSignal signal(T unitOfWork, UUID partyId);

    /**
     * A risk signal.
     *
     * @param code the seam's answer - a closed code such as {@code NOT_ASSESSED}
     * @param seamVersion the answering implementation's version
     */
    record RiskSignal(String code, int seamVersion) {
        public RiskSignal {
            Objects.requireNonNull(code, "code");
            new AttributeValue.CodeValue(code);
            if (seamVersion < 1) {
                throw new IllegalArgumentException("a seam version counts from 1");
            }
        }
    }
}
