package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The money of a dispute (`P7-TSK-013`, ADR-0061 §3–§5): the combined bound, every stage's
 * posting, the PSP's dispute fee, and the re-attribution that keeps the bound true when a
 * counted refund fails or a standing chargeback is won.
 *
 * <h2>The external fact first, then the attribution — two entries per financial stage</h2>
 *
 * <pre>
 *   CHARGED_BACK  dispute-chargeback:&lt;id&gt;   DR CHARGEBACK_RECOVERABLE D / CR rail clearing D
 *                 dispute-attribution:&lt;id&gt;  DR counterparty S / CR CHARGEBACK_RECOVERABLE S
 *   WON           dispute-won:&lt;id&gt;          DR rail clearing D / CR CHARGEBACK_RECOVERABLE D
 *                 dispute-restoration:&lt;id&gt;  DR CHARGEBACK_RECOVERABLE S / CR counterparty S
 *   LOST/ACCEPTED dispute-loss:&lt;id&gt;         DR DISPUTE_COSTS E / CR CHARGEBACK_RECOVERABLE E
 *   a fee         dispute-fee:&lt;id&gt;          DR DISPUTE_COSTS F / CR rail clearing F
 * </pre>
 *
 * <p>Per account the chargeback's pair is exactly ADR-0061 §4's table — the rail's clearing
 * down by what the network took ({@code INV-RAIL-04}: the stored rail's declared position,
 * never another's), the counterparty down by its share, the recoverable holding the rest — and
 * the win's pair is its exact inverse ({@code INV-DSP-02}). Two entries rather than one, a
 * recorded design correction: the first entry is the network's act and never varies with who
 * bears it; the counterparty's line always faces {@code CHARGEBACK_RECOVERABLE}, so a merchant's
 * payable drill-down can NAME a chargeback in the ledger's own vocabulary (a one-entry
 * chargeback with no excess would be line-for-line a retained refund — `P7-TSK-010`'s mislabel
 * class); and {@link DisputeComposition} composes only the counterparty's part.
 *
 * <h2>The bound, judged under the lock both money paths take</h2>
 *
 * <p>{@link #split} reads captured, non-failed refunds and the chargebacks already standing
 * under the attempt row lock the caller holds — the lock a refund's dispatch takes too — and
 * attributes {@code min(amount, what remains)} ({@code INV-DSP-01}). Every posting runs once per
 * stage because its caller's conditional transition fires once, and its key makes a replay
 * structurally unable to post twice ({@code INV-IDEM-04}).
 *
 * <h2>Freed headroom goes back to the excess, oldest dispute first</h2>
 *
 * <p>A chargeback's excess is value the network took that the platform had already returned
 * — or had not yet credited. Three events make part of it false, and they are exactly the three
 * ways the headroom {@code captured − non-failed refunds − standing attributions} can grow: a
 * CAPTURE LANDS on an attempt a chargeback was already stated against (it was judged against
 * nothing captured — the gate's find); a refund the chargeback counted as non-failed FAILS (the
 * money never went back — ADR-0061 §3's rule); or another standing chargeback is WON (its
 * attribution is reversed — a win delivered after a second cycle's chargeback, out of order).
 * Each time the freed amount is re-attributed to the counterparty, under the attempt lock, from
 * the recoverable (or, after a loss wrote the excess off, from {@code DISPUTE_COSTS}), keyed
 * {@code dispute-reattribution:<dispute>:<cause>} — so at every commit the split is the one a
 * chargeback arriving now would take: the counterparty bears no more than it was credited, and
 * the recoverable holds exactly the value the network took twice or took uncredited. That is
 * also why no statement ever waits for a capture to resolve: it is recorded against what has
 * been captured, and corrected when the capture lands.
 *
 * <h2>A counterparty that can take no posting is parked, never refused</h2>
 *
 * <p>ADR-0061 §5: the network has already taken the money, so a closed wallet never fails the
 * chargeback — its share stays in the recoverable, recorded as parked, and recovering it is an
 * operator's act. Postability is read {@code FOR SHARE} and never followed by a
 * {@code FOR UPDATE} on the same row in this code (the `P7-TSK-011` SHARE-then-UPDATE deadlock
 * class): the refund failure path's hold release takes its {@code FOR UPDATE} before anything
 * here runs.
 */
@Slf4j
@RequiredArgsConstructor
public final class ChargebackAccounting {

    static final String CHARGEBACK_KEY = "dispute-chargeback:";
    static final String ATTRIBUTION_KEY = "dispute-attribution:";
    static final String WON_KEY = "dispute-won:";
    static final String RESTORATION_KEY = "dispute-restoration:";
    static final String LOSS_KEY = "dispute-loss:";
    static final String FEE_KEY = "dispute-fee:";
    static final String REATTRIBUTION_KEY = "dispute-reattribution:";

    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentRails rails;
    @NonNull private final DisputeComposition<Connection> composition;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Whether the attempt's rail declares chargebacks — the declaration, never a name
     * ({@code INV-RAIL-01}). */
    public boolean disputable(PaymentAttempt attempt) {
        return rails.capabilitiesOf(attempt.rail()).disputes()
                == RailCapabilities.DisputeModel.CARD_SCHEME_CHARGEBACKS;
    }

    /**
     * The split a chargeback of {@code amount} takes on {@code locked} NOW: {@code min(amount,
     * captured − non-failed refunds − standing attributions)} to the counterparty — posted, or
     * parked when its account takes no postings — and the rest as excess. The caller holds the
     * attempt's row lock, so every term is current (lock-then-look); an attempt that never
     * captured credited nobody, so its whole chargeback is excess.
     */
    public ChargebackSplit split(
            Connection unitOfWork, PaymentAttempt locked, PaymentIntent intent, Money amount) {
        Objects.requireNonNull(locked, "locked must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        CurrencyCode currency = amount.currency();
        Money captured =
                locked.status() == PaymentAttemptStatus.CAPTURED
                        ? locked.capturedAmount()
                        : Money.ofPersisted(0L, currency, amount.scale());
        Money headroom =
                captured.minus(refunds.sumNonFailedFor(unitOfWork, locked.id(), currency))
                        .minus(disputes.attributedStanding(unitOfWork, locked.id(), currency));
        return ChargebackSplit.of(
                amount, headroom, postable(unitOfWork, intent.creditAccount()));
    }

    /** The bound's chargeback term for a refund's dispatch — valid under the attempt lock. */
    public Money attributedStanding(
            Connection unitOfWork, PaymentAttemptId attempt, CurrencyCode currency) {
        return disputes.attributedStanding(unitOfWork, attempt, currency);
    }

    /**
     * The postings for the stage {@code dispute} just entered — once per stage applied, inside
     * the delivery's transaction, after its conditional transition won. {@code INQUIRY},
     * {@code REPRESENTED} and {@code CLOSED} move no money (ADR-0061 §2).
     */
    public void entered(
            Connection unitOfWork,
            Dispute dispute,
            PaymentAttempt attempt,
            PaymentIntent intent,
            Correlation correlation,
            Instant now) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        switch (dispute.stage()) {
            case CHARGED_BACK -> {
                ChargebackSplit split = dispute.split().orElseThrow();
                counterpartyFirst(unitOfWork, intent);
                LedgerAccountId recoverable = recoverableAccount(unitOfWork, split.amount());
                // THE EXTERNAL FACT: the rail's clearing moves by exactly what the network
                // took, whoever bears it.
                post(unitOfWork, CHARGEBACK_KEY + dispute.id().value(), dispute,
                        List.of(
                                new JournalLine(recoverable, Direction.DEBIT, split.amount()),
                                new JournalLine(
                                        clearingAccount(unitOfWork, attempt, split.amount()),
                                        Direction.CREDIT,
                                        split.amount())));
                // THE ATTRIBUTION: the counterparty's share, composed by the flow that knows
                // whose money its account holds. Nothing when it bears nothing, or when its
                // share is parked (the recoverable already holds it).
                if (split.counterpartyShare().isPositive()) {
                    post(unitOfWork, ATTRIBUTION_KEY + dispute.id().value(), dispute,
                            composition.attribute(
                                    unitOfWork,
                                    attribution(dispute, intent, recoverable,
                                            split.counterpartyShare(), correlation, now)));
                }
            }
            case WON -> {
                ChargebackSplit split = dispute.split().orElseThrow();
                counterpartyFirst(unitOfWork, intent);
                LedgerAccountId recoverable = recoverableAccount(unitOfWork, split.amount());
                post(unitOfWork, WON_KEY + dispute.id().value(), dispute,
                        List.of(
                                new JournalLine(
                                        clearingAccount(unitOfWork, attempt, split.amount()),
                                        Direction.DEBIT,
                                        split.amount()),
                                new JournalLine(recoverable, Direction.CREDIT, split.amount())));
                if (split.counterpartyShare().isPositive()) {
                    post(unitOfWork, RESTORATION_KEY + dispute.id().value(), dispute,
                            composition.restore(
                                    unitOfWork,
                                    attribution(dispute, intent, recoverable,
                                            split.counterpartyShare(), correlation, now)));
                }
                // The win freed what this dispute attributed: a sibling chargeback whose split
                // counted it keeps no excess the counterparty now has room for.
                reattributeFreed(unitOfWork, attempt, intent, split.attributed(),
                        dispute.id().value(), "dispute", correlation, now);
            }
            case LOST, ACCEPTED -> {
                ChargebackSplit split = dispute.split().orElseThrow();
                // Only the excess is written off: the counterparty's share stands as its debt
                // (INV-MER-07 amended), and a parked share stays recoverable from it.
                if (split.excess().isPositive()) {
                    post(unitOfWork, LOSS_KEY + dispute.id().value(), dispute,
                            List.of(
                                    new JournalLine(
                                            costsAccount(unitOfWork, split.amount()),
                                            Direction.DEBIT,
                                            split.excess()),
                                    new JournalLine(
                                            recoverableAccount(unitOfWork, split.amount()),
                                            Direction.CREDIT,
                                            split.excess())));
                }
            }
            case INQUIRY, REPRESENTED, CLOSED -> {
                // No money moves at these stages (ADR-0061 section 2).
            }
        }
    }

    /**
     * The PSP's dispute fee, recorded on {@code dispute} in this transaction: posted once as a
     * platform cost against the rail's clearing (the PSP nets it from settlement), audited.
     */
    public void feeRecorded(
            Connection unitOfWork,
            Dispute dispute,
            PaymentAttempt attempt,
            Correlation correlation,
            Instant now) {
        Money fee = dispute.fee().orElseThrow();
        post(unitOfWork, FEE_KEY + dispute.id().value(), dispute,
                List.of(
                        new JournalLine(costsAccount(unitOfWork, fee), Direction.DEBIT, fee),
                        new JournalLine(
                                clearingAccount(unitOfWork, attempt, fee), Direction.CREDIT, fee)));
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        now,
                        PaymentsAuditAction.DISPUTE_FEE_RECORDED,
                        DisputeNotifications.TARGET_TYPE,
                        dispute.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("dispute=" + dispute.id() + ", attempt=" + attempt.id())));
    }

    /**
     * The attempt a refund failure must hold before its hold is released, when its rail
     * declares chargebacks (`P7-TSK-013`): locked FIRST — attempt, then account, the order every
     * money path keeps — so a chargeback racing this failure either committed its split before
     * (and this failure sees it) or waits and counts this refund as failed.
     */
    public Optional<PaymentAttempt> lockIfDisputable(
            Connection unitOfWork, PaymentAttemptId attemptId) {
        PaymentAttempt attempt =
                attempts.findById(unitOfWork, attemptId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a refund's attempt exists: V004's foreign key"
                                                        + " holds it"));
        if (!disputable(attempt)) {
            return Optional.empty();
        }
        return Optional.of(
                attempts.lockById(unitOfWork, attemptId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "attempt " + attemptId + " read and then"
                                                        + " vanished")));
    }

    /**
     * A capture LANDED on an attempt some chargeback was already stated against (the gate's
     * find, `P7-TSK-013`): a chargeback recorded while the capture was still being resolved — or
     * before it was even dispatched — was judged against a captured amount of nothing, so its
     * whole amount rests as excess; now the capture has credited the counterparty, the
     * counterparty has headroom again, and up to the captured amount of the standing excess comes
     * back to it — exactly the split a chargeback arriving now would have taken. Called inside the
     * capture's own transaction, after its conditional transition won (the attempt row is that
     * transaction's) and after the capture's posting, so the trail reads cause before effect.
     */
    public void captureLanded(
            Connection unitOfWork,
            PaymentAttemptId attemptId,
            PaymentIntentId intentId,
            Money captured,
            Correlation correlation,
            Instant now) {
        PaymentAttempt attempt =
                attempts.findById(unitOfWork, attemptId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a captured attempt reads back in its own"
                                                        + " transaction"));
        if (!disputable(attempt)) {
            return;
        }
        PaymentIntent intent =
                intents.findById(unitOfWork, intentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an attempt row's intent exists: V003's foreign"
                                                        + " key holds it"));
        reattributeFreed(unitOfWork, attempt, intent, captured, attemptId.value(), "capture",
                correlation, now);
    }

    /**
     * A refund the chargebacks may have counted FAILED (ADR-0061 §3): its amount never went back,
     * so up to that much of the standing chargebacks' excess returns to the counterparty, oldest
     * dispute first. The caller holds {@code locked} (from {@link #lockIfDisputable}) and has
     * released the refund's hold.
     */
    public void refundFailed(
            Connection unitOfWork,
            PaymentAttempt locked,
            Refund refund,
            PaymentIntentId intentId,
            Correlation correlation,
            Instant now) {
        PaymentIntent intent =
                intents.findById(unitOfWork, intentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a refund's intent exists: V003's foreign key"
                                                        + " holds its attempt to it"));
        reattributeFreed(unitOfWork, locked, intent, refund.amount(), refund.id().value(),
                "refund", correlation, now);
    }

    // -----------------------------------------------------------------

    /**
     * {@code freed} of headroom returns to the standing chargebacks' excess, oldest first — each
     * moved share its own conditional write, entry and record.
     */
    private void reattributeFreed(
            Connection unitOfWork,
            PaymentAttempt attempt,
            PaymentIntent intent,
            Money freed,
            UUID cause,
            String causeKind,
            Correlation correlation,
            Instant now) {
        if (!freed.isPositive()) {
            return;
        }
        List<Dispute> standing = disputes.standingOn(unitOfWork, attempt.id());
        if (standing.stream().noneMatch(dispute -> dispute.split().orElseThrow().excess()
                .isPositive())) {
            return;
        }
        boolean postable = postable(unitOfWork, intent.creditAccount());
        Money remaining = freed;
        for (Dispute before : standing) {
            ChargebackSplit split = before.split().orElseThrow();
            if (!remaining.isPositive()) {
                break;
            }
            if (!split.excess().isPositive()) {
                continue;
            }
            Money moved = remaining.compareTo(split.excess()) < 0 ? remaining : split.excess();
            Dispute after = before.reattributed(moved, postable);
            if (!disputes.reattribute(unitOfWork, before, after)) {
                // The row is locked by standingOn in this transaction: a lost conditional here
                // is a wiring fault, never a race.
                throw new IllegalStateException(
                        "dispute " + before.id() + " moved under its own lock");
            }
            List<JournalLine> lines =
                    reattributionLines(unitOfWork, before, intent, moved, postable, correlation,
                            now);
            if (!lines.isEmpty()) {
                post(unitOfWork,
                        REATTRIBUTION_KEY + before.id().value() + ":" + cause,
                        before,
                        lines);
            }
            audit.append(
                    unitOfWork,
                    new AuditRecord(
                            AuditId.next(ids),
                            SecurityContext.require(),
                            now,
                            PaymentsAuditAction.CHARGEBACK_REATTRIBUTED,
                            DisputeNotifications.TARGET_TYPE,
                            before.id().value().toString(),
                            Optional.empty(),
                            AuditOutcome.SUCCEEDED,
                            correlation.correlationId(),
                            Optional.of(
                                    "dispute=" + before.id()
                                            + ", attempt=" + attempt.id()
                                            + ", cause=" + causeKind + ":" + cause
                                            + ", to=" + (postable ? "COUNTERPARTY" : "PARKED"))));
            log.info(
                    "A chargeback's excess was re-attributed to the counterparty after a {}"
                            + " freed headroom (dispute {}, {} {}, INV-DSP-01)",
                    causeKind, before.id(), causeKind, cause);
            remaining = remaining.minus(moved);
        }
    }

    /**
     * Where a re-attributed share moves from and to: from the recoverable while the chargeback
     * is contested, from {@code DISPUTE_COSTS} once a loss wrote the excess off; to the
     * counterparty — or, when its account takes no postings, parked in the recoverable (no line
     * at all before a loss: the value already rests there).
     */
    private List<JournalLine> reattributionLines(
            Connection unitOfWork,
            Dispute dispute,
            PaymentIntent intent,
            Money moved,
            boolean postable,
            Correlation correlation,
            Instant now) {
        boolean writtenOff = dispute.stage().isTerminal();
        Money unit = dispute.split().orElseThrow().amount();
        LedgerAccountId source =
                writtenOff ? costsAccount(unitOfWork, unit) : recoverableAccount(unitOfWork, unit);
        if (postable) {
            return composition.attribute(
                    unitOfWork, attribution(dispute, intent, source, moved, correlation, now));
        }
        if (!writtenOff) {
            return List.of();
        }
        return List.of(
                new JournalLine(recoverableAccount(unitOfWork, unit), Direction.DEBIT, moved),
                new JournalLine(source, Direction.CREDIT, moved));
    }

    private ChargebackAttribution attribution(
            Dispute dispute,
            PaymentIntent intent,
            LedgerAccountId counterpart,
            Money amount,
            Correlation correlation,
            Instant now) {
        return new ChargebackAttribution(
                intent.id(),
                dispute.attemptId(),
                dispute.id(),
                intent.creditAccount(),
                counterpart,
                amount,
                correlation,
                now);
    }

    /**
     * The counterparty's account, share-locked BEFORE a stage's first posting (`P7-TST-001`'s
     * find). Every path that holds a counterparty's account {@code FOR UPDATE} — a hold placed or
     * released for a refund, a withdrawal, a wallet payment — takes that account before any balance
     * row, and every single-entry posting takes the account's key share at its line insert before
     * any balance row. A stage whose FIRST entry touches only the rail's clearing and the
     * recoverable takes those balance rows first and the counterparty after — the one order
     * nothing else takes: in the multi-rail storm a win's restoration, waiting on the counterparty
     * a refund of another payment held while that refund waited on the clearing's balance row the
     * win's first entry held, deadlocked ({@code 40P01}, a 500 at the card door). Taken first, the
     * counterparty is where the two meet, and one waits for the other before either holds a
     * balance row. Share, never upgraded: chargebacks on one account still run side by side. The
     * chargeback's own split already takes it ({@link #postable}); re-taking it in the same
     * transaction is a no-op that keeps the rule stated where the postings are.
     */
    private void counterpartyFirst(Connection unitOfWork, PaymentIntent intent) {
        ledgerAccounts.lockForShare(unitOfWork, intent.creditAccount());
    }

    /**
     * The platform's three dispute rows — the rail's clearing, the recoverable and the costs —
     * taken in the projection's own order BEFORE any dispute posting (the Phase 7 -&gt; 8
     * transition). Each entry locks its own rows in that order, but a delivery posting SEVERAL
     * entries does not: a loss (recoverable, costs) followed by a first-reported fee (costs,
     * clearing) reached back to the clearing, which sorts first and which every chargeback, win
     * and capture takes first - one deadlock ({@code 40P01}, a 500 at the card door) the review's
     * seeded-accounts rule did not cover. Taken here, every dispute delivery meets every other
     * writer of these rows at the clearing, before either holds anything past it. After
     * {@link #counterpartyFirst} where a stage takes it: the counterparty's ACCOUNT row before
     * any balance row stays the rule. Re-taking in the same transaction is a no-op.
     */
    private void platformRowsInOrder(Connection unitOfWork, Dispute dispute, Money inCurrency) {
        PaymentAttempt attempt =
                attempts.findById(unitOfWork, dispute.attemptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a dispute's attempt exists: V020's foreign key"
                                                        + " holds it"));
        postings.lockBalancesInOrder(
                unitOfWork,
                List.of(
                        clearingAccount(unitOfWork, attempt, inCurrency),
                        recoverableAccount(unitOfWork, inCurrency),
                        costsAccount(unitOfWork, inCurrency)));
    }

    /** Whether the counterparty's account takes postings now — share-locked, never upgraded. */
    private boolean postable(Connection unitOfWork, LedgerAccountId account) {
        return ledgerAccounts
                .lockForShare(unitOfWork, account)
                .map(row -> row.status() == LedgerAccountStatus.ACTIVE)
                .orElse(false);
    }

    /** The recoverable in the chargeback's currency — resolved per use, never cached. */
    public LedgerAccountId recoverableAccount(Connection unitOfWork, Money inCurrency) {
        return chart.resolve(
                        unitOfWork, AccountPurpose.CHARGEBACK_RECOVERABLE, inCurrency.currency())
                .id();
    }

    private LedgerAccountId costsAccount(Connection unitOfWork, Money inCurrency) {
        return chart.resolve(unitOfWork, AccountPurpose.DISPUTE_COSTS, inCurrency.currency())
                .id();
    }

    /**
     * The STORED rail's declared clearing position (`INV-RAIL-04`, ADR-0059 §4) — the card's
     * {@code SETTLEMENT_CLEARING} — read off the attempt, never off this instance's wiring.
     */
    private LedgerAccountId clearingAccount(
            Connection unitOfWork, PaymentAttempt attempt, Money inCurrency) {
        AccountPurpose purpose =
                rails.capabilitiesOf(attempt.rail())
                        .clearingPurpose()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "rail '" + attempt.rail().value() + "' declares"
                                                        + " chargebacks but no clearing"
                                                        + " position: its coherence rules"
                                                        + " refuse that pairing"));
        return chart.resolve(unitOfWork, purpose, inCurrency.currency()).id();
    }

    private void post(
            Connection unitOfWork, String key, Dispute dispute, List<JournalLine> lines) {
        platformRowsInOrder(unitOfWork, dispute, lines.get(0).amount());
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // The entry references the DISPUTE: every stage posting is attributable to it (the
        // backlog's audit line), and reconciliation joins dispute to entries by it.
        postings.post(
                unitOfWork,
                new PostingCommand(key, today, today, dispute.id().value().toString(), lines));
    }
}
