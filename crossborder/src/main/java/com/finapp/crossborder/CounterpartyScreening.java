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

    /** A screening request: a stable reference, the counterparty, and the payee check as handed in. */
    record Request(
            String reference,
            String name,
            CountryCode country,
            BeneficiaryVocabulary.EntityType entityType,
            BeneficiaryVocabulary.PayeeCheck payeeCheck) {
        public Request {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
        }

        @Override
        public String toString() {
            return "Request[reference=" + reference + ", name=<redacted>, country=" + country + ", entityType="
                    + entityType + ", payeeCheck=" + payeeCheck + "]";
        }
    }
}
