package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.PeriodCloseStore;
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
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
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
 *
 * <p><strong>An unlock's end</strong> (`P14-TSK-024`): a second person's rejection and the proposer's withdrawal, keyed and
 * reasoned as the unlock's acts are. A proposer's rejection of their own is recorded {@code FAILED} as a self-approval is, and
 * so is another person's withdrawal.
 *
 * <p><strong>Every act on a pending unlock names it</strong> (`P14-TSK-024`'s fix round): an end makes a pending request
 * replaceable, so the approval, the rejection and the withdrawal each name the unlock read by {@code unlockProposedAt} - an
 * unlock request has no id, and its row's instant is its identity - as the list serves it to the accountant and
 * {@code GET .../periods/{id}/requests} to the controller. The fingerprint covers it, so the same key naming another request
 * is the executor's conflict. Another pending is {@code 409 accounting.RequestStale}, recorded {@code FAILED}. The list
 * names the reopen pending too ({@code reopenId}), which the accountant who proposed it names to withdraw it.
 */
@RequiredArgsConstructor
public final class AccountingPeriodDesk {

    static final String SCOPE = "accounting.period:";

    static final String LOCK_SPAN = "accounting.period.lock";
    static final String UNLOCK_PROPOSE_SPAN = "accounting.period.unlock.propose";
    static final String UNLOCK_APPROVE_SPAN = "accounting.period.unlock.approve";
    static final String UNLOCK_REJECT_SPAN = "accounting.period.unlock.reject";
    static final String UNLOCK_WITHDRAW_SPAN = "accounting.period.unlock.withdraw";
    static final String READ_SPAN = "accounting.period.read";

    /** The list's default and largest page - two years, and ten. */
    static final int DEFAULT_LIMIT = 24;
    static final int MAX_LIMIT = 120;

    @NonNull private final AccountingPeriods periods;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;
    /** The reopen pending on a period, which the list names (`P14-TSK-024`): {@code PeriodCloses.pendingReopenOf}. Read only. */
    @NonNull private final PendingReopens pendingReopens;

    /** The reopen pending on a period, if one is: one stands only beside its {@code CLOSED} period at its revision. A plain read. */
    @FunctionalInterface
    public interface PendingReopens {
        Optional<PeriodCloseStore.ReopenRow> of(Connection unitOfWork, AccountingPeriodStore.PeriodRow row);
    }

    /** An act's receipt: the period as the act left it. */
    public record AccountingPeriodReceipt(
            String id, String period, String status, int revision, Integer mappingVersion, boolean unlockPending) {}

    /**
     * A period as the operator reads it - no person, no reason.
     *
     * @param unlockProposedAt when the pending unlock was proposed, the database's clock - its identity, which its withdrawal names; null when none is pending
     * @param reopenPending a reopen of the {@code CLOSED} period is pending (`P14-TSK-024`)
     * @param reopenId the pending reopen's id, which its withdrawal names; null when none is pending
     * @param reopenProposedAt when it was proposed, the database's clock; null when none is pending
     */
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
            String unlockProposedAt,
            boolean reopenPending,
            String reopenId,
            String reopenProposedAt) {}

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

    public AccountingPeriodReceipt approveUnlock(String idempotencyKey, String rawId, String rawRequested, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Instant requested = requestedUnlock(rawRequested);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(UNLOCK_APPROVE_SPAN, () -> keyed(actor, idempotencyKey,
                "unlock-approval|" + id.value() + "@" + requested + "|" + reason,
                uow -> receipt(periods.approveUnlock(uow, id, requested, actor, reason, correlation))));
    }

    /** Rejects the pending unlock - a second person's decision (`P14-TSK-024`): the period stays {@code LOCKED}, nothing moves. */
    public AccountingPeriodReceipt rejectUnlock(String idempotencyKey, String rawId, String rawRequested, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Instant requested = requestedUnlock(rawRequested);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(UNLOCK_REJECT_SPAN, () -> keyed(actor, idempotencyKey,
                "unlock-rejection|" + id.value() + "@" + requested + "|" + reason,
                uow -> receipt(periods.rejectUnlock(uow, id, requested, actor, reason, correlation))));
    }

    /** Withdraws the pending unlock - its proposer's own act (`P14-TSK-024`): the period stays {@code LOCKED}, nothing moves. */
    public AccountingPeriodReceipt withdrawUnlock(String idempotencyKey, String rawId, String rawRequested, String reason) {
        AccountingPeriodId id = periodId(rawId);
        Instant requested = requestedUnlock(rawRequested);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(UNLOCK_WITHDRAW_SPAN, () -> keyed(actor, idempotencyKey,
                "unlock-withdrawal|" + id.value() + "@" + requested + "|" + reason,
                uow -> receipt(periods.withdrawUnlock(uow, id, requested, actor, reason, correlation))));
    }

    /** The latest periods, newest first - a read, unkeyed, in one transaction; {@code limit} held to 1..120. */
    public List<AccountingPeriodView> list(Integer limit) {
        int page = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return flow.door(READ_SPAN, () -> transactions.inTransaction(
                uow -> periods.recent(uow, page).stream().map(row -> view(row, pendingReopens.of(uow, row))).toList()));
    }

    // ------------------------------------------------------------------ views

    AccountingPeriodView view(AccountingPeriodStore.PeriodRow row, Optional<PeriodCloseStore.ReopenRow> reopen) {
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
                row.unlockPending() ? row.unlockProposedAt().map(Instant::toString).orElse(null) : null,
                reopen.isPresent(),
                reopen.map(pending -> pending.id().value().toString()).orElse(null),
                reopen.map(pending -> pending.proposedAt().toString()).orElse(null));
    }

    private static AccountingPeriodReceipt receipt(AccountingPeriods.Acted acted) {
        return new AccountingPeriodReceipt(acted.id().value().toString(), acted.month().toString(),
                acted.status().name(), acted.revision(), acted.pinnedVersion().orElse(null), acted.unlockPending());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing; a
     * self-approval, another person's withdrawal and an act on another request are recorded {@code FAILED} in a
     * transaction of their own.
     */
    private AccountingPeriodReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, AccountingPeriodReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalRecorded(actor,
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
     * {@code work}, a refusal the doors keep - a self-approval, another person's withdrawal, an act on another request
     * ({@link AccountingPeriods.RecordedRefusal}) - recorded {@code FAILED} in a transaction of its own after the act rolled
     * back, then refused as before.
     */
    private <R> R refusalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingPeriods.RecordedRefusal refused) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                periods.recordRefusal(uow, refused, actor, correlation);
                return null;
            });
            throw refused;
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
        } catch (AccountingPeriods.NotTheProposer other) {
            throw refused(AccountingErrorCode.NOT_THE_PROPOSER, other);
        } catch (AccountingPeriods.RequestStale another) {
            throw refused(AccountingErrorCode.REQUEST_STALE, another);
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

    /** The unlock a body names, by when it was proposed: one that cannot be an instant names no request, the refusal naming another gets. */
    static Instant requestedUnlock(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.REQUEST_STALE, "An accounting period command was refused", "the unlock named is no such request.");
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an accounting period command runs inside a correlation"
                        + " scope"))
                .correlationId();
    }
}
