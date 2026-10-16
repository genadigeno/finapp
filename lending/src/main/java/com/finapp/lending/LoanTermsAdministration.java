package com.finapp.lending;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Loan product terms change only forward, and only under four eyes (`P11-TSK-005`; ADR-0092 sections 1-3,
 * {@code LENDING_LIFECYCLES.md} section 4.1; {@code INV-LND-05}, {@code INV-LND-09}, {@code INV-HIST-04},
 * {@code INV-AUD-04}): a holder of {@code LENDING_ADMINISTER} proposes a whole new version for a product - its
 * {@link LoanTerms} already judged complete and within bounds; a DIFFERENT holder activates it, retiring the product's
 * predecessor in the SAME transaction, so a product has at most one {@code ACTIVE} version at every instant and the
 * effective periods meet; anyone, the proposer included, may reject it. Credit's policy protocol (ADR-0086), in
 * lending's lock order.
 *
 * <p><strong>The lock order</strong> (A0 to A2): every act takes the product's administration lock first (advisory
 * namespace {@code 11}, {@code hashtext(product)}); a decision then locks the version row {@code FOR UPDATE} and acts
 * only from {@code PROPOSED}, and an activation locks the {@code ACTIVE} row last - so it waits for every application
 * and offer holding the predecessor {@code FOR SHARE} as its pin ({@link #pinActive}), and none of them ever pins a
 * version retiring under it. <strong>Ten proposers, one proposal</strong> ({@link ProposalPending}; the partial unique
 * over {@code PROPOSED} the backstop). <strong>Ten approvers, one activation</strong> ({@link TermsStale} for the
 * others; the partial unique over {@code ACTIVE} the backstop). The same person's retry of their own decision
 * converges.
 *
 * <p><strong>Never activated by a migration</strong>: nothing seeds a version; production starts with none, and a
 * product is offered only while one is {@code ACTIVE} (ADR-0090 section 9, L11).
 */
@RequiredArgsConstructor
public final class LoanTermsAdministration {

    /** The event every activation publishes. */
    public static final String ACTIVATED_EVENT = "lending.LoanTermsVersionActivated";

    static final String PRODUCER = "lending";
    static final int EVENT_VERSION = 1;

    /** The audit record's and the event's target type. */
    static final String TARGET_TYPE = "loan_terms_version";

    @NonNull private final LoanTermsStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final LoanProductBounds bounds;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(LoanTermsVersionId id, LoanProduct product, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for the first, a
     * rejection, and a converged retry), {@code replayed} when the same person's retry converged.
     */
    public record Decided(
            LoanTermsVersionId id,
            LoanProduct product,
            int version,
            LoanTermsVersionStatus status,
            Optional<LoanTermsVersionId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No terms version has this id, or none was (is) in force for the product at the instant. */
    public static final class TermsNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsNotFound() {
            super("no loan terms version answers this request");
        }
    }

    /** The version is no longer {@code PROPOSED} - decided by somebody else, or differently. */
    public static final class TermsStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsStale(LoanTermsVersionStatus status) {
            super("the loan terms version is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(LoanTermsVersionId id, LoanProduct product, int version) {
            super("a loan terms version is activated by someone other than its proposer; the proposer may reject it");
            this.target = id.value().toString();
            this.summary = "product=" + product.name() + ", version=v" + version + ", act=terms:activate";
        }

        /** The proposal the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the proposal and the act - never an amount. */
        public String summary() {
            return summary;
        }
    }

    /** A version of the product already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a proposed loan terms version already awaits a decision for this product");
        }
    }

    /**
     * The terms cannot amortise across the product's amount and term bounds (`P11-TSK-006`; ADR-0093 section 5,
     * {@code INV-LND-03}): some loan within them would repay no principal in an instalment. The detail names a term and
     * a period's days, never a rate or an amount.
     */
    public static final class TermsNotAmortising extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsNotAmortising(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: {@code terms} already judged ({@link LoanTerms}'s construction), the product locked
     * (A0), numbered {@code max + 1}, written {@code PROPOSED} with its term set, its history row and the reasoned audit
     * record.
     *
     * <p>An instalment product's terms are first judged by the schedule engine they pin against the product's amount and
     * term bounds - credit's, through {@link LoanProductBounds} - so terms under which some loan within those bounds
     * could not amortise are refused before anything is locked or written (`P11-TSK-006`,
     * {@link ScheduleEngineV1#judgeAmortisation}). A pure judgement over code-held bounds: the same on every instance.
     *
     * @throws ProposalPending when a version of the product already awaits a decision
     * @throws TermsNotAmortising when some loan within the product's bounds could not amortise under the terms
     * @throws LendingCapitalAdministration.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Proposed propose(Connection unitOfWork, LoanTerms terms, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        amortises(terms);
        store.lockProduct(unitOfWork, terms.product());
        if (store.proposalPending(unitOfWork, terms.product())) {
            throw new ProposalPending();
        }
        int version = store.maxVersion(unitOfWork, terms.product()) + 1;
        LoanTermsVersionId id = LoanTermsVersionId.next(ids);
        store.insertProposal(unitOfWork, new LoanTermsStore.NewProposal(id, version, terms, actor, reasoned, correlation,
                ids.next()));
        audit(unitOfWork, actor, LendingAuditAction.TERMS_VERSION_PROPOSED, id, reasoned,
                "product=" + terms.product().name() + ", version=" + version, correlation);
        return new Proposed(id, terms.product(), version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version - a different person's act. Under A0, then the version's row lock (A1), then the
     * {@code ACTIVE} row's (A2), the predecessor (if any) is retired FIRST and the proposal moves
     * {@code PROPOSED -> ACTIVE} naming its approver; the effective period is the database's - the predecessor's end and
     * the successor's start one instant, clamped so a database clock that steps back neither refuses the activation nor
     * overlaps two periods ({@code lending V003}, credit {@code V014}'s lesson). Both history rows, the reasoned audit
     * record and {@code lending.LoanTermsVersionActivated} commit with it.
     *
     * @throws TermsNotFound when no version has this id
     * @throws TermsStale when the version is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     */
    public Decided approve(
            Connection unitOfWork, LoanTermsVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanTermsStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == LoanTermsVersionStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.product(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != LoanTermsVersionStatus.PROPOSED) {
            throw new TermsStale(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row.id(), row.product(), row.version());
        }
        Optional<LoanTermsStore.VersionRow> predecessor = store.lockActive(unitOfWork, row.product());
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id())) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        Instant effectiveFrom = store.decide(unitOfWork, row.id(), LoanTermsVersionStatus.ACTIVE, actor, reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"))
                .effectiveFrom()
                .orElseThrow(() -> new IllegalStateException("an activation is stamped with its effective start"));
        predecessor.ifPresent(retired -> store.appendEvent(unitOfWork, new LoanTermsStore.HistoryRow(ids.next(),
                retired.id(), retired.product(), Optional.of(LoanTermsVersionStatus.ACTIVE),
                LoanTermsVersionStatus.RETIRED, actor,
                bounded("retired by version " + row.version() + "'s activation: " + reasoned), correlation)));
        store.appendEvent(unitOfWork, new LoanTermsStore.HistoryRow(ids.next(), row.id(), row.product(),
                Optional.of(LoanTermsVersionStatus.PROPOSED), LoanTermsVersionStatus.ACTIVE, actor, reasoned, correlation));
        audit(unitOfWork, actor, LendingAuditAction.TERMS_VERSION_ACTIVATED, row.id(), reasoned,
                "product=" + row.product().name() + ", activated=v" + row.version()
                        + predecessor.map(p -> ", retired=v" + p.version()).orElse(", retired=none"),
                correlation);
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ACTIVATED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        row.id(),
                        TARGET_TYPE,
                        clock.instant(),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                // Never a rate or a fee - they stay inside lending and the operator surface.
                EventPayload.of()
                        .with("product", row.product().name())
                        .with("version", Integer.toString(row.version()))
                        .with("effectiveFromEpochMilli", Long.toString(effectiveFrom.toEpochMilli()))
                        .with("predecessorId", predecessor.map(p -> p.id().value().toString()).orElse("none"))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.product(), row.version(), LoanTermsVersionStatus.ACTIVE,
                predecessor.map(LoanTermsStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included. Terminal; frees the product for a
     * new proposal - a correction is a rejection and a new proposal. The same person's retry converges.
     */
    public Decided reject(
            Connection unitOfWork, LoanTermsVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = LendingCapitalAdministration.reasoned(reason);
        LoanTermsStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == LoanTermsVersionStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.product(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != LoanTermsVersionStatus.PROPOSED) {
            throw new TermsStale(row.status());
        }
        if (store.decide(unitOfWork, row.id(), LoanTermsVersionStatus.REJECTED, actor, reasoned).isEmpty()) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new LoanTermsStore.HistoryRow(ids.next(), row.id(), row.product(),
                Optional.of(LoanTermsVersionStatus.PROPOSED), LoanTermsVersionStatus.REJECTED, actor, reasoned,
                correlation));
        audit(unitOfWork, actor, LendingAuditAction.TERMS_VERSION_REJECTED, row.id(), reasoned,
                "product=" + row.product().name() + ", rejected=v" + row.version() + ", byProposer="
                        + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(row.id(), row.product(), row.version(), LoanTermsVersionStatus.REJECTED, Optional.empty(), false);
    }

    // ------------------------------------------------------------------ read

    /**
     * The product's version in force at {@code at} - answered from the effective periods alone; with no instant, the
     * version {@code ACTIVE} now, by status, so no application clock decides. An offer never relies on this read: it
     * pins its version through {@link #pinActive}.
     *
     * @throws TermsNotFound when no version was (or is) in force
     */
    public LoanTermsStore.LoanTermsVersion activeAt(Connection unitOfWork, LoanProduct product, Optional<Instant> at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(at, "at");
        LoanTermsVersionId id = (at.isPresent() ? store.activeAt(unitOfWork, product, at.get())
                        : store.active(unitOfWork, product))
                .orElseThrow(TermsNotFound::new);
        return store.version(unitOfWork, id).orElseThrow(TermsNotFound::new);
    }

    /**
     * The product's {@code ACTIVE} version, its row held {@code FOR SHARE} to the caller's commit - the read the
     * application (`P11-TSK-010`) and the offer (`P11-TSK-011`) pin. An activation waits for the pin, so the caller pins
     * the old version or the new, never one retiring under it, and never neither.
     *
     * @throws TermsNotFound when no version of the product is active - the product is not offered
     */
    public LoanTermsStore.LoanTermsVersion pinActive(Connection unitOfWork, LoanProduct product) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(product, "product");
        LoanTermsVersionId id = store.shareActive(unitOfWork, product).orElseThrow(TermsNotFound::new);
        return store.version(unitOfWork, id).orElseThrow(TermsNotFound::new);
    }

    /** An instalment product's terms amortise across its bounds, or the proposal is refused whole. */
    private void amortises(LoanTerms terms) {
        if (terms.product().kind() != LoanKind.INSTALMENT) {
            return;
        }
        LoanProductBounds.Bounds within = bounds.amortisationBounds(terms.product())
                .orElseThrow(() -> new IllegalStateException("an instalment product declares its amount and term bounds"));
        Optional<ScheduleOutcome.Refused> refused = ScheduleEngineV1.judgeAmortisation(terms, within);
        if (refused.isPresent()) {
            if (refused.get().reason() == ScheduleRefusal.NOT_AMORTISING) {
                throw new TermsNotAmortising(refused.get().detail());
            }
            throw new LoanTerms.TermsInvalid(refused.get().detail());
        }
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link LendingAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept (the Phase 11
     * gate's R4).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The version read under its product's lock (A0) and then locked itself (A1): read once to learn the product
     * (frozen), the product locked, the row locked - the state the decision acts on is the one every committed decision
     * left.
     */
    private LoanTermsStore.VersionRow locked(Connection unitOfWork, LoanTermsVersionId id) {
        LoanTermsStore.VersionRow first = store.find(unitOfWork, id).orElseThrow(TermsNotFound::new);
        store.lockProduct(unitOfWork, first.product());
        return store.lock(unitOfWork, id).orElseThrow(TermsNotFound::new);
    }

    private static String bounded(String reason) {
        return reason.length() <= LendingCapitalAdministration.REASON_BOUND
                ? reason
                : reason.substring(0, LendingCapitalAdministration.REASON_BOUND);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            LendingAuditAction action,
            LoanTermsVersionId id,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
