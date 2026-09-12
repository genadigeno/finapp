package com.finapp.consent;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable consent fact: a grant or a withdrawal, purpose-scoped, pinned to the text
 * version it was recorded against (`P2-TSK-017`, ADR-0037).
 *
 * <p><strong>No lifecycle, deliberately</strong> ({@code PHASE_2_PLAN.md} §5 says so in as many
 * words): a record never changes state, because a changed record is destroyed evidence
 * ({@code INV-CNS-02}). What flips between granted and withdrawn is the <em>derived</em> basis,
 * as records append — the store's derivation, never a field here.
 *
 * <p><strong>The record does not carry its position in the history.</strong> The order of facts
 * is {@code consent_record.seq}, assigned by the server at insert ({@code GENERATED ALWAYS}),
 * because a value minted here would be some instance's clock or counter — and which of two
 * racing facts is later must be answered the same way by every reader
 * ({@code P0-TST-009}'s lesson, cited by the backlog). {@code recordedAt} is the human-readable
 * date of the fact, informational and never the order.
 *
 * <p>{@code partyId} is a raw value, not a {@code PartyId}: this module cannot see {@code party}
 * (ADR-0029's by-value reference, the {@code kyc_case.customerId} pattern).
 */
public final class ConsentRecord {

    private final ConsentRecordId id;
    private final UUID partyId;
    private final ConsentPurpose purpose;
    private final ConsentAction action;
    private final int textVersion;
    private final Instant recordedAt;

    private ConsentRecord(
            ConsentRecordId id,
            UUID partyId,
            ConsentPurpose purpose,
            ConsentAction action,
            int textVersion,
            Instant recordedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.partyId = Objects.requireNonNull(partyId, "partyId must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        if (textVersion < 1) {
            // INV-CNS-04: every record pins a real text version. The schema's composite FK is
            // the enforcement; this is the domain refusing to construct the unpinnable.
            throw new IllegalArgumentException(
                    "a consent record must pin the text version it was recorded against");
        }
        this.textVersion = textVersion;
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }

    /** A grant for {@code purpose}, against the text version the person was shown. */
    public static ConsentRecord grant(
            IdGenerator ids, Clock clock, UUID partyId, ConsentPurpose purpose, int textVersion) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ConsentRecord(
                ConsentRecordId.next(ids),
                partyId,
                purpose,
                ConsentAction.GRANT,
                textVersion,
                Instant.now(clock));
    }

    /**
     * A withdrawal for {@code purpose}, pinned to the version current when the person withdrew.
     * A new fact, never an edit of the grant it follows — and recordable with no grant before
     * it, since absence and withdrawal are one answer to every caller ({@code INV-CNS-01}).
     */
    public static ConsentRecord withdrawal(
            IdGenerator ids, Clock clock, UUID partyId, ConsentPurpose purpose, int textVersion) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ConsentRecord(
                ConsentRecordId.next(ids),
                partyId,
                purpose,
                ConsentAction.WITHDRAWAL,
                textVersion,
                Instant.now(clock));
    }

    /** Reconstitutes from storage. Applies no rules the row has not already satisfied. */
    public static ConsentRecord rehydrate(
            ConsentRecordId id,
            UUID partyId,
            ConsentPurpose purpose,
            ConsentAction action,
            int textVersion,
            Instant recordedAt) {
        return new ConsentRecord(id, partyId, purpose, action, textVersion, recordedAt);
    }

    public ConsentRecordId id() {
        return id;
    }

    public UUID partyId() {
        return partyId;
    }

    public ConsentPurpose purpose() {
        return purpose;
    }

    public ConsentAction action() {
        return action;
    }

    public int textVersion() {
        return textVersion;
    }

    public Instant recordedAt() {
        return recordedAt;
    }
}
