package com.finapp.app.checkout;

import com.finapp.app.payments.PaymentService;
import com.finapp.checkout.CheckoutErrorCode;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionExpiredException;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.FeeScheduleVersionId;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.merchant.PaymentFeePin;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentParticipants;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checkout surface behind the controllers (`P6-TSK-007`) — parse and refuse at the
 * boundary, one transaction per command, domain refusals translated to the registered codes.
 *
 * <h2>What it orchestrates, and in what order</h2>
 *
 * <p><strong>Create</strong> is one transaction. <strong>Confirm</strong> is one transaction
 * for the intent, the fee pin and the session's move — then the provider chain
 * <em>outside</em> it, because no transaction spans a provider call (ADR-0046) and the
 * platform already has exactly one implementation of "confirm a payment and chain its
 * capture": {@link PaymentService#confirm}. Reusing it rather than re-choreographing means a
 * checkout payment and a wallet top-up cannot drift in how they handle an unknown outcome.
 *
 * <h2>A retried confirm CONVERGES rather than refusing</h2>
 *
 * <p>A session already {@code PAYMENT_PENDING} with an intent is not an error — it is the
 * customer clicking twice, or a client retrying a lost response. The confirm continues on the
 * intent the first call opened, which also finishes a capture stranded by a crash
 * ({@code PaymentController}'s recorded shape: <em>the machine is the idempotency</em>).
 *
 * <p><strong>A session that is already PAID converges too</strong>, and that is the case the
 * customer actually hits. The capture is chained synchronously, so the first confirm normally
 * returns {@code COMPLETED} — which would leave every retry of a <em>successful purchase</em>
 * answering {@code 409}, to a customer who has no other way to learn the outcome: the checkout
 * read surface is the merchant's, key-authenticated, and the customer holds only a token. A
 * lost response is the ordinary failure of a payment flow, not an exceptional one, so the
 * retry renders the session and writes nothing. The payer is re-established first, against the
 * intent the session names, so this tells a second holder of the token nothing — it is the
 * same one {@code 404} an unknown session gets.
 *
 * <p>Only a session that left the flow <em>without</em> being paid — abandoned, or expired
 * with nothing captured — refuses, because there is no outcome to converge on.
 */
public class CheckoutService {

    /**
     * A session as its merchant sees it. Never the token, never after creation.
     *
     * <p><strong>{@code checkoutId}, not {@code sessionId}</strong>, and a build rule is why:
     * {@code sessionid} is in {@code NoUnwrappedSecretRulesTest}'s secret vocabulary because
     * one meaning of it — an <em>identity</em> session — is credential-adjacent (ADR-0019,
     * {@code P1-TSK-009}). A checkout session's identifier is not that thing, and neither a
     * reader nor a serialiser can tell the two apart from the name. Exempting four members
     * would have reintroduced the hole for the sensitive meaning; renaming costs nothing and
     * says what this actually is.
     */
    public record SessionView(
            String checkoutId,
            String merchantId,
            String amount,
            String currency,
            String lineSummary,
            String status,
            String expiresAt,
            String paymentIntentId,
            String orderId) {}

    /** The creation's answer: the session, and the token exactly once. */
    public record CreatedSessionView(
            String checkoutId,
            String status,
            String expiresAt,
            String sessionToken,
            boolean alreadyCreated) {}

    private final CheckoutSessions checkout;
    private final MerchantSettlement settlement;

    /**
     * Stateless, like every JDBC store on this platform — held as a field rather than built
     * twice, because the branch that creates an intent and the branch that re-establishes its
     * payer must be reading the same table through the same predicate.
     */
    private final com.finapp.payments.PaymentIntentStore<Connection> intents =
            new com.finapp.payments.JdbcPaymentIntentStore();

    private final PaymentService payments;
    private final PaymentParticipants<Connection> instruments;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final IdentityStore<Connection> identities;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public CheckoutService(
            CheckoutSessions checkout,
            MerchantSettlement settlement,
            PaymentService payments,
            PaymentParticipants<Connection> instruments,
            LedgerAccountStore<Connection> ledgerAccounts,
            IdentityStore<Connection> identities,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.checkout = Objects.requireNonNull(checkout, "checkout must not be null");
        this.settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.instruments = Objects.requireNonNull(instruments, "instruments must not be null");
        this.ledgerAccounts =
                Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    // ----------------------------------------------------------------- merchant surface

    /** Opens an offer. The token is in this response and in no other, ever. */
    public CreatedSessionView create(
            AuthenticatedMerchant merchant, CreateSessionRequest body, String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        Money amount = Money.ofMinorUnits(body.amountMinor(), CurrencyCode.of(body.currency()));
        CheckoutSessions.OpenSessionCommand command =
                new CheckoutSessions.OpenSessionCommand(
                        idempotencyKey, merchant.merchantId(), amount, body.lineSummary());
        try {
            CheckoutSessions.OpenedSession opened =
                    inOneTransaction(unitOfWork -> checkout.open(unitOfWork, command));
            CheckoutSession session =
                    inOneTransaction(
                            unitOfWork ->
                                    checkout
                                            .ownedBy(
                                                    unitOfWork,
                                                    merchant.merchantId(),
                                                    opened.id())
                                            .orElseThrow(CheckoutService::sessionNotFound));
            return new CreatedSessionView(
                    session.id().value().toString(),
                    session.status().name(),
                    session.expiresAt().toString(),
                    // THE ONE UNWRAP ON THIS PATH, at the one boundary that must transmit the
                    // freshly minted token - and empty on a replay, because the claim records
                    // the session id alone (CheckoutSessions.open's recorded reasoning).
                    opened.token().map(token -> token.expose()).orElse(null),
                    opened.replayed());
        } catch (MerchantNotTradingException refused) {
            throw new ApiException(
                    CheckoutErrorCode.NOT_TRADING,
                    "A checkout session was refused by the merchant's standing",
                    "this merchant cannot open new checkout sessions.");
        } catch (MerchantNotPriceableException refused) {
            throw new ApiException(
                    CheckoutErrorCode.NOT_PRICEABLE,
                    "A checkout session was refused because the merchant has no fee schedule",
                    "this merchant has no fee schedule, so a checkout cannot be priced.");
        }
    }

    /** The merchant's own session. Unknown, malformed and another's are one 404. */
    public SessionView view(AuthenticatedMerchant merchant, String rawId) {
        CheckoutSessionId id = parsedOrAbsent(rawId);
        return inOneTransaction(
                unitOfWork ->
                        checkout
                                .ownedBy(unitOfWork, merchant.merchantId(), id)
                                .map(session -> render(unitOfWork, session))
                                .orElseThrow(CheckoutService::sessionNotFound));
    }

    // ----------------------------------------------------------------- customer surface

    /**
     * The customer commits: the intent is opened, the fee pinned, the session moved — then the
     * payment is confirmed and its capture chained.
     *
     * <p>The customer must hold <strong>both</strong> credentials: a session of their own, and
     * the checkout token. Neither alone is enough.
     *
     * <p><strong>Every state that can be converged on, is</strong> (see the class javadoc): a
     * session mid-payment continues on the intent the first call opened, and a session already
     * paid renders its outcome to the payer without confirming anything. Only a session that
     * left the flow unpaid refuses.
     */
    public SessionView confirm(Session current, ConfirmSessionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        // THE ONE UNWRAP ON THE INBOUND PATH: the presented value goes straight into the
        // token type, which hashes it to look the session up and never surrenders it again.
        CheckoutSessionToken presented = CheckoutSessionToken.of(body.sessionToken().expose());

        PaymentIntentId intent;
        CheckoutSessionId sessionId;
        boolean alreadyPaid;
        try {
            Opened opened =
                    inOneTransaction(unitOfWork -> openPayment(unitOfWork, current, presented, body));
            intent = opened.intent();
            sessionId = opened.session();
            alreadyPaid = opened.alreadyPaid();
        } catch (UnknownCheckoutSessionException unknown) {
            throw sessionNotFound();
        } catch (CheckoutSessionExpiredException expired) {
            throw new ApiException(
                    CheckoutErrorCode.SESSION_EXPIRED,
                    "A confirmation arrived after the offer's deadline",
                    "this checkout session has expired.");
        } catch (CheckoutSessionNotOpenException refused) {
            throw new ApiException(
                    CheckoutErrorCode.NOT_CONFIRMABLE,
                    "A confirmation was refused by the session's state (" + refused.status() + ")",
                    "this checkout session is " + refused.status()
                            + " and is not awaiting confirmation.");
        }

        // OUTSIDE a transaction: the command runs its own Tx1 / provider call / Tx2
        // choreography and must hold no connection during the call (ADR-0046). The capture is
        // chained by PaymentService exactly as it is for a wallet top-up - one implementation,
        // so the two cannot drift in how they treat an unknown outcome.
        if (!alreadyPaid) {
            payments.confirm(current, intent);
        }

        return inOneTransaction(
                unitOfWork ->
                        checkout
                                .ownedBySession(unitOfWork, sessionId)
                                .map(session -> render(unitOfWork, session))
                                .orElseThrow(CheckoutService::sessionNotFound));
    }

    /**
     * What the confirming transaction yields across the connectionless gap.
     *
     * @param alreadyPaid the session was already {@code COMPLETED} or {@code COMPLETED_LATE},
     *     so the payment succeeded and there is nothing to confirm or chain. Confirming it
     *     again would be refused by the intent's own machine ({@code payments.NotConfirmable})
     *     — correctly, since a succeeded payment cannot be confirmed — and answering the
     *     customer an error for a purchase that worked is what this flag prevents
     */
    private record Opened(
            CheckoutSessionId session, PaymentIntentId intent, boolean alreadyPaid) {}

    private Opened openPayment(
            Connection unitOfWork,
            Session current,
            CheckoutSessionToken presented,
            ConfirmSessionRequest body) {
        CheckoutSession session = checkout.byToken(unitOfWork, presented);

        // A RETRY CONVERGES rather than refusing: a session already PAYMENT_PENDING with an
        // intent is the customer clicking twice, or a client retrying a lost response. Chain
        // the payment on the intent the first call opened - which also finishes a capture
        // stranded by a crash (PaymentController's recorded shape).
        if (session.status() == CheckoutSessionStatus.PAYMENT_PENDING) {
            // Ownership is not checked here: PaymentConfirmation re-resolves the intent as the
            // caller's own and answers a stranger the payments surface's 404 (P5-TSK-011's
            // predicate). A second check would be a second place for it to be wrong.
            return new Opened(session.id(), intentOf(session), false);
        }
        if (session.status().isPaid()) {
            // THE RETRY OF A PURCHASE THAT WORKED. Nothing is confirmed and nothing chained:
            // the order is created in the capture's own transaction, so a paid session means
            // the money has landed. Ownership IS checked here, precisely because this branch
            // skips the payments surface that would otherwise be checking it.
            PaymentIntentId intent = intentOf(session);
            intents.findOwned(unitOfWork, intent, partyOf(unitOfWork, current))
                    .orElseThrow(UnknownCheckoutSessionException::new);
            return new Opened(session.id(), intent, true);
        }
        if (!session.acceptsNewWork()) {
            throw new CheckoutSessionNotOpenException(session.status());
        }
        if (session.hasExpired(clock)) {
            // The clock as well as the state: a sweeper one minute behind is not a minute in
            // which the platform honours a dead offer (ADR-0053 §5).
            throw new CheckoutSessionExpiredException();
        }

        UUID payerParty = partyOf(unitOfWork, current);
        PaymentCreation creation =
                new PaymentCreation(
                        executor,
                        // THE SECOND WIRING: this payment credits the MERCHANT's payable, read
                        // authoritatively from the merchant the SESSION names (ADR-0050 §6).
                        new CheckoutPaymentParticipants(
                                ledgerAccounts,
                                instruments,
                                session.merchantRef(),
                                session.amount().currency()),
                        intents,
                        audit,
                        outbox,
                        ids,
                        clock);
        PaymentCreation.CreationResult created =
                creation.create(
                        unitOfWork,
                        new PaymentCreation.CreatePaymentCommand(
                                payerParty,
                                body.paymentMethodId(),
                                session.amount(),
                                // The session's OWN identifier as the key: deterministic, so a
                                // retry that reaches here converges on one intent rather than
                                // opening a second (INV-IDEM-01 through a derived key).
                                "checkout:" + session.id().value()));

        // THE PRICE PINNED, in this same transaction (INV-MER-03, INV-HIST-04): the version
        // the SESSION was priced under, carried onto the payment, so a schedule version
        // created while the customer is at the payment page reprices nothing.
        settlement.pin(
                unitOfWork,
                new PaymentFeePin(
                        created.intent().value(),
                        com.finapp.merchant.MerchantId.of(session.merchantRef()),
                        FeeScheduleVersionId.of(session.feeScheduleVersionRef()),
                        session.amount(),
                        Instant.now(clock),
                        current.identityId().value().toString()));

        if (!checkout.paymentOpened(unitOfWork, session, created.intent().value())) {
            // An expiry sweeper or a concurrent confirmation moved the row first. The whole
            // transaction rolls back, so nothing was pinned and no intent exists.
            throw new CheckoutSessionNotOpenException(CheckoutSessionStatus.EXPIRED);
        }
        return new Opened(session.id(), created.intent(), false);
    }

    /** The intent a session past {@code OPEN} must carry; its absence is a broken database. */
    private static PaymentIntentId intentOf(CheckoutSession session) {
        return PaymentIntentId.of(
                session.paymentIntentRef()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a " + session.status() + " session always"
                                                        + " carries its intent")));
    }

    // -----------------------------------------------------------------

    private SessionView render(Connection unitOfWork, CheckoutSession session) {
        return new SessionView(
                session.id().value().toString(),
                session.merchantRef().toString(),
                session.amount().toBigDecimal().toPlainString(),
                session.amount().currency().code(),
                session.lineSummary(),
                session.status().name(),
                session.expiresAt().toString(),
                session.paymentIntentRef().map(UUID::toString).orElse(null),
                checkout
                        .orderOf(unitOfWork, session.id())
                        .map(order -> order.id().value().toString())
                        .orElse(null));
    }

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

    /** Malformed equals absent — the one 404. */
    private static CheckoutSessionId parsedOrAbsent(String rawId) {
        try {
            return CheckoutSessionId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw sessionNotFound();
        }
    }

    private static ApiException sessionNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A checkout session read found nothing at the identifier",
                "the requested resource does not exist.");
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
