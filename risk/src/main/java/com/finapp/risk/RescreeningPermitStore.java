package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The rescreening permits' rows (`P13-TSK-022`; {@code risk V018}'s {@code rescreening_permit}) - every write one
 * statement on risk's own table, conditional on the state and the attempt it leaves; the stamps and the first permit the
 * trigger's on the database clock; no {@code DELETE}. Each read returns the permit with its version's list and
 * activation instant.
 */
public interface RescreeningPermitStore {

    /** A permit enqueued: its id, party, version and the activation's correlation. */
    record NewPermit(RescreeningPermitId id, UUID partyId, ScreeningListVersionId listVersionId, String correlationId) {
        public NewPermit {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(listVersionId, "listVersionId");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /**
     * Inserts {@code permit} {@code PENDING}, due at once - {@code ON CONFLICT (party_id, list_version_id) DO NOTHING}
     * ({@code rescreening_permit_once}, {@code INV-RSK-06}): false when the party already holds a permit of the version.
     */
    boolean enqueue(Connection unitOfWork, NewPermit permit);

    /**
     * The schedule's claim: the oldest {@code PENDING} permit whose permit has come on the database clock, {@code FOR
     * UPDATE SKIP LOCKED} (R9), its permit moved {@code lease} ahead and one attempt counted in the same statement - so ten
     * sweepers claim a permit once per lease.
     */
    Optional<RescreeningPermit> claimNextDue(Connection unitOfWork, Duration lease);

    /** (R9) the permit row {@code FOR UPDATE} - the conclusion's first lock. */
    Optional<RescreeningPermit> lock(Connection unitOfWork, RescreeningPermitId id);

    Optional<RescreeningPermit> find(Connection unitOfWork, RescreeningPermitId id);

    /**
     * {@code PENDING → DONE(outcome)} naming {@code signal}, conditional on {@code attempt} being the latest claim's; false
     * when it is not (another claim or conclusion came first).
     */
    boolean conclude(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Outcome outcome,
            Optional<RiskSignalId> signal);

    /** {@code PENDING → FAILED(cause)}, conditional on {@code attempt}. */
    boolean fail(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Cause cause);

    /** {@code PENDING → PENDING}: re-permitted {@code backoff} ahead with {@code cause}, conditional on {@code attempt}. */
    boolean defer(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Cause cause,
            Duration backoff);

    /** How many permits are {@code PENDING}, counted up to {@code cap} (saturating) - the backlog gauge's figure. */
    long pending(Connection unitOfWork, long cap);
}
