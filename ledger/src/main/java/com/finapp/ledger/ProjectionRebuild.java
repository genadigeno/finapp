package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A four-eyes rebuild of one account's balance projection from its journal lines and {@code ACTIVE} holds
 * (`P14-TSK-026`; ADR-0119 decision 1; ADR-0041 rule 2; {@code INV-BAL-01}, {@code INV-BAL-02}, {@code INV-AUD-04}).
 *
 * <p><strong>Drift is the journal and its display copy disagreeing, and the journal is the authority.</strong> A rebuild
 * moves the projection to what the journal and the holds derive - never the journal to the projection, which is what an
 * adjustment would do and why one is wrong here ({@code CLAUDE.md} rules 5 and 11). It is a person's act, never the
 * verification's: {@code ProjectionVerification} reports and never repairs, because a ledger that corrected itself would
 * destroy the evidence. This record keeps it: what the proposer observed, what the approval found under the lock, what it
 * wrote.
 *
 * <p><strong>What counts as drift</strong> is the verification's own definition, restated once here for the two readings a
 * rebuild takes: a row absent while entries exist; or a row whose applied-entry count, settled balance or standing holds
 * differ from the derivation's. The scale is part of the row's identity: a row persisted at a scale the derivation does not
 * have is not rebuildable here ({@code INV-MON-03}, V006's grant), nor a row standing for an account with no entries
 * ({@code last_entry_seq >= 1} cannot say "none").
 *
 * <p><strong>What the approval attests.</strong> The approver decides that the account's projection be made what the
 * journal says <em>at the approval's moment</em>; the figures written are the approval's own reading under the posting's
 * locks, never the proposal's. Postings may land between the two acts, so the two drifts (proposed and found) are both
 * recorded and may differ. A rebuild whose approval finds nothing to correct is {@code UNCHANGED} and writes nothing to the
 * projection.
 */
public final class ProjectionRebuild {

    /** {@code ledger.projection_rebuild.reason}'s bound (V031). */
    public static final int MAX_REASON_LENGTH = 1000;

    /** What an approval found under the lock and what it wrote, or found already there. */
    public record Approval(Optional<ProjectionFigures> before, ProjectionFigures after) {

        public Approval {
            Objects.requireNonNull(before, "before must not be null");
            Objects.requireNonNull(after, "after must not be null");
        }

        /** True when the row was found and already said what the journal says. */
        public boolean unchanged() {
            return before.filter(after::equals).isPresent();
        }
    }

    private final ProjectionRebuildId id;
    private final LedgerAccountId account;
    private final CurrencyCode currency;
    private final int scale;
    private final ProjectionRebuildStatus status;
    private final String reason;
    private final String proposedBy;
    private final Instant proposedAt;
    private final Optional<ProjectionFigures> observedRow;
    private final ProjectionFigures observedDerived;
    private final Optional<String> decidedBy;
    private final Optional<Instant> decidedAt;
    private final Optional<Approval> approval;

    private ProjectionRebuild(
            ProjectionRebuildId id,
            LedgerAccountId account,
            CurrencyCode currency,
            int scale,
            ProjectionRebuildStatus status,
            String reason,
            String proposedBy,
            Instant proposedAt,
            Optional<ProjectionFigures> observedRow,
            ProjectionFigures observedDerived,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<Approval> approval) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.account = Objects.requireNonNull(account, "account must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        if (scale < 0 || scale > 9) {
            throw new IllegalArgumentException("a projection's scale is 0 to 9 (V006)");
        }
        this.scale = scale;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.proposedBy = Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        this.proposedAt = Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        this.observedRow = Objects.requireNonNull(observedRow, "observedRow must not be null");
        this.observedDerived = Objects.requireNonNull(observedDerived, "observedDerived must not be null");
        this.decidedBy = Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        this.approval = Objects.requireNonNull(approval, "approval must not be null");
        if ((status == ProjectionRebuildStatus.PROPOSED) != decidedBy.isEmpty() || decidedBy.isEmpty() != decidedAt.isEmpty()) {
            throw new IllegalArgumentException("a rebuild's decision is one fact with its status");
        }
        if (status.approved() != approval.isPresent()) {
            throw new IllegalArgumentException("an approved rebuild, and only one, records what it found and wrote");
        }
        if (status.approved() && decidedBy.orElseThrow().equals(proposedBy)) {
            throw new IllegalArgumentException("an approved rebuild was approved by someone other than its proposer (INV-AUD-04)");
        }
        if (status == ProjectionRebuildStatus.UNCHANGED && !approval.orElseThrow().unchanged()) {
            throw new IllegalArgumentException("an UNCHANGED rebuild found the row already what the journal says");
        }
        if (status == ProjectionRebuildStatus.APPLIED && approval.orElseThrow().unchanged()) {
            throw new IllegalArgumentException("an APPLIED rebuild moved the row");
        }
    }

    /**
     * A proposal from the proposer's reading, judged: the reading is stable, the history derivable at the row's scale, and
     * the projection drifts from it. Nothing about the figures is a decision's input ({@code INV-BAL-05}).
     *
     * @throws ProjectionNotDriftingException the reading agrees, or an entry committed mid-reading
     * @throws ProjectionNotRebuildableException the row's scale is not the derivation's, or a row stands with no entries
     * @throws RebuildReasonRefusedException a reason that is blank, longer than {@value #MAX_REASON_LENGTH} or holds an
     *     instrument shape
     */
    public static ProjectionRebuild propose(
            ProjectionRebuildId id,
            LedgerAccountId account,
            ProjectionRebuildRows.Observation observation,
            String reason,
            String proposedBy,
            Instant at) {
        Objects.requireNonNull(observation, "observation must not be null");
        String reasoned = reasoned(reason);
        if (!observation.stable()) {
            throw new ProjectionNotDriftingException(account, true);
        }
        requireRebuildable(account, observation.row(), observation.derived());
        if (agrees(observation.row(), observation.derived())) {
            throw new ProjectionNotDriftingException(account, false);
        }
        return new ProjectionRebuild(id, account, observation.derived().currency(), observation.derived().scale(),
                ProjectionRebuildStatus.PROPOSED, reasoned, proposedBy, at,
                observation.row().map(ProjectionRebuildRows.Row::figures), observation.derived().figures(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A rebuild as a store read it back. */
    public static ProjectionRebuild rehydrate(
            ProjectionRebuildId id,
            LedgerAccountId account,
            CurrencyCode currency,
            int scale,
            ProjectionRebuildStatus status,
            String reason,
            String proposedBy,
            Instant proposedAt,
            Optional<ProjectionFigures> observedRow,
            ProjectionFigures observedDerived,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<Approval> approval) {
        return new ProjectionRebuild(id, account, currency, scale, status, reason, proposedBy, proposedAt, observedRow,
                observedDerived, decidedBy, decidedAt, approval);
    }

    /**
     * Whether a row says what the journal says - the verification's definition: no row and no entries, or a row whose
     * three figures equal the derivation's at the derivation's scale.
     */
    public static boolean agrees(Optional<ProjectionRebuildRows.Row> row, ProjectionRebuildRows.Derived derived) {
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(derived, "derived must not be null");
        if (row.isEmpty()) {
            return derived.figures().appliedEntries() == 0;
        }
        return row.get().scale() == derived.scale() && row.get().currency().equals(derived.currency())
                && row.get().figures().equals(derived.figures());
    }

    /**
     * Refuses what this door cannot write: a row at a scale the derivation does not have (the scale is an identity
     * column), or a row for an account with no entries ({@code last_entry_seq >= 1}).
     *
     * @throws ProjectionNotRebuildableException as above
     */
    public static void requireRebuildable(
            LedgerAccountId account, Optional<ProjectionRebuildRows.Row> row, ProjectionRebuildRows.Derived derived) {
        if (row.isEmpty()) {
            return;
        }
        if (row.get().scale() != derived.scale() || !row.get().currency().equals(derived.currency())) {
            throw new ProjectionNotRebuildableException(account,
                    "its projection row is persisted at a scale its history does not have, and the scale is the"
                            + " migrator's to change (INV-MON-03)");
        }
        if (derived.figures().appliedEntries() == 0) {
            throw new ProjectionNotRebuildableException(account,
                    "its projection row stands for an account with no entries, which last_entry_seq cannot say; deleting"
                            + " the row is the migrator's act (V006)");
        }
    }

    /**
     * Refuses an approval this person may not give: the rebuild is decided, or they proposed it ({@code INV-AUD-04}). The
     * same approver's retry on an approved rebuild never reaches this - the caller converges first.
     *
     * @throws RebuildNotOpenException the rebuild is not {@code PROPOSED}
     * @throws RebuildSelfApprovalRefusedException the approver proposed it
     */
    public void requireApprovableBy(String approver) {
        Objects.requireNonNull(approver, "approver must not be null");
        if (status != ProjectionRebuildStatus.PROPOSED) {
            throw new RebuildNotOpenException(id, status);
        }
        if (proposedBy.equals(approver)) {
            throw new RebuildSelfApprovalRefusedException(id, account);
        }
    }

    /** True when {@code actor} made this rebuild's decision - the converging retry. */
    public boolean decidedBy(String actor) {
        return decidedBy.filter(actor::equals).isPresent();
    }

    /** This rebuild decided {@code to} by {@code by} at {@code at}; the store moves the row, this is its image. */
    public ProjectionRebuild decided(ProjectionRebuildStatus to, String by, Instant at, Optional<Approval> found) {
        if (!status.canMoveTo(to)) {
            throw new RebuildNotOpenException(id, status);
        }
        return new ProjectionRebuild(id, account, currency, scale, to, reason, proposedBy, proposedAt, observedRow,
                observedDerived, Optional.of(by), Optional.of(at), found);
    }

    /**
     * The reason, screened as every person-written reason is ({@code AccountingReasons}' screen; {@code INV-AUD-02}): it is
     * stored in the rebuild's row and its audit record and served to the approver, so it holds no card-number or
     * bank-account shape ({@link InstrumentShapes}, the platform's one screen). Blank is {@link String#isBlank()}'s -
     * Unicode whitespace, which the request's {@code @NotBlank} (ASCII trim) lets through. The bound is the raw length, so
     * V031's {@code length(btrim(reason)) BETWEEN 1 AND 1000} always holds of what passes.
     *
     * @throws RebuildReasonRefusedException a reason missing, blank, longer than {@value #MAX_REASON_LENGTH} or holding an
     *     instrument shape
     */
    private static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new RebuildReasonRefusedException("a rebuild's reason is 1 to " + MAX_REASON_LENGTH
                    + " characters, not all of them whitespace");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw new RebuildReasonRefusedException("a rebuild's reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }

    public ProjectionRebuildId id() {
        return id;
    }

    public LedgerAccountId account() {
        return account;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public int scale() {
        return scale;
    }

    public ProjectionRebuildStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public String proposedBy() {
        return proposedBy;
    }

    public Instant proposedAt() {
        return proposedAt;
    }

    /** The row the proposer observed - empty when the account had none. */
    public Optional<ProjectionFigures> observedRow() {
        return observedRow;
    }

    /** What the journal and the holds derived when the proposer observed. */
    public ProjectionFigures observedDerived() {
        return observedDerived;
    }

    public Optional<String> decidedBy() {
        return decidedBy;
    }

    public Optional<Instant> decidedAt() {
        return decidedAt;
    }

    /** What the approval found and wrote - present exactly for {@code APPLIED} and {@code UNCHANGED}. */
    public Optional<Approval> approval() {
        return approval;
    }

    @Override
    public String toString() {
        // Identifiers and the status only - never a figure (INV-AUD-02).
        return "ProjectionRebuild[" + id + ", " + account + ", " + status + "]";
    }
}
