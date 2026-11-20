package com.finapp.app.risk;

import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.PendingDecisions.PendingDecision;
import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.RiskAuditAction;
import com.finapp.risk.RiskCollections;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Risk's proposals awaiting a second person's decision, in the one pending-decision shape (`P17-TSK-040`, ADR-C133 decision
 * 3): alert suppressions, case dispositions, case risk actions, decision overrides and regulatory reports - the investigative
 * queues - and policy versions and screening list versions - the administrative ones. Each oldest first, in the domain's own
 * pending word ({@code PROPOSED}, {@code DRAFT}, {@code RECORDED}). The decision stays at each kind's own approve and reject
 * doors, whose conditional transitions answer {@code 409} to a proposal decided after the page was served.
 *
 * <p><strong>What is recorded</strong>: an investigative queue's page is recorded {@code risk.PendingDecisionsRead} in the
 * read's own transaction, as the alert and case queues' pages are ({@code risk.AlertRead}, {@code risk.CaseRead}); the
 * administrative queues are not, as the policy and screening list collections are not.
 *
 * <p>Not applied here: the alert and case queues' self-dealing withholding ({@code INV-RSK-08}). These queues serve a
 * proposal's ids, its proposer and its reason, never the subject's party; the decision doors keep their own judgement.
 */
@RequiredArgsConstructor
public final class RiskPendingDecisionDesk {

    static final String TARGET_TYPE = "risk_pending_decisions";
    static final String SUPPRESSIONS = "pending.risk.suppressions";
    static final String DISPOSITIONS = "pending.risk.dispositions";
    static final String ACTIONS = "pending.risk.actions";
    static final String OVERRIDES = "pending.risk.overrides";
    static final String REPORTS = "pending.risk.reports";
    static final String POLICY_VERSIONS = "pending.risk.policy-versions";
    static final String SCREENING_LIST_VERSIONS = "pending.risk.screening-list-versions";

    @NonNull private final RiskCollections collections;
    @NonNull private final RiskTransactions transactions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final RiskFlowScope flow;

    /** The alert suppressions awaiting approval; each is the alert's, its subject the alert. */
    public PendingDecisionPage suppressions(String status, String cursor, String limit) {
        return queue(SUPPRESSIONS, "risk.Suppression", "PROPOSED", true, status, cursor, limit,
                collections::pendingSuppressions);
    }

    /** The case dispositions awaiting a second person; the subject the case. */
    public PendingDecisionPage dispositions(String status, String cursor, String limit) {
        return queue(DISPOSITIONS, "risk.Disposition", "PROPOSED", true, status, cursor, limit,
                collections::pendingDispositions);
    }

    /** The case risk actions awaiting approval; the subject the case. */
    public PendingDecisionPage actions(String status, String cursor, String limit) {
        return queue(ACTIONS, "risk.RiskAction", "PROPOSED", true, status, cursor, limit, collections::pendingRiskActions);
    }

    /** The decision overrides awaiting a decision; the subject the assessment. */
    public PendingDecisionPage overrides(String status, String cursor, String limit) {
        return queue(OVERRIDES, "risk.Override", "PROPOSED", true, status, cursor, limit, collections::pendingOverrides);
    }

    /** The regulatory reports in draft, awaiting approval; the subject the case; no reason. */
    public PendingDecisionPage reports(String status, String cursor, String limit) {
        return queue(REPORTS, "risk.RegulatoryReport", "DRAFT", true, status, cursor, limit, collections::pendingReports);
    }

    /** The policy versions awaiting activation; the subject the operation. Not recorded. */
    public PendingDecisionPage policyVersions(String status, String cursor, String limit) {
        return queue(POLICY_VERSIONS, "risk.PolicyVersion", "PROPOSED", false, status, cursor, limit,
                collections::pendingPolicyVersions);
    }

    /** The screening list versions recorded and awaiting activation; the subject the list; no reason. Not recorded. */
    public PendingDecisionPage screeningListVersions(String status, String cursor, String limit) {
        return queue(SCREENING_LIST_VERSIONS, "risk.ScreeningListVersion", "RECORDED", false, status, cursor, limit,
                collections::pendingScreeningListVersions);
    }

    /** One store read of a queue. */
    @FunctionalInterface
    private interface PendingRead {
        List<RiskCollections.PendingProposal> read(Connection unitOfWork, Optional<RiskCollections.Cursor> after, int limit);
    }

    private PendingDecisionPage queue(String collection, String kind, String pendingWord, boolean recorded, String status,
            String cursor, String limit, PendingRead read) {
        PendingDecisions.pendingOnly(status, pendingWord);
        int size = PendingDecisions.limit(limit);
        Optional<RiskCollections.Cursor> after = PendingDecisions.position(collection, cursor)
                .map(position -> new RiskCollections.Cursor(position.proposedAt(), position.id()));
        Actor actor = recorded ? SecurityContext.require() : null;
        CorrelationId correlation = recorded ? correlation() : null;
        return flow.door(RiskSpans.QUEUE, () -> transactions.inTransaction(uow -> {
            List<PendingDecision> items = read.read(uow, after, size + 1).stream()
                    .map(row -> PendingDecisions.of(row.id(), kind, row.proposedBy(), row.proposedAt(),
                            row.reason().orElse(null), row.subject()))
                    .toList();
            PendingDecisionPage page = PendingDecisions.page(collection, items, size);
            if (recorded) {
                // On the read's own transaction: a page nobody recorded was never served.
                audit.append(uow, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                        RiskAuditAction.PENDING_DECISIONS_READ, TARGET_TYPE, collection, Optional.empty(),
                        AuditOutcome.SUCCEEDED, correlation,
                        Optional.of("continued=" + after.isPresent() + ", served=" + page.items().size()
                                + ", more=" + (page.nextCursor() != null))));
            }
            return page;
        }));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk read runs inside a correlation scope"))
                .correlationId();
    }
}
