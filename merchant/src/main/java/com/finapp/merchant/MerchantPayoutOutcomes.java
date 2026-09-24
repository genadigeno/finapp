package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
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
 * Applies the payout provider's word to a payout (`P6-TSK-012`, ADR-0051 §2, ADR-0057) — the
 * ONE place an answer becomes state, shared by the dispatch's own outcome transaction, a
 * takeover's re-send and the resolution sweep, so the three resolvers cannot disagree about what
 * an answer means.
 *
 * <h2>Always on a locked row, always conditional, acting only once</h2>
 *
 * <p>Every caller hands in the payout it LOCKED in its own transaction, so no resolver judges a
 * stale state and none reports a status the row does not have (the refund's losing-resolver
 * report, not inherited). Every move is still a conditional transition — the lock makes a lost
 * count unreachable, and refusing loudly beats guessing if an unknown writer proves otherwise —
 * and everything with an effect (the release, the posting, the fact, the audit record) happens
 * only in the branch where this call's own transition fired.
 *
 * <h2>The money</h2>
 *
 * <ul>
 *   <li><strong>Accepted</strong>: the hold released and DEBIT the merchant's payable / CREDIT
 *       {@code PAYOUT_CLEARING} posted in one transaction, keyed
 *       {@code merchant-payout:<payoutId>} — instructed, not settled ({@code INV-SET-01}). The
 *       release's result is required, not ignored: releasing a hold that is not there would be
 *       posting money no hold reserved.
 *   <li><strong>Refused or never received</strong>: the hold released, nothing posted — the
 *       payable is whole again.
 *   <li><strong>Ambiguous</strong>: {@code DISPATCHED → UNKNOWN} and the hold STANDS, visibly,
 *       until a query resolves it ({@code INV-LIFE-03}, the standing-hold doctrine). Nothing is
 *       published: not terminal, and the hold is its record (the refund precedent).
 * </ul>
 *
 * <h2>The two answers that mean less than they seem</h2>
 *
 * <ul>
 *   <li><strong>Nothing sent</strong> fails a payout only on its FIRST send. On a takeover's
 *       re-send, a refused connection says nothing about the send before it — which may have
 *       paid — so it moves nothing (ADR-0057 §3). Releasing the hold there would be the
 *       over-payout this whole machine exists to prevent.
 *   <li><strong>Unrecognised</strong> is concluded {@code NEVER_RECEIVED} only when the latest
 *       send permit is at or before the caller's bound, judged on the locked row (ADR-0057 §4).
 * </ul>
 */
@RequiredArgsConstructor
public final class MerchantPayoutOutcomes {

    static final String INITIATED_EVENT_TYPE = "merchant.MerchantPayoutInitiated";
    static final String COMPLETED_EVENT_TYPE = "merchant.MerchantPayoutCompleted";
    static final String FAILED_EVENT_TYPE = "merchant.MerchantPayoutFailed";
    static final String AGGREGATE_TYPE = "merchant_payout";
    static final String PRODUCER = "merchant";
    static final int EVENT_VERSION = 1;

    /** The posting key's prefix — ADR-0051 §2's own spelling. */
    public static final String POSTING_KEY_PREFIX = "merchant-payout:";

    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final HoldService holds;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The committed status after an answer, and whether THIS call's transition fired.
     *
     * @param status the locked row's status once the answer is applied — the row's truth, never
     *     the verdict's
     */
    public record Applied(MerchantPayoutStatus status, boolean acting) {

