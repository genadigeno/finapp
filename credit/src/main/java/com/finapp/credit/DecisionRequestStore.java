package com.finapp.credit;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
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

    // ------------------------------------------------------------------ the progress (P10-TSK-015)

    /**
     * Claims at most {@code limit} due open requests, oldest permit first, in ONE statement: each claimed row's permit is
     * re-stamped {@code statement_timestamp() + permit} and rows another sweeper holds are skipped - so concurrent sweepers
     * claim disjoint pages, and a page no step can act on is retried only once its permit lapses.
     */
    List<DecisionRequestId> claimDue(Connection unitOfWork, int limit, java.time.Duration permit);

    /** The request {@code FOR UPDATE} (lock order element (2)) for the platform's step, with whether it has expired. */
    Optional<Locked> lock(Connection unitOfWork, DecisionRequestId id);

    /** A locked request and whether {@code expires_at <= statement_timestamp()} - the database's clock, never ours. */
    record Locked(DecisionRequest request, boolean expired) {}

    /**
     * Pins {@code versions} on a {@code SUBMITTED} request and moves it {@code COLLECTING}, with the edge's history row;
     * true when this call moved it. The trigger admits the pins at this edge alone, once.
     */
    boolean pin(Connection unitOfWork, DecisionRequestId id, PinnedVersions versions, Actor actor);

    /** The request's party, read without a lock - the deciding transaction locks the party's profile first (element (1)). */
    Optional<UUID> partyOf(Connection unitOfWork, DecisionRequestId id);
}
