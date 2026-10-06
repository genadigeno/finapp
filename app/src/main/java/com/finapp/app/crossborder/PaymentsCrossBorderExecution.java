package com.finapp.app.crossborder;

import com.finapp.crossborder.CrossBorderExecution;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.payments.CorridorRail;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.OutboundCreditId;
import com.finapp.payments.OutboundCreditOutcomes;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.PaymentDirection;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.RailOperations;
import com.finapp.payments.RoutingDecision;
import com.finapp.payments.RoutingInputs;
import com.finapp.payments.RoutingPlan;
import com.finapp.payments.RoutingPolicyVersion;
import com.finapp.payments.RoutingStore;
import com.finapp.payments.RoutingSubject;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * crossborder's {@link CrossBorderExecution} over payments and ledger (`P9-TSK-019`, ADR-0080 sections 2 and 5b,
 * PHASE_9_PLAN.md section 12.8): routing's third subject under the policy in force, reachable only by the
 * beneficiary's issuing rail; the hold on the source wallet under its lock ({@code HoldService}); the outbound
 * credit born {@code DISPATCHED} with our end-to-end reference minted once; the routing decision on it; the
 * send through the corridor rail the {@code RailOperations} directory names - and the answer recorded, its bytes
 * retained under the outbound credit and judged by {@link OutboundCreditOutcomes} as the platform (`P9-TSK-020`).
 */
public final class PaymentsCrossBorderExecution implements CrossBorderExecution {

    private final RoutingStore<Connection> routing;
    private final PaymentRails rails;
    private final RailOperations operations;
    private final HoldService holds;
    private final OutboundCreditStore credits;
    private final ProviderEvidenceStore<Connection> evidence;
    private final IdGenerator ids;
    private final Clock clock;
    private final OutboundCreditOutcomes outcomes;

