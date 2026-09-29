package com.finapp.app.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.MerchantPayout;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.PayoutSettlementDeclaration;
import com.finapp.merchant.PayoutSettlementExpectations;
import com.finapp.payments.ClearingRecord;
import com.finapp.payments.ClearingRecordStore;
import com.finapp.payments.Dispute;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.RailCapabilities;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SettlementExpectations;
import com.finapp.payments.UnmatchedConfirmation;
import com.finapp.payments.UnmatchedConfirmationStore;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The opening-position backfill (`P8-TSK-007`, ADR-0067 §8): adopts Phases 5–7's completed
 * clearing operations as tracked expectations, <strong>through the live recorder's own
 * path</strong> — each adopted row is what the live opener would have written, because the
 * recorder derives amount, direction and posting date from the posted entry, never from
 * memory. History is not forgiven: each expectation keeps its completion's own posting
 * date, so whatever no report settles ages into {@code MISSING_EXTERNAL} once ageing runs.
 *
 * <h2>Leaderless, paged, converging</h2>
 *
 * <p>One bounded page per transaction, per producer, paged by id — a crash leaves a prefix,
 * and a re-run (any instance, any key) converges on {@code UNIQUE (kind, operation_ref)}
 * and {@code UNIQUE (journal_entry_id, ledger_account_id)} under
 * {@code ON CONFLICT DO NOTHING}. Ten backfills racing each other and live completions add
 * nothing twice; the uniques are the arbiter, never a lock or a leader.
 *
 * <h2>The key records; the uniques converge</h2>
 *
 * <p>The command is keyed per principal ({@code reconciliation.opening-position:<actorType>
 * :<actorId>}, the `P8-TSK-003` disposition). The walk runs <em>before</em> the claim and
 * the keyed transaction commits <em>last</em>, with the counts and the audit record — so a
 * same-key retry after a crash re-walks (and adds nothing, by the uniques), while a replay
 * of a recorded run answers the recorded counts byte for byte.
 *
 * <h2>Never guessed</h2>
 *
 * <p>An operation whose entry the posting key cannot find is skipped and counted — if its
 * line exists anyway, the completeness verifier shows it ({@code line.unattributed});
 * opening from anything but the posted entry would be the guess ADR-0067 §8 refuses.
 */
@Slf4j
@RequiredArgsConstructor
public class OpeningPosition {

    static final String SCOPE_PREFIX = "reconciliation.opening-position:";

    /** One page: bounded so a transaction's write set stays small under any history. */
    static final int PAGE = 200;

    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final WithdrawalStore<Connection> withdrawals;
    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final UnmatchedConfirmationStore<Connection> parkings;
    @NonNull private final ClearingRecordStore<Connection> clearings;
    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final PaymentRails rails;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final ReconciliationExpectationRecorder recorder;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** What one recorded run adopted — counts only, never an amount ({@code INV-AUD-02}). */
    public record Adopted(
            long captures,
            long executions,
            long refunds,
            long withdrawals,
            long disputeStages,
            long parkings,
            long payouts,
            long aliases,
            long skipped) {}

