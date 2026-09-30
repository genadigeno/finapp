package com.finapp.reconciliation;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one seat of the person's resolution templates (`P8-TSK-015`, ADR-0071 §2) — pure, so a
 * proposal and its approval derive the SAME amount and lines from the same stored remainder,
 * and a changed remainder is detectable as a changed derivation.
 *
 * <p>Three rules, each ADR-0069's or ADR-0071's in code:
 *
 * <ul>
 *   <li><strong>Which kinds a type admits</strong> — ADR-0069 §2's per-type table, the one
 *       authority: every suspense-owning type admits {@code WRITE_OFF} (a DEBIT item) and
 *       {@code RECOGNISE_GAIN} (a CREDIT item, aged), save the exclusions its row names;
 *       {@code SETTLEMENT_MISMATCH} admits its posting kinds for {@code REMITTANCE_DIFFERS}
 *       alone, its statement causes closing only {@code EVIDENCED}.
 *   <li><strong>Where a kind applies</strong> — its lines decide: a write-off needs an INBOUND
 *       remainder or a DEBIT item, a transfer an OUTBOUND remainder or a CREDIT item, a gain a
 *       CREDIT item. A CREDIT item is never written off (that would be a gain) and a DEBIT
 *       item never recognised as one.
 *   <li><strong>The lines</strong> — derived from the subject's current remainder, never
 *       typed; the whole residual or nothing. The accounts a template names are closed: the
 *       subject's own position, {@code SUSPENSE_UNMATCHED}, the two P&amp;L accounts and a
 *       transfer's owned target.
 * </ul>
 */
public final class ResolutionTemplates {

    private ResolutionTemplates() {}

    /** What the break's subject holds for a resolution to dispose of. */
    public sealed interface Holding permits Holding.Remainder, Holding.Parked, Holding.Nothing {

        /** An expectation's open remainder in its own position. */
        record Remainder(
                UUID expectationId,
                ExpectationDirection direction,
                Money amount,
                UUID positionAccountId) implements Holding {

            public Remainder {
                Objects.requireNonNull(expectationId, "expectationId must not be null");
                Objects.requireNonNull(direction, "direction must not be null");
                Objects.requireNonNull(amount, "amount must not be null");
                Objects.requireNonNull(positionAccountId, "positionAccountId must not be null");
            }
        }

        /** The break's one open suspense item's unreleased value. */
        record Parked(
                UUID suspenseItemId,
                Optional<UUID> externalItemId,
                SuspenseSide side,
                Money amount,
                UUID positionAccountId) implements Holding {

            public Parked {
                Objects.requireNonNull(suspenseItemId, "suspenseItemId must not be null");
                Objects.requireNonNull(externalItemId, "externalItemId must not be null");
                Objects.requireNonNull(side, "side must not be null");
                Objects.requireNonNull(amount, "amount must not be null");
            }
        }

        /** Nothing in a position: a decision's or a run's subject, or an emptied one. */
        record Nothing() implements Holding {}
    }

