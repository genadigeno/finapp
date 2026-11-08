package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.PriorPeriodAdjustmentId;
import com.finapp.accounting.PriorPeriodAdjustmentStore;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.Side;
import com.finapp.app.api.DecimalText;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.MonetaryException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the prior-period adjustment's doors (`P14-TSK-013`; {@code PeriodCloseDesk}'s shape): key every act per principal ({@code accounting.ppa:<type>:<id>}), run each in one transaction under its span,
 * translate the domain's refusals onto the error contract; a refused self-approval is recorded {@code FAILED} in a transaction of its own; each read recorded {@code accounting.PriorPeriodAdjustmentRead} in a transaction of its own before
 * the answer. The boundary's decimal strings become exact {@link Money} here, and a value that cannot is refused, naming the line and the field and never the value. The machine, what the person read and the four eyes are the domain's and {@code accounting
 * V009}'s; the ledger's own refusals pass through to its own codes.
 *
 * <p><strong>A key's replay is the first answer</strong>: the same key with the same body returns the stored receipt, a timeout then a retry included; the same key with another body is the executor's conflict, the fingerprint covering the reason, the lines and what was
 * read. Beneath the key, the domain converges the same person's repeat.
 *
 * <p>What leaves a read: the months, the revision, the date, the ledger's ids, the status, and to the controller alone the lines and the proposer's reason, which it must read to answer for. Never a person; never a decision's reason.
 */
@RequiredArgsConstructor
public final class PriorPeriodAdjustmentDesk {

    static final String SCOPE = "accounting.ppa:";

    static final String PROPOSE_SPAN = "accounting.ppa.propose";
    static final String APPROVE_SPAN = "accounting.ppa.approve";
    static final String REJECT_SPAN = "accounting.ppa.reject";
    static final String READ_SPAN = "accounting.ppa.read";

    @NonNull private final PriorPeriodAdjustments adjustments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /** One line of a proposal as a person states it - the amount a decimal string. */
    public record StatedLine(String ledgerAccountId, String direction, String amount, String currency) {

        /** Never an amount: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "StatedLine[" + ledgerAccountId + ", " + direction + "]";
        }
    }

    /** One correction of a month - no person, no reason, no amount. */
    public record PriorPeriodCorrectionView(String id, int correctedRevision, String status, String postingDate, String journalEntryId) {}

    /**
     * An act's receipt: what it stands at, and the month's other corrections as the act found them - so nobody corrects a month twice without having been told.
     *
     * @param postingDate the date the entry posts on, and what the approver names
     */
    public record PriorPeriodAdjustmentReceipt(String id, String correctedPeriod, int correctedRevision, String targetPeriod, String postingDate, String status, String adjustmentProposalId,
            String journalEntryId, List<PriorPeriodCorrectionView> earlierCorrections) {}

    /**
     * One line as the approver reads it.
     *
     * @param purpose the account's purpose, a code: the approver holds no ledger read, so this is how they know what the line touches
     * @param ownerKind the kind of party holding the account, a code - never the party
     */
    public record PriorPeriodLineView(String ledgerAccountId, String purpose, String ownerKind, String direction, String amount, String currency) {

        /** Served to the approver and never logged. */
        @Override
        public String toString() {
            return "PriorPeriodLineView[" + ledgerAccountId + ", " + purpose + ", " + direction + "]";
        }
    }

    /**
     * What an approver reads. {@code stillStands} when neither month would refuse an approval now; {@code blockedBy} which, and why. When false the way on is to reject it and propose again. True is no promise: an approval can still be refused for a purpose a release has closed, a purpose unmapped in the version now in force, or by the ledger, and it stands {@code PROPOSED}. The approval names {@code postingDate}, {@code correctedRevision} and the ids of
     * {@code earlierCorrections} that are {@code POSTED}.
     */
    public record PriorPeriodAdjustmentView(String id, String correctedPeriod, int correctedRevision, String targetPeriod, String postingDate, String status, String adjustmentProposalId,
            String journalEntryId, String proposedAt, String decidedAt, String reason, boolean stillStands, String blockedBy, List<PriorPeriodLineView> lines, List<PriorPeriodCorrectionView> earlierCorrections) {

        /** Served to the approver and never logged: a record's generated {@code toString} would carry the reason and the amounts into a log line. */
        @Override
        public String toString() {
            return "PriorPeriodAdjustmentView[" + id + ", " + status + "]";
        }
    }