    public PaymentsCrossBorderExecution(
            RoutingStore<Connection> routing,
            PaymentRails rails,
            RailOperations operations,
            HoldService holds,
            OutboundCreditStore credits,
            ProviderEvidenceStore<Connection> evidence,
            IdGenerator ids,
            Clock clock,
            OutboundCreditOutcomes outcomes) {
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.credits = Objects.requireNonNull(credits, "credits must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
    }

    private record Route(RoutingPolicyVersion version, RoutingInputs inputs, RoutingPlan plan, RailId chosen)
            implements Routed {
        @Override
        public String rail() {
            return chosen.value();
        }
    }

    private record Sent(CorridorRail.SendAnswer answer) implements SendOutcome {
        @Override
        public String kind() {
            return switch (answer) {
                case CorridorRail.SendAnswer.Received received -> "RECEIVED";
                case CorridorRail.SendAnswer.Accepted accepted -> "ACCEPTED";
                case CorridorRail.SendAnswer.Rejected rejected -> "REJECTED";
                case CorridorRail.SendAnswer.NothingSent nothing -> "NOTHING_SENT";
                case CorridorRail.SendAnswer.Indeterminate unknown -> "INDETERMINATE";
            };
        }

        Optional<byte[]> bytes() {
            return switch (answer) {
                case CorridorRail.SendAnswer.Received received -> Optional.of(received.evidence().body());
                case CorridorRail.SendAnswer.Accepted accepted -> Optional.of(accepted.evidence().body());
                case CorridorRail.SendAnswer.Rejected rejected -> Optional.of(rejected.evidence().body());
                case CorridorRail.SendAnswer.NothingSent nothing -> Optional.empty();
                case CorridorRail.SendAnswer.Indeterminate unknown -> unknown.evidence().map(CorridorRail.Evidence::body);
            };
        }
    }

    @Override
    public Routed route(Connection unitOfWork, RouteAsk ask) {
        RoutingPolicyVersion version = routing.findVersionInForce(unitOfWork, Instant.now(clock))
                .orElseThrow(() -> new ExecutionRefused("PAYMENTS", "NO_ELIGIBLE_RAIL"));
        RailId issuing;
        try {
            issuing = RailId.of(ask.beneficiaryRail());
        } catch (IllegalArgumentException malformed) {
            throw new ExecutionRefused("PAYMENTS", "NO_ELIGIBLE_RAIL");
        }
        RoutingInputs inputs = new RoutingInputs(PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT, ask.instructed(),
                Optional.empty(), Optional.of(ask.destinationCountry()), Optional.of(Set.of(issuing)));
        RoutingPlan plan = version.decide(inputs, rails, routing.availabilityByRail(unitOfWork));
        RailId chosen = plan.chosen().orElseThrow(() -> new ExecutionRefused("PAYMENTS", "NO_ELIGIBLE_RAIL"));
        if (operations.corridorRail(chosen).isEmpty()) {
            // A routed rail that cannot carry a corridor credit: refused with nothing written or sent (ADR-0080 2).
            throw new ExecutionRefused("PAYMENTS", "NO_ELIGIBLE_RAIL");
        }
        return new Route(version, inputs, plan, chosen);
    }

    @Override
    public Dispatched dispatchWithin(Connection unitOfWork, Routed routed, DispatchAsk ask) {
        Route route = (Route) routed;
        Hold hold;
        try {
            hold = holds.place(unitOfWork, ask.wallet(), ask.hold());
        } catch (HoldExceedsAvailableBalanceException unfunded) {
            throw new ExecutionRefused("FX", "INSUFFICIENT_FUNDS");
        } catch (LedgerAccountNotPostableException closed) {
            throw new ExecutionRefused("FX", "WALLET_NOT_POSTABLE");
        }
        OutboundCreditId id = OutboundCreditId.next(ids);
        EndToEndReference reference = new EndToEndReference(lettersOnly(ids.next()));
        credits.insert(unitOfWork, new OutboundCreditStore.Draft(id, ask.owner(), ask.payment(), ask.dispatchKey(),
                route.chosen(), new ProviderReference(ask.destinationReference()), ask.instructed(), ask.hold(),
                hold.id().value(), reference));
        routing.insertDecision(unitOfWork, RoutingDecision.create(ids, clock, RoutingSubject.ofOutboundCredit(id),
                route.version().id(), route.inputs(), route.plan()));
        return new Dispatched(id.value(), reference.value(), hold.id().value(), route.chosen().value(), "DISPATCHED",
                ask.destinationReference(), ask.instructed());
    }

    /**
     * Our end-to-end reference for a fresh credit (`P9-TSK-022`): the identifier's 32 hexadecimal digits rendered in
     * letters alone ({@code 0-9} as {@code g-p}) - unique as the identifier is, and never carrying a run of digits a
     * provider's report screen would read as a card number (the corridor format refuses a 13-digit run).
     */
    static String lettersOnly(java.util.UUID id) {
        StringBuilder letters = new StringBuilder(32);
        for (char hex : id.toString().replace("-", "").toCharArray()) {
            letters.append(Character.isDigit(hex) ? (char) ('g' + (hex - '0')) : hex);
        }
        return letters.toString();
    }

    @Override
    public Optional<Dispatched> dispatched(Connection unitOfWork, UUID payment) {
        return credits.bySubject(unitOfWork, payment).map(row -> new Dispatched(row.id().value(), row.reference().value(),
                row.holdId(), row.rail().value(), row.status().name(), row.destination().value(), row.amount()));
    }

    @Override
    public boolean renewPermit(Connection unitOfWork, Dispatched dispatched) {
        return credits.renewPermit(unitOfWork, OutboundCreditId.of(dispatched.outboundCredit()));
    }

    @Override
    public RecallRequest requestRecall(Connection unitOfWork, UUID outboundCredit) {
        // The credit FOR UPDATE: the outcome applier's lock, taken before crossborder locks the payment.
        com.finapp.payments.OutboundCreditStore.Row locked = credits.lock(unitOfWork, OutboundCreditId.of(outboundCredit))
                .orElseThrow(() -> new IllegalStateException("a payment's outbound credit always exists"));
        if (!locked.status().resolvable()) {
            return RecallRequest.NOT_RECALLABLE;
        }
        if (locked.recallRequestedAt().isPresent()) {
            return RecallRequest.ALREADY_REQUESTED;
        }
        return credits.requestRecall(unitOfWork, locked.id()) ? RecallRequest.REQUESTED : RecallRequest.NOT_RECALLABLE;
    }

    @Override
    public SendOutcome send(Dispatched dispatched) {
        CorridorRail rail = operations.corridorRail(RailId.of(dispatched.rail()))
                .orElseThrow(() -> new IllegalStateException("a dispatched credit's rail speaks the corridor port"));
        CorridorRail.SendAnswer answer = rail.send(new CorridorRail.CreditInstruction(
                new EndToEndReference(dispatched.endToEndReference()), new ProviderReference(dispatched.destinationReference()),
                dispatched.instructed()));
        return new Sent(answer);
    }

    @Override
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public void recordSend(Connection unitOfWork, Dispatched dispatched, SendOutcome outcome) {
        Sent sent = (Sent) outcome;
        OutboundCreditId id = OutboundCreditId.of(dispatched.outboundCredit());
        OutboundCreditStore.Row locked = credits.lock(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a dispatched credit always reads back"));
        sent.bytes().ifPresent(bytes -> evidence.appendForOutboundCredit(unitOfWork, id, EvidenceKind.RESPONSE, bytes,
                Instant.now(clock)));
        // The platform applies every outcome, whichever resolver wins the harmless race - the synchronous answer here,
        // a hinted inquiry or the sweep - so the customer's request thread never decides the actor (the withdrawal's
        // rule: the dispatch is the person's, the outcome the platform's).
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            outcomes.applySendAnswer(unitOfWork, locked, sent.answer(),
                    CorrelationContext.current().orElseThrow(() -> new IllegalStateException(
                            "a send's answer is recorded inside a correlation scope")));
        }
    }
}
