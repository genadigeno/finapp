package com.finapp.identity;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a session was established from, as a label a person can recognise (`P1-TSK-016`).
 *
 * <h2>Recorded, never scored</h2>
 *
 * <p>{@code V005}'s own comment states it: <em>"Whether a device is trusted is a Phase 13 risk
 * decision, and a column that quietly became an input to an access decision would be that decision
 * taken by accident."</em> This type exists so the value can be shown to its owner — <em>"Chrome on
 * Windows, last used an hour ago"</em> — and for nothing else. Nothing compares two of these, and
 * no access decision reads one.
 *
 * <h2>It sanitises rather than refuses, and that is the opposite of {@code PartyName}</h2>
 *
 * <p>The difference is principled rather than inconsistent. A display name is the person's own
 * data: refusing it tells them to fix something they chose and can change. A {@code User-Agent} is
 * a header the person did not choose and cannot edit, so <strong>a login must never fail because a
 * browser sent something odd</strong>. Letting an unscored convenience label refuse an
 * authentication would invert its importance completely.
 *
 * <p>That is the same conclusion ADR-0034 reached for the correlation header — <em>never fails the
 * request</em> — arrived at from the other side: correlation is <em>replaced</em> because a caller
 * value has no use to us, and this is <em>sanitised</em> because the value is the whole point.
 *
 * <h2>What is removed, and why it matters for a field nobody scores</h2>
 *
 * <p>Control and formatting characters, and a bound on length. This value is
 * {@code RESTRICTED-PII} (ADR-0022), it is stored durably, and it is rendered back to a person — so
 * a {@code CR} in it is a forged log line and a bidirectional override is a display that lies about
 * which session is which. That is the {@code P1-TSK-006} {@code PartyName} finding, in the one
 * remaining column that takes a caller's string and had no rule at all.
 *
 * <p>Enforced again as a {@code CHECK} constraint in {@code V007}, because
 * {@code DEFINITION_OF_DONE.md} §1.3 puts an invariant the database can carry in the database and
 * this application is not the only thing that will ever write that column.
 */
public record DeviceDescription(String value) {

    /**
     * The bound.
     *
     * <p>Generous enough for a real {@code User-Agent}, which is routinely 150-200 characters, and
     * far short of what an attacker would need for this column to be a storage amplifier on an
     * endpoint that issues sessions.
     */
    public static final int MAX_LENGTH = 256;

    /** Control, format, surrogate, private-use and unassigned — the {@code PartyName} set. */
    private static final Pattern FORBIDDEN =
            Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]");

    public DeviceDescription {
        Objects.requireNonNull(value, "device must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("device must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "device must be at most " + MAX_LENGTH + " characters but was " + value.length());
        }
        if (FORBIDDEN.matcher(value).find()) {
            // The message never repeats the value: it is RESTRICTED-PII and an exception message
            // reaches a log line (INV-AUD-02).
            throw new IllegalArgumentException("device must not contain control characters");
        }
    }

    /**
     * Builds one from a {@code User-Agent}, or nothing at all.
     *
     * <p><strong>Total, and it never throws.</strong> Every input produces either a valid
     * description or {@link Optional#empty()} — an absent header, a blank one, one that is nothing
     * but control characters. A session with no device label is entirely ordinary and the column is
     * nullable for it.
     *
     * <p>Sanitising means the stored value can differ from what was sent, and that is stated rather
     * than hidden: what is dropped could not have been displayed honestly anyway.
     *
     * <p><strong>Its production call site is {@code AuthenticationController}</strong>
     * ({@code P1-TSK-027}), which is what makes {@code GET /v1/sessions} show a person something
     * they recognise rather than a column of nulls. It was written with no caller by
     * {@code P1-TSK-016}, and named the task that would supply one.
     */
    public static Optional<DeviceDescription> fromUserAgent(String userAgent) {
        if (userAgent == null) {
            return Optional.empty();
        }
        String cleaned = FORBIDDEN.matcher(userAgent).replaceAll("").strip();
        if (cleaned.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(
                new DeviceDescription(
                        cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned));
    }

    @Override
    public String toString() {
        return value;
    }
}
