package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The payout destination commands (`P6-TSK-011`, ADR-0056): propose, approve, reject, withdraw
 * — every one an operator's act, audited with actor, reason and correlation — and the plain
 * read of the effective destination, {@link #effectiveFor}.
 *
 * <h2>Four-eyes is two authenticated acts, never one request carrying two names</h2>
 *
 * <p>The {@code P3-TSK-021} shape, applied to its second subject: an operator proposes (nothing
 * pays), and a <em>second</em> operator approves in a transaction of their own. The approver is
 * judged distinct from the proposer three times, each layer blind in a different direction — the
 * aggregate, the approval statement's own {@code proposed_by <> ?}, and `V006`'s {@code CHECK}.
 *
 * <h2>A refused self-approval commits its own evidence</h2>
 *
 * <p>{@link #approve} does not throw on a self-approval: it writes the {@code DENIED} record and
 * returns {@link SelfApprovalRefused}, so the caller's transaction commits the refusal and
 * nothing else. An exception would roll the evidence back with the attempt, and a control that
 * leaves no trace of refusing anything is one nobody can show is working ({@code INV-AUD-03}).
 *
 * <h2>Retries converge through state</h2>
 *
 * <p>The proposal is keyed ({@code INV-IDEM-01}): a lost response's retry replays the recorded
 * destination. The decisions converge by machine — the same approver's retry finds their own
 * approval, a repeated rejection or withdrawal finds the terminal state — and a convergence is no
 * second act, so it writes no second record.
 */
public final class PayoutDestinations {

    /** The audit target type: the aggregate's own name, as the ledger's proposal uses its own. */
    static final String TARGET_TYPE = "merchant.PayoutDestination";

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    static final String PROPOSE_SCOPE = "merchant.payout-destination.propose";

    private final PayoutDestinationStore<Connection> destinations;
    private final MerchantStore<Connection> merchants;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration coolingOff;

    /**
     * @param coolingOff the period an approved destination waits before it can take effect —
     *     positive, because a zero cooling-off is the control switched off, and a
     *     misconfiguration should refuse to start rather than quietly disable it
     */
    public PayoutDestinations(
            PayoutDestinationStore<Connection> destinations,
            MerchantStore<Connection> merchants,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            Duration coolingOff) {
        this.destinations = Objects.requireNonNull(destinations, "destinations must not be null");
        this.merchants = Objects.requireNonNull(merchants, "merchants must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.coolingOff = Objects.requireNonNull(coolingOff, "coolingOff must not be null");
        if (coolingOff.isNegative() || coolingOff.isZero()) {
            throw new IllegalArgumentException(
                    "the payout destination cooling-off must be positive: " + coolingOff);
        }
    }

    /**
     * An operator's proposal, already tokenised: the reference the exchange returned, never the
     * grant or anything rawer.
     */
    public record ProposeCommand(
            String idempotencyKey,
            MerchantId merchantId,
            PayoutDestinationTokenisation.TokenisedDestination destination,
            String reason) {

        public ProposeCommand {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            Objects.requireNonNull(merchantId, "merchantId must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** The proposed destination, and whether this call replayed a recorded proposal. */
    public record ProposalResult(PayoutDestinationId destinationId, boolean replayed) {}

    /** What an approval attempt came to. */
    public sealed interface Approval permits Approved, SelfApprovalRefused {}

    /** Approved — by this call, or {@code replayed} onto this approver's own earlier approval. */
    public record Approved(PayoutDestination destination, boolean replayed) implements Approval {}

    /** The proposer tried to approve; the {@code DENIED} record is written, nothing else. */
    public record SelfApprovalRefused() implements Approval {}

    /**
     * Proposes the destination, or replays the recorded proposal for a retried key.
     *
     * @throws UnknownMerchantException no merchant answers to the identifier
     * @throws MerchantClosedException the merchant is closed
     * @throws PayoutDestinationChangePendingException the merchant already has an open change
     */
    public ProposalResult propose(Connection unitOfWork, ProposeCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        IdempotencyKey key = new IdempotencyKey(PROPOSE_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint = RequestFingerprint.sha256(canonicalForm(command, actor));
        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> accept(uow, command, actor, correlation));
        String recorded =
                new String(
                        outcome.body()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a recorded proposal always carries the"
                                                                + " destination id")),
                        StandardCharsets.UTF_8);
        return new ProposalResult(
                PayoutDestinationId.of(UUID.fromString(recorded)), outcome.replayed());
    }

    /** The proposal itself, inside the claim: every check here replays with the outcome. */
    private CommandResult accept(
            Connection uow, ProposeCommand command, Actor actor, Correlation correlation) {
        Merchant merchant =
                merchants
                        .findById(uow, command.merchantId())
                        .orElseThrow(UnknownMerchantException::new);
        if (merchant.status() == MerchantStatus.CLOSED) {
            throw new MerchantClosedException();
        }
        PayoutDestination proposed =
                PayoutDestination.propose(
                        ids,
                        clock,
                        merchant.id(),
                        command.destination().reference(),
                        command.destination().displaySuffix(),
                        actor.id(),
                        command.reason());
        destinations.insert(uow, proposed);
        record(
                uow,
                actor,
                MerchantAuditAction.PAYOUT_DESTINATION_PROPOSED,
                proposed,
                Optional.of(command.reason()),
                AuditOutcome.SUCCEEDED,
                correlation,
                "destination=" + proposed.id() + ", merchant=" + merchant.id());
        return CommandResult.succeeded(
                StoredResponse.of(
                        proposed.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    /**
     * Approves the proposal as the acting person: the cooling-off starts, and its deadline is
     * pinned on the row.
     *
     * <p><strong>Lock-then-look</strong> ({@code P2-TSK-015}): {@code FOR UPDATE} on the row,
     * then judge status and person, then the conditional write — so N racing approvals produce one
     * approval, and the losers resume onto the winner's committed decision.
     *
     * @return {@link Approved}, or {@link SelfApprovalRefused} with the {@code DENIED} record
     *     written for the caller's transaction to commit
     * @throws UnknownPayoutDestinationException no such destination under the merchant
     * @throws IllegalPayoutDestinationTransitionException a different decision already stands
     */
    public Approval approve(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id, String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        PayoutDestination before = locked(unitOfWork, merchant, id);

        // The same approver's retry converges on their own recorded approval (INV-IDEM-01
        // through state): the machine, not a stored response, answers a lost response.
        if (before.approvedBy().filter(actor.id()::equals).isPresent()) {
            return new Approved(before, true);
        }
        PayoutDestination approved;
        try {
            approved = before.approve(actor.id(), clock, coolingOff);
        } catch (PayoutDestinationSelfApprovalException refused) {
            record(
                    unitOfWork,
                    actor,
                    MerchantAuditAction.PAYOUT_DESTINATION_APPROVAL_REFUSED,
                    before,
                    Optional.of(reason),
                    AuditOutcome.DENIED,
                    correlation,
                    "destination=" + before.id() + ", merchant=" + before.merchantId()
                            + ", refused=self-approval");
            return new SelfApprovalRefused();
        }
        requireLanded(unitOfWork, before, approved);
        record(
                unitOfWork,
                actor,
                MerchantAuditAction.PAYOUT_DESTINATION_APPROVED,
                approved,
                Optional.of(reason),
                AuditOutcome.SUCCEEDED,
                correlation,
                "destination=" + approved.id() + ", merchant=" + approved.merchantId()
                        + ", coolingOffUntil=" + approved.coolingOffUntil().orElseThrow());
        return new Approved(approved, false);
    }

    /**
     * The second pair of eyes says no: {@code PROPOSED → REJECTED}, terminal. Converges on an
     * already-rejected change.
     */
    public PayoutDestination reject(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id, String reason) {
        return end(
                unitOfWork,
                merchant,
                id,
                reason,
                PayoutDestinationStatus.REJECTED,
                MerchantAuditAction.PAYOUT_DESTINATION_REJECTED);
    }

    /**
     * Withdraws the change before it takes effect — from {@code PROPOSED}, or from
     * {@code APPROVED} during the cooling-off. Terminal; converges on an already-withdrawn change.
     */
    public PayoutDestination withdraw(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id, String reason) {
        return end(
                unitOfWork,
                merchant,
                id,
                reason,
                PayoutDestinationStatus.WITHDRAWN,
                MerchantAuditAction.PAYOUT_DESTINATION_WITHDRAWN);
    }

    /**
     * The merchant's committed {@code EFFECTIVE} destination, or empty when it has none - the
     * operator's list reads it. A change still cooling off changes nothing here until the
     * platform has made it effective.
     *
     * <p><strong>Not the dispatch's read.</strong> The payout dispatch reads the same row
     * {@code FOR SHARE} in its own transaction ({@code PayoutDestinationStore#findEffectiveForShare},
     * ADR-0056 §9 and ADR-0057 §7), so a supersession cannot commit under it. This javadoc said
     * otherwise until `P6-DOC-001`.
     */
    public Optional<PayoutDestination> effectiveFor(Connection unitOfWork, MerchantId merchant) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        return destinations.findEffective(unitOfWork, merchant);
    }

    /** The merchant's open change — proposed, or approved and cooling off — if any. */
    public Optional<PayoutDestination> pendingFor(Connection unitOfWork, MerchantId merchant) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        return destinations.findOpen(unitOfWork, merchant);
    }

    /** The merchant's destination as it stands, or empty — unknown and foreign alike. */
    public Optional<PayoutDestination> find(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return destinations.find(unitOfWork, merchant, id);
    }

    /** Whether the merchant exists — the list's one-404 for unknown and malformed alike. */
    public boolean merchantExists(Connection unitOfWork, MerchantId merchant) {
        return merchants.findById(unitOfWork, merchant).isPresent();
    }

    private PayoutDestination end(
            Connection unitOfWork,
            MerchantId merchant,
            PayoutDestinationId id,
            String reason,
            PayoutDestinationStatus target,
            MerchantAuditAction action) {
        Objects.requireNonNull(reason, "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        PayoutDestination before = locked(unitOfWork, merchant, id);
        // The state this move produces, already produced, is this move done: one act, however
        // many operators asked (the merchant standing moves' idiom).
        if (before.status() == target) {
            return before;
        }
        PayoutDestination ended =
                target == PayoutDestinationStatus.REJECTED
                        ? before.reject(actor.id(), clock)
                        : before.withdraw(actor.id(), clock);
        requireLanded(unitOfWork, before, ended);
        record(
                unitOfWork,
                actor,
                action,
                ended,
                Optional.of(reason),
                AuditOutcome.SUCCEEDED,
                correlation,
                "destination=" + ended.id() + ", merchant=" + ended.merchantId() + ", from="
                        + before.status());
        return ended;
    }

    private PayoutDestination locked(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return destinations
                .findForUpdate(unitOfWork, merchant, id)
                .orElseThrow(UnknownPayoutDestinationException::new);
    }

    private void requireLanded(
            Connection unitOfWork, PayoutDestination before, PayoutDestination after) {
        if (!destinations.transition(unitOfWork, before, after)) {
            // The lock makes a lost count unreachable in this flow; refusing loudly beats
            // guessing if an unknown writer proves otherwise (INV-CON-01).
            throw new MerchantStorageException(
                    "a locked payout destination's conditional move found another writer's"
                            + " state");
        }
    }

    private void record(
            Connection unitOfWork,
            Actor actor,
            MerchantAuditAction action,
            PayoutDestination subject,
            Optional<String> reason,
            AuditOutcome outcome,
            Correlation correlation,
            String summary) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        action,
                        TARGET_TYPE,
                        subject.id().value().toString(),
                        // The operator's own words, verbatim (INV-AUD-03).
                        reason,
                        outcome,
                        correlation.correlationId(),
                        // Identifiers and states only - never the reference or its suffix
                        // (INV-AUD-02).
                        Optional.of(summary)));
    }

    /**
     * The fingerprint's subject: the actor and the request's meaning — a reused key from a
     * different operator, for another merchant, destination or reason is the distinct 409
     * ({@code INV-IDEM-03}), never a silent replay. Hashed before it is stored, so the reference
     * never rests in the idempotency record.
     */
    private static byte[] canonicalForm(ProposeCommand command, Actor actor) {
        return String.join(
                        "|",
                        PROPOSE_SCOPE,
                        actor.type().name(),
                        actor.id(),
                        command.merchantId().value().toString(),
                        command.destination().reference().expose(),
                        command.destination().displaySuffix(),
                        command.reason())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The flow's correlation — the merchant administration's idiom. */
    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a payout destination command must run inside a"
                                                + " correlation scope"));
    }
}
