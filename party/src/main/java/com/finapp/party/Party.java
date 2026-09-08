package com.finapp.party;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Who exists (ADR-0029).
 *
 * <p>A Party is the answer to "who is this", and nothing else. It holds no commercial
 * relationship, no login, and no permission — those are {@link Customer} and
 * {@code identity.Identity}, and keeping them apart is this phase's top named risk
 * (`DELIVERY_PLAN.md` §17).
 *
 * <h2>Why a Party has no lifecycle</h2>
 *
 * <p>Every other aggregate here has a state machine and this one does not, which looks like an
 * omission and is the design. A Party is a statement that someone exists; existence does not have
 * states. What people reach for — "inactive", "closed", "archived" — are all statements about a
 * <em>relationship</em> or a <em>login</em>, and each already has somewhere to live. Adding a
 * status here would mean the same fact could be recorded in two places and disagree.
 *
 * <p>The consequence is stated plainly: <strong>a Party is never deleted and never closed.</strong>
 * A person who ceases to be a customer is a Party with a {@code CLOSED} Customer. A person whose
 * login is retired is a Party with a {@code CLOSED} Identity. Erasure under a right-to-be-forgotten
 * request is a Phase 15 concern and is not deletion of this row, because financial records
 * reference it and {@code INV-HIST-01} forbids rewriting them.
 *
 * <h2>What it deliberately does not hold</h2>
 *
 * <p>No email, no phone number, no address. Contact details belong to a profile that changes on its
 * own schedule, and putting a changeable contact channel on the identity of a person is how an
 * email address becomes a primary key by accident — the mistake `PHASE_1_PLAN.md` §4 calls out for
 * {@code EmailAddress} and the same mistake one level up. Profile data arrives with the task that
 * needs it.
 *
 * <p>The display name is here because a Party with no name is not usefully identifiable by a human
 * in an audit trail or a support conversation, and it is {@code RESTRICTED-PII} accordingly.
 */
public final class Party {

    private final PartyId id;
    private final PartyKind kind;
    private final PartyName name;
    private final Instant registeredAt;

    private Party(PartyId id, PartyKind kind, PartyName name, Instant registeredAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.registeredAt = Objects.requireNonNull(registeredAt, "registeredAt must not be null");
    }

    /**
     * Registers a Party.
     *
     * <p>The clock is injected rather than read, because {@code registeredAt} is a fact that has to
     * be reproducible in a test at a boundary and in a replay years later — the reasoning in
     * {@code DOMAIN_MODEL.md} §Time, enforced by {@code noAmbientTimeIsRead}.
     */
    public static Party register(IdGenerator ids, Clock clock, PartyKind kind, PartyName name) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new Party(PartyId.next(ids), kind, name, Instant.now(clock));
    }

    /** Reconstitutes a Party from storage. Applies no rules: the row was already valid. */
    public static Party rehydrate(PartyId id, PartyKind kind, PartyName name, Instant registeredAt) {
        return new Party(id, kind, name, registeredAt);
    }

    /**
     * A new Party with a different display name (`P1-TSK-030`).
     *
     * <p><strong>Not a state transition, and Party deliberately has no lifecycle</strong>
     * ({@code P1-TSK-005}): existence has no states, and every state people reach for is a statement
     * about a <em>relationship</em> or a <em>login</em>, each of which has its own table. So there
     * is no status to move and no {@code status_changed_at} to stamp.
     *
     * <p>There is no {@code updated_at} column either, and that is deliberate rather than an
     * omission: <em>when</em> and <em>by whom</em> belong on the audit record, and a second answer
     * in the row would be free to disagree with it.
     */
    public Party rename(PartyName newName) {
        Objects.requireNonNull(newName, "newName must not be null");
        return new Party(id, kind, newName, registeredAt);
    }

    public PartyId id() {
        return id;
    }

    public PartyKind kind() {
        return kind;
    }

    public PartyName name() {
        return name;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    /**
     * Identity is the identifier, not the contents.
     *
     * <p>Two Party objects describing the same Party are the same Party even if one was loaded
     * before a rename. Comparing by value would make "is this the same person" depend on how fresh
     * each copy is.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Party party && id.equals(party.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Deliberately excludes the name.
     *
     * <p>A {@code toString} is what ends up in a log line or an exception message by accident, and
     * the name is {@code RESTRICTED-PII} ({@code INV-AUD-02}). The identifier is enough to find the
     * row and carries nothing about the person.
     */
    @Override
    public String toString() {
        return "Party[" + id + ", " + kind + "]";
    }
}
