package com.finapp.payments;

import java.util.Objects;

/**
 * The system withdrawal entry's refusals (`P11-TSK-015`, ADR-0097 point 5). Three are refused before anything is written:
 * a person or an operator calling the platform's door, an instruction not of the recorded shape, a hold that is not the
 * instructor's ACTIVE hold of exactly the amount on the resolved wallet. Two are the instructor's resolver saying the
 * instruction cannot be served - no active customer, no live bank instrument of the party's - and those are RECORDED under
 * the instruction's key, like the routing refusal, and replayed: an instruction refused is refused for good, so no
 * withdrawal can ever be born under it afterwards and the instructor may release its hold. The message names the reason
 * only - never a wallet, an amount or a party.
 */
public final class SystemWithdrawalRefusedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /** Why the entry refused. */
    public enum Reason {
        /** Only the platform may withdraw on a recorded instruction. */
        NOT_A_SYSTEM_ACTOR(false),
        /** The instruction is not {@code <namespace>:<uuid>}. */
        INSTRUCTION_MALFORMED(false),
        /** The hold to adopt is unknown, released, on another account or of another amount. */
        HOLD_NOT_ADOPTABLE(false),
        /** The instruction's party has no active customer to withdraw for - recorded under the key. */
        CUSTOMER_UNAVAILABLE(true),
        /** The instruction's destination is no live bank instrument of the party's - recorded under the key. */
        DESTINATION_UNAVAILABLE(true);

        private final boolean recorded;

        Reason(boolean recorded) {
            this.recorded = recorded;
        }

        /** Whether this refusal is the instruction's recorded, replayed answer (the resolver's servable refusals). */
        public boolean isRecorded() {
            return recorded;
        }
    }

    private final Reason reason;

    public SystemWithdrawalRefusedException(Reason reason) {
        super("a system withdrawal was refused: " + Objects.requireNonNull(reason, "reason must not be null"));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
