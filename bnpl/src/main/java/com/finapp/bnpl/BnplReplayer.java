package com.finapp.bnpl;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.AllocationEngineV1;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueState;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.creditmath.LendingEngine;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pinned engines by version (`P12-TSK-025`; {@code PHASE_12_PLAN.md} sections 12.2 and 12.12, ADR-0102; lending's
 * {@code LoanReplayer}; {@code INV-LND-05} and {@code INV-LND-09} by analogy): every computation a BNPL obligation's
 * figures came from, dispatched on the engine NAME a row pinned - bnpl's own ({@link BnplEngine}: pay-in-4's schedule, the
 * refund's allocation) and the ones it shares with lending ({@link EngineVersion}: pay-in-N's schedule, the accrual, the
 * allocation). A name this build does not hold is {@link EngineAbsent} - the replay reports it {@code DIVERGED}, never
 * another version's arithmetic and never skipped.
 *
 * <p>Each dispatch is an exhaustive {@code switch} over the version enum: a new member is a compile error here until its
 * replay is written, and the old member's case stays for as long as an agreement pins it. Pure and stateless - the same
 * answer on every instance and in every JVM.
 */
public final class BnplReplayer {

    private BnplReplayer() {}

    /** A version this build does not hold: the replay is {@code DIVERGED}, never skipped. */
    public static final class EngineAbsent extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EngineAbsent(String engine) {
            super("engine " + engine + " is not held by this build");
        }
    }

    // ------------------------------------------------------------------ which engines this build holds

    /** The shared engine named {@code name} of {@code engine}'s kind, or {@link EngineAbsent}. */
    static EngineVersion shared(LendingEngine engine, String name) {
        Objects.requireNonNull(name, "name");
        for (EngineVersion version : EngineVersion.values()) {
            if (version.name().equals(name) && version.engine() == engine) {
                return version;
            }
        }
        throw new EngineAbsent(name);
    }

    /** bnpl's own engine named {@code name} of {@code kind}, or {@link EngineAbsent}. */
    static BnplEngine own(BnplEngine.Kind kind, String name) {
        Objects.requireNonNull(name, "name");
        for (BnplEngine version : BnplEngine.values()) {
            if (version.name().equals(name) && version.kind() == kind) {
                return version;
            }
        }
        throw new EngineAbsent(name);
    }

    /** Whether this build holds the schedule engine {@code name} - pay-in-4's own or pay-in-N's shared one. */
    static boolean holdsSchedule(String name) {
        try {
            scheduleEngine(name);
            return true;
        } catch (EngineAbsent absent) {
            return false;
        }
    }

    /** Whether this build holds every engine an agreement version pins. */
    static boolean holdsEvery(BnplAgreement agreement) {
        try {
            scheduleEngine(agreement.scheduleEngine());
            agreement.accrualEngine().ifPresent(name -> shared(LendingEngine.ACCRUAL, name));
            shared(LendingEngine.ALLOCATION, agreement.allocationEngine());
            own(BnplEngine.Kind.REFUND_ALLOCATION, agreement.refundAllocationEngine());
            return true;
        } catch (EngineAbsent absent) {
            return false;
        }
    }

    /** The schedule engine {@code name}: a {@link BnplEngine} of pay-in-4's kind, or the shared schedule engine. */
    static Object scheduleEngine(String name) {
        try {
            return own(BnplEngine.Kind.BNPL_SCHEDULE, name);
        } catch (EngineAbsent notOurs) {
            return shared(LendingEngine.SCHEDULE, name);
        }
    }

    // ------------------------------------------------------------------ the schedule

    /**
     * The schedule of {@code amount} on {@code plan} from {@code start}, by the schedule engine {@code name} - version 1 of
     * an obligation's schedule (the purchase's business date) and the offer's illustration (the offering day) alike.
     */
    static BnplIllustration project(String name, BnplTerms pinned, BnplPlan plan, Money amount, LocalDate start) {
        Object engine = scheduleEngine(name);
        BnplIllustration projected = switch (engine) {
            case BnplEngine own -> switch (own) {
                case BNPL_SCHEDULE_ENGINE_V1 -> BnplIllustration.of(pinned, plan, amount, start);
                case REFUND_ALLOCATION_ENGINE_V1 -> throw notThis(name);
            };
            case EngineVersion shared -> switch (shared) {
                case SCHEDULE_ENGINE_V1 -> BnplIllustration.of(pinned, plan, amount, start);
                case ACCRUAL_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(name);
            };
            default -> throw notThis(name);
        };
        if (!projected.engine().equals(name)) {
            // The pinned terms name another engine than the row: the row's engine is what replays it, never the terms'.
            throw new EngineAbsent(name + " (the pinned terms project by " + projected.engine() + ")");
        }
        return projected;
    }

    // ------------------------------------------------------------------ the accrual

    static InterestAccrual accrue(AccrualTerms terms, AccrualPeriod period, Optional<InterestAccrual> previous,
            LocalDate date, Money base) {
        return switch (terms.accrualEngine()) {
            case ACCRUAL_ENGINE_V1 -> AccrualEngineV1.accrue(terms, period, previous, date, base);
            case SCHEDULE_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(terms.accrualEngine().name());
        };
    }

    /** The payoff quote's projection (`P12-TSK-022`): what the accrual engine posts for {@code [from, through]}. */
    static Money project(AccrualTerms terms, List<AccrualPeriod> periods, Optional<InterestAccrual> last, LocalDate from,
            LocalDate through, Money base) {
        return switch (terms.accrualEngine()) {
            case ACCRUAL_ENGINE_V1 -> AccrualEngineV1.project(terms, periods, last, from, through, base).interest();
            case SCHEDULE_ENGINE_V1, ALLOCATION_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(terms.accrualEngine().name());
        };
    }

    // ------------------------------------------------------------------ the allocation

    static List<AllocationLine> allocate(Money amount, DueState dueState, AllocationRules rules) {
        return switch (rules.engine()) {
            case ALLOCATION_ENGINE_V1 -> AllocationEngineV1.allocate(amount, dueState, rules);
            case SCHEDULE_ENGINE_V1, ACCRUAL_ENGINE_V1, STATEMENT_ENGINE_V1 -> throw notThis(rules.engine().name());
        };
    }

    // ------------------------------------------------------------------ the refund's allocation

    static RefundAllocationOutcome allocateRefund(String name, RefundPosition position, Money refund,
            MerchantFeeReturn fees) {
        return switch (own(BnplEngine.Kind.REFUND_ALLOCATION, name)) {
            case REFUND_ALLOCATION_ENGINE_V1 -> RefundAllocationEngineV1.allocate(position, refund, fees);
            case BNPL_SCHEDULE_ENGINE_V1 -> throw notThis(name);
        };
    }

    private static IllegalStateException notThis(String name) {
        return new IllegalStateException("a version of another engine dispatched: " + name);
    }
}
