package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A screening list version - the operator-recorded fact that version {@link #version} of {@link #list} is in force
 * (`P13-TSK-022`; {@code PHASE_13_PLAN.md} section 12.10; {@code RISK_LIFECYCLES.md} section 4.10; {@code risk V018}'s
 * {@code screening_list_version}). Not a Screening Result (kyc's) and not a policy: no rule reads the list itself.
 *
 * <p>{@link #ordinal} numbers a list's versions in the order they were recorded ({@code max + 1} under the list's
 * advisory lock), so "a version recorded before the one in force" is a comparison of two integers, never of two
 * instances' clocks. The activation's enqueue rides the row: its permit {@link #enqueueNextAt} (due at the activation,
 * cleared when the census is complete or the version retires), the last party enqueued ({@link #enqueueCursor}, a keyset
 * over party ids) and the count. The stamps are the trigger's {@code clock_timestamp()}.
 *
 * @param id the version's identity - the receipt's, the permits' reference and the signals' {@code list_version_ref}
 * @param list the list
 * @param version the provider's label for the version - {@link #LABEL}, never free text
 * @param ordinal the list's recording order, from 1
 * @param status where the version stands
 * @param recordedBy the person who recorded it
 * @param recordedAt when, on the database clock
 * @param activatedBy the second person who activated it - never the recorder (four eyes)
 * @param activatedAt when it came into force - the rescreening's currency line and the hit alert's window
 * @param retiredAt when a successor's activation retired it
 * @param enqueueNextAt the enqueue's permit, while the census is incomplete and the version is in force
 * @param enqueueCursor the last party the enqueue reached
 * @param enqueued how many permits the enqueue has born
 * @param enqueueCompletedAt when the enqueue reached the last {@code ACTIVE} party
 * @param correlationId the activation's correlation (the recording's until then) - every permit carries it
 */
public record ScreeningListVersion(
        ScreeningListVersionId id,
        ScreeningList list,
        String version,
        int ordinal,
        ScreeningListVersionStatus status,
        RiskCase.Person recordedBy,
        Instant recordedAt,
        Optional<RiskCase.Person> activatedBy,
        Optional<Instant> activatedAt,
        Optional<Instant> retiredAt,
        Optional<Instant> enqueueNextAt,
        Optional<UUID> enqueueCursor,
        long enqueued,
        Optional<Instant> enqueueCompletedAt,
        String correlationId) {

    /** A version label: a provider's identifier of the list's edition, 1 to 64 characters, no space and no text. */
    public static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    public ScreeningListVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(recordedBy, "recordedBy");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(activatedBy, "activatedBy");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(retiredAt, "retiredAt");
        Objects.requireNonNull(enqueueNextAt, "enqueueNextAt");
        Objects.requireNonNull(enqueueCursor, "enqueueCursor");
        Objects.requireNonNull(enqueueCompletedAt, "enqueueCompletedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        label(version);
        if (ordinal < 1) {
            throw new IllegalArgumentException("a list version's ordinal is at least 1");
        }
        if ((status != ScreeningListVersionStatus.RECORDED) != (activatedBy.isPresent() && activatedAt.isPresent())) {
            throw new IllegalArgumentException("a list version names its activator exactly once it is activated");
        }
        if ((status == ScreeningListVersionStatus.RETIRED) != retiredAt.isPresent()) {
            throw new IllegalArgumentException("a list version is stamped retired exactly when it is RETIRED");
        }
        if (enqueueNextAt.isPresent() && status != ScreeningListVersionStatus.ACTIVE) {
            throw new IllegalArgumentException("only a version in force enqueues");
        }
        if (enqueued < 0) {
            throw new IllegalArgumentException("a census is never negative");
        }
    }

    /** {@code version} as a label, or refused. */
    public static String label(String version) {
        if (version == null || !LABEL.matcher(version).matches()) {
            throw new IllegalArgumentException("a list version is labelled by 1 to 64 letters, digits, '.', '_', ':' or"
                    + " '-', starting with a letter or digit");
        }
        return version;
    }

    /**
     * True when {@code person} recorded this version - by identity, whatever the type the session gave it: the four-eyes
     * {@code CHECK} beneath compares the identities alone.
     */
    public boolean recordedBy(com.finapp.platform.security.Actor person) {
        return recordedBy.id().equals(person.id());
    }

    /** True while the enqueue still has parties to reach - the version in force with its permit standing. */
    public boolean enqueuing() {
        return status == ScreeningListVersionStatus.ACTIVE && enqueueNextAt.isPresent();
    }

    @Override
    public String toString() {
        // Codes and numbers only - the persons are CONFIDENTIAL.
        return "ScreeningListVersion[" + list + ", #" + ordinal + ", " + status + "]";
    }
}
