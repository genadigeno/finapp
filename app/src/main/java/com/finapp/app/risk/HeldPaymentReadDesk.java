package com.finapp.app.risk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.payments.PaymentCollections;
import com.finapp.payments.PaymentsAuditAction;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.RiskActingParty;
import com.finapp.risk.RiskAuditAction;
import com.finapp.risk.RiskErrorCode;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The risk investigator's list of held card payments (`P17-TSK-032`, finapp-admin-panel B7.2 item 7, gap G-43): the open capture
 * holds - payments risk held, awaiting its override or action - under {@code RISK_INVESTIGATE}, a §7 collection
 * ({@code API_CONVENTIONS.md}), the hold's deadline earliest first and the intent breaking the tie.
 *
 * <h2>A held payment's doors stay risk's</h2>
 *
 * <p>The list is read-only (ADR-0112): capture and void are risk's override and action doors. It follows risk's own rule for an
 * investigator's reads - the reader's own party's payments withheld, and a reader the platform cannot place in a party refused
 * {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED} in a transaction of its own (`P17-TSK-020`'s shape). Every page
 * served is recorded {@code payments.HeldPaymentsListed} in the read's own transaction.
 *
 * <p>A risk desk, wired in risk's own configuration: no customer or merchant surface reaches it
 * ({@code NoCustomerSurfaceReadsRiskTest}).
 */
@RequiredArgsConstructor
public final class HeldPaymentReadDesk {

    static final String HELD = "payments.held-payments";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    static final String HELD_TARGET = "held_payments";

    @NonNull private final PaymentCollections collections;
    @NonNull private final RiskActingParty actingParties;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** A page of held card payments: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeldPaymentPage(List<HeldPaymentView> items, String nextCursor) {}

    /** One held card payment: its payer's party by identifier, amount, rail, when it was held and the hold's deadline. */
    public record HeldPaymentView(
            String paymentIntentId,
            String attemptId,
            String partyId,
            String amount,
            String currency,
            String rail,
            String heldAt,
            String heldUntil) {}

    /**
     * A page of the open capture holds, earliest deadline first - the reader's own party's withheld - read and recorded. A
     * reader the platform cannot place in a party is refused, recorded {@code FAILED} in a transaction of its own.
     */
    public HeldPaymentPage heldPayments(String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<PaymentCollections.HeldCursor> after = heldCursor(cursor);
        Actor actor = SecurityContext.require();
        Optional<HeldPaymentPage> page = inOneTransaction(uow -> {
            Optional<UUID> own = actingParties.partyOf(uow, actor);
            if (own.isEmpty()) {
                return Optional.<HeldPaymentPage>empty();
            }
            List<PaymentCollections.HeldPayment> rows = collections.heldPayments(uow, own.get(), after, size + 1);
            List<PaymentCollections.HeldPayment> served = rows.subList(0, Math.min(size, rows.size()));
            String next = null;
            if (rows.size() > size) {
                PaymentCollections.HeldPayment tail = served.get(served.size() - 1);
                next = OperatorCollection.cursor(HELD, tail.heldUntil().toString(), tail.intent().value().toString());
            }
            record(uow, actor, PaymentsAuditAction.HELD_PAYMENTS_LISTED, AuditOutcome.SUCCEEDED,
                    "continued=" + after.isPresent() + ", served=" + served.size() + ", more=" + (next != null));
            return Optional.of(new HeldPaymentPage(served.stream().map(HeldPaymentReadDesk::heldView).toList(), next));
        });
        if (page.isPresent()) {
            return page.get();
        }
        // Nothing was served: the refusal is recorded on its own, as risk's other reads record theirs.
        inOneTransaction(uow -> {
            record(uow, actor, RiskAuditAction.SELF_DEALING_REFUSED, AuditOutcome.FAILED,
                    "act=held-payments, cause=UNPLACEABLE refused");
            return null;
        });
        throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A held payments read was refused",
                "a holder never reads held payments when the platform cannot place them in a party; nothing was served.");
    }

    static Optional<PaymentCollections.HeldCursor> heldCursor(String cursor) {
        return OperatorCollection.key(HELD, cursor, 2).map(key -> {
            try {
                return new PaymentCollections.HeldCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    static HeldPaymentView heldView(PaymentCollections.HeldPayment held) {
        return new HeldPaymentView(
                held.intent().value().toString(),
                held.attempt().value().toString(),
                held.party().toString(),
                decimal(held.amount()),
                held.amount().currency().code(),
                held.rail().value(),
                held.heldAt().toString(),
                held.heldUntil().toString());
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private void record(Connection uow, Actor actor, AuditableAction action, AuditOutcome outcome, String summary) {
        audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, HELD_TARGET, "open",
                Optional.empty(), outcome, correlation(), Optional.of(summary)));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a held payments read runs inside a correlation scope"))
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
