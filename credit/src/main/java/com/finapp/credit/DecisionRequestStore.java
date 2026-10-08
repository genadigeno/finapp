package com.finapp.credit;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The decision request's persistence (`P10-TSK-014`, {@code credit V010}) - on the caller's unit of work. */
public interface DecisionRequestStore {

    /**
     * Inserts a request born {@code SUBMITTED} with its birth history row, unless the party already holds an open one for
     * the product ({@code ON CONFLICT} on the partial unique, {@code DO NOTHING}); true when this call wrote it. The
     * database stamps {@code submitted_at}, {@code expires_at = submitted_at + validity} and the first permit.
     */
    boolean insert(
            Connection unitOfWork,
            DecisionRequestId id,
            UUID party,
            CreditProfileId profile,
            DecisionRequest.Application application,
            Duration validity,
            String correlation,
            Actor actor);

    /** The party's open request for the product, if there is one. */
    Optional<DecisionRequestId> openFor(Connection unitOfWork, UUID party, CreditProduct product);

    /** The request, if it is {@code party}'s - another party's request and an absent one are the same empty answer. */
    Optional<DecisionRequest> ownedBy(Connection unitOfWork, DecisionRequestId id, UUID party);

    /** The request {@code FOR UPDATE} (lock order element (2)), if it is {@code party}'s. */
    Optional<DecisionRequest> lockOwnedBy(Connection unitOfWork, DecisionRequestId id, UUID party);

    /**
     * Moves the request to {@code to} if its status is one of {@code from}, with the edge's history row; true when this
     * call moved it. The trigger judges the edge for every writer.
     */
    boolean transition(
            Connection unitOfWork,
            DecisionRequestId id,
            Set<DecisionRequestStatus> from,
            DecisionRequestStatus to,
            Optional<ClosureReason> closureReason,
            Actor actor,
            Optional<String> reason);
}
