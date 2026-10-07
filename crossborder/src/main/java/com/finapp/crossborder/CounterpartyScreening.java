package com.finapp.crossborder;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CountryCode;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * kyc's counterparty screening, as crossborder asks for it (`P9-TSK-017`, ADR-0081; PHASE_9_PLAN.md
 * section 3's ports table) - declared here, implemented in {@code app} over kyc's
 * {@code CounterpartyScreenings}. kyc decides; crossborder only asks and is told (the
 * {@code ScreeningOutcomeListener} composition). The name passes through and is stored only by kyc.
 */
public interface CounterpartyScreening {

    /**
     * Requests a screening in the caller's unit of work - the request row commits with the caller's
     * transaction, so a crash afterwards still leaves a due screening for kyc's retry. Idempotent on
     * {@code request.reference()}.
     *
     * @return the screening's identifier
     */
    UUID requestWithin(Connection unitOfWork, Request request);

    /** Asks the provider now for a requested screening - no connection held; kyc decides in its own T-e. */
    void screenNow(UUID screening, CorrelationId correlation);

    /**
     * Requests a re-screen of {@code previous}'s counterparty in the caller's unit of work (`P9-TSK-018`) -
     * kyc reuses the stored subject and payee check, so crossborder never needs the name again. Idempotent on
     * {@code reference}.
     *
     * @return the new screening's identifier
     */
    UUID rescreenWithin(Connection unitOfWork, UUID previous, String reference);

    /** {@code screening}'s clearance, read in the caller's unit of work (`P9-TSK-018`). */
    java.util.Optional<Clearance> clearance(Connection unitOfWork, UUID screening);

    /** Whether a screening clears, since when, and whether it is still waiting for the provider. */
    record Clearance(boolean clears, java.util.Optional<java.time.Instant> decidedAt, boolean unanswered) {
        public Clearance {
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        }
    }

    /**
     * A screening request: a stable reference, the counterparty, the payee check as handed in, and the actor who
     * registered the beneficiary - the one person who may never review its screening (the Phase 9 to 10 transition
     * gate: four eyes are two persons, INV-AUD-04).
     */
    record Request(
            String reference,
            String name,
            CountryCode country,
            BeneficiaryVocabulary.EntityType entityType,
            BeneficiaryVocabulary.PayeeCheck payeeCheck,
            String requestedBy) {
        public Request {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
            Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        }

        @Override
        public String toString() {
            return "Request[reference=" + reference + ", name=<redacted>, country=" + country + ", entityType="
                    + entityType + ", payeeCheck=" + payeeCheck + "]";
        }
    }
}
