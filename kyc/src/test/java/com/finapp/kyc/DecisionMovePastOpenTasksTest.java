package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision's move never passes an open review task (`P13-TSK-022`'s last review, {@code INV-KYC-04}), hermetic over
 * a recording connection: a move to a terminal status locks the case row first and then moves it only where no review
 * task of the case is {@code OPEN} - a NEW statement, so a task committed while the lock was awaited is seen. A list
 * update's rescreen hit, or a late onboarding hit, that opened its task on a {@code READY_FOR_DECISION} case is resolved
 * by a person before any decision. Every other move is the one conditional statement it always was. The live behaviour
 * is {@code RescreeningDatabaseTest#aRescreenHitOnACaseReadyForDecisionHoldsTheDecision}'s (written, owed - C5).
 */
@DisplayName("the decision's move never passes an open review task (P13-TSK-022, INV-KYC-04)")
class DecisionMovePastOpenTasksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final List<String> prepared = new ArrayList<>();
    private final JdbcKycCaseStore store = new JdbcKycCaseStore();
    private boolean caseExists = true;

    @Test
    @DisplayName("a decision - READY_FOR_DECISION to APPROVED or REJECTED - locks the case, then moves it only where no"
            + " review task of the case is OPEN")
    void aDecisionNeverPassesAnOpenTask() {
        for (KycCaseStatus decided : List.of(KycCaseStatus.APPROVED, KycCaseStatus.REJECTED)) {
            prepared.clear();
            assertThat(store.moveStatus(recording(), KycCaseId.next(IDS), KycCaseStatus.READY_FOR_DECISION, decided,
                    CLOCK.instant())).isTrue();
            assertThat(prepared).as("%s", decided).hasSize(2);
            assertThat(prepared.get(0)).isEqualTo("SELECT 1 FROM kyc.kyc_case WHERE id = ? FOR UPDATE");
            assertThat(prepared.get(1)).startsWith("UPDATE kyc.kyc_case SET status = ?")
                    .contains("WHERE id = ? AND status = ?")
                    .contains("AND NOT EXISTS (SELECT 1 FROM kyc.review_task WHERE case_id = ? AND status = 'OPEN')");
        }
    }

    @Test
    @DisplayName("a case that does not exist is not decided - nothing is updated")
    void noCaseNoDecision() {
        caseExists = false;
        assertThat(store.moveStatus(recording(), KycCaseId.next(IDS), KycCaseStatus.READY_FOR_DECISION,
                KycCaseStatus.APPROVED, CLOCK.instant())).isFalse();
        assertThat(prepared).singleElement().asString().endsWith("FOR UPDATE");
    }

    @Test
    @DisplayName("every other move is the one conditional statement it always was - no lock, no task predicate")
    void otherMovesAreUnchanged() {
        assertThat(store.moveStatus(recording(), KycCaseId.next(IDS), KycCaseStatus.CHECKS_IN_PROGRESS,
                KycCaseStatus.IN_REVIEW, CLOCK.instant())).isTrue();
        assertThat(prepared).singleElement().asString().startsWith("UPDATE kyc.kyc_case")
                .doesNotContain("FOR UPDATE").doesNotContain("review_task");
    }

    /** A connection recording every statement; a query answers one row while the case exists, an update 1. */
    private Connection recording() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        prepared.add((String) args[0]);
                        yield statement();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement() {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> result();
                    case "executeUpdate" -> 1;
                    case "close" -> null;
                    default -> {
                        if (method.getName().startsWith("set")) {
                            yield null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private ResultSet result() {
        boolean[] served = {false};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean row = caseExists && !served[0];
                        served[0] = true;
                        yield row;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
