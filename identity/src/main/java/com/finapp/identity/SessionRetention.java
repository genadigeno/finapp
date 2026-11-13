package com.finapp.identity;

import com.finapp.platform.retention.RetentionConnectionSource;
import com.finapp.platform.retention.RetentionSweep;
import java.time.Duration;
import java.util.Objects;

/**
 * The session sweep (`P16-TSK-010`; {@code THREAT_MODEL.md} section 6 item 18; {@code DATA_RETENTION.md}; ADR-0030): identity's own
 * {@code SECURITY DEFINER} function, identity {@code V027}, run as one of the platform's retention sweeps.
 *
 * <p><strong>Never a live session.</strong> The function selects a row only once its idle bound passed more than the grace ago, on the
 * database's clock, and a live session's idle bound is in the future by definition ({@link JdbcSessionStore}'s {@code LIVE}). The idle
 * bound is never past the absolute one, and nothing moves a dead row's bounds again, so a selected row is dead for good. A state the
 * function was not told about is never selected, and a column it was not reviewed against stops it.
 *
 * <p><strong>The grace is operational, not a duty.</strong> It keeps a dead session's row for diagnosis (a reported takeover asks which
 * device and how strongly a person signed in), as the outbox keeps a published row; the logins' audit records are never swept. The
 * function refuses less than {@link #MIN_GRACE}, and so does this class at start.
 */
public final class SessionRetention {

    /** identity's sweep function, named here and in its migration alone. */
    public static final String FUNCTION = "identity.sweep_expired_sessions";

    /** The table it deletes from: the meters' {@code type}. */
    public static final String TABLE = "session";

    /** The least grace the function accepts: a minute, so a row is never judged on the edge of its own statement. */
    public static final Duration MIN_GRACE = Duration.ofMinutes(1);

    private SessionRetention() {}

    /**
     * @param grace how long after its idle bound a dead session's row is kept
     * @param batchSize the rows one batch may delete, 1 to {@link RetentionSweep#MAX_BATCH_SIZE}
     */
    public static RetentionSweep sweep(RetentionConnectionSource connections, Duration grace, int batchSize) {
        Objects.requireNonNull(grace, "grace");
        if (grace.compareTo(MIN_GRACE) < 0) {
            throw new IllegalArgumentException("the session sweep's grace must be at least " + MIN_GRACE + " but was " + grace);
        }
        return RetentionSweep.ofModuleFunction(connections, TABLE, FUNCTION, grace, batchSize);
    }
}
