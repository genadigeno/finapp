package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a seam adapter hands T-eval (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 7.1, 12.1; ADR-0108 decision 1) -
 * channel-neutral: the judged operation, the command's identity, the amount and the movement's subjects, each by
 * reference, plus the flow it runs in. Nothing here is read from another module by risk; the adapter (`-008`…`-011`,
 * in {@code app}) fills it from its own aggregate inside its own transaction.
 *
 * <ul>
 *   <li><strong>The party</strong> is required: whose movement it is - the subject of the {@code PARTY} windows and
 *       of the profile's rating, and the party an operator is refused when it is their own (self-dealing).</li>
 *   <li><strong>The accounts</strong> are <strong>customer account ids</strong> (the ledger's {@code owner_ref}), never a
 *       {@code LedgerAccountId}: the windows and the proof's evidence name the same subject (`-006`). The destination is
 *       an account or a cross-border beneficiary - one role either way.</li>
 *   <li><strong>The acting identity and actor</strong> - the identity whose credential change or recovery the signals
 *       record, and the actor whose session's assurance answers a {@code CHALLENGE} ({@link SessionAssurance} through
 *       {@link RiskInputs}); both absent for a system-initiated movement, which no challenge is satisfied for.</li>
 *   <li><strong>The device</strong> only ever by identity's hash (OQ-4); <strong>the session</strong> the identity
 *       session that confirmed - by its id, never its token - the {@code SESSION} windows' subject (`P13-TSK-014`: the
 *       checkout payment seam's alone); <strong>the corridor</strong> a cross-border payment's {@code SRC-DST-CC}.</li>
 * </ul>
 *
 * Its {@code toString} names the operation and the command only - never a subject or the amount ({@code INV-RSK-08}).
 */
