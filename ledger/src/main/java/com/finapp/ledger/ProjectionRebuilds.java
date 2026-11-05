package com.finapp.ledger;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The four-eyes rebuild of one account's balance projection (`P14-TSK-026`; ADR-0119 decision 1; ADR-0041 rule 2;
 * {@code INV-BAL-01}, {@code INV-BAL-02}, {@code INV-LED-03}, {@code INV-LED-04}, {@code INV-AUD-04}) - the door
 * {@code PROJECTION_VERIFIED}'s {@code PROJECTION_DRIFT} had none of.
 *
 * <h2>Two acts, two people, and the second recomputes</h2>
 *
 * <p>{@link #propose} records the drift the proposer observed - the verification's bracketed, unlocked reading - and
 * writes nothing to the projection. {@link #approve}, by a second person, takes the posting's own locks in the posting's
 * own order ({@link ProjectionRebuildRows}: the rebuild row, the account {@code FOR KEY SHARE}, the projection row
 * {@code FOR UPDATE}), derives in statements that start after that grant, and writes what <em>it</em> derived - never
 * what the proposal saw. Nothing that changes the sum can commit while it holds those locks: every posting to the account
 * takes the projection row before it commits, and every hold takes the account {@code FOR UPDATE} first. No advisory lock
 * is taken: the rebuild inserts no journal entry.
 *
 * <h2>It never touches a journal line</h2>
 *
 * <p>No {@code PostingEffect}, no journal store, no posting command: the only writes are the projection row's four
 * granted columns (V006) and this door's own record (V031). {@code ProjectionRebuildNeverPostsTest} holds it.
 *
 * <h2>Keyed, and converging</h2>
 *
 * <p>Every act runs under its key ({@link #PROPOSE_SCOPE}, {@link #APPROVE_SCOPE}, {@link #REJECT_SCOPE}); the fingerprint
 * binds the actor, the target and the reason, so a key reused by another person or for another account is a conflict. The
 * stored response is the rebuild's id and the outcome is re-read: a decided rebuild is immutable. Beside the key the
 * machine converges: the same approver's retry under a new key on its approved rebuild answers the recorded outcome and
 * writes nothing; a repeated rejection converges.
 */
@RequiredArgsConstructor
public final class ProjectionRebuilds {

    /** The proposal's idempotency scope (ADR-0004). */
    public static final String PROPOSE_SCOPE = "ledger.projection-rebuild";

    /** The approval's. */
    public static final String APPROVE_SCOPE = "ledger.projection-rebuild.approve";

    /** The rejection's. */
    public static final String REJECT_SCOPE = "ledger.projection-rebuild.reject";

    /** The audit records' target type. */
    static final String TARGET_TYPE = "projection_rebuild";

    /** What an act came to: the rebuild as it now stands, and whether this call found it already done. */
    public record Outcome(ProjectionRebuild rebuild, boolean replayed) {

        public Outcome {
            Objects.requireNonNull(rebuild, "rebuild must not be null");
        }
    }

    @NonNull private final IdempotentExecutor executor;
    @NonNull private final ProjectionRebuildStore<Connection> store;
    @NonNull private final ProjectionRebuildRows<Connection> rows;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Records a rebuild proposal for {@code account}, or replays the recorded one for a retried key. Writes nothing to the
     * projection.
     *
     * @throws ProjectionRebuildAccountNotFoundException no such account
     * @throws ProjectionRebuildStandingException a rebuild of the account already awaits a decision
     * @throws ProjectionNotDriftingException the projection agrees with its journal, or was posted to mid-reading
     * @throws ProjectionNotRebuildableException the history or the row's scale is not this door's to correct
     */
    public Outcome propose(Connection unitOfWork, LedgerAccountId account, String reason, String idempotencyKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Actor actor = person();
        CorrelationId correlation = correlation();
        String canonical = PROPOSE_SCOPE + "|propose|" + principal(actor) + "|" + account.value() + "|"
                + (reason == null ? "-" : reason.length() + ":" + reason);
        return keyed(unitOfWork, PROPOSE_SCOPE, idempotencyKey, canonical, new AtomicBoolean(), uow -> {
            if (store.standingFor(uow, account).isPresent()) {
                throw new ProjectionRebuildStandingException(account);
            }
            ProjectionRebuildRows.Observation observed = rows.observe(uow, account);
            ProjectionRebuild rebuild = ProjectionRebuild.propose(ProjectionRebuildId.next(ids), account, observed, reason,
                    actor.id(), clock.instant());
            store.insert(uow, rebuild);
            record(uow, actor, rebuild.proposedAt(), LedgerAuditAction.PROJECTION_REBUILD_PROPOSED, rebuild,
                    Optional.of(rebuild.reason()), "rebuild=" + rebuild.id() + ", account=" + account + ", observedRow="
                            + rebuild.observedRow().isPresent() + ", observedSeq="
                            + rebuild.observedRow().map(row -> Long.toString(row.appliedEntries())).orElse("none")
                            + ", derivedSeq=" + rebuild.observedDerived().appliedEntries(),
                    correlation);
            return rebuild.id();
        });
    }

    /**
     * Approves a standing rebuild as a second person and applies it, under the posting's locks, from what the journal and
     * the holds derive at this moment. Nothing to correct is {@code UNCHANGED}, the projection untouched.
     *
     * @throws ProjectionRebuildNotFoundException no such rebuild
     * @throws RebuildNotOpenException the rebuild is decided otherwise
     * @throws RebuildSelfApprovalRefusedException the approver proposed it ({@code INV-AUD-04}); nothing written
     * @throws ProjectionNotRebuildableException the history or the row's scale is no longer this door's to correct
     */
    public Outcome approve(Connection unitOfWork, ProjectionRebuildId id, String idempotencyKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = person();
        CorrelationId correlation = correlation();
        AtomicBoolean converged = new AtomicBoolean();
        return keyed(unitOfWork, APPROVE_SCOPE, idempotencyKey,
                APPROVE_SCOPE + "|approve|" + principal(actor) + "|" + id.value(), converged,
                uow -> applied(uow, id, actor, correlation, converged));
    }

    /**
     * Rejects - or, for its proposer, withdraws - a standing rebuild; converges on one already rejected.
     *
     * @throws ProjectionRebuildNotFoundException no such rebuild
     * @throws RebuildNotOpenException the rebuild was approved
     */
    public Outcome reject(Connection unitOfWork, ProjectionRebuildId id, String idempotencyKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = person();
        CorrelationId correlation = correlation();
        AtomicBoolean converged = new AtomicBoolean();
        return keyed(unitOfWork, REJECT_SCOPE, idempotencyKey, REJECT_SCOPE + "|reject|" + principal(actor) + "|" + id.value(),
                converged, uow -> {
                    ProjectionRebuild rebuild = store.lockById(uow, id).orElseThrow(() -> new ProjectionRebuildNotFoundException(id));
                    if (rebuild.status() == ProjectionRebuildStatus.REJECTED) {
                        // Ensure-rejected converges whoever rejected it: a second record would name an act that did not happen.
                        converged.set(true);
                        return id;
                    }
                    if (rebuild.status() != ProjectionRebuildStatus.PROPOSED) {
                        throw new RebuildNotOpenException(id, rebuild.status());
                    }
                    Instant now = clock.instant();
                    rebuild.decided(ProjectionRebuildStatus.REJECTED, actor.id(), now, Optional.empty());
                    if (!store.decide(uow, id, ProjectionRebuildStatus.REJECTED, actor.id(), now, Optional.empty())) {
                        throw new IllegalStateException("the locked rebuild " + id + " was decided by another writer: the FOR"
                                + " UPDATE protocol was bypassed");
                    }
                    record(uow, actor, now, LedgerAuditAction.PROJECTION_REBUILD_REJECTED, rebuild, Optional.empty(),
                            "rebuild=" + id + ", account=" + rebuild.account() + ", proposedBy=" + rebuild.proposedBy()
                                    + ", byProposer=" + rebuild.proposedBy().equals(actor.id()),
                            correlation);
                    return id;
                });
    }

    /** The rebuild in full, unlocked - what the approver reads before deciding. */
    public Optional<ProjectionRebuild> find(Connection unitOfWork, ProjectionRebuildId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return store.findById(unitOfWork, id);
    }

    /**
     * Whether the drift the approval found is the drift the proposal recorded: both found a row and its three figures stood
     * the same distance from the derivation, or neither found a row. A {@code false} means postings landed with another
     * defect, or another repair intervened - both recorded, the approval's figures the ones written.
     */
    public static boolean driftAsProposed(ProjectionRebuild rebuild) {
        Objects.requireNonNull(rebuild, "rebuild must not be null");
        Optional<ProjectionRebuild.Approval> approval = rebuild.approval();
        if (approval.isEmpty()) {
            return false;
        }
        Optional<ProjectionFigures> proposed = rebuild.observedRow();
        Optional<ProjectionFigures> found = approval.get().before();
        if (proposed.isEmpty() || found.isEmpty()) {
            return proposed.isEmpty() && found.isEmpty();
        }
        ProjectionFigures derivedThen = rebuild.observedDerived();
        ProjectionFigures derivedNow = approval.get().after();
        return proposed.get().postedMinor() - derivedThen.postedMinor() == found.get().postedMinor() - derivedNow.postedMinor()
                && proposed.get().holdsMinor() - derivedThen.holdsMinor() == found.get().holdsMinor() - derivedNow.holdsMinor()
                && proposed.get().appliedEntries() - derivedThen.appliedEntries()
                        == found.get().appliedEntries() - derivedNow.appliedEntries();
    }

    // ------------------------------------------------------------------ the approval

    private ProjectionRebuildId applied(
            Connection unitOfWork, ProjectionRebuildId id, Actor actor, CorrelationId correlation, AtomicBoolean converged) {
        // (R1) The rebuild row: every decision of it serialised here, before any money-adjacent row.
        ProjectionRebuild rebuild = store.lockById(unitOfWork, id).orElseThrow(() -> new ProjectionRebuildNotFoundException(id));
        // The same approver's retry converges on the recorded outcome (INV-IDEM-01 through state), writing nothing.
        if (rebuild.status().approved() && rebuild.decidedBy(actor.id())) {
            converged.set(true);
            return id;
        }
        rebuild.requireApprovableBy(actor.id());
        LedgerAccountId account = rebuild.account();
        // (R2) The account FOR KEY SHARE - before the projection row, as every posting and lockInOrder take it, so a hold
        // holding the account FOR UPDATE is waited for here, holding nothing it will want.
        if (!rows.shareAccount(unitOfWork, account)) {
            throw new IllegalStateException("rebuild " + id + " names account " + account + " and the row is gone: an"
                    + " invariant is already broken");
        }
        // (R3) The projection row FOR UPDATE, then (R4) the derivation in statements that start after the grant.
        Optional<ProjectionRebuildRows.Row> before = rows.lockRow(unitOfWork, account);
        ProjectionRebuildRows.Derived after = rows.derive(unitOfWork, account);
        Instant now = clock.instant();
        boolean inserted = false;
        if (before.isEmpty()) {
            if (after.figures().appliedEntries() == 0) {
                throw new ProjectionNotRebuildableException(account, "it has neither a projection row nor an entry");
            }
            requireTheRebuildsScale(rebuild, after);
            inserted = rows.insertRow(unitOfWork, account, after, now);
            if (!inserted) {
                // A posting created the row first (it committed, or our insert waited for it to): lock it and derive again.
                before = rows.lockRow(unitOfWork, account);
                if (before.isEmpty()) {
                    throw new IllegalStateException("an insert of account " + account + "'s projection row met a conflict and"
                            + " no row: the lock protocol was bypassed");
                }
                after = rows.derive(unitOfWork, account);
            }
        }
        ProjectionRebuild.requireRebuildable(account, before, after);
        requireTheRebuildsScale(rebuild, after);
        boolean unchanged = before.isPresent() && ProjectionRebuild.agrees(before, after);
        if (before.isPresent() && !unchanged) {
            // (R5) What THIS approval derived - the proposal's figures are evidence, never input.
            rows.writeRow(unitOfWork, account, after, now);
        }
        ProjectionRebuild.Approval found = new ProjectionRebuild.Approval(before.map(ProjectionRebuildRows.Row::figures),
                after.figures());
        ProjectionRebuildStatus to = unchanged ? ProjectionRebuildStatus.UNCHANGED : ProjectionRebuildStatus.APPLIED;
        ProjectionRebuild decided = rebuild.decided(to, actor.id(), now, Optional.of(found));
        if (!store.decide(unitOfWork, id, to, actor.id(), now, Optional.of(found))) {
            throw new IllegalStateException("the locked rebuild " + id + " was decided by another writer: the FOR UPDATE"
                    + " protocol was bypassed");
        }
        ProjectionFigures written = found.after();
        String moved = found.before().map(was -> ", postedMoved=" + (was.postedMinor() != written.postedMinor())
                + ", holdsMoved=" + (was.holdsMinor() != written.holdsMinor()) + ", seqBefore=" + was.appliedEntries())
                .orElse(", rowInserted=" + inserted);
        record(unitOfWork, actor, now, LedgerAuditAction.PROJECTION_REBUILT, rebuild, Optional.empty(),
                "rebuild=" + id + ", account=" + account + ", outcome=" + to + moved + ", seqAfter=" + written.appliedEntries()
                        + ", driftAsProposed=" + driftAsProposed(decided),
                correlation);
        return id;
    }

    private static void requireTheRebuildsScale(ProjectionRebuild rebuild, ProjectionRebuildRows.Derived derived) {
        if (derived.scale() != rebuild.scale() || !derived.currency().equals(rebuild.currency())) {
            throw new ProjectionNotRebuildableException(rebuild.account(), "its history's scale moved since the proposal"
                    + " (INV-MON-03)");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Outcome keyed(
            Connection unitOfWork,
            String scope,
            String idempotencyKey,
            String canonical,
            AtomicBoolean converged,
            Function<Connection, ProjectionRebuildId> act) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        IdempotentExecutor.ExecutionOutcome outcome = executor.execute(unitOfWork, new IdempotencyKey(scope, idempotencyKey),
                RequestFingerprint.sha256(canonical.getBytes(StandardCharsets.UTF_8)),
                uow -> CommandResult.succeeded(StoredResponse.of(
                        act.apply(uow).value().toString().getBytes(StandardCharsets.UTF_8), "text/plain")));
        ProjectionRebuildId id = ProjectionRebuildId.of(UUID.fromString(new String(outcome.body()
                .orElseThrow(() -> new IllegalStateException("a recorded rebuild act always carries the rebuild's id")),
                StandardCharsets.UTF_8)));
        ProjectionRebuild rebuild = store.findById(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a recorded rebuild act names a rebuild that exists"));
        return new Outcome(rebuild, outcome.replayed() || converged.get());
    }

    private void record(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            LedgerAuditAction action,
            ProjectionRebuild rebuild,
            Optional<String> reason,
            String summary,
            CorrelationId correlation) {
        // Identifiers, counts and booleans only - never an amount (INV-AUD-02): the figures are on the rebuild's row.
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, at, action, TARGET_TYPE,
                rebuild.id().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static Actor person() {
        Actor actor = SecurityContext.require();
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a projection rebuild is a person's act - never the platform's, a service's or"
                    + " a merchant's");
        }
        return actor;
    }

    private static String principal(Actor actor) {
        return actor.type().name() + ":" + actor.id();
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a projection rebuild runs inside a correlation scope"))
                .correlationId();
    }
}
