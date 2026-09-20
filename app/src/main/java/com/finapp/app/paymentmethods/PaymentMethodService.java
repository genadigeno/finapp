package com.finapp.app.paymentmethods;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.paymentmethods.PaymentMethod;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.paymentmethods.PaymentmethodsAuditAction;
import com.finapp.paymentmethods.PaymentmethodsErrorCode;
import com.finapp.paymentmethods.TokenisationGrant;
import com.finapp.paymentmethods.TokenisationProvider;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
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
 * The `/v1/me/payment-methods` slice behind {@link PaymentMethodController} (`P5-TSK-005`) —
 * the {@code BeneficiaryService} shape: {@code Session → Identity → Party} per operation, the
 * Party as owner, no live-customer step (an instrument is a Party's convenience; money-gating
 * happens at the intent, `P5-TSK-009`), and attach as the conditional step-up point.
 *
 * <h2>The attach spans a provider call, so it is two transactions</h2>
 *
 * <p>Tx1 resolves the party and <strong>fails fast</strong> on the step-up (read-only, so the
 * refusal writes nothing and a password-session attacker cannot spend tokenisation calls);
 * the exchange then runs <strong>holding no database connection</strong> (the `P1-TSK-026`
 * discipline); Tx2 <strong>re-checks the step-up authoritatively before any write</strong> —
 * the per-decision read at the write is the control, the Tx1 check the fail-fast — then
 * attaches, audits and announces, creating call only.
 *
 * <h2>The events are this surface's, in the act's transaction</h2>
 *
 * <p>{@code paymentmethods.PaymentMethodAttached}/{@code Detached} (plan §10) ride the outbox
 * in the transaction that commits the fact ({@code INV-EVT-01}), acting call only; payloads
 * are empty — the identifiers ride the envelope, and brand is not an enumerated name
 * ({@code INV-AUD-02}'s discipline at {@code EventPayload}).
 */
public final class PaymentMethodService {

    static final String ATTACHED_EVENT = "paymentmethods.PaymentMethodAttached";
    static final String DETACHED_EVENT = "paymentmethods.PaymentMethodDetached";
    static final String PRODUCER = "paymentmethods";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "payment_method";

    private final PaymentMethodStore<Connection> methods;
    private final ObjectProvider<TokenisationProvider> tokenisation;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final IdentityStore<Connection> identities;
    private final AuditWriter<Connection> auditWriter;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public PaymentMethodService(
            PaymentMethodStore<Connection> methods,
            ObjectProvider<TokenisationProvider> tokenisation,
            MfaEnrolmentStore<Connection> enrolments,
            IdentityStore<Connection> identities,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate paymentMethodTransactions,
            DataSource dataSource) {
        this.methods = Objects.requireNonNull(methods, "methods must not be null");
        this.tokenisation =
                Objects.requireNonNull(tokenisation, "tokenisation must not be null");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(
                        paymentMethodTransactions, "paymentMethodTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** The rendered instrument — display metadata by construction, never the token. */
    public record PaymentMethodView(
            String id,
            String brand,
            String displaySuffix,
            int expiryMonth,
            int expiryYear,
            String createdAt) {

        static PaymentMethodView of(PaymentMethod method) {
            return new PaymentMethodView(
                    method.id().value().toString(),
                    method.brand(),
                    method.displaySuffix(),
                    method.expiryMonth(),
                    method.expiryYear(),
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
                                PaymentMethod.attach(
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
                                attachment.method().id(),
                                attachment.method().status());
                    }
                    return PaymentMethodView.of(attachment.method());
                });
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
                        act(
                                unitOfWork,
                                PaymentmethodsAuditAction.PAYMENT_METHOD_DETACHED,
                                DETACHED_EVENT,
                                method,
                                PaymentMethodStatus.DETACHED);
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
     * .require()}) and the event, in the act's transaction ({@code INV-EVT-01}). Identifiers
     * and enumerated names only — never the token, never display metadata
     * ({@code INV-AUD-02}).
     */
    private void act(
            Connection unitOfWork,
            PaymentmethodsAuditAction action,
            String eventType,
            PaymentMethodId method,
            PaymentMethodStatus status) {
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
                        Optional.empty(),
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
                // Enumerated names only (INV-AUD-02 at EventPayload): the machine's
                // status - and never the token, never display metadata, since brand is
                // provider text rather than an enumerated name.
                EventPayload.of().with("status", status.name()).toBytes(),
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
