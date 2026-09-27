package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Applies the scheme's word to a withdrawal (`P7-TSK-008`) — the ONE place an answer becomes
 * state, shared by the dispatch's own outcome transaction, a takeover's re-send and the
 * inquiry sweep, so the resolvers cannot disagree about what an answer means
 * ({@code MerchantPayoutOutcomes}' §12 discipline, clause for clause).
 *
 * <h2>Always on a locked row, always conditional, acting only once</h2>
 *
 * <p>Every caller hands in the withdrawal it LOCKED in its own transaction; every move is a
 * conditional transition; everything with an effect — the release, the posting, the fact,
 * the audit record — happens only in the branch where this call's own transition fired.
 *
 * <h2>The money ({@code INV-RAIL-04})</h2>
 *
 * <ul>
 *   <li><strong>Accepted</strong> — irrevocably (ADR-0062 §3): the hold released and DEBIT
 *       the customer's wallet / CREDIT {@code INSTANT_CLEARING} posted in one transaction,
 *       keyed {@code wallet-withdrawal:<id>} — instructed and accepted, not settled
 *       ({@code INV-SET-01}); the scheme's reference and cycle land on the row, Phase 8's
 *       keys.
 *   <li><strong>Rejected or never received</strong>: the hold released, nothing posted —
 *       the wallet is whole again.
 *   <li><strong>Ambiguous</strong>: {@code DISPATCHED → UNKNOWN} and the hold STANDS until
 *       the inquiry resolves it ({@code INV-LIFE-03}); nothing is published.
 * </ul>
 *
 * <h2>The two answers that mean less than they seem</h2>
 *
 * <ul>
 *   <li><strong>Nothing sent</strong> fails a withdrawal only on its FIRST send, and only
 *       while the locked row's permit is still that send's (ADR-0057 §3, re-judged on the
 *       row): a takeover that renewed the permit may have sent, and been paid.
 *   <li><strong>Unrecognised</strong> — the scheme's explicit word — is concluded
 *       {@code NEVER_RECEIVED} only when the latest send permit is at or before the
 *       caller's bound: the rail's <em>declared</em> outcome deadline plus the configured
 *       margin, never a clock alone (ADR-0062 §3).
 * </ul>
 */
@RequiredArgsConstructor
public final class WithdrawalOutcomes {

    static final String INITIATED_EVENT_TYPE = "payments.WithdrawalInitiated";
    static final String COMPLETED_EVENT_TYPE = "payments.WithdrawalCompleted";
    static final String FAILED_EVENT_TYPE = "payments.WithdrawalFailed";
    static final String AGGREGATE_TYPE = "withdrawal";
    static final String PRODUCER = "payments";
    static final int EVENT_VERSION = 1;

    /** The posting key's prefix — the backlog's own spelling. */
    public static final String POSTING_KEY_PREFIX = "wallet-withdrawal:";

    @NonNull private final WithdrawalStore<Connection> withdrawals;
    @NonNull private final HoldService holds;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Where each acting judgement is reported (`P7-TSK-015`, {@link RailOutcomeObserver}).
     * Appended last (the constructor is positional). */
    @NonNull private final RailOutcomeObserver observer;

    /** The committed status after an answer, and whether THIS call's transition fired —
     * the row's truth, never the verdict's. */
    public record Applied(WithdrawalStatus status, boolean acting) {

        public Applied {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /**
     * Applies the answer to a send of our reference.
     *
     * @param firstSend whether this answer is to the dispatch's own first send
     * @param permit the send permit the answering flight committed; a first send's refused
     *     connection fails the withdrawal only while the locked row's permit is still this
     *     one
     */
    public Applied applySendAnswer(
            Connection unitOfWork,
            Withdrawal locked,
            PushAnswer answer,
            boolean firstSend,
            Instant permit,
            Correlation correlation) {
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!locked.status().isResolvable()) {
            // A late or contradictory answer beside a resolved withdrawal is evidence,
            // not a move.
            return new Applied(locked.status(), false);
        }
        return switch (answer.verdict()) {
            case ACCEPTED ->
                    complete(
                            unitOfWork,
                            locked,
                            answer.schemeReference().orElseThrow(),
                            answer.settlementCycle(),
                            correlation,
                            "send");
            case REJECTED ->
                    fail(unitOfWork, locked, WithdrawalFailureReason.DECLINED, correlation,
                            "send");
            case NOTHING_SENT ->
                    firstSend
                                    && locked.status() == WithdrawalStatus.DISPATCHED
                                    && locked.lastDispatchedAt().equals(permit)
                            ? fail(
                                    unitOfWork,
                                    locked,
                                    WithdrawalFailureReason.PROVIDER_UNAVAILABLE,
                                    correlation,
                                    "send")
                            : new Applied(locked.status(), false);
            case INDETERMINATE -> unknown(unitOfWork, locked, correlation, "send");
        };
    }

    /**
     * Applies the answer to the inquiry sweep's question by our reference.
     *
     * @param neverReceivedBound the scheme's explicit {@code UNRECOGNISED} is concluded
     *     {@code NEVER_RECEIVED} only when the withdrawal's latest send permit is at or
     *     before this instant — the declared deadline plus margin, computed by the caller
     */
    public Applied applyInquiryAnswer(
            Connection unitOfWork,
            Withdrawal locked,
            PushInquiryAnswer answer,
            Instant neverReceivedBound,
            Correlation correlation) {
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(neverReceivedBound, "neverReceivedBound must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!locked.status().isResolvable()) {
            return new Applied(locked.status(), false);
        }
        return switch (answer.verdict()) {
            case ACCEPTED ->
                    complete(
                            unitOfWork,
                            locked,
                            answer.schemeReference().orElseThrow(),
                            answer.settlementCycle(),
                            correlation,
                            "inquiry");
            case REJECTED ->
                    fail(unitOfWork, locked, WithdrawalFailureReason.DECLINED, correlation,
                            "inquiry");
            case UNRECOGNISED ->
                    locked.sendPermitAtOrBefore(neverReceivedBound)
                            ? fail(
                                    unitOfWork,
                                    locked,
                                    WithdrawalFailureReason.NEVER_RECEIVED,
                                    correlation,
                                    "inquiry")
                            // A permit younger than the bound: a re-send may be in flight
                            // right now, so absence proves nothing yet (ADR-0057 §4).
                            : new Applied(locked.status(), false);
            case INDETERMINATE -> unknown(unitOfWork, locked, correlation, "inquiry");
        };
    }

    private Applied complete(
            Connection unitOfWork,
            Withdrawal locked,
            ProviderReference theirs,
            Optional<String> cycle,
            Correlation correlation,
            String resolver) {
        Instant now = Instant.now(clock);
        Withdrawal completed = locked.complete(theirs, cycle);
        requireLanded(unitOfWork, locked, completed, now);
        requireReleased(unitOfWork, locked);

        LedgerAccount clearing =
                chart.resolve(
                        unitOfWork, AccountPurpose.INSTANT_CLEARING, locked.amount().currency());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // Only in the acting branch, keyed by the operation: a double-complete is a
        // conflict, never a second entry (INV-PAY-04's posting face).
        postings.post(
                unitOfWork,
                new PostingCommand(
                        POSTING_KEY_PREFIX + locked.id().value(),
                        today,
                        today,
                        locked.id().value().toString(),
                        List.of(
                                new JournalLine(
                                        locked.walletAccountId(),
                                        Direction.DEBIT,
                                        locked.amount()),
                                new JournalLine(
                                        clearing.id(), Direction.CREDIT, locked.amount()))));
        announce(unitOfWork, completed, COMPLETED_EVENT_TYPE, correlation, now);
        record(unitOfWork, completed, correlation, now, resolver);
        return new Applied(WithdrawalStatus.COMPLETED, true);
    }

    private Applied fail(
            Connection unitOfWork,
            Withdrawal locked,
            WithdrawalFailureReason why,
            Correlation correlation,
            String resolver) {
        Instant now = Instant.now(clock);
        Withdrawal failed = locked.fail(why);
        requireLanded(unitOfWork, locked, failed, now);
        requireReleased(unitOfWork, locked);
        announce(unitOfWork, failed, FAILED_EVENT_TYPE, correlation, now);
        record(unitOfWork, failed, correlation, now, resolver);
        return new Applied(WithdrawalStatus.FAILED, true);
    }

    private Applied unknown(
            Connection unitOfWork, Withdrawal locked, Correlation correlation, String resolver) {
        if (locked.status() != WithdrawalStatus.DISPATCHED) {
            // Already UNKNOWN: another ambiguous answer teaches nothing new.
            return new Applied(locked.status(), false);
        }
        Instant now = Instant.now(clock);
        Withdrawal unknown = locked.outcomeUnknown();
        requireLanded(unitOfWork, locked, unknown, now);
        // The hold STANDS, and nothing is published: UNKNOWN is not terminal.
        record(unitOfWork, unknown, correlation, now, resolver);
        return new Applied(WithdrawalStatus.UNKNOWN, true);
    }

    private void requireLanded(
            Connection unitOfWork, Withdrawal before, Withdrawal after, Instant at) {
        if (!withdrawals.transition(unitOfWork, before, after, at)) {
            // The caller locked the row, so a lost count is unreachable in this flow;
            // refusing loudly beats guessing if an unknown writer proves otherwise.
            throw new PaymentsStorageException(
                    "a locked withdrawal's conditional move found another writer's state");
        }
    }

    private void requireReleased(Connection unitOfWork, Withdrawal withdrawal) {
        Optional<HoldService.Release> release = holds.release(unitOfWork, withdrawal.holdId());
        if (release.isEmpty() || !release.get().released()) {
            // The acting transition is the only path here, so the hold must have been
            // standing: a missing or already-released hold means some other writer moved
            // this withdrawal's money, and posting now would move it twice.
            throw new PaymentsStorageException(
                    "a resolving withdrawal's hold was not standing to release");
        }
    }

    private void announce(
            Connection unitOfWork,
            Withdrawal withdrawal,
            String eventType,
            Correlation correlation,
            Instant now) {
        EventPayload payload =
                EventPayload.of()
                        .with("status", withdrawal.status().name())
                        .with("rail", withdrawal.railId().value());
        if (withdrawal.failureReason().isPresent()) {
            payload = payload.with("failureReason", withdrawal.failureReason().get().name());
        }
        // Identifiers and enumerated names only - never the amount, never a reference
        // (INV-AUD-02).
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        withdrawal.id(),
                        AGGREGATE_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        causeOf(correlation)),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** The dispatch's own fact: the withdrawal committed durably before its outcome exists. */
    void announceInitiated(
            Connection unitOfWork, Withdrawal withdrawal, Correlation correlation, Instant now) {
        announce(unitOfWork, withdrawal, INITIATED_EVENT_TYPE, correlation, now);
    }

    private void record(
            Connection unitOfWork,
            Withdrawal after,
            Correlation correlation,
            Instant now,
            String resolver) {
        // The platform applies every outcome - an enumerated enterSystem() site at every
        // caller (the MerchantPayoutOutcomes attribution rule: which resolver wins the
        // harmless race must not decide the actor).
        Actor platform = SecurityContext.require();
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        PaymentsAuditAction.WITHDRAWAL_OUTCOME_APPLIED,
                        Withdrawals.TARGET_TYPE,
                        after.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "withdrawal=" + after.id()
                                        + ", status=" + after.status()
                                        + after.failureReason()
                                                .map(r -> ", failure=" + r)
                                                .orElse("")
                                        + ", resolver=" + resolver)));
        // Every acting branch - complete, fail, unknown - records here, so this is the one
        // report of the judgement (P7-TSK-015), on the withdrawal's stored rail.
        observer.withdrawalJudged(after.railId(), after.status());
    }

    static CausationId causeOf(Correlation correlation) {
        return correlation
                .cause()
                .orElseGet(() -> CausationId.of(correlation.correlationId().value()));
    }
}
