package com.finapp.platform.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The previous callback-signing key, accepted beside the current one for a bounded time (`P15-TSK-019`, ADR-0123 point 5).
 *
 * <p>Provider-signed callbacks arrive continuously, so a rotation is not a moment: a rotation stores the new secret as
 * current and the old one as previous, the fleet rolls, and only then does the provider start signing with the new one.
 * During that window both must verify.
 *
 * <h2>What closes it</h2>
 *
 * <p>The instant, on every verification, with nothing restarted: a window is only ever a window. Two things keep that
 * instant honest. It must be stated (a previous key with no instant is ignored, so a value left in the store opens nothing),
 * and it must be near: {@link #MAX_AHEAD} past the moment the replica starts is refused, so nobody can leave the previous key
 * accepted indefinitely by typing a date far ahead. An operator who needs longer changes the configuration again, which is a
 * reviewed change.
 *
 * <p>Judged on the server's clock, as the freshness tolerance beside it is: a replica's clock skew shifts when its own
 * window closes, by that skew, and nothing else.
 */
public final class PreviousKeyWindow {

    /** A week: long enough to move a provider, short enough that a forgotten window ends by itself. */
    public static final Duration MAX_AHEAD = Duration.ofDays(7);

    private final byte[] key;
    private final Instant validUntil;

    private PreviousKeyWindow(byte[] key, Instant validUntil) {
        this.key = key;
        this.validUntil = validUntil;
    }

    /**
     * @param validUntil the end of the window, exclusive
     * @param now the moment the replica started
     * @return the window, or empty if the instant has already passed: a window that closed before this replica started
     *     opens nothing, and a replica does not refuse to start over it
     * @throws IllegalStateException if the instant is further ahead than {@link #MAX_AHEAD}
     */
    public static Optional<PreviousKeyWindow> of(byte[] key, Instant validUntil, Instant now) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(validUntil, "validUntil must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (key.length == 0) {
            throw new IllegalArgumentException("the previous signing key must not be empty");
        }
        if (validUntil.isAfter(now.plus(MAX_AHEAD))) {
            throw new IllegalStateException(
                    "The previous key's window ends more than " + MAX_AHEAD.toDays() + " days ahead: refusing to start,"
                            + " because a window that long is a second permanent key");
        }
        if (!validUntil.isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(new PreviousKeyWindow(key.clone(), validUntil));
    }

    /** Whether this window still accepts the previous key at {@code instant}. */
    public boolean isOpenAt(Instant instant) {
        return instant.isBefore(validUntil);
    }

    public Instant validUntil() {
        return validUntil;
    }

    /** A copy: the caller signs with it and keeps nothing it can alter here. */
    public byte[] key() {
        return key.clone();
    }

    /** The window's end and nothing else ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "PreviousKeyWindow[until=" + validUntil + "]";
    }
}
