package com.finapp.app.accounting;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.AccountingBreakId;
import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.BreakStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the accounting break doors (`P14-TSK-010`; {@code AccountingPeriodDesk}'s shape): parse the
 * request exactly, key every act per principal ({@code accounting.break:<type>:<id>}), run each in one transaction under
 * its span, translate the domain's refusals onto the error contract; a refused self-approval is recorded {@code FAILED}
 * in a transaction of its own. The machine, the born-once key and the four eyes are the domain's and
 * {@code accounting V006}'s; nothing here decides them, and nothing here posts.
 *
 * <p>What leaves: a break's finding (its period, check, type, subject, evidence - codes, ids and counts - and the
 * register version it was judged by), its status and its acts' instants, the acknowledgement's <strong>disclosure</strong>
 * (it exists to be disclosed) and the correcting entry's id. Never a person, never a reason.
 */
@RequiredArgsConstructor
public final class AccountingBreakDesk {

    static final String SCOPE = "accounting.break:";

    static final String READ_SPAN = "accounting.break.read";
    static final String PROPOSE_SPAN = "accounting.break.acknowledgement.propose";
    static final String APPROVE_SPAN = "accounting.break.acknowledgement.approve";
    static final String RESOLVE_SPAN = "accounting.break.resolve";

    /** The list's default and largest page. */
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    /** The audit record's target type for a period's page. */
    static final String PERIOD_TARGET = "accounting_break_period";

