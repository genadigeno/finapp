package com.finapp.payments;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A held card payment's capture hold (`P13-TSK-010`, slice 2; {@code PHASE_13_PLAN.md} §7.3, §12.3, §12.8; C2;
 * payments {@code V034}): the record that the risk seam's {@code HOLD} deferred this authorisation's capture until a
 * person decides - born <strong>open</strong> in the confirmation's first transaction beside the
 * {@code AUTH_DISPATCHED} attempt, its window stamped by the database ({@code held_at} the insert's own instant,
 * {@code held_until} that plus the pinned policy's hold validity), and <strong>resolved exactly once</strong>:
 * {@link Resolution#CAPTURE} (payments' keyed capture door, within {@code held_until}), {@link Resolution#VOID} (the keyed
 * void door) or {@link Resolution#EXPIRED} (the expiry leg, from {@code held_until}).
 *
 * <p><strong>Not a status machine of the payment.</strong> The intent stays {@code PROCESSING} and the attempt's own
 * machine is unchanged ({@code AUTHORIZED} while the hold is open); this row is what makes the capture wait: the
 * attempt's conditional capture dispatch and {@code V034}'s every-writer trigger refuse
 * {@code AUTHORIZED → CAPTURE_DISPATCHED} while the hold exists unresolved or resolved other than {@code CAPTURE}, and
 * the sweeper's stranded-chain leg passes it by. The resolution is the decision; the money is then moved - or the
 * promise released - by the existing commands ({@link PaymentCapture}, {@link PaymentVoid}), each in its own
 * transactions with the provider called holding no connection (ADR-0046).
 *
 * <p>Identifiers and instants only: a class rather than a record would buy nothing here - no amount rides it.
 */
public record CaptureHold(
        PaymentIntentId intentId,
        PaymentAttemptId attemptId,
        Instant heldAt,
        Instant heldUntil,
        Optional<Resolution> resolution,
        Optional<String> resolutionKey,
        Optional<Instant> resolvedAt) {

    /** The longest door key a resolution records - the column's bound. */
    public static final int MAX_RESOLUTION_KEY_LENGTH = 200;

    /** How the hold ended. */
    public enum Resolution {
        /** Released for capture by payments' keyed door: the capture follows. */
        CAPTURE,
        /** Voided by payments' keyed door: the authorisation is released. */
        VOID,
        /** {@code held_until} reached: the expiry leg releases the authorisation. */
        EXPIRED;

        /** Whether a door's key rides this resolution - the two doors', never the expiry's. */
        public boolean keyed() {
            return this != EXPIRED;
        }

        /** The values as a SQL literal list - {@code V034}'s {@code payment_capture_hold_resolution_is_known}. */
        public static String sqlValueList() {
            return Arrays.stream(values()).map(r -> "'" + r.name() + "'").collect(Collectors.joining(", "));
        }
    }

    public CaptureHold {
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(heldAt, "heldAt must not be null");
        Objects.requireNonNull(heldUntil, "heldUntil must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        Objects.requireNonNull(resolutionKey, "resolutionKey must not be null");
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        if (!heldUntil.isAfter(heldAt)) {
            throw new IllegalArgumentException("a capture hold's window is positive");
        }
        if (resolution.isPresent() != resolvedAt.isPresent()) {
            throw new IllegalArgumentException("a capture hold is resolved with its instant, or not at all");
        }
        if (resolutionKey.isPresent() != resolution.map(Resolution::keyed).orElse(false)) {
            throw new IllegalArgumentException("a door's key rides exactly a door's resolution");
        }
        resolutionKey.ifPresent(key -> {
            if (key.isBlank() || key.length() > MAX_RESOLUTION_KEY_LENGTH) {
                throw new IllegalArgumentException("a capture hold's door key is 1-" + MAX_RESOLUTION_KEY_LENGTH
                        + " characters");
            }
        });
    }

    /** Whether no resolution has been recorded yet. */
    public boolean open() {
        return resolution.isEmpty();
    }

    /** Whether the attempt's capture may be dispatched: no open hold and none resolved other than {@code CAPTURE}. */
    public boolean releasedForCapture() {
        return resolution.filter(Resolution.CAPTURE::equals).isPresent();
    }

    /** Identifiers and the resolution only. */
    @Override
    public String toString() {
        return "CaptureHold[" + intentId + ", " + resolution.map(Enum::name).orElse("OPEN") + "]";
    }
}
