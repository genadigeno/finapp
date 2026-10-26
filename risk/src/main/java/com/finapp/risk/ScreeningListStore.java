package com.finapp.risk;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The screening list versions' rows (`P13-TSK-022`; {@code risk V017}'s {@code screening_list_version}) - every write one
 * statement on risk's own table, conditional on the state it leaves; the stamps the trigger's on the database clock; no
 * {@code DELETE}.
 */
public interface ScreeningListStore {

    /** A version recorded: its id, list, label, the list's next ordinal, the recorder, the recording's correlation. */
    record NewVersion(ScreeningListVersionId id, ScreeningList list, String version, int ordinal, Actor recorder,
            String correlationId) {
        public NewVersion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(list, "list");
            ScreeningListVersion.label(version);
            Objects.requireNonNull(recorder, "recorder");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /**
     * (A0′) the list's administration lock - advisory namespace {@code 13}, keyed {@code hashtext('screening_list:' ||
     * list)} - held to commit: every recording and activation of the list is serialised behind it.
     */
    void lockList(Connection unitOfWork, ScreeningList list);

    /** The list's highest ordinal, 0 when it has none - read under {@link #lockList}. */
    int maxOrdinal(Connection unitOfWork, ScreeningList list);

    /**
     * Inserts {@code version} {@code RECORDED} - {@code ON CONFLICT (list, version) DO NOTHING}: empty when the label is
     * already recorded for the list.
     */
    Optional<ScreeningListVersion> insert(Connection unitOfWork, NewVersion version);

    Optional<ScreeningListVersion> find(Connection unitOfWork, ScreeningListVersionId id);

    /** The version of {@code list} labelled {@code version}, if recorded. */
    Optional<ScreeningListVersion> findByLabel(Connection unitOfWork, ScreeningList list, String version);

    /** (R9) the version row {@code FOR UPDATE}. */
    Optional<ScreeningListVersion> lock(Connection unitOfWork, ScreeningListVersionId id);

    /** The list's {@code ACTIVE} version {@code FOR UPDATE}, if one is. */
    Optional<ScreeningListVersion> lockActive(Connection unitOfWork, ScreeningList list);

    /** {@code ACTIVE → RETIRED}, its enqueue permit cleared; false when it was not active. */
    boolean retire(Connection unitOfWork, ScreeningListVersionId id);

    /**
     * {@code RECORDED → ACTIVE} by {@code activator} - the enqueue due at once, the correlation the activation's; empty when
     * it was not recorded.
     */
    Optional<ScreeningListVersion> activate(Connection unitOfWork, ScreeningListVersionId id, Actor activator,
            String correlationId);

    /**
     * The enqueue's claim: the oldest {@code ACTIVE} version whose enqueue permit has come on the database clock,
     * {@code FOR UPDATE SKIP LOCKED} (R9) - held to the batch's commit, so ten enqueuers take a version one batch at a time.
     */
    Optional<ScreeningListVersion> claimEnqueueDue(Connection unitOfWork);

    /**
     * The claimed version's enqueue advanced: the cursor moved to {@code cursor} (forward only), {@code born} permits
     * counted, the permit due again at once - or cleared, the census complete, when {@code complete}.
     */
    ScreeningListVersion advanceEnqueue(Connection unitOfWork, ScreeningListVersionId id, Optional<UUID> cursor, int born,
            boolean complete);
}