    @NonNull private final AccountingBreaks breaks;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * One keyset page of a period's breaks: the breaks in birth order and, when the page is full, the cursor the next
     * page starts after ({@code after}) - so a run's thousand breaks can all be reached and decided (the review's M-4).
     */
    public record AccountingBreakPage(List<AccountingBreakView> breaks, String next) {}

    /** An act's receipt: the break as the act left it. */
    public record AccountingBreakReceipt(String id, String period, String status, boolean acknowledgementPending) {}

    /** A break as the operator reads it - no person, no reason. */
    public record AccountingBreakView(
            String id,
            String period,
            String check,
            String type,
            String subject,
            int generation,
            String evidence,
            String judgedBy,
            String status,
            String raisedAt,
            boolean acknowledgementPending,
            String acknowledgementProposedAt,
            String disclosure,
            String acknowledgedAt,
            String resolvedAt,
            String correctingEntryId) {}

    /**
     * One keyset page of a period's breaks in birth order - a read, unkeyed, in one transaction; {@code limit} 1 to 500,
     * {@code after} the previous page's {@code next}. Recorded {@code accounting.BreakRead} in a transaction of its own
     * before the answer (the review's M-5).
     */
    public AccountingBreakPage list(String rawPeriod, String rawStatus, Integer rawLimit, String rawAfter) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        YearMonth period = GeneralLedgerDesk.period(rawPeriod);
        Optional<BreakStatus> status = status(rawStatus);
        int limit = limit(rawLimit);
        Optional<AccountingBreakId> after = after(rawAfter);
        List<AccountingBreakView> page = flow.door(READ_SPAN, () -> transactions.inTransaction(uow ->
                breaks.list(uow, period, status, after, limit).stream().map(AccountingBreakDesk::view).toList()));
        String next = page.size() == limit ? page.get(page.size() - 1).id() : null;
        recordRead(actor, correlation, PERIOD_TARGET, period.toString(), "door=list, period=" + period + ", status="
                + status.map(Enum::name).orElse("ALL") + ", breaks=" + page.size() + ", page="
                + (after.isPresent() ? "next" : "first"));
        return new AccountingBreakPage(page, next);
    }

    /**
     * One break - the controller's read of what they decide. Recorded {@code accounting.BreakRead} before the answer; an
     * unknown break records nothing.
     */
    public AccountingBreakView one(String rawId) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        AccountingBreakId id = breakId(rawId);
        AccountingBreakView view = flow.door(READ_SPAN, () -> transactions.inTransaction(uow ->
                breaks.find(uow, id).map(AccountingBreakDesk::view).orElseThrow(AccountingBreakDesk::notFound)));
        recordRead(actor, correlation, AccountingBreaks.TARGET_TYPE, view.id(), "door=one, period=" + view.period()
                + ", check=" + view.check() + ", type=" + view.type() + ", status=" + view.status());
        return view;
    }

    public AccountingBreakReceipt proposeAcknowledgement(String idempotencyKey, String rawId, String disclosure) {
        AccountingBreakId id = breakId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(PROPOSE_SPAN, () -> keyed(actor, idempotencyKey,
                "acknowledgement-proposal|" + id.value() + "|" + disclosure,
                uow -> receipt(breaks.proposeAcknowledgement(uow, id, actor, disclosure, correlation))));
    }

    public AccountingBreakReceipt approveAcknowledgement(String idempotencyKey, String rawId, String reason) {
        AccountingBreakId id = breakId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(APPROVE_SPAN, () -> keyed(actor, idempotencyKey,
                "acknowledgement-approval|" + id.value() + "|" + reason,
                uow -> receipt(breaks.approveAcknowledgement(uow, id, actor, reason, correlation))));
    }

    public AccountingBreakReceipt resolve(String idempotencyKey, String rawId, String rawEntryId, String reason) {
        AccountingBreakId id = breakId(rawId);
        UUID correctingEntry = entryId(rawEntryId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(RESOLVE_SPAN, () -> keyed(actor, idempotencyKey,
                "resolution|" + id.value() + "|" + correctingEntry + "|" + reason,
                uow -> receipt(breaks.resolve(uow, id, actor, correctingEntry, reason, correlation))));
    }

    // ------------------------------------------------------------------ views

    static AccountingBreakView view(AccountingBreakStore.BreakRow row) {
        return new AccountingBreakView(
                row.id().value().toString(),
                row.period().toString(),
                row.check().name(),
                row.type().name(),
                row.subject(),
                row.generation(),
                row.evidence(),
                row.judgedBy(),
                row.status().name(),
                row.raisedAt().toString(),
                row.acknowledgementPending(),
                row.ackProposedAt().map(Instant::toString).orElse(null),
                row.disclosure().orElse(null),
                row.ackApprovedAt().map(Instant::toString).orElse(null),
                row.resolvedAt().map(Instant::toString).orElse(null),
                row.resolvingEntry().map(UUID::toString).orElse(null));
    }

    private static AccountingBreakReceipt receipt(AccountingBreaks.Acted acted) {
        return new AccountingBreakReceipt(acted.id().value().toString(), acted.period().toString(),
                acted.status().name(), acted.acknowledgementPending());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval or a refused resolution is recorded {@code FAILED} in a transaction of its own.
     */
    private AccountingBreakReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, AccountingBreakReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalsRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    AccountingBreakReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.period() + "|" + receipt.status() + "|"
                            + receipt.acknowledgementPending();
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a break act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new AccountingBreakReceipt(stored[0], stored[1], stored[2], Boolean.parseBoolean(stored[3]));
    }

    /**
     * {@code work}, a refused self-approval or a refused resolution (the review's I-2) recorded {@code FAILED} after the
     * act rolled back with nothing changed, then refused as before.
     */
    private <R> R refusalsRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingBreaks.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                breaks.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        } catch (AccountingBreaks.CorrectingEntryInvalid invalid) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                breaks.recordRefusal(uow, invalid, actor, correlation);
                return null;
            });
            throw invalid;
        }
    }

    /** {@code accounting.BreakRead}, SUCCEEDED, in a transaction of its own - before the answer leaves. */
    private void recordRead(Actor actor, CorrelationId correlation, String targetType, String targetId, String summary) {
        transactions.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    AccountingAuditAction.BREAK_READ, targetType, targetId, Optional.empty(), AuditOutcome.SUCCEEDED,
                    correlation, Optional.of(summary)));
            return null;
        });
    }

    /** The previous page's {@code next}: a break's id; anything else {@code api.ValidationFailed}. */
    static Optional<AccountingBreakId> after(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AccountingBreakId.of(UUID.fromString(raw)));
        } catch (IllegalArgumentException malformed) {
            throw invalid("after is the next cursor of a previous page");
        }
    }

    /** The domain's refusals, in the API's words. */
    static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountingBreaks.BreakNotFound unknown) {
            throw notFound();
        } catch (AccountingBreaks.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (AccountingBreaks.BreakStale stale) {
            throw refused(AccountingErrorCode.BREAK_STALE, stale);
        } catch (AccountingBreaks.CorrectingEntryInvalid invalid) {
            throw refused(AccountingErrorCode.CORRECTING_ENTRY_INVALID, invalid);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    static Optional<BreakStatus> status(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(BreakStatus.valueOf(raw));
        } catch (IllegalArgumentException unknown) {
            throw invalid("status is OPEN, ACKNOWLEDGED or RESOLVED");
        }
    }

    static int limit(Integer raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (raw < 1 || raw > MAX_LIMIT) {
            throw invalid("a page holds 1 to " + MAX_LIMIT + " breaks");
        }
        return raw;
    }

    private static UUID entryId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw invalid("correctingEntryId is a journal entry's id");
        }
    }

    private static AccountingBreakId breakId(String raw) {
        try {
            return AccountingBreakId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An accounting break command was refused", cause.getMessage());
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "An accounting break request carried an invalid"
                + " parameter", detail);
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No accounting break answers the request",
                "no such record.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an accounting break command runs inside a correlation"
                        + " scope"))
                .correlationId();
    }
}
