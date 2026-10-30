package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the GL mapping versions (`P14-TSK-003`, {@code accounting V002}): the machine row
 * ({@code gl_mapping_version}, its decision and retirement columns the only ones that move), the content born with it
 * ({@code gl_account}, {@code gl_mapping_rule}, INSERT-only) and the history ({@code gl_mapping_version_event},
 * append-only).
 *
 * <p>The administration's lock order (T-map; {@code PHASE_14_PLAN.md} section 8.2's A2 then A4): advisory namespace
 * {@code 14} on {@code hashtext('mapping')} exclusively, then the version row {@code FOR UPDATE}, then the {@code ACTIVE}
 * row {@code FOR UPDATE}.
 */
public interface GlMappingStore {

    /** A2: the mapping administration lock - namespace {@code 14}, key {@code mapping}, blocking, transaction-scoped. */
    void lockMapping(Connection unitOfWork);

    /**
     * A2 shared: the same key, shared - a period lock's pin read (`P14-TSK-005`, the plan's section 8.3 row 8), so an
     * activation (exclusive) either committed before the read or re-reads the period no longer {@code OPEN} after it.
     */
    void lockMappingShared(Connection unitOfWork);

    /**
     * The activated version (ACTIVE or RETIRED) whose month range holds {@code month} - the version a lock pins; empty
     * when no version is in force for it (`P14-TSK-005`).
     */
    Optional<VersionRow> inForceFor(Connection unitOfWork, YearMonth month);

    /**
     * The earliest accounting period at or after {@code month} that is no longer {@code OPEN} - its mapping pinned; an
     * activation effective from {@code month} would be retroactive for it (`P14-TSK-005`, {@code INV-ACC-07}). Read under
     * A2 exclusive.
     */
    Optional<YearMonth> firstPinnedPeriodFrom(Connection unitOfWork, YearMonth month);

    /** The highest version number, 0 for none - read under A2. */
    int maxVersion(Connection unitOfWork);

    /** Births the version {@code PROPOSED}, its content and its history row; the database stamps the time. */
    void insertProposal(Connection unitOfWork, NewProposal proposal);

    /** The version's machine row, unlocked. */
    Optional<VersionRow> find(Connection unitOfWork, GlMappingVersionId id);

    /** The version's machine row {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, GlMappingVersionId id);

    /** The {@code ACTIVE} row {@code FOR UPDATE}. */
    Optional<VersionRow> lockActive(Connection unitOfWork);

    /** The {@code ACTIVE} row, unlocked - the reviewer's diff base. */
    Optional<VersionRow> active(Connection unitOfWork);

    /** The latest month any activated version (ACTIVE or RETIRED) takes effect from - the floor of the next activation. */
    Optional<YearMonth> latestEffectiveFrom(Connection unitOfWork);

    /** The version's content, as born. */
    GlMappingVersion content(Connection unitOfWork, GlMappingVersionId id);

    /** Retires the {@code ACTIVE} version, ending where its successor begins; {@code false} when it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, GlMappingVersionId id, YearMonth endsAt);

    /** Decides a {@code PROPOSED} version; {@code false} when it was no longer {@code PROPOSED} - nothing written. */
    boolean decide(Connection unitOfWork, GlMappingVersionId id, GlMappingVersionStatus to, Actor actor, String reason);

    /** Appends one history row. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    // ------------------------------------------------------------------ rows

    /** A proposal to birth. */
    record NewProposal(
            GlMappingVersionId id,
            int version,
            GlMappingVersion content,
            Actor actor,
            String reason,
            CorrelationId correlation,
            UUID historyId) {}

    /** A version's machine row - no reason (RESTRICTED-PII, never served). */
    record VersionRow(
            GlMappingVersionId id,
            int version,
            GlMappingVersionStatus status,
            YearMonth effectiveFrom,
            Optional<YearMonth> effectiveTo,
            String proposedBy,
            Instant proposedAt,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<Instant> retiredAt) {}

    /** One edge of the machine, as history. */
    record HistoryRow(
            UUID id,
            GlMappingVersionId versionId,
            Optional<GlMappingVersionStatus> from,
            GlMappingVersionStatus to,
            Actor actor,
            String reason,
            CorrelationId correlation) {}
}
