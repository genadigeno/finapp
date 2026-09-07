package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Where an enrolment is in its lifecycle (`P1-TSK-017`, `INV-LIFE-01`).
 *
 * <h2>Two values, and the distinction between them IS this task's acceptance criterion</h2>
 *
 * <p>An enrolment is `PENDING` from the moment the secret is issued until the customer proves they
 * hold it by returning a valid code. A `PENDING` factor <strong>never satisfies anything</strong>:
 * the challenge selects `ACTIVE` in its `WHERE` clause, so partial enrolment leaves assurance
 * unchanged by construction rather than by a check somebody could forget.
 *
 * <p>Why that matters: without the confirmation step, anyone who could start an enrolment on
 * somebody else's account would have added a factor the victim cannot produce codes for - locking
 * them out at best, and at worst satisfying a challenge the attacker alone can answer.
 *
 * <h2>DISCARDED exists because something produces it; REVOKED does not</h2>
 *
 * <p>Starting a second enrolment discards the first pending one - a customer who closed the page
 * before scanning the QR code would otherwise be stuck with a secret they can neither confirm nor
 * remove. So `DISCARDED` has a real producer.
 *
 * <p>The row is <strong>marked, never deleted</strong>. The application role holds no `DELETE` on
 * this table, and an abandoned enrolment is evidence that somebody began adding a factor - which is
 * exactly what an investigator wants after an account takeover attempt.
 *
 * <p>There is deliberately no `REVOKED`: nothing removes a *confirmed* factor in Phase 1
 * (`PHASE_1_PLAN.md` §7 lists no such endpoint), so that value would have no producer and would be
 * speculation. It arrives with the task that builds removal.
 */
public enum MfaFactorStatus {
    PENDING,
    ACTIVE,
    DISCARDED;

    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
