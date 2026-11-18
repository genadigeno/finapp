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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The analyst's assessment search (`P17-TSK-020`, gap G-91; {@code INV-RSK-08}): the sealed assessments of a window, newest
 * first - a read model over {@code risk_assessment} through {@link RiskCollections}, judged as the one-assessment read is
 * ({@link RiskAssessments}).
 *
 * <p>The reader is placed in their party first, a reader the platform cannot place refused (fail closed). Their own party's
 * assessments are withheld by the read itself - a page never carries one, and no scan counts them - and a search that names
 * their own party is refused outright: it asks for nothing but what self-dealing forbids. {@link #record} appends
 * {@code risk.AssessmentsSearched} to the read's own transaction, so nothing is served unrecorded; a refusal is recorded
 * {@code FAILED} by {@link #recordRefusal} in a transaction of the caller's own, after the read was abandoned. A read decides
 * nothing and writes nothing else.
 */
public final class AssessmentSearch {

    /** The audit target type of a search - its id the party named, or {@code all}. */
    public static final String TARGET_TYPE = "risk_assessment_search";

    private static final String ACT = "assessment-search";

    private final RiskCollections collections;
    private final RiskActingParty actingParties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public AssessmentSearch(RiskCollections collections, RiskActingParty actingParties, AuditWriter<Connection> audit,
            IdGenerator ids, Clock clock) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Up to {@code limit} heads of {@code filter}'s window for {@code actor}, newest first, strictly after {@code after} -
     * nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the search names the reader's own party, or the reader is placed in no party
     * @throws IllegalArgumentException the reader is not a person
     */
    public List<RiskCollections.AssessmentHead> search(Connection unitOfWork, RiskCollections.AssessmentFilter filter,
            Optional<RiskCollections.Cursor> after, int limit, Actor actor) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(after, "after");
        UUID own = Queues.reader(unitOfWork, actingParties, actor, ACT);
        if (filter.party().filter(own::equals).isPresent()) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, ACT);
        }
        return collections.assessments(unitOfWork, filter, own, after, limit);
    }

    /**
     * Records a page served to {@code actor} - {@code risk.AssessmentsSearched} on {@code unitOfWork}, the read's own
     * transaction. The summary the window, the filters, whether the page continued a walk and how many heads were served.
     */
    public void record(Connection unitOfWork, RiskCollections.AssessmentFilter filter, boolean continued, int served,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.ASSESSMENTS_SEARCHED, TARGET_TYPE, target(filter), Optional.empty(), AuditOutcome.SUCCEEDED,
                correlation, Optional.of(summary(filter) + ", continued=" + continued + ", served=" + served)));
    }

    /** Records a refused search {@code FAILED} ({@code risk.SelfDealingRefused}) - a transaction of the caller's own. */
    public void recordRefusal(Connection unitOfWork, RiskCollections.AssessmentFilter filter,
            RiskAssessments.SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, target(filter), Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    private static String target(RiskCollections.AssessmentFilter filter) {
        return filter.party().map(UUID::toString).orElse("all");
    }

    private static String summary(RiskCollections.AssessmentFilter filter) {
        return "from=" + filter.from() + ", to=" + filter.to()
                + filter.party().map(party -> ", party=" + party).orElse("")
                + filter.operation().map(operation -> ", operation=" + operation.name()).orElse("")
                + filter.decision().map(decision -> ", decision=" + decision.name()).orElse("");
    }
}
