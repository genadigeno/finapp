package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for BNPL disputes (`P12-TSK-023`, {@code bnpl V015}): the dispute row with its claim and hold, its edges
 * conditional on their from-state, its history, the four-eyes proposals, the deadline sweep's claim, and the reads each door
 * scopes - every merchant read {@code merchant_id = ?} in the statement, every customer read {@code party_id = ?}.
 */
public interface BnplDisputeStore {

    /** The dispute {@code id}, unlocked - a door's first read, to find the purchase it locks (its ids are frozen). */
    Optional<BnplDispute> find(Connection unitOfWork, BnplDisputeId id);

    /** The dispute {@code id} if it is {@code party}'s - {@code party_id = ?} in the statement; another's is empty. */
    Optional<BnplDispute> findOfParty(Connection unitOfWork, BnplDisputeId id, UUID party);

    /** The dispute {@code id} if it is of {@code merchant}'s purchase - {@code merchant_id = ?} in the statement. */
    Optional<BnplDispute> findAtMerchant(Connection unitOfWork, BnplDisputeId id, UUID merchant);

    /** (B6) the dispute {@code id} {@code FOR UPDATE} - after the caller's B5 and B4. */
    Optional<BnplDispute> lock(Connection unitOfWork, BnplDisputeId id);

    /** The dispute {@code party} opened under {@code requestKey} - the arbiter behind a lapsed claim. */
    Optional<BnplDispute> findByKey(Connection unitOfWork, UUID party, String requestKey);

    /** The purchase's {@code OPEN} or {@code MERCHANT_RESPONDED} dispute - read under the caller's B5. */
    Optional<BnplDispute> openOf(Connection unitOfWork, BnplPurchaseId purchase);

    /**
     * When the purchase's latest {@code WITHDRAWN} dispute was withdrawn (its database-stamped {@code resolved_at}) - read
     * under the caller's B5, the withdrawal's cooldown's input (the Phase 12 to 13 gate's A5-F2); empty when none was.
     */
    Optional<java.time.Instant> latestWithdrawalOf(Connection unitOfWork, BnplPurchaseId purchase);

    /**
     * Births the dispute, its claim and its hold; the database stamps the opening, its business date, the response deadline
     * and the sweep's permit - the dispute answered as stamped.
     */
    BnplDispute insert(Connection unitOfWork, BnplDispute dispute);

    /**
     * The edge to {@code moved}'s state, conditional on {@code from} - the dispute answered as stamped, or empty when it was
     * not in {@code from} (another act committed first); {@code bnpl V015}'s edge function re-judges the deadline and the
     * four-eyes rule ({@code bnpl V018}: on the purchase's cumulative refunds by dispute). The merchant's edges are also
     * conditional on the deadline being ahead on the database clock and the system's lapse on its having come, in the same
     * statement (the Phase 12 to 13 gate's A3-M2) - empty, never a storage failure, when the deadline passed meanwhile.
     */
    Optional<BnplDispute> move(Connection unitOfWork, BnplDispute moved, BnplDisputeStatus from);

    /** Appends one history row. */
    void appendEvent(Connection unitOfWork, Event event);

    /**
     * The deadline sweep's claim, in ONE statement on the database clock that holds no row after it: {@code OPEN} disputes
     * whose deadline and permit have come, {@code FOR UPDATE SKIP LOCKED}, each permit re-stamped {@code cadence} ahead.
     */
    List<BnplDisputeId> claimLapsed(Connection unitOfWork, int limit, Duration cadence);

    /** {@code merchant}'s open disputes, oldest first - {@code merchant_id = ?} in the statement. */
    List<BnplDispute> openAtMerchant(Connection unitOfWork, UUID merchant);

    /** Whether {@code merchant} has any open dispute - the merchant close's question (ADR-0106 decision 11). */
    boolean anyOpenAtMerchant(Connection unitOfWork, UUID merchant);

    /**
     * Whether {@code party} has an open ({@code OPEN} or {@code MERCHANT_RESPONDED}) dispute in {@code currency} - the
     * wallet close's question (the Phase 12 to 13 gate's A7-1): its refund outcome may credit the party's wallet.
     */
    boolean anyOpenOfParty(Connection unitOfWork, UUID party, CurrencyCode currency);

    /**
     * The operator's queue (the gate's A7-2): at most {@code limit} disputes in {@code status} ({@code OPEN} or
     * {@code MERCHANT_RESPONDED}), oldest first by the instant each entered it, after the cursor dispute when given - never
     * one of {@code excludingParty}'s, the reading operator's own party.
     */
    List<BnplDispute> queue(Connection unitOfWork, BnplDisputeStatus status, UUID excludingParty,
            Optional<BnplDisputeId> after, int limit);

    /**
     * How long the oldest {@code MERCHANT_RESPONDED} dispute has awaited an operator since the merchant answered, on the
     * database clock - empty when none does (the gate's A7-2 gauge).
     */
    Optional<Duration> oldestContestedAge(Connection unitOfWork);

    /** The dispute's live ({@code PROPOSED} or {@code APPROVED}) proposal. */
    Optional<BnplDisputeProposal> liveProposal(Connection unitOfWork, BnplDisputeId dispute);

    /** Births a proposal {@code PROPOSED}; the database stamps when - answered as stamped. */
    BnplDisputeProposal insertProposal(Connection unitOfWork, BnplDisputeProposal proposal);

    /** (B6) the proposal {@code id} of {@code dispute} {@code FOR UPDATE}. */
    Optional<BnplDisputeProposal> lockProposal(Connection unitOfWork, UUID id, BnplDisputeId dispute);

    /** The decision recorded, conditional on {@code PROPOSED} - answered as stamped, or empty. */
    Optional<BnplDisputeProposal> decide(Connection unitOfWork, BnplDisputeProposal decided);

    /** One history row: the state entered, from which (none at birth), by whom. */
    record Event(UUID id, BnplDisputeId dispute, Optional<BnplDisputeStatus> from, BnplDisputeStatus to, String actor,
            CorrelationId correlation) {

        public Event {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(dispute, "dispute must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }
}
