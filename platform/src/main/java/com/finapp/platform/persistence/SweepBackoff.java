package com.finapp.platform.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a sweep leaves a row that failed, or could not be read, before it lists it again (`P16-TSK-017`). Each module's
 * failure record computes the wait in its own statement, on the database's clock, from the record's own count: the base
 * doubled per consecutive failure, capped. The numbers live here once, so the payments and payout records, and the test that
 * holds the cap inside the flows' unknown-age pages, read the same values.
 */
public final class SweepBackoff {

    /** The first failure's wait: two of the sweeps' default 30-second polls. */
    public static final Duration BASE = Duration.ofSeconds(60);

    /**
     * The longest a resolution sweep leaves a failing row. A row that recovers - a release fixing a defect, a provider answering
     * again - is listed again within this plus one poll: 600 + 30 seconds, inside the 900 seconds after which
     * {@code PaymentUnknownAged}, {@code WithdrawalUnknownAged} and {@code MerchantPayoutUnknownAged} page
     * ({@code SweepBackoffBoundTest} reads both from the code and the rule file).
     */
    public static final Duration CAP = Duration.ofMinutes(10);

    /**
     * The longest the rematch leg leaves a faulting residual. A residual is already a break, aged and alerted in days
     * ({@code BreakAgeHigh} at one), and the rematch is opportunistic: an hour keeps a fixed defect's residual re-examined the
     * same day while sparing the worklist sixty re-heads an hour.
     */
    public static final Duration REMATCH_CAP = Duration.ofHours(1);

    /** What a record stores for a row the listing could not read. */
    public static final String UNREADABLE = "UnreadableRow";

    private SweepBackoff() {}

    /**
     * The failure's class name, the only thing a record or a log line keeps of it ({@code INV-AUD-02}: a JDBC or provider message
     * can name hosts, references and values). An anonymous class's empty simple name falls back to its binary name's last part.
     * Bounded to the record's column check.
     */
    public static String failureClass(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        String name = failure.getClass().getSimpleName();
        if (name.isEmpty()) {
            String binary = failure.getClass().getName();
            name = binary.substring(binary.lastIndexOf('.') + 1);
        }
        return name.length() > 128 ? name.substring(0, 128) : name;
    }
}
