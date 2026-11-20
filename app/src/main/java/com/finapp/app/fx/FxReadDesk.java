package com.finapp.app.fx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.fx.AvailabilityStore;
import com.finapp.fx.AvailabilitySubject;
import com.finapp.fx.FxAuditAction;
import com.finapp.fx.FxCollections;
import com.finapp.fx.RateSnapshot;
import com.finapp.fx.RateSnapshotStore;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.fx.TradeStatus;
import com.finapp.fx.TransactionRunner;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The FX operator's reads (`P17-TSK-037`, finapp-admin-panel B9.1-B9.2, gaps G-100, G-101 and G-102): the reference rates and
 * their age, each pair's and each provider's availability with who changed it and when, the enable requests a controller
 * decides, and the booked trades.
 *
 * <p>Each read is one transaction over committed rows. The reference rates and the availability are the platform's own
 * configuration and market data, so they are not recorded; a page of trades is a read of customers' conversions, so every page
 * is recorded {@code fx.TradesListed} in the read's own transaction, as a trade's provenance is.
 *
 * <p>Every list of rows that grows is a §7 collection ({@code API_CONVENTIONS.md}): {@code limit} 50 unless named, 200 at most,
 * the opaque {@code cursor} of the page before, newest first. A trade search names a window of at most 31 days.
 */
@RequiredArgsConstructor
public final class FxReadDesk {

