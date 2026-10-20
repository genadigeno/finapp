package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplDisputeId;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplSpans;
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
 * Records BNPL's step spans and links them by the flow's stored correlation (`P12-TSK-026`; {@code PHASE_12_PLAN.md}
 * section 15; lending's {@code LendingFlowScope}, `P11-TSK-030`). Every span is recorded with NO attribute
 * ({@link BnplSpans}).
 *
 * <ul>
 *   <li><strong>A sweep leg</strong> - the offer, the expiry, the servicing step, the dispute deadline - runs on any
 *       instance under the system actor and no request. Around it this enters the correlation its row stored (the
 *       application's for an application or offer, the obligation's for a servicing step and for a dispute's deadline) -
 *       the flow every row the step writes belongs to - so the leg's span joins the flow the customer began.
 *   <li><strong>A door on an existing row</strong> - a withdrawal, a decline, a repayment, a quote, a payoff, a refund, a
 *       dispute act, an operator's correction - records its span in the row's stored correlation with the request's
 *       correlation as the CAUSE, then runs the act back in the request's own scope: what the act writes - its rows, its
 *       audit, its idempotency record - carries the request's correlation exactly as before; only the span is linked. An
 *       unknown or malformed identifier, or no request scope, records the span in the request's scope unlinked.
 *   <li><strong>A door that begins a flow</strong> - the application, the acceptance - records its span in the request's
 *       scope, which becomes the stored correlation every later leg is linked by.
 * </ul>
 *
 * <p>The lookup is one read on a read-only snapshot of its own, without a lock; a lookup that fails leaves the step to run
 * unlinked, never to fail - a span is telemetry, never the work's precondition, and ten instances recording one row's
 * spans each read the same stored correlation. A door's lookup is owner-agnostic: it reads a correlation identifier,
 * never anything the caller is shown, and the act it wraps judges ownership and tenancy as before.
 */
@Slf4j
public final class BnplFlowScope {

    /** No spans and no lookups: every step runs as it did - the suites' scope. */
    public static final BnplFlowScope NONE = new BnplFlowScope(null, Spans.NONE);

    /** Where each kind of row's flow is stored - fixed statements, never built from input. */
    enum Flow {
        APPLICATION("SELECT correlation_id FROM bnpl.bnpl_application WHERE id = ?"),
        OFFER("SELECT correlation_id FROM bnpl.bnpl_offer WHERE id = ?"),
        OBLIGATION("SELECT correlation_id FROM bnpl.bnpl_obligation WHERE id = ?"),
        /** The path's agreement names its obligation, whose flow it is. */
        AGREEMENT("SELECT correlation_id FROM bnpl.bnpl_obligation WHERE agreement_id = ?"),
        PURCHASE("SELECT correlation_id FROM bnpl.purchase WHERE id = ?"),
        /** A dispute's acts belong to its obligation's flow. */
        DISPUTE("SELECT o.correlation_id FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_obligation o"
                + " ON o.id = d.obligation_id WHERE d.id = ?");

        final String select;

        Flow(String select) {
            this.select = select;
        }
    }

    private final BnplTransactions transactions;
    private final Spans spans;

    public BnplFlowScope(BnplTransactions transactions, Spans spans) {
        this.transactions = transactions;
        this.spans = Objects.requireNonNull(spans, "spans");
    }

    // ------------------------------------------------------------------ sweep legs

    /** A sweep leg on {@code application}, in the application's stored correlation. */
    public <T> T forApplication(String span, BnplApplicationId application, Supplier<T> work) {
        return leg(spans, span, lookup(Flow.APPLICATION, application.value()), work);
    }

    /** A sweep leg on {@code offer}, in the offer's stored correlation - its application's. */
    public <T> T forOffer(String span, BnplOfferId offer, Supplier<T> work) {
        return leg(spans, span, lookup(Flow.OFFER, offer.value()), work);
    }

    /** A sweep leg on {@code obligation}, in the obligation's stored correlation - its acceptance's. */
    public <T> T forObligation(String span, BnplObligationId obligation, Supplier<T> work) {
        return leg(spans, span, lookup(Flow.OBLIGATION, obligation.value()), work);
    }

    /** A sweep leg on {@code dispute}, in its obligation's stored correlation. */
    public <T> T forDispute(String span, BnplDisputeId dispute, Supplier<T> work) {
        return leg(spans, span, lookup(Flow.DISPUTE, dispute.value()), work);
    }

    // ------------------------------------------------------------------ doors

    /** A door that begins a flow: the span in the request's own scope. */
    public <T> T door(String span, Supplier<T> work) {
        return spans.within(span, Map.of(), work);
    }

    /** A door on the application {@code rawId} names. */
    public <T> T doorOnApplication(String span, String rawId, Supplier<T> work) {
        return doorOn(Flow.APPLICATION, span, rawId, work);
    }

    /** A door on the offer {@code rawId} names. */
    public <T> T doorOnOffer(String span, String rawId, Supplier<T> work) {
        return doorOn(Flow.OFFER, span, rawId, work);
    }

    /** A door on the agreement {@code rawId} names: the span in its obligation's flow, the act in the request's. */
    public <T> T doorOnAgreement(String span, String rawId, Supplier<T> work) {
        return doorOn(Flow.AGREEMENT, span, rawId, work);
    }

    /** A door on the purchase {@code rawId} names. */
    public <T> T doorOnPurchase(String span, String rawId, Supplier<T> work) {
        return doorOn(Flow.PURCHASE, span, rawId, work);
    }

    /** A door on the dispute {@code rawId} names: the span in its obligation's flow. */
    public <T> T doorOnDispute(String span, String rawId, Supplier<T> work) {
        return doorOn(Flow.DISPUTE, span, rawId, work);
    }

    private <T> T doorOn(Flow flow, String span, String rawId, Supplier<T> work) {
        return linked(spans, span, uuid(rawId).flatMap(id -> lookup(flow, id)), work);
    }

    // ------------------------------------------------------------------ the linking, pure

    /** A sweep leg: in {@code stored}'s flow - the one its step's rows belong to - or unlinked when there is none. */
    @SuppressWarnings("try") // The scope is used for its close side effect.
    static <T> T leg(Spans spans, String span, Optional<String> stored, Supplier<T> work) {
        Optional<Correlation> flow = stored.flatMap(BnplFlowScope::correlation)
                .map(id -> Correlation.startingWith(id).causing(CausationId.of(id.value())));
        if (flow.isEmpty()) {
            return spans.within(span, Map.of(), work);
        }
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow.get())) {
            return spans.within(span, Map.of(), work);
        }
    }

    /**
     * A door on an existing row: the span in {@code stored}'s flow caused by the request's correlation, the work back in
     * the request's own scope; unlinked when either is missing.
     */
    @SuppressWarnings("try") // The scopes are used for their close side effect.
    static <T> T linked(Spans spans, String span, Optional<String> stored, Supplier<T> work) {
        Optional<Correlation> request = CorrelationContext.current();
        Optional<CorrelationId> flow = stored.flatMap(BnplFlowScope::correlation);
        if (request.isEmpty() || flow.isEmpty()) {
            return spans.within(span, Map.of(), work);
        }
        Correlation linked = new Correlation(flow.get(), CausationId.of(request.get().correlationId().value()));
        try (CorrelationContext.Scope outer = CorrelationContext.enter(linked)) {
            return spans.within(span, Map.of(), () -> {
                try (CorrelationContext.Scope inner = CorrelationContext.enter(request.get())) {
                    return work.get();
                }
            });
        }
    }

    private static Optional<CorrelationId> correlation(String stored) {
        try {
            return Optional.of(CorrelationId.of(stored));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    static Optional<UUID> uuid(String raw) {
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** {@code flow}'s stored correlation for {@code id}, on a read-only snapshot; empty when absent or unreadable. */
    private Optional<String> lookup(Flow flow, UUID id) {
        if (transactions == null) {
            return Optional.empty();
        }
        try {
            return transactions.inSnapshot(uow -> {
                try (PreparedStatement select = uow.prepareStatement(flow.select)) {
                    select.setObject(1, id);
                    try (ResultSet row = select.executeQuery()) {
                        return row.next() ? Optional.ofNullable(row.getString(1)) : Optional.<String>empty();
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException("the flow's correlation could not be read: " + failure.getSQLState());
                }
            });
        } catch (RuntimeException unreadable) {
            log.warn("A BNPL flow's correlation could not be read; the step runs unlinked: {}",
                    unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
