package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.RegulatoryObligationConfiguration;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryObligationStore;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.RegulatoryRuleType;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the regulatory obligation's doors (`P14-TSK-018`; ADR-0118 decision 1; {@code PriorPeriodAdjustmentDesk}'s shape): each act keyed per principal and run in one transaction; a refused self-activation recorded {@code FAILED} in a transaction of its own; each read recorded
 * {@code regulatory.ObligationRead} in a transaction of its own before it is answered. The configuration a person states becomes {@link RegulatoryObligationConfiguration} here, refused whole, naming what and never echoing it. A reason is never logged; the proposer's is served to the controller who decides, and to nobody else.
 */
@RequiredArgsConstructor
public final class RegulatoryObligationDesk {

    static final String SCOPE = "accounting.regulatory-obligation:";

    static final String ACT_SPAN = "accounting.regulatory.obligation";
    static final String READ_SPAN = "accounting.regulatory.obligation.read";

    @NonNull private final RegulatoryObligations obligations;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    // ------------------------------------------------------------------ what comes and leaves

    /** A source a form draws from: a definition's code and version. */
    public record RegulatoryObligationSourceItem(String definition, int version) {}

    /** One line of the form. */
    public record RegulatoryObligationLineItem(String code, String label, String source, String figure) {}

    /** One rule of the closed vocabulary, over line codes. */
    public record RegulatoryObligationRuleItem(String code, String type, List<String> left, List<String> right) {}

    /** An act's receipt. */
    public record RegulatoryObligationReceipt(String obligationId, String status, String standing, String configurationSha256) {}

    /** A version in a list. */
    public record RegulatoryObligationSummaryView(String obligationId, String standing, String jurisdictionCode, String formCode, int formVersion, String status, String configurationSha256, String proposedAt) {}

