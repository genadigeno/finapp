package com.finapp.crossborder;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The cross-border operator's lists (`P17-TSK-037`, finapp-admin-panel B9.1-B9.2, gaps G-101 and G-103): each corridor's latest
 * availability fact, the corridor enable requests a controller decides, and the payments - read-only, each a committed row.
 *
 * <p>Kept apart from the stores that write those rows: a list reads what the writers committed and never takes their locks.
 */
public interface CrossBorderCollections {

    /** A corridor's latest availability fact: whether it is available, who switched it, why and when. */
    record CorridorFact(CorridorKey corridor, boolean available, String actorId, String reason, Instant recordedAt) {
        public CorridorFact {
            Objects.requireNonNull(corridor, "corridor must not be null");
            Objects.requireNonNull(actorId, "actorId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        }
    }

    /** Each corridor's latest fact; a corridor never switched has none (and is available). */
    Map<CorridorKey, CorridorFact> latestCorridorFacts(Connection unitOfWork);

    /** Where the corridor enable requests resume: the last row served, newest first. */
    record CorridorRequestCursor(Instant proposedAt, UUID id) {
        public CorridorRequestCursor {
            Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** One corridor enable request: its corridor, where it stands, who proposed it and when, and its decision once taken. */
    record CorridorRequestEntry(
            UUID id,
            CorridorKey corridor,
            CorridorAvailabilityStore.EnableRequestStatus status,
            String proposedBy,
            Instant proposedAt,
            String reason,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<String> decisionReason) {}

    /** The corridor enable requests, newest proposal first; {@code status} narrows them. */
    List<CorridorRequestEntry> corridorEnableRequests(
            Connection unitOfWork,
            Optional<CorridorAvailabilityStore.EnableRequestStatus> status,
            Optional<CorridorRequestCursor> after,
            int limit);

    /** A payment search: a creation window {@code [from, to)}, and optionally a corridor and a status. */
    record CrossBorderPaymentFilter(
            Instant from, Instant to, Optional<CorridorKey> corridor, Optional<PaymentStore.Status> status) {
        public CrossBorderPaymentFilter {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(corridor, "corridor must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** Where the payments resume: the last row served, newest first. */
    record CrossBorderPaymentCursor(Instant createdAt, UUID id) {
        public CrossBorderPaymentCursor {
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * One payment as a list shows it: its payer and beneficiary by identifier, its corridor and state, and its offer's amounts -
     * what is sent, the fee, the total debited and what is delivered - at their stored scales.
     */
    record CrossBorderPaymentHead(
            UUID id,
            UUID owner,
            UUID beneficiary,
            CorridorKey corridor,
            PaymentStore.Status status,
            Optional<String> failureReason,
            Money sent,
            Money fee,
            Money totalDebit,
            Money delivered,
            Instant createdAt) {}

    /** The payments created in the window, newest first. */
    List<CrossBorderPaymentHead> payments(
            Connection unitOfWork, CrossBorderPaymentFilter filter, Optional<CrossBorderPaymentCursor> after, int limit);
}
