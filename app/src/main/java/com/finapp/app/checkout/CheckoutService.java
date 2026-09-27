package com.finapp.app.checkout;

import com.finapp.app.payments.PaymentService;
import com.finapp.app.telemetry.CheckoutMeters;
import com.finapp.checkout.CheckoutErrorCode;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionExpiredException;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.IllegalCheckoutSessionTransitionException;
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
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.payments.UnknownPaymentInstrumentException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.io.Serial;
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
 *
 * <p><strong>A confirmation that LOSES the open converges as well</strong> (the Phase 6 → 7
 * transition). Two confirmations that both read the session {@code OPEN} meet at the claim;
 * the second replays the first's intent and then finds the session already moved. Until the
 * transition it answered {@code checkout.NotConfirmable} naming {@code EXPIRED} whatever had
 * moved it - a double-click told the customer their offer was dead while their payment went
 * through. Now the losing transaction rolls back and the call reads the session once more,
 * answering from the state the winner left: nothing moves a session back to {@code OPEN}, so
 * the second read never reaches the open again.
 */
public class CheckoutService {

    /**
     * The claim scope of the payment a confirmation opens (the Phase 6 → 7 transition).
     *
     * <p><strong>Its own scope, never {@code payment.create}</strong>. The key is derived -
     * {@code checkout:<checkoutId>} - and a derived key is predictable: in the public command's
     * scope any customer could send {@code POST /v1/payments} with that key first, their own
     * top-up claiming it, and the payer's confirmation would then meet a fingerprint it could
     * never match and be refused for as long as the session lived. In a scope that only this
     * class claims, and with a key no client chooses, there is nothing to squat.
     *
     * <p>Moving the scope strands no claim: a confirmation whose transaction committed left the
     * session {@code PAYMENT_PENDING} or paid, and a retry of either converges before it claims;
     * one that rolled back left no claim at all.
     */
    static final String PAYMENT_IDEMPOTENCY_SCOPE = "checkout.payment";

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
            String orderId,
            /**
             * The payer's authorization handle (`P7-TSK-009`, ADR-0062 §5): present exactly
             * while this session's pay-by-bank attempt awaits the payer — the capability
             * URL their client follows to their PSP. Rendered only to the holder of the
             * session's own credentials; additive on the contract; the render is a
             * registered {@code expose()} site.
             */
            String authorizationHandle) {

        /**
         * The identifier and the state only - never the amount, never the line summary
         * ({@code INV-AUD-02}), the aggregate's own rule for its own rendering. The line summary
         * is {@code RESTRICTED-PII}: what one person bought.
         */
        @Override
        public String toString() {
            return "SessionView[" + checkoutId + ", " + status + "]";
        }
    }

    /** The creation's answer: the session, and the token exactly once. */
    public record CreatedSessionView(
            String checkoutId,
            String status,
            String expiresAt,
            String sessionToken,
            boolean alreadyCreated) {

        /**
         * Masked: the token is the credential that pays this session. The three guards that
         * exempt this field let it be SERIALISED, never logged, and their exemption rested on
         * an override this record did not have until the Phase 6 → 7 transition.
         */
        @Override
        public String toString() {
            return "CreatedSessionView[" + checkoutId + ", " + status + ", alreadyCreated="
                    + alreadyCreated + ", sessionToken=" + Sensitive.MASK + "]";
        }
    }

    private final CheckoutSessions checkout;
    private final CheckoutMeters meters;
    private final MerchantSettlement settlement;

    /**
     * Stateless, like every JDBC store on this platform — held as a field rather than built
     * twice, because the branch that creates an intent and the branch that re-establishes its
     * payer must be reading the same table through the same predicate.
     */
    private final com.finapp.payments.PaymentIntentStore<Connection> intents =
            new com.finapp.payments.JdbcPaymentIntentStore();

    /** Stateless, the same reasoning: the payer's rendering reads the attempt's handle. */
    private final com.finapp.payments.PaymentAttemptStore<Connection> attempts =
            new com.finapp.payments.JdbcPaymentAttemptStore();

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

    /** The wallet instrument's assurance read (P7-TSK-011): the withdrawal's step-up policy
     * at this door - a factor enrolled means a MULTI_FACTOR session pays from the wallet. */
    private final com.finapp.identity.MfaEnrolmentStore<Connection> enrolments;

    public CheckoutService(
            CheckoutSessions checkout,
            CheckoutMeters meters,
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
            DataSource dataSource,
            com.finapp.identity.MfaEnrolmentStore<Connection> enrolments) {
        this.checkout = Objects.requireNonNull(checkout, "checkout must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
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
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
    }

    // ----------------------------------------------------------------- merchant surface

    /** Opens an offer. The token is in this response and in no other, ever. */
    public CreatedSessionView create(
            AuthenticatedMerchant merchant, CreateSessionRequest body, String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        Money amount = Money.ofMinorUnits(body.amountMinor(), CurrencyCode.of(body.currency()));
        CheckoutSessions.OpenSessionCommand command =
                new CheckoutSessions.OpenSessionCommand(
                        idempotencyKey,
                        merchant.merchantId(),
                        merchant.keyId(),
                        amount,
                        body.lineSummary());
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
                    "A checkout session was refused because no fee schedule prices it",
                    "this merchant has no fee schedule for this currency, so a checkout cannot"
                            + " be priced.");
        } catch (com.finapp.merchant.SaleBelowFeeException refused) {
            throw saleBelowFee();
        }
    }

    /** `P6-TST-001`, ADR-0058: refused at the price, at creation or when the fee is pinned. */
    private static ApiException saleBelowFee() {
        return new ApiException(
                CheckoutErrorCode.SALE_BELOW_FEE,
                "A checkout was refused because its fee meets or exceeds its amount",
                "this amount does not cover the merchant's fee, so it cannot be sold.");
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

    /**
     * The merchant withdraws its own offer (`P6-TSK-008`): {@code OPEN → ABANDONED}, reasoned.
     *
     * <p>Answers the session as it now stands rather than a bare {@code 204}, because the
     * merchant's next question is always "so what happened to it" and a body that says
     * {@code ABANDONED} answers it without a second round trip.
     *
     * <p>The meter is incremented <strong>after the transaction returned</strong> and only when
     * this call's own conditional fired — so a retried abandonment, which converges silently,
     * is not a second ending.
     */
    public SessionView abandon(
            AuthenticatedMerchant merchant, String rawId, AbandonSessionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        CheckoutSessionId id = parsedOrAbsent(rawId);
        Abandonment outcome;
        try {
            outcome =
                    inOneTransaction(
                            unitOfWork -> {
                                boolean acted =
                                        checkout.abandon(
                                                unitOfWork,
                                                merchant.merchantId(),
                                                merchant.keyId(),
                                                id,
                                                body.reason());
                                return new Abandonment(
                                        acted,
                                        checkout
                                                .ownedBy(unitOfWork, merchant.merchantId(), id)
                                                .map(session -> render(unitOfWork, session))
                                                .orElseThrow(CheckoutService::sessionNotFound));
                            });
        } catch (UnknownCheckoutSessionException unknown) {
            throw sessionNotFound();
        } catch (IllegalCheckoutSessionTransitionException refused) {
            throw new ApiException(
                    CheckoutErrorCode.NOT_ABANDONABLE,
                    "A withdrawal was refused by the session's state (" + refused.from() + ")",
                    "this checkout session is " + refused.from()
                            + " and cannot be withdrawn.");
        }
        if (outcome.acted()) {
            meters.session(CheckoutMeters.Outcome.ABANDONED);
        }
        return outcome.view();
    }

    /** Whether this call ended the offer, and the offer as it now stands. */
    private record Abandonment(boolean acted, SessionView view) {}

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
        // THE INSTRUMENT CHOICE, exactly one (P7-TSK-011): a registered method, or the
        // payer's own wallet - which the platform RESOLVES, never a named account. A body
        // saying both or neither is malformed, refused before anything is read.
        boolean fromWallet = "WALLET".equals(body.instrument());
        if (body.instrument() != null && !fromWallet) {
            throw new ApiException(
                    com.finapp.platform.api.PlatformErrorCode.VALIDATION_FAILED,
                    "A confirmation named an unknown instrument choice",
                    "instrument must be \"WALLET\" or absent.");
        }
        if (fromWallet == (body.paymentMethodId() != null)) {
            throw new ApiException(
                    com.finapp.platform.api.PlatformErrorCode.VALIDATION_FAILED,
                    "A confirmation names exactly one instrument",
                    "send paymentMethodId, or instrument=\"WALLET\" - not both, not neither.");
        }
        // THE ONE UNWRAP ON THE INBOUND PATH: the presented value goes straight into the
        // token type, which hashes it to look the session up and never surrenders it again.
        CheckoutSessionToken presented = CheckoutSessionToken.of(body.sessionToken().expose());

        PaymentIntentId intent;
        CheckoutSessionId sessionId;
        boolean alreadyPaid;
        try {
            Opened opened = opened(current, presented, body);
            intent = opened.intent();
            sessionId = opened.session();
            alreadyPaid = opened.alreadyPaid();
        } catch (UnknownCheckoutSessionException unknown) {
            throw sessionNotFound();
        } catch (UnknownPaymentInstrumentException foreign) {
            // The create door's own refusal, answered as that door answers it (the Phase 6 -> 7
            // transition): an instrument that is unknown, detached or somebody else's writes
            // nothing and spends no key. It was a 500. On the METHOD path the wallet and
            // currency refusals are not answered, deliberately: the participants resolve the
            // payable in the offer's own currency, which creation priced, so either one is a
            // broken database. On the WALLET path (P7-TSK-011) both are the payer's own
            // conditions and are answered below.
            throw new ApiException(
                    PaymentsErrorCode.UNKNOWN_INSTRUMENT,
                    "A checkout confirmation named an instrument that is not the payer's");
        } catch (MerchantNotTradingException refused) {
            throw new ApiException(
                    CheckoutErrorCode.NOT_TRADING,
                    "A confirmation was refused by the merchant's standing",
                    "this merchant is not trading, so this checkout cannot be paid.");
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
        } catch (com.finapp.merchant.SaleBelowFeeException refused) {
            // Reachable only for a session opened before the rule existed: the pin re-asserts
            // it, and its transaction rolls back with the intent it would have priced.
            throw saleBelowFee();
        } catch (com.finapp.payments.NoWalletForPaymentException noWallet) {
            // ON THE WALLET PATH ONLY (P7-TSK-011) this is the payer's own condition - no
            // open wallet to pay from - answered as the create door answers it. On the
            // method path the credit side is the payable in the offer's own currency, so
            // the refusal still means a broken database and stays loud (the comment above).
            if (!"WALLET".equals(body.instrument())) {
                throw noWallet;
            }
            throw new ApiException(
                    PaymentsErrorCode.NO_WALLET,
                    "A wallet payment was refused: the payer has no open wallet");
        } catch (com.finapp.payments.PaymentCurrencyMismatchException mismatched) {
            // Likewise the payer's own condition on the wallet path: a wallet in another
            // currency cannot pay this offer (FX is no part of this flow) - nothing written.
            if (!"WALLET".equals(body.instrument())) {
                throw mismatched;
            }
            throw new ApiException(
                    PaymentsErrorCode.CURRENCY_MISMATCH,
                    "A wallet payment was refused: the offer is not in the wallet's currency");
        }

        // OUTSIDE a transaction: the command runs its own Tx1 / provider call / Tx2
        // choreography and must hold no connection during the call (ADR-0046). The capture is
        // chained by PaymentService exactly as it is for a wallet top-up - one implementation,
        // so the two cannot drift in how they treat an unknown outcome.
        if (!alreadyPaid) {
            try {
                payments.confirm(current, intent);
            } catch (ApiException refused) {
                if (refused.errorCode() == PlatformErrorCode.NOT_FOUND) {
                    // THE CALLER IS NOT THE PAYER (the Phase 6 -> 7 transition): a second holder
                    // of the token on a session mid-payment. The payments surface answers its
                    // own 404, and its words - "no such payment" - told that holder the token
                    // was live and the session being paid. The session's one 404 tells nothing.
                    throw sessionNotFound();
                }
                if (refused.errorCode() != PaymentsErrorCode.NOT_CONFIRMABLE
                        || !paidMeanwhile(sessionId)) {
                    throw refused;
                }
                // The purchase worked between this call's read and its confirm - a concurrent
                // confirmation's capture landed. NotConfirmable reaches only the payer (the
                // payments surface resolves the intent as the caller's first), so rendering the
                // paid session tells nobody anything they do not own.
            }
        }

        return inOneTransaction(
                unitOfWork ->
                        checkout
                                .ownedBySession(unitOfWork, sessionId)
                                // The payer's own answer: the one rendering that may carry
                                // the authorization handle (P7-TSK-009).
                                .map(session -> renderForPayer(unitOfWork, session))
                                .orElseThrow(CheckoutService::sessionNotFound));
    }

    /**
     * The confirming transaction - run once more if it lost the open to a concurrent writer.
     *
     * <p>The loser's transaction rolls back whole: an intent it opened, the pin it wrote and a
     * key it claimed. The second run reads the row the winner left, and since nothing moves a
     * session back to {@code OPEN} it converges or refuses on that state without reaching the
     * open again - a second loss would be a broken machine, and surfaces as one.
     */
    private Opened opened(
            Session current, CheckoutSessionToken presented, ConfirmSessionRequest body) {
        try {
            return inOneTransaction(unitOfWork -> openPayment(unitOfWork, current, presented, body));
        } catch (LostTheOpen lost) {
            return inOneTransaction(unitOfWork -> openPayment(unitOfWork, current, presented, body));
        }
    }

    /** Whether the session a call is answering has been paid since it was read. */
    private boolean paidMeanwhile(CheckoutSessionId sessionId) {
        return inOneTransaction(
                unitOfWork ->
                        checkout.ownedBySession(unitOfWork, sessionId)
                                .map(session -> session.status().isPaid())
                                .orElse(false));
    }

    /**
     * A confirmation lost the open: another writer moved the session between this call's read
     * and its conditional transition. Thrown to roll the transaction back, never to a caller.
     */
    private static final class LostTheOpen extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        LostTheOpen() {
            super("the session left OPEN before this confirmation could move it", null, false, false);
        }
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
        if (session.status() == CheckoutSessionStatus.EXPIRED) {
            // THE STATE AND THE CLOCK MUST SAY THE SAME THING, and `P6-TSK-008`'s suite is
            // what noticed they did not. Before the sweeper arrives, a past-deadline session
            // still reads OPEN and the clock check below answers SessionExpired; after it
            // arrives, the row reads EXPIRED. Falling through to the generic refusal below
            // would tell the SAME customer about the SAME dead offer a different thing
            // depending on whether a background job had run yet - "too late" or "already
            // done". One question, one answer, whichever side of the tick it arrives on.
            throw new CheckoutSessionExpiredException();
        }
        if (!session.acceptsNewWork()) {
            // What is left is ABANDONED - the merchant withdrew the offer - which is exactly
            // what NotConfirmable's own contract says it covers.
            throw new CheckoutSessionNotOpenException(session.status());
        }
        if (session.hasExpired(clock)) {
            // The clock as well as the state: a sweeper one minute behind is not a minute in
            // which the platform honours a dead offer (ADR-0053 section 5).
            throw new CheckoutSessionExpiredException();
        }
        // The merchant's standing as well as the session's (P6-DOC-001): an offer made while
        // the merchant traded is not paid after it was suspended. Before anything is written,
        // so a refusal leaves no intent and no pin.
        checkout.requireTrading(unitOfWork, session.merchantRef());

        UUID payerParty = partyOf(unitOfWork, current);
        boolean fromWallet = "WALLET".equals(body.instrument());
        if (fromWallet) {
            // The wallet instrument's assurance, BEFORE anything is written (P7-TSK-011):
            // the withdrawal's step-up policy at this door - money leaves the payer's
            // wallet, so an MFA-enrolled identity pays with a MULTI_FACTOR session. The
            // refusal rolls this transaction back whole; the derived key converges the
            // retry after elevation.
            requireConditionalAssurance(unitOfWork, current);
        }
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
                        clock,
                        PAYMENT_IDEMPOTENCY_SCOPE);
        PaymentCreation.CreationResult created =
                creation.create(
                        unitOfWork,
                        // The session's OWN identifier as the key: deterministic, so a
                        // retry that reaches here converges on one intent rather than
                        // opening a second (INV-IDEM-01 through a derived key) - in its
                        // own scope, where no client can claim it first. The fingerprint
                        // carries the instrument choice, so a retry that switches
                        // instruments meets the conflict it should (INV-IDEM-03).
                        fromWallet
                                ? PaymentCreation.CreatePaymentCommand.fromWallet(
                                        payerParty,
                                        session.amount(),
                                        "checkout:" + session.id().value())
                                : new PaymentCreation.CreatePaymentCommand(
                                        payerParty,
                                        body.paymentMethodId(),
                                        session.amount(),
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
            // An expiry sweeper, the merchant's withdrawal or a concurrent confirmation moved
            // the row first. The whole transaction rolls back - nothing pinned, no intent, the
            // key unspent unless the winner spent it - and the caller reads the row again
            // rather than guessing which of the three it was.
            throw new LostTheOpen();
        }
        return new Opened(session.id(), created.intent(), false);
    }

    /**
     * The withdrawal's conditional assurance, at the wallet-payment door (`P7-TSK-011`):
     * an identity with a TOTP factor enrolled pays from the wallet only with a
     * {@code MULTI_FACTOR} session. Enrolment is the condition, not the request.
     */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor =
                enrolments
                        .findActive(
                                unitOfWork,
                                current.identityId(),
                                com.finapp.identity.MfaFactorType.TOTP)
                        .isPresent();
        if (hasFactor
                && !current.assurance()
                        .atLeast(com.finapp.identity.AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(
                    com.finapp.identity.IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A wallet payment from an MFA-enrolled identity requires a MULTI_FACTOR"
                            + " session");
        }
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

    /** The merchant's rendering: never the payer's authorization handle (`P7-TSK-009`). */
    private SessionView render(Connection unitOfWork, CheckoutSession session) {
        return render(unitOfWork, session, null);
    }

    /**
     * The PAYER's rendering (`P7-TSK-009`, ADR-0062 §5): the one checkout surface that may
     * carry the authorization handle — the payer holds both credentials and is the person
     * the capability URL exists for. A merchant's read never comes here: a handle in the
     * merchant's view is a merchant able to complete or observe the payer's flow, which is
     * the {@code InitiationAnswer} javadoc's exact warning. The registered {@code expose()}
     * site beside {@code PaymentService}'s.
     */
    private SessionView renderForPayer(Connection unitOfWork, CheckoutSession session) {
        String handle = null;
        if (session.status() == CheckoutSessionStatus.PAYMENT_PENDING) {
            handle =
                    session.paymentIntentRef()
                            .flatMap(
                                    intentRef ->
                                            attempts.findForIntent(
                                                    unitOfWork,
                                                    PaymentIntentId.of(intentRef)))
                            .filter(
                                    attempt ->
                                            attempt.status()
                                                    == com.finapp.payments.PaymentAttemptStatus
                                                            .AWAITING_PAYER)
                            .flatMap(com.finapp.payments.PaymentAttempt::authorizationHandle)
                            .map(Sensitive::expose)
                            .orElse(null);
        }
        return render(unitOfWork, session, handle);
    }

    private SessionView render(
            Connection unitOfWork, CheckoutSession session, String authorizationHandle) {
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
                        .orElse(null),
                authorizationHandle);
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