    /** ADR-0069 §2's row: the person's kinds {@code type} admits ({@code cause}-refined). */
    public static Set<ResolutionKind> admittedKinds(BreakType type, BreakCause cause) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        return switch (type) {
            case MISSING_EXTERNAL ->
                    EnumSet.of(ResolutionKind.WRITE_OFF, ResolutionKind.TRANSFER_TO_ACCOUNT);
            case MISSING_INTERNAL, UNKNOWN_EXTERNAL, DUPLICATE_EXTERNAL, PROCESSING_ERROR ->
                    EnumSet.of(
                            ResolutionKind.TRANSFER_TO_ACCOUNT,
                            ResolutionKind.WRITE_OFF,
                            ResolutionKind.OFFSET_SUSPENSE,
                            ResolutionKind.RECOGNISE_GAIN);
            case AMOUNT_MISMATCH ->
                    EnumSet.of(
                            ResolutionKind.WRITE_OFF,
                            ResolutionKind.TRANSFER_TO_ACCOUNT,
                            ResolutionKind.RECOGNISE_GAIN);
            // A currency break is never income.
            case CURRENCY_MISMATCH ->
                    EnumSet.of(
                            ResolutionKind.TRANSFER_TO_ACCOUNT,
                            ResolutionKind.WRITE_OFF,
                            ResolutionKind.OFFSET_SUSPENSE);
            case FEE_MISMATCH, TIMING_DIFFERENCE -> EnumSet.of(ResolutionKind.ACKNOWLEDGE);
            case DUPLICATE_INTERNAL ->
                    EnumSet.of(ResolutionKind.ACKNOWLEDGE, ResolutionKind.WRITE_OFF);
            case AMBIGUOUS_MATCH ->
                    EnumSet.of(
                            ResolutionKind.MANUAL_MATCH,
                            ResolutionKind.TRANSFER_TO_ACCOUNT,
                            ResolutionKind.WRITE_OFF,
                            ResolutionKind.RECOGNISE_GAIN);
            // The value is a merchant's or a customer's: never the platform's gain.
            case REVERSAL_MISMATCH ->
                    EnumSet.of(
                            ResolutionKind.TRANSFER_TO_ACCOUNT,
                            ResolutionKind.OFFSET_SUSPENSE,
                            ResolutionKind.WRITE_OFF);
            case REFUND_MISMATCH ->
                    EnumSet.of(ResolutionKind.WRITE_OFF, ResolutionKind.TRANSFER_TO_ACCOUNT);
            // The statement causes close only EVIDENCED (ADR-0069 section 9).
            case SETTLEMENT_MISMATCH ->
                    cause == BreakCause.REMITTANCE_DIFFERS
                            ? EnumSet.of(
                                    ResolutionKind.WRITE_OFF,
                                    ResolutionKind.TRANSFER_TO_ACCOUNT,
                                    ResolutionKind.RECOGNISE_GAIN)
                            : EnumSet.noneOf(ResolutionKind.class);
        };
    }

    /**
     * Why the subject cannot carry {@code kind}'s lines, or empty when it can — the side rule,
     * judged at proposal and again at approval.
     */
    public static Optional<String> sideRefusal(ResolutionKind kind, Holding holding) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(holding, "holding must not be null");
        boolean ok =
                switch (kind) {
                    case ACKNOWLEDGE -> true;
                    case WRITE_OFF ->
                            (holding instanceof Holding.Remainder remainder
                                            && remainder.direction()
                                                    == ExpectationDirection.INBOUND)
                                    || (holding instanceof Holding.Parked parked
                                            && parked.side() == SuspenseSide.DEBIT);
                    case TRANSFER_TO_ACCOUNT ->
                            (holding instanceof Holding.Remainder remainder
                                            && remainder.direction()
                                                    == ExpectationDirection.OUTBOUND)
                                    || (holding instanceof Holding.Parked parked
                                            && parked.side() == SuspenseSide.CREDIT);
                    case RECOGNISE_GAIN ->
                            holding instanceof Holding.Parked parked
                                    && parked.side() == SuspenseSide.CREDIT;
                    case OFFSET_SUSPENSE, MANUAL_MATCH -> holding instanceof Holding.Parked;
                    case EVIDENCED, REPUDIATE_BATCH -> false;
                };
        if (!ok) {
            return Optional.of(
                    kind.name() + " needs "
                            + switch (kind) {
                                case WRITE_OFF -> "an INBOUND remainder or a DEBIT suspense item";
                                case TRANSFER_TO_ACCOUNT ->
                                        "an OUTBOUND remainder or a CREDIT suspense item";
                                case RECOGNISE_GAIN -> "a CREDIT suspense item";
                                case OFFSET_SUSPENSE, MANUAL_MATCH -> "parked value";
                                default -> "a subject no person resolves";
                            }
                            + ", and this break's subject holds "
                            + describe(holding));
        }
        if (kind != ResolutionKind.ACKNOWLEDGE && amountOf(holding).minorUnits() == 0) {
            return Optional.of("the subject holds nothing left to dispose of");
        }
        return Optional.empty();
    }

    /**
     * The disposed amount: the whole current remainder, never typed; an acknowledgement's is
     * the break's value at issue (it disposes of nothing in a position).
     */
    public static Money amount(ResolutionKind kind, Holding holding, Money valueAtIssue) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(valueAtIssue, "valueAtIssue must not be null");
        return kind == ResolutionKind.ACKNOWLEDGE ? valueAtIssue : amountOf(holding);
    }

    /** The derived flag (`V007`'s CHECK): value at issue or a posting needs two people. */
    public static boolean fourEyes(ResolutionKind kind, Money amount) {
        return kind != ResolutionKind.EVIDENCED
                && !(kind == ResolutionKind.ACKNOWLEDGE && amount.minorUnits() == 0);
    }

    /**
     * A posting kind's lines, exactly the remainder: {@code WRITE_OFF} DR losses / CR the
     * position (or CR suspense for a DEBIT item); {@code TRANSFER_TO_ACCOUNT} DR suspense (or
     * DR the position for an OUTBOUND remainder) / CR the target; {@code RECOGNISE_GAIN} DR
     * suspense / CR gains.
     */
    public static List<JournalLine> lines(
            ResolutionKind kind,
            Holding holding,
            LedgerAccountId losses,
            LedgerAccountId gains,
            LedgerAccountId suspense,
            Optional<LedgerAccountId> target) {
        Objects.requireNonNull(kind, "kind must not be null");
        Money amount = amountOf(holding);
        return switch (kind) {
            case WRITE_OFF ->
                    List.of(
                            new JournalLine(losses, Direction.DEBIT, amount),
                            new JournalLine(creditedBy(holding, suspense), Direction.CREDIT,
                                    amount));
            case TRANSFER_TO_ACCOUNT ->
                    List.of(
                            new JournalLine(creditedBy(holding, suspense), Direction.DEBIT,
                                    amount),
                            new JournalLine(
                                    target.orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "a transfer names its target")),
                                    Direction.CREDIT,
                                    amount));
            case RECOGNISE_GAIN ->
                    List.of(
                            new JournalLine(suspense, Direction.DEBIT, amount),
                            new JournalLine(gains, Direction.CREDIT, amount));
            default ->
                    throw new IllegalArgumentException(
                            kind + " posts no adjustment (ADR-0071 section 2)");
        };
    }

    /** The account the subject's value sits in: its position, or suspense for parked value. */
    private static LedgerAccountId creditedBy(Holding holding, LedgerAccountId suspense) {
        return switch (holding) {
            case Holding.Remainder remainder -> LedgerAccountId.of(remainder.positionAccountId());
            case Holding.Parked parked -> suspense;
            case Holding.Nothing nothing ->
                    throw new IllegalArgumentException("nothing to post against");
        };
    }

    private static Money amountOf(Holding holding) {
        return switch (holding) {
            case Holding.Remainder remainder -> remainder.amount();
            case Holding.Parked parked -> parked.amount();
            case Holding.Nothing nothing ->
                    throw new IllegalArgumentException("the subject holds no value");
        };
    }

    private static String describe(Holding holding) {
        return switch (holding) {
            case Holding.Remainder remainder -> "an " + remainder.direction() + " remainder";
            case Holding.Parked parked -> "a " + parked.side() + " suspense item";
            case Holding.Nothing nothing -> "no value in a position";
        };
    }
}
