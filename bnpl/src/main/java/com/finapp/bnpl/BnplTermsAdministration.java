package com.finapp.bnpl;

import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.ScheduleRefusal;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * BNPL product terms change only forward, and only under four eyes (`P12-TSK-004`; ADR-0102 decision 4,
 * {@code BNPL_LIFECYCLES.md} section 4.1; {@code INV-HIST-04}, {@code INV-AUD-04}, {@code INV-LND-05} and
 * {@code INV-LND-09} by analogy): a holder of {@code BNPL_ADMINISTER} proposes a whole new version for a product - its
 * {@link BnplTerms} already judged complete and within bounds, and judged here against credit's bounds; a DIFFERENT
 * holder activates it, retiring the product's predecessor in the SAME transaction, so a product has at most one
 * {@code ACTIVE} version at every instant and the effective periods meet; anyone, the proposer included, may reject it.
 * Lending's {@code LoanTermsAdministration}, with the Phase 11 gate's repairs from the start.
 *
 * <p><strong>The lock order</strong> (A0 to A2): every act takes the product's administration lock first (advisory
 * namespace {@code 12}, {@code hashtext(product)}); a decision then locks the version row {@code FOR UPDATE} and acts only
 * from {@code PROPOSED}, and an activation locks the {@code ACTIVE} row last - so it waits for every application and offer
 * holding the predecessor {@code FOR SHARE} as its pin ({@link #pinActive}), and none of them ever pins a version retiring
 * under it. <strong>Ten proposers, one proposal</strong> ({@link ProposalPending}; the partial unique over
 * {@code PROPOSED} the backstop). <strong>Ten approvers, one activation</strong> ({@link TermsStale} for the others; the
 * partial unique over {@code ACTIVE} the backstop). An approval and a rejection racing leave exactly one. The same
 * person's retry of their own decision converges. Every decision instant is the database's, written after the locks
 * ({@code clock_timestamp()}, lending {@code V030}'s lesson in {@code bnpl V002}'s trigger).
 *
 * <p><strong>Never activated by a migration</strong>: nothing seeds a version; production starts with none, and a product
 * is offered only while one is {@code ACTIVE} ({@link ProductNotOffered}; ADR-0101 decision 14).
 */
@RequiredArgsConstructor
public final class BnplTermsAdministration {

    /** The event every activation publishes. */
    public static final String ACTIVATED_EVENT = "bnpl.BnplTermsVersionActivated";

    static final String PRODUCER = "bnpl";
    static final int EVENT_VERSION = 1;

    /** The audit record's and the event's target type. */
    public static final String TARGET_TYPE = "bnpl_terms_version";

    /**
     * Every day of a month a pay-in-N purchase can fall on - its repayment day (A6: one month after the purchase on its
     * day of month, clamped), so the amortisation judgement covers every one.
     */
    static final List<Integer> PURCHASE_DAYS = IntStream.rangeClosed(1, 31).boxed().toList();

    @NonNull private final BnplTermsStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final BnplProductBounds bounds;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(BnplTermsVersionId id, BnplProduct product, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for the first, a
     * rejection, and a converged retry), {@code replayed} when the same person's retry converged.
     */
    public record Decided(
            BnplTermsVersionId id,
            BnplProduct product,
            int version,
            BnplTermsVersionStatus status,
            Optional<BnplTermsVersionId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No terms version has this id, or none was (is) in force for the product at the instant. */
    public static final class TermsNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsNotFound() {
            super("no BNPL terms version answers this request");
        }
    }

    /** No terms version of the product is {@code ACTIVE}: the product is not offered. */
    public static final class ProductNotOffered extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProductNotOffered() {
            super("the BNPL product has no active terms version and is not offered");
        }
    }

    /** The version is no longer {@code PROPOSED} - decided by somebody else, or differently. */
    public static final class TermsStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsStale(BnplTermsVersionStatus status) {
            super("the BNPL terms version is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(BnplTermsVersionId id, BnplProduct product, int version) {
            super("a BNPL terms version is activated by someone other than its proposer; the proposer may reject it");
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
            super("a proposed BNPL terms version already awaits a decision for this product");
        }
    }

    /**
     * Some purchase within credit's bounds could not be scheduled under the terms: a pay-in-N instalment repaying no
     * principal, or a pay-in-4 instalment of nothing. The detail names a term and a period's days, never a rate or an
     * amount.
     */
    public static final class TermsNotAmortising extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsNotAmortising(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: {@code terms} already judged ({@link BnplTerms}'s construction), then judged against
     * credit's bounds ({@link BnplProductBounds}) before anything is locked or written - a pay-in-N set by
     * {@link ScheduleEngineV1#judgeAmortisation} (every purchase day, the smallest amount, the longest plan offered; the
     * Phase 11 gate's half-unit margin), a pay-in-4 set by its own check; then the product locked (A0), numbered
     * {@code max + 1}, written {@code PROPOSED} with its term set, its history row and the reasoned audit record.
     *
     * @throws ProposalPending when a version of the product already awaits a decision
     * @throws TermsNotAmortising when some purchase within the product's bounds could not be scheduled
     * @throws BnplTerms.TermsInvalid when a plan offered is one credit cannot decide
     * @throws BnplReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Proposed propose(Connection unitOfWork, BnplTerms terms, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        schedulable(terms);
        store.lockProduct(unitOfWork, terms.product());
        if (store.proposalPending(unitOfWork, terms.product())) {
            throw new ProposalPending();
        }
        int version = store.maxVersion(unitOfWork, terms.product()) + 1;
        BnplTermsVersionId id = BnplTermsVersionId.next(ids);
        store.insertProposal(unitOfWork, new BnplTermsStore.NewProposal(id, version, terms, actor, reasoned, correlation,
                ids.next()));
        audit(unitOfWork, actor, BnplAuditAction.TERMS_VERSION_PROPOSED, id, reasoned,
                "product=" + terms.product().name() + ", version=" + version, correlation);
        return new Proposed(id, terms.product(), version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version - a different person's act. Under A0, then the version's row lock (A1), then the
     * {@code ACTIVE} row's (A2), the predecessor (if any) is retired FIRST and the proposal moves
     * {@code PROPOSED -> ACTIVE} naming its approver; the effective period is the database's - the predecessor's end and
     * the successor's start one instant, written after the locks and clamped so a database clock that steps back neither
     * refuses the activation nor overlaps two periods. Both history rows, the reasoned audit record and
     * {@code bnpl.BnplTermsVersionActivated} commit with it.
     *
     * @throws TermsNotFound when no version has this id
     * @throws TermsStale when the version is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     */
    public Decided approve(
            Connection unitOfWork, BnplTermsVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplTermsStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == BnplTermsVersionStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.product(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != BnplTermsVersionStatus.PROPOSED) {
            throw new TermsStale(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row.id(), row.product(), row.version());
        }
        Optional<BnplTermsStore.VersionRow> predecessor = store.lockActive(unitOfWork, row.product());
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id())) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        Instant effectiveFrom = store.decide(unitOfWork, row.id(), BnplTermsVersionStatus.ACTIVE, actor, reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"))
                .effectiveFrom()
                .orElseThrow(() -> new IllegalStateException("an activation is stamped with its effective start"));
        predecessor.ifPresent(retired -> store.appendEvent(unitOfWork, new BnplTermsStore.HistoryRow(ids.next(),
                retired.id(), retired.product(), Optional.of(BnplTermsVersionStatus.ACTIVE),
                BnplTermsVersionStatus.RETIRED, actor,
                BnplReasons.bounded("retired by version " + row.version() + "'s activation: " + reasoned), correlation)));
        store.appendEvent(unitOfWork, new BnplTermsStore.HistoryRow(ids.next(), row.id(), row.product(),
                Optional.of(BnplTermsVersionStatus.PROPOSED), BnplTermsVersionStatus.ACTIVE, actor, reasoned, correlation));
        audit(unitOfWork, actor, BnplAuditAction.TERMS_VERSION_ACTIVATED, row.id(), reasoned,
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
                // Never a rate or a fee - they stay inside bnpl and the operator surface.
                EventPayload.of()
                        .with("product", row.product().name())
                        .with("version", Integer.toString(row.version()))
                        .with("effectiveFromEpochMilli", Long.toString(effectiveFrom.toEpochMilli()))
                        .with("predecessorId", predecessor.map(p -> p.id().value().toString()).orElse("none"))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.product(), row.version(), BnplTermsVersionStatus.ACTIVE,
                predecessor.map(BnplTermsStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included. Terminal; frees the product for a
     * new proposal - a correction is a rejection and a new proposal. The same person's retry converges.
     *
     * @throws TermsNotFound when no version has this id
     * @throws TermsStale when the version is no longer {@code PROPOSED}
     */
    public Decided reject(
            Connection unitOfWork, BnplTermsVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = BnplReasons.reasoned(reason);
        BnplTermsStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == BnplTermsVersionStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.product(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != BnplTermsVersionStatus.PROPOSED) {
            throw new TermsStale(row.status());
        }
        if (store.decide(unitOfWork, row.id(), BnplTermsVersionStatus.REJECTED, actor, reasoned).isEmpty()) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new BnplTermsStore.HistoryRow(ids.next(), row.id(), row.product(),
                Optional.of(BnplTermsVersionStatus.PROPOSED), BnplTermsVersionStatus.REJECTED, actor, reasoned,
                correlation));
        audit(unitOfWork, actor, BnplAuditAction.TERMS_VERSION_REJECTED, row.id(), reasoned,
                "product=" + row.product().name() + ", rejected=v" + row.version() + ", byProposer="
                        + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(row.id(), row.product(), row.version(), BnplTermsVersionStatus.REJECTED, Optional.empty(),
                false);
    }

    // ------------------------------------------------------------------ read

    /**
     * The product's version in force at {@code at} - answered from the effective periods alone; with no instant, the
     * version {@code ACTIVE} now, by status, so no application clock decides. An offer never relies on this read: it pins
     * its version through {@link #pinActive}.
     *
     * @throws TermsNotFound when no version was (or is) in force
     */
    public BnplTermsVersion activeAt(Connection unitOfWork, BnplProduct product, Optional<Instant> at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(at, "at");
        BnplTermsVersionId id = (at.isPresent() ? store.activeAt(unitOfWork, product, at.get())
                        : store.active(unitOfWork, product))
                .orElseThrow(TermsNotFound::new);
        return store.version(unitOfWork, id).orElseThrow(TermsNotFound::new);
    }

    /**
     * One version by id, in any state - what the second person reads before deciding a proposal.
     *
     * @throws TermsNotFound when no version has this id
     */
    public BnplTermsVersion version(Connection unitOfWork, BnplTermsVersionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        return store.version(unitOfWork, id).orElseThrow(TermsNotFound::new);
    }

    /**
     * The product's {@code ACTIVE} version, its row held {@code FOR SHARE} to the caller's commit - the read the
     * application (`P12-TSK-010`) and the offer (`P12-TSK-011`) pin. An activation waits for the pin, so the caller pins
     * the old version or the new, never one retiring under it, and never neither.
     *
     * @throws ProductNotOffered when no version of the product is active ({@code 422 bnpl.ProductNotOffered})
     */
    public BnplTermsVersion pinActive(Connection unitOfWork, BnplProduct product) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(product, "product");
        BnplTermsVersionId id = store.shareActive(unitOfWork, product).orElseThrow(ProductNotOffered::new);
        return store.version(unitOfWork, id).orElseThrow(ProductNotOffered::new);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link BnplAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept (the Phase 11
     * gate's R4).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ the judgement against credit's bounds

    /**
     * Whether every purchase within credit's bounds can be scheduled under {@code terms}, or the proposal is refused
     * whole. A pure judgement over code-held bounds - the same on every instance.
     */
    void schedulable(BnplTerms terms) {
        BnplProductBounds.Bounds within = bounds.of(terms.product());
        if (!within.minimumAmount().currency().equals(terms.currency())) {
            throw new IllegalStateException("a product's bounds are in its terms' currency");
        }
        terms.payIn4().ifPresent(payIn4 -> {
            // floor(P / 4) is the smallest instalment (A4): at the smallest purchase it must be at least one minor unit.
            if (within.minimumAmount().minorUnits() < payIn4.instalments()) {
                throw new TermsNotAmortising("a pay-in-4 purchase at the product's smallest amount would have an"
                        + " instalment of nothing");
            }
        });
        terms.payInN().ifPresent(payInN -> {
            if (!within.admittedTermMonths().containsAll(payInN.allowedTerms())) {
                throw new BnplTerms.TermsInvalid("the terms offer a plan credit cannot decide");
            }
            Optional<ScheduleOutcome.Refused> refused = ScheduleEngineV1.judgeAmortisation(
                    terms.scheduleTerms().orElseThrow(), PURCHASE_DAYS, within.minimumAmount(), payInN.longestTerm());
            if (refused.isPresent()) {
                if (refused.get().reason() == ScheduleRefusal.NOT_AMORTISING) {
                    throw new TermsNotAmortising(refused.get().detail());
                }
                throw new BnplTerms.TermsInvalid(refused.get().detail());
            }
        });
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The version read under its product's lock (A0) and then locked itself (A1): read once to learn the product
     * (frozen), the product locked, the row locked - the state the decision acts on is the one every committed decision
     * left.
     */
    private BnplTermsStore.VersionRow locked(Connection unitOfWork, BnplTermsVersionId id) {
        BnplTermsStore.VersionRow first = store.find(unitOfWork, id).orElseThrow(TermsNotFound::new);
        store.lockProduct(unitOfWork, first.product());
        return store.lock(unitOfWork, id).orElseThrow(TermsNotFound::new);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            BnplAuditAction action,
            BnplTermsVersionId id,
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