    /**
     * A version as its reader reads it.
     *
     * @param adapterHeld whether this build holds an adapter filing with its jurisdiction
     * @param proposalReason the proposer's reason: to the controller's door alone
     */
    public record RegulatoryObligationView(String obligationId, String standing, String notice, String jurisdictionCode, String formCode, int formVersion, String periodType, String adapterName, boolean adapterHeld, String status,
            String configurationSha256, List<RegulatoryObligationSourceItem> sources, List<RegulatoryObligationLineItem> lines, List<RegulatoryObligationRuleItem> rules, String proposedAt, String decidedAt, String retiredAt, String proposalReason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryObligationView[" + obligationId + ", " + status + "]";
        }
    }

    // ------------------------------------------------------------------ acts

    /** What a proposer states. */
    public record Stated(String jurisdiction, String form, int formVersion, String adapter, List<RegulatoryObligationSourceItem> sources, List<RegulatoryObligationLineItem> lines, List<RegulatoryObligationRuleItem> rules, String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "Stated[" + form + "/" + formVersion + "]";
        }
    }

    public RegulatoryObligationReceipt propose(String idempotencyKey, Stated stated) {
        RegulatoryObligationConfiguration configuration = configuration(stated);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RegulatoryObligations.Proposal proposal = new RegulatoryObligations.Proposal(stated.jurisdiction(), stated.form(), stated.formVersion(), stated.adapter(), configuration);
        String fingerprinted = "obligation-proposal|" + stated.jurisdiction() + "|" + stated.form() + "|" + stated.formVersion() + "|" + stated.adapter() + "|" + configuration.canonical() + "|" + stated.reason();
        String id = flow.door(ACT_SPAN, () -> keyed(actor, idempotencyKey, fingerprinted, uow -> obligations.propose(uow, proposal, stated.reason(), actor, correlation)));
        return receipt(obligationId(id));
    }

    public RegulatoryObligationReceipt activate(String idempotencyKey, String rawId, String configurationSha256, String reason) {
        RegulatoryObligationId id = obligationId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(ACT_SPAN, () -> keyed(actor, idempotencyKey, "obligation-activation|" + id.value() + "|" + configurationSha256 + "|" + reason, uow -> obligations.activate(uow, id, configurationSha256, reason, actor, correlation)));
        return receipt(id);
    }

    public RegulatoryObligationReceipt reject(String idempotencyKey, String rawId, String reason) {
        RegulatoryObligationId id = obligationId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(ACT_SPAN, () -> keyed(actor, idempotencyKey, "obligation-rejection|" + id.value() + "|" + reason, uow -> obligations.reject(uow, id, reason, actor, correlation)));
        return receipt(id);
    }

    // ------------------------------------------------------------------ reads

    /** A preparer's read of one version. Recorded before it is served. */
    public RegulatoryObligationView read(String rawId) {
        return served(obligationId(rawId), "preparer", false);
    }

    /** A controller's read of what they decide. Recorded before it is served. */
    public RegulatoryObligationView attestation(String rawId) {
        return served(obligationId(rawId), "approver", true);
    }

    private RegulatoryObligationView served(RegulatoryObligationId id, String door, boolean withReason) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RegulatoryObligations.View view = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> obligations.view(uow, id))));
        recordRead(id.value().toString(), "door=" + door + ", status=" + view.row().status().name() + ", form=" + view.row().form() + ", formVersion=" + view.row().formVersion(), actor, correlation);
        return view(view, withReason);
    }

    /** The latest versions. Recorded before it is served. */
    public List<RegulatoryObligationSummaryView> recent() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<RegulatoryObligationStore.Row> rows = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(obligations::recent)));
        recordRead("recent", "door=recent, rows=" + rows.size(), actor, correlation);
        return rows.stream().map(RegulatoryObligationDesk::summary).toList();
    }

    /** What this controller can decide: never their own. Recorded before it is served. */
    public List<RegulatoryObligationSummaryView> awaitingActivation() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<RegulatoryObligationStore.Row> rows = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> obligations.awaitingActivation(uow, actor))));
        recordRead("awaiting-activation", "door=awaiting-activation, rows=" + rows.size(), actor, correlation);
        return rows.stream().map(RegulatoryObligationDesk::summary).toList();
    }

    private void recordRead(String target, String summary, Actor actor, CorrelationId correlation) {
        transactions.inTransaction(uow -> {
            obligations.recordRead(uow, target, summary, actor, correlation);
            return null;
        });
    }

    // ------------------------------------------------------------------ views

    private static RegulatoryObligationView view(RegulatoryObligations.View view, boolean withReason) {
        RegulatoryObligationStore.Row row = view.row();
        RegulatoryObligationConfiguration configuration = view.configuration();
        return new RegulatoryObligationView(row.id().value().toString(), row.standing(), RegulatorySubmissionDesk.NOTICE, row.jurisdiction(), row.form(), row.formVersion(), row.periodType(), row.adapter(), view.adapterHeld(), row.status().name(),
                row.configurationSha256(), configuration.sources().stream().map(source -> new RegulatoryObligationSourceItem(source.definition(), source.version())).toList(),
                configuration.lines().stream().map(line -> new RegulatoryObligationLineItem(line.code(), line.label(), line.source(), line.figure())).toList(),
                configuration.rules().stream().map(rule -> new RegulatoryObligationRuleItem(rule.code(), rule.type().name(), rule.left(), rule.right())).toList(), row.proposedAt().toString(), row.decidedAt().map(Instant::toString).orElse(null),
                row.retiredAt().map(Instant::toString).orElse(null), withReason ? row.proposalReason() : null);
    }

    private static RegulatoryObligationSummaryView summary(RegulatoryObligationStore.Row row) {
        return new RegulatoryObligationSummaryView(row.id().value().toString(), row.standing(), row.jurisdiction(), row.form(), row.formVersion(), row.status().name(), row.configurationSha256(), row.proposedAt().toString());
    }

    private RegulatoryObligationReceipt receipt(RegulatoryObligationId id) {
        RegulatoryObligationStore.Row row = guarded(() -> transactions.inTransaction(uow -> obligations.view(uow, id))).row();
        return new RegulatoryObligationReceipt(id.value().toString(), row.status().name(), row.standing(), row.configurationSha256());
    }

    // ------------------------------------------------------------------ plumbing

    private static RegulatoryObligationConfiguration configuration(Stated stated) {
        try {
            List<RegulatoryObligationConfiguration.Rule> rules = new ArrayList<>();
            for (RegulatoryObligationRuleItem rule : stated.rules() == null ? List.<RegulatoryObligationRuleItem>of() : stated.rules()) {
                rules.add(new RegulatoryObligationConfiguration.Rule(rule.code(), ruleType(rule.type()), rule.left(), rule.right()));
            }
            return new RegulatoryObligationConfiguration(
                    stated.sources() == null ? List.of() : stated.sources().stream().map(source -> new RegulatoryObligationConfiguration.Source(source.definition(), source.version())).toList(),
                    stated.lines() == null ? List.of() : stated.lines().stream().map(line -> new RegulatoryObligationConfiguration.Line(line.code(), line.label(), line.source(), line.figure())).toList(),
                    rules);
        } catch (RegulatoryObligationConfiguration.Invalid invalid) {
            throw refused(AccountingErrorCode.REGULATORY_OBLIGATION_INVALID, invalid);
        }
    }

    private static RegulatoryRuleType ruleType(String raw) {
        for (RegulatoryRuleType type : RegulatoryRuleType.values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        throw new ApiException(AccountingErrorCode.REGULATORY_OBLIGATION_INVALID, "A regulatory obligation command was refused", "a rule's type is of the closed vocabulary: NON_NEGATIVE, EQUALS, NOT_LESS_THAN");
    }

    private String keyed(Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, RegulatoryObligations.Acted> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor, () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                CommandResult.succeeded(StoredResponse.of(act.apply(uow).id().value().toString().getBytes(StandardCharsets.UTF_8), "text/plain"))))));
        return new String(outcome.body().orElseThrow(() -> new IllegalStateException("a regulatory obligation's act stores its id")), StandardCharsets.UTF_8);
    }

    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (RegulatoryObligations.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                obligations.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (RegulatoryObligations.ObligationNotFound unknown) {
            throw new ApiException(AccountingErrorCode.NOT_FOUND, "No regulatory obligation answers the request", "no such record.");
        } catch (RegulatoryObligations.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RegulatoryObligations.ObligationInvalid invalid) {
            throw refused(AccountingErrorCode.REGULATORY_OBLIGATION_INVALID, invalid);
        } catch (RegulatoryObligations.ObligationStale stale) {
            throw refused(AccountingErrorCode.REGULATORY_OBLIGATION_STALE, stale);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A regulatory obligation command was refused", cause.getMessage());
    }

    private static RegulatoryObligationId obligationId(String raw) {
        try {
            return RegulatoryObligationId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.NOT_FOUND, "No regulatory obligation answers the request", "no such record.");
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a regulatory obligation command runs inside a correlation scope"))
                .correlationId();
    }
}
