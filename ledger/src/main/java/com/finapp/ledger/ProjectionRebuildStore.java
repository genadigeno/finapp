package com.finapp.ledger;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for projection rebuilds (`P14-TSK-026`, {@code ledger V031}): the row born {@code PROPOSED} with the
 * proposer's observation, and its one decision - a conditional {@code UPDATE ... WHERE status = 'PROPOSED'} of the
 * decision and approval columns, the only ones the application role may write.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface ProjectionRebuildStore<T> {

    /**
     * Births the rebuild {@code PROPOSED}.
     *
     * @throws ProjectionRebuildStandingException another rebuild of the account already stands (the partial unique index,
     *     for a proposer that raced past the domain's read)
     */
    void insert(T unitOfWork, ProjectionRebuild rebuild);

    /** The rebuild, unlocked. */
    Optional<ProjectionRebuild> findById(T unitOfWork, ProjectionRebuildId id);

    /** The rebuild {@code FOR UPDATE} - the first lock an approval or a rejection takes. */
    Optional<ProjectionRebuild> lockById(T unitOfWork, ProjectionRebuildId id);

    /** The account's {@code PROPOSED} rebuild, unlocked - at most one stands. */
    Optional<ProjectionRebuild> standingFor(T unitOfWork, LedgerAccountId account);

    /**
     * {@code PROPOSED -> to}, with the approval's figures for {@code APPLIED} and {@code UNCHANGED}.
     *
     * @return false when the rebuild was not {@code PROPOSED}
     */
    boolean decide(
            T unitOfWork,
            ProjectionRebuildId id,
            ProjectionRebuildStatus to,
            String decidedBy,
            Instant decidedAt,
            Optional<ProjectionRebuild.Approval> approval);
}
