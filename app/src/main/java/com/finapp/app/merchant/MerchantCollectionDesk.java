package com.finapp.app.merchant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAuditAction;
import com.finapp.merchant.MerchantCollections;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayout;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's merchant collections (`P17-TSK-033`, finapp-admin-panel B7.3, gaps G-50, G-51, G-52): the merchants, one
 * merchant's payouts and one payout, and one merchant's transactions - each a §7 collection ({@code API_CONVENTIONS.md}) under
 * {@code MERCHANT_ADMINISTER}, the permission every operator read of a merchant already takes.
 *
 * <h2>What is recorded, and why the line falls there</h2>
 *
 * <p>The merchants page serves what {@code GET /v1/operator/merchants/{id}} serves - a counterparty's name, currency and
 * standing - and is unrecorded as that read is. A merchant's payouts and its transactions are its money, read across tenants
 * by permission: each page and each payout served writes its record in the read's own transaction, so nothing is served
 * unrecorded ({@code payments.DisputeRead}'s and {@code ledger.EntriesSearched}'s rule; finapp-admin-panel ADR-C132 decision
 * 5). A merchant's own reads of the same money over its key stay unrecorded - its own record, its own key.
 *
 * <h2>The transactions are a list, not a statement</h2>
 *
 * <p>The merchant's statement ({@code GET /v1/merchant/transactions}) pages by period because its pages must sum to the
 * period inside one snapshot. This list claims no sum: each page is the movements after the cursor in a declared total order -
 * posting date, then the entry, then the currency, each descending but the currency - within a bounded window of posting
 * dates, each movement exactly as the merchant's own statement renders it.
 */
@RequiredArgsConstructor
public final class MerchantCollectionDesk {

    static final String MERCHANTS = "merchant.merchants";
    static final String PAYOUTS = "merchant.payouts";
    static final String TRANSACTIONS = "merchant.transactions";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /** The widest window of posting dates one transactions page covers, and its default: the merchant statement's own bound. */
    static final int MAXIMUM_WINDOW_DAYS = MerchantTransactionReport.MAX_PERIOD_DAYS;

    /** The audit target of a merchant's money read as a page: the merchant, as its standing's records name it. */
    static final String MERCHANT_TARGET = "merchant";

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    @NonNull private final MerchantCollections collections;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final PayoutDestinationStore<Connection> destinations;
    @NonNull private final MerchantTransactionReport report;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ views

    /** A page of merchants: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MerchantPage(List<MerchantListItem> items, String nextCursor) {}

    /** One merchant as its own read serves it, and when it was onboarded (ISO-8601 UTC) - the list's order. */
    public record MerchantListItem(
            String merchantId, String displayName, String settlementCurrency, String status, String onboardedAt) {}

    /** A page of a merchant's payouts: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MerchantPayoutPage(List<MerchantPayoutOperations.PayoutView> items, String nextCursor) {}

    /** A page of a merchant's transactions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MerchantTransactionPage(List<MerchantTransactionView> items, String nextCursor) {}

    /**
     * One movement on the merchant's payable in {@code currency}, exactly as the merchant's own statement renders it: the
     * {@code kind}, the journal entry, its posting date, {@code gross} and {@code fee} as magnitudes and {@code net} signed -
     * what the entry did to what the platform owes the merchant - and the commercial identifiers that resolve under this
     * merchant, never another's.
     */
    public record MerchantTransactionView(
            String currency,
            String kind,
            String entryId,
            String postingDate,
            String gross,
            String fee,
            String net,
            String checkoutId,
            String orderId,
            String paymentIntentId,
            String refundId,
            String purchaseId,
            String label) {}

    // ------------------------------------------------------------------ the merchants (G-50)

