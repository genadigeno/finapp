package com.finapp.app.risk;

import com.finapp.accounts.AccountRestrictions;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.RestrictionKind;
import com.finapp.party.CustomerId;
import com.finapp.party.PartyStore;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.risk.AccountActions;
import com.finapp.risk.ActionOutcome;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Risk's {@link AccountActions} port over accounts' restriction machine (`P17-TSK-028`, ADR-0153 decision 5):
 * {@link AccountRestrictions#applyForRisk} - the agreement's own lock, machine and audit ({@code accounts.AccountSuspended} /
 * {@code accounts.AccountReinstated}, the acting principal the platform under the action driver's system scope, the reason
 * the action's code) - in <strong>one transaction of accounts' own</strong>, never a risk one. Risk's case approval is already
 * four eyes ({@code RISK_CASE_APPROVE}, never the case's proposer), so no second proposal is made here.
 *
 * <p><strong>Keyed {@code risk.action:<id>}</strong> under {@value #SCOPE} - {@link MerchantRiskActions}' shape: a re-drive
 * replays the first answer word for word. And the proposal's one read, {@link #heldBy}: the agreement is the case party's own.
 */
public final class AccountRiskActions implements AccountActions {

    /** The idempotency scope of accounts' risk-action door. */
    static final String SCOPE = "accounts.risk-action";

    private final AccountRestrictions restrictions;
    private final CustomerAccountStore<Connection> accounts;
    private final PartyStore<Connection> parties;
    private final IdempotentExecutor executor;
    private final IdentityRiskActions.Transactions transactions;

    AccountRiskActions(AccountRestrictions restrictions, CustomerAccountStore<Connection> accounts,
            PartyStore<Connection> parties, IdempotentExecutor executor, IdentityRiskActions.Transactions transactions) {
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AccountRiskActions(AccountRestrictions restrictions, CustomerAccountStore<Connection> accounts,
            PartyStore<Connection> parties, IdempotentExecutor executor, TransactionTemplate template, DataSource dataSource) {
        this(restrictions, accounts, parties, executor, IdentityRiskActions.Transactions.over(template, dataSource));
    }

    @Override
    public ActionOutcome suspend(UUID accountId, String idempotencyKey, String reason) {
        return act(RestrictionKind.SUSPENSION, accountId, idempotencyKey, reason);
    }

    @Override
    public ActionOutcome reinstate(UUID accountId, String idempotencyKey, String reason) {
        return act(RestrictionKind.REINSTATEMENT, accountId, idempotencyKey, reason);
    }

    @Override
    public boolean heldBy(Connection unitOfWork, UUID partyId, UUID accountId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(accountId, "accountId");
        return idOf(accountId)
                .flatMap(id -> accounts.findById(unitOfWork, id))
                .flatMap(account -> parties.findCustomer(unitOfWork, CustomerId.of(account.customerId())))
                .map(customer -> customer.partyId().value().equals(partyId))
                .orElse(false);
    }

    private ActionOutcome act(RestrictionKind kind, UUID accountId, String idempotencyKey, String reason) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(reason, "reason");
        IdempotencyKey key = new IdempotencyKey(SCOPE, idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256((kind.name() + "|" + accountId + "|" + reason)
                .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = transactions.inOne(uow -> executor.execute(uow, key, fingerprint,
                work -> CommandResult.succeeded(StoredResponse.of(outcomeOf(work, kind, accountId, reason).name()
                        .getBytes(StandardCharsets.UTF_8), "text/plain"))));
        return ActionOutcome.valueOf(new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a risk action's account door stores its outcome")), StandardCharsets.UTF_8));
    }

    private ActionOutcome outcomeOf(Connection unitOfWork, RestrictionKind kind, UUID accountId, String reason) {
        return idOf(accountId).map(id -> switch (restrictions.applyForRisk(unitOfWork, id, kind, reason)) {
            case DONE -> ActionOutcome.DONE;
            case ALREADY -> ActionOutcome.ALREADY;
            case REFUSED -> ActionOutcome.REFUSED;
        }).orElse(ActionOutcome.REFUSED);
    }

    private static Optional<CustomerAccountId> idOf(UUID accountId) {
        try {
            return Optional.of(CustomerAccountId.of(accountId));
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
    }
}
