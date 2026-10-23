package com.finapp.risk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A device, as risk ever knows it: the SHA-256 of identity's device description, as 64 lowercase hex characters -
 * never the description (`P13-TSK-005`; OQ-4, {@code PHASE_13_PLAN.md} section 12.5; ADR-0107 decision 9).
 *
 * <p><strong>Why a hash is the only shape admitted.</strong> A device description is a {@code User-Agent} a person
 * did not choose - {@code RESTRICTED-PII} in identity's own register - and risk needs only one question of it: "has
 * this device been seen before?" A hash answers that and nothing else, so no risk row, event, log line or metric tag
 * can ever carry what the description says. The shape is held at three ranks: here (the only way to build one), on
 * {@link SignalSubject} (a {@link SubjectKind#DEVICE} subject's reference is a hash), and in {@code risk V003}'s
 * {@code CHECK} on {@code subject_ref} (a {@code DEVICE} row's reference matches {@code ^[0-9a-f]{64}$}) - so a
 * description, which has spaces and punctuation, cannot be stored by any writer.
 *
 * <p><strong>The canonical function, declared here for the source to mirror.</strong> The plan hashes at the source
 * - identity's handler sends the hash, this module receives it - and today no identity event carries a device at all
 * ({@code EventPayload}'s grammar admits no description, so none could ride one). When identity first publishes a
 * device, it publishes {@link #of} of the description: SHA-256 over the description's UTF-8 bytes, lowercase hex,
 * computed once from the description exactly as identity stores it. Nothing else is compared, so a different
 * function at the source would silently make every device new; the function is stated once, here.
 */
public record DeviceHash(String value) {

    /** 64 lowercase hex characters - SHA-256's digest, and nothing a person typed can match it. */
    public static final Pattern SHAPE = Pattern.compile("^[0-9a-f]{64}$");

    public DeviceHash {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            // The message never repeats the value: a refused value may be the description itself (INV-AUD-02).
            throw new IllegalArgumentException("a device hash is 64 lowercase hex characters");
        }
    }

    /** The canonical hash of a device description - the one function the source and this module share. */
    public static DeviceHash of(String description) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("a device description is never blank");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return new DeviceHash(HexFormat.of().formatHex(sha256.digest(description.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", absent);
        }
    }

    /** A hash received as text - from an event's payload - admitted only in the shape above. */
    public static DeviceHash parse(String hex) {
        return new DeviceHash(hex);
    }

    @Override
    public String toString() {
        return value;
    }
}
