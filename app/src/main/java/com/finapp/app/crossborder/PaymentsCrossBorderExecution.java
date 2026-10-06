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
 * retained under the outbound credit. The outcome appliers are `P9-TSK-020`'s.
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

    public PaymentsCrossBorderExecution(
            RoutingStore<Connection> routing,
            PaymentRails rails,
            RailOperations operations,
            HoldService holds,
            OutboundCreditStore credits,
            ProviderEvidenceStore<Connection> evidence,
            IdGenerator ids,
            Clock clock) {
        this.routing = Objects.requireNonNull(routing, "routing must not be null");
        this.rails = Objects.requireNonNull(rails, "rails must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.credits = Objects.requireNonNull(credits, "credits must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    private record Route(RoutingPolicyVersion version, RoutingInputs inputs, RoutingPlan plan, RailId chosen)
            implements Routed {
        @Override
        public String rail() {
            return chosen.value();
        }
    }

    private record Sent(String kind, Optional<byte[]> bytes, Optional<String> providerReference) implements SendOutcome {}

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
        EndToEndReference reference = new EndToEndReference(ids.next().toString().replace("-", ""));
        credits.insert(unitOfWork, new OutboundCreditStore.Draft(id, ask.owner(), ask.payment(), ask.dispatchKey(),
                route.chosen(), new ProviderReference(ask.destinationReference()), ask.instructed(), ask.hold(),
                hold.id().value(), reference));
        routing.insertDecision(unitOfWork, RoutingDecision.create(ids, clock, RoutingSubject.ofOutboundCredit(id),
                route.version().id(), route.inputs(), route.plan()));
        return new Dispatched(id.value(), reference.value(), hold.id().value(), route.chosen().value(), "DISPATCHED",
                ask.destinationReference(), ask.instructed());
    }

    @Override
    public Optional<Dispatched> dispatched(Connection unitOfWork, UUID payment) {
        return credits.bySubject(unitOfWork, payment).map(row -> new Dispatched(row.id().value(), row.reference().value(),
                row.holdId(), row.rail().value(), row.status().name(), row.destination().value(), row.amount()));
    }

    @Override
    public void renewPermit(Connection unitOfWork, Dispatched dispatched) {
        credits.renewPermit(unitOfWork, OutboundCreditId.of(dispatched.outboundCredit()));
    }

    @Override
    public SendOutcome send(Dispatched dispatched) {
        CorridorRail rail = operations.corridorRail(RailId.of(dispatched.rail()))
                .orElseThrow(() -> new IllegalStateException("a dispatched credit's rail speaks the corridor port"));
        CorridorRail.SendAnswer answer = rail.send(new CorridorRail.CreditInstruction(
                new EndToEndReference(dispatched.endToEndReference()), new ProviderReference(dispatched.destinationReference()),
                dispatched.instructed()));
        return switch (answer) {
            case CorridorRail.SendAnswer.Received received -> new Sent("RECEIVED", Optional.of(received.evidence().body()), Optional.empty());
            case CorridorRail.SendAnswer.Accepted accepted -> new Sent("ACCEPTED", Optional.of(accepted.evidence().body()),
                    Optional.of(accepted.providerReference().value()));
            case CorridorRail.SendAnswer.Rejected rejected -> new Sent("REJECTED", Optional.of(rejected.evidence().body()), Optional.empty());
            case CorridorRail.SendAnswer.NothingSent nothing -> new Sent("NOTHING_SENT", Optional.empty(), Optional.empty());
            case CorridorRail.SendAnswer.Indeterminate unknown -> new Sent("INDETERMINATE",
                    unknown.evidence().map(CorridorRail.Evidence::body), Optional.empty());
        };
    }

    @Override
    public void recordSend(Connection unitOfWork, Dispatched dispatched, SendOutcome outcome) {
        Sent sent = (Sent) outcome;
        OutboundCreditId id = OutboundCreditId.of(dispatched.outboundCredit());
        OutboundCreditStore.Row locked = credits.lock(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a dispatched credit always reads back"));
        sent.bytes().ifPresent(bytes -> evidence.appendForOutboundCredit(unitOfWork, id, EvidenceKind.RESPONSE, bytes,
                Instant.now(clock)));
        switch (sent.kind()) {
            case "INDETERMINATE" -> {
                if (locked.status() == OutboundCreditStore.Status.DISPATCHED) {
                    credits.move(unitOfWork, id, OutboundCreditStore.Status.DISPATCHED, OutboundCreditStore.Status.UNKNOWN);
                }
            }
            case "RECEIVED" -> {
                if (locked.status() == OutboundCreditStore.Status.DISPATCHED
                        || locked.status() == OutboundCreditStore.Status.UNKNOWN) {
                    credits.move(unitOfWork, id, locked.status(), OutboundCreditStore.Status.RECEIVED);
                }
            }
            default -> {
                // ACCEPTED, REJECTED and NOTHING_SENT conclude through the outcome appliers (P9-TSK-020), which
                // own the completion, the failure, the hold's release and the quote's close; the bytes are kept.
            }
        }
    }
}
