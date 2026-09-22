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
import com.finapp.sharedkernel.correlation.CausationId;
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
    static final String FEE_RETURNED_EVENT_TYPE = "merchant.FeeReturned";
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
     * The lines a refund of a merchant-bound payment posts, or empty when the payment is
     * nobody's merchant's (`P6-TSK-014`, ADR-0050's consequences).
     *
     * <h2>The gross comes out of the payable; the fee follows the PINNED policy</h2>
     *
     * <pre>
     *   RETAINED:  DR MERCHANT_PAYABLE refunded / CR SETTLEMENT_CLEARING refunded
     *   RETURNED:  ... and DR FEE_REVENUE returned / CR MERCHANT_PAYABLE returned
     * </pre>
     *
     * <p>Under {@code RETAINED} the merchant ends a fully refunded capture <strong>down by the
     * fee</strong>, and that is the policy working rather than a defect: they received
     * gross minus fee and returned the gross, so the platform keeps what it charged for
     * processing a payment that did happen. Under {@code RETURNED} a fully refunded capture
     * leaves the payable at <strong>exactly zero</strong> — the reversal is complete.
     *
     * <h2>Priced by the pin, never by today's schedule</h2>
     *
     * <p>The version is resolved from {@code payment_fee_pin}, exactly as the capture resolved
     * it, so a schedule version created between the capture and the refund prices neither. A
     * refund is priced by what the payment was priced by ({@code INV-MER-03}) — and
     * {@link FeeCalculation#returnedFee} checks that the assessment it is handed came from that
     * version rather than trusting this method to have looked it up correctly.
     *
     * <h2>The two checked assumptions</h2>
     *
     * <p>The merchant's payable must exist, and the account the refund debits must BE that
     * payable — {@link #compose}'s second and third assumptions at the reversal, for the same
     * reason: a refund taking money out of the wrong account is the defect this method exists
     * to make impossible. Both throw, failing the whole refund transaction, because returning
     * the gross out of somewhere else and calling the refund done is the worse answer.
     *
     * @param intentRef the refunded payment's intent, by value
     * @param debit the account the refund was going to take the money from
     * @param refunded this refund's amount
     * @param refundedBefore what had already been refunded and completed, excluding this one
     * @throws MerchantSettlementException if either assumption breaks
     */
    public Optional<List<JournalLine>> refund(
            Connection unitOfWork,
            UUID intentRef,
            LedgerAccountId clearing,
            LedgerAccountId debit,
            Money refunded,
            Money refundedBefore,
            Correlation correlation,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(intentRef, "intentRef must not be null");

        Optional<PaymentFeePin> found = pins.findFor(unitOfWork, intentRef);
        if (found.isEmpty()) {
            // A wallet top-up's refund, as seen from here. Empty keeps Phase 5's path
            // byte-identical: no pin, no fee treatment, no change.
            return Optional.empty();
        }
        PaymentFeePin pin = found.get();

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
                                                        + " means the pin outlived its"
                                                        + " schema"));

        LedgerAccount payable =
                ledgerAccounts
                        .findOwned(
                                unitOfWork,
                                pin.merchantId().value(),
                                AccountPurpose.MERCHANT_PAYABLE,
                                refunded.currency())
                        .orElseThrow(
                                () ->
                                        new MerchantSettlementException(
                                                "merchant " + pin.merchantId() + " has no "
                                                        + refunded.currency()
                                                        + " payable account; the capture this"
                                                        + " refund reverses could not have"
                                                        + " posted without one"));
        if (!payable.id().equals(debit)) {
            throw new MerchantSettlementException(
                    "the refund would take " + refunded + " from " + debit + " but merchant "
                            + pin.merchantId() + "'s payable is " + payable.id()
                            + "; the intent and its fee pin disagree about whose payment this"
                            + " is");
        }

        // The gross out of the payable - the capture's own inverse, whatever the policy says
        // about the fee.
        List<JournalLine> lines =
                new java.util.ArrayList<>(
                        List.of(
                                new JournalLine(payable.id(), Direction.DEBIT, refunded),
                                new JournalLine(clearing, Direction.CREDIT, refunded)));

        if (version.refundFeePolicy() == RefundFeePolicy.RETURNED) {
            FeeAssessment assessment = FeeCalculation.assess(pin.gross(), version);
            Money returned =
                    FeeCalculation.returnedFee(assessment, version, refundedBefore, refunded);
            if (!returned.isZero()) {
                // Zero happens legitimately: a free schedule, or a partial refund so small
                // that its share rounds to nothing this time and to a whole unit on a later
                // one - which the CUMULATIVE arithmetic handles without stranding anything.
                // A zero line asserts nothing and the ledger refuses one anyway.
                LedgerAccount feeRevenue =
                        chart.resolve(unitOfWork, AccountPurpose.FEE_REVENUE, refunded.currency());
                lines.add(new JournalLine(feeRevenue.id(), Direction.DEBIT, returned));
                lines.add(new JournalLine(payable.id(), Direction.CREDIT, returned));
                announceReturn(unitOfWork, pin, returned, refunded, correlation, at);
            }
        }
        return Optional.of(List.copyOf(lines));
    }

    /**
     * {@code merchant.FeeReturned}, on the refund's own connection.
     *
     * <p><strong>Announced only when a fee was actually returned.</strong> A {@code RETAINED}
     * policy emits nothing, because an event saying "no fee came back" is a message no
     * consumer can act on and a contract nobody could later change.
     */
    private void announceReturn(
            Connection unitOfWork,
            PaymentFeePin pin,
            Money returned,
            Money refunded,
            Correlation correlation,
            Instant at) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        FEE_RETURNED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        pin.merchantId(),
                        AGGREGATE_TYPE,
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        causeOf(correlation)),
                EventPayload.of()
                        .with("paymentIntentId", pin.paymentIntentRef().toString())
                        // INV-HIST-04 on the wire, as FeeAssessed carries it: the version alone
                        // lets a consumer reproduce both the original fee and this share.
                        .with("feeScheduleVersionId", pin.versionId().value().toString())
                        .with("refundedMinor", String.valueOf(refunded.minorUnits()))
                        .with("feeReturnedMinor", String.valueOf(returned.minorUnits()))
                        .with("currency", returned.currency().code())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * The causation identifier this module's events carry, <strong>resolving a root flow to
     * itself</strong> ({@code PaymentCreation.resolvedCorrelation}'s idiom).
     *
     * <p>Both announcements here used to call {@code cause().orElseThrow()}, which is true of
     * every caller that exists — a request or a resolver, both already caused — and is a
     * landmine for the next one. `P6-TSK-008` hit exactly this in the expiry sweeper, whose
     * flow is a root and has no cause by construction. {@code INV-EVT-03} makes all ten
     * envelope fields mandatory, so the honest answer for a root is that it causes itself,
     * and stating it once here is cheaper than a caller discovering it.
     */
    private static CausationId causeOf(Correlation correlation) {
        return correlation
                .cause()
                .orElseGet(() -> CausationId.of(correlation.correlationId().value()));
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
                        causeOf(correlation)),
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
