package com.finapp.app.risk;

import com.finapp.merchant.IllegalMerchantTransitionException;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.MerchantActions;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Risk's {@link MerchantActions} port over merchant's existing suspension (`P13-TSK-017`; {@code PHASE_13_PLAN.md}
 * sections 7.3, 12.8; ADR-0112 decision 2; {@code INV-RSK-02}, {@code INV-IDEM-01}): {@link MerchantAdministration#suspend}
 * - merchant's own machine, its row's lock and its audit ({@code merchant.MerchantSuspended}, the acting principal the
 * platform under the action driver's system scope, the reason the action's code) - in <strong>one transaction of
 * merchant's own</strong>, never a risk one. The merchant row is taken {@code FOR UPDATE} first (merchant's own order:
 * its administration's first step is the same lock), so the answer is judged on the state the suspension will move -
 * {@code ACTIVE} → {@code DONE}, {@code SUSPENDED} → {@code ALREADY}, {@code CLOSED} or unknown → {@code REFUSED}. Risk
 * never reinstates a merchant.
 *
 * <p><strong>Keyed {@code risk.action:<id>}</strong> - merchant's administration converges on state but takes no key, so
 * this adapter is the keyed door: the platform's {@link IdempotentExecutor} records the answer under ({@value #SCOPE},
 * the action's key) in the same merchant transaction; a re-drive replays it word for word.
 */
public final class MerchantRiskActions implements MerchantActions {

    /** The idempotency scope of merchant's risk-action door. */
    static final String SCOPE = "merchant.risk-action";

    private final MerchantAdministration administration;
    private final MerchantStore<Connection> merchants;
    private final IdempotentExecutor executor;
    private final IdentityRiskActions.Transactions transactions;

    MerchantRiskActions(MerchantAdministration administration, MerchantStore<Connection> merchants,
            IdempotentExecutor executor, IdentityRiskActions.Transactions transactions) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.merchants = Objects.requireNonNull(merchants, "merchants");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public MerchantRiskActions(MerchantAdministration administration, MerchantStore<Connection> merchants,
            IdempotentExecutor executor, TransactionTemplate template, DataSource dataSource) {
        this(administration, merchants, executor, IdentityRiskActions.Transactions.over(template, dataSource));
    }

    @Override
    public ActionOutcome suspend(UUID merchantId, String idempotencyKey, String reason) {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reason, "reason");
        IdempotencyKey key = new IdempotencyKey(SCOPE, idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("SUSPEND_MERCHANT|" + merchantId + "|" + reason)
                .getBytes(StandardCharsets.UTF_8));
        Function<Connection, ActionOutcome> command = uow -> idOf(merchantId).map(id -> suspended(uow, id, reason))
                .orElse(ActionOutcome.REFUSED);
        IdempotentExecutor.ExecutionOutcome outcome = transactions.inOne(uow -> executor.execute(uow, key, fingerprint,
                work -> CommandResult.succeeded(StoredResponse.of(command.apply(work).name()
                        .getBytes(StandardCharsets.UTF_8), "text/plain"))));
        return ActionOutcome.valueOf(new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a risk action's merchant door stores its outcome")), StandardCharsets.UTF_8));
    }

    private ActionOutcome suspended(Connection unitOfWork, MerchantId id, String reason) {
        Optional<Merchant> locked = merchants.findByIdForUpdate(unitOfWork, id);
        if (locked.isEmpty() || locked.get().status() == MerchantStatus.CLOSED) {
            return ActionOutcome.REFUSED;
        }
        if (locked.get().status() == MerchantStatus.SUSPENDED) {
            return ActionOutcome.ALREADY;
        }
        try {
            administration.suspend(unitOfWork, id, reason);
            return ActionOutcome.DONE;
        } catch (UnknownMerchantException | IllegalMerchantTransitionException refused) {
            return ActionOutcome.REFUSED;
        }
    }

    private static Optional<MerchantId> idOf(UUID merchantId) {
        try {
            return Optional.of(MerchantId.of(merchantId));
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
    }
}
