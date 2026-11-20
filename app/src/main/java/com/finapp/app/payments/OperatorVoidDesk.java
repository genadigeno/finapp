package com.finapp.app.payments;

import com.finapp.payments.PaymentIntentId;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator void's key (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decision 2 - the void stays one person's,
 * since it moves no captured money, and gains a key). The effect is the void's own: two transactions and the provider call
 * between them, converging by its machine - the conditional out of {@code AUTHORIZED} admits one dispatch, and a second call
 * converges on the truth with nothing sent. The key adds what the machine cannot: <strong>a different request under the same
 * key is {@code 409}</strong> ({@code INV-IDEM-03}), judged before anything is dispatched, and a retry of the same request is
 * answered by the converging void, never a second dispatch.
 *
 * <p>Why not the executor's begin/complete around the void: the void's dispatch transaction is the payments command's own
 * (`PaymentVoid`), and the claim must not be held open across the provider call. So the key is read before the act and
 * recorded after it; a request whose key record is lost between the two is re-driven by the machine, which converges.
 */
@RequiredArgsConstructor
public final class OperatorVoidDesk {

    static final String SCOPE = "payment.void:";

    @NonNull private final PaymentService payments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final IdempotencyRecordStore<Connection> records;
    @NonNull private final TransactionTemplate paymentTransactions;
    @NonNull private final DataSource dataSource;

    /** The operator's keyed void of an uncaptured authorization. */
    public PaymentService.PaymentView voidPayment(String idempotencyKey, PaymentIntentId intentId, String reason) {
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("VOID|" + intentId.value() + "|" + digest(reason))
                .getBytes(StandardCharsets.UTF_8));
        Optional<IdempotencyRecord> prior = inOneTransaction(unitOfWork -> records.find(unitOfWork, key));
        if (prior.isPresent() && !fingerprint.matches(prior.get().fingerprint())) {
            throw new IdempotencyConflictException(key);
        }
        // A first request voids; a retry of it converges on the machine's truth - the same call, no second dispatch.
        PaymentService.PaymentView voided = payments.operatorVoid(intentId, reason);
        if (prior.isEmpty()) {
            inOneTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint,
                    work -> CommandResult.succeeded(StoredResponse.of(intentId.value().toString()
                            .getBytes(StandardCharsets.UTF_8), "text/plain"))));
        }
        return voided;
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return paymentTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }
}