public record RiskEvaluationRequest(
        RiskOperation operation,
        CommandRef commandRef,
        Money amount,
        UUID party,
        Optional<UUID> identity,
        Optional<Actor> actor,
        Optional<UUID> sourceAccount,
        Optional<Destination> destination,
        Optional<DeviceHash> device,
        Optional<UUID> merchant,
        Optional<UUID> session,
        Optional<String> corridor,
        CorrelationId correlation,
        CausationId causation) {

    /** Which kind of thing the money goes to. */
    public enum DestinationKind {
        /** A customer account - by its id (the ledger's {@code owner_ref}). */
        ACCOUNT,
        /** A cross-border beneficiary. */
        BENEFICIARY
    }

    /** Where the money goes: a customer account or a beneficiary, by id. */
    public record Destination(DestinationKind kind, UUID id) {
        public Destination {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }
    }

    public RiskEvaluationRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(commandRef, "commandRef");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sourceAccount, "sourceAccount");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(causation, "causation");
        if (!operation.judgesAMovement()) {
            throw new IllegalArgumentException(operation + " judges no movement: T-eval evaluates the five that do");
        }
        if (amount.minorUnits() < 0 || amount.minorUnits() > EvaluationInputs.MAX_MINOR) {
            throw new IllegalArgumentException("a judged amount is 0 to " + EvaluationInputs.MAX_MINOR + " minor units");
        }
        corridor.ifPresent(code -> {
            if (!ParameterValue.Corridors.CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("a corridor is named SRC-DST-CC");
            }
        });
    }

    /** A request for {@code operation}'s command {@code commandRef}, moving {@code amount} for {@code party}. */
    public static Builder builder(RiskOperation operation, CommandRef commandRef, Money amount, UUID party,
            CorrelationId correlation, CausationId causation) {
        return new Builder(operation, commandRef, amount, party, correlation, causation);
    }

    /**
     * The movement the windows count - every subject in its velocity role: the party, the source account, the
     * destination (an account or a beneficiary - one role), the device, the merchant, the session.
     */
    public VelocityMovement movement() {
        return RiskAssessment.Subjects.of(this).movement(amount);
    }

    /**
     * The inputs every evaluation of this request seals, whatever else is read: the operation, the subjects (the party,
     * the identity, the source account, a beneficiary destination, the device, the merchant, the session - by reference;
     * no rule reads them), the amount and the corridor. A fail-safe seals exactly these.
     */
    public EvaluationInputs.Builder judged() {
        EvaluationInputs.Builder inputs = EvaluationInputs.builder(operation)
                .subject(SignalSubject.of(SubjectKind.PARTY, party))
                .amount(amount);
        identity.ifPresent(id -> inputs.subject(SignalSubject.of(SubjectKind.IDENTITY, id)));
        sourceAccount.ifPresent(id -> inputs.subject(SignalSubject.of(SubjectKind.ACCOUNT, id)));
        destination.filter(to -> to.kind() == DestinationKind.BENEFICIARY)
                .ifPresent(to -> inputs.subject(SignalSubject.of(SubjectKind.BENEFICIARY, to.id())));
        device.ifPresent(hash -> inputs.subject(SignalSubject.device(hash)));
        merchant.ifPresent(id -> inputs.subject(SignalSubject.of(SubjectKind.MERCHANT, id)));
        session.ifPresent(id -> inputs.subject(SignalSubject.of(SubjectKind.SESSION, id)));
        corridor.ifPresent(inputs::corridor);
        return inputs;
    }

    @Override
    public String toString() {
        return "RiskEvaluationRequest[" + operation + ", " + commandRef + "]";
    }

    /** Fills a request role by role; every role not set is absent. */
    public static final class Builder {

        private final RiskOperation operation;
        private final CommandRef commandRef;
        private final Money amount;
        private final UUID party;
        private final CorrelationId correlation;
        private final CausationId causation;
        private Optional<UUID> identity = Optional.empty();
        private Optional<Actor> actor = Optional.empty();
        private Optional<UUID> sourceAccount = Optional.empty();
        private Optional<Destination> destination = Optional.empty();
        private Optional<DeviceHash> device = Optional.empty();
        private Optional<UUID> merchant = Optional.empty();
        private Optional<UUID> session = Optional.empty();
        private Optional<String> corridor = Optional.empty();

        private Builder(RiskOperation operation, CommandRef commandRef, Money amount, UUID party,
                CorrelationId correlation, CausationId causation) {
            this.operation = operation;
            this.commandRef = commandRef;
            this.amount = amount;
            this.party = party;
            this.correlation = correlation;
            this.causation = causation;
        }

        public Builder identity(UUID identity) {
            this.identity = Optional.of(Objects.requireNonNull(identity, "identity"));
            return this;
        }

        public Builder actor(Actor actor) {
            this.actor = Optional.of(Objects.requireNonNull(actor, "actor"));
            return this;
        }

        public Builder sourceAccount(UUID account) {
            this.sourceAccount = Optional.of(Objects.requireNonNull(account, "account"));
            return this;
        }

        public Builder destinationAccount(UUID account) {
            this.destination = Optional.of(new Destination(DestinationKind.ACCOUNT, account));
            return this;
        }

        public Builder destinationBeneficiary(UUID beneficiary) {
            this.destination = Optional.of(new Destination(DestinationKind.BENEFICIARY, beneficiary));
            return this;
        }

        public Builder device(DeviceHash device) {
            this.device = Optional.of(Objects.requireNonNull(device, "device"));
            return this;
        }

        public Builder merchant(UUID merchant) {
            this.merchant = Optional.of(Objects.requireNonNull(merchant, "merchant"));
            return this;
        }

        /** The identity session that confirmed - its id, never its token (`P13-TSK-014`). */
        public Builder session(UUID session) {
            this.session = Optional.of(Objects.requireNonNull(session, "session"));
            return this;
        }

        public Builder corridor(String corridor) {
            this.corridor = Optional.of(Objects.requireNonNull(corridor, "corridor"));
            return this;
        }

        public RiskEvaluationRequest build() {
            return new RiskEvaluationRequest(operation, commandRef, amount, party, identity, actor, sourceAccount,
                    destination, device, merchant, session, corridor, correlation, causation);
        }
    }
}
