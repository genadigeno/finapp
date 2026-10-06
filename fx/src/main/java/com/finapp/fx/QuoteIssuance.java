package com.finapp.fx;

import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues FX quotes in three steps that never share a connection (`P9-TSK-008`; ADR-0075 section 4,
 * PHASE_9_PLAN.md section 12.3): {@link #claim} - Tx1, beside the caller's idempotency claim;
 * {@link #source} - the wire, holding no connection; {@link #issue} - Tx2. Every window is the
 * database's: the claim stamps {@code requested_at}, the insert trigger computes the expiry, and
 * this class's {@link Clock} only measures the wire's total budget and stamps when an answer
 * arrived (provenance, never a decision).
 */
@Slf4j
public final class QuoteIssuance {

    public static final String ISSUED_EVENT = "fx.FxQuoteIssued";
    public static final String AGGREGATE_TYPE = "fx_quote";
    static final String PRODUCER = "fx";
    static final int EVENT_VERSION = 1;
    /** The wire's total budget across candidates; each call's own 2 s is the adapter's. */
    public static final Duration TOTAL_BUDGET = Duration.ofSeconds(5);

    private final PricingPolicyStore policies;
    private final FxAvailability availability;
    private final QuoteStore quotes;
    private final RateSnapshotStore<Connection> references;
    private final FxProviders providers;
    private final FxProviderEvidenceStore<Connection> evidence;
    private final ConversionParticipants participants;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public QuoteIssuance(
            PricingPolicyStore policies,
            FxAvailability availability,
            QuoteStore quotes,
            RateSnapshotStore<Connection> references,
            FxProviders providers,
            FxProviderEvidenceStore<Connection> evidence,
            ConversionParticipants participants,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
        this.references = Objects.requireNonNull(references, "references must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.participants = Objects.requireNonNull(participants, "participants must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What the customer asked: the pair, the fixed side and its exact amount. */
    public record QuoteRequest(
            UUID ownerParty, CurrencyCode source, CurrencyCode destination, FixedSide fixedSide, Money amount,
            PricingPurpose purpose) {
        public QuoteRequest {
            Objects.requireNonNull(ownerParty, "ownerParty must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(purpose, "purpose must not be null");
        }

        /** A wallet conversion's request - the purpose every caller before `P9-TSK-018` meant. */
        public QuoteRequest(UUID ownerParty, CurrencyCode source, CurrencyCode destination, FixedSide fixedSide, Money amount) {
            this(ownerParty, source, destination, fixedSide, amount, PricingPurpose.CONVERSION);
        }
    }

    /** A candidate provider, at its position in the pinned version's order. */
    record Candidate(int position, FxProviders.Composed composed) {}

    /** Tx1's outcome: the stored claim, its pinned terms, the candidates and the reference judged against. */
    public record Claimed(
            QuoteStore.RequestRow request,
            PolicyPair terms,
            List<Candidate> candidates,
            List<QuoteStore.Step> unavailable,
            RateSnapshot reference) {}

    /** The chosen provider's firm quote, and the plan it prices under the pinned terms. */
    public record Chosen(
            String providerCode,
            String providerQuoteReference,
            ProviderQuote quote,
            LocalDate valueDate,
            Instant obtainedAt,
            ConversionPlan.Plan plan) {}

    /** One answer's evidence, retained in Tx2. */
    record Retained(String providerCode, byte[] bytes) {}

    /** The wire's outcome: every step, the chosen quote if one was usable, else why not. */
    public record Sourced(List<QuoteStore.Step> steps, Optional<Chosen> chosen, QuoteRefusal failure, List<Retained> retained) {}

    /** An issued quote - or the one a racing flight issued for the same claim. */
    public record Issued(QuoteStore.QuoteRow quote, boolean converged) {}

    // ------------------------------------------------------------------ Tx1

    /**
     * The claim's work, beside the caller's {@code IN_PROGRESS} idempotency record: the owner's
     * standing, the pinned version (the claim's own on a takeover, else the {@code ACTIVE} one),
     * the pair offered and available, the fixed leg within its bounds, the reference fresh, the
     * cap pre-checked - all before any provider call - and the request stored or re-read.
     *
     * @throws QuoteRefusal.Refused for each refusal; nothing is stored by a refused claim
     */
    public Claimed claim(Connection unitOfWork, String claimKey, QuoteRequest asked, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(claimKey, "claimKey must not be null");
        Objects.requireNonNull(asked, "asked must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (participants.activeCustomer(unitOfWork, asked.ownerParty()).isEmpty()) {
            throw new QuoteRefusal.Refused(QuoteRefusal.CUSTOMER_NOT_ELIGIBLE);
        }
        Optional<QuoteStore.RequestRow> existing = quotes.requestByClaim(unitOfWork, claimKey);
        PricingPolicyStore.VersionView version =
                (existing.isPresent()
                                ? policies.version(unitOfWork, existing.get().version())
                                : policies.active(unitOfWork))
                        .orElseThrow(() -> new QuoteRefusal.Refused(QuoteRefusal.PAIR_NOT_OFFERED));
        PolicyPair terms = termsFor(version, asked.purpose(), asked.source(), asked.destination())
                .orElseThrow(() -> new QuoteRefusal.Refused(QuoteRefusal.PAIR_NOT_OFFERED));
        if (!admits(terms.pricing(), asked.fixedSide(), asked.amount())) {
            throw new QuoteRefusal.Refused(QuoteRefusal.AMOUNT_OUT_OF_RANGE);
        }
        if (!availability.isAvailable(unitOfWork, AvailabilitySubject.pair(pairName(asked.source(), asked.destination())))) {
            throw new QuoteRefusal.Refused(QuoteRefusal.PAIR_SUSPENDED);
        }
        // Advisory pre-checks that spare provider calls; Tx2 and the insert trigger decide.
        RateSnapshot reference = freshReference(unitOfWork, asked.source(), asked.destination(), terms)
                .orElseThrow(() -> new QuoteRefusal.Refused(QuoteRefusal.REFERENCE_STALE));
        if (quotes.liveCount(unitOfWork, asked.ownerParty()) >= version.row().openQuoteCap()) {
            throw new QuoteRefusal.Refused(QuoteRefusal.TOO_MANY_OPEN_QUOTES);
        }
        QuoteStore.RequestRow request = existing.orElseGet(() -> {
            UUID id = ids.next();
            return quotes.insertRequestIfAbsent(unitOfWork, new QuoteStore.RequestDraft(
                    id, reference(id), claimKey, asked.ownerParty(), asked.purpose(),
                    asked.source(), asked.destination(), asked.fixedSide(), asked.amount(),
                    version.row().id(), correlation.value()));
        });
        List<Candidate> candidates = new ArrayList<>();
        List<QuoteStore.Step> unavailable = new ArrayList<>();
        Set<String> declared = providers.declarations().stream()
                .map(FxProviderDeclaration::code)
                .collect(Collectors.toUnmodifiableSet());
        int position = 0;
        for (String code : terms.providers()) {
            position++;
            Optional<FxProviders.Composed> composed = providers.find(code);
            if (composed.isEmpty()) {
                unavailable.add(step(position, code, 0, QuoteStore.StepOutcome.UNAVAILABLE, "NOT_DECLARED"));
            } else if (!composed.get().declaration().quotes(asked.source(), asked.destination())) {
                unavailable.add(step(position, code, composed.get().declaration().version(),
                        QuoteStore.StepOutcome.UNAVAILABLE, "PAIR_NOT_QUOTED"));
            } else if (!availability.isAvailable(unitOfWork, AvailabilitySubject.provider(code, declared))) {
                unavailable.add(step(position, code, composed.get().declaration().version(),
                        QuoteStore.StepOutcome.UNAVAILABLE, "DISABLED"));
            } else {
                candidates.add(new Candidate(position, composed.get()));
            }
        }
        return new Claimed(request, terms, List.copyOf(candidates), List.copyOf(unavailable), reference);
    }

    // ------------------------------------------------------------------ the wire

    /**
     * Asks each candidate in the pinned order for a firm quote - no connection held - judging
     * each answer's coherence and plausibility in memory, failing over until one is usable or
     * the total budget is spent. A firm quote moves no money, so failover is safe.
     */
    public Sourced source(Claimed claimed) {
        Objects.requireNonNull(claimed, "claimed must not be null");
        QuoteStore.RequestRow request = claimed.request();
        List<QuoteStore.Step> steps = new ArrayList<>(claimed.unavailable());
        List<Retained> retained = new ArrayList<>();
        Instant start = clock.instant();
        boolean implausible = false;
        boolean incoherent = false;
        for (Candidate candidate : claimed.candidates()) {
            FxProviderDeclaration declaration = candidate.composed().declaration();
            String code = declaration.code();
            if (Duration.between(start, clock.instant()).compareTo(TOTAL_BUDGET) >= 0) {
                steps.add(step(candidate.position(), code, declaration.version(),
                        QuoteStore.StepOutcome.UNAVAILABLE, "BUDGET_EXHAUSTED"));
                continue;
            }
            FxProvider.FirmQuoteAnswer answer;
            try {
                answer = candidate.composed().adapter().firmQuote(new FxProvider.FirmQuoteRequest(
                        request.reference(), request.source(), request.destination(), request.fixedSide(),
                        request.fixedAmount()));
            } catch (RuntimeException adapterDefect) {
                log.warn("FX provider {} threw {} on a firm quote; treated as indeterminate",
                        code, adapterDefect.getClass().getSimpleName());
                answer = new FxProvider.FirmQuoteAnswer.Indeterminate(FxProvider.Indeterminacy.TRANSPORT, Optional.empty());
            }
            int version = declaration.version();
            switch (answer) {
                case FxProvider.FirmQuoteAnswer.Declined declined -> {
                    retained.add(new Retained(code, declined.evidence().bytes()));
                    steps.add(step(candidate.position(), code, version, QuoteStore.StepOutcome.DECLINED, declined.reason().name()));
                }
                case FxProvider.FirmQuoteAnswer.NothingSent ignored ->
                        steps.add(step(candidate.position(), code, version, QuoteStore.StepOutcome.NOTHING_SENT, null));
                case FxProvider.FirmQuoteAnswer.Indeterminate indeterminate -> {
                    indeterminate.evidence().ifPresent(e -> retained.add(new Retained(code, e.bytes())));
                    steps.add(step(candidate.position(), code, version, QuoteStore.StepOutcome.INDETERMINATE,
                            indeterminate.cause().name()));
                }
                case FxProvider.FirmQuoteAnswer.Quoted quoted -> {
                    retained.add(new Retained(code, quoted.evidence().bytes()));
                    Judged judged = judge(quoted.quote(), request, claimed.terms(), claimed.reference());
                    switch (judged.outcome()) {
                        case INCOHERENT -> incoherent = true;
                        case IMPLAUSIBLE -> implausible = true;
                        default -> { }
                    }
                    steps.add(step(candidate.position(), code, version, judged.outcome(), judged.detail()));
                    if (judged.plan() != null) {
                        steps.add(step(candidate.position(), code, version, QuoteStore.StepOutcome.CHOSEN, null));
                        return new Sourced(List.copyOf(steps), Optional.of(new Chosen(
                                code, quoted.providerQuoteReference(), quoted.quote(), quoted.valueDate(),
                                clock.instant().truncatedTo(ChronoUnit.MICROS), judged.plan())),
                                QuoteRefusal.RATE_UNAVAILABLE, List.copyOf(retained));
                    }
                }
            }
        }
        QuoteRefusal failure = implausible ? QuoteRefusal.IMPLAUSIBLE
                : incoherent ? QuoteRefusal.INCOHERENT : QuoteRefusal.RATE_UNAVAILABLE;
        return new Sourced(List.copyOf(steps), Optional.empty(), failure, List.copyOf(retained));
    }

    /** One quoted answer, judged: coherent and plausible yields the plan. */
    record Judged(QuoteStore.StepOutcome outcome, String detail, ConversionPlan.Plan plan) {}

    static Judged judge(ProviderQuote quote, QuoteStore.RequestRow request, PolicyPair terms, RateSnapshot reference) {
        ConversionPlan.Result result;
        try {
            result = ConversionPlan.compute(request.fixedSide(), request.fixedAmount(), quote, terms.pricing());
        } catch (IllegalArgumentException wrongShape) {
            // A quote for another pair or in another currency: the provider's answer contradicts the ask.
            return new Judged(QuoteStore.StepOutcome.INCOHERENT, "WRONG_SHAPE", null);
        }
        return switch (result) {
            case ConversionPlan.Refused refused when refused.refusal() == ConversionPlan.Refusal.PROVIDER_QUOTE_INCOHERENT ->
                    new Judged(QuoteStore.StepOutcome.INCOHERENT, null, null);
            case ConversionPlan.Refused refused -> {
                if (refused.refusal() == ConversionPlan.Refusal.PLAN_INVARIANT_VIOLATED) {
                    log.error("CRITICAL: a plan's residual exceeded the policy's bound ({}); not issued", refused.detail());
                }
                yield new Judged(QuoteStore.StepOutcome.QUOTED, refused.refusal().name(), null);
            }
            case ConversionPlan.Priced priced -> plausible(quote.rate(), reference.rate(), terms.band())
                    ? new Judged(QuoteStore.StepOutcome.QUOTED, null, priced.plan())
                    : new Judged(QuoteStore.StepOutcome.IMPLAUSIBLE, null, null);
        };
    }

    // ------------------------------------------------------------------ Tx2

    /**
     * The insert's transaction: the steps and evidence recorded first (a refusal keeps them), then
     * the pinned version held {@code FOR SHARE} and still {@code ACTIVE}, the latest reference
     * fresh and the chosen rate still plausible against it, and the quote inserted - the trigger
     * computing its window and arbitrating the cap - with its history and outbox. A racing flight
     * that issued first is converged on.
     *
     * @throws QuoteRefusal.Refused for each refusal, after the steps and evidence are written in
     *     the caller's transaction (which commits them with the claim's failed outcome)
     */
    public Issued issue(Connection unitOfWork, Claimed claimed, Sourced sourced, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(claimed, "claimed must not be null");
        Objects.requireNonNull(sourced, "sourced must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        QuoteStore.RequestRow request = claimed.request();
        Optional<FxQuoteId> racing = quotes.quoteOfRequest(unitOfWork, request.id());
        if (racing.isPresent()) {
            return converged(unitOfWork, racing.get(), request);
        }
        int attempt = quotes.nextAttempt(unitOfWork, request.id());
        quotes.insertSteps(unitOfWork, request.id(), attempt, sourced.steps());
        Instant retainedAt = clock.instant();
        for (Retained answer : sourced.retained()) {
            evidence.append(unitOfWork, answer.providerCode(), request.reference(),
                    FxProviderEvidenceStore.Kind.RESPONSE, answer.bytes(), retainedAt);
        }
        Chosen chosen = sourced.chosen().orElseThrow(() -> new QuoteRefusal.Refused(sourced.failure()));
        if (!policies.holdActive(unitOfWork, request.version())) {
            throw new QuoteRefusal.Refused(QuoteRefusal.POLICY_STALE);
        }
        RateSnapshot reference = freshReference(unitOfWork, request.source(), request.destination(), claimed.terms())
                .orElseThrow(() -> new QuoteRefusal.Refused(QuoteRefusal.REFERENCE_STALE));
        if (!plausible(chosen.quote().rate(), reference.rate(), claimed.terms().band())) {
            throw new QuoteRefusal.Refused(QuoteRefusal.IMPLAUSIBLE);
        }
        ConversionPlan.Plan plan = chosen.plan();
        BigDecimal disclosed = ConversionPlan.disclosedMarginOverMid(plan.customerRate(), reference.rate());
        FxQuoteId id = FxQuoteId.next(ids);
        EventId issuedEvent = EventId.next(ids);
        QuoteStore.Insertion insertion = quotes.insertQuote(unitOfWork, new QuoteStore.QuoteDraft(
                id, request, chosen.providerCode(), chosen.providerQuoteReference(), chosen.quote(),
                chosen.valueDate(), chosen.obtainedAt(), reference, claimed.terms(), plan, disclosed,
                issuedEvent.value(), correlation.value()));
        return switch (insertion) {
            case QuoteStore.CapReached cap -> throw new QuoteRefusal.Refused(QuoteRefusal.TOO_MANY_OPEN_QUOTES);
            case QuoteStore.WindowTooShort window -> throw new QuoteRefusal.Refused(QuoteRefusal.RATE_UNAVAILABLE);
            case QuoteStore.AlreadyIssued already -> converged(unitOfWork, already.id(), request);
            case QuoteStore.Inserted inserted -> {
                quotes.appendEvent(unitOfWork, id, Optional.empty(), QuoteStatus.ISSUED, actor.id(),
                        actor.type().name(), Optional.empty(), correlation.value());
                outbox.write(
                        unitOfWork,
                        new EventEnvelope(issuedEvent, ISSUED_EVENT, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                                id, AGGREGATE_TYPE, inserted.issuedAt(), PRODUCER, correlation,
                                CausationId.of(correlation.value())),
                        EventPayload.of()
                                .with("purpose", request.purpose().name())
                                .with("sourceCurrency", request.source().code())
                                .with("destinationCurrency", request.destination().code())
                                .with("fixedSide", request.fixedSide().name())
                                .with("sourceMinor", Long.toString(plan.customerPays().minorUnits()))
                                .with("sourceScale", Integer.toString(plan.customerPays().scale()))
                                .with("destinationMinor", Long.toString(plan.customerReceives().minorUnits()))
                                .with("destinationScale", Integer.toString(plan.customerReceives().scale()))
                                .with("expiresAtEpochMicros", Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, inserted.expiresAt())))
                                .with("pricingPolicyVersionId", request.version().value().toString())
                                .toBytes(),
                        EventPayload.MEDIA_TYPE);
                yield new Issued(quotes.findOwned(unitOfWork, id, request.owner())
                        .orElseThrow(() -> new IllegalStateException("an issued quote must read back")), false);
            }
        };
    }

    // ------------------------------------------------------------------ the judgements

    /** The pair's terms for a conversion in the version, if it offers the pair. */
    static Optional<PolicyPair> termsFor(PricingPolicyStore.VersionView version, CurrencyCode source, CurrencyCode destination) {
        return termsFor(version, PricingPurpose.CONVERSION, source, destination);
    }

    /** The pair's terms for {@code purpose} in the version, if it offers the pair for it (`P9-TSK-018`). */
    static Optional<PolicyPair> termsFor(
            PricingPolicyStore.VersionView version, PricingPurpose purpose, CurrencyCode source, CurrencyCode destination) {
        return version.pairs().stream()
                .filter(pair -> pair.purpose() == purpose
                        && pair.pricing().source().equals(source)
                        && pair.pricing().destination().equals(destination))
                .findFirst();
    }

    /** Whether the fixed leg is within the pair's notional bounds, exactly at its currency's scale. */
    public static boolean admits(PricingPair pricing, FixedSide fixedSide, Money amount) {
        return (fixedSide == FixedSide.FIXED_SOURCE ? pricing.sourceBounds() : pricing.destinationBounds()).admits(amount);
    }

    /**
     * The plausibility band, exact and with no division (ADR-0075 section 1): in the pair's direction
     * {@code |rp - ref| <= band x ref}; in the inverse direction {@code |rp x ref - 1| <= band}.
     */
    public static boolean plausible(ExchangeRate provider, ExchangeRate reference, BigDecimal band) {
        if (provider.source().equals(reference.source()) && provider.destination().equals(reference.destination())) {
            return provider.value().subtract(reference.value()).abs().compareTo(band.multiply(reference.value())) <= 0;
        }
        if (provider.source().equals(reference.destination()) && provider.destination().equals(reference.source())) {
            return provider.value().multiply(reference.value()).subtract(BigDecimal.ONE).abs().compareTo(band) <= 0;
        }
        return false;
    }

    /** The reference source's canonical pair for either direction of a conversion. */
    static ReferencePair referencePair(CurrencyCode source, CurrencyCode destination) {
        ReferencePair forward = new ReferencePair(source, destination);
        return ReferenceSourceDeclaration.declares(forward) ? forward : new ReferencePair(destination, source);
    }

    private Optional<RateSnapshot> freshReference(Connection unitOfWork, CurrencyCode source, CurrencyCode destination, PolicyPair terms) {
        return references.freshLatest(unitOfWork, ReferenceSourceDeclaration.SOURCE,
                referencePair(source, destination), terms.referenceMaxAge());
    }

    private Issued converged(Connection unitOfWork, FxQuoteId id, QuoteStore.RequestRow request) {
        return new Issued(quotes.findOwned(unitOfWork, id, request.owner())
                .orElseThrow(() -> new IllegalStateException("a request's quote is its owner's")), true);
    }

    static String pairName(CurrencyCode source, CurrencyCode destination) {
        return source.code() + "-" + destination.code();
    }

    /** Our quote-request reference {@code QR}, as the column's shape requires. */
    static String reference(UUID id) {
        return "QR-" + id.toString().replace("-", "");
    }

    private static QuoteStore.Step step(int position, String code, int version, QuoteStore.StepOutcome outcome, String detail) {
        return new QuoteStore.Step(position, code, version, outcome, Optional.ofNullable(detail));
    }
}
