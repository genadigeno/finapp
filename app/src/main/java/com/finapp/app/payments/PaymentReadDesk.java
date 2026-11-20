package com.finapp.app.payments;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.paymentmethods.PaymentmethodsAuditAction;
import com.finapp.app.paymentmethods.PaymentMethodService;
import com.finapp.payments.Dispute;
import com.finapp.payments.DisputeStage;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentCollections;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.PaymentsAuditAction;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStore;
import com.finapp.payments.RoutingDecision;
import com.finapp.payments.RoutingStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's payment reads (`P17-TSK-032`, finapp-admin-panel B7.2, gaps G-40, G-42, G-43, G-44, G-45): the payment search
 * and one payment, a party's payment methods and the payments report under {@code PAYMENT_READ}; the dispute queue under
 * {@code DISPUTE_ADMINISTER}. The held card payments are risk's desk ({@code HeldPaymentReadDesk}). Every one recorded in the read's own
 * transaction - the {@code PAYMENT_READ} ones through {@link RecordedReads}, which the answer path holds them to.
 *
 * <h2>The merchant is a filter the ledger resolves</h2>
 *
 * <p>{@code payments} never learns what a merchant is (ADR-0061 section 4): {@code merchantId} becomes the merchant's payables,
 * read through the ledger's owner-scoped read - a merchant with none has no payments, and the page is empty without asking.
 *
 */
@RequiredArgsConstructor
public final class PaymentReadDesk {

