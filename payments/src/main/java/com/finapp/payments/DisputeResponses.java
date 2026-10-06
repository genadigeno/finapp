package com.finapp.payments;

import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The dispute response command (`P7-TSK-014`, ADR-0061 §7): contest a chargeback with its
 * evidence, or concede it — "a dispatch like any other", in so many words.
 *
 * <h2>The refund's two-transaction choreography, for an answer that moves no money</h2>
 *
 * <p>Tx1 commits the claim ({@code IdempotentExecutor.begin}, held {@code IN_PROGRESS}) beside the
 * dispatch: judged under the attempt and dispute locks ({@link DisputeActs}), the response born
 * {@code DISPATCHED} with our reference minted ({@code INV-PAY-04}), the act audited, and — for a
 * representment — the evidence read back, decrypted and verified INSIDE Tx1, with its
 * transmission recorded, so a document failing its integrity check rolls the whole dispatch back
 * with nothing claimed or sent. The PSP call runs holding no database connection (ADR-0046).
 * Tx2 applies the answer on the LOCKED row through {@link DisputeResponseOutcomes} and completes
 * the claim with the judged {@code responseId|status}: a replay renders what this key was
 * answered, byte for byte.
 *
 * <h2>The crash between the two is the lease's case</h2>
 *
 * <p>A retry takes the expired claim over and {@code dispatchOrConverge} finds the committed
 * response by its dispatch key: still resolvable, a new send permit is committed and the wire
 * re-driven with the SAME reference and the SAME documents; already resolved, nothing is sent and
 * the claim answers the row's truth. The {@link DisputeResponseResolution} sweep covers the flight
 * nobody retries.
 *
 * <h2>The responder commands; the platform applies</h2>
 *
 * <p>The dispatch is the responder's act — the merchant over its key, or an operator for a payment
 * with no merchant, reasoned; the outcome is the platform's, through this class's enumerated
 * {@code enterSystem()} site (the refund's reasoning).
 */
public final class DisputeResponses {

    static final String IDEMPOTENCY_SCOPE_PREFIX = "dispute.respond:";

    private final TransactionRunner transactions;
    private final IdempotentExecutor executor;
    private final DisputeActs acts;
    private final DisputeStore<Connection> disputes;
    private final DisputeEvidenceStore<Connection> evidence;
    private final DisputeResponseStore<Connection> responses;
    private final DisputeResponder responder;
    private final DisputeResponseOutcomes outcomes;
    private final ProviderEvidenceStore<Connection> providerEvidence;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    /** Manual: the act rule is derived from the stores it reads, not injected. */
    public DisputeResponses(
            TransactionRunner transactions,
            IdempotentExecutor executor,
            DisputeStore<Connection> disputes,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            LedgerAccountStore<Connection> ledgerAccounts,
            DisputeEvidenceStore<Connection> evidence,
            DisputeResponseStore<Connection> responses,
            DisputeResponder responder,
            DisputeResponseOutcomes outcomes,
            ProviderEvidenceStore<Connection> providerEvidence,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.acts = new DisputeActs(disputes, attempts, intents, ledgerAccounts);
        this.disputes = Objects.requireNonNull(disputes, "disputes must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
        this.responder = Objects.requireNonNull(responder, "responder must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.providerEvidence =
                Objects.requireNonNull(providerEvidence, "providerEvidence must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * What the responder learns — the status honestly, {@code UNKNOWN} included.
     *
     * @param replayed the recorded judgement was rendered; no wire call happened
     * @param acting this call's own conditional made the committed status
     */
    public record ResponseResult(
            DisputeResponseId response,
            DisputeResponseStatus status,
            Optional<DisputeResponseFailure> failure,
            boolean replayed,
            boolean acting) {}

    /**
     * Tx1's yield, carried across the connectionless gap.
     *
     * @param send whether a send of our reference was permitted — false when a takeover found the
     *     crashed flight's response already resolved
     * @param firstSend whether this is the dispatch's own birth send — the only send whose refused
     *     connection can prove that nothing was ever transmitted
     * @param permit the send permit this flight committed, as stored
     */
    private record Dispatch(
            DisputeResponse response,
            ProviderReference disputeReference,
            List<DisputeResponder.EvidenceDocument> documents,
            boolean send,
            boolean firstSend,
            Instant permit) {}

    /**
     * Dispatches (or replays) the response and applies the PSP's answer.
     *
     * @throws UnknownDisputeException no dispute visible to the actor has the id — one 404
     * @throws DisputeResponseRefusedException the dispute takes no answer now, nothing written
     * @throws DisputeResponseKeyReusedException the key already names a different response
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key was used for a
     *     materially different request ({@code INV-IDEM-03})
     * @throws com.finapp.platform.idempotency.IdempotencyInProgressException another flight holds
     *     this key and its lease is running
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public ResponseResult respond(
            DisputeActor actor, DisputeId dispute, DisputeResponseKind kind, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Optional<String> reason = DisputeActs.reasonOf(actor);
        Actor requester = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        // KEYED PER RESPONDER (the backlog's "submission keyed per merchant"): the scope carries
        // the command and the owning principal, so two merchants' keys never collide (ADR-0004).
        String scope =
                IDEMPOTENCY_SCOPE_PREFIX
                        + requester.type().name().toLowerCase(Locale.ROOT)
                        + ":"
                        + requester.id();
        IdempotencyKey claimKey = new IdempotencyKey(scope, idempotencyKey);
        Dispatch[] holder = new Dispatch[1];
        IdempotentExecutor.BeginOutcome begun =
                transactions.inTransaction(
                        uow ->
                                executor.begin(
                                        uow,
                                        claimKey,
                                        RequestFingerprint.sha256(
                                                canonicalForm(scope, dispute, kind, reason)),
                                        claimed -> {
                                            Dispatch dispatched =
                                                    dispatchOrConverge(
                                                            claimed, actor, scope, idempotencyKey,
                                                            dispute, kind, reason, requester,
                                                            correlation);
                                            holder[0] = dispatched;
                                            return dispatched
                                                    .response()
                                                    .id()
                                                    .value()
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8);
                                        }));
        if (begun.replay().isPresent()) {
            return parsedReplay(begun.replay().get());
        }
        Dispatch dispatch = holder[0];

        // The PSP call - between the transactions, holding no database connection (ADR-0046) -
        // and only under a committed permit. An exception propagates: the dispatch stays
        // committed and visible, DISPATCHED, for the sweep - never a fabricated outcome.
        Optional<ProviderAnswer> answer =
                dispatch.send()
                        ? Optional.of(
                                responder.respond(
                                        new DisputeResponder.DisputeResponseRequest(
                                                dispatch.response().reference(),
                                                dispatch.disputeReference(),
                                                dispatch.response().kind(),
                                                dispatch.documents())))
                        : Optional.empty();

        // Tx2: the outcome, applied as the platform - a PSP's answer has no session (the
        // enumerated enterSystem() site, the refund's form).
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        DisputeResponseStore.Locked locked =
                                responses.lockForOutcome(uow, dispatch.response().id())
                                        .orElseThrow();
                        DisputeResponse current = locked.response();
                        DisputeResponseOutcomes.Applied applied =
                                answer.isPresent() && current.status().isResolvable()
                                        ? outcomes.apply(
                                                uow,
                                                current,
                                                judged(answer.get().verdict(), dispatch, locked),
                                                answer.get().providerReference(),
                                                correlation)
                                        : new DisputeResponseOutcomes.Applied(
                                                current.status(), false);
                        // Whatever the mapping said, what arrived is retained (INV-HIST-02) -
                        // AFTER the outcome's row lock.
                        answer.flatMap(ProviderAnswer::evidence)
                                .ifPresent(
                                        bytes ->
                                                providerEvidence.appendForDisputeResponse(
                                                        uow,
                                                        current.id(),
                                                        EvidenceKind.RESPONSE,
                                                        bytes,
                                                        Instant.now(clock)));
                        Optional<DisputeResponseFailure> failure =
                                responses.lockForOutcome(uow, current.id())
                                        .flatMap(row -> row.response().failure());
                        executor.complete(
                                uow,
                                claimKey,
                                true,
                                StoredResponse.of(
                                        renderedForm(current.id(), applied.status(), failure),
                                        "text/plain"));
                        return new ResponseResult(
                                current.id(), applied.status(), failure, false,
                                applied.acting());
                    });
        }
    }

    /**
     * The {@code DispatchCommand} contract made real: a lease takeover re-runs this against work
     * the crashed flight committed, so the first act is the convergence lookup by the dispatch
     * key. Found with the same facts, the crashed flight's dispatch stands — still resolvable, a
     * new permit and a re-send of the SAME reference and documents; resolved, nothing is sent.
     * Found with different facts, the key is refused: `V022` binds a key to one response.
     */
    private Dispatch dispatchOrConverge(
            Connection uow,
            DisputeActor actor,
            String scope,
            String dispatchKey,
            DisputeId dispute,
            DisputeResponseKind kind,
            Optional<String> reason,
            Actor requester,
            Correlation correlation) {
        Optional<DisputeResponse> existing = responses.findByDispatchKey(uow, scope, dispatchKey);
        if (existing.isEmpty()) {
            return dispatch(uow, actor, scope, dispatchKey, dispute, kind, reason, requester,
                    correlation);
        }
        DisputeResponse found = existing.get();
        if (!(found.dispute().equals(dispute)
                && found.kind() == kind
                && found.reason().equals(reason))) {
            throw new DisputeResponseKeyReusedException();
        }
        // The conditional renewal IS the permit: a response another resolver finished since the
        // lookup matches no row, and then nothing may be sent.
        Optional<Instant> permit =
                found.status().isResolvable()
                        ? responses.renewSendPermit(uow, found.id())
                        : Optional.empty();
        if (permit.isEmpty()) {
            return new Dispatch(found, null, List.of(), false, false, null);
        }
        Dispute contested =
                disputes.findById(uow, dispute)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a response's dispute exists: V022's foreign key"
                                                        + " holds it"))
                        .dispute();
        List<DisputeResponder.EvidenceDocument> documents = outcomes.documentsOf(uow, found);
        outcomes.transmitted(uow, found, correlation);
        return new Dispatch(
                found, contested.providerReference(), documents, true, false, permit.get());
    }

    /** The claimed dispatch: judged under the attempt-then-dispute locks, documents verified. */
    private Dispatch dispatch(
            Connection uow,
            DisputeActor actor,
            String scope,
            String dispatchKey,
            DisputeId dispute,
            DisputeResponseKind kind,
            Optional<String> reason,
            Actor requester,
            Correlation correlation) {
        DisputeStore.Found found = acts.lockForAct(uow, actor, dispute);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        DisputeActs.requireRespondable(
                found.dispute(), responses.findLive(uow, dispute).isPresent(), now);

        // THE EVIDENCE SET FREEZES HERE: every document the dispute holds, in upload order,
        // under the dispute lock no upload can pass while this answer is live.
        List<DisputeEvidenceId> carried =
                kind.carriesEvidence()
                        ? evidence.listFor(uow, dispute).stream().map(DisputeEvidence::id).toList()
                        : List.of();
        if (kind.carriesEvidence() && carried.isEmpty()) {
            throw new DisputeResponseRefusedException(
                    DisputeResponseRefusedException.Refusal.EVIDENCE_REQUIRED);
        }
        DisputeResponse response =
                DisputeResponse.dispatch(ids, now, dispute, kind, carried, requester, reason);
        // Read back and verified INSIDE Tx1: a document failing its integrity check throws here,
        // and the claim, the row and the records all roll back - nothing is ever sent unverified.
        List<DisputeResponder.EvidenceDocument> documents = outcomes.documentsOf(uow, response);
        responses.insert(uow, response, scope, dispatchKey);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        requester,
                        now,
                        PaymentsAuditAction.DISPUTE_RESPONSE_DISPATCHED,
                        DisputeNotifications.TARGET_TYPE,
                        dispute.value().toString(),
                        // The operator's own words when an operator acts (INV-AUD-03).
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "dispute=" + dispute
                                        + ", response=" + response.id()
                                        + ", kind=" + kind
                                        + ", documents=" + carried.size()
                                        + ", reference=" + response.reference().value())));
        outcomes.transmitted(uow, response, correlation);
        // The birth permit, as the database stored it: the value a refused connection on this
        // first send is later judged against.
        Instant permit =
                responses.lockForOutcome(uow, response.id())
                        .map(DisputeResponseStore.Locked::sendPermit)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a response inserted in this transaction is"
                                                        + " readable in it"));
        return new Dispatch(
                response, found.dispute().providerReference(), documents, true, true, permit);
    }

    /**
     * What a verdict proves about THIS response, judged against the locked row (ADR-0057 §3, the
     * refund's rule): a refused connection proves only that THIS send transmitted nothing — a
     * failure only when this was the dispatch's own first send and no later send has been
     * permitted since. After a takeover's re-send, an earlier send may have reached the PSP, so it
     * counts as what it is: we do not know.
     */
    private static ProviderAnswer.Verdict judged(
            ProviderAnswer.Verdict verdict, Dispatch dispatch, DisputeResponseStore.Locked locked) {
        if (verdict == ProviderAnswer.Verdict.NOTHING_SENT
                && !(dispatch.firstSend() && locked.sendPermit().equals(dispatch.permit()))) {
            return ProviderAnswer.Verdict.INDETERMINATE;
        }
        return verdict;
    }

    /**
     * The claim's stored judgement: {@code <responseId>|<status>|<failure or empty>}, parsed back
     * on replay.
     */
    private static byte[] renderedForm(
            DisputeResponseId response,
            DisputeResponseStatus status,
            Optional<DisputeResponseFailure> failure) {
        return (response.value() + "|" + status.name() + "|" + failure.map(Enum::name).orElse(""))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ResponseResult parsedReplay(StoredResponse stored) {
        String body =
                new String(
                        stored.bodyBytes()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a completed response claim stores its"
                                                                + " judgement; an empty body is a"
                                                                + " wiring defect")),
                        StandardCharsets.UTF_8);
        String[] parts = body.split("\\|", -1);
        return new ResponseResult(
                DisputeResponseId.of(UUID.fromString(parts[0])),
                DisputeResponseStatus.valueOf(parts[1]),
                parts[2].isEmpty()
                        ? Optional.empty()
                        : Optional.of(DisputeResponseFailure.valueOf(parts[2])),
                true,
                false);
    }

    /** The responder, the dispute and the answer's meaning ({@code INV-IDEM-03}). */
    private static byte[] canonicalForm(
            String scope, DisputeId dispute, DisputeResponseKind kind, Optional<String> reason) {
        return (scope + "|" + dispute.value() + "|" + kind.name() + "|" + reason.orElse(""))
                .getBytes(StandardCharsets.UTF_8);
    }
}
