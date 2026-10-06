package com.finapp.payments;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseTime;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The dispute-response resolution sweep (`P7-TSK-014`) — {@link ReturnResolution}'s legs, asked of
 * the card PSP's dispute port: every {@code DISPATCHED} response whose flight never came back and
 * every {@code UNKNOWN} one is resolved by query on OUR reference, never by a clock.
 *
 * <p>Per row: inquire (an idempotent read, permit-free). {@code UNRECOGNISED} means the PSP never
 * saw the response — so the row's send permit is stamped forward CONDITIONALLY and the SAME
 * request re-sent: the same reference (the PSP deduplicates on it, {@code INV-PAY-04}), the same
 * documents, read back exactly as the response froze them, the transmission recorded first as the
 * platform's. A re-send's refused connection proves nothing about the sends before it and stays
 * {@code INDETERMINATE} (ADR-0057 §3). The judgement lands through the one shared
 * {@link DisputeResponseOutcomes#apply}, on the LOCKED row, so whoever wins acts once. Registered
 * leaderless in {@code DISTRIBUTED_EXECUTION.md} §3.
 */
@Slf4j
public final class DisputeResponseResolution {

    /** The sweep's pacing and batch, refused mis-configured at construction. */
    public record Config(Duration dispatchedAge, Duration unknownAge, int batchSize) {

        public Config {
            Objects.requireNonNull(dispatchedAge, "dispatchedAge must not be null");
            Objects.requireNonNull(unknownAge, "unknownAge must not be null");
            if (dispatchedAge.isZero() || dispatchedAge.isNegative()) {
                throw new IllegalArgumentException(
                        "dispatchedAge must be positive: a zero bound would re-contact the PSP"
                                + " about a send still in flight");
            }
            if (unknownAge.isZero() || unknownAge.isNegative()) {
                throw new IllegalArgumentException("unknownAge must be positive");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
        }
    }

    private final DisputeResponseStore<Connection> responses;
    private final DisputeStore<Connection> disputes;
    private final DisputeResponseOutcomes outcomes;
    private final DisputeResponder responder;
    private final ProviderEvidenceStore<Connection> providerEvidence;
    private final Config config;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionRunner transactions;

    public DisputeResponseResolution(
            DisputeResponseStore<Connection> responses,
            DisputeStore<Connection> disputes,
            DisputeResponseOutcomes outcomes,
            DisputeResponder responder,
            ProviderEvidenceStore<Connection> providerEvidence,
            Config config,
            IdGenerator ids,
            Clock clock,
            TransactionRunner transactions) {
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
        this.disputes = Objects.requireNonNull(disputes, "disputes must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.responder = Objects.requireNonNull(responder, "responder must not be null");
        this.providerEvidence =
                Objects.requireNonNull(providerEvidence, "providerEvidence must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /** One tick's tally — telemetry, never the count of record ({@code PaymentSweeper}'s rule). */
    public record SweepResult(int candidates, int applied, int skipped) {}

    /** A re-send's facts, read under the renewed permit. */
    private record Resend(
            ProviderReference dispute, List<DisputeResponder.EvidenceDocument> documents) {}

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<DisputeResponse> candidates =
                transactions.inTransaction(
                        uow ->
                                responses.findSweepable(
                                        uow,
                                        // The permit's own clock, the database's (X-TSK-013).
                                        DatabaseTime.now(uow).minus(config.dispatchedAge()),
                                        now.minus(config.unknownAge()),
                                        config.batchSize()));
        int applied = 0;
        int skipped = 0;
        for (DisputeResponse candidate : candidates) {
            // Per-row containment: one candidate's failure never starves the rest.
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)))) {
                if (resolveOne(candidate)) {
                    applied++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException oneRowFailed) {
                // Identifier and failure class only - never PSP bytes (INV-AUD-02).
                log.warn(
                        "dispute response sweep could not resolve response {}: {}",
                        candidate.id(),
                        oneRowFailed.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), applied, skipped);
    }

    /** One row's wire call(s) and outcome transaction; true when its conditional acted. */
    private boolean resolveOne(DisputeResponse candidate) {
        // The inquiry - HOLDING NO DATABASE CONNECTION (the P1-TSK-026 discipline).
        QueryAnswer answer = responder.query(candidate.reference());
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        Optional<ProviderAnswer> resent = Optional.empty();
        if (answer.verdict() == QueryAnswer.Verdict.UNRECOGNISED) {
            // The PSP says it never saw our reference: re-send under a committed permit, the
            // same request - the PSP records our reference once, however many resolvers send it.
            Optional<Resend> resend =
                    transactions.inTransaction(
                            uow ->
                                    responses.renewSendPermit(uow, candidate.id())
                                            .map(
                                                    permit -> {
                                                        Dispute contested =
                                                                disputes.findById(
                                                                                uow,
                                                                                candidate
                                                                                        .dispute())
                                                                        .orElseThrow()
                                                                        .dispute();
                                                        List<DisputeResponder.EvidenceDocument>
                                                                documents =
                                                                        outcomes.documentsOf(
                                                                                uow, candidate);
                                                        outcomes.transmitted(
                                                                uow, candidate, correlation);
                                                        return new Resend(
                                                                contested.providerReference(),
                                                                documents);
                                                    }));
            if (resend.isEmpty()) {
                // Resolved since the candidate list: nothing may be sent.
                return false;
            }
            resent =
                    Optional.of(
                            responder.respond(
                                    new DisputeResponder.DisputeResponseRequest(
                                            candidate.reference(),
                                            resend.get().dispute(),
                                            candidate.kind(),
                                            resend.get().documents())));
        }

        Optional<ProviderAnswer> sent = resent;
        return transactions.inTransaction(
                uow -> {
                    DisputeResponseStore.Locked locked =
                            responses.lockForOutcome(uow, candidate.id()).orElse(null);
                    if (locked == null || !locked.response().status().isResolvable()) {
                        return false;
                    }
                    DisputeResponse current = locked.response();
                    ProviderAnswer.Verdict verdict;
                    Optional<ProviderReference> reference;
                    if (sent.isPresent()) {
                        // A re-send is never the first send: its refused connection proves
                        // nothing about the sends before it (ADR-0057 section 3).
                        verdict =
                                sent.get().verdict() == ProviderAnswer.Verdict.NOTHING_SENT
                                        ? ProviderAnswer.Verdict.INDETERMINATE
                                        : sent.get().verdict();
                        reference = sent.get().providerReference();
                    } else {
                        verdict =
                                switch (answer.verdict()) {
                                    case APPROVED -> ProviderAnswer.Verdict.APPROVED;
                                    case DECLINED -> ProviderAnswer.Verdict.DECLINED;
                                    default -> ProviderAnswer.Verdict.INDETERMINATE;
                                };
                        reference = answer.providerReference();
                    }
                    boolean acted =
                            !(verdict == ProviderAnswer.Verdict.INDETERMINATE
                                            && current.status() == DisputeResponseStatus.UNKNOWN)
                                    && outcomes.apply(uow, current, verdict, reference, correlation)
                                            .acting();
                    // Whatever the mapping said, what arrived is retained (INV-HIST-02) - AFTER
                    // the outcome's row lock.
                    Instant at = Instant.now(clock);
                    answer.evidence()
                            .ifPresent(
                                    bytes ->
                                            providerEvidence.appendForDisputeResponse(
                                                    uow,
                                                    current.id(),
                                                    EvidenceKind.QUERY_RESULT,
                                                    bytes,
                                                    at));
                    sent.flatMap(ProviderAnswer::evidence)
                            .ifPresent(
                                    bytes ->
                                            providerEvidence.appendForDisputeResponse(
                                                    uow,
                                                    current.id(),
                                                    EvidenceKind.RESPONSE,
                                                    bytes,
                                                    at));
                    return acted;
                });
    }
}
