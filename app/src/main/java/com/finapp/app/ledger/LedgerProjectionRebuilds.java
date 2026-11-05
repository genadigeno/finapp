package com.finapp.app.ledger;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerErrorCode;
import com.finapp.ledger.ProjectionFigures;
import com.finapp.ledger.ProjectionNotDriftingException;
import com.finapp.ledger.ProjectionNotRebuildableException;
import com.finapp.ledger.ProjectionRebuild;
import com.finapp.ledger.ProjectionRebuildAccountNotFoundException;
import com.finapp.ledger.ProjectionRebuildId;
import com.finapp.ledger.ProjectionRebuildNotFoundException;
import com.finapp.ledger.ProjectionRebuildStandingException;
import com.finapp.ledger.ProjectionRebuildStatus;
import com.finapp.ledger.ProjectionRebuilds;
import com.finapp.ledger.RebuildNotOpenException;
import com.finapp.ledger.RebuildSelfApprovalRefusedException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code /v1/ledger/projection-rebuilds} slice behind {@link ProjectionRebuildController} (`P14-TSK-026`, ADR-0119
 * decision 1): runs each act in one transaction ({@code ledgerTransactions}: {@code READ COMMITTED}, which the rebuild's
 * lock seam requires), translates the ledger's refusals onto the error contract, and counts each concluded act after its
 * commit.
 *
 * <p><strong>The figures leave as decimal strings at the projection's scale</strong> - an operator holding
 * {@code LEDGER_ADJUST} reads amounts, as an adjustment's approver does (the `P2-TSK-016` reviewer-sees-everything
 * argument); they are display and evidence, never a decision's input ({@code INV-BAL-05}). {@code available} is
 * {@code posted - holds} ({@code INV-BAL-04}'s presentation), computed exactly. A malformed identifier names nothing: one
 * {@code 404} with unknown (`P1-TSK-016`).
 */
@RequiredArgsConstructor
public final class LedgerProjectionRebuilds {

    static final String NOT_FOUND_DETAIL = "no such projection rebuild or ledger account";

    @NonNull private final ProjectionRebuilds rebuilds;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final ProjectionRebuildMetrics metrics;

    /** Three figures of a projection, as decimal strings in the rebuild's currency, and the applied-entry count. */
    public record FiguresView(String posted, String holds, String available, long appliedEntries) {}

    /**
     * A rebuild in full - what the approver reads before deciding, and every act's answer. Absent figures are {@code null},
     * never invented: {@code observedRow} when the proposer found no row, {@code before} when the approval found none,
     * {@code before}, {@code after} and {@code driftAsProposed} until an approval.
     *
     * @param driftAsProposed whether the approval found the drift the proposal recorded - postings or another repair may
     *     have moved it; the approval wrote its own reading either way
     * @param replayed this call found the act already done and wrote nothing
     */
    public record RebuildView(
            String rebuildId,
            String accountId,
            String currency,
            String status,
            String reason,
            String proposedBy,
            String proposedAt,
            FiguresView observedRow,
            FiguresView observedDerived,
            String decidedBy,
            String decidedAt,
            FiguresView before,
            FiguresView after,
            Boolean driftAsProposed,
            boolean replayed) {}

    /** Records the proposal, or replays it; writes nothing to the projection. */
    public RebuildView propose(String rawAccountId, String reason, String idempotencyKey) {
        LedgerAccountId account = accountOrAbsent(rawAccountId);
        return acted(() -> inOneTransaction(uow -> rebuilds.propose(uow, account, reason, idempotencyKey)));
    }

    /** The rebuild in full, or the one {@code 404}. */
    public RebuildView view(String rawId) {
        ProjectionRebuildId id = rebuildOrAbsent(rawId);
        return render(inOneTransaction(uow -> rebuilds.find(uow, id)).orElseThrow(LedgerProjectionRebuilds::notFound), false);
    }

    /** Approves as a second person, under the posting's locks: {@code APPLIED} or {@code UNCHANGED}. */
    public RebuildView approve(String rawId, String idempotencyKey) {
        ProjectionRebuildId id = rebuildOrAbsent(rawId);
        return acted(() -> inOneTransaction(uow -> rebuilds.approve(uow, id, idempotencyKey)));
    }

    /** Rejects or withdraws; converges on a rebuild already rejected. */
    public RebuildView reject(String rawId, String idempotencyKey) {
        ProjectionRebuildId id = rebuildOrAbsent(rawId);
        return acted(() -> inOneTransaction(uow -> rebuilds.reject(uow, id, idempotencyKey)));
    }

    /** One act: committed, then counted (a replay or a converge counts nothing), or refused, counted and translated. */
    private RebuildView acted(Supplier<ProjectionRebuilds.Outcome> act) {
        ProjectionRebuilds.Outcome outcome;
        try {
            outcome = act.get();
        } catch (ProjectionRebuildNotFoundException | ProjectionRebuildAccountNotFoundException unknown) {
            throw notFound();
        } catch (ProjectionNotDriftingException refusal) {
            throw refused(LedgerErrorCode.PROJECTION_NOT_DRIFTING, refusal);
        } catch (ProjectionRebuildStandingException refusal) {
            throw refused(LedgerErrorCode.PROJECTION_REBUILD_STANDING, refusal);
        } catch (ProjectionNotRebuildableException refusal) {
            throw refused(LedgerErrorCode.PROJECTION_NOT_REBUILDABLE, refusal);
        } catch (RebuildSelfApprovalRefusedException refusal) {
            throw refused(LedgerErrorCode.REBUILD_SELF_APPROVAL_REFUSED, refusal);
        } catch (RebuildNotOpenException refusal) {
            throw refused(LedgerErrorCode.REBUILD_NOT_OPEN, refusal);
        }
        if (!outcome.replayed()) {
            metrics.count(outcome.rebuild().status().name().toLowerCase(Locale.ROOT));
        }
        return render(outcome.rebuild(), outcome.replayed());
    }

    static RebuildView render(ProjectionRebuild rebuild, boolean replayed) {
        return new RebuildView(
                rebuild.id().value().toString(),
                rebuild.account().value().toString(),
                rebuild.currency().code(),
                rebuild.status().name(),
                rebuild.reason(),
                rebuild.proposedBy(),
                rebuild.proposedAt().toString(),
                rebuild.observedRow().map(figures -> figures(figures, rebuild.scale())).orElse(null),
                figures(rebuild.observedDerived(), rebuild.scale()),
                rebuild.decidedBy().orElse(null),
                rebuild.decidedAt().map(Instant::toString).orElse(null),
                rebuild.approval().flatMap(ProjectionRebuild.Approval::before).map(figures -> figures(figures, rebuild.scale()))
                        .orElse(null),
                rebuild.approval().map(approval -> figures(approval.after(), rebuild.scale())).orElse(null),
                rebuild.status() == ProjectionRebuildStatus.APPLIED || rebuild.status() == ProjectionRebuildStatus.UNCHANGED
                        ? ProjectionRebuilds.driftAsProposed(rebuild) : null,
                replayed);
    }

    /** Exact: an unscaled long at the projection's scale, never a floating-point value (INV-MON-01). */
    static FiguresView figures(ProjectionFigures figures, int scale) {
        BigDecimal posted = BigDecimal.valueOf(figures.postedMinor(), scale);
        BigDecimal holds = BigDecimal.valueOf(figures.holdsMinor(), scale);
        return new FiguresView(posted.toPlainString(), holds.toPlainString(), posted.subtract(holds).toPlainString(),
                figures.appliedEntries());
    }

    private ApiException refused(ErrorCode code, RuntimeException cause) {
        metrics.count("refused");
        // The ledger's messages name the account and the rebuild, never a figure (INV-AUD-02).
        return new ApiException(code, "A projection rebuild was refused", cause.getMessage());
    }

    /** The one 404 for an unknown or malformed rebuild or account (`P1-TSK-016`). */
    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No projection rebuild or ledger account matches the request",
                NOT_FOUND_DETAIL);
    }

    private static ProjectionRebuildId rebuildOrAbsent(String raw) {
        try {
            return ProjectionRebuildId.of(UUID.fromString(Objects.requireNonNull(raw)));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static LedgerAccountId accountOrAbsent(String raw) {
        try {
            return LedgerAccountId.of(UUID.fromString(Objects.requireNonNull(raw)));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }
}