    /** A page of merchants, newest onboarding first; unrecorded, as the merchant's own operator read is. */
    public MerchantPage merchants(String rawStatus, String rawName, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        MerchantCollections.MerchantFilter filter = merchantFilter(rawStatus, rawName);
        Optional<MerchantCollections.MerchantCursor> after = merchantCursor(cursor);
        List<Merchant> rows = inOneTransaction(uow -> collections.merchants(uow, filter, after, size + 1));
        List<Merchant> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            Merchant tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(MERCHANTS, tail.createdAt().toString(), tail.id().value().toString());
        }
        return new MerchantPage(served.stream().map(MerchantCollectionDesk::merchantItem).toList(), next);
    }

    /**
     * The merchants page's filters: a standing of {@link MerchantStatus}'s words, and a name matched exactly - one to
     * {@link Merchant#MAX_NAME_LENGTH} characters, never a pattern - against the legal or the display name.
     */
    static MerchantCollections.MerchantFilter merchantFilter(String rawStatus, String rawName) {
        Optional<MerchantStatus> status = Optional.ofNullable(rawStatus).map(raw -> {
            try {
                return MerchantStatus.valueOf(raw);
            } catch (IllegalArgumentException unknown) {
                throw OperatorCollection.invalid("status is one of ACTIVE, SUSPENDED, CLOSED.");
            }
        });
        Optional<String> name = Optional.ofNullable(rawName).map(raw -> {
            if (raw.isEmpty() || raw.length() > Merchant.MAX_NAME_LENGTH) {
                throw OperatorCollection.invalid(
                        "name is a merchant's legal or display name, 1 to " + Merchant.MAX_NAME_LENGTH + " characters, matched exactly.");
            }
            return raw;
        });
        return new MerchantCollections.MerchantFilter(status, name);
    }

    static Optional<MerchantCollections.MerchantCursor> merchantCursor(String cursor) {
        return OperatorCollection.key(MERCHANTS, cursor, 2).map(key -> {
            try {
                return new MerchantCollections.MerchantCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static MerchantListItem merchantItem(Merchant merchant) {
        return new MerchantListItem(
                merchant.id().value().toString(),
                merchant.displayName(),
                merchant.settlementCurrency().code(),
                merchant.status().name(),
                merchant.createdAt().toString());
    }

    // ------------------------------------------------------------------ a merchant's payouts (G-51)

    /** A page of the merchant's payouts, newest first, each as the merchant's own read renders it - read and recorded. */
    public MerchantPayoutPage payouts(String rawMerchantId, String cursor, String limit) {
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<MerchantCollections.PayoutCursor> after = payoutCursor(cursor);
        return inOneTransaction(uow -> {
            requireMerchant(uow, merchant);
            List<MerchantPayout> rows = collections.payouts(uow, merchant, after, size + 1);
            List<MerchantPayout> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                MerchantPayout tail = served.get(served.size() - 1);
                next = OperatorCollection.cursor(PAYOUTS, tail.createdAt().toString(), tail.id().value().toString());
            }
            List<MerchantPayoutOperations.PayoutView> items = served.stream()
                    .map(payout -> MerchantPayoutOperations.render(destinations, uow, payout, payout.status()))
                    .toList();
            record(uow, MerchantAuditAction.PAYOUTS_LISTED, MERCHANT_TARGET, merchant.value().toString(),
                    "continued=" + after.isPresent() + ", served=" + served.size() + ", more=" + (next != null));
            return new MerchantPayoutPage(items, next);
        });
    }

    static Optional<MerchantCollections.PayoutCursor> payoutCursor(String cursor) {
        return OperatorCollection.key(PAYOUTS, cursor, 2).map(key -> {
            try {
                return new MerchantCollections.PayoutCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    /** One of the merchant's payouts, current - read and recorded. Another merchant's, unknown and malformed are one 404. */
    public MerchantPayoutOperations.PayoutView payout(String rawMerchantId, String rawPayoutId) {
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        MerchantPayoutId id;
        try {
            id = MerchantPayoutId.of(UUID.fromString(rawPayoutId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
        return inOneTransaction(uow -> {
            MerchantPayout payout = payouts.find(uow, merchant, id).orElseThrow(MerchantCollectionDesk::notFound);
            record(uow, MerchantAuditAction.PAYOUT_READ, MerchantPayouts.TARGET_TYPE, id.value().toString(),
                    "merchant=" + merchant.value() + ", status=" + payout.status().name());
            return MerchantPayoutOperations.render(destinations, uow, payout, payout.status());
        });
    }

    // ------------------------------------------------------------------ a merchant's transactions (G-52)

    /** The window a transactions page covers: posting dates, both ends inclusive. */
    record Window(LocalDate from, LocalDate to) {}

    /**
     * A page of the merchant's movements whose posting date lies in {@code [from, to]} - {@code to} today (UTC) and
     * {@code from} thirty days before it unless named, {@value #MAXIMUM_WINDOW_DAYS} days at most - read and recorded.
     */
    public MerchantTransactionPage transactions(
            String rawMerchantId, String rawFrom, String rawTo, String cursor, String limit) {
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Window window = window(rawFrom, rawTo, LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        Optional<MerchantTransactionReport.MovementCursor> after = movementCursor(cursor);
        return inOneTransaction(uow -> {
            requireMerchant(uow, merchant);
            List<MerchantTransactionReport.PlacedMovement> rows =
                    report.page(uow, merchant, window.from(), window.to(), after, size + 1);
            List<MerchantTransactionReport.PlacedMovement> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                MerchantTransactionReport.PlacedMovement tail = served.get(served.size() - 1);
                next = OperatorCollection.cursor(TRANSACTIONS, tail.movement().postingDate().toString(),
                        tail.movement().entry().value().toString(), tail.currency().code());
            }
            record(uow, MerchantAuditAction.TRANSACTIONS_LISTED, MERCHANT_TARGET, merchant.value().toString(),
                    "from=" + window.from() + ", to=" + window.to() + ", continued=" + after.isPresent()
                            + ", served=" + served.size() + ", more=" + (next != null));
            return new MerchantTransactionPage(served.stream().map(MerchantCollectionDesk::transactionView).toList(), next);
        });
    }

    static Window window(String rawFrom, String rawTo, LocalDate today) {
        LocalDate to = rawTo == null ? today : date("to", rawTo);
        LocalDate from = rawFrom == null ? to.minusDays(MAXIMUM_WINDOW_DAYS - 1L) : date("from", rawFrom);
        if (from.isAfter(to)) {
            throw OperatorCollection.invalid("from is after to.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAXIMUM_WINDOW_DAYS) {
            throw OperatorCollection.invalid(
                    "the window from " + from + " to " + to + " is wider than " + MAXIMUM_WINDOW_DAYS + " days; narrow it.");
        }
        return new Window(from, to);
    }

    static Optional<MerchantTransactionReport.MovementCursor> movementCursor(String cursor) {
        return OperatorCollection.key(TRANSACTIONS, cursor, 3).map(key -> {
            try {
                if (!DATE.matcher(key.get(0)).matches()) {
                    throw OperatorCollection.invalidCursor();
                }
                return new MerchantTransactionReport.MovementCursor(
                        LocalDate.parse(key.get(0)), UUID.fromString(key.get(1)),
                        com.finapp.sharedkernel.money.CurrencyCode.of(key.get(2)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static MerchantTransactionView transactionView(MerchantTransactionReport.PlacedMovement placed) {
        MerchantTransactionController.MovementView movement = MerchantTransactionController.render(placed.movement());
        return new MerchantTransactionView(
                placed.currency().code(),
                movement.kind(),
                movement.entryId(),
                movement.postingDate(),
                movement.gross(),
                movement.fee(),
                movement.net(),
                movement.checkoutId(),
                movement.orderId(),
                movement.paymentIntentId(),
                movement.refundId(),
                movement.purchaseId(),
                movement.label());
    }

    private static LocalDate date(String name, String value) {
        if (!DATE.matcher(value).matches()) {
            throw OperatorCollection.invalid(name + " is an ISO date (YYYY-MM-DD).");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException malformed) {
            throw OperatorCollection.invalid(name + " is an ISO date (YYYY-MM-DD).");
        }
    }

    // ------------------------------------------------------------------ shared

    private void requireMerchant(Connection uow, MerchantId merchant) {
        if (merchants.findById(uow, merchant).isEmpty()) {
            throw notFound();
        }
    }

    /** Malformed equals absent - the one 404 ({@code INV-MER-01}'s oracle discipline). */
    private static MerchantId merchantOrAbsent(String rawId) {
        try {
            return MerchantId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A merchant read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    /** The read's record, {@code SUCCEEDED}, in the read's own transaction: served and recorded, or neither. */
    private void record(Connection uow, MerchantAuditAction action, String targetType, String targetId, String summary) {
        audit.append(uow, new AuditRecord(AuditId.next(ids), SecurityContext.require(), Instant.now(clock), action, targetType,
                targetId, Optional.empty(), AuditOutcome.SUCCEEDED, correlation(), Optional.of(summary)));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a merchant read runs inside a correlation scope"))
                .correlationId();
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
