package com.finapp.settlement;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.regex.Pattern;

/**
 * What a counterparty's reference looks like when it is one (ADR-0066 §3): the alphabet every
 * format's opaque references share, at most 100 characters, with no instrument shape anywhere in
 * it — no digit run of card length (single dashes, colons or underscores between digits
 * collapsed) and no account identifier, contiguous or in its printed groups. Each format
 * restates its own classes; this is the shape a stored reference must have to be SERVED back.
 *
 * <p><em>(Added 2026-10-02 by the Phase 8 → 9 transition, the audit's {@code SEC-02}: the PSP
 * format's reference class admitted a dash-grouped or letter-prefixed card number, which then
 * rested in {@code settlement.batch.external_batch_ref} and was returned by
 * {@code GET /batches/{id}}. The door now refuses it; a stored batch reference that is not this
 * shape — one written before the correction, in a store that cannot be cleaned — is withheld
 * from the read rather than served verbatim.)</em>
 *
 * <p><em>(Corrected 2026-10-03 by the Phase 8 → 9 transition's re-gate, NEW-SEC-2: the run
 * detection collapsed single dashes alone, so a card number grouped by ':' or '_' — both in
 * this alphabet and in the PSP class's — WAS a reference shape, rested in
 * {@code batch.external_batch_ref} and {@code line_reference} and was served back verbatim.
 * The collapse now reads ':', '_' and '-' alike: a 13+-digit run grouped by any of them, Luhn
 * or not, is no reference, and such a stored value is withheld from the read.)</em>
 */
public final class ReferenceShape {

    private static final Pattern REFERENCE =
            Pattern.compile("(?!.*[0-9](?:[:_-]?[0-9]){12})[A-Za-z0-9._:-]{1,100}");

    private ReferenceShape() {}

    /** True when the value is a reference shape and may be served verbatim. */
    public static boolean isReference(String value) {
        return value != null
                && REFERENCE.matcher(value).matches()
                && !InstrumentShapes.holdsAccountIdentifier(value);
    }
}
