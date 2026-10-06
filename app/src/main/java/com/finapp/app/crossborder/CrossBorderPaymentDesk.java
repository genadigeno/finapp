package com.finapp.app.crossborder;

import com.finapp.crossborder.CrossBorderExecution;
import com.finapp.crossborder.CrossBorderFx;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.crossborder.PaymentAuthorization;
import com.finapp.crossborder.PaymentStore;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.fx.FxErrorCode;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The boundary half of the cross-border payment door (`P9-TSK-019`, PHASE_9_PLAN.md section 12.8): the step-up
 * gate, the claim and Tx1 (the authorization, one commit), then the wire holding no connection - <strong>the
 * cover first</strong>, then the corridor - and Tx2 recording the corridor's answer and the claim's {@code 202}.
 * A refusal commits only the claim's outcome. A crashed flight is taken over by the same key, converging on the
 * committed payment and re-sending the same end-to-end reference.
 */
public final class CrossBorderPaymentDesk {

    static final String SCOPE = "crossborder.payment:";
    public static final String PAYMENT_METER = "finapp.crossborder.payment";

    private final PaymentAuthorization authorization;
    private final CrossBorderFx fx;
    private final CrossBorderExecution execution;
    private final IdentityStore<Connection> identities;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final IdempotentExecutor executor;
    private final TransactionRunner transactions;
    private final Counter submitted;
    private final Clock clock;

    public CrossBorderPaymentDesk(
            PaymentAuthorization authorization,
            CrossBorderFx fx,
            CrossBorderExecution execution,
            IdentityStore<Connection> identities,
            MfaEnrolmentStore<Connection> enrolments,
            IdempotentExecutor executor,
            TransactionRunner transactions,
            MeterRegistry meters,
            Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.fx = Objects.requireNonNull(fx, "fx must not be null");
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.submitted = Counter.builder(PAYMENT_METER).tag("outcome", "submitted")
                .description("Cross-border payments authorized - held and dispatched. A count, never an amount")
                .register(Objects.requireNonNull(meters, "meters must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** A payment as its owner sees it - the status shaped. */
    public record CrossBorderPaymentView(String paymentId, String status, String quoteId, String beneficiaryId, String corridor) {}

    private record Refusal(String vocabulary, String code) {}

    public CrossBorderPaymentView authorize(
            Session current, String idempotencyKey, CrossBorderPaymentController.CrossBorderPaymentRequest body) {
        Objects.requireNonNull(current, "current must not be null");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        UUID quoteId;
        try {
            quoteId = UUID.fromString(body.quoteId());
        } catch (IllegalArgumentException malformed) {
            throw refused(new Refusal("XB", CrossborderErrorCode.OFFER_NOT_FOUND.name()));
        }
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        String dispatchKey = key.scope() + "|" + key.key();

        record Begun(IdempotentExecutor.BeginOutcome outcome, PaymentAuthorization.Authorized authorized, Refusal refusal) {}
        Begun begun = transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            // The step-up gate, before any write: the ApiException aborts the transaction.
            requireConditionalAssurance(unitOfWork, current);
            RequestFingerprint fingerprint = RequestFingerprint.sha256(
                    ("crossborder.payment|" + party + "|" + quoteId).getBytes(StandardCharsets.UTF_8));
            AtomicReference<PaymentAuthorization.Authorized> authorized = new AtomicReference<>();
            AtomicReference<Refusal> refused = new AtomicReference<>();
            IdempotentExecutor.BeginOutcome outcome = executor.begin(unitOfWork, key, fingerprint, claimed -> {
                // A refusal can follow writes - the quote accepted before routing refuses, the cover born before
                // the hold finds no funds - so the authorization runs under a savepoint and a refusal rolls back
                // to it: only the claim's outcome commits.
                Savepoint before = savepoint(claimed);
                try {
                    authorized.set(authorization.authorize(claimed, dispatchKey, party, quoteId, actor, now(), correlation));
                } catch (PaymentAuthorization.PaymentRefused refusal) {
                    refused.set(new Refusal("XB", refusal.code().name()));
                } catch (CrossBorderFx.FxRefused refusal) {
                    refused.set(new Refusal("FX", refusal.code()));
                } catch (CrossBorderExecution.ExecutionRefused refusal) {
                    refused.set(new Refusal(refusal.vocabulary(), refusal.code()));
                }
                if (refused.get() != null) {
                    rollbackTo(claimed, before);
                }
                return new byte[] {1};
            });
            if (refused.get() != null) {
                executor.complete(unitOfWork, key, false, failure(refused.get()));
            }
            return new Begun(outcome, authorized.get(), refused.get());
        });
        if (begun.outcome().replay().isPresent()) {
            return replayed(current, begun.outcome().replay().get());
        }
        if (begun.refusal() != null) {
            throw refused(begun.refusal());
        }
        PaymentAuthorization.Authorized authorized = begun.authorized();
        if (!authorized.takenOver()) {
            submitted.increment();
        }

        // The wire, holding no connection: the cover first - handed to fx's nudge, which sends it as the
        // platform off this thread; the cover sweep is the guarantee whatever the corridor does.
        fx.dispatchCover(authorized.payment().cover());
        if (authorized.takenOver()) {
            transactions.inTransaction(unitOfWork -> {
                execution.renewPermit(unitOfWork, authorized.dispatched());
                return null;
            });
        }
        CrossBorderExecution.SendOutcome sent = execution.send(authorized.dispatched());
        transactions.inTransaction(unitOfWork -> {
            execution.recordSend(unitOfWork, authorized.dispatched(), sent);
            executor.complete(unitOfWork, key, true, StoredResponse.of(
                    ("OK|" + authorized.payment().id()).getBytes(StandardCharsets.UTF_8), "text/plain"));
            return null;
        });
        return view(authorized.payment());
    }

    public CrossBorderPaymentView read(Session current, String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        return transactions.inTransaction(unitOfWork -> authorization.read(unitOfWork, id, partyOf(unitOfWork, current)))
                .map(CrossBorderPaymentDesk::view)
                .orElseThrow(CrossBorderPaymentDesk::notFound);
    }

    // ------------------------------------------------------------------ plumbing

    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A cross-border payment from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    private static Savepoint savepoint(Connection unitOfWork) {
        try {
            return unitOfWork.setSavepoint();
        } catch (SQLException failure) {
            throw new IllegalStateException("the authorization's savepoint could not be set", failure);
        }
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint savepoint) {
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException failure) {
            throw new IllegalStateException("a refused authorization could not be rolled back to its savepoint", failure);
        }
    }

