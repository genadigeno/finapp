package com.finapp.fx;

import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The FX operator's lists (`P17-TSK-037`, finapp-admin-panel B9.1-B9.2, gaps G-101 and G-102): the latest availability fact of
 * each pair and provider, the enable requests a controller decides, and the booked trades - read-only, each a committed row.
 *
 * <p>Kept apart from the stores that write those rows: a list reads what the writers committed and never takes their locks, so
 * a page is a snapshot of the committed state and never a claim on it.
 */
public interface FxCollections {

    /** A subject's latest availability fact: whether it is available, who switched it, why and when. */
    record AvailabilityFact(AvailabilitySubject subject, boolean available, String actorId, String reason, Instant recordedAt) {
        public AvailabilityFact {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(actorId, "actorId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        }
    }

    /** Each subject's latest fact of {@code kind}; a subject never switched has none (and is available). */
    Map<AvailabilitySubject, AvailabilityFact> latestFacts(Connection unitOfWork, AvailabilitySubject.Kind kind);

    /** Where the enable requests resume: the last row served, newest first. */
    record EnableRequestCursor(Instant proposedAt, UUID id) {
        public EnableRequestCursor {
            Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** One enable request: its subject, where it stands, who proposed it and when, and its decision once taken. */
    record EnableRequestEntry(
            UUID id,
            AvailabilitySubject subject,
            AvailabilityStore.EnableRequestStatus status,
            String proposedBy,
            Instant proposedAt,
            String reason,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<String> decisionReason) {}

    /** The enable requests, newest proposal first; {@code status} narrows them. */
    List<EnableRequestEntry> enableRequests(
            Connection unitOfWork,
            Optional<AvailabilityStore.EnableRequestStatus> status,
            Optional<EnableRequestCursor> after,
            int limit);

    /** A trade search: a booking window {@code [from, to)}, and optionally a pair and a status. */
    record TradeFilter(Instant from, Instant to, Optional<AvailabilitySubject> pair, Optional<TradeStatus> status) {
        public TradeFilter {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(pair, "pair must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (pair.isPresent() && pair.get().kind() != AvailabilitySubject.Kind.PAIR) {
                throw new IllegalArgumentException("a trade search narrows by a pair, never a provider");
            }
        }
    }

    /** Where the trades resume: the last row served, newest booking first. */
    record TradeCursor(Instant bookedAt, UUID id) {
        public TradeCursor {
            Objects.requireNonNull(bookedAt, "bookedAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** One booked trade as a list shows it: the customer's two amounts at their stored scales, and its executed rate. */
    record TradeHead(
            FxTradeId id,
            UUID owner,
            PricingPurpose purpose,
            FixedSide fixedSide,
            TradeStatus status,
            Money customerSource,
            Money customerDestination,
            BigDecimal executedRate,
            String providerCode,
            Instant bookedAt,
            LocalDate bookedOn) {}

    /** The trades booked in the window, newest first. */
    List<TradeHead> trades(Connection unitOfWork, TradeFilter filter, Optional<TradeCursor> after, int limit);
}