    /** A standing proposal in a controller's list - read one to decide it. */
    public record PendingPriorPeriodAdjustmentView(String id, String correctedPeriod, int correctedRevision, String targetPeriod, String postingDate, String proposedAt) {}

    // ------------------------------------------------------------------ acts

    public PriorPeriodAdjustmentReceipt propose(String idempotencyKey, String rawCorrected, int correctedRevision, String reason, List<StatedLine> stated) {
        AccountingPeriodId corrected = periodId(rawCorrected);
        List<StatedLine> given = stated == null ? List.of() : stated;
        List<PriorPeriodPostings.Line> lines = parsed(given);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(PROPOSE_SPAN, () -> keyed(actor, idempotencyKey,
                "ppa-proposal|" + corrected.value() + "|" + correctedRevision + "|" + reason + "|" + given.stream().map(PriorPeriodAdjustmentDesk::fingerprinted).collect(Collectors.joining(";")),
                uow -> receipt(adjustments.propose(uow, corrected, correctedRevision, lines, actor, reason, correlation))));
    }

    public PriorPeriodAdjustmentReceipt approve(String idempotencyKey, String rawId, String rawDate, int correctedRevision, List<String> rawPosted, String reason) {
        PriorPeriodAdjustmentId id = adjustmentId(rawId);
        LocalDate date = namedDate(rawDate);
        Set<UUID> posted = namedCorrections(rawPosted);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(APPROVE_SPAN, () -> keyed(actor, idempotencyKey,
                "ppa-approval|" + id.value() + "|" + date + "|" + correctedRevision + "|" + new TreeSet<>(posted) + "|" + reason,
                uow -> receipt(adjustments.approve(uow, id, new PriorPeriodAdjustments.Approval(date, correctedRevision, posted), actor, reason, correlation))));
    }

    public PriorPeriodAdjustmentReceipt reject(String idempotencyKey, String rawId, String reason) {
        PriorPeriodAdjustmentId id = adjustmentId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REJECT_SPAN, () -> keyed(actor, idempotencyKey, "ppa-rejection|" + id.value() + "|" + reason,
                uow -> receipt(adjustments.reject(uow, id, actor, reason, correlation))));
    }

    // ------------------------------------------------------------------ reads

