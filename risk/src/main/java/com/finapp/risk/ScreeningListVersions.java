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
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * T-list - a screening list version recorded by one person and activated by a second (`P13-TSK-022`;
 * {@code PHASE_13_PLAN.md} sections 7.1 T-list, 11, 12.10; {@code RISK_LIFECYCLES.md} section 4.10; {@code INV-RSK-07},
 * {@code INV-AUD-04}) - {@link RiskPolicyAdministration}'s shape for an operator fact.
 *
 * <p><strong>Recording</strong> is the fact "version V of list L exists", with a reason: under the list's lock (A0′) the
 * label is looked up - an existing one converges (the fact exists once; nothing is written or audited again) - and a new
 * one is numbered {@code max + 1} and born {@code RECORDED} beside its reasoned audit record.
 *
 * <p><strong>Activation</strong> - a different person's act, from a {@code MULTI_FACTOR} session (the door's rank) - puts
 * the version in force: under A0′, the version row (R9) and then the list's {@code ACTIVE} row, the predecessor is
 * retired FIRST (its enqueue stopped) and the version moved {@code RECORDED → ACTIVE} with its enqueue due at once, so a
 * list has at most one version in force at every instant. <strong>The rescreening is enqueued by the sweep, not here</strong>
 * (the entry, D3): an activation writes one row, whatever the population - the permits are born in bounded batches by
 * {@link RescreeningRun#enqueueNextBatch}. A second activation of one version, and a version recorded before the one in
 * force, are {@link ListVersionStale} ({@code 409 risk.PolicyStale}); the recorder's own activation is
 * {@link SelfApprovalRefused}, recorded {@code FAILED}.
 *
 * <p><strong>Persons only</strong>: both acts are an authenticated human's - {@code CUSTOMER}-typed as the platform's
 * session establishes every operator, or {@code EMPLOYEE} ({@link Cases#person}) - never the system, a service or a
 * merchant; {@code risk V017}'s {@code screening_list_version_by_persons} is the rank beneath.
 */
@RequiredArgsConstructor
public final class ScreeningListVersions {

    /** The audit records' target type. */
    public static final String TARGET_TYPE = "screening_list_version";

    @NonNull private final ScreeningListStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A recording's outcome: the version, and whether it already stood (converged - nothing written). */
    public record Recorded(ScreeningListVersion version, boolean converged) {
        public Recorded {
            Objects.requireNonNull(version, "version");
        }
    }

    /** An activation's outcome: the version in force, and the predecessor it retired. */
    public record Activated(ScreeningListVersion version, Optional<ScreeningListVersionId> retired) {
        public Activated {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(retired, "retired");
        }
    }

    /** No list version has this id. */
    public static final class ListVersionNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ListVersionNotFound() {
            super("no screening list version answers this request");
        }
    }

    /** The version is no longer awaiting its activation, or is superseded by the version in force. */
    public static final class ListVersionStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ListVersionStale(String detail) {
            super(detail);
        }
    }

    /** The recorder tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(ScreeningListVersion version) {
            super("a screening list version is activated by someone other than the person who recorded it");
            this.target = version.id().value().toString();
            this.summary = "list=" + version.list().name() + ", ordinal=" + version.ordinal() + ", act=list:activate";
        }

        /** The version the activator recorded - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ record

    /**
     * Records version {@code version} of {@code list} - or converges on it when recorded already.
     *
     * @throws RiskReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     * @throws IllegalArgumentException for a label outside {@link ScreeningListVersion#LABEL}, or an actor who is no person
     */
    public Recorded record(Connection unitOfWork, ScreeningList list, String version, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String label = ScreeningListVersion.label(version);
        String reasoned = RiskReasons.reasoned(reason);
        store.lockList(unitOfWork, list);
        Optional<ScreeningListVersion> standing = store.findByLabel(unitOfWork, list, label);
        if (standing.isPresent()) {
            return new Recorded(standing.get(), true);
        }
        int ordinal = store.maxOrdinal(unitOfWork, list) + 1;
        ScreeningListVersion born = store.insert(unitOfWork, new ScreeningListStore.NewVersion(
                        ScreeningListVersionId.next(ids), list, label, ordinal, actor, correlation.value()))
                .orElseThrow(() -> new IllegalStateException(
                        "a list version's label was recorded under the list's lock by another writer: A0' was bypassed"));
        append(unitOfWork, actor, RiskAuditAction.LIST_VERSION_RECORDED, born, reasoned,
                "list=" + list.name() + ", version=" + label + ", ordinal=" + ordinal, correlation);
        return new Recorded(born, false);
    }

    // ------------------------------------------------------------------ activate

    /**
     * Activates a recorded version - a different person's act; retires the list's predecessor in the same transaction and
     * leaves the enqueue due at once.
     *
     * @throws ListVersionNotFound when no version has this id
     * @throws ListVersionStale when the version is no longer {@code RECORDED}, or was recorded before the one in force
     * @throws SelfApprovalRefused when the activator recorded it
     */
    public Activated activate(Connection unitOfWork, ScreeningListVersionId id, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = RiskReasons.reasoned(reason);
        ScreeningListVersion first = store.find(unitOfWork, id).orElseThrow(ListVersionNotFound::new);
        store.lockList(unitOfWork, first.list());
        ScreeningListVersion row = store.lock(unitOfWork, id).orElseThrow(ListVersionNotFound::new);
        if (row.status() != ScreeningListVersionStatus.RECORDED) {
            // A second activation of one version - by anyone - changes nothing: one rescreening per version.
            throw new ListVersionStale("the screening list version is no longer awaiting its activation: it is "
                    + row.status().name());
        }
        if (row.recordedBy(actor)) {
            throw new SelfApprovalRefused(row);
        }
        Optional<ScreeningListVersion> predecessor = store.lockActive(unitOfWork, row.list());
        if (predecessor.isPresent() && predecessor.get().ordinal() > row.ordinal()) {
            throw new ListVersionStale("the screening list version is superseded: #" + predecessor.get().ordinal()
                    + " of " + row.list().name() + " is in force; record a later version");
        }
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id())) {
            throw new IllegalStateException(
                    "the locked ACTIVE list version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        ScreeningListVersion activated = store.activate(unitOfWork, id, actor, correlation.value())
                .orElseThrow(() -> new IllegalStateException(
                        "the locked list version was activated by another writer: the FOR UPDATE protocol was bypassed"));
        append(unitOfWork, actor, RiskAuditAction.LIST_VERSION_ACTIVATED, activated, reasoned,
                "list=" + row.list().name() + ", version=" + row.version() + ", ordinal=" + row.ordinal()
                        + predecessor.map(p -> ", retired=#" + p.ordinal()).orElse(", retired=none"),
                correlation);
        return new Activated(activated, predecessor.map(ScreeningListVersion::id));
    }

    /**
     * Records a refused self-approval {@code FAILED} on {@code unitOfWork} - the caller's own transaction, after the act
     * rolled back ({@code INV-RSK-07}).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(),
                refused.summary(), correlation);
    }

    // ------------------------------------------------------------------ plumbing

    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (!Cases.person(actor)) {
            throw new IllegalArgumentException("a screening list version is recorded and activated by a person - never"
                    + " the platform, a service or a merchant");
        }
    }

    private void append(Connection unitOfWork, Actor actor, RiskAuditAction action, ScreeningListVersion version,
            String reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                version.id().value().toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation,
                Optional.of(summary)));
    }
}
