package com.finapp.fx;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The cover's ONE outcome applier - T-d (`P9-TSK-012`; ADR-0077 sections 4-6, PHASE_9_PLAN.md
 * sections 7 and 12.5): the send's answer, the sweep's inquiry and the callback's hinted inquiry
 * all resolve here, under the lock order (the quote and its trade {@code FOR SHARE}, then the cover
 * {@code FOR UPDATE}), each re-judged on the locked row; the losers record nothing.
 *
 * <p><strong>No conclusion without knowledge</strong> ({@code INV-FX-08}, {@code INV-LIFE-03}): only
 * {@code Executed} and {@code Rejected} - definitive answers in so many words - move a cover to a
 * conclusion. A send that went unanswered ({@code Indeterminate}) moves {@code DISPATCHED -> UNKNOWN};
 * {@code Unrecognised} and {@code NothingSent} move nothing, and the sweep re-sends the same
 * {@code T}. An answer for a superseded attempt, or one in other currencies than the plan's, is
 * retained as evidence and counted as an anomaly, never applied.
 *
 * <p><strong>One provider execution, one money fact</strong>: the acting branch inserts
 * {@code fx.cover_execution} - its PK the arbiter, holding with this class's checks removed - posts
 * {@code fx-cover:<coverId>} closing exactly the plan's position legs ({@link CoverLines}), attaches
 * the entry, moves the cover {@code EXECUTED}, opens both leg expectations on the provider's own
 * clearing ({@link FxSettlementExpectations}), and writes its event and audit record - one commit.
 * No customer line is touched by any outcome ({@code INV-FX-09}).
 */
@Slf4j
public final class FxCoverOutcomes {

    public static final String POSTING_PREFIX = "fx-cover:";
    public static final String AGGREGATE_TYPE = "fx_cover";
    public static final String EXECUTED_EVENT = "fx.FxCoverExecuted";
    public static final String REJECTED_EVENT = "fx.FxCoverRejected";
    public static final String REQUOTED_EVENT = "fx.FxCoverRequoted";

    /** Which channel brought the answer - only a send's lost answer makes a cover UNKNOWN. */
    public enum Channel {
        /** The answer to our {@code execute(T)}. */
        ANSWER,
        /** The sweep's {@code inquire(T)}. */
        INQUIRY,
        /** The {@code inquire(T)} a verified callback triggered - the callback itself moves nothing. */
        HINTED_INQUIRY
    }

    /** What an application did: the cover as it now stands, whether this caller acted, and the meters' verdicts. */
    public record Applied(
            CoverStore.CoverRow cover, CoverStatus status, boolean acting, List<CoverObserver.Outcome> outcomes,
            Optional<Duration> sinceBirth) {

        public Applied {
            Objects.requireNonNull(cover, "cover must not be null");
            Objects.requireNonNull(status, "status must not be null");
            outcomes = List.copyOf(outcomes);
            Objects.requireNonNull(sinceBirth, "sinceBirth must not be null");
        }
    }

