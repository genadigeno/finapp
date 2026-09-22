package com.finapp.merchant;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0050 §3's entry, composed (`P6-TSK-005`) — <strong>the phase's financial heart</strong>.
 *
 * <h2>One entry, four lines, and why it is one</h2>
 *
 * <pre>
 *   DR SETTLEMENT_CLEARING   gross    the platform's claim against the PSP
 *   CR MERCHANT_PAYABLE      gross    what the merchant is owed, in full
 *   DR MERCHANT_PAYABLE      fee      what the platform keeps, visibly
 *   CR FEE_REVENUE           fee      recognised at capture (ADR-0050 section 2)
 * </pre>
 *
 * <p>The books show the gross flow and the fee explicitly — a merchant statement can render
 * both — while the payable's <em>position</em> is net, which is all a payout ever pays
 * ({@code INV-MER-02}). One entry, so {@code INV-LED-01} holds trivially and
 * <strong>no crash can separate the capture from its fee</strong>: there is no state in which
 * the platform captured but forgot to charge, or charged without capturing.
 *
 * <h2>The fee is the PINNED version's, not today's</h2>
 *
 * <p>{@link PaymentFeePin} fixed the version when the price was agreed. A capture may arrive
 * days later; a schedule version created in between prices nothing that was already dispatched
 * ({@code INV-MER-03}, ADR-0050 §5). The arithmetic is {@link FeeCalculation}'s, unchanged and
 * pure: one rounding under the version's named policy, the net by subtraction, so the split
 * conserves the capture by construction ({@code INV-MER-04}) and the two lines above sum back
 * to the gross the customer paid.
 *
 * <h2>Three assumptions, checked rather than trusted</h2>
 *
 * <p>Each of these is unreachable today and each would be a silent mispricing if it ever
 * stopped being: the captured amount must be the pinned gross (ADR-0045 §4 — no partial
 * capture until its producer exists); the merchant's payable account must exist (onboarding
 * created it in the merchant's own transaction); and the account the capture was going to
 * credit must <em>be</em> that payable. All three throw, which fails the capture's whole
 * transaction — the honest outcome, because the alternative is posting two lines where four
 * were owed.
 *
 * <h2>What it writes</h2>
 *
 * <p>{@code merchant.FeeAssessed}, into the outbox, <strong>on the capture's own
 * connection</strong> — so the announcement commits with the entry it describes, and a
 * duplicate outcome from any resolver announces once for the same reason it posts once. It
 * writes no assessment table: the assessment <em>is</em> the entry, and the version that
 * produced it is the pin, so recomputing reproduces it without a second authority to drift
 * ({@code INV-MER-02}'s reasoning applied to a derived number).
 */
public final class MerchantSettlement {

    static final String EVENT_TYPE = "merchant.FeeAssessed";
    static final String PRODUCER = "merchant";
    static final int EVENT_VERSION = 1;
    static final String AGGREGATE_TYPE = "merchant";

    private final PaymentFeePinStore<Connection> pins;
    private final FeeScheduleStore<Connection> schedules;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final ChartOfAccounts<Connection> chart;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;

    public MerchantSettlement(
            PaymentFeePinStore<Connection> pins,
            FeeScheduleStore<Connection> schedules,
            LedgerAccountStore<Connection> ledgerAccounts,
            ChartOfAccounts<Connection> chart,
            OutboxWriter<Connection> outbox,
            IdGenerator ids) {
        this.pins = Objects.requireNonNull(pins, "pins must not be null");
        this.schedules = Objects.requireNonNull(schedules, "schedules must not be null");
        this.ledgerAccounts =
                Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    /**
     * The four lines this capture posts, or empty when the payment is nobody's merchant's.
     *
     * <p>Empty is how a wallet top-up looks from here, and it is the answer that keeps Phase
     * 5's path byte-identical: no pin, no fee, no change.
     *
     * @param intentRef the captured payment's intent, by value
     * @param credit the account the capture was going to credit — verified to be the pinned
     *     merchant's payable, because a capture crediting the wrong account is the defect this
     *     method exists to make impossible
     * @throws MerchantSettlementException if any of the three assumptions above breaks
     */
    public Optional<List<JournalLine>> settle(
            Connection unitOfWork,
            UUID intentRef,
            LedgerAccountId clearing,
            LedgerAccountId credit,
            Money captured,
            Correlation correlation,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(intentRef, "intentRef must not be null");

        Optional<PaymentFeePin> found = pins.findFor(unitOfWork, intentRef);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PaymentFeePin pin = found.get();

        // ASSUMPTION 1: the capture is the promise, in full. Unreachable today - and the day
        // partial capture arrives, this refuses rather than prices an amount nobody agreed.
        if (!captured.equals(pin.gross())) {
            throw new MerchantSettlementException(
                    "a merchant-bound capture of " + captured + " does not match the "
                            + pin.gross() + " its fee was pinned against (ADR-0045 section 4:"
                            + " a capture is the authorized promise in full)");
        }

        FeeScheduleVersion version =
                schedules
                        .findVersion(unitOfWork, pin.versionId())
                        .orElseThrow(
                                () ->
                                        new MerchantSettlementException(
                                                "the pinned fee schedule version "
                                                        + pin.versionId()
                                                        + " is not there; a pinned row is"
                                                        + " immutable and undeletable, so this"
                                                        + " means the pin outlived its schema"));

        // ASSUMPTION 2: the merchant has books. Onboarding opened them in the merchant's own
        // transaction (INV-MER-02), so a merchant without a payable cannot have been onboarded.
        LedgerAccount payable =
                ledgerAccounts
                        .findOwned(
                                unitOfWork,
                                pin.merchantId().value(),
                                AccountPurpose.MERCHANT_PAYABLE,
                                captured.currency())
                        .orElseThrow(
                                () ->
                                        new MerchantSettlementException(
                                                "merchant " + pin.merchantId() + " has no "
                                                        + captured.currency()
                                                        + " payable account; onboarding opens"
                                                        + " one in the same transaction that"
                                                        + " creates the merchant"));

        // ASSUMPTION 3: the capture was already going to credit that payable. The intent
        // records the account its capture credits; for a merchant payment that account IS the
        // payable, and a mismatch means the intent and the pin disagree about whose money
        // this is - the one disagreement that must never be resolved by picking a side.
        if (!payable.id().equals(credit)) {
            throw new MerchantSettlementException(
                    "the capture would credit " + credit + " but merchant "
                            + pin.merchantId() + "'s payable is " + payable.id()
                            + "; the intent and its fee pin disagree about whose payment this"
                            + " is");
        }

        FeeAssessment assessment = FeeCalculation.assess(captured, version);
        announce(unitOfWork, pin, assessment, correlation, at);

        // DR clearing gross / CR payable gross / DR payable fee / CR fee revenue fee.
        // Balanced by construction: debits are gross + fee and so are credits, whatever the
        // fee is - PostingService refuses anything else (INV-LED-01), the third rank.
        List<JournalLine> lines =
                new java.util.ArrayList<>(
                        List.of(
                                new JournalLine(clearing, Direction.DEBIT, assessment.gross()),
                                new JournalLine(
                                        payable.id(), Direction.CREDIT, assessment.gross())));
        if (!assessment.fee().isZero()) {
            // A free schedule posts TWO lines, not two more of zero: a zero line asserts
            // nothing, and the ledger's own amount constraint refuses one anyway. The entry
            // still says the truth - this capture cost the merchant nothing.
            LedgerAccount feeRevenue =
                    chart.resolve(unitOfWork, AccountPurpose.FEE_REVENUE, captured.currency());
            lines.add(new JournalLine(payable.id(), Direction.DEBIT, assessment.fee()));
            lines.add(new JournalLine(feeRevenue.id(), Direction.CREDIT, assessment.fee()));
        }
        return Optional.of(List.copyOf(lines));
    }

    /**
     * Records the price this payment will be charged at. Commits with the intent it prices, or
     * neither exists.
     *
     * <h2>A duplicate converges; a DIFFERENT price does not (`P6-TSK-007`)</h2>
     *
     * <p>The caller that creates the intent is keyed, so a retried creation converges on one
     * intent -- and then arrives <em>here</em> a second time, carrying the same decision again.
     * That is the ordinary shape of a retry on this platform, not an error: ten instances
     * racing to complete one checkout all reach this method with the same merchant, the same
     * version and the same gross, and the primary key lets exactly one of them write. The
     * other nine are <strong>converged</strong>, because a second record of a decision already
     * made is nothing to record.
     *
     * <p>What is emphatically not converged is a second attempt naming a <em>different</em>
     * price. That is a payment being repriced after it was agreed, which is the whole of what
     * {@code INV-MER-03} forbids, and it throws -- failing the caller's transaction rather than
     * silently keeping whichever price happened to be written first. The distinction is
     * {@link PaymentFeePin#pricesTheSameAs}'s, and it deliberately ignores who pinned and when.
     *
     * <p>This is the fourth checked assumption, and the only one reachable today: the three in
     * {@link #compose} guard a capture, and this one guards the agreement the capture will be
     * settled against.
     *
     * @throws MerchantSettlementException this intent is already pinned to a different price
     */
    public void pin(Connection unitOfWork, PaymentFeePin pin) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(pin, "pin must not be null");
        if (pins.insertIfAbsent(unitOfWork, pin)) {
            return;
        }
        PaymentFeePin standing =
                pins.findFor(unitOfWork, pin.paymentIntentRef())
                        .orElseThrow(
                                () ->
                                        new MerchantSettlementException(
                                                "payment " + pin.paymentIntentRef()
                                                        + " refused a second fee pin and then"
                                                        + " had none; the primary key and the"
                                                        + " row disagree about whether this"
                                                        + " payment is priced"));
        if (!standing.pricesTheSameAs(pin)) {
            throw new MerchantSettlementException(
                    "payment " + pin.paymentIntentRef() + " is pinned to version "
                            + standing.versionId() + " at " + standing.gross()
                            + " for merchant " + standing.merchantId()
                            + ", and this call would price it at " + pin.gross()
                            + " under version " + pin.versionId() + " for merchant "
                            + pin.merchantId()
                            + "; a payment has one agreed price (INV-MER-03)");
        }
    }

    /** The pin for an intent — the merchant statement's provenance read. */
    public Optional<PaymentFeePin> pinFor(Connection unitOfWork, UUID intentRef) {
        return pins.findFor(unitOfWork, intentRef);
    }

    private void announce(
            Connection unitOfWork,
            PaymentFeePin pin,
            FeeAssessment assessment,
            Correlation correlation,
            Instant at) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        pin.merchantId(),
                        AGGREGATE_TYPE,
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("paymentIntentId", pin.paymentIntentRef().toString())
                        // INV-HIST-04 on the wire: a consumer can reproduce the number below
                        // from the version alone, which is the whole promise.
                        .with("feeScheduleVersionId", pin.versionId().value().toString())
                        .with("grossMinor", String.valueOf(assessment.gross().minorUnits()))
                        .with("feeMinor", String.valueOf(assessment.fee().minorUnits()))
                        .with("netMinor", String.valueOf(assessment.net().minorUnits()))
                        .with("currency", assessment.gross().currency().code())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