    static final String PAYMENTS = "payments.payments";
    static final String DISPUTES = "payments.disputes";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    /** The widest window one search covers, {@code [from, to)}: a month of creation instants. */
    static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);
    /** The earliest day a report may name - before any record this platform can hold. */
    static final LocalDate EARLIEST_REPORT_DAY = LocalDate.of(2000, 1, 1);

    static final String SEARCH_TARGET = "payment_search";
    static final String QUEUE_TARGET = "dispute_queue";
    static final String REPORT_TARGET = "payments_report";
    static final String METHODS_TARGET = "party";
    static final String PAYMENT_TARGET = "payment_intent";

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    @NonNull private final PaymentCollections collections;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final RoutingStore<Connection> routing;
    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final PaymentRails rails;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PaymentMethodStore<Connection> methods;
    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final RecordedReads recordedReads;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    // ------------------------------------------------------------------ views

    /** A page of the search: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentPage(List<PaymentSummary> items, String nextCursor) {}

    /**
     * One payment as the search shows it: its payer's party by identifier, its status and amount (a decimal string), when it
     * was created (ISO-8601 UTC), and its attempt's rail and status - both absent when it never had an attempt.
     */
    public record PaymentSummary(
            String paymentIntentId,
            String partyId,
            String status,
            String amount,
            String currency,
            String createdAt,
            String rail,
            String attemptStatus) {}

    /** One payment whole: the intent, its attempt with the provider's references, its refunds, routing decision and disputes. */
    public record PaymentDetailView(
            String paymentIntentId,
            String partyId,
            String customerId,
            String paymentMethodId,
            String captureMode,
            String status,
            String amount,
            String currency,
            String failureReason,
            String createdAt,
            AttemptView attempt,
            List<OperatorRefundView> refunds,
            RoutingDecisionView routingDecision,
            List<DisputeSummaryView> disputes) {}

    /**
     * The payment's attempt: its rail, interaction model and status; the provider's references - the authorization's
     * ({@code authProviderReference}, the column's own name), the capture's, the void's and the scheme's - each absent until the
     * provider gave one; the authorized and captured amounts; a failure's mapped reason.
     */
    public record AttemptView(
            String attemptId,
            String rail,
            String interactionModel,
            String status,
            String authProviderReference,
            String captureProviderReference,
            String voidProviderReference,
            String schemeReference,
            String authorizedAmount,
            String capturedAmount,
            String failureReason,
            String createdAt) {}

    /** One refund: status, amount and currency, the provider's reference once completed, when it was asked. */
    public record OperatorRefundView(
            String refundId, String status, String amount, String currency, String providerReference, String createdAt) {}

    /** The payment's latest routing decision: its chosen rail and when it was decided; the explanation is its own read. */
    public record RoutingDecisionView(String routingDecisionId, String chosenRail, Integer matchedRuleIndex, String decidedAt) {}

    /** One of the payment's disputes, as its queue row shows it. */
    public record DisputeSummaryView(
            String disputeId, String stage, String reason, String chargebackAmount, String chargebackCurrency,
            String respondBy, String openedAt) {}

    /** A page of the dispute queue: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisputeQueuePage(List<DisputeQueueItem> items, String nextCursor) {}

    /** One dispute in the queue: its payment, stage, reason, the chargeback (absent until taken), the network's deadline. */
    public record DisputeQueueItem(
            String disputeId,
            String paymentIntentId,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String respondBy,
            String openedAt) {}

    /** A party's live payment methods, masked as the party's own list serves them. */
    public record PaymentMethodList(List<PaymentMethodService.PaymentMethodView> items) {}

    /** The payments report for a UTC day, and the unknown-state backlog as of {@code generatedAt}. */
    public record PaymentsReportView(
            String date, String generatedAt, List<PaymentOutcomeRow> outcomes, List<PaymentUnknownRow> unknownBacklog) {}

    /**
     * How many of the day's payments ended where: the attempt's rail - its provider: each declared rail is one provider's
     * adapter - and status, both absent for a payment that never had an attempt, and the payment's own status.
     */
    public record PaymentOutcomeRow(String rail, String attemptStatus, String intentStatus, long count) {}

    /** What waits on a provider's answer now: attempts or refunds in an unknown status, how many and the oldest's instant. */
    public record PaymentUnknownRow(String kind, String status, long count, String oldestCreatedAt) {}

    // ------------------------------------------------------------------ the payment search (G-40)

    /** A page of the payments created in {@code [from, to)}, newest first - read and recorded. */
    public PaymentPage payments(
            String rawFrom, String rawTo, String rawParty, String rawMerchant, String rawStatus, String rawRail,
            String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Instant from = instant("from", rawFrom);
        Instant to = instant("to", rawTo);
        if (!from.isBefore(to)) {
            throw OperatorCollection.invalid("from is before to: the window is [from, to).");
        }
        if (Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0) {
            throw OperatorCollection.invalid("the window is at most " + MAXIMUM_WINDOW.toDays() + " days; narrow it.");
        }
        Optional<UUID> party = Optional.ofNullable(rawParty).map(raw -> identifier("partyId", raw));
        Optional<UUID> merchant = Optional.ofNullable(rawMerchant).map(raw -> identifier("merchantId", raw));
        if (party.isPresent() && merchant.isPresent()) {
            throw OperatorCollection.invalid("partyId and merchantId are each a search of their own; name one.");
        }
        Optional<PaymentIntentStatus> status = Optional.ofNullable(rawStatus).map(PaymentReadDesk::intentStatus);
        Optional<RailId> rail = Optional.ofNullable(rawRail).map(this::declaredRail);
        Optional<PaymentCollections.PaymentCursor> after = paymentCursor(cursor);
        return inOneTransaction(uow -> {
            Optional<Set<LedgerAccountId>> credited = merchant.map(id -> payablesOf(uow, id));
            List<PaymentCollections.PaymentHead> rows = credited.filter(Set::isEmpty).isPresent()
                    ? List.of()
                    : collections.payments(uow, new PaymentCollections.PaymentFilter(from, to, party, credited, status, rail),
                            after, size + 1);
            List<PaymentCollections.PaymentHead> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                PaymentCollections.PaymentHead tail = served.get(served.size() - 1);
                next = OperatorCollection.cursor(PAYMENTS, tail.createdAt().toString(), tail.id().value().toString());
            }
            recordedReads.record(uow, PaymentsAuditAction.PAYMENTS_LISTED, SEARCH_TARGET,
                    party.map(id -> "party:" + id).or(() -> merchant.map(id -> "merchant:" + id)).orElse("all"),
                    "from=" + from + ", to=" + to + status.map(s -> ", status=" + s).orElse("")
                            + rail.map(r -> ", rail=" + r.value()).orElse("") + ", continued=" + after.isPresent()
                            + ", served=" + served.size() + ", more=" + (next != null));
            return new PaymentPage(served.stream().map(PaymentReadDesk::summary).toList(), next);
        });
    }

    static Optional<PaymentCollections.PaymentCursor> paymentCursor(String cursor) {
        return OperatorCollection.key(PAYMENTS, cursor, 2).map(key -> {
            try {
                return new PaymentCollections.PaymentCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static PaymentSummary summary(PaymentCollections.PaymentHead head) {
        return new PaymentSummary(
                head.id().value().toString(),
                head.party().toString(),
                head.status().name(),
                decimal(head.amount()),
                head.amount().currency().code(),
                head.createdAt().toString(),
                head.rail().map(RailId::value).orElse(null),
                head.attemptStatus().map(Enum::name).orElse(null));
    }

    /** One payment whole - recorded; unknown and malformed are one 404. */
    public PaymentDetailView payment(String rawIntentId) {
        PaymentIntentId id = intentOrAbsent(rawIntentId);
        return inOneTransaction(uow -> {
            PaymentIntent intent = intents.findById(uow, id).orElseThrow(PaymentReadDesk::paymentNotFound);
            Optional<PaymentAttempt> attempt = attempts.findForIntent(uow, id);
            List<Refund> refunded = attempt.map(found -> refunds.listFor(uow, found.id())).orElse(List.of());
            Optional<RoutingDecision> decision = routing.findLatestDecisionForIntent(uow, id);
            List<DisputeStore.Found> disputed = disputes.listForIntent(uow, id);
            recordedReads.record(uow, PaymentsAuditAction.PAYMENT_READ, PAYMENT_TARGET, id.value().toString(),
                    "status=" + intent.status() + ", attempt=" + attempt.isPresent() + ", refunds=" + refunded.size()
                            + ", disputes=" + disputed.size());
            return new PaymentDetailView(
                    id.value().toString(),
                    intent.partyId().toString(),
                    intent.customerId().toString(),
                    intent.paymentMethodId().toString(),
                    intent.captureMode().name(),
                    intent.status().name(),
                    decimal(intent.amount()),
                    intent.amount().currency().code(),
                    intent.failureReason().map(Enum::name).orElse(null),
                    intent.createdAt().toString(),
                    attempt.map(PaymentReadDesk::attemptView).orElse(null),
                    refunded.stream().map(PaymentReadDesk::refundView).toList(),
                    decision.map(PaymentReadDesk::decisionView).orElse(null),
                    disputed.stream().map(found -> disputeSummary(found.dispute())).toList());
        });
    }

    static AttemptView attemptView(PaymentAttempt attempt) {
        return new AttemptView(
                attempt.id().value().toString(),
                attempt.rail().value(),
                attempt.interactionModel().name(),
                attempt.status().name(),
                reference(attempt.authorizationProviderReference()),
                reference(attempt.captureProviderReference()),
                reference(attempt.voidProviderReference()),
                attempt.schemeReference().map(ProviderReference::value).orElse(null),
                attempt.authorizedAmount() == null ? null : decimal(attempt.authorizedAmount()),
                attempt.capturedAmount() == null ? null : decimal(attempt.capturedAmount()),
                attempt.failureReason() == null ? null : attempt.failureReason().name(),
                attempt.createdAt().toString());
    }

    static OperatorRefundView refundView(Refund refund) {
        return new OperatorRefundView(
                refund.id().value().toString(),
                refund.status().name(),
                decimal(refund.amount()),
                refund.amount().currency().code(),
                reference(refund.providerReference()),
                refund.createdAt().toString());
    }

    static RoutingDecisionView decisionView(RoutingDecision decision) {
        return new RoutingDecisionView(
                decision.id().value().toString(),
                decision.chosenRail().map(RailId::value).orElse(null),
                decision.matchedRuleIndex().orElse(null),
                decision.createdAt().toString());
    }

    static DisputeSummaryView disputeSummary(Dispute dispute) {
        return new DisputeSummaryView(
                dispute.id().value().toString(),
                dispute.stage().name(),
                dispute.reason().name(),
                dispute.chargeback().map(PaymentReadDesk::decimal).orElse(null),
                dispute.chargeback().map(amount -> amount.currency().code()).orElse(null),
                dispute.respondBy().map(Instant::toString).orElse(null),
                dispute.openedAt().toString());
    }

    // ------------------------------------------------------------------ a party's payment methods (G-44)

    /** The party's live payment methods, masked - recorded; an unknown or malformed party is the one 404. */
    public PaymentMethodList paymentMethods(String rawPartyId) {
        UUID party;
        try {
            party = UUID.fromString(rawPartyId);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw partyNotFound();
        }
        return inOneTransaction(uow -> {
            PartyId id;
            try {
                id = PartyId.of(party);
            } catch (IllegalArgumentException notAPartyId) {
                throw partyNotFound();
            }
            if (parties.findById(uow, id).isEmpty()) {
                throw partyNotFound();
            }
            List<PaymentMethodService.PaymentMethodView> served =
                    methods.listLiveFor(uow, party).stream().map(PaymentMethodService.PaymentMethodView::of).toList();
            recordedReads.record(uow, PaymentmethodsAuditAction.PAYMENT_METHODS_READ, METHODS_TARGET, party.toString(),
                    "served=" + served.size());
            return new PaymentMethodList(served);
        });
    }

    // ------------------------------------------------------------------ the payments report (G-45)

    /** The day's payments by rail and outcome, and the unknown-state backlog now - read and recorded. */
    public PaymentsReportView report(String rawDate) {
        LocalDate day = date("date", rawDate);
        if (day.isBefore(EARLIEST_REPORT_DAY)) {
            throw OperatorCollection.invalid("date is on or after " + EARLIEST_REPORT_DAY + ".");
        }
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return inOneTransaction(uow -> {
            Instant generatedAt = Instant.now(clock);
            List<PaymentOutcomeRow> outcomes = collections.outcomes(uow, from, to).stream()
                    .map(count -> new PaymentOutcomeRow(
                            count.rail().map(RailId::value).orElse(null),
                            count.attemptStatus().map(Enum::name).orElse(null),
                            count.intentStatus().name(),
                            count.count()))
                    .toList();
            List<PaymentUnknownRow> backlog = collections.unknownBacklog(uow).stream()
                    .map(count -> new PaymentUnknownRow(count.kind(), count.status(), count.count(),
                            count.oldestCreatedAt().toString()))
                    .toList();
            recordedReads.record(uow, PaymentsAuditAction.PAYMENTS_REPORT_READ, REPORT_TARGET, day.toString(),
                    "date=" + day + ", outcomes=" + outcomes.size() + ", unknown=" + backlog.size());
            return new PaymentsReportView(day.toString(), generatedAt.toString(), outcomes, backlog);
        });
    }

    // ------------------------------------------------------------------ the dispute queue (G-42)

    /** A page of the dispute queue, earliest deadline first, an unstated one last - read and recorded. */
    public DisputeQueuePage disputeQueue(String rawStage, String rawDeadlineBefore, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<DisputeStage> stage = Optional.ofNullable(rawStage).map(PaymentReadDesk::disputeStage);
        Optional<Instant> deadlineBefore = Optional.ofNullable(rawDeadlineBefore).map(raw -> instant("deadlineBefore", raw));
        Optional<PaymentCollections.DisputeCursor> after = disputeCursor(cursor);
        Actor actor = SecurityContext.require();
        return inOneTransaction(uow -> {
            List<DisputeStore.Found> rows = collections.disputes(uow, stage, deadlineBefore, after, size + 1);
            List<DisputeStore.Found> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                Dispute tail = served.get(served.size() - 1).dispute();
                next = OperatorCollection.cursor(DISPUTES, tail.respondBy().map(Instant::toString).orElse(UNSTATED),
                        tail.id().value().toString());
            }
            record(uow, actor, PaymentsAuditAction.DISPUTE_QUEUE_READ, QUEUE_TARGET, stage.map(Enum::name).orElse("all"),
                    AuditOutcome.SUCCEEDED, "deadlineBefore=" + deadlineBefore.map(Instant::toString).orElse("none")
                            + ", continued=" + after.isPresent() + ", served=" + served.size() + ", more=" + (next != null));
            return new DisputeQueuePage(served.stream().map(PaymentReadDesk::queueItem).toList(), next);
        });
    }

    /** A cursor's word for a dispute whose deadline the network has not stated - never an instant's shape. */
    static final String UNSTATED = "unstated";

    static Optional<PaymentCollections.DisputeCursor> disputeCursor(String cursor) {
        return OperatorCollection.key(DISPUTES, cursor, 2).map(key -> {
            try {
                Optional<Instant> respondBy = UNSTATED.equals(key.get(0)) ? Optional.empty() : Optional.of(Instant.parse(key.get(0)));
                return new PaymentCollections.DisputeCursor(respondBy, UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static DisputeQueueItem queueItem(DisputeStore.Found found) {
        Dispute dispute = found.dispute();
        return new DisputeQueueItem(
                dispute.id().value().toString(),
                found.intentId().value().toString(),
                dispute.stage().name(),
                dispute.reason().name(),
                dispute.chargeback().map(PaymentReadDesk::decimal).orElse(null),
                dispute.chargeback().map(amount -> amount.currency().code()).orElse(null),
                dispute.respondBy().map(Instant::toString).orElse(null),
                dispute.openedAt().toString());
    }

    // ------------------------------------------------------------------ the request's words

    static PaymentIntentStatus intentStatus(String raw) {
        try {
            return PaymentIntentStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of " + names(PaymentIntentStatus.values()) + ".");
        }
    }

    static DisputeStage disputeStage(String raw) {
        try {
            return DisputeStage.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("stage is one of " + names(DisputeStage.values()) + ".");
        }
    }

    RailId declaredRail(String raw) {
        Optional<RailId> rail = rails.declaredIds().stream().filter(id -> id.value().equals(raw)).findFirst();
        return rail.orElseThrow(() -> OperatorCollection.invalid(
                "rail is one of " + rails.declaredIds().stream().map(RailId::value).sorted().collect(Collectors.joining(", "))
                        + "."));
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

    static LocalDate date(String name, String raw) {
        if (raw == null || !DATE.matcher(raw).matches()) {
            throw OperatorCollection.invalid(name + " is an ISO date (YYYY-MM-DD).");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw OperatorCollection.invalid(name + " is an ISO date (YYYY-MM-DD).");
        }
    }

    static UUID identifier(String name, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw OperatorCollection.invalid(name + " is an identifier.");
        }
    }

    private static String names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ shared

    /** The merchant's payables, through the ledger's owner-scoped read: every account here is provably this merchant's. */
    private Set<LedgerAccountId> payablesOf(Connection uow, UUID merchant) {
        return ledgerAccounts.findAllOwned(uow, merchant).stream()
                .filter(account -> account.purpose() == AccountPurpose.MERCHANT_PAYABLE)
                .map(LedgerAccount::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void record(Connection uow, Actor actor, PaymentsAuditAction action, String targetType,
            String targetId, AuditOutcome outcome, String summary) {
        audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, targetType, targetId,
                Optional.empty(), outcome, correlation(), Optional.of(summary)));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a payment read runs inside a correlation scope"))
                .correlationId();
    }

    private static String reference(Object reference) {
        if (reference == null) {
            return null;
        }
        return reference instanceof ProviderReference provider ? provider.value() : reference.toString();
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /** Malformed equals absent - the one 404. */
    private static PaymentIntentId intentOrAbsent(String raw) {
        try {
            return PaymentIntentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw paymentNotFound();
        }
    }

    private static ApiException paymentNotFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "A payment read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private static ApiException partyNotFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "A payment methods read found no party at the identifier",
                "the requested resource does not exist.");
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }
}
