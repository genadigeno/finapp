package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.lending.LoanApplicationId;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOfferId;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Records lending's step spans and links them by the flow's stored correlation (`P11-TSK-030`; PHASE_11_PLAN.md section
 * 15; the {@code CreditFlowScope} precedent, `P10-TSK-020`). Every span is recorded with NO attribute ({@link LendingSpans}).
 *
 * <ul>
 *   <li><strong>A sweep leg</strong> - the offer, the expiry, the disbursement, the payout, the servicing step - runs on any
 *       instance under the system actor and no request. Around it this enters the correlation its row stored (the
 *       application's for an application or offer, the account's for a loan) - the flow every row the step writes already
 *       carries - so the leg's span joins the flow the customer's application or acceptance began.
 *   <li><strong>A door on an existing row</strong> - a repayment, a draw, a payoff, a closure, an amendment's acceptance, an
 *       operator's correction, a withdrawal or a decline - runs the act in the request's own scope and span: what the act
 *       writes - its rows, its audit, its idempotency record - carries the request's correlation exactly as before. Only
 *       once the act has returned, so only once it has judged the row the caller's to act on, is a span of the same name
 *       recorded in the row's stored correlation with the request's correlation as the CAUSE (`P15-TSK-023`: until then the
 *       link was made before ownership was judged, so a stranger's refused request joined another party's flow). An unknown
 *       or malformed identifier, no request scope, or a refused act leaves the request's span alone, unlinked.
 *   <li><strong>A door that begins a flow</strong> - the application, the acceptance - records its span in the request's
 *       scope, which becomes the stored correlation every later leg is linked by.
 * </ul>
 *
 * <p>The lookup is one read on a read-only snapshot of its own, without a lock; a lookup that fails leaves the step to run
 * unlinked, never to fail - a span is telemetry, never the work's precondition. A door's lookup is owner-agnostic: it
 * reads a correlation identifier, never anything the caller is shown, and it runs only after the act it follows has judged
 * ownership and returned.
 */
@Slf4j
public final class LendingFlowScope {

    /** No spans and no lookups: every step runs as it did - the suites' scope. */
    public static final LendingFlowScope NONE = new LendingFlowScope(null, Spans.NONE);

    private final LendingTransactions transactions;
    private final Spans spans;

    public LendingFlowScope(LendingTransactions transactions, Spans spans) {
        this.transactions = transactions;
        this.spans = Objects.requireNonNull(spans, "spans");
    }

    // ------------------------------------------------------------------ sweep legs

    /** A sweep leg on {@code loan}, in the account's stored correlation. */
    public <T> T forLoan(String span, LoanId loan, Supplier<T> work) {
        return leg(spans, span, lookup("lending.loan", loan.value()), work);
    }

    /** A sweep leg on {@code application}, in the application's stored correlation. */
    public <T> T forApplication(String span, LoanApplicationId application, Supplier<T> work) {
        return leg(spans, span, lookup("lending.loan_application", application.value()), work);
    }

    /** A sweep leg on {@code offer}, in the offer's stored correlation - its application's. */
    public <T> T forOffer(String span, LoanOfferId offer, Supplier<T> work) {
        return leg(spans, span, lookup("lending.loan_offer", offer.value()), work);
    }

    // ------------------------------------------------------------------ doors

    /** A door that begins a flow: the span in the request's own scope. */
    public <T> T door(String span, Supplier<T> work) {
        return spans.within(span, Map.of(), work);
    }

    /** A door on the loan {@code rawId} names: the span in the account's correlation, the act in the request's. */
    public <T> T doorOnLoan(String span, String rawId, Supplier<T> work) {
        return linked(spans, span, () -> uuid(rawId).flatMap(id -> lookup("lending.loan", id)), work);
    }

    /** A door on the application {@code rawId} names. */
    public <T> T doorOnApplication(String span, String rawId, Supplier<T> work) {
        return linked(spans, span, () -> uuid(rawId).flatMap(id -> lookup("lending.loan_application", id)), work);
    }

    /** A door on the offer {@code rawId} names. */
    public <T> T doorOnOffer(String span, String rawId, Supplier<T> work) {
        return linked(spans, span, () -> uuid(rawId).flatMap(id -> lookup("lending.loan_offer", id)), work);
    }

    // ------------------------------------------------------------------ the linking, pure

    /** A sweep leg: in {@code stored}'s flow - the one its step's rows carry - or unlinked when there is none. */
    @SuppressWarnings("try") // The scope is used for its close side effect.
    static <T> T leg(Spans spans, String span, Optional<String> stored, Supplier<T> work) {
        Optional<Correlation> flow = stored.flatMap(LendingFlowScope::correlation)
                .map(id -> Correlation.startingWith(id).causing(CausationId.of(id.value())));
        if (flow.isEmpty()) {
            return spans.within(span, Map.of(), work);
        }
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow.get())) {
            return spans.within(span, Map.of(), work);
        }
    }

    /**
     * A door on an existing row (`P15-TSK-023`, closing the privacy residue A5-05): the act first, in the request's own scope and
     * a span of its own, because the act is where ownership is judged; then, <strong>only once the act has returned</strong>, the
     * link - a span of the same name in {@code stored}'s flow, caused by the request's correlation. A refused act (a stranger's
     * identifier, a closed row) throws, so it is never linked into another party's flow, and {@code stored} is never even read
     * for it. Unlinked when there is no request scope or nothing usable is stored.
     */
    @SuppressWarnings("try") // The scope is used for its close side effect.
    static <T> T linked(Spans spans, String span, Supplier<Optional<String>> stored, Supplier<T> work) {
        Optional<Correlation> request = CorrelationContext.current();
        T result = spans.within(span, Map.of(), work);
        if (request.isEmpty()) {
            return result;
        }
        Optional<CorrelationId> flow = stored.get().flatMap(LendingFlowScope::correlation)
                .filter(id -> !id.equals(request.get().correlationId()));
        if (flow.isPresent()) {
            Correlation linked = new Correlation(flow.get(), CausationId.of(request.get().correlationId().value()));
            try (CorrelationContext.Scope scope = CorrelationContext.enter(linked)) {
                spans.within(span, Map.of(), () -> null);
            }
        }
        return result;
    }

    private static Optional<CorrelationId> correlation(String stored) {
        try {
            return Optional.of(CorrelationId.of(stored));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> uuid(String raw) {
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** {@code table}'s stored correlation for {@code id} - the table names are this class's constants, never input. */
    private Optional<String> lookup(String table, UUID id) {
        if (transactions == null) {
            return Optional.empty();
        }
        try {
            return transactions.inSnapshot(uow -> {
                try (PreparedStatement select = uow.prepareStatement(
                        "SELECT correlation_id FROM " + table + " WHERE id = ?")) {
                    select.setObject(1, id);
                    try (ResultSet row = select.executeQuery()) {
                        return row.next() ? Optional.ofNullable(row.getString(1)) : Optional.<String>empty();
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException("the flow's correlation could not be read: " + failure.getSQLState());
                }
            });
        } catch (RuntimeException unreadable) {
            log.warn("A lending flow's correlation could not be read; the step runs unlinked: {}",
                    unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
