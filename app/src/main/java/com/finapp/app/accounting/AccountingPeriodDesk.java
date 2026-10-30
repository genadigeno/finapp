package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.PostingCutoff;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the accounting period doors (`P14-TSK-005`; {@code GlMappingDesk}'s shape): key every act per
 * principal ({@code accounting.period:<type>:<id>}), run each in one transaction under its span, and translate the
 * domain's refusals onto the error contract; a refused self-approval is recorded {@code FAILED} in a transaction of its
 * own. The machine, the order, the grace, the pin, the cutoff's move and the four eyes are the domain's and
 * {@code accounting V003}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class AccountingPeriodDesk {

    static final String SCOPE = "accounting.period:";

    static final String LOCK_SPAN = "accounting.period.lock";
    static final String UNLOCK_PROPOSE_SPAN = "accounting.period.unlock.propose";
    static final String UNLOCK_APPROVE_SPAN = "accounting.period.unlock.approve";
    static final String READ_SPAN = "accounting.period.read";

    /** The list's default and largest page - two years, and ten. */
    static final int DEFAULT_LIMIT = 24;
    static final int MAX_LIMIT = 120;

    @NonNull private final AccountingPeriods periods;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /** An act's receipt: the period as the act left it. */
    public record AccountingPeriodReceipt(
            String id, String period, String status, int revision, Integer mappingVersion, boolean unlockPending) {}

    /** A period as the operator reads it - no person, no reason. */
    public record AccountingPeriodView(
            String id,
            String period,
            String startDate,
            String endDate,
            String status,
            int revision,
            String lockableFrom,
            String openedAt,
            String lockedAt,
            Integer mappingVersion,
            boolean unlockPending,
            String unlockProposedAt) {}

    public AccountingPeriodReceipt lock(String idempotencyKey, String rawId, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(LOCK_SPAN, () -> keyed(actor, idempotencyKey, "lock|" + id.value() + "|" + reason,
                uow -> receipt(periods.lock(uow, id, actor, reason, correlation))));
    }

    public AccountingPeriodReceipt proposeUnlock(String idempotencyKey, String rawId, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(UNLOCK_PROPOSE_SPAN, () -> keyed(actor, idempotencyKey,
                "unlock-proposal|" + id.value() + "|" + reason,
                uow -> receipt(periods.proposeUnlock(uow, id, actor, reason, correlation))));
    }

    public AccountingPeriodReceipt approveUnlock(String idempotencyKey, String rawId, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(UNLOCK_APPROVE_SPAN, () -> keyed(actor, idempotencyKey,
                "unlock-approval|" + id.value() + "|" + reason,
                uow -> receipt(periods.approveUnlock(uow, id, actor, reason, correlation))));
    }

    /** The latest periods, newest first - a read, unkeyed, in one transaction; {@code limit} held to 1..120. */
    public List<AccountingPeriodView> list(Integer limit) {
        int page = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return flow.door(READ_SPAN, () -> transactions.inTransaction(
                uow -> periods.recent(uow, page).stream().map(this::view).toList()));
    }

    // ------------------------------------------------------------------ views

    AccountingPeriodView view(AccountingPeriodStore.PeriodRow row) {
        return new AccountingPeriodView(
                row.id().value().toString(),
                row.month().toString(),
                row.start().toString(),
                row.end().toString(),
                row.status().name(),
                row.revision(),
                periods.lockableFrom(row.month()).toString(),
                row.openedAt().toString(),
                row.lockedAt().map(Instant::toString).orElse(null),
                row.pinned().map(AccountingPeriodStore.Pin::version).orElse(null),
                row.unlockPending(),
                row.unlockPending() ? row.unlockProposedAt().map(Instant::toString).orElse(null) : null);
    }

    private static AccountingPeriodReceipt receipt(AccountingPeriods.Acted acted) {
        return new AccountingPeriodReceipt(acted.id().value().toString(), acted.month().toString(),
                acted.status().name(), acted.revision(), acted.pinnedVersion().orElse(null), acted.unlockPending());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private AccountingPeriodReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, AccountingPeriodReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    AccountingPeriodReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.period() + "|" + receipt.status() + "|"
                            + receipt.revision() + "|"
                            + (receipt.mappingVersion() == null ? "" : receipt.mappingVersion()) + "|"
                            + receipt.unlockPending();
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a period act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new AccountingPeriodReceipt(stored[0], stored[1], stored[2], Integer.parseInt(stored[3]),
                stored[4].isEmpty() ? null : Integer.valueOf(stored[4]), Boolean.parseBoolean(stored[5]));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back,
     * then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                periods.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. */
    static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.PeriodNotFound unknown) {
            throw notFound();
        } catch (AccountingPeriods.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (AccountingPeriods.PeriodStale stale) {
            throw refused(AccountingErrorCode.PERIOD_STALE, stale);
        } catch (AccountingPeriods.PeriodNotLockable early) {
            throw refused(AccountingErrorCode.PERIOD_NOT_LOCKABLE, early);
        } catch (PostingCutoff.CutoffBusy busy) {
            throw new ApiException(AccountingErrorCode.CUTOFF_BUSY, "An accounting period command was refused",
                    "postings held the posting cutoff; nothing was changed, retry.");
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An accounting period command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No accounting period answers the request",
                "no such record.");
    }

    private static AccountingPeriodId periodId(String raw) {
        try {
            return AccountingPeriodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an accounting period command runs inside a correlation"
                        + " scope"))
                .correlationId();
    }
}