        public Applied {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /**
     * Applies the answer to a send of our reference.
     *
     * @param firstSend whether this answer is to the dispatch's own first send — the only send
     *     whose refused connection proves nothing was ever transmitted
     * @param permit the send permit the answering flight committed; a first send's refused
     *     connection fails the payout only while the locked row's permit is still this one —
     *     a takeover that renewed it since may have sent, and been paid (the Phase 6 → 7
     *     transition: this rule compared no permit, judging the request rather than the row)
     */
    public Applied applySendAnswer(
            Connection unitOfWork,
            MerchantPayout locked,
            PayoutAnswer answer,
            boolean firstSend,
            Instant permit,
            Correlation correlation) {
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!locked.status().isResolvable()) {
            // A late or contradictory answer beside a resolved payout is evidence, not a move.
            return new Applied(locked.status(), false);
        }
        return switch (answer.verdict()) {
            case ACCEPTED ->
                    complete(unitOfWork, locked, answer.providerReference().orElseThrow(),
                            correlation, "send");
            case DECLINED ->
                    fail(unitOfWork, locked, PayoutFailureReason.DECLINED, correlation, "send");
            case NOTHING_SENT ->
                    firstSend
                                    && locked.status() == MerchantPayoutStatus.DISPATCHED
                                    && locked.lastDispatchedAt().equals(permit)
                            ? fail(unitOfWork, locked, PayoutFailureReason.PROVIDER_UNAVAILABLE,
                                    correlation, "send")
                            : new Applied(locked.status(), false);
            case INDETERMINATE -> unknown(unitOfWork, locked, correlation, "send");
        };
    }

    /**
     * Applies the answer to the resolution sweep's query by our reference.
     *
     * @param neverReceivedBound an unrecognised reference is concluded {@code NEVER_RECEIVED}
     *     only when the payout's latest send permit is at or before this instant
     */
    public Applied applyQueryAnswer(
            Connection unitOfWork,
            MerchantPayout locked,
            PayoutQueryAnswer answer,
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
                    complete(unitOfWork, locked, answer.providerReference().orElseThrow(),
                            correlation, "query");
            case DECLINED ->
                    fail(unitOfWork, locked, PayoutFailureReason.DECLINED, correlation, "query");
            case UNRECOGNISED ->
                    locked.sendPermitAtOrBefore(neverReceivedBound)
                            ? fail(unitOfWork, locked, PayoutFailureReason.NEVER_RECEIVED,
                                    correlation, "query")
                            // A send permit younger than the bound: a re-send may be in flight
                            // right now, so absence proves nothing yet (ADR-0057 §4).
                            : new Applied(locked.status(), false);
            case INDETERMINATE -> unknown(unitOfWork, locked, correlation, "query");
        };
    }

    private Applied complete(
            Connection unitOfWork,
            MerchantPayout locked,
            PayoutProviderReference theirs,
            Correlation correlation,
            String resolver) {
        Instant now = Instant.now(clock);
        MerchantPayout completed = locked.complete(theirs);
        requireLanded(unitOfWork, locked, completed, now);
        requireReleased(unitOfWork, locked);

        LedgerAccount payable =
                accounts.findOwned(
                                unitOfWork,
                                locked.merchantId().value(),
                                AccountPurpose.MERCHANT_PAYABLE,
                                locked.amount().currency())
                        .orElseThrow(
                                () ->
                                        new MerchantStorageException(
                                                "a payout's merchant has no payable in its"
                                                        + " currency"));
        LedgerAccount clearing =
                chart.resolve(unitOfWork, AccountPurpose.PAYOUT_CLEARING, locked.amount().currency());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // Only in the acting branch: the posting key's fingerprint carries the dates, so a
        // replay on a later day would conflict rather than converge - and the conditional
        // transition above already made this call the only one that can reach here.
        postings.post(
                unitOfWork,
                new PostingCommand(
                        POSTING_KEY_PREFIX + locked.id().value(),
                        today,
                        today,
                        locked.id().value().toString(),
                        List.of(
                                new JournalLine(payable.id(), Direction.DEBIT, locked.amount()),
                                new JournalLine(
                                        clearing.id(), Direction.CREDIT, locked.amount()))));
        announce(unitOfWork, completed, COMPLETED_EVENT_TYPE, correlation, now);
        record(unitOfWork, completed, correlation, now, resolver);
        return new Applied(MerchantPayoutStatus.COMPLETED, true);
    }

