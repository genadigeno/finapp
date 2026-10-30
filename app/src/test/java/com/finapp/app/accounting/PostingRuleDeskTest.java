package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The posting rule register's door (`P14-TSK-002`; {@code PHASE_14_PLAN.md} sections 10, 12): the register served whole,
 * <strong>recorded {@code accounting.PostingRulesRead} before the answer</strong>, in a transaction of its own; a record
 * that cannot be written fails the read; an actor-less call reads and records nothing; and the route is the
 * accountant's alone - {@code ACCOUNTING_READ}, which neither the controller nor the reporter holds (the partition of
 * identity {@code V025}; the interceptor's refusal of a caller without it is {@code DenyByDefaultDatabaseTest}'s, the
 * declaration pinned by {@code RoutePermissionRegisterTest}). Hermetic: the unit of work is a fake.
 */
@DisplayName("the posting rule register's audited door (P14-TSK-002)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingRuleDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final List<String> units = new ArrayList<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            units.add("transaction");
            return work.apply(null);
        }
    };
    private final Actor accountant = new Actor("accountant-1", ActorType.EMPLOYEE);

    @Test
    @DisplayName("the register is served whole and recorded once - SUCCEEDED, its catalogue version the target, the"
            + " version and count the summary, no reason")
    void servesTheRegisterRecorded() {
        PostingRuleDesk desk = new PostingRuleDesk(PostingRuleRegister.CURRENT, transactions,
                (unitOfWork, record) -> audit.add(record), IDS, CLOCK);
        PostingRuleDesk.PostingRuleRegisterView view = as(accountant, desk::read);

        assertThat(view.catalogueVersion()).isEqualTo(PostingRuleRegister.CATALOGUE_VERSION);
        assertThat(view.rules()).hasSize(PostingRuleRegister.CURRENT.rules().size());
        PostingRuleDesk.PostingRuleView accrual = view.rules().stream().filter(rule -> rule.id().equals("LND-2"))
                .findFirst().orElseThrow();
        assertThat(accrual.entryType()).isEqualTo("POSTING");
        assertThat(accrual.namespace()).isEqualTo("lending.accrual:");
        assertThat(accrual.datingClass()).isEqualTo("ECONOMIC_DATE");
        assertThat(accrual.mayBackDate()).isTrue();
        assertThat(accrual.effectiveFrom()).isEqualTo("1970-01-01");
        assertThat(accrual.posters()).containsExactly("LoanServicing");
        assertThat(accrual.lines()).extracting(PostingRuleDesk.PostingRuleLineView::side)
                .containsExactly("DEBIT", "CREDIT");
        assertThat(accrual.lines().get(0).purposes()).containsExactly("LOAN_INTEREST_ACCRUED");
        assertThat(accrual.lines()).extracting(PostingRuleDesk.PostingRuleLineView::presence)
                .containsExactly("REQUIRED", "REQUIRED");
        assertThat(view.rules().stream().filter(rule -> rule.id().equals("LND-1")).findFirst().orElseThrow().lines())
                .extracting(PostingRuleDesk.PostingRuleLineView::presence)
                .as("the origination fee's line is written only for a fee").containsExactly("REQUIRED", "REQUIRED",
                        "OPTIONAL");

        assertThat(units).containsExactly("transaction");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(AccountingAuditAction.POSTING_RULES_READ);
            assertThat(record.actor()).isEqualTo(accountant);
            assertThat(record.targetType()).isEqualTo("posting_rule_register");
            assertThat(record.targetId()).isEqualTo("catalogue-1");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary()).contains("catalogueVersion=1, rules=58");
            assertThat(record.occurredAt()).isEqualTo(CLOCK.instant());
        });
    }

    @Test
    @DisplayName("a record that cannot be written fails the read - nothing is served unrecorded")
    void anUnrecordableReadFails() {
        PostingRuleDesk desk = new PostingRuleDesk(PostingRuleRegister.CURRENT, transactions,
                (unitOfWork, record) -> {
                    throw new IllegalStateException("the audit store refused");
                }, IDS, CLOCK);
        assertThatThrownBy(() -> as(accountant, desk::read)).hasMessageContaining("the audit store refused");
    }

    @Test
    @DisplayName("without an authenticated actor nothing is read or recorded")
    void anActorlessCallReadsNothing() {
        PostingRuleDesk desk = new PostingRuleDesk(PostingRuleRegister.CURRENT, transactions,
                (unitOfWork, record) -> audit.add(record), IDS, CLOCK);
        assertThatThrownBy(desk::read).isInstanceOf(RuntimeException.class);
        assertThat(units).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("the route is GET /operator/accounting/posting-rules under ACCOUNTING_READ, which only the ACCOUNTANT"
            + " holds - the controller and the reporter are refused")
    void theRouteIsTheAccountants() throws NoSuchMethodException {
        assertThat(PostingRuleController.class.getAnnotation(RequestMapping.class).path())
                .containsExactly("/operator/accounting/posting-rules");
        var handler = PostingRuleController.class.getMethod("readPostingRuleRegister");
        assertThat(handler.isAnnotationPresent(GetMapping.class)).isTrue();
        assertThat(handler.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(
                PermissionName.ACCOUNTING_READ))).containsExactly(RoleName.ACCOUNTANT);
    }

    // ------------------------------------------------------------------

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + java.util.UUID.randomUUID())))) {
            return work.get();
        }
    }
}