    /** The command: walk, then record under the principal's key. */
    public Adopted record(String idempotencyKey, String reason) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "the opening position is a reasoned act (INV-AUD-01)");
        }
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // THE WALK, before the claim: page by page, each page its own transaction. Every
        // insert converges on the uniques, so however many runs, instances or keys walk,
        // the register gains each operation once.
        Counters counters = new Counters();
        walk(counters);

        // THE RECORD, keyed per principal and committed last: the counts and the reasoned
        // audit record. A crash before here leaves the walk's prefix and no record; the
        // retry re-walks and converges.
        IdempotencyKey key =
                new IdempotencyKey(
                        SCOPE_PREFIX + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(reason.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                inOneTransaction(
                        unitOfWork ->
                                executor.execute(
                                        unitOfWork,
                                        key,
                                        fingerprint,
                                        uow -> recorded(uow, counters, reason, actor,
                                                correlation)));
        return parse(
                outcome.body()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a recorded opening-position outcome always"
                                                        + " carries its counts")));
    }

    private CommandResult recorded(
            Connection unitOfWork,
            Counters counters,
            String reason,
            Actor actor,
            Correlation correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        com.finapp.reconciliation.ReconciliationAuditAction
                                .OPENING_POSITION_RECORDED,
                        "reconciliation_register",
                        "opening-position",
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(counters.summary())));
        return CommandResult.succeeded(
                StoredResponse.of(counters.render().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    // ----------------------------------------------------------------- the walk

    private void walk(Counters counters) {
        pageThrough(
                (uow, after) ->
                        attempts.pageByStatus(uow, PaymentAttemptStatus.CAPTURED, after, PAGE),
                attempt -> attempt.id().value(),
                (uow, attempt) -> adoptCapture(uow, attempt, counters));
        pageThrough(
                (uow, after) ->
                        attempts.pageByStatus(uow, PaymentAttemptStatus.EXECUTED, after, PAGE),
                attempt -> attempt.id().value(),
                (uow, attempt) -> adoptExecution(uow, attempt, counters));
        pageThrough(
                (uow, after) -> refunds.pageCompleted(uow, after, PAGE),
                refund -> refund.id().value(),
                (uow, refund) -> adoptRefund(uow, refund, counters));
        pageThrough(
                (uow, after) -> withdrawals.pageCompleted(uow, after, PAGE),
                withdrawal -> withdrawal.id().value(),
                (uow, withdrawal) -> adoptWithdrawal(uow, withdrawal, counters));
        pageThrough(
                (uow, after) -> disputes.pageAll(uow, after, PAGE),
                dispute -> dispute.id().value(),
                (uow, dispute) -> adoptDisputeStages(uow, dispute, counters));
        pageThrough(
                (uow, after) -> parkings.page(uow, after, PAGE),
                UnmatchedConfirmation::id,
                (uow, parking) -> adoptParking(uow, parking, counters));
        pageThrough(
                (uow, after) -> payouts.pageCompleted(uow, after, PAGE),
                payout -> payout.id().value(),
                (uow, payout) -> adoptPayout(uow, payout, counters));
        pageThrough(
                (uow, after) -> clearings.page(uow, after, PAGE),
                record -> record.id().value(),
                (uow, record) -> adoptAlias(uow, record, counters));
    }

    /** One producer's pages: each page one transaction, the cursor the last id seen. */
    private <R> void pageThrough(Page<R> page, Function<R, UUID> cursorOf, Adopt<R> adopt) {
        UUID after = new UUID(0L, 0L);
        while (true) {
            UUID cursor = after;
            List<R> rows = inOneTransaction(uow -> page.read(uow, cursor));
            if (rows.isEmpty()) {
                return;
            }
            for (R row : rows) {
                inOneTransaction(
                        uow -> {
                            adopt.adopt(uow, row);
                            return null;
                        });
                after = cursorOf.apply(row);
            }
            if (rows.size() < PAGE) {
                return;
            }
        }
    }

    private interface Page<R> {
        List<R> read(Connection unitOfWork, UUID after);
    }

    private interface Adopt<R> {
        void adopt(Connection unitOfWork, R row);
    }

    // ----------------------------------------------------------------- the producers

    private void adoptCapture(Connection uow, PaymentAttempt attempt, Counters counters) {
        Optional<AccountPurpose> purpose =
                rails.capabilitiesOf(attempt.rail()).clearingPurpose();
        if (purpose.isEmpty()) {
            return; // A book completion opens nothing (SettlementModel.NONE, INV-SET-01).
        }
        String postingKey = "payment-capture:" + attempt.id().value();
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            counters.skipped++;
            return;
        }
        recorder.open(
                uow,
                new SettlementExpectations.Opening(
                        SettlementExpectations.Kind.CARD_CAPTURE,
                        attempt.id().value().toString(),
                        postingKey,
                        purpose.get(),
                        clearingAccount(uow, purpose.get(), currencyOf(uow, attempt)),
                        entry.get(),
                        Optional.empty(),
                        List.of(
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.PSP_CAPTURE_REF,
                                        attempt.captureProviderReference().value()),
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.CARD_ATTEMPT,
                                        attempt.id().value().toString())),
                        resolvedCorrelation()));
        counters.captures++;
    }

    private void adoptExecution(Connection uow, PaymentAttempt attempt, Counters counters) {
        Optional<AccountPurpose> purpose =
                rails.capabilitiesOf(attempt.rail()).clearingPurpose();
        if (purpose.isEmpty() || attempt.schemeReference().isEmpty()) {
            return; // The book rail's EXECUTED, or a row no scheme ever referenced.
        }
        String postingKey = "payment-execution:" + attempt.id().value();
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            counters.skipped++;
            return;
        }
        recorder.open(
                uow,
                new SettlementExpectations.Opening(
                        SettlementExpectations.Kind.PUSH_PAY_IN,
                        attempt.id().value().toString(),
                        postingKey,
                        purpose.get(),
                        clearingAccount(uow, purpose.get(), currencyOf(uow, attempt)),
                        entry.get(),
                        attempt.settlementCycle(),
                        List.of(
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.SCHEME_REF,
                                        attempt.schemeReference().orElseThrow().value()),
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.END_TO_END_REF,
                                        attempt.endToEndReference().value())),
                        resolvedCorrelation()));
        counters.executions++;
    }

    private void adoptRefund(Connection uow, Refund refund, Counters counters) {
        PaymentAttempt attempt =
                attempts.findById(uow, refund.attemptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a refund's attempt exists: V004's foreign"
                                                        + " key holds it"));
        RailCapabilities capabilities = rails.capabilitiesOf(attempt.rail());
        if (capabilities.clearingPurpose().isEmpty()) {
            return; // The book refund's counterpart is the payer's own wallet.
        }
        SettlementExpectations.Kind kind;
        SettlementExpectations.ReferenceKind theirKind;
        switch (capabilities.refundMode()) {
            case PROVIDER_REFUND -> {
                kind = SettlementExpectations.Kind.CARD_REFUND;
                theirKind = SettlementExpectations.ReferenceKind.PSP_REFUND_REF;
            }
            case RETURN_PAYMENT -> {
                kind = SettlementExpectations.Kind.PUSH_RETURN;
                theirKind = SettlementExpectations.ReferenceKind.SCHEME_REF;
            }
            default -> {
                return;
            }
        }
        String postingKey = "payment-refund:" + refund.id().value();
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            counters.skipped++;
            return;
        }
        recorder.open(
                uow,
                new SettlementExpectations.Opening(
                        kind,
                        refund.id().value().toString(),
                        postingKey,
                        capabilities.clearingPurpose().get(),
                        clearingAccount(
                                uow,
                                capabilities.clearingPurpose().get(),
                                refund.amount().currency()),
                        entry.get(),
                        Optional.empty(),
                        List.of(
                                new SettlementExpectations.Key(
                                        theirKind, refund.providerReference().value()),
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.OUR_REF,
                                        refund.providerIdempotencyReference().value())),
                        resolvedCorrelation()));
        counters.refunds++;
    }

    private void adoptWithdrawal(Connection uow, Withdrawal withdrawal, Counters counters) {
        Optional<AccountPurpose> purpose =
                rails.capabilitiesOf(withdrawal.railId()).clearingPurpose();
        if (purpose.isEmpty() || withdrawal.schemeReference().isEmpty()) {
            return;
        }
        String postingKey = "wallet-withdrawal:" + withdrawal.id().value();
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            counters.skipped++;
            return;
        }
        recorder.open(
                uow,
                new SettlementExpectations.Opening(
                        SettlementExpectations.Kind.PUSH_WITHDRAWAL,
                        withdrawal.id().value().toString(),
                        postingKey,
                        purpose.get(),
                        clearingAccount(uow, purpose.get(), withdrawal.amount().currency()),
                        entry.get(),
                        withdrawal.settlementCycle(),
                        List.of(
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.SCHEME_REF,
                                        withdrawal.schemeReference().orElseThrow().value()),
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.END_TO_END_REF,
                                        withdrawal.reference().value())),
                        resolvedCorrelation()));
        counters.withdrawals++;
    }

    private void adoptDisputeStages(Connection uow, Dispute dispute, Counters counters) {
        PaymentAttempt attempt =
                attempts.findById(uow, dispute.attemptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a dispute's attempt exists: V020's foreign"
                                                        + " key holds it"));
        Optional<AccountPurpose> purpose =
                rails.capabilitiesOf(attempt.rail()).clearingPurpose();
        if (purpose.isEmpty()) {
            return;
        }
        CurrencyCode currency = currencyOf(uow, attempt);
        adoptStage(uow, dispute, purpose.get(), currency,
                SettlementExpectations.Kind.CHARGEBACK, "dispute-chargeback:",
                SettlementExpectations.ReferenceKind.DISPUTE_CB_REF, counters);
        adoptStage(uow, dispute, purpose.get(), currency,
                SettlementExpectations.Kind.CHARGEBACK_REVERSAL, "dispute-won:",
                SettlementExpectations.ReferenceKind.DISPUTE_REV_REF, counters);
        adoptStage(uow, dispute, purpose.get(), currency,
                SettlementExpectations.Kind.DISPUTE_FEE, "dispute-fee:",
                SettlementExpectations.ReferenceKind.DISPUTE_FEE_REF, counters);
    }

    /** One stage's adoption: the ledger's posting key decides whether the stage posted. */
    private void adoptStage(
            Connection uow,
            Dispute dispute,
            AccountPurpose purpose,
            CurrencyCode currency,
            SettlementExpectations.Kind kind,
            String keyPrefix,
            SettlementExpectations.ReferenceKind referenceKind,
            Counters counters) {
        String postingKey = keyPrefix + dispute.id().value();
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            return; // The stage never posted - an unwalked stage, not a skip.
        }
        recorder.open(
                uow,
                new SettlementExpectations.Opening(
                        kind,
                        dispute.id().value().toString(),
                        postingKey,
                        purpose,
                        clearingAccount(uow, purpose, currency),
                        entry.get(),
                        Optional.empty(),
                        List.of(
                                new SettlementExpectations.Key(
                                        referenceKind, dispute.providerReference().value())),
                        resolvedCorrelation()));
        counters.disputeStages++;
    }

    private void adoptParking(Connection uow, UnmatchedConfirmation parking, Counters counters) {
        Optional<AccountPurpose> purpose =
                rails.capabilitiesOf(parking.rail()).clearingPurpose();
        if (purpose.isEmpty()) {
            return;
        }
        String operation = parking.rail().value() + ":" + parking.schemeReference().value();
        String postingKey = "unmatched-confirmation:" + operation;
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            counters.skipped++;
            return;
        }
        recorder.open(
                uow,
                new SettlementExpectations.Opening(
                        SettlementExpectations.Kind.UNMATCHED_CONFIRMATION,
                        operation,
                        postingKey,
                        purpose.get(),
                        clearingAccount(uow, purpose.get(), parking.amount().currency()),
                        entry.get(),
                        parking.attribution().settlementCycle(),
                        List.of(
                                new SettlementExpectations.Key(
                                        SettlementExpectations.ReferenceKind.SCHEME_REF,
                                        parking.schemeReference().value())),
                        resolvedCorrelation()));
        counters.parkings++;
    }

    private void adoptPayout(Connection uow, MerchantPayout payout, Counters counters) {
        String postingKey = "merchant-payout:" + payout.id().value();
        Optional<JournalEntryId> entry = entryOf(uow, postingKey);
        if (entry.isEmpty()) {
            counters.skipped++;
            return;
        }
        recorder.open(
                uow,
                new PayoutSettlementExpectations.Opening(
                        PayoutSettlementExpectations.Kind.MERCHANT_PAYOUT,
                        payout.id().value().toString(),
                        postingKey,
                        PayoutSettlementDeclaration.CLEARING_PURPOSE,
                        clearingAccount(
                                uow,
                                PayoutSettlementDeclaration.CLEARING_PURPOSE,
                                payout.amount().currency()),
                        entry.get(),
                        List.of(
                                new PayoutSettlementExpectations.Key(
                                        PayoutSettlementExpectations.ReferenceKind
                                                .PAYOUT_PROVIDER_REF,
                                        payout.providerReference().orElseThrow().value()),
                                new PayoutSettlementExpectations.Key(
                                        PayoutSettlementExpectations.ReferenceKind.OUR_REF,
                                        payout.reference().value())),
                        resolvedCorrelation()));
        counters.payouts++;
    }

    private void adoptAlias(Connection uow, ClearingRecord record, Counters counters) {
        PaymentAttempt attempt =
                attempts.findById(uow, record.attemptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a clearing record's attempt exists: V015's"
                                                        + " foreign key holds it"));
        Optional<AccountPurpose> purpose =
                rails.capabilitiesOf(attempt.rail()).clearingPurpose();
        if (purpose.isEmpty()) {
            return;
        }
        recorder.alias(
                uow,
                new SettlementExpectations.AliasRegistration(
                        purpose.get(),
                        SettlementExpectations.ReferenceKind.ACQUIRER_REF,
                        record.acquirerReference().value(),
                        SettlementExpectations.ReferenceKind.CARD_ATTEMPT,
                        record.attemptId().value().toString(),
                        resolvedCorrelation()));
        counters.aliases++;
    }

    // ----------------------------------------------------------------- plumbing

    private Optional<JournalEntryId> entryOf(Connection uow, String postingKey) {
        return entries.findByIdempotencyScope(
                uow, PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey);
    }

    private LedgerAccountId clearingAccount(
            Connection uow, AccountPurpose purpose, CurrencyCode currency) {
        return chart.resolve(uow, purpose, currency).id();
    }

    /** The operation's currency, from its intent — present for card and push alike. */
    private CurrencyCode currencyOf(Connection uow, PaymentAttempt attempt) {
        return intents.findById(uow, attempt.intentId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "an attempt's intent exists: V003's foreign key"
                                                + " holds it"))
                .amount()
                .currency();
    }

    private static Correlation resolvedCorrelation() {
        Correlation current =
                com.finapp.platform.correlation.CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "the backfill runs inside a correlation"
                                                        + " scope"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
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

    /** The run's counters — mutable during the walk, rendered once at the record. */
    private static final class Counters {
        long captures;
        long executions;
        long refunds;
        long withdrawals;
        long disputeStages;
        long parkings;
        long payouts;
        long aliases;
        long skipped;

        String render() {
            return captures + "|" + executions + "|" + refunds + "|" + withdrawals + "|"
                    + disputeStages + "|" + parkings + "|" + payouts + "|" + aliases + "|"
                    + skipped;
        }

        String summary() {
            return "captures=" + captures + ", executions=" + executions + ", refunds="
                    + refunds + ", withdrawals=" + withdrawals + ", disputeStages="
                    + disputeStages + ", parkings=" + parkings + ", payouts=" + payouts
                    + ", aliases=" + aliases + ", skipped=" + skipped;
        }
    }

    private static Adopted parse(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", 9);
        if (fields.length != 9) {
            throw new IllegalStateException(
                    "a stored opening-position body always carries its nine counts");
        }
        return new Adopted(
                Long.parseLong(fields[0]),
                Long.parseLong(fields[1]),
                Long.parseLong(fields[2]),
                Long.parseLong(fields[3]),
                Long.parseLong(fields[4]),
                Long.parseLong(fields[5]),
                Long.parseLong(fields[6]),
                Long.parseLong(fields[7]),
                Long.parseLong(fields[8]));
    }
}
