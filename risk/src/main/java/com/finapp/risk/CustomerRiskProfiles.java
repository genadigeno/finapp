package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer risk profile's reads (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.5;
 * {@code RISK_LIFECYCLES.md} section 4.9; {@code INV-RSK-10}, {@code INV-RSK-08}, {@code INV-RSK-07}).
 *
 * <ul>
 *   <li><strong>{@link #current}</strong> - the party's current rating and the version it is, read from the head with
 *       no lock on the caller's unit of work: what credit's seam answers (seam version 2, through {@code app}'s
 *       {@code RiskCreditRiskSignal}) and what `-004`'s evaluation reads for {@link RuleKind#PROFILE_RATING}. Empty
 *       while the party is unassessed - no profile, or one enrolled and not yet recomputed - which is
 *       <em>not</em> a clean bill of health: the seam answers {@code NOT_ASSESSED} and the reading is {@code ABSENT}
 *       (`DESIGN-007` D1). The caller records what it read; a later version applies to the next read.</li>
 *   <li><strong>{@link #history}</strong> - the analyst's door ({@code GET /v1/operator/risk/parties/{partyId}/profile},
 *       {@code RISK_INVESTIGATE}): the self-dealing judgement first ({@link RiskActingParty} - the analyst's own party,
 *       or an analyst placed in no party, is refused, fail closed), then the head and every version, and
 *       {@code risk.ProfileRead} written on the same unit of work - committed with the read, so nothing is served
 *       unrecorded. A refusal is recorded {@code FAILED} by {@link #recordRefusal} in a transaction of its own.</li>
 * </ul>
 */
public final class CustomerRiskProfiles {

    /** The audit target type of a profile read. */
    public static final String TARGET_TYPE = "customer_risk_profile";

    private final CustomerRiskProfileStore store;
    private final RiskActingParty actingParties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public CustomerRiskProfiles(CustomerRiskProfileStore store, RiskActingParty actingParties,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A party's current rating and the profile version it is. */
    public record CurrentRating(UUID partyId, int version, RiskRating rating) {
        public CurrentRating {
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(rating, "rating");
            if (version < 1) {
                throw new IllegalArgumentException("a current rating is a version's, counted from 1");
            }
        }

        /** Never the rating ({@code INV-RSK-08}). */
        @Override
        public String toString() {
            return "CurrentRating[" + partyId + ", v" + version + "]";
        }
    }

    /** A party's profile as the analyst reads it: the current rating, if assessed, and every version, oldest first. */
    public record History(UUID partyId, Optional<CurrentRating> current, List<CustomerRiskProfileVersion> versions) {
        public History {
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(current, "current");
            versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        }

        @Override
        public String toString() {
            return "History[" + partyId + ", " + versions.size() + " versions]";
        }
    }

    /** No profile exists for the party ({@code 404 risk.NotFound}). */
    public static final class ProfileNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProfileNotFound() {
            super("no customer risk profile answers the request");
        }
    }

    /** The reader may not read this party's profile ({@code 403 risk.SelfDealingRefused}) - nothing was read. */
    public static final class SelfDealingRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        /** Why: the reader's own party, or a reader the platform cannot place in any party. */
        public enum Cause { OWN_PARTY, UNPLACEABLE }

        private final Cause refusal;

        SelfDealingRefused(Cause refusal) {
            super("a holder never reads the risk profile of their own party; nothing was read");
            this.refusal = refusal;
        }

        /** Why the read was refused. */
        public Cause refusal() {
            return refusal;
        }
    }

    /** The party's current rating - empty while unassessed. No lock: the head as committed at this read. */
    public Optional<CurrentRating> current(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        return store.read(unitOfWork, partyId)
                .filter(CustomerRiskProfile::assessed)
                .map(head -> new CurrentRating(partyId, head.currentVersion(), head.rating().orElseThrow()));
    }

    /**
     * The analyst's read of {@code partyId}'s profile, recorded {@code risk.ProfileRead} on the same unit of work.
     *
     * @throws SelfDealingRefused the party is the reader's own, or the reader is placed in no party
     * @throws ProfileNotFound no profile exists for the party
     */
    public History history(Connection unitOfWork, UUID partyId, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        Optional<UUID> own = actingParties.partyOf(unitOfWork, actor);
        if (own.isEmpty()) {
            throw new SelfDealingRefused(SelfDealingRefused.Cause.UNPLACEABLE);
        }
        if (own.get().equals(partyId)) {
            throw new SelfDealingRefused(SelfDealingRefused.Cause.OWN_PARTY);
        }
        store.read(unitOfWork, partyId).orElseThrow(ProfileNotFound::new);
        // ONE statement for the versions: they are born in order, each with its head in one transaction, so a single
        // read is a consistent prefix of the history whatever commits beside it; the current rating is its newest.
        List<CustomerRiskProfileVersion> versions = store.versions(unitOfWork, partyId);
        Optional<CurrentRating> current = versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.get(versions.size() - 1))
                        .map(newest -> new CurrentRating(partyId, newest.version(), newest.rating()));
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.PROFILE_READ,
                TARGET_TYPE, partyId.toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("versions=" + versions.size())));
        return new History(partyId, current, versions);
    }

    /**
     * Records a refused read {@code FAILED} on {@code unitOfWork} - a transaction of its own, after the read's rolled
     * back, so the attempt survives. Its target the profile; the summary the act and the cause - never the party.
     */
    public void recordRefusal(Connection unitOfWork, UUID partyId, SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, partyId.toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation,
                Optional.of("act=profile-read, cause=" + refused.refusal().name() + " refused")));
    }
}