    private Applied fail(
            Connection unitOfWork,
            MerchantPayout locked,
            PayoutFailureReason why,
            Correlation correlation,
            String resolver) {
        Instant now = Instant.now(clock);
        MerchantPayout failed = locked.fail(why);
        requireLanded(unitOfWork, locked, failed, now);
        requireReleased(unitOfWork, locked);
        announce(unitOfWork, failed, FAILED_EVENT_TYPE, correlation, now);
        record(unitOfWork, failed, correlation, now, resolver);
        return new Applied(MerchantPayoutStatus.FAILED, true);
    }

    private Applied unknown(
            Connection unitOfWork, MerchantPayout locked, Correlation correlation, String resolver) {
        if (locked.status() != MerchantPayoutStatus.DISPATCHED) {
            // Already UNKNOWN: another ambiguous answer teaches nothing new.
            return new Applied(locked.status(), false);
        }
        Instant now = Instant.now(clock);
        MerchantPayout unknown = locked.outcomeUnknown();
        requireLanded(unitOfWork, locked, unknown, now);
        // The hold STANDS, and nothing is published: UNKNOWN is not terminal.
        record(unitOfWork, unknown, correlation, now, resolver);
        return new Applied(MerchantPayoutStatus.UNKNOWN, true);
    }

    private void requireLanded(
            Connection unitOfWork, MerchantPayout before, MerchantPayout after, Instant at) {
        if (!payouts.transition(unitOfWork, before, after, at)) {
            // The caller locked the row, so a lost count is unreachable in this flow; refusing
            // loudly beats guessing if an unknown writer proves otherwise (INV-CON-01).
            throw new MerchantStorageException(
                    "a locked payout's conditional move found another writer's state");
        }
    }

    private void requireReleased(Connection unitOfWork, MerchantPayout payout) {
        Optional<HoldService.Release> release = holds.release(unitOfWork, payout.holdId());
        if (release.isEmpty() || !release.get().released()) {
            // The acting transition is the only path here, so the hold must have been standing:
            // a missing or already-released hold means some other writer moved this payout's
            // money, and posting now would move it twice.
            throw new MerchantStorageException(
                    "a resolving payout's hold was not standing to release");
        }
    }

    private void announce(
            Connection unitOfWork,
            MerchantPayout payout,
            String eventType,
            Correlation correlation,
            Instant now) {
        EventPayload payload =
                EventPayload.of()
                        .with("status", payout.status().name())
                        .with("merchantId", payout.merchantId().value().toString())
                        .with("destinationId", payout.destinationId().value().toString());
        if (payout.failureReason().isPresent()) {
            payload = payload.with("failureReason", payout.failureReason().get().name());
        }
        // Identifiers and enumerated names only - never the amount, never the destination's
        // reference (PHASE_6_PLAN.md section 10, INV-AUD-02).
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        payout.id(),
                        AGGREGATE_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        causeOf(correlation)),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** The dispatch's own fact: the payout committed durably before its outcome exists. */
    void announceInitiated(
            Connection unitOfWork, MerchantPayout payout, Correlation correlation, Instant now) {
        announce(unitOfWork, payout, INITIATED_EVENT_TYPE, correlation, now);
    }

    private void record(
            Connection unitOfWork,
            MerchantPayout after,
            Correlation correlation,
            Instant now,
            String resolver) {
        // The platform applies every outcome - an enumerated enterSystem() site at every caller.
        Actor platform = SecurityContext.require();
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        MerchantAuditAction.MERCHANT_PAYOUT_OUTCOME_APPLIED,
                        MerchantPayouts.TARGET_TYPE,
                        after.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "payout=" + after.id()
                                        + ", merchant=" + after.merchantId()
                                        + ", status=" + after.status()
                                        + after.failureReason().map(r -> ", failure=" + r).orElse("")
                                        + ", resolver=" + resolver)));
    }

    static CausationId causeOf(Correlation correlation) {
        return correlation
                .cause()
                .orElseGet(() -> CausationId.of(correlation.correlationId().value()));
    }
}
