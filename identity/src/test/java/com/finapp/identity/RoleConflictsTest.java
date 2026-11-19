package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The segregation-of-duties table is the owner's seven pairs and nothing else (`P17-TSK-016`; decided 2026-11-18): a pair added or
 * dropped is a failing test, so changing who may hold what together is a reviewed decision, never a quiet edit.
 */
@DisplayName("the segregation-of-duties table is the owner's seven pairs (P17-TSK-016)")
class RoleConflictsTest {

    @Test
    @DisplayName("exactly the seven pairs the owner decided, each once")
    void theTableIsTheOwnersSevenPairs() {
        assertThat(RoleConflicts.PAIRS.stream().map(pair -> pair.first() + "/" + pair.second()).toList())
                .containsExactlyInAnyOrder(
                        "ACCOUNTANT/FINANCIAL_CONTROLLER",
                        "REGULATORY_REPORTER/FINANCIAL_CONTROLLER",
                        "LOAN_SERVICING_AGENT/LENDING_OFFICER",
                        "BNPL_SERVICING_AGENT/BNPL_OFFICER",
                        "RECONCILIATION_OPERATOR/RECONCILIATION_CONTROLLER",
                        "RISK_ANALYST/RISK_OFFICER",
                        "LEDGER_OPERATOR/RECONCILIATION_OPERATOR");
    }

    @Test
    @DisplayName("a conflict reads both ways, and a role conflicts with every partner it has")
    void aConflictReadsBothWays() {
        assertThat(RoleConflicts.conflictsOf(RoleName.FINANCIAL_CONTROLLER))
                .containsExactlyInAnyOrder(RoleName.ACCOUNTANT, RoleName.REGULATORY_REPORTER);
        assertThat(RoleConflicts.conflictsOf(RoleName.RECONCILIATION_OPERATOR))
                .containsExactlyInAnyOrder(RoleName.RECONCILIATION_CONTROLLER, RoleName.LEDGER_OPERATOR);
        for (RoleConflicts.Pair pair : RoleConflicts.PAIRS) {
            assertThat(RoleConflicts.conflictsOf(pair.first())).contains(pair.second());
            assertThat(RoleConflicts.conflictsOf(pair.second())).contains(pair.first());
        }
        assertThat(RoleConflicts.conflictsOf(RoleName.ADMINISTRATOR)).isEmpty();
        assertThat(RoleConflicts.conflictsOf(RoleName.CREDIT_POLICY_OFFICER)).as("not a pair: no four-eyes act spans it").isEmpty();
    }

    @Test
    @DisplayName("the held role a grant conflicts with is found among the live roles; none, when none conflicts")
    void theHeldConflictIsFound() {
        assertThat(RoleConflicts.conflictWith(RoleName.ACCOUNTANT, Set.of(RoleName.KYC_REVIEWER, RoleName.FINANCIAL_CONTROLLER)))
                .contains(RoleName.FINANCIAL_CONTROLLER);
        assertThat(RoleConflicts.conflictWith(RoleName.ACCOUNTANT, List.of(RoleName.KYC_REVIEWER))).isEmpty();
        assertThat(RoleConflicts.conflictWith(RoleName.ACCOUNTANT, List.of(RoleName.ACCOUNTANT))).as("not with itself").isEmpty();
    }

    @Test
    @DisplayName("a role never conflicts with itself")
    void noRoleConflictsWithItself() {
        assertThatThrownBy(() -> new RoleConflicts.Pair(RoleName.ACCOUNTANT, RoleName.ACCOUNTANT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
