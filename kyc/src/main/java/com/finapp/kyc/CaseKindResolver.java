package com.finapp.kyc;

import java.util.UUID;

/**
 * Answers which {@link KycCaseKind} a customer's case must open as (`P2-TSK-015`).
 *
 * <p>A port, because the answer is a fact only {@code party} knows — an {@code ORGANISATION}
 * customer opens a {@code KYB} case — and this module cannot see {@code party} (module
 * isolation, ADR-0029). The {@code app} composition root implements it over {@code PartyStore},
 * exactly where the {@code DecisionOutcome → CustomerStatus} mapping lives for the same reason
 * (ADR-0035): the cross-context fact is the orchestration's to carry.
 *
 * <p>This is what lets {@code CustomerOpenedOpensCase} keep its metadata-only stance: the
 * consumer still reads no payload — it asks a port a question, on the same unit of work.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CaseKindResolver<T> {

    /**
     * The kind of case this customer's verification opens as.
     *
     * <p>Throws rather than defaulting when the customer cannot be resolved: the consumer runs
     * after the registration that created the customer committed (outbox ordering), so an
     * unresolvable customer is a defect, and a silently defaulted kind would quietly disarm the
     * ownership gate for an organisation.
     */
    KycCaseKind kindFor(T unitOfWork, UUID customerId);
}