    static final String ENABLE_REQUESTS = "fx.enable-requests";
    static final String TRADES = "fx.trades";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);
    static final String TRADES_TARGET = "fx_trade_search";

    @NonNull private final FxCollections collections;
    @NonNull private final RateSnapshotStore<Connection> rates;
    @NonNull private final Set<String> declaredProviders;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionRunner transactions;

    /** The reference rates of the declared source, one per canonical pair. */
    public record FxRateList(String source, List<FxRateView> items) {}

    /**
     * A pair's latest reference rate - a decimal string, quote currency per one base - when it was observed and received, and
     * its age in whole seconds by the database's clock since it was received, never negative; all absent for a pair never
     * observed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FxRateView(
            String pair, String base, String quote, String rate, String observedAt, String receivedAt, Long ageSeconds) {}

    /** Every pair's or every declared provider's availability. */
    public record FxAvailabilityList(List<FxAvailabilityItem> items) {}

    /**
     * A subject's availability: whether it is available, and the latest fact's controller, instant and reason - absent for a
     * subject never switched, which is available.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FxAvailabilityItem(String subject, boolean available, String changedBy, String changedAt, String reason) {}

    /** A page of enable requests: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FxEnableRequestPage(List<FxEnableRequestItem> items, String nextCursor) {}

    /** One enable request: its subject, where it stands, its proposer and reason, and its decision once taken. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FxEnableRequestItem(
            String id,
            String subjectKind,
            String subject,
            String status,
            String proposedBy,
            String proposedAt,
            String reason,
            String decidedBy,
            String decidedAt,
            String decisionReason) {}

    /** A page of trades: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FxTradePage(List<FxTradeItem> items, String nextCursor) {}

    /** One trade: its owner by identifier, the customer's two amounts as decimal strings with their currencies, and its rate. */
    public record FxTradeItem(
            String id,
            String ownerPartyId,
            String purpose,
            String pair,
            String fixedSide,
            String status,
            String sourceAmount,
            String sourceCurrency,
            String destinationAmount,
            String destinationCurrency,
            String executedRate,
            String providerCode,
            String bookedAt,
            String bookedOn) {}

    // ------------------------------------------------------------------ the reference rates (G-100)

    /** The declared source's latest rate of each canonical pair, with its age. */
    public FxRateList rates() {
        List<FxRateView> items = transactions.inTransaction(uow -> {
            List<FxRateView> views = new ArrayList<>();
            for (ReferencePair pair : ReferenceSourceDeclaration.PAIRS) {
                Optional<RateSnapshot> latest = rates.latest(uow, ReferenceSourceDeclaration.SOURCE, pair);
                Optional<Duration> age = rates.age(uow, ReferenceSourceDeclaration.SOURCE, pair);
                views.add(new FxRateView(
                        pair.code(),
                        pair.base().code(),
                        pair.quote().code(),
                        latest.map(snapshot -> snapshot.rate().value().stripTrailingZeros().toPlainString()).orElse(null),
                        latest.map(snapshot -> snapshot.observedAt().toString()).orElse(null),
                        latest.map(snapshot -> snapshot.receivedAt().toString()).orElse(null),
                        // Never negative: a snapshot committed between the read statement's clock and its snapshot reads
                        // as just received, never as received in the future.
                        age.map(known -> Math.max(0L, known.toSeconds())).orElse(null)));
            }
            return views;
        });
        return new FxRateList(ReferenceSourceDeclaration.SOURCE, List.copyOf(items));
    }

    // ------------------------------------------------------------------ availability (G-101)

    /** Every directed pair of the postable currencies, each with its availability. */
    public FxAvailabilityList pairs() {
        List<AvailabilitySubject> subjects = new ArrayList<>();
        for (CurrencyCode source : SupportedCurrencies.ALL) {
            for (CurrencyCode destination : SupportedCurrencies.ALL) {
                if (!source.equals(destination)) {
                    subjects.add(AvailabilitySubject.pair(source.code() + "-" + destination.code()));
                }
            }
        }
        return availability(AvailabilitySubject.Kind.PAIR, subjects);
    }

    /** Every provider this build declares, each with its availability. */
    public FxAvailabilityList providers() {
        return availability(AvailabilitySubject.Kind.PROVIDER, declaredProviders.stream()
                .sorted()
                .map(code -> AvailabilitySubject.provider(code, declaredProviders))
                .toList());
    }

    private FxAvailabilityList availability(AvailabilitySubject.Kind kind, List<AvailabilitySubject> subjects) {
        Map<AvailabilitySubject, FxCollections.AvailabilityFact> facts =
                transactions.inTransaction(uow -> collections.latestFacts(uow, kind));
        return new FxAvailabilityList(subjects.stream()
                .map(subject -> availabilityItem(subject, Optional.ofNullable(facts.get(subject))))
                .toList());
    }

    static FxAvailabilityItem availabilityItem(AvailabilitySubject subject, Optional<FxCollections.AvailabilityFact> fact) {
        return fact.map(known -> new FxAvailabilityItem(subject.subject(), known.available(), known.actorId(),
                        known.recordedAt().toString(), known.reason()))
                // Never switched: available, as the gate reads it (JdbcAvailabilityStore.isAvailable).
                .orElseGet(() -> new FxAvailabilityItem(subject.subject(), true, null, null, null));
    }

    /** A page of the enable requests, newest first; {@code status} one of the machine's own words. */
    public FxEnableRequestPage enableRequests(String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<AvailabilityStore.EnableRequestStatus> status =
                Optional.ofNullable(rawStatus).map(FxReadDesk::requestStatus);
        Optional<FxCollections.EnableRequestCursor> after = requestCursor(cursor);
        List<FxCollections.EnableRequestEntry> rows =
                transactions.inTransaction(uow -> collections.enableRequests(uow, status, after, size + 1));
        List<FxCollections.EnableRequestEntry> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            FxCollections.EnableRequestEntry tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(ENABLE_REQUESTS, tail.proposedAt().toString(), tail.id().toString());
        }
        return new FxEnableRequestPage(served.stream().map(FxReadDesk::requestItem).toList(), next);
    }

    // ------------------------------------------------------------------ the trades (G-102)

    /** A page of the trades booked in {@code [from, to)}, newest first - read and recorded. */
    public FxTradePage trades(String rawFrom, String rawTo, String rawPair, String rawStatus, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Instant from = instant("from", rawFrom);
        Instant to = instant("to", rawTo);
        if (!from.isBefore(to)) {
            throw OperatorCollection.invalid("from is before to: the window is [from, to).");
        }
        if (Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0) {
            throw OperatorCollection.invalid("the window is at most " + MAXIMUM_WINDOW.toDays() + " days; narrow it.");
        }
        Optional<AvailabilitySubject> pair = Optional.ofNullable(rawPair).map(FxReadDesk::pair);
        Optional<TradeStatus> status = Optional.ofNullable(rawStatus).map(FxReadDesk::tradeStatus);
        Optional<FxCollections.TradeCursor> after = tradeCursor(cursor);
        Actor actor = SecurityContext.require();
        return transactions.inTransaction(uow -> {
            List<FxCollections.TradeHead> rows = collections.trades(
                    uow, new FxCollections.TradeFilter(from, to, pair, status), after, size + 1);
            List<FxCollections.TradeHead> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                FxCollections.TradeHead tail = served.get(served.size() - 1);
                next = OperatorCollection.cursor(TRADES, tail.bookedAt().toString(), tail.id().value().toString());
            }
            audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), FxAuditAction.TRADES_LISTED,
                    TRADES_TARGET, pair.map(AvailabilitySubject::subject).orElse("all"), Optional.empty(),
                    AuditOutcome.SUCCEEDED, correlation(),
                    Optional.of("from=" + from + ", to=" + to + status.map(s -> ", status=" + s).orElse("")
                            + ", continued=" + after.isPresent() + ", served=" + served.size() + ", more=" + (next != null))));
            return new FxTradePage(served.stream().map(FxReadDesk::tradeItem).toList(), next);
        });
    }

    // ------------------------------------------------------------------ the request's words

    static AvailabilityStore.EnableRequestStatus requestStatus(String raw) {
        try {
            return AvailabilityStore.EnableRequestStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of " + names(AvailabilityStore.EnableRequestStatus.values()) + ".");
        }
    }

    static TradeStatus tradeStatus(String raw) {
        try {
            return TradeStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("status is one of " + names(TradeStatus.values()) + ".");
        }
    }

    static AvailabilitySubject pair(String raw) {
        try {
            return AvailabilitySubject.pair(raw);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("pair is SOURCE-DESTINATION, two postable currencies, such as EUR-USD.");
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

    static Optional<FxCollections.EnableRequestCursor> requestCursor(String cursor) {
        return OperatorCollection.key(ENABLE_REQUESTS, cursor, 2).map(key -> {
            try {
                return new FxCollections.EnableRequestCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static Optional<FxCollections.TradeCursor> tradeCursor(String cursor) {
        return OperatorCollection.key(TRADES, cursor, 2).map(key -> {
            try {
                return new FxCollections.TradeCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static FxEnableRequestItem requestItem(FxCollections.EnableRequestEntry entry) {
        return new FxEnableRequestItem(
                entry.id().toString(),
                entry.subject().kind().name(),
                entry.subject().subject(),
                entry.status().name(),
                entry.proposedBy(),
                entry.proposedAt().toString(),
                entry.reason(),
                entry.decidedBy().orElse(null),
                entry.decidedAt().map(Instant::toString).orElse(null),
                entry.decisionReason().orElse(null));
    }

    static FxTradeItem tradeItem(FxCollections.TradeHead trade) {
        return new FxTradeItem(
                trade.id().value().toString(),
                trade.owner().toString(),
                trade.purpose().name(),
                trade.customerSource().currency().code() + "-" + trade.customerDestination().currency().code(),
                trade.fixedSide().name(),
                trade.status().name(),
                decimal(trade.customerSource()),
                trade.customerSource().currency().code(),
                decimal(trade.customerDestination()),
                trade.customerDestination().currency().code(),
                trade.executedRate().toPlainString(),
                trade.providerCode(),
                trade.bookedAt().toString(),
                trade.bookedOn().toString());
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private static String names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an FX read runs inside a correlation scope"))
                .correlationId();
    }
}
