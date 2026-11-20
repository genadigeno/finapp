package com.finapp.app.crossborder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CorridorTerms;
import com.finapp.crossborder.CrossBorderCollections;
import com.finapp.crossborder.CrossborderAuditAction;
import com.finapp.crossborder.PaymentStore;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The cross-border operator's reads (`P17-TSK-037`, finapp-admin-panel B9.1-B9.2, gaps G-101 and G-103): the active corridor
 * policy's corridors with each one's availability and who changed it, when; the corridor enable requests a controller decides;
 * and the payments.
 *
 * <p>Each read is one transaction over committed rows. The corridors and the enable requests are the platform's own
 * configuration, so they are not recorded; a page of payments is a read of customers' payments, so every page is recorded
 * {@code crossborder.CrossBorderPaymentsListed} in the read's own transaction, as a payment's trace is.
 *
 * <p>Every list of rows that grows is a §7 collection ({@code API_CONVENTIONS.md}): {@code limit} 50 unless named, 200 at most,
 * the opaque {@code cursor} of the page before, newest first. A payment search names a window of at most 31 days.
 */
@RequiredArgsConstructor
public final class CrossBorderReadDesk {

    static final String CORRIDOR_ENABLE_REQUESTS = "crossborder.corridor-enable-requests";
    static final String PAYMENTS = "crossborder.payments";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);
    static final String PAYMENTS_TARGET = "crossborder_payment_search";

    @NonNull private final CrossBorderCollections collections;
    @NonNull private final CorridorPolicyStore policies;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionRunner transactions;

    /** The active corridor policy's corridors - none, and no version, when no policy is active. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorridorAvailabilityList(Integer policyVersion, List<CorridorAvailabilityItem> items) {}

    /**
     * A corridor as an operator reads it: its rails, its maximum and delivery estimate, whether it is available, and the latest
     * fact's controller, instant and reason - absent for a corridor never switched, which is available.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorridorAvailabilityItem(
            String corridor,
            List<String> rails,
            String maximumAmount,
            String maximumCurrency,
            long deliveryEstimateHours,
            boolean available,
            String changedBy,
            String changedAt,
            String reason) {}

    /** A page of corridor enable requests: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorridorEnableRequestPage(List<CorridorEnableRequestItem> items, String nextCursor) {}

    /** One corridor enable request: its corridor, where it stands, its proposer and reason, and its decision once taken. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorridorEnableRequestItem(
            String id,
            String corridor,
            String status,
            String proposedBy,
            String proposedAt,
            String reason,
            String decidedBy,
            String decidedAt,
            String decisionReason) {}

    /** A page of cross-border payments: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CrossBorderPaymentPage(List<CrossBorderPaymentItem> items, String nextCursor) {}

    /**
     * One cross-border payment: its payer and beneficiary by identifier, its corridor and state - a failure's reason when it
     * failed - and its offer's amounts as decimal strings: sent, the fee and the total debited in the source currency, and what
     * is delivered in the destination currency.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CrossBorderPaymentItem(
            String id,
            String ownerPartyId,
            String beneficiaryId,
            String corridor,
            String status,
            String failureReason,
            String sentAmount,
            String feeAmount,
            String totalDebitAmount,
            String sourceCurrency,
            String deliveredAmount,
            String destinationCurrency,
            String createdAt) {}

    // ------------------------------------------------------------------ the corridors (G-101)

    /** The active policy's corridors, each with its availability. */
    public CorridorAvailabilityList corridors() {
        return transactions.inTransaction(uow -> {
            Optional<CorridorPolicyStore.VersionView> active = policies.active(uow);
            if (active.isEmpty()) {
                return new CorridorAvailabilityList(null, List.of());
            }
            Map<CorridorKey, CrossBorderCollections.CorridorFact> facts = collections.latestCorridorFacts(uow);
            return new CorridorAvailabilityList(active.get().row().version(), active.get().corridors().stream()
                    .sorted(Comparator.comparing(terms -> terms.key().code()))
                    .map(terms -> corridorItem(terms, Optional.ofNullable(facts.get(terms.key()))))
                    .toList());
        });
    }

    /** A page of the corridor enable requests, newest first; {@code status} one of the machine's own words. */
    public CorridorEnableRequestPage enableRequests(String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<CorridorAvailabilityStore.EnableRequestStatus> status =
                Optional.ofNullable(rawStatus).map(CrossBorderReadDesk::requestStatus);
        Optional<CrossBorderCollections.CorridorRequestCursor> after = requestCursor(cursor);
        List<CrossBorderCollections.CorridorRequestEntry> rows =
                transactions.inTransaction(uow -> collections.corridorEnableRequests(uow, status, after, size + 1));
        List<CrossBorderCollections.CorridorRequestEntry> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            CrossBorderCollections.CorridorRequestEntry tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(CORRIDOR_ENABLE_REQUESTS, tail.proposedAt().toString(), tail.id().toString());
        }
        return new CorridorEnableRequestPage(served.stream().map(CrossBorderReadDesk::requestItem).toList(), next);
    }

    // ------------------------------------------------------------------ the payments (G-103)

    /** A page of the payments created in {@code [from, to)}, newest first - read and recorded. */
    public CrossBorderPaymentPage payments(
            String rawFrom, String rawTo, String rawCorridor, String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Instant from = instant("from", rawFrom);
        Instant to = instant("to", rawTo);
        if (!from.isBefore(to)) {
            throw OperatorCollection.invalid("from is before to: the window is [from, to).");
        }
        if (Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0) {
            throw OperatorCollection.invalid("the window is at most " + MAXIMUM_WINDOW.toDays() + " days; narrow it.");
        }
        Optional<CorridorKey> corridor = Optional.ofNullable(rawCorridor).map(CrossBorderReadDesk::corridor);
        Optional<PaymentStore.Status> status = Optional.ofNullable(rawStatus).map(CrossBorderReadDesk::paymentStatus);
        Optional<CrossBorderCollections.CrossBorderPaymentCursor> after = paymentCursor(cursor);
        Actor actor = SecurityContext.require();
        return transactions.inTransaction(uow -> {
            List<CrossBorderCollections.CrossBorderPaymentHead> rows = collections.payments(
                    uow, new CrossBorderCollections.CrossBorderPaymentFilter(from, to, corridor, status), after, size + 1);
            List<CrossBorderCollections.CrossBorderPaymentHead> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                CrossBorderCollections.CrossBorderPaymentHead tail = served.get(served.size() - 1);
                next = OperatorCollection.cursor(PAYMENTS, tail.createdAt().toString(), tail.id().toString());
            }
            audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    CrossborderAuditAction.CROSS_BORDER_PAYMENTS_LISTED, PAYMENTS_TARGET,
                    corridor.map(CorridorKey::code).orElse("all"), Optional.empty(), AuditOutcome.SUCCEEDED, correlation(),
                    Optional.of("from=" + from + ", to=" + to + status.map(s -> ", status=" + s).orElse("")
                            + ", continued=" + after.isPresent() + ", served=" + served.size() + ", more=" + (next != null))));
            return new CrossBorderPaymentPage(served.stream().map(CrossBorderReadDesk::paymentItem).toList(), next);
        });
    }

    // ------------------------------------------------------------------ the request's words

    static CorridorAvailabilityStore.EnableRequestStatus requestStatus(String raw) {
        try {
            return CorridorAvailabilityStore.EnableRequestStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid(
                    "status is one of " + names(CorridorAvailabilityStore.EnableRequestStatus.values()) + ".");
        }
    }

    static PaymentStore.Status paymentStatus(String raw) {
        try {
            return PaymentStore.Status.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of " + names(PaymentStore.Status.values()) + ".");
        }
    }

    static CorridorKey corridor(String raw) {
        try {
            return CorridorKey.parse(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("corridor is SOURCE-DESTINATION-COUNTRY, such as EUR-USD-US.");
        }
    }

    static Instant instant(String name, String raw) {
        if (raw == null) {
            throw OperatorCollection.invalid(name + " is required: an ISO-8601 instant.");
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw OperatorCollection.invalid(name + " is an ISO-8601 instant, such as 2026-11-20T00:00:00Z.");
        }
    }

    static Optional<CrossBorderCollections.CorridorRequestCursor> requestCursor(String cursor) {
        return OperatorCollection.key(CORRIDOR_ENABLE_REQUESTS, cursor, 2).map(key -> {
            try {
                return new CrossBorderCollections.CorridorRequestCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static Optional<CrossBorderCollections.CrossBorderPaymentCursor> paymentCursor(String cursor) {
        return OperatorCollection.key(PAYMENTS, cursor, 2).map(key -> {
            try {
                return new CrossBorderCollections.CrossBorderPaymentCursor(
                        Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static CorridorAvailabilityItem corridorItem(CorridorTerms terms, Optional<CrossBorderCollections.CorridorFact> fact) {
        return new CorridorAvailabilityItem(
                terms.key().code(),
                List.copyOf(terms.rails()),
                decimal(terms.maximum()),
                terms.maximum().currency().code(),
                terms.deliveryEstimate().toHours(),
                // Never switched: available, as the gate reads it (JdbcCorridorAvailabilityStore.isAvailable).
                fact.map(CrossBorderCollections.CorridorFact::available).orElse(true),
                fact.map(CrossBorderCollections.CorridorFact::actorId).orElse(null),
                fact.map(known -> known.recordedAt().toString()).orElse(null),
                fact.map(CrossBorderCollections.CorridorFact::reason).orElse(null));
    }

    static CorridorEnableRequestItem requestItem(CrossBorderCollections.CorridorRequestEntry entry) {
        return new CorridorEnableRequestItem(
                entry.id().toString(),
                entry.corridor().code(),
                entry.status().name(),
                entry.proposedBy(),
                entry.proposedAt().toString(),
                entry.reason(),
                entry.decidedBy().orElse(null),
                entry.decidedAt().map(Instant::toString).orElse(null),
                entry.decisionReason().orElse(null));
    }

    static CrossBorderPaymentItem paymentItem(CrossBorderCollections.CrossBorderPaymentHead payment) {
        return new CrossBorderPaymentItem(
                payment.id().toString(),
                payment.owner().toString(),
                payment.beneficiary().toString(),
                payment.corridor().code(),
                payment.status().name(),
                payment.failureReason().orElse(null),
                decimal(payment.sent()),
                decimal(payment.fee()),
                decimal(payment.totalDebit()),
                payment.sent().currency().code(),
                decimal(payment.delivered()),
                payment.delivered().currency().code(),
                payment.createdAt().toString());
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private static String names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a cross-border read runs inside a correlation scope"))
                .correlationId();
    }
}
