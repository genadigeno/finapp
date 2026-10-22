package com.finapp.risk;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a signal is about: a {@link SubjectKind} and a reference - the canonical text of the subject's identifier, or
 * for a {@link SubjectKind#DEVICE} its {@link DeviceHash} (`P13-TSK-005`; {@code PHASE_13_PLAN.md} section 3.1).
 *
 * <p>The reference is text rather than a {@code UUID} for one reason: a device is a hash. Every other kind's reference
 * is a {@code UUID} in its canonical lowercase form, so the velocity windows (`-006`), alerts (`-015`) and cases
 * (`-016`) keyed on {@code subject_ref} read one column of one shape. The shape is a {@code CHECK} on
 * {@code risk_signal.subject_ref} as well as this constructor, so no writer can store a device description or an
 * arbitrary string as a subject (OQ-4; {@code INV-AUD-02}).
 */
public record SignalSubject(SubjectKind kind, String ref) {

    /** A canonical {@code UUID.toString()}. */
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public SignalSubject {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ref, "ref");
        Pattern shape = kind == SubjectKind.DEVICE ? DeviceHash.SHAPE : UUID_SHAPE;
        if (!shape.matcher(ref).matches()) {
            // Never the value: a refused device reference may be the description (INV-AUD-02).
            throw new IllegalArgumentException(
                    "a " + kind + " subject's reference is " + (kind == SubjectKind.DEVICE
                            ? "identity's device hash (64 lowercase hex), never a description"
                            : "a canonical UUID"));
        }
    }

    /** A subject by its identifier - every kind but a device. */
    public static SignalSubject of(SubjectKind kind, UUID id) {
        Objects.requireNonNull(id, "id");
        if (kind == SubjectKind.DEVICE) {
            throw new IllegalArgumentException("a device is referenced by its hash, not an identifier");
        }
        return new SignalSubject(kind, id.toString());
    }

    /** A device, by its hash - the only way to name one. */
    public static SignalSubject device(DeviceHash hash) {
        return new SignalSubject(SubjectKind.DEVICE, Objects.requireNonNull(hash, "hash").value());
    }

    @Override
    public String toString() {
        return kind + ":" + ref;
    }
}
