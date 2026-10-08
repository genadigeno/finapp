package com.finapp.credit;

import java.util.Optional;
import java.util.UUID;

/**
 * The published decision read (`P10-TSK-016`; ADR-0087): what Phase 11's {@code lending} will read to open a loan from
 * an approval - the decision of a request, or by its id - on the caller's unit of work. No consumer in Phase 10.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditDecisions<T> {

    /** The request's decision, if it has one. */
    Optional<CreditDecision> forRequest(T unitOfWork, UUID decisionRequest);

    /** The decision {@code id}, if it exists. */
    Optional<CreditDecision> byId(T unitOfWork, CreditDecisionId id);
}
