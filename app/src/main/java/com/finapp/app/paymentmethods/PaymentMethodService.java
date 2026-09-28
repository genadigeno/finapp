package com.finapp.app.paymentmethods;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.paymentmethods.DestinationReference;
import com.finapp.paymentmethods.PayeeCheck;
import com.finapp.paymentmethods.PaymentMethod;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.paymentmethods.PaymentmethodsAuditAction;
import com.finapp.paymentmethods.PaymentmethodsErrorCode;
import com.finapp.paymentmethods.TokenisationGrant;
import com.finapp.paymentmethods.TokenisationProvider;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.ExchangeAnswer;
import com.finapp.payments.PushRail;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/me/payment-methods` slice behind {@link PaymentMethodController} (`P5-TSK-005`;
 * bank accounts by `P7-TSK-007`) — the {@code BeneficiaryService} shape:
 * {@code Session → Identity → Party} per operation, the Party as owner, no live-customer step
 * (an instrument is a Party's convenience; money-gating happens at the intent, `P5-TSK-009`),
 * and both registration doors as conditional step-up points.
 *
 * <h2>Both registrations span a provider call, so each is two transactions</h2>
 *
 * <p>Tx1 resolves the party and <strong>fails fast</strong> on the step-up (read-only for the
 * card; for the bank account it also commits the idempotency claim, and a step-up refusal
 * rolls the claim back with it, so a refused caller's key stays unburned); the exchange then
 * runs <strong>holding no database connection</strong> (the `P1-TSK-026` discipline); Tx2
 * <strong>re-checks the step-up authoritatively before any write</strong> — the per-decision
 * read at the write is the control, the Tx1 check the fail-fast — then attaches, audits and
 * announces, creating call only.
 *
 * <h2>The card is unkeyed, the bank account keyed — a recorded asymmetry</h2>
 *
 * <p>The card exchange is grant-idempotent, so the natural-key convergence is its whole retry
 * story. The bank grant is <strong>single-use at the provider</strong> (ADR-0062 §2), so a
 * lost-response retry cannot re-exchange: the register is keyed per party
 * ({@code payment-method-register:<party>}), Tx1 commits the claim beside nothing, and the
 * replay answers the recorded outcome — refusals included — byte for byte. A crash between
 * the exchange and Tx2 leaves the claim in progress until the database-owned lease expires;
 * the takeover re-dispatches, its re-exchange meets the spent grant's {@code REFUSED}, and
 * the honest failure is recorded — no money and no state were at stake, the customer links
 * again.
 *
 * <h2>The events are this surface's, in the act's transaction</h2>
 *
 * <p>{@code paymentmethods.PaymentMethodAttached}/{@code Detached} (plan §10) ride the outbox
 * in the transaction that commits the fact ({@code INV-EVT-01}), acting call only; payloads
 * carry the machine's status and the kind — enumerated names, never a reference and never
 * display metadata ({@code INV-AUD-02}'s discipline at {@code EventPayload}).
 */
public final class PaymentMethodService {

    static final String ATTACHED_EVENT = "paymentmethods.PaymentMethodAttached";
    static final String DETACHED_EVENT = "paymentmethods.PaymentMethodDetached";
    static final String PRODUCER = "paymentmethods";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "payment_method";

    /** The keyed register's claim scope prefix; the principal completes it (ADR-0004). */
    static final String REGISTER_SCOPE_PREFIX = "payment-method-register:";

    /** The audit reason when consent was the gate — an enumerated constant, never a value
     * ({@code INV-AUD-02}); the reason field's first use at this surface. */
    static final String NO_MATCH_ACKNOWLEDGED_REASON = "PAYEE_CHECK_NO_MATCH_ACKNOWLEDGED";

    private final PaymentMethodStore<Connection> methods;
    private final ObjectProvider<TokenisationProvider> tokenisation;
    private final ObjectProvider<PushRail> instantRail;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final IdentityStore<Connection> identities;
    private final AuditWriter<Connection> auditWriter;
    private final OutboxWriter<Connection> outbox;
    private final IdempotentExecutor executor;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public PaymentMethodService(
            PaymentMethodStore<Connection> methods,
            ObjectProvider<TokenisationProvider> tokenisation,
            ObjectProvider<PushRail> instantRail,
            MfaEnrolmentStore<Connection> enrolments,
            IdentityStore<Connection> identities,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outbox,
            IdempotentExecutor executor,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate paymentMethodTransactions,
            DataSource dataSource) {
        this.methods = Objects.requireNonNull(methods, "methods must not be null");
        this.tokenisation =
                Objects.requireNonNull(tokenisation, "tokenisation must not be null");
        this.instantRail = Objects.requireNonNull(instantRail, "instantRail must not be null");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(
                        paymentMethodTransactions, "paymentMethodTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * The rendered instrument — display metadata by construction, never a reference. The card
     * fields are the card kind's and null on a bank account; {@code payeeCheck} the reverse
     * (`P7-TSK-007`: one list renders both kinds, and what a kind lacks it honestly lacks).
     */
    public record PaymentMethodView(
            String id,
            String kind,
            String brand,
            String displaySuffix,
            Integer expiryMonth,
            Integer expiryYear,
            String payeeCheck,
            String createdAt) {

        static PaymentMethodView of(PaymentMethod method) {
            return new PaymentMethodView(
                    method.id().value().toString(),
                    method.kind().name(),
                    method.brand().orElse(null),
                    method.displaySuffix(),
                    method.expiryMonth().orElse(null),
                    method.expiryYear().orElse(null),
                    method.payeeCheck().map(PayeeCheck::name).orElse(null),
                    method.createdAt().toString());
        }
    }

    /**
     * Attaches the instrument the grant tokenises to, or converges on the live row already
     * holding its (party, token) slot — 201 either way (the convergence idiom); the
     * lost-response retry rides the provider's grant-idempotent exchange onto the same slot.
     */
    public PaymentMethodView attach(Session current, Sensitive<String> clientToken) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(clientToken, "clientToken must not be null");

        // The grant's shape - including the refusal of card-number-shaped values, INV-PAY-02
        // at the surface - decided before any transaction or provider call. Names the field,
        // never the value (INV-AUD-02).
        TokenisationGrant grant;
        try {
            grant = new TokenisationGrant(clientToken);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A tokenisation grant was refused by the domain rule",
                    "clientToken must be a tokenisation grant, not an instrument number.");
        }

        // Tx1: fail fast, read-only - a refused caller costs no exchange and writes nothing.
        inOneTransaction(
                unitOfWork -> {
                    partyOf(unitOfWork, current);
                    requireConditionalAssurance(unitOfWork, current);
                    return null;
                });

        // The exchange, holding no database connection (the P1-TSK-026 discipline). Absent
        // provider = unavailable: an unconfigured deployment keeps a stable contract and
        // answers the honest 503 (the ObjectProvider decision, recorded in the beans).
        TokenisationProvider provider = tokenisation.getIfAvailable();
        TokenisationProvider.Exchange exchange =
                provider == null
                        ? TokenisationProvider.Exchange.unavailable()
                        : provider.exchange(grant);
        TokenisationProvider.TokenisedInstrument instrument =
                switch (exchange.outcome()) {
                    case TOKENISED -> exchange.instrument().orElseThrow();
                    case REFUSED ->
                            throw new ApiException(
                                    PaymentmethodsErrorCode.INSTRUMENT_NOT_TOKENISED,
                                    "The tokenisation provider refused the grant");
                    case UNAVAILABLE -> throw tokenisationUnavailable();
                };

        // Tx2: the authoritative step-up decision at the write, then the attach - the
        // ApiException aborts the transaction, so a refusal commits nothing.
        return inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    requireConditionalAssurance(unitOfWork, current);
                    PaymentMethod fresh;
                    try {
                        fresh =
                                PaymentMethod.attachCard(
                                        PaymentMethodId.next(ids),
                                        partyId,
                                        instrument.token(),
                                        instrument.brand(),
                                        instrument.displaySuffix(),
                                        instrument.expiryMonth(),
                                        instrument.expiryYear(),
                                        clock);
                    } catch (IllegalArgumentException unstorable) {
                        // The aggregate's constructor is the display metadata's one validator
                        // (the TokenisedInstrument decision): a provider answer we refuse to
                        // store is an exchange that did not yield an instrument.
                        throw tokenisationUnavailable();
                    }
                    PaymentMethodStore.Attachment attachment =
                            methods.attachOrConverge(unitOfWork, fresh);
                    if (attachment.created()) {
                        // Only the creating call is an act: one record, one event, however
                        // many times it was asked for.
                        act(
                                unitOfWork,
                                PaymentmethodsAuditAction.PAYMENT_METHOD_ATTACHED,
                                ATTACHED_EVENT,
                                attachment.method(),
                                Optional.empty());
                    }
                    return PaymentMethodView.of(attachment.method());
                });
    }

    /**
     * Registers the external bank account the grant links to (`P7-TSK-007`, ADR-0062 §2), or
     * replays this key's recorded outcome — refusals included.
     *
     * <p>The exchange keeps exactly three values ({@code INV-RAIL-03}): the opaque
     * destination, the four-character suffix, the payee word. Its evidence bytes are
     * <strong>deliberately dropped</strong> — a provider body can carry the account holder's
     * name, which is exactly what this boundary refuses to hold. A {@code NO_MATCH} answer
     * needs {@code acknowledgeNoMatch} or is refused on the record (409, replayable), the
     * mismatch never stored; the grant being single-use, the acknowledged retry arrives with
     * a fresh grant under a new key — the recorded cost of refusing a half-registered
     * pending state.
     */
    public PaymentMethodView registerBankAccount(
            Session current, RegisterBankAccountRequest request, String idempotencyKey) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        String grant = request.grant().expose();
        // The grant's shape rule at the boundary (the Phase 7 -> 8 transition): the port's
        // own predicate, so nothing a customer types can steer the provider's request - and
        // nothing shaped like a bank identifier gets further than this line (INV-RAIL-03).
        if (!com.finapp.payments.PushRail.GrantExchange.wellShaped(grant)) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A bank-account grant was refused by the domain rule",
                    "grant must be the rail provider's one-time linking grant: 1-"
                            + com.finapp.payments.PushRail.GrantExchange.MAX_GRANT_LENGTH
                            + " characters of letters, digits and _ . : - with a letter.");
        }

        // Tx1: resolve the party (the claim's scope names the principal, so the reads come
        // first in the same transaction), fail fast on the step-up, then claim. A step-up
        // refusal aborts the transaction and takes the claim with it - the key stays
        // unburned, and no exchange was spent on a refused caller.
        record Begun(UUID partyId, IdempotencyKey key, IdempotentExecutor.BeginOutcome outcome) {}
        Begun begun =
                inOneTransaction(
                        unitOfWork -> {
                            UUID partyId = partyOf(unitOfWork, current);
                            requireConditionalAssurance(unitOfWork, current);
                            IdempotencyKey key =
                                    new IdempotencyKey(
                                            REGISTER_SCOPE_PREFIX + partyId, idempotencyKey);
                            // The grant is part of what the request MEANS, so it enters the
                            // canonical form - SHA-256-hashed in the same expression, never
                            // stored or logged bare (the PayoutDestinations claim).
                            RequestFingerprint fingerprint =
                                    RequestFingerprint.sha256(
                                            ("payment-method-register|"
                                                            + partyId
                                                            + "|"
                                                            + grant
                                                            + "|"
                                                            + request.acknowledgeNoMatch())
                                                    .getBytes(StandardCharsets.UTF_8));
                            return new Begun(
                                    partyId,
                                    key,
                                    executor.begin(
                                            unitOfWork,
                                            key,
                                            fingerprint,
                                            claimed ->
                                                    // Nothing beyond the claim must be durable
                                                    // before the exchange; a lease takeover
                                                    // re-runs this and converges by re-reading
                                                    // the same party.
                                                    partyId.toString()
                                                            .getBytes(StandardCharsets.UTF_8)));
                        });
        if (begun.outcome().replay().isPresent()) {
            return parsedReplay(begun.outcome().replay().get());
        }

        // The exchange, holding no database connection (ADR-0046's discipline; the grant's
        // one legitimate destination). Our reference is minted fresh: the exchange is not a
        // send, and the claim - not the reference - is this command's convergence.
        PushRail rail = instantRail.getIfAvailable();
        ExchangeAnswer answer =
                rail == null
                        ? ExchangeAnswer.nothingSent()
                        : rail.exchange(
                                new PushRail.GrantExchange(
                                        new EndToEndReference(
                                                ids.next().toString().replace("-", "")),
                                        grant));

        // Tx2: the authoritative step-up decision, the outcome applied, and the claim
        // completed IN THE SAME TRANSACTION - a refusal is recorded terminal with its error,
        // then thrown after the commit so the record survives the 4xx/5xx answer.
        Outcome outcome =
                inOneTransaction(
                        unitOfWork -> {
                            requireConditionalAssurance(unitOfWork, current);
                            Outcome judged = judge(unitOfWork, begun.partyId(), request, answer);
                            executor.complete(
                                    unitOfWork,
                                    begun.key(),
                                    judged.refusal() == null,
                                    StoredResponse.of(
                                            judged.stored().getBytes(StandardCharsets.UTF_8),
                                            "text/plain"));
                            return judged;
                        });
        if (outcome.refusal() != null) {
            throw refusalFor(outcome.refusal());
        }
        return outcome.view();
    }

    /** Tx2's judged result: a view to answer, or a refusal recorded on the claim. */
    private record Outcome(PaymentMethodView view, PaymentmethodsErrorCode refusal) {

        String stored() {
            return refusal != null
                    ? "ERR|" + refusal.name()
                    : "OK|"
                            + view.id()
                            + "|"
                            + view.displaySuffix()
                            + "|"
                            + view.payeeCheck()
                            + "|"
                            + view.createdAt();
        }
    }

    /** Maps the exchange's total answer onto the register's outcome, acting only on
     * {@code EXCHANGED} with consent satisfied. */
    private Outcome judge(
            Connection unitOfWork,
            UUID partyId,
            RegisterBankAccountRequest request,
            ExchangeAnswer answer) {
        return switch (answer.outcome()) {
            case REFUSED ->
                    // Knowledge: spent, expired or revoked - the caller's own grant (422).
                    new Outcome(null, PaymentmethodsErrorCode.GRANT_EXCHANGE_REFUSED);
            case INDETERMINATE, NOTHING_SENT ->
                    // No usable answer (or no rail configured): honest retry-later, recorded.
                    new Outcome(null, PaymentmethodsErrorCode.GRANT_EXCHANGE_UNAVAILABLE);
            case EXCHANGED -> {
                PayeeCheck check = payeeCheckOf(answer.payee().orElseThrow());
                if (check == PayeeCheck.NO_MATCH && !request.acknowledgeNoMatch()) {
                    // ADR-0062 §2's consent rule, refused ON THE RECORD: the claim stores
                    // this 409, and nothing of the mismatch is retained (INV-RAIL-03).
                    yield new Outcome(null, PaymentmethodsErrorCode.PAYEE_CHECK_NO_MATCH);
                }
                PaymentMethod fresh;
                try {
                    fresh =
                            PaymentMethod.registerBankAccount(
                                    PaymentMethodId.next(ids),
                                    partyId,
                                    DestinationReference.of(
                                            answer.destination().orElseThrow().value()),
                                    answer.displaySuffix().orElseThrow(),
                                    check,
                                    request.acknowledgeNoMatch(),
                                    clock);
                } catch (IllegalArgumentException unstorable) {
                    // An answer we refuse to store - a suffix outside the bound, a
                    // destination in a refused shape (INV-RAIL-03's structural stance) - is
                    // an exchange that did not yield an instrument. The adapter-prefix
                    // contract rides DestinationReference's javadoc.
                    yield new Outcome(null, PaymentmethodsErrorCode.GRANT_EXCHANGE_UNAVAILABLE);
                }
                PaymentMethodStore.Attachment attachment =
                        methods.attachOrConverge(unitOfWork, fresh);
                if (attachment.created()) {
                    // Only the creating call is an act; the audit carries the consent
                    // reason exactly when consent was the gate.
                    act(
                            unitOfWork,
                            PaymentmethodsAuditAction.PAYMENT_METHOD_ATTACHED,
                            ATTACHED_EVENT,
                            attachment.method(),
                            attachment.method().noMatchAcknowledgedAt().isPresent()
                                    ? Optional.of(NO_MATCH_ACKNOWLEDGED_REASON)
                                    : Optional.empty());
                }
                yield new Outcome(PaymentMethodView.of(attachment.method()), null);
            }
        };
    }

    /** The port's word onto this module's own — exhaustive, so a fifth word is a compile
     * error here rather than a runtime guess (the PCI build-graph restatement's seam). */
    private static PayeeCheck payeeCheckOf(ExchangeAnswer.ConfirmationOfPayee payee) {
        return switch (payee) {
            case MATCH -> PayeeCheck.MATCH;
            case CLOSE_MATCH -> PayeeCheck.CLOSE_MATCH;
            case NO_MATCH -> PayeeCheck.NO_MATCH;
            case UNAVAILABLE -> PayeeCheck.UNAVAILABLE;
        };
    }

    /** A replayed claim, byte for byte: the recorded view, or the recorded refusal re-thrown. */
    private static PaymentMethodView parsedReplay(StoredResponse response) {
        String stored = new String(response.body(), StandardCharsets.UTF_8);
        if (stored.startsWith("ERR|")) {
            throw refusalFor(PaymentmethodsErrorCode.valueOf(stored.substring(4)));
        }
        String[] parts = stored.split("\\|");
        if (parts.length != 5 || !"OK".equals(parts[0])) {
            throw new IllegalStateException(
                    "a payment-method register claim held a response in no known form");
        }
        return new PaymentMethodView(
                parts[1],
                com.finapp.paymentmethods.PaymentMethodKind.BANK_ACCOUNT.name(),
                null,
                parts[2],
                null,
                null,
                parts[3],
                parts[4]);
    }

    private static ApiException refusalFor(PaymentmethodsErrorCode code) {
        return switch (code) {
            case GRANT_EXCHANGE_REFUSED ->
                    new ApiException(code, "The rail provider refused the grant");
            case GRANT_EXCHANGE_UNAVAILABLE ->
                    new ApiException(code, "The grant exchange could not be completed");
            case PAYEE_CHECK_NO_MATCH ->
                    new ApiException(
                            code,
                            "The payee check found no match and the request carried no"
                                    + " acknowledgement");
            default ->
                    throw new IllegalStateException(
                            "a register claim recorded a refusal this surface never issues: "
                                    + code.name());
        };
    }

    /** The caller's live payment methods, oldest first. */
    public List<PaymentMethodView> list(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return inOneTransaction(
                unitOfWork ->
                        methods
                                .listLiveFor(unitOfWork, partyOf(unitOfWork, current))
                                .stream()
                                .map(PaymentMethodView::of)
                                .toList());
    }

    /**
     * Detaches the caller's instrument — or converges on one already detached, the retry story
     * of a lost {@code DELETE} response.
     *
     * @return {@code true} when the identifier names a row of the caller's (detached now, or
     *     already — the converging 204); {@code false} for the surface's one 404
     */
    public boolean detach(Session current, PaymentMethodId method) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(method, "method must not be null");
        return inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    if (methods.detach(unitOfWork, method, partyId, Instant.now(clock))) {
                        // The winning detach is the act; a converged retry and a stranger's
                        // attempt moved nothing and record nothing.
                        actOnDetach(unitOfWork, method);
                        return true;
                    }
                    return methods.findOwned(unitOfWork, method, partyId).isPresent();
                });
    }

    // -----------------------------------------------------------------

    /**
     * {@code MULTI_FACTOR} required exactly of an identity that has an active factor — the
     * `P4-TSK-007` conditional verbatim: an authoritative read per decision, never a cache.
     */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor =
                enrolments
                        .findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP)
                        .isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(
                    IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A payment-method attach from an MFA-enrolled identity requires a"
                            + " MULTI_FACTOR session");
        }
    }

    private static ApiException tokenisationUnavailable() {
        return new ApiException(
                PaymentmethodsErrorCode.TOKENISATION_UNAVAILABLE,
                "The tokenisation exchange could not be completed");
    }

    /** {@code Session → Identity → Party}: the {@code BeneficiaryService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A proven session resolved to no identity; registration"
                                                + " should make this impossible"));
    }

    /**
     * The person's own act: the audit record naming the person ({@code SecurityContext
     * .require()}) and the event, in the act's transaction ({@code INV-EVT-01}). Identifiers,
     * enumerated names and the enumerated consent reason only — never a reference, never
     * display metadata ({@code INV-AUD-02}).
     */
    private void act(
            Connection unitOfWork,
            PaymentmethodsAuditAction action,
            String eventType,
            PaymentMethod method,
            Optional<String> reason) {
        append(
                unitOfWork,
                action,
                eventType,
                method.id(),
                reason,
                EventPayload.of()
                        .with("status", method.status().name())
                        .with("kind", method.kind().name()));
    }

    /** The detach's act: the conditional {@code UPDATE} never loaded the row, so the payload
     * carries the machine's status alone — the shipped `P5-TSK-005` contract unchanged. */
    private void actOnDetach(Connection unitOfWork, PaymentMethodId method) {
        append(
                unitOfWork,
                PaymentmethodsAuditAction.PAYMENT_METHOD_DETACHED,
                DETACHED_EVENT,
                method,
                Optional.empty(),
                EventPayload.of().with("status", PaymentMethodStatus.DETACHED.name()));
    }

    private void append(
            Connection unitOfWork,
            PaymentmethodsAuditAction action,
            String eventType,
            PaymentMethodId method,
            Optional<String> reason,
            EventPayload payload) {
        Correlation correlation = resolvedCorrelation();
        Instant now = Instant.now(clock);
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        now,
                        action,
                        TARGET_TYPE,
                        method.value().toString(),
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.empty()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        method,
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                // Enumerated names only (INV-AUD-02 at EventPayload): the machine's status
                // and the kind - and never a reference, never display metadata, since brand
                // is provider text rather than an enumerated name.
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** The flow's correlation with the cause resolved — the {@code AccountOpening} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a payment-method act must run inside a"
                                                        + " correlation scope: the audit record"
                                                        + " and the event both carry the"
                                                        + " identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
