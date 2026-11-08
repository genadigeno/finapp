package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheme's own record of one execution, asked by ITS transaction reference (`P15-TSK-036`, ADR-0083): the inquiry a
 * statement of value naming nothing we minted triggers. Our end-to-end reference cannot ask about that statement, by
 * definition, so the scheme's own reference does. {@code endToEndReference} is the scheme's word on what the execution
 * carried, which the caller reads to refuse a parking of value one of our own rows already explains.
 */
public record ExecutionAnswer(
        Verdict verdict,
        Optional<ProviderReference> schemeReference,
        Optional<EndToEndReference> endToEndReference,
        Optional<String> settlementCycle,
        Optional<Money> executed,
        Optional<byte[]> evidence) {

    /** The scheme's view of the execution. Default branch: {@link #INDETERMINATE}. */
    public enum Verdict {
        /** Executed: the scheme's reference and the executed amount are carried. */
        EXECUTED,
        /** The scheme explicitly does not know this reference. */
        UNRECOGNISED,
        /** Everything else: ask again. */
        INDETERMINATE
    }

    public ExecutionAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        Objects.requireNonNull(endToEndReference, "endToEndReference must not be null");
        Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        Objects.requireNonNull(executed, "executed must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (verdict == Verdict.EXECUTED) {
            if (schemeReference.isEmpty() || executed.isEmpty() || !executed.get().isPositive()) {
                throw new IllegalArgumentException("an EXECUTED answer carries the scheme reference and a positive amount");
            }
        } else if (schemeReference.isPresent() || endToEndReference.isPresent() || settlementCycle.isPresent()
                || executed.isPresent()) {
            throw new IllegalArgumentException("only an EXECUTED answer carries an execution's facts");
        }
        settlementCycle.ifPresent(PushAnswer::requireCycleShape);
        evidence = evidence.map(byte[]::clone);
    }

    public static ExecutionAnswer executed(
            ProviderReference scheme, Optional<EndToEndReference> endToEnd, Optional<String> cycle, Money executed,
            byte[] evidence) {
        return new ExecutionAnswer(
                Verdict.EXECUTED, Optional.of(scheme), endToEnd, cycle, Optional.of(executed), Optional.of(evidence));
    }

    public static ExecutionAnswer unrecognised(byte[] evidence) {
        return new ExecutionAnswer(
                Verdict.UNRECOGNISED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(evidence));
    }

    public static ExecutionAnswer indeterminate() {
        return new ExecutionAnswer(
                Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public static ExecutionAnswer indeterminate(byte[] evidence) {
        return new ExecutionAnswer(
                Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(evidence));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived - never the amount or the bytes ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "ExecutionAnswer[" + verdict + ", evidence=" + evidence.isPresent() + "]";
    }
}
