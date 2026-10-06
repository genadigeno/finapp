package com.finapp.crossborder;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * payments' execution of a cross-border payment, as crossborder asks for it (`P9-TSK-019`, ADR-0080 sections
 * 2 and 5b; PHASE_9_PLAN.md section 3's ports table) - declared here, implemented in {@code app} over
 * payments' routing, its outbound credit, its rails and ledger's holds. crossborder never sees payments'
 * types: the route is an opaque handle, the dispatch comes back as its identifiers, and a refusal carries its
 * vocabulary and code by name for the door to answer.
 */
public interface CrossBorderExecution {

    /**
     * Routes the payment's outbound credit through routing's third subject under the policy in force: the
     * destination country and amount, reachable only by the beneficiary's issuing rail - in the caller's unit
     * of work, nothing written yet.
     *
     * @throws ExecutionRefused {@code PAYMENTS/NO_ELIGIBLE_RAIL} when no candidate is eligible
     */
    Routed route(Connection unitOfWork, RouteAsk ask);

    /**
     * Places the hold under the wallet lock, births the outbound credit {@code DISPATCHED} with our end-to-end
     * reference and the first permit, and records the routing decision on it - in the caller's unit of work.
     *
     * @throws ExecutionRefused {@code FX/INSUFFICIENT_FUNDS}, {@code FX/WALLET_NOT_POSTABLE}
     */
    Dispatched dispatchWithin(Connection unitOfWork, Routed routed, DispatchAsk ask);

    /** The payment's committed dispatch, if Tx1 committed it - a takeover's convergence. */
    Optional<Dispatched> dispatched(Connection unitOfWork, UUID payment);

    /** Renews the dispatch's permit before a takeover re-sends the same reference. */
    void renewPermit(Connection unitOfWork, Dispatched dispatched);

    /** The send to the corridor rail - the same reference every time, no connection held. */
    SendOutcome send(Dispatched dispatched);

    /**
     * Records the send's answer in the caller's unit of work: the bytes as evidence under the outbound credit;
     * a lost answer moves the credit {@code UNKNOWN}, an acknowledgement {@code RECEIVED}; every other answer
     * waits for the outcome appliers (`P9-TSK-020`).
     */
    void recordSend(Connection unitOfWork, Dispatched dispatched, SendOutcome outcome);

    /** What routing judges. */
    record RouteAsk(Money instructed, CountryCode destinationCountry, String beneficiaryRail) {
        public RouteAsk {
            Objects.requireNonNull(instructed, "instructed must not be null");
            Objects.requireNonNull(destinationCountry, "destinationCountry must not be null");
            Objects.requireNonNull(beneficiaryRail, "beneficiaryRail must not be null");
        }
    }

    /** The route - opaque to crossborder. */
    interface Routed {
        /** The chosen rail. */
        String rail();
    }

    /** What the dispatch holds and instructs. */
    record DispatchAsk(
            UUID owner,
            UUID payment,
            String dispatchKey,
            LedgerAccountId wallet,
            Money hold,
            String destinationReference,
            Money instructed) {
        public DispatchAsk {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(payment, "payment must not be null");
            Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
            Objects.requireNonNull(wallet, "wallet must not be null");
            Objects.requireNonNull(hold, "hold must not be null");
            Objects.requireNonNull(destinationReference, "destinationReference must not be null");
            Objects.requireNonNull(instructed, "instructed must not be null");
        }

        @Override
        public String toString() {
            return "DispatchAsk[payment=" + payment + ", destination=<redacted>]";
        }
    }

    /** A committed dispatch: its identifiers and the frozen instruction a send repeats. */
    record Dispatched(
            UUID outboundCredit,
            String endToEndReference,
            UUID hold,
            String rail,
            String status,
            String destinationReference,
            Money instructed) {
        public Dispatched {
            Objects.requireNonNull(outboundCredit, "outboundCredit must not be null");
            Objects.requireNonNull(endToEndReference, "endToEndReference must not be null");
            Objects.requireNonNull(hold, "hold must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(destinationReference, "destinationReference must not be null");
            Objects.requireNonNull(instructed, "instructed must not be null");
        }

        @Override
        public String toString() {
            return "Dispatched[outboundCredit=" + outboundCredit + ", rail=" + rail + ", status=" + status
                    + ", destination=<redacted>]";
        }
    }

    /** A send's answer - opaque to crossborder but for its kind. */
    interface SendOutcome {
        /** The answer's kind by name: RECEIVED, ACCEPTED, REJECTED, NOTHING_SENT or INDETERMINATE. */
        String kind();
    }

    /** payments or the hold refused - {@code vocabulary} is {@code PAYMENTS} or {@code FX}, {@code code} the code's name. */
    final class ExecutionRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String vocabulary;
        private final String code;

        public ExecutionRefused(String vocabulary, String code) {
            super("the execution refused: " + vocabulary + "/" + code);
            this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public String vocabulary() {
            return vocabulary;
        }

        public String code() {
            return code;
        }
    }
}