    private CrossBorderPaymentView replayed(Session current, StoredResponse response) {
        String[] fields = new String(response.body(), StandardCharsets.UTF_8).split("\\|", -1);
        if (fields[0].equals("ERR")) {
            throw refused(new Refusal(fields[1], fields[2]));
        }
        return read(current, fields[1]);
    }

    private static StoredResponse failure(Refusal refusal) {
        return StoredResponse.of(("ERR|" + refusal.vocabulary() + "|" + refusal.code()).getBytes(StandardCharsets.UTF_8),
                "text/plain");
    }

    private static ApiException refused(Refusal refusal) {
        return switch (refusal.vocabulary()) {
            case "FX" -> new ApiException(FxErrorCode.valueOf(refusal.code()), "The cross-border payment was refused");
            case "PAYMENTS" -> new ApiException(PaymentsErrorCode.valueOf(refusal.code()), "The cross-border payment was refused");
            default -> new ApiException(CrossborderErrorCode.valueOf(refusal.code()), "The cross-border payment was refused");
        };
    }

    private static ApiException notFound() {
        return new ApiException(CrossborderErrorCode.PAYMENT_NOT_FOUND, "No payment matches the requested identifier",
                "no such payment.");
    }

    static CrossBorderPaymentView view(PaymentStore.Row payment) {
        return new CrossBorderPaymentView(payment.id().toString(), payment.status().shaped(), payment.quote().toString(),
                payment.beneficiary().value().toString(), payment.corridor().code());
    }

    /** {@code Session -> Identity -> Party}: the {@code ProfileService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a cross-border payment runs inside a correlation scope"))
                .correlationId();
    }
}