    /** One adjustment, as its approver reads it. Recorded before it is served. */
    public PriorPeriodAdjustmentView read(String rawId) {
        PriorPeriodAdjustmentId id = adjustmentId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        PriorPeriodAdjustments.View view = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> adjustments.view(uow, id))));
        recordRead(PriorPeriodAdjustments.TARGET_TYPE, id.value().toString(), "door=adjustment, status=" + view.row().status().name() + ", stillStands=" + view.stillStands() + ", lines=" + view.lines().size(), actor, correlation);
        return view(view);
    }

    /** Those standing, oldest first - so one that can no longer post can be found. Recorded before it is served. */
    public List<PendingPriorPeriodAdjustmentView> pending() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<PriorPeriodAdjustmentStore.Row> rows = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(adjustments::pending)));
        recordRead(PriorPeriodAdjustments.TARGET_TYPE, "pending", "door=pending, rows=" + rows.size(), actor, correlation);
        return rows.stream().map(row -> new PendingPriorPeriodAdjustmentView(row.id().value().toString(), row.correctedMonth().toString(), row.correctedRevision(), row.targetMonth().toString(), row.postingDate().toString(),
                row.proposedAt().toString())).toList();
    }

    /** Every correction of a month, whatever its status and revision. Recorded before it is served. */
    public List<PriorPeriodCorrectionView> ofPeriod(String rawPeriod) {
        AccountingPeriodId period = periodId(rawPeriod);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<PriorPeriodAdjustments.Correction> rows = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> adjustments.correctionsOf(uow, period))));
        recordRead(AccountingPeriods.TARGET_TYPE, period.value().toString(), "door=period, rows=" + rows.size(), actor, correlation);
        return rows.stream().map(PriorPeriodAdjustmentDesk::correction).toList();
    }

    private void recordRead(String targetType, String target, String summary, Actor actor, CorrelationId correlation) {
        transactions.inTransaction(uow -> {
            adjustments.recordRead(uow, targetType, target, summary, actor, correlation);
            return null;
        });
    }

    // ------------------------------------------------------------------ views

    private static PriorPeriodAdjustmentView view(PriorPeriodAdjustments.View view) {
        PriorPeriodAdjustmentStore.Row row = view.row();
        return new PriorPeriodAdjustmentView(row.id().value().toString(), row.correctedMonth().toString(), row.correctedRevision(), row.targetMonth().toString(), row.postingDate().toString(), row.status().name(),
                row.proposal().toString(), row.entry().map(UUID::toString).orElse(null), row.proposedAt().toString(), row.decidedAt().map(Instant::toString).orElse(null), row.proposalReason(), view.stillStands(),
                view.blockedBy().map(Enum::name).orElse(null),
                view.lines().stream().map(read -> new PriorPeriodLineView(read.line().ledgerAccount().toString(), read.purpose(), read.ownerKind(), read.line().side().name(), read.line().amount().toBigDecimal().toPlainString(), read.line().amount().currency().code())).toList(),
                view.earlierCorrections().stream().map(PriorPeriodAdjustmentDesk::correction).toList());
    }

    private static PriorPeriodCorrectionView correction(PriorPeriodAdjustments.Correction correction) {
        return new PriorPeriodCorrectionView(correction.id().value().toString(), correction.correctedRevision(), correction.status().name(), correction.postingDate().toString(), correction.entry().map(UUID::toString).orElse(null));
    }

    private static PriorPeriodAdjustmentReceipt receipt(PriorPeriodAdjustments.Acted acted) {
        return new PriorPeriodAdjustmentReceipt(acted.id().value().toString(), acted.correctedMonth().toString(), acted.correctedRevision(), acted.targetMonth().toString(), acted.postingDate().toString(),
                acted.status().name(), acted.proposal().toString(), acted.entry().map(UUID::toString).orElse(null), acted.earlierCorrections().stream().map(PriorPeriodAdjustmentDesk::correction).toList());
    }

    // ------------------------------------------------------------------ plumbing

    /** One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing; a self-approval is recorded {@code FAILED} in a transaction of its own. */
    private PriorPeriodAdjustmentReceipt keyed(Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, PriorPeriodAdjustmentReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                        CommandResult.succeeded(StoredResponse.of(stored(act.apply(uow)).getBytes(StandardCharsets.UTF_8), "text/plain"))))));
        return parsedReceipt(new String(outcome.body().orElseThrow(() -> new IllegalStateException("a prior-period act stores its receipt")), StandardCharsets.UTF_8));
    }

    /** The receipt as the key stores it: ids, months, dates and codes, none holding a delimiter. */
    static String stored(PriorPeriodAdjustmentReceipt receipt) {
        return String.join("|", receipt.id(), receipt.correctedPeriod(), Integer.toString(receipt.correctedRevision()), receipt.targetPeriod(), receipt.postingDate(), receipt.status(), receipt.adjustmentProposalId(),
                orEmpty(receipt.journalEntryId()), receipt.earlierCorrections().stream()
                        .map(correction -> String.join(",", correction.id(), Integer.toString(correction.correctedRevision()), correction.status(), correction.postingDate(), orEmpty(correction.journalEntryId())))
                        .collect(Collectors.joining(";")));
    }

    static PriorPeriodAdjustmentReceipt parsedReceipt(String stored) {
        String[] fields = stored.split("\\|", -1);
        List<PriorPeriodCorrectionView> earlier = new ArrayList<>();
        if (!fields[8].isEmpty()) {
            for (String item : fields[8].split(";", -1)) {
                String[] correction = item.split(",", -1);
                earlier.add(new PriorPeriodCorrectionView(correction[0], Integer.parseInt(correction[1]), correction[2], correction[3], orNull(correction[4])));
            }
        }
        return new PriorPeriodAdjustmentReceipt(fields[0], fields[1], Integer.parseInt(fields[2]), fields[3], fields[4], fields[5], fields[6], orNull(fields[7]), earlier);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (PriorPeriodAdjustments.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                adjustments.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. A detail names codes and dates, never a reason or an amount. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (PriorPeriodAdjustments.AdjustmentNotFound | AccountingPeriods.PeriodNotFound unknown) {
            throw notFound();
        } catch (PriorPeriodAdjustments.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (AccountingPeriods.PeriodStale stale) {
            throw refused(AccountingErrorCode.PERIOD_STALE, stale);
        } catch (PriorPeriodAdjustments.AdjustmentStale stale) {
            throw refused(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, stale);
        } catch (PriorPeriodAdjustments.AdjustmentInvalid invalid) {
            throw refused(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_INVALID, invalid);
        } catch (PriorPeriodAdjustments.PurposeClosed closed) {
            throw refused(AccountingErrorCode.PRIOR_PERIOD_PURPOSE_CLOSED, closed);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A prior-period adjustment command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No prior-period adjustment answers the request", "no such record.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_INVALID, "A prior-period adjustment command was refused", detail);
    }

    private static ApiException stale(String detail) {
        return new ApiException(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, "A prior-period adjustment command was refused", detail);
    }

    private static AccountingPeriodId periodId(String raw) {
        try {
            return AccountingPeriodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static PriorPeriodAdjustmentId adjustmentId(String raw) {
        try {
            return PriorPeriodAdjustmentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    /** The date the body names: one that is no date is not what stands. */
    private static LocalDate namedDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException | NullPointerException malformed) {
            throw stale("the posting date named is no date; an approver names the date they read.");
        }
    }

    private static Set<UUID> namedCorrections(List<String> raw) {
        Set<UUID> named = new TreeSet<>();
        for (String correction : raw == null ? List.<String>of() : raw) {
            try {
                named.add(UUID.fromString(correction));
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw stale("a posted correction named is no such record.");
            }
        }
        return named;
    }

    /**
     * Parse before the transaction opens; each refusal names the line and the field, never the value. {@link DecimalText} first, so no exponent reaches a rescale; {@code Money.of} is exact: more precision than the
     * currency has is refused, never rounded.
     */
    private static List<PriorPeriodPostings.Line> parsed(List<StatedLine> stated) {
        List<PriorPeriodPostings.Line> lines = new ArrayList<>();
        for (int at = 0; at < stated.size(); at++) {
            StatedLine line = stated.get(at);
            UUID account;
            try {
                account = UUID.fromString(line.ledgerAccountId());
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw invalid("line " + at + ": the ledger account is malformed.");
            }
            Side side;
            if ("DEBIT".equals(line.direction())) {
                side = Side.DEBIT;
            } else if ("CREDIT".equals(line.direction())) {
                side = Side.CREDIT;
            } else {
                throw invalid("line " + at + ": the direction is DEBIT or CREDIT.");
            }
            CurrencyCode currency;
            try {
                currency = CurrencyCode.of(line.currency());
            } catch (RuntimeException malformed) {
                throw invalid("line " + at + ": the currency is malformed.");
            }
            try {
                lines.add(new PriorPeriodPostings.Line(account, side, Money.of(DecimalText.parse(line.amount()), currency)));
            } catch (NumberFormatException | NullPointerException | MonetaryException malformed) {
                throw invalid("line " + at + ": the amount is not an exact amount of the currency.");
            }
        }
        return lines;
    }

    private static String fingerprinted(StatedLine line) {
        return line.ledgerAccountId() + ">" + line.direction() + ">" + line.amount() + ">" + line.currency();
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a prior-period adjustment command runs inside a correlation scope"))
                .correlationId();
    }
}
