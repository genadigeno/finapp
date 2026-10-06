package com.finapp.fx;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
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
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The cover's wire legs (`P9-TSK-012`; ADR-0077 sections 3-4 and 9, ADR-0046): send, re-send,
 * inquire, requote and the callback's hinted inquiry - each a permit transaction, the provider
 * call holding no connection, then {@link FxCoverOutcomes} in its own transaction.
 *
 * <p><strong>Every send is the same {@code T}</strong> ({@code INV-FX-08}, {@code INV-PAY-04}): a
 * {@code DISPATCHED} cover is (re-)sent {@code execute(T)}; an {@code UNKNOWN} one is asked
 * {@code inquire(T)} first and re-sent the same {@code T} only if the provider has never seen it -
 * the provider's contract being to dedupe on our reference before judging the quote, a re-send
 * after the lock lapsed answers the original execution if there was one. A permit renewed by the
 * database before every send paces the instances ({@code fx V006}'s forward-only stamp).
 *
 * <p><strong>A new reference only after a definitive rejection</strong>: a {@code REJECTED} cover
 * its quote still wants obtains a fresh firm quote for its exposure's fixed leg from the SAME
 * provider (a cover never moves provider), judged by the plausibility band against a fresh
 * reference under the quote's pinned policy (ADR-0075); only then are attempt n+1 and
 * {@code T(n+1)} stored - before the permit that licenses sending them commits - and sent. A
 * refused requote leaves the cover {@code REJECTED} with its backoff advanced, and alerts. A
 * {@code REJECTED} cover its quote no longer wants is voided (ADR-0077 section 7).
 *
 * <p><strong>The unwind's first price</strong> (`P9-TSK-021`): an unwind is born in its writer's transaction, which
 * cannot call the provider, so it is born without an attempt; its first dispatch obtains a fresh firm quote for its
 * fixed leg from the SAME provider, judges it by the band, stores attempt 1 and {@code T1} - before the send - and
 * sends. An implausible or unanswered price leaves it waiting for the next sweep, alerted. An unwind is always
 * wanted: it is requoted after a rejection, never voided.
 */
@Slf4j
@SuppressWarnings("try") // Each Scope is used for its close side effect (the established idiom).
public final class FxCoverDispatch {

    /** The sweep's pacing: re-send after, the requote backoff's base, and the claim's page. */
    public record Config(Duration resendAfter, Duration requoteBase, int batch) {
        public Config {
            Objects.requireNonNull(resendAfter, "resendAfter must not be null");
            Objects.requireNonNull(requoteBase, "requoteBase must not be null");
            if (resendAfter.isNegative() || resendAfter.isZero() || requoteBase.isNegative() || requoteBase.isZero()) {
                throw new IllegalArgumentException("the cover sweep's intervals are positive");
            }
            if (batch < 1 || batch > 1000) {
                throw new IllegalArgumentException("batch must be 1..1000: " + batch);
            }
        }
    }

    private final CoverStore covers;
    private final FxCoverOutcomes outcomes;
    private final FxProviders providers;
    private final QuoteStore quotes;
    private final PricingPolicyStore policies;
    private final RateSnapshotStore<Connection> references;
    private final FxProviderEvidenceStore<Connection> evidence;
    private final OutboxWriter<Connection> outbox;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final TransactionRunner transactions;
    private final CoverObserver observer;
    private final Clock clock;
    private final Config config;

    public FxCoverDispatch(
            CoverStore covers,
            FxCoverOutcomes outcomes,
            FxProviders providers,
            QuoteStore quotes,
            PricingPolicyStore policies,
            RateSnapshotStore<Connection> references,
            FxProviderEvidenceStore<Connection> evidence,
            OutboxWriter<Connection> outbox,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            TransactionRunner transactions,
            CoverObserver observer,
            Clock clock,
            Config config) {
        this.covers = Objects.requireNonNull(covers, "covers must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.references = Objects.requireNonNull(references, "references must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * One sweep: every due cover this call can claim - its permit renewed by the database, rows
     * another instance holds skipped - advanced along its leg. A failing cover is logged by class
     * and left to the next sweep; the others proceed.
     *
     * @return how many covers this call claimed
     */
    public int sweepOnce(Actor actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        List<CoverStore.CoverRow> claimed = transactions.inTransaction(unitOfWork ->
                covers.claimDue(unitOfWork, config.resendAfter(), config.requoteBase(), config.batch()));
        for (CoverStore.CoverRow cover : claimed) {
            try {
                advanceInScope(cover, actor);
            } catch (RuntimeException failure) {
                // The class name only: a JDBC message can carry values. The next sweep retries.
                log.warn("FX cover {} could not advance: {}", cover.id(), failure.getClass().getSimpleName());
            }
        }
        return claimed.size();
    }

    /**
     * Advances one open cover now, under a renewed permit - the post-commit nudge's send of a
     * freshly born cover, or an operator's or suite's targeted advance of any non-terminal one.
     */
    public void dispatchNow(UUID coverId, Actor actor) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        transactions.inTransaction(unitOfWork -> covers.claim(unitOfWork, coverId))
                .ifPresent(cover -> advanceInScope(cover, actor));
    }

    /**
     * A verified callback's hint (ADR-0077 section 9): {@code inquire(T)} at once, and apply the
     * INQUIRY's answer - never the callback's. An unknown reference does nothing.
     */
    public void hint(String clientReference, Actor actor) {
        Objects.requireNonNull(clientReference, "clientReference must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Optional<CoverStore.AttemptRow> attempt =
                transactions.inTransaction(unitOfWork -> covers.attemptByReference(unitOfWork, clientReference));
        if (attempt.isEmpty()) {
            return;
        }
        Optional<CoverStore.CoverRow> cover =
                transactions.inTransaction(unitOfWork -> covers.find(unitOfWork, attempt.get().coverId()));
        Optional<FxProvider> adapter = cover.flatMap(found -> adapter(found.providerCode()));
        if (adapter.isEmpty()) {
            return;
        }
        FxProvider.ExecutionAnswer answer = adapter.get().inquire(clientReference);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(correlationOf(cover.get()));
                SecurityContext.Scope acting = SecurityContext.enter(actor)) {
            apply(cover.get(), attempt.get().attempt(), answer, FxCoverOutcomes.Channel.HINTED_INQUIRY, actor);
        }
    }

    // ------------------------------------------------------------------ the legs

    /**
     * Every leg runs in the cover's own flow - its conversion's correlation, caused by the acceptance
     * (the posting, the inbox and the idempotency layers all require a scope; INV-LED-05) - and as
     * the actor the caller names: the platform, for every caller today.
     */
    private void advanceInScope(CoverStore.CoverRow cover, Actor actor) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(correlationOf(cover));
                SecurityContext.Scope acting = SecurityContext.enter(actor)) {
            advance(cover, actor);
        }
    }

    private static Correlation correlationOf(CoverStore.CoverRow cover) {
        return new Correlation(CorrelationId.of(cover.correlationId()), CausationId.of(cover.causedByEventId().toString()));
    }

    private void advance(CoverStore.CoverRow cover, Actor actor) {
        switch (cover.status()) {
            case DISPATCHED -> send(cover, actor);
            case UNKNOWN -> inquireThenResend(cover, actor);
            case REJECTED -> requote(cover, actor);
            case EXECUTED, VOIDED -> { }
        }
    }

    private void send(CoverStore.CoverRow cover, Actor actor) {
        Optional<FxProvider> adapter = adapter(cover.providerCode());
        if (adapter.isEmpty()) {
            return;
        }
        Optional<CoverStore.AttemptRow> current = transactions.inTransaction(unitOfWork ->
                covers.attempt(unitOfWork, cover.id(), cover.attempts()));
        if (current.isEmpty() && cover.kind() == CoverKind.UNWIND && cover.attempts() == 1) {
            current = priceUnwind(cover, adapter.get());
            if (current.isEmpty()) {
                return;
            }
        }
        CoverStore.AttemptRow attempt = current
                .orElseThrow(() -> new IllegalStateException("a cover's current attempt has its row"));
        FxProvider.ExecutionAnswer answer = adapter.get().execute(new FxProvider.ExecutionRequest(
                attempt.clientReference(), attempt.providerQuoteReference(), cover.source(), cover.destination(),
                cover.fixedSide(), cover.fixedAmount()));
        apply(cover, attempt.attempt(), answer, FxCoverOutcomes.Channel.ANSWER, actor);
    }

    private void inquireThenResend(CoverStore.CoverRow cover, Actor actor) {
        Optional<FxProvider> adapter = adapter(cover.providerCode());
        if (adapter.isEmpty()) {
            return;
        }
        CoverStore.AttemptRow attempt = currentAttempt(cover);
        FxProvider.ExecutionAnswer answer = adapter.get().inquire(attempt.clientReference());
        FxCoverOutcomes.Applied applied = apply(cover, attempt.attempt(), answer, FxCoverOutcomes.Channel.INQUIRY, actor);
        if (answer instanceof FxProvider.ExecutionAnswer.Unrecognised && applied.status() == CoverStatus.UNKNOWN) {
            // The provider has never seen T: send the SAME T - never a new one.
            send(applied.cover(), actor);
        }
    }

    /**
     * The unwind's first price: a fresh firm quote for its fixed leg, judged by the band, then attempt 1 and {@code T1}
     * stored under the lock order - the attempt's primary key deciding between instances - before any send.
     */
    private Optional<CoverStore.AttemptRow> priceUnwind(CoverStore.CoverRow cover, FxProvider adapter) {
        String quoteRequest = "CR-" + ids.next().toString().replace("-", "");
        FxProvider.FirmQuoteAnswer answer = adapter.firmQuote(new FxProvider.FirmQuoteRequest(
                quoteRequest, cover.source(), cover.destination(), cover.fixedSide(), cover.fixedAmount()));
        Optional<CoverStore.AttemptRow> priced = transactions.inTransaction(unitOfWork -> {
            retainQuote(unitOfWork, cover, quoteRequest, answer);
            if (!(answer instanceof FxProvider.FirmQuoteAnswer.Quoted quoted) || !plausible(unitOfWork, cover, quoted)) {
                return Optional.<CoverStore.AttemptRow>empty();
            }
            covers.lockWanted(unitOfWork, cover.quoteId());
            CoverStore.CoverRow locked = covers.lock(unitOfWork, cover.id()).orElseThrow();
            if (locked.status() != CoverStatus.DISPATCHED || locked.attempts() != 1) {
                return Optional.<CoverStore.AttemptRow>empty();
            }
            covers.insertFirstAttempt(unitOfWork, locked.id(), "T-" + ids.next().toString().replace("-", ""),
                    quoted.providerQuoteReference());
            return covers.attempt(unitOfWork, locked.id(), 1);
        });
        if (priced.isEmpty()) {
            log.error("ALERT: FX unwind {} at provider {} waits: its first price was refused ({})",
                    cover.id(), cover.providerCode(), answer.getClass().getSimpleName());
            observer.outcome(cover.providerCode(), cover.kind(), CoverObserver.Outcome.REQUOTE_REFUSED);
        }
        return priced;
    }

    private void requote(CoverStore.CoverRow cover, Actor actor) {
        boolean voided = transactions.inTransaction(unitOfWork -> {
            CoverStore.Wanted wanted = covers.lockWanted(unitOfWork, cover.quoteId());
            CoverStore.CoverRow locked = covers.lock(unitOfWork, cover.id()).orElseThrow();
            if (locked.status() != CoverStatus.REJECTED || locked.attempts() != cover.attempts()
                    || stillWanted(locked, wanted)) {
                return false;
            }
            if (!covers.transition(unitOfWork, locked.id(), locked.attempts(), CoverStatus.REJECTED, CoverStatus.VOIDED)) {
                return false;
            }
            audit.append(unitOfWork, new AuditRecord(
                    AuditId.next(ids), actor, Instant.now(clock), FxAuditAction.COVER_VOIDED, FxCoverOutcomes.AGGREGATE_TYPE,
                    locked.id().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, CorrelationId.of(locked.correlationId()),
                    Optional.of("quote=" + locked.quoteId().value() + ", quote " + wanted.quoteStatus()
                            + wanted.tradeStatus().map(status -> ", trade " + status).orElse(""))));
            return true;
        });
        if (voided) {
            observer.outcome(cover.providerCode(), cover.kind(), CoverObserver.Outcome.VOIDED);
            return;
        }
        Optional<FxProvider> adapter = adapter(cover.providerCode());
        if (adapter.isEmpty()) {
            return;
        }
        String quoteRequest = "CR-" + ids.next().toString().replace("-", "");
        FxProvider.FirmQuoteAnswer answer = adapter.get().firmQuote(new FxProvider.FirmQuoteRequest(
                quoteRequest, cover.source(), cover.destination(), cover.fixedSide(), cover.fixedAmount()));
        Requote result = transactions.inTransaction(unitOfWork -> {
            retainQuote(unitOfWork, cover, quoteRequest, answer);
            if (answer instanceof FxProvider.FirmQuoteAnswer.Quoted quoted && plausible(unitOfWork, cover, quoted)) {
                return requoted(unitOfWork, cover, quoted, actor) ? Requote.REQUOTED : Requote.LOST;
            }
            return covers.recordRequoteFailure(unitOfWork, cover.id(), cover.attempts()) ? Requote.REFUSED : Requote.LOST;
        });
        if (result == Requote.LOST) {
            return; // another applier advanced the cover first: its requote stands, nothing to report.
        }
        if (result == Requote.REFUSED) {
            log.error("ALERT: FX cover {} at provider {} stays REJECTED: its requote was refused ({})",
                    cover.id(), cover.providerCode(), answer.getClass().getSimpleName());
            observer.outcome(cover.providerCode(), cover.kind(), CoverObserver.Outcome.REQUOTE_REFUSED);
            return;
        }
        observer.outcome(cover.providerCode(), cover.kind(), CoverObserver.Outcome.REQUOTED);
        transactions.inTransaction(unitOfWork -> covers.find(unitOfWork, cover.id()))
                .filter(found -> found.status() == CoverStatus.DISPATCHED)
                .ifPresent(found -> send(found, actor));
    }

    /** A cover is wanted while its quote wants it; an unwind is wanted until it executes - its quote never wants again. */
    private static boolean stillWanted(CoverStore.CoverRow cover, CoverStore.Wanted wanted) {
        return cover.kind() == CoverKind.UNWIND || wanted.wanted();
    }

    /** What a requote leg came to: this caller requoted, another applier did first, or it was refused. */
    private enum Requote { REQUOTED, LOST, REFUSED }

    /** Attempt n+1 under the lock order, re-judged; whether this caller advanced it. */
    private boolean requoted(
            Connection unitOfWork, CoverStore.CoverRow cover, FxProvider.FirmQuoteAnswer.Quoted quoted, Actor actor) {
        CoverStore.Wanted wanted = covers.lockWanted(unitOfWork, cover.quoteId());
        CoverStore.CoverRow locked = covers.lock(unitOfWork, cover.id()).orElseThrow();
        if (locked.status() != CoverStatus.REJECTED || locked.attempts() != cover.attempts() || !stillWanted(locked, wanted)) {
            return false;
        }
        String reference = "T-" + ids.next().toString().replace("-", "");
        if (!covers.requote(unitOfWork, locked.id(), locked.attempts(), reference, quoted.providerQuoteReference())) {
            return false;
        }
        Instant now = Instant.now(clock);
        outbox.write(unitOfWork,
                outcomes.envelope(FxCoverOutcomes.REQUOTED_EVENT, locked, now),
                EventPayload.of()
                        .with("kind", locked.kind().name())
                        .with("attempt", Integer.toString(locked.attempts() + 1))
                        .with("providerCode", locked.providerCode())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids), actor, now, FxAuditAction.COVER_REQUOTED, FxCoverOutcomes.AGGREGATE_TYPE,
                locked.id().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, CorrelationId.of(locked.correlationId()),
                Optional.of("quote=" + locked.quoteId().value() + ", attempt=" + (locked.attempts() + 1)
                        + ", provider=" + locked.providerCode())));
        return true;
    }

    /**
     * The fresh firm quote, judged as an issuance judges one (ADR-0075 section 1): for this
     * cover's pair, its stated counter in the computed leg's currency, and within the band of the
     * quote's pinned policy against a FRESH reference. No fresh reference, no requote.
     */
    private boolean plausible(Connection unitOfWork, CoverStore.CoverRow cover, FxProvider.FirmQuoteAnswer.Quoted quoted) {
        ProviderQuote quote = quoted.quote();
        com.finapp.sharedkernel.money.CurrencyCode computed =
                cover.fixedSide() == FixedSide.FIXED_SOURCE ? cover.destination() : cover.source();
        if (!quote.rate().source().equals(cover.source()) || !quote.rate().destination().equals(cover.destination())
                || !quote.statedCounter().currency().equals(computed)) {
            return false;
        }
        QuoteStore.PlanRow plan = quotes.plan(unitOfWork, cover.quoteId()).orElseThrow();
        // The band and the reference age are the quote's own pair's, under the purpose it was priced for - an unwind's
        // pair is the reverse, which the policy need not offer (P9-TSK-021); the band judges either orientation.
        Optional<PolicyPair> terms = policies.version(unitOfWork, plan.version())
                .flatMap(version -> QuoteIssuance.termsFor(version, plan.purpose(), plan.source(), plan.destination()));
        if (terms.isEmpty()) {
            return false;
        }
        return references.freshLatest(unitOfWork, ReferenceSourceDeclaration.SOURCE,
                        QuoteIssuance.referencePair(cover.source(), cover.destination()), terms.get().referenceMaxAge())
                .map(reference -> QuoteIssuance.plausible(quote.rate(), reference.rate(), terms.get().band()))
                .orElse(false);
    }

    private void retainQuote(Connection unitOfWork, CoverStore.CoverRow cover, String quoteRequest, FxProvider.FirmQuoteAnswer answer) {
        Optional<FxProvider.Evidence> bytes = switch (answer) {
            case FxProvider.FirmQuoteAnswer.Quoted quoted -> Optional.of(quoted.evidence());
            case FxProvider.FirmQuoteAnswer.Declined declined -> Optional.of(declined.evidence());
            case FxProvider.FirmQuoteAnswer.Indeterminate indeterminate -> indeterminate.evidence();
            case FxProvider.FirmQuoteAnswer.NothingSent nothing -> Optional.empty();
        };
        bytes.filter(evidenceBytes -> evidenceBytes.bytes().length > 0
                        && evidenceBytes.bytes().length <= FxProviderEvidenceStore.MAX_PAYLOAD_BYTES)
                .ifPresent(evidenceBytes -> evidence.append(unitOfWork, cover.providerCode(), quoteRequest,
                        FxProviderEvidenceStore.Kind.RESPONSE, evidenceBytes.bytes(), Instant.now(clock)));
    }

    private FxCoverOutcomes.Applied apply(
            CoverStore.CoverRow cover, int attempt, FxProvider.ExecutionAnswer answer, FxCoverOutcomes.Channel channel,
            Actor actor) {
        FxCoverOutcomes.Applied applied = transactions.inTransaction(unitOfWork ->
                outcomes.apply(unitOfWork, cover.id(), attempt, answer, channel, actor));
        // The meters after the commit - a rolled-back application counts nothing.
        for (CoverObserver.Outcome outcome : applied.outcomes()) {
            observer.outcome(cover.providerCode(), cover.kind(), outcome);
        }
        applied.sinceBirth().ifPresent(age -> observer.executed(cover.providerCode(), cover.kind(), age));
        return applied;
    }

    private CoverStore.AttemptRow currentAttempt(CoverStore.CoverRow cover) {
        return transactions.inTransaction(unitOfWork -> covers.attempt(unitOfWork, cover.id(), cover.attempts()))
                .orElseThrow(() -> new IllegalStateException("a cover's current attempt has its row"));
    }

    private Optional<FxProvider> adapter(String providerCode) {
        Optional<FxProvider> adapter = providers.find(providerCode).map(FxProviders.Composed::adapter);
        if (adapter.isEmpty()) {
            log.warn("FX cover provider {} is not composed on this instance: its covers wait", providerCode);
        }
        return adapter;
    }
}