    private final CoverStore covers;
    private final QuoteStore quotes;
    private final FxProviders providers;
    private final ChartOfAccounts<Connection> chart;
    private final PostingService postings;
    private final FxSettlementExpectations expectations;
    private final FxProviderEvidenceStore<Connection> evidence;
    private final OutboxWriter<Connection> outbox;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public FxCoverOutcomes(
            CoverStore covers,
            QuoteStore quotes,
            FxProviders providers,
            ChartOfAccounts<Connection> chart,
            PostingService postings,
            FxSettlementExpectations expectations,
            FxProviderEvidenceStore<Connection> evidence,
            OutboxWriter<Connection> outbox,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock) {
        this.covers = Objects.requireNonNull(covers, "covers must not be null");
        this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
        this.postings = Objects.requireNonNull(postings, "postings must not be null");
        this.expectations = Objects.requireNonNull(expectations, "expectations must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Applies {@code answer} for attempt {@code attempt} of the cover, in the caller's transaction.
     * The caller holds no lock of this cover's; this method takes them in order.
     */
    public Applied apply(
            Connection unitOfWork, UUID coverId, int attempt, FxProvider.ExecutionAnswer answer, Channel channel, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(coverId, "coverId must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CoverStore.CoverRow seen = covers.find(unitOfWork, coverId)
                .orElseThrow(() -> new IllegalArgumentException("no such cover"));
        // The lock order: the quote, its trade, then the cover - re-judged on the locked row.
        covers.lockWanted(unitOfWork, seen.quoteId());
        CoverStore.CoverRow locked = covers.lock(unitOfWork, coverId)
                .orElseThrow(() -> new IllegalStateException("a read cover vanished"));
        CoverStore.AttemptRow answered = covers.attempt(unitOfWork, coverId, attempt)
                .orElseThrow(() -> new IllegalArgumentException("no such attempt"));
        retain(unitOfWork, locked, answered.clientReference(), answer, channel);

        if (locked.attempts() != attempt) {
            // The answer is for a superseded attempt: evidence only. An execution of it would be a
            // second trade - the provider broke its contract, and settlement will show it loudly.
            return answer instanceof FxProvider.ExecutionAnswer.Executed
                    ? anomaly(locked, "an execution answered for superseded attempt " + attempt)
                    : still(locked);
        }
        return switch (answer) {
            case FxProvider.ExecutionAnswer.Executed executed -> executed(unitOfWork, locked, answered, executed, actor);
            case FxProvider.ExecutionAnswer.Rejected rejected -> rejected(unitOfWork, locked, rejected);
            case FxProvider.ExecutionAnswer.Indeterminate indeterminate ->
                    channel == Channel.ANSWER && locked.status() == CoverStatus.DISPATCHED
                            ? unknown(unitOfWork, locked)
                            : still(locked);
            case FxProvider.ExecutionAnswer.Unrecognised unrecognised -> still(locked);
            case FxProvider.ExecutionAnswer.NothingSent nothing -> still(locked);
        };
    }

    // ------------------------------------------------------------------ the branches

    private Applied executed(
            Connection unitOfWork, CoverStore.CoverRow locked, CoverStore.AttemptRow attempt,
            FxProvider.ExecutionAnswer.Executed executed, Actor actor) {
        if (locked.status() == CoverStatus.EXECUTED) {
            return still(locked); // a duplicate of the applied answer: converged.
        }
        if (locked.status() != CoverStatus.DISPATCHED && locked.status() != CoverStatus.UNKNOWN) {
            return anomaly(locked, "an execution answered for a " + locked.status() + " cover");
        }
        QuoteStore.PlanRow planRow = quotes.plan(unitOfWork, locked.quoteId())
                .orElseThrow(() -> new IllegalStateException("a cover's quote has its plan"));
        CoverLines.Plan plan = CoverLines.Plan.of(planRow);
        CoverLines.Execution execution = new CoverLines.Execution(executed.sold(), executed.bought());
        if (!CoverLines.coherent(plan, execution)) {
            // Not an answer about this cover's currencies: unusable, so not knowledge.
            if (locked.status() == CoverStatus.DISPATCHED) {
                covers.transition(unitOfWork, locked.id(), locked.attempts(), CoverStatus.DISPATCHED, CoverStatus.UNKNOWN);
            }
            return anomaly(reread(unitOfWork, locked), "an execution in other currencies than the plan's");
        }
        AccountPurpose clearing = providers.find(locked.providerCode())
                .map(composed -> composed.declaration().clearingPurpose())
                .orElseThrow(() -> new IllegalStateException("a cover's provider is a composed declaration"));

        // 1. The fact - the arbiter.
        CoverStore.Recorded recorded = covers.insertExecution(unitOfWork, new CoverStore.ExecutionDraft(
                locked.id(), locked.attempts(), attempt.clientReference(), locked.providerCode(),
                executed.providerTradeReference(), locked.fixedSide(), executed.sold(), executed.bought(),
                executed.executedRate(), executed.valueDate(), plan, locked.correlationId()));
        CoverLines.Realised realised = CoverLines.realised(plan, execution);

        // 2. The entry: the plan's legs closed onto the provider's own clearing, the difference realised.
        CoverLines.Accounts accounts = CoverLines.accounts(chart, unitOfWork, clearing, locked.providerCode(),
                plan.sold().currency(), plan.bought().currency());
        String postingKey = POSTING_PREFIX + locked.id();
        PostingResult posted = postings.post(unitOfWork, new PostingCommand(
                postingKey, recorded.recordedOn(), executed.valueDate(), postingKey,
                CoverLines.compose(plan, execution, accounts)));
        covers.attachExecutionEntry(unitOfWork, locked.id(), posted.entryId().value());
        if (!covers.transition(unitOfWork, locked.id(), locked.attempts(), locked.status(), CoverStatus.EXECUTED)) {
            throw new IllegalStateException("the locked cover did not execute beside its execution fact");
        }

        // 3. The legs reconciliation will compare the provider's evidence with: what it confirmed.
        CorrelationId correlation = CorrelationId.of(locked.correlationId());
        Correlation legCorrelation = new Correlation(correlation, CausationId.of(locked.causedByEventId().toString()));
        List<FxSettlementExpectations.Key> keys = List.of(
                new FxSettlementExpectations.Key(FxSettlementExpectations.ReferenceKind.COVER_REF, attempt.clientReference()),
                new FxSettlementExpectations.Key(FxSettlementExpectations.ReferenceKind.FX_TRADE_REF, executed.providerTradeReference()));
        expectations.open(unitOfWork, new FxSettlementExpectations.Opening(
                FxSettlementExpectations.Kind.FX_SELL_LEG, locked.id().toString(), postingKey, clearing,
                locked.providerCode(), accounts.soldClearing(), posted.entryId(), keys, legCorrelation));
        expectations.open(unitOfWork, new FxSettlementExpectations.Opening(
                FxSettlementExpectations.Kind.FX_BUY_LEG, locked.id().toString(), postingKey, clearing,
                locked.providerCode(), accounts.boughtClearing(), posted.entryId(), keys, legCorrelation));

        // 4. The facts: the event, the audit record (acting only).
        Instant now = Instant.now(clock);
        outbox.write(unitOfWork,
                envelope(EXECUTED_EVENT, locked, now),
                EventPayload.of()
                        .with("kind", locked.kind().name())
                        .with("attempt", Integer.toString(locked.attempts()))
                        .with("providerCode", locked.providerCode())
                        // The platform's own words - what it delivered and received - never the wire's.
                        .with("deliveredMinor", Long.toString(executed.sold().minorUnits()))
                        .with("deliveredCurrencyCode", executed.sold().currency().code())
                        .with("deliveredScale", Integer.toString(executed.sold().scale()))
                        .with("receivedMinor", Long.toString(executed.bought().minorUnits()))
                        .with("receivedCurrencyCode", executed.bought().currency().code())
                        .with("receivedScale", Integer.toString(executed.bought().scale()))
                        .with("deliveredResult", direction(realised.soldMinor()))
                        .with("receivedResult", direction(realised.boughtMinor()))
                        .with("executedOffPlan", Boolean.toString(realised.offPlan()))
                        .with("postingReference", "fx-cover-" + locked.id())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids), actor, now, FxAuditAction.COVER_EXECUTED, AGGREGATE_TYPE, locked.id().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("quote=" + locked.quoteId().value() + ", attempt=" + locked.attempts()
                        + ", provider=" + locked.providerCode() + ", offPlan=" + realised.offPlan())));
        if (realised.offPlan()) {
            log.error("ALERT: cover {} executed off its plan's fixed leg at provider {} - booked, realised result posted",
                    locked.id(), locked.providerCode());
        }
        List<CoverObserver.Outcome> outcomes = new ArrayList<>(List.of(CoverObserver.Outcome.EXECUTED));
        if (realised.offPlan()) {
            outcomes.add(CoverObserver.Outcome.OFF_PLAN);
        }
        return new Applied(reread(unitOfWork, locked), CoverStatus.EXECUTED, true, outcomes,
                Optional.of(Duration.between(locked.createdAt(), recorded.recordedAt())));
    }

    private Applied rejected(Connection unitOfWork, CoverStore.CoverRow locked, FxProvider.ExecutionAnswer.Rejected rejected) {
        if (locked.status() == CoverStatus.REJECTED || locked.status() == CoverStatus.VOIDED) {
            return still(locked);
        }
        if (locked.status() == CoverStatus.EXECUTED) {
            return anomaly(locked, "a rejection answered for an executed cover");
        }
        if (!covers.transition(unitOfWork, locked.id(), locked.attempts(), locked.status(), CoverStatus.REJECTED)) {
            throw new IllegalStateException("the locked cover did not reject");
        }
        outbox.write(unitOfWork,
                envelope(REJECTED_EVENT, locked, Instant.now(clock)),
                EventPayload.of()
                        .with("kind", locked.kind().name())
                        .with("attempt", Integer.toString(locked.attempts()))
                        .with("providerCode", locked.providerCode())
                        .with("reason", rejected.reason().name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Applied(reread(unitOfWork, locked), CoverStatus.REJECTED, true,
                List.of(CoverObserver.Outcome.REJECTED), Optional.empty());
    }

    private Applied unknown(Connection unitOfWork, CoverStore.CoverRow locked) {
        if (!covers.transition(unitOfWork, locked.id(), locked.attempts(), CoverStatus.DISPATCHED, CoverStatus.UNKNOWN)) {
            throw new IllegalStateException("the locked DISPATCHED cover did not become UNKNOWN");
        }
        return new Applied(reread(unitOfWork, locked), CoverStatus.UNKNOWN, true,
                List.of(CoverObserver.Outcome.UNKNOWN), Optional.empty());
    }

    private static Applied still(CoverStore.CoverRow locked) {
        return new Applied(locked, locked.status(), false, List.of(), Optional.empty());
    }

    private static Applied anomaly(CoverStore.CoverRow locked, String what) {
        log.error("ALERT: {} - cover {}, provider {}: retained as evidence, nothing applied",
                what, locked.id(), locked.providerCode());
        return new Applied(locked, locked.status(), false, List.of(CoverObserver.Outcome.ANOMALY), Optional.empty());
    }

    // -----------------------------------------------------------------

    /** Evidence first ({@code INV-HIST-02}): every answer's received bytes, under its reference. */
    private void retain(
            Connection unitOfWork, CoverStore.CoverRow cover, String clientReference,
            FxProvider.ExecutionAnswer answer, Channel channel) {
        Optional<FxProvider.Evidence> bytes = switch (answer) {
            case FxProvider.ExecutionAnswer.Executed executed -> Optional.of(executed.evidence());
            case FxProvider.ExecutionAnswer.Rejected rejected -> Optional.of(rejected.evidence());
            case FxProvider.ExecutionAnswer.Unrecognised unrecognised -> Optional.of(unrecognised.evidence());
            case FxProvider.ExecutionAnswer.Indeterminate indeterminate -> indeterminate.evidence();
            case FxProvider.ExecutionAnswer.NothingSent nothing -> Optional.empty();
        };
        bytes.filter(evidenceBytes -> evidenceBytes.bytes().length > 0
                        && evidenceBytes.bytes().length <= FxProviderEvidenceStore.MAX_PAYLOAD_BYTES)
                .ifPresent(evidenceBytes -> evidence.append(unitOfWork, cover.providerCode(), clientReference,
                        channel == Channel.ANSWER
                                ? FxProviderEvidenceStore.Kind.RESPONSE
                                : FxProviderEvidenceStore.Kind.INQUIRY_RESULT,
                        evidenceBytes.bytes(), Instant.now(clock)));
    }

    EventEnvelope envelope(String type, CoverStore.CoverRow cover, Instant now) {
        return new EventEnvelope(EventId.next(ids), type, QuoteIssuance.EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                FxCoverId.of(cover.id()), AGGREGATE_TYPE, now, QuoteIssuance.PRODUCER,
                CorrelationId.of(cover.correlationId()), CausationId.of(cover.causedByEventId().toString()));
    }

    private CoverStore.CoverRow reread(Connection unitOfWork, CoverStore.CoverRow locked) {
        return covers.find(unitOfWork, locked.id()).orElse(locked);
    }

    private static String direction(long minor) {
        return minor > 0 ? "GAIN" : minor < 0 ? "LOSS" : "NONE";
    }
}
