package com.finapp.app.accounting;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.LineShape;
import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The posting rule register's door (`P14-TSK-002`; ADR-0117 decision 1; {@code PHASE_14_PLAN.md} sections 10, 12): the
 * register as built, every rule and version with its line shape and dating class, served to an {@code ACCOUNTING_READ}
 * holder and <strong>recorded {@code accounting.PostingRulesRead} before it is answered</strong> - in a transaction of
 * its own that holds nothing but the audit row, so a failed record fails the read and nothing is served unrecorded.
 *
 * <p>The register is code compiled into the build, identical on every instance; the answer carries the catalogue
 * version, so a reader can tell two builds apart during a rolling deploy. Nothing here reads or writes a journal, a
 * balance or a customer's figure - purposes and code names only.
 */
public final class PostingRuleDesk {

    /** The audit record's target type - the register itself, identified by its catalogue version. */
    static final String TARGET_TYPE = "posting_rule_register";

    private final PostingRuleRegister register;
    private final AccountingTransactions transactions;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public PostingRuleDesk(PostingRuleRegister register, AccountingTransactions transactions,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.register = Objects.requireNonNull(register, "register");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The register, recorded at every serving. */
    public PostingRuleRegisterView read() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an operator read runs inside a correlation scope"))
                .correlationId();
        PostingRuleRegisterView view = PostingRuleRegisterView.of(register);
        transactions.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    AccountingAuditAction.POSTING_RULES_READ, TARGET_TYPE, target(register), Optional.empty(),
                    AuditOutcome.SUCCEEDED, correlation,
                    Optional.of("catalogueVersion=" + register.catalogueVersion() + ", rules=" + register.rules().size())));
            return null;
        });
        return view;
    }

    static String target(PostingRuleRegister register) {
        return "catalogue-" + register.catalogueVersion();
    }

    // ------------------------------------------------------------------ the views

    /** The register: its catalogue version and every rule, every version, in the catalogue's order. */
    public record PostingRuleRegisterView(int catalogueVersion, List<PostingRuleView> rules) {

        static PostingRuleRegisterView of(PostingRuleRegister register) {
            return new PostingRuleRegisterView(register.catalogueVersion(),
                    register.rules().stream().map(PostingRuleView::of).toList());
        }
    }

    /**
     * One rule: its identity and version with the first posting date it judges ({@code YYYY-MM-DD}); the ledger door
     * ({@code POSTING}, {@code REVERSAL}, {@code ADJUSTMENT}) and the namespace - a key prefix, or an adjustment's
     * origin; where it comes from; its dating class and whether that class may date an entry before today.
     */
    public record PostingRuleView(
            String id,
            int version,
            String effectiveFrom,
            String entryType,
            String namespace,
            String sourceModule,
            List<String> posters,
            String economicEvent,
            String authoritativeReference,
            String datingClass,
            boolean mayBackDate,
            List<PostingRuleLineView> lines,
            String catalogue) {

        static PostingRuleView of(PostingRule rule) {
            return new PostingRuleView(rule.id(), rule.version(), rule.effectiveFrom().toString(), rule.type().name(),
                    rule.namespace(), rule.sourceModule(), rule.posters(), rule.economicEvent(),
                    rule.authoritativeReference(), rule.dating().name(), rule.mayBackDate(),
                    rule.lines().stream().map(PostingRuleLineView::of).toList(), rule.catalogue());
        }
    }

    /**
     * One line of a shape: the ledger purposes it may name (any one), its side ({@code DEBIT}, {@code CREDIT},
     * {@code EITHER}) and its presence - {@code REQUIRED} on every entry, or {@code OPTIONAL} for a non-zero figure or a
     * branch. (Not a boolean {@code required}: that name is a JSON-schema keyword in the published contract.)
     */
    public record PostingRuleLineView(List<String> purposes, String side, String presence) {

        static PostingRuleLineView of(LineShape line) {
            return new PostingRuleLineView(line.purposes(), line.side().name(), line.required() ? "REQUIRED" : "OPTIONAL");
        }
    }
}
