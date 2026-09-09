package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies one retained evidence payload (ADR-0013).
 *
 * <p>Typed although nothing references evidence yet, because something will: a decision
 * (`P2-TSK-013`) references the evidence it rested on ({@code INV-KYC-02}), and the row that
 * gets referenced deserves the identifier discipline everything else has — a bare
 * {@code UUID.randomUUID()} here was this project's recorded v4-versus-v7 trap, met again and
 * removed before it shipped.
 */
public final class EvidenceId extends EntityId {

    private EvidenceId(UUID value) {
        super(value);
    }

    public static EvidenceId next(IdGenerator ids) {
        return new EvidenceId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static EvidenceId of(UUID value) {
        return new EvidenceId(value);
    }
}
