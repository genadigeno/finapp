package com.finapp.risk.testing;

import com.finapp.risk.CustomerRiskProfile;
import com.finapp.risk.CustomerRiskProfileStore;
import com.finapp.risk.CustomerRiskProfileVersion;
import com.finapp.risk.ProfileCause;
import com.finapp.risk.RiskRating;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer risk profile store in memory (`P13-TSK-007`) - for the hermetic suites of the recompute, the hint, the
 * reads and credit's seam adapter. It holds what {@code risk V006}'s triggers hold: a head born unassessed, a version
 * numbered without a gap, no recompute's version after a {@code PROHIBITED} one, the head moved one version at a time
 * beside a version of the same rating, the permit on a clock the suite moves ({@link #now}). It cannot hold the
 * database's locks: those are {@code ProfileDatabaseTest}'s subject. {@link #log} records each call, in order.
 */
public final class InMemoryCustomerRiskProfileStore implements CustomerRiskProfileStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-23T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    private final Map<UUID, CustomerRiskProfile> heads = new LinkedHashMap<>();
    private final Map<UUID, List<CustomerRiskProfileVersion>> versions = new LinkedHashMap<>();

    /** Seeds a version as a disposition would write it - `-016`'s act, here only to stand a party at a rating. */
    public void seedDisposition(UUID partyId, RiskRating rating) {
        CustomerRiskProfile head = heads.computeIfAbsent(partyId, this::unassessed);
        int next = head.currentVersion() + 1;
        insertVersion(null, new CustomerRiskProfileVersion(partyId, next, rating, ProfileCause.DISPOSITION, List.of(),
                Optional.empty(), Optional.of(UUID.randomUUID()), "seeded-disposition", Optional.empty()));
        heads.put(partyId, new CustomerRiskProfile(partyId, next, Optional.of(rating), head.nextRecomputeAt(),
                head.recomputedAt()));
        log.clear();
    }

    /** The head as stored, if any. */
    public Optional<CustomerRiskProfile> head(UUID partyId) {
        return Optional.ofNullable(heads.get(partyId));
    }

    @Override
    public void pullForward(Connection unitOfWork, UUID partyId) {
        log.add("pull " + partyId);
        CustomerRiskProfile head = heads.get(partyId);
        if (head == null) {
            heads.put(partyId, unassessed(partyId));
            return;
        }
        Instant earlier = head.nextRecomputeAt().isBefore(now) ? head.nextRecomputeAt() : now;
        heads.put(partyId, new CustomerRiskProfile(partyId, head.currentVersion(), head.rating(), earlier,
                head.recomputedAt()));
    }

    @Override
    public Optional<CustomerRiskProfile> claimDue(Connection unitOfWork) {
        log.add("claim");
        return heads.values().stream()
                .filter(head -> !head.nextRecomputeAt().isAfter(now))
                .min(Comparator.comparing(CustomerRiskProfile::nextRecomputeAt));
    }

    @Override
    public CustomerRiskProfile lockOrCreate(Connection unitOfWork, UUID partyId) {
        log.add("lock " + partyId);
        return heads.computeIfAbsent(partyId, this::unassessed);
    }

    @Override
    public Optional<CustomerRiskProfile> read(Connection unitOfWork, UUID partyId) {
        log.add("read " + partyId);
        return Optional.ofNullable(heads.get(partyId));
    }

    @Override
    public void insertVersion(Connection unitOfWork, CustomerRiskProfileVersion version) {
        log.add("version " + version.partyId() + " v" + version.version());
        if (!heads.containsKey(version.partyId())) {
            throw new IllegalStateException("customer_risk_profile_version_head_fk");
        }
        List<CustomerRiskProfileVersion> history = versions.computeIfAbsent(version.partyId(), party -> new ArrayList<>());
        if (version.version() != history.size() + 1) {
            throw new IllegalStateException("a customer risk profile version is numbered without a gap");
        }
        if (!history.isEmpty() && history.get(history.size() - 1).rating() == RiskRating.PROHIBITED
                && version.cause() != ProfileCause.DISPOSITION) {
            throw new IllegalStateException("a recompute never follows a PROHIBITED version");
        }
        if (version.dispositionId().isPresent()
                && versionOfDisposition(unitOfWork, version.dispositionId().get()).isPresent()) {
            // risk V012's customer_risk_profile_version_one_per_disposition.
            throw new IllegalStateException("customer_risk_profile_version_one_per_disposition");
        }
        history.add(new CustomerRiskProfileVersion(version.partyId(), version.version(), version.rating(),
                version.cause(), version.reasons(), version.evaluation(), version.dispositionId(),
                version.correlationId(), Optional.of(now)));
    }

    @Override
    public void advance(Connection unitOfWork, UUID partyId, int fromVersion, int toVersion, RiskRating rating,
            Duration permitDelay) {
        log.add("advance " + partyId + " v" + fromVersion + "->v" + toVersion);
        CustomerRiskProfile head = Objects.requireNonNull(heads.get(partyId), "the head");
        if (head.currentVersion() != fromVersion || toVersion != fromVersion + 1) {
            throw new IllegalStateException("the profile being advanced is not at the version its lock read");
        }
        List<CustomerRiskProfileVersion> history = versions.getOrDefault(partyId, List.of());
        if (history.size() != toVersion || history.get(toVersion - 1).rating() != rating) {
            throw new IllegalStateException("a customer risk profile moves one version at a time, beside that version");
        }
        heads.put(partyId, new CustomerRiskProfile(partyId, toVersion, Optional.of(rating), now.plus(permitDelay),
                Optional.of(now)));
    }

    @Override
    public void restamp(Connection unitOfWork, UUID partyId, Duration permitDelay, boolean evaluated) {
        log.add("restamp " + partyId + (evaluated ? " evaluated" : ""));
        CustomerRiskProfile head = Objects.requireNonNull(heads.get(partyId), "the head");
        heads.put(partyId, new CustomerRiskProfile(partyId, head.currentVersion(), head.rating(), now.plus(permitDelay),
                evaluated ? Optional.of(now) : head.recomputedAt()));
    }

    @Override
    public List<CustomerRiskProfileVersion> versions(Connection unitOfWork, UUID partyId) {
        log.add("versions " + partyId);
        return List.copyOf(versions.getOrDefault(partyId, List.of()));
    }

    @Override
    public Optional<CustomerRiskProfileVersion> versionOfDisposition(Connection unitOfWork, UUID dispositionId) {
        Objects.requireNonNull(dispositionId, "dispositionId");
        return versions.values().stream().flatMap(List::stream)
                .filter(version -> version.dispositionId().filter(dispositionId::equals).isPresent())
                .findFirst();
    }

    private CustomerRiskProfile unassessed(UUID partyId) {
        return new CustomerRiskProfile(partyId, 0, Optional.empty(), now, Optional.empty());
    }
}
