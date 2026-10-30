package com.finapp.app.accounting;

import com.finapp.accounting.PostingCutoff;
import com.finapp.ledger.PostingCutoffs;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * accounting's {@link PostingCutoff} over the ledger's own {@link PostingCutoffs} API (`P14-TSK-004`; ADR-0113 decision
 * 3, ADR-0114 decisions 1-2; {@code INV-ACC-08}) - in {@code app}, the one module that sees both. It adds nothing: the
 * lock (namespace {@code 14} key {@code cutoff} exclusive), the conditional {@code UPDATE}, the event row and every
 * refusal are the ledger's, on the caller's unit of work - accounting's lock, unlock or reopen transaction
 * ({@code P14-TSK-005}). The ledger's {@code IllegalCutoffMoveException} is an {@link IllegalStateException}, so it
 * crosses the port as the JDK type the port documents.
 */
public final class LedgerPostingCutoff implements PostingCutoff {

    private final PostingCutoffs cutoffs;

    public LedgerPostingCutoff(PostingCutoffs cutoffs) {
        this.cutoffs = Objects.requireNonNull(cutoffs, "cutoffs must not be null");
    }

    @Override
    public Optional<LocalDate> closedThrough(Connection unitOfWork) {
        return cutoffs.closedThrough(unitOfWork);
    }

    @Override
    public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
        return cutoffs.advance(unitOfWork, from, through, reason) == PostingCutoffs.Outcome.MOVED;
    }

    @Override
    public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
        return cutoffs.moveBack(unitOfWork, from, to, reason) == PostingCutoffs.Outcome.MOVED;
    }
}
