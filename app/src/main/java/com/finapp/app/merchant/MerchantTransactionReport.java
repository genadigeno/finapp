package com.finapp.app.merchant;

import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.OrderStore;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.StatementDerivation;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundStore;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the platform owes a merchant, movement by movement, and which purchase each movement
 * belongs to (`P6-TSK-009`) — the merchant transaction report.
 *
 * <h2>The money IS the journal, and ADR-0006 decides how the rest is found</h2>
 *
 * <p>The figures come from {@code ledger}'s own {@link StatementDerivation} of the merchant's
 * payable: an opening, every line, and a closing that reconciles to them <strong>by
 * construction</strong> ({@code P3-TSK-018}'s disjoint-predicates argument). Nothing here is a
 * stored figure and nothing is recomputed ({@code INV-MER-02}); a row's {@code net} is the sum of
 * that entry's lines on the payable, so within a currency {@code Σ net = closing − opening} is
 * not a property this class maintains — it is inherited.
 *
 * <p>Everything else is <em>enrichment</em>, and ADR-0006 is explicit about how it may be done:
 * <em>cross-module data is read through the owning module's API, never by querying its
 * tables</em>. So there is no cross-schema join here. A line's {@code reference} names an attempt
 * (a capture) or a refund; {@code payments} resolves it through its own stores; {@code checkout}
 * resolves the intent to the session and the session to its order through its own. The join is
 * in memory, by stored identifier, one owner at a time.
 *
 * <h2>No other tenant's identifier, at two ranks</h2>
 *
 * <p>The ledger statement is owner-scoped — it returns only accounts whose {@code owner_ref} is
 * this merchant — so every reference this class ever follows came from a line on
 * <strong>this merchant's</strong> payable. That is the authoritative chain, and it is the first
 * rank. The checkout read is the second: it carries {@code merchant_ref = ?} in its own
 * statement, so a capture ever posted to the wrong payable (which {@code MerchantSettlement}
 * refuses, loudly) still could not surface a competitor's session in this report. A row whose
 * chain does not resolve under this merchant is rendered without commercial identifiers, never
 * with somebody else's.
 *
 * <h2>Why the period is the page</h2>
 *
 * <p>Each request is one {@code READ COMMITTED} snapshot, and the reconciliation above holds
 * <em>inside</em> a snapshot. A cursor over the lines of a period would make every page its own
 * snapshot, and a posting that committed between two pages would make the pages fail to sum to
 * the period — silently, which is the one thing a statement must never do. So the period is the
 * unit of pagination, bounded so that one request stays one bounded read.
 */
@RequiredArgsConstructor
public final class MerchantTransactionReport {

    /** The longest period one request may ask for: any calendar month fits. */
    public static final int MAX_PERIOD_DAYS = 31;

    /** What kind of movement an entry is, as seen from the merchant's payable. */
    public enum Kind {
        /** A capture: the gross credited to the payable, the platform's fee debited from it. */
        CAPTURE,
        /** A refund: the gross debited from the payable, any returned fee credited back. */
        REFUND,
        /**
         * Anything else that moved the payable — a payout (`P6-TSK-012`), an operator
         * adjustment. Shown with its money and <em>without</em> commercial identifiers,
         * because the honest answer to "which purchase is this" is sometimes "none".
         */
        OTHER
    }

    /**
     * One movement.
     *
     * @param gross the movement's principal as a magnitude: credited by a capture, debited by
     *     a refund; absent for {@link Kind#OTHER}
     * @param fee the platform's fee as a magnitude: charged by a capture, returned by a refund
     *     under a {@code RETURNED} policy; zero when there is none, absent for {@link Kind#OTHER}
     * @param net what this entry did to what the platform owes the merchant, SIGNED — the only
     *     figure whose sum over the report reconciles to the payable
     */
    public record Movement(
            Kind kind,
            JournalEntryId entry,
            LocalDate postingDate,
            Optional<Money> gross,
            Optional<Money> fee,
            Money net,
            Optional<UUID> checkoutId,
            Optional<UUID> orderId,
            Optional<UUID> paymentIntentId,
            Optional<UUID> refundId) {}

    /** One currency's movements, between an opening and a closing they reconcile to. */
    public record Section(Money opening, List<Movement> movements, Money closing) {}

    @NonNull private final StatementDerivation<Connection> statements;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final CheckoutSessionStore<Connection> sessions;
    @NonNull private final OrderStore<Connection> orders;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /**
     * The merchant's movements for {@code [from, to]}, one section per currency its payable
     * holds. ONE transaction, so every read sees the same snapshot as the statement it enriches.
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to} or the period is
     *     longer than {@link #MAX_PERIOD_DAYS} — the caller's own correctable values
     */
    public List<Section> report(AuthenticatedMerchant merchant, LocalDate from, LocalDate to) {
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'.");
        }
        if (from.plusDays(MAX_PERIOD_DAYS).isBefore(to.plusDays(1))) {
            throw new IllegalArgumentException(
                    "a report covers at most " + MAX_PERIOD_DAYS + " days.");
        }
        UUID tenant = merchant.merchantId().value();
        return inOneTransaction(
                uow ->
                        statements.statementsFor(uow, tenant, from, to).stream()
                                .map(statement -> section(uow, tenant, statement))
                                .toList());
    }

    private Section section(
            Connection uow, UUID tenant, StatementDerivation.AccountStatement statement) {
        // Group the payable's lines by journal entry: a capture has TWO lines on the payable
        // (credit the gross, debit the fee) and they are one movement, not two.
        Map<JournalEntryId, List<StatementDerivation.StatementLine>> byEntry =
                new LinkedHashMap<>();
        for (StatementDerivation.StatementLine line : statement.lines()) {
            byEntry.computeIfAbsent(line.entry(), entry -> new ArrayList<>()).add(line);
        }
        List<Movement> movements = new ArrayList<>();
        for (List<StatementDerivation.StatementLine> lines : byEntry.values()) {
            movements.add(movement(uow, tenant, lines, statement.opening()));
        }
        return new Section(statement.opening(), List.copyOf(movements), statement.closing());
    }

    private Movement movement(
            Connection uow,
            UUID tenant,
            List<StatementDerivation.StatementLine> lines,
            Money zeroShape) {
        StatementDerivation.StatementLine first = lines.get(0);
        Money zero = Money.ofMinorUnits(0L, zeroShape.currency());
        Money credited = zero;
        Money debited = zero;
        for (StatementDerivation.StatementLine line : lines) {
            if (line.direction() == Direction.CREDIT) {
                credited = credited.plus(line.amount());
            } else {
                debited = debited.plus(line.amount());
            }
        }
        // Signed by the LIABILITY's normal balance: a credit increases what is owed.
        Money net = credited.minus(debited);

        Optional<UUID> reference = asUuid(first.reference());
        Optional<PaymentAttempt> captured =
                reference.flatMap(id -> attemptNamed(uow, id));
        if (captured.isPresent()) {
            PaymentIntentId intent = captured.get().intentId();
            Commercial commercial = commercialOf(uow, tenant, intent);
            return new Movement(
                    Kind.CAPTURE,
                    first.entry(),
                    first.postingDate(),
                    Optional.of(credited),
                    Optional.of(debited),
                    net,
                    commercial.checkout(),
                    commercial.order(),
                    commercial.intent(),
                    Optional.empty());
        }
        Optional<Refund> refunded = reference.flatMap(id -> refundNamed(uow, id));
        if (refunded.isPresent()) {
            Refund refund = refunded.get();
            Optional<PaymentIntentId> intent =
                    attempts.findById(uow, refund.attemptId()).map(PaymentAttempt::intentId);
            Commercial commercial =
                    intent.map(id -> commercialOf(uow, tenant, id)).orElse(Commercial.NONE);
            return new Movement(
                    Kind.REFUND,
                    first.entry(),
                    first.postingDate(),
                    Optional.of(debited),
                    Optional.of(credited),
                    net,
                    commercial.checkout(),
                    commercial.order(),
                    commercial.intent(),
                    commercial.intent().isPresent()
                            ? Optional.of(refund.id().value())
                            : Optional.empty());
        }
        return new Movement(
                Kind.OTHER,
                first.entry(),
                first.postingDate(),
                Optional.empty(),
                Optional.empty(),
                net,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** The purchase an intent belongs to — THIS merchant's, or nothing at all. */
    private record Commercial(
            Optional<UUID> checkout, Optional<UUID> order, Optional<UUID> intent) {

        static final Commercial NONE =
                new Commercial(Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Commercial commercialOf(Connection uow, UUID tenant, PaymentIntentId intent) {
        // THE SECOND RANK: merchant_ref = ? in the checkout statement. An intent that is not a
        // checkout payment of THIS merchant's resolves to nothing, and the row keeps its money
        // and loses its identifiers rather than borrowing a competitor's.
        Optional<CheckoutSession> session =
                sessions.findByIntentOwnedBy(uow, intent.value(), tenant);
        if (session.isEmpty()) {
            return Commercial.NONE;
        }
        return new Commercial(
                Optional.of(session.get().id().value()),
                orders.findBySession(uow, session.get().id()).map(order -> order.id().value()),
                Optional.of(intent.value()));
    }

    private Optional<PaymentAttempt> attemptNamed(Connection uow, UUID id) {
        try {
            return attempts.findById(uow, PaymentAttemptId.of(id));
        } catch (IllegalArgumentException notAnAttemptId) {
            // A reference that is not a UUIDv7 names nothing payments minted.
            return Optional.empty();
        }
    }

    private Optional<Refund> refundNamed(Connection uow, UUID id) {
        try {
            return refunds.findById(uow, RefundId.of(id));
        } catch (IllegalArgumentException notARefundId) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> asUuid(String reference) {
        try {
            return Optional.of(UUID.fromString(reference));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
