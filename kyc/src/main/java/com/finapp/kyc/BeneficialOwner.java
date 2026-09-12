package com.finapp.kyc;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One node of a KYB case's beneficial-ownership graph (`P2-TSK-015`, `PHASE_2_PLAN.md` §4).
 *
 * <p>A natural-person Party who owns or controls the organisation under verification. The
 * glossary's structural claim — the graph <em>"is recursive, and it terminates in Parties who
 * must each be verified"</em> — is carried by two references: {@link #ownerPartyId()} names the
 * person (a raw value, the {@code kyc_case.customerId} pattern — no foreign key and no
 * compile-time sight of {@code party}), and {@link #verificationCaseId()} pins <strong>which
 * verification the organisation's decision will rest on</strong> — a {@code KYC}-kind case in
 * this same schema, enforced by V008's composite foreign key. Pinned at declaration, the
 * {@code INV-HIST-04} shape: the owner may be re-verified later under a new case, and that
 * changes nothing about what <em>this</em> graph rested on.
 *
 * <h2>The row is evidence, so it is append-only and never edited</h2>
 *
 * <p>The owner set is part of what the decision rests on ({@code INV-KYC-02}): rows carry no
 * {@code UPDATE} or {@code DELETE} grant, declarations are refused once the case reaches
 * {@code READY_FOR_DECISION}, and a wrong declaration is corrected the way a wrong decision is
 * — a new case, never an edit ({@code INV-LIFE-04}'s asymmetry). Together those make "the
 * owner rows of this case" exactly and immutably the set the decision was taken over, with no
 * join table needed.
 *
 * <h2>Qualification: a stake, a control role, or both</h2>
 *
 * <p>The stake is <strong>basis points</strong> — an integer, 1..10000 — because a percentage
 * is a number that must never be floating point on a financial platform ({@code INV-MON-01}'s
 * hygiene outside money), and basis points are the finest granularity ownership registers use.
 * An owner with neither a stake nor a role is unrepresentable: it would be a person on the
 * graph for no stated reason, which is exactly what a reviewer cannot defend.
 */
public final class BeneficialOwner {

    private final BeneficialOwnerId id;
    private final KycCaseId caseId;
    private final UUID ownerPartyId;
    private final KycCaseId verificationCaseId;
    private final OptionalInt stakeBasisPoints;
    private final Optional<ControlRole> controlRole;
    private final Instant declaredAt;

    private BeneficialOwner(
            BeneficialOwnerId id,
            KycCaseId caseId,
            UUID ownerPartyId,
            KycCaseId verificationCaseId,
            OptionalInt stakeBasisPoints,
            Optional<ControlRole> controlRole,
            Instant declaredAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        this.ownerPartyId = Objects.requireNonNull(ownerPartyId, "ownerPartyId must not be null");
        this.verificationCaseId =
                Objects.requireNonNull(verificationCaseId, "verificationCaseId must not be null");
        this.stakeBasisPoints =
                Objects.requireNonNull(stakeBasisPoints, "stakeBasisPoints must not be null");
        this.controlRole = Objects.requireNonNull(controlRole, "controlRole must not be null");
        this.declaredAt = Objects.requireNonNull(declaredAt, "declaredAt must not be null");
        if (stakeBasisPoints.isEmpty() && controlRole.isEmpty()) {
            throw new IllegalArgumentException(
                    "an owner qualifies by a stake, a control role, or both - one with neither"
                            + " is a person on the graph for no stated reason");
        }
        stakeBasisPoints.ifPresent(
                stake -> {
                    if (stake < 1 || stake > 10_000) {
                        throw new IllegalArgumentException(
                                "a stake is 1..10000 basis points; was " + stake);
                    }
                });
        if (verificationCaseId.equals(caseId)) {
            throw new IllegalArgumentException(
                    "a case cannot be its own owner's verification - the graph terminates in"
                            + " KYC-kind cases (V008's composite FK is the schema's copy of this"
                            + " rule)");
        }
    }

    /** Declares an owner onto a KYB case, pinning the verification the graph rests on. */
    public static BeneficialOwner declare(
            IdGenerator ids,
            Clock clock,
            KycCaseId caseId,
            UUID ownerPartyId,
            KycCaseId verificationCaseId,
            OptionalInt stakeBasisPoints,
            Optional<ControlRole> controlRole) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new BeneficialOwner(
                BeneficialOwnerId.next(ids),
                caseId,
                ownerPartyId,
                verificationCaseId,
                stakeBasisPoints,
                controlRole,
                Instant.now(clock));
    }

    /** Reconstitutes from storage. Applies no rules the row has not already satisfied. */
    public static BeneficialOwner rehydrate(
            BeneficialOwnerId id,
            KycCaseId caseId,
            UUID ownerPartyId,
            KycCaseId verificationCaseId,
            OptionalInt stakeBasisPoints,
            Optional<ControlRole> controlRole,
            Instant declaredAt) {
        return new BeneficialOwner(
                id, caseId, ownerPartyId, verificationCaseId, stakeBasisPoints, controlRole,
                declaredAt);
    }

    public BeneficialOwnerId id() {
        return id;
    }

    /** The KYB case whose graph this owner belongs to. */
    public KycCaseId caseId() {
        return caseId;
    }

    /** The person, as a Party — a value, never a foreign key (ADR-0029's pattern). */
    public UUID ownerPartyId() {
        return ownerPartyId;
    }

    /** The KYC-kind case whose terminal outcome is this owner's verification. */
    public KycCaseId verificationCaseId() {
        return verificationCaseId;
    }

    /** Ownership stake in basis points (1..10000), if the owner qualifies by stake. */
    public OptionalInt stakeBasisPoints() {
        return stakeBasisPoints;
    }

    /** The control role, if the owner qualifies by control. */
    public Optional<ControlRole> controlRole() {
        return controlRole;
    }

    public Instant declaredAt() {
        return declaredAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BeneficialOwner owner && id.equals(owner.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers and enumerated names only ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BeneficialOwner[" + id + ", case=" + caseId + ", owner=" + ownerPartyId
                + ", verification=" + verificationCaseId + "]";
    }
}
