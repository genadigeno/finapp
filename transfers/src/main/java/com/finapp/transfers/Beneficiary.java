package com.finapp.transfers;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A saved destination belonging to a Party ({@code P4-TSK-006}, {@code PHASE_4_PLAN.md} §5): a
 * display name a person chose, resolving (in Phase 4) to an internal customer product.
 *
 * <p><strong>A convenience with a lifecycle, never a trust decision.</strong> Saving a
 * destination does not authorize anything and removing one blocks nothing already in flight —
 * the transfer's own judgement resolves and judges the destination at decision time (the
 * accepted race, plan §7). What a beneficiary IS structural about is the platform's
 * account-takeover surface: creating one is where a takeover monetises, which is why creation
 * is the step-up point ({@code P4-TSK-007}) — a fact about the <em>surface</em>, recorded here
 * so the aggregate's reader knows why its birth is guarded harder than its removal.
 *
 * <p><strong>Owned by a Party, not a Customer, and by raw {@code UUID}s, deliberately.</strong>
 * A beneficiary is a person's saved address book entry, and it outlives any one commercial
 * relationship — the consent precedent ({@code ADR-0037}'s owner), and the plan's own ownership
 * line. Both references are raw {@code UUID}s because {@code party} and {@code accounts} own
 * the typed identifiers and this module cannot see them (the {@link Transfer#customerId()}
 * precedent, reasoning recorded there); no cross-schema FK backs either (ADR-0029's rule).
 *
 * <p><strong>The display name restates {@code PartyName}'s rule, and the duplication is the
 * recorded cost of the boundary.</strong> Non-blank, bounded, and free of the five Unicode
 * categories no name contains ({@code Cc}, {@code Cf}, {@code Cs}, {@code Co}, {@code Cn}) —
 * a person names people, so the value is {@code RESTRICTED-PII} and a control character in it
 * is a forged log line waiting for the first renderer. {@code PartyName} itself is
 * {@code party}'s and module isolation forbids the import; restating the mechanism rather than
 * moving proven code is the {@code DocumentCipher} precedent. No charset restriction beyond
 * that, for {@code PartyName}'s own reason: a rule narrow enough to be a control rejects real
 * people's names.
 */
public record Beneficiary(
        BeneficiaryId id,
        UUID partyId,
        String displayName,
        UUID destinationAccountId,
        BeneficiaryStatus status,
        Instant createdAt,
        Optional<Instant> removedAt) {

    /** Matches {@code V003}'s length CHECK, so a value that constructs here always stores. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 200;

    /** The five Unicode categories that contain no character of any name (the PartyName rule). */
    private static final Pattern FORBIDDEN =
            Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]");

    public Beneficiary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(removedAt, "removedAt must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("a beneficiary's display name must not be blank");
        }
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            // The bound and not the value (INV-AUD-02): the name is RESTRICTED-PII.
            throw new IllegalArgumentException(
                    "a beneficiary's display name must not exceed "
                            + MAX_DISPLAY_NAME_LENGTH
                            + " characters");
        }
        if (FORBIDDEN.matcher(displayName).find()) {
            throw new IllegalArgumentException(
                    "a beneficiary's display name must not contain control, format, surrogate,"
                            + " private-use or unassigned characters");
        }
        // Coherence both directions (INV-LIFE-02's shape): the status and the removal instant
        // are one fact, refused on read-back too - defence in depth ahead of V003's CHECK.
        if (status == BeneficiaryStatus.REMOVED && removedAt.isEmpty()) {
            throw new IllegalArgumentException("a removed beneficiary records when it was removed");
        }
        if (status == BeneficiaryStatus.ACTIVE && removedAt.isPresent()) {
            throw new IllegalArgumentException("a live beneficiary has no removal instant");
        }
        if (removedAt.isPresent() && removedAt.get().isBefore(createdAt)) {
            throw new IllegalArgumentException("a beneficiary cannot be removed before it exists");
        }
    }

    /** A newly saved destination: {@code ACTIVE} from birth — saved is the only way in. */
    public static Beneficiary create(
            BeneficiaryId id,
            UUID partyId,
            String displayName,
            UUID destinationAccountId,
            Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new Beneficiary(
                id,
                partyId,
                displayName,
                destinationAccountId,
                BeneficiaryStatus.ACTIVE,
                Instant.now(clock),
                Optional.empty());
    }

    /** A stored row, already validated by the schema; the constructor re-judges coherence. */
    public static Beneficiary rehydrate(
            BeneficiaryId id,
            UUID partyId,
            String displayName,
            UUID destinationAccountId,
            BeneficiaryStatus status,
            Instant createdAt,
            Instant removedAt) {
        return new Beneficiary(
                id,
                partyId,
                displayName,
                destinationAccountId,
                status,
                createdAt,
                Optional.ofNullable(removedAt));
    }

    /**
     * The one transition: {@code ACTIVE → REMOVED}.
     *
     * @throws IllegalBeneficiaryTransitionException from any other state ({@code INV-LIFE-02},
     *     {@code INV-LIFE-04}) — the aggregate refuses, not merely the store's conditional
     */
    public Beneficiary remove(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(BeneficiaryStatus.REMOVED)) {
            throw new IllegalBeneficiaryTransitionException(id, status, BeneficiaryStatus.REMOVED);
        }
        return new Beneficiary(
                id,
                partyId,
                displayName,
                destinationAccountId,
                BeneficiaryStatus.REMOVED,
                createdAt,
                Optional.of(Instant.now(clock)));
    }

    /**
     * Names the beneficiary and its status, never the display name ({@code INV-AUD-02}): a
     * record's generated {@code toString} prints every component, and the name is
     * {@code RESTRICTED-PII}.
     */
    @Override
    public String toString() {
        return "Beneficiary[id=" + id + ", status=" + status + "]";
    }
}
