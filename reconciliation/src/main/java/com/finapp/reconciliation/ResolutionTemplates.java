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

    /**
     * ADR-0069 §2's row: the person's kinds {@code type} admits, refined by {@code cause}.
     *
     * <p>The refinements are keyed on the CAUSE alone, whatever the break's current type
     * (`P8-TST-002`'s gate find): the cause is frozen at raise and a reclassification moves only
     * the type, so a diverged replay reclassified onto a {@code TIMING_DIFFERENCE} - both stand
     * on a decision - or an explained duplicate reclassified onto {@code UNKNOWN_EXTERNAL} keeps
     * the refinement its detector earned.
     */
    public static Set<ResolutionKind> admittedKinds(BreakType type, BreakCause cause) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        switch (cause) {
            // A diverged replay (P8-TSK-022, ADR-0068 section 9.1): a decision its stored
            // snapshot no longer reproduces holds no value - its subject is the decision - so the
            // one disposal is a person's four-eyes acknowledgement once the defect is
            // investigated.
            case REPLAY_DIVERGED -> {
                return EnumSet.of(ResolutionKind.ACKNOWLEDGE);
            }
            // The statement causes close only EVIDENCED (ADR-0069 section 9).
            case STATEMENT_GAP, OPENING_BALANCE -> {
                return EnumSet.noneOf(ResolutionKind.class);
            }
            // A parking whose scheme execution a credit already explains: its value was
            // attributed once, so no transfer may attribute it again - the gain after its minimum
            // age, beside a write-off of the doubled clearing remainder, or an offset against the
            // scheme's own correction (P8-TSK-020, ADR-0070 point 8). WRITE_OFF stays, as on
            // every suspense-owning type: the side rule refuses it a CREDIT item.
            case EXECUTION_ALREADY_EXPLAINED -> {
                Set<ResolutionKind> admitted = byType(type, cause);
                admitted.remove(ResolutionKind.TRANSFER_TO_ACCOUNT);
                return admitted;
            }
            default -> {
                return byType(type, cause);
            }
        }
    }

    private static Set<ResolutionKind> byType(BreakType type, BreakCause cause) {
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
            // The posting kinds for REMITTANCE_DIFFERS alone (ADR-0069 section 9).
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

    /**
     * The derived flag (`V007`'s CHECK, `V014`'s trigger): value at issue or a posting needs two
     * people, and so does every acknowledgement but a zero-value {@code TIMING_DIFFERENCE}'s.
     *
     * <p>ADR-0071 §3 named the single-person path "in practice exactly" the timing difference's
     * acknowledgement, but derived it from value alone - so a diverged replay's zero-value
     * {@code PROCESSING_ERROR} (P8-TSK-022, ADR-0068 §9.1: "the matcher's decisions cannot be
     * reproduced", CRITICAL) closed on one person's word. `P8-TST-002`'s correction derives it
     * from the break's type AND cause: a zero-value {@code ACKNOWLEDGE} is one person's ONLY on
     * a {@code TIMING_DIFFERENCE} raised by a timing detector ({@link #timingCause}) - the
     * cause is frozen at raise, so a break reclassified onto the timing type keeps two people;
     * every other acknowledgement is four-eyes.
     */
    public static boolean fourEyes(
            ResolutionKind kind, Money amount, BreakType type, BreakCause cause) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        return kind != ResolutionKind.EVIDENCED
                && !(kind == ResolutionKind.ACKNOWLEDGE
                        && amount.minorUnits() == 0
                        && type == BreakType.TIMING_DIFFERENCE
                        && timingCause(cause));
    }

    /** A cause whose detector raises {@code TIMING_DIFFERENCE}: {@code LATE_MATCH}, {@code CYCLE_MISMATCH}. */
    public static boolean timingCause(BreakCause cause) {
        return cause.raisesAs().contains(BreakType.TIMING_DIFFERENCE);
    }

    /**
     * Why a reclassification onto {@code to} would strand the break, or empty when its frozen
     * cause keeps an exit there (ADR-0069 §7). *(Corrected 2026-10-02 by the Phase 8 -> 9
     * transition: the case file checked only the subject, the parking parity and the seat, so
     * one investigator could move a break onto a type no kind and no evidence would ever close -
     * a {@code STATEMENT_GAP} onto {@code PROCESSING_ERROR}, a {@code RUN_BLOCKED} onto
     * {@code SETTLEMENT_MISMATCH}, an {@code EXPECTATION_OVERDUE} onto
     * {@code SETTLEMENT_MISMATCH}.)*
     *
     * <p>An exit is any one of three, each one that cannot pass the break by:
     *
     * <ul>
     *   <li><strong>A person's kind on {@code to}</strong> that the subject takes - an
     *       {@code ACKNOWLEDGE} (it needs no value), or any kind over PARKED value (parked value
     *       leaves only by a release or an unpark, both of which close its owning break). A kind
     *       over an expectation's remainder is not counted: an allocation can take the remainder
     *       while the closers look for other types, and the break would be left over nothing.
     *   <li><strong>The cause's own evidence still finds {@code to}</strong>
     *       ({@link #evidenceFinds}): the statement chain's gap closer, the requeued run's
     *       completion, the settling allocation's two remainder closers.
     *   <li><strong>The cause's raise type admits {@code ACKNOWLEDGE}</strong>: a reclassification
     *       back onto it always recovers the break (a timing difference moved onto
     *       {@code PROCESSING_ERROR}, a key collision moved anywhere).
     * </ul>
     */
    public static Optional<String> reclassificationStrands(
            BreakType to,
            BreakCause cause,
            BreakSubjectKind subject,
            Holding holding,
            Optional<ExpectationKind> expectationKind) {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(holding, "holding must not be null");
        Objects.requireNonNull(expectationKind, "expectationKind must not be null");
        boolean personExit =
                admittedKinds(to, cause).stream()
                        .anyMatch(kind -> sideRefusal(kind, holding).isEmpty()
                                && (kind == ResolutionKind.ACKNOWLEDGE
                                        || holding instanceof Holding.Parked));
        if (personExit) {
            return Optional.empty();
        }
        Set<BreakType> evidence = evidenceFinds(cause, subject, expectationKind);
        if (evidence.contains(to)) {
            return Optional.empty();
        }
        boolean recoverable =
                cause.raisesAs().stream()
                        .filter(type -> type.admits(subject))
                        .anyMatch(type -> admittedKinds(type, cause)
                                .contains(ResolutionKind.ACKNOWLEDGE));
        if (recoverable) {
            return Optional.empty();
        }
        return Optional.of(
                to.name() + " leaves a " + cause.name() + " break no exit: no kind it admits"
                        + " can dispose of " + describe(holding) + ", and the evidence that"
                        + " closes the cause looks for "
                        + (evidence.isEmpty() ? "no type" : evidence.toString())
                        + "; reclassify onto a type that keeps one");
    }

    /**
     * The types the evidence closing {@code cause} looks for on {@code subject} - the closers'
     * own filters, read from where they stand: the statement chain's gap closer selects
     * {@code SETTLEMENT_MISMATCH} (cause {@code STATEMENT_GAP}) on the successor's run; a
     * requeued run's completion selects the run's {@code PROCESSING_ERROR}; an allocation that
     * settles an expectation selects its {@code MISSING_EXTERNAL} and its shortfall type
     * ({@code SETTLEMENT_MISMATCH} for a {@code REMITTANCE}, {@code AMOUNT_MISMATCH} for any
     * other kind). Every other cause is closed by a person or by the unpark of its own parked
     * value, never by a type-filtered closer.
     */
    static Set<BreakType> evidenceFinds(
            BreakCause cause, BreakSubjectKind subject, Optional<ExpectationKind> expectationKind) {
        return switch (cause) {
            case STATEMENT_GAP ->
                    subject == BreakSubjectKind.RUN
                            ? EnumSet.of(BreakType.SETTLEMENT_MISMATCH)
                            : EnumSet.noneOf(BreakType.class);
            case RUN_BLOCKED ->
                    subject == BreakSubjectKind.RUN
                            ? EnumSet.of(BreakType.PROCESSING_ERROR)
                            : EnumSet.noneOf(BreakType.class);
            case EXPECTATION_OVERDUE, AMOUNT_DIFFERS, REMITTANCE_DIFFERS -> {
                if (subject != BreakSubjectKind.EXPECTATION) {
                    yield EnumSet.noneOf(BreakType.class);
                }
                Set<BreakType> closers = EnumSet.of(BreakType.MISSING_EXTERNAL);
                expectationKind.ifPresent(kind -> closers.add(
                        kind == ExpectationKind.REMITTANCE
                                ? BreakType.SETTLEMENT_MISMATCH
                                : BreakType.AMOUNT_MISMATCH));
                yield closers;
            }
            default -> EnumSet.noneOf(BreakType.class);
        };
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
