package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The KYC case machine's named transition table (`P17-TSK-126`, ADR-0155 decision 1; {@code INV-LIFE-02}): the table is exactly
 * the nine named edges, {@link KycCaseStatus#permittedTransitions()} is derived from it, the case awaiting its customer leads back
 * to review alone, and the store refuses a pair no edge names before any statement runs.
 *
 * <p>Every pair of statuses is swept against the aggregate by {@code KycCaseLifecycleTest#everyTransitionIsEnforced}; this class
 * holds the table itself.
 */
@DisplayName("the KYC case's transition table is named, and is the machine (P17-TSK-126)")
class KycCaseTransitionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the table is exactly the nine named edges, each from one status to another")
    void theTableIsTheNineNamedEdges() {
        Map<KycCaseTransition, List<KycCaseStatus>> table = Map.of(
                KycCaseTransition.BEGIN_CHECKS, List.of(KycCaseStatus.OPEN, KycCaseStatus.CHECKS_IN_PROGRESS),
                KycCaseTransition.CHECKS_CLEAR, List.of(KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.READY_FOR_DECISION),
                KycCaseTransition.CHECKS_RAISE_REVIEW, List.of(KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.IN_REVIEW),
                KycCaseTransition.REVIEW_COMPLETE, List.of(KycCaseStatus.IN_REVIEW, KycCaseStatus.READY_FOR_DECISION),
                KycCaseTransition.INFORMATION_REQUESTED, List.of(KycCaseStatus.IN_REVIEW, KycCaseStatus.AWAITING_CUSTOMER),
                KycCaseTransition.CUSTOMER_ANSWERED, List.of(KycCaseStatus.AWAITING_CUSTOMER, KycCaseStatus.IN_REVIEW),
                KycCaseTransition.REQUEST_EXPIRED, List.of(KycCaseStatus.AWAITING_CUSTOMER, KycCaseStatus.IN_REVIEW),
                KycCaseTransition.APPROVE, List.of(KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.APPROVED),
                KycCaseTransition.REJECT, List.of(KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.REJECTED));
        assertThat(EnumSet.allOf(KycCaseTransition.class)).isEqualTo(table.keySet());
        table.forEach((transition, edge) -> {
            assertThat(transition.from()).as("%s leaves", transition).isEqualTo(edge.get(0));
            assertThat(transition.to()).as("%s reaches", transition).isEqualTo(edge.get(1));
        });
    }

    @Test
    @DisplayName("each status permits exactly the targets of the edges leaving it - derived, never written beside the table")
    void thePermittedSetIsTheTables() {
        for (KycCaseStatus status : KycCaseStatus.values()) {
            Set<KycCaseStatus> fromTable = EnumSet.allOf(KycCaseTransition.class).stream()
                    .filter(transition -> transition.from() == status)
                    .map(KycCaseTransition::to)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(KycCaseStatus.class)));
            assertThat(status.permittedTransitions()).as("%s", status).isEqualTo(fromTable);
        }
    }

    @Test
    @DisplayName("a case awaiting its customer goes back to review and nowhere else, and is not terminal")
    void awaitingTheCustomerLeadsBackToReviewAlone() {
        assertThat(KycCaseStatus.AWAITING_CUSTOMER.permittedTransitions()).containsExactly(KycCaseStatus.IN_REVIEW);
        assertThat(KycCaseStatus.AWAITING_CUSTOMER.isTerminal()).isFalse();
        assertThat(KycCaseStatus.IN_REVIEW.permittedTransitions())
                .containsExactlyInAnyOrder(KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.AWAITING_CUSTOMER);
        // Not terminal, so the one-open-case index and the review queue's index already hold such a case.
        assertThat(KycCaseStatus.sqlTerminalValueList()).isEqualTo("'APPROVED', 'REJECTED'");
    }

    @Test
    @DisplayName("each aggregate method applies its own edge: the checks' edge cannot return a waiting case, nor the return start a review")
    void eachMethodAppliesItsOwnEdge() {
        KycCase awaiting = at(KycCaseStatus.AWAITING_CUSTOMER);
        assertThatThrownBy(() -> awaiting.requireReview(CLOCK)).isInstanceOf(IllegalKycCaseTransitionException.class);
        assertThatThrownBy(() -> awaiting.readyForDecision(CLOCK)).isInstanceOf(IllegalKycCaseTransitionException.class);
        assertThatThrownBy(() -> awaiting.approve(CLOCK)).isInstanceOf(IllegalKycCaseTransitionException.class);
        assertThatThrownBy(() -> awaiting.reject(CLOCK)).isInstanceOf(IllegalKycCaseTransitionException.class);
        assertThatThrownBy(() -> awaiting.awaitCustomer(CLOCK)).isInstanceOf(IllegalKycCaseTransitionException.class);
        assertThat(awaiting.resumeReview(KycCaseTransition.CUSTOMER_ANSWERED, CLOCK).status()).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(awaiting.resumeReview(KycCaseTransition.REQUEST_EXPIRED, CLOCK).status()).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThatThrownBy(() -> awaiting.resumeReview(KycCaseTransition.CHECKS_RAISE_REVIEW, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);

        KycCase checking = at(KycCaseStatus.CHECKS_IN_PROGRESS);
        assertThatThrownBy(() -> checking.resumeReview(KycCaseTransition.CUSTOMER_ANSWERED, CLOCK))
                .isInstanceOf(IllegalKycCaseTransitionException.class);
        assertThatThrownBy(() -> checking.awaitCustomer(CLOCK)).isInstanceOf(IllegalKycCaseTransitionException.class);

        KycCase inReview = at(KycCaseStatus.IN_REVIEW);
        assertThat(inReview.awaitCustomer(CLOCK).status()).isEqualTo(KycCaseStatus.AWAITING_CUSTOMER);
        assertThat(inReview.readyForDecision(CLOCK).status()).isEqualTo(KycCaseStatus.READY_FOR_DECISION);
    }

    @Test
    @DisplayName("the store refuses every pair no edge names before any statement runs; a named pair reaches the database")
    void theStoreRefusesAPairTheTableLacks() {
        AtomicInteger statements = new AtomicInteger();
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        statements.incrementAndGet();
                        throw new java.sql.SQLException("no database in this test");
                    }
                    return null;
                });
        JdbcKycCaseStore store = new JdbcKycCaseStore();
        KycCaseId id = KycCaseId.next(IDS);
        Set<String> refused = new LinkedHashSet<>();
        for (KycCaseStatus from : KycCaseStatus.values()) {
            for (KycCaseStatus to : KycCaseStatus.values()) {
                if (from.canTransitionTo(to)) {
                    continue;
                }
                assertThatThrownBy(() -> store.moveStatus(connection, id, from, to, CLOCK.instant()))
                        .as("%s -> %s", from, to)
                        .isInstanceOf(IllegalKycCaseTransitionException.class);
                refused.add(from + "->" + to);
            }
        }
        assertThat(statements).as("no refused pair reached the database").hasValue(0);
        assertThat(refused).contains("AWAITING_CUSTOMER->READY_FOR_DECISION", "AWAITING_CUSTOMER->APPROVED",
                "CHECKS_IN_PROGRESS->AWAITING_CUSTOMER", "READY_FOR_DECISION->AWAITING_CUSTOMER");

        assertThatThrownBy(() -> store.moveStatus(connection, id, KycCaseStatus.IN_REVIEW, KycCaseStatus.AWAITING_CUSTOMER,
                CLOCK.instant()))
                .as("a named edge is judged by the database")
                .isInstanceOf(KycStorageException.class);
        assertThat(statements).hasValue(1);
    }

    private static KycCase at(KycCaseStatus status) {
        Instant opened = Instant.parse("2026-11-20T09:00:00Z");
        return KycCase.rehydrate(KycCaseId.next(IDS), IDS.next(), KycCaseKind.KYC, status, KycPolicyVersion.CURRENT, opened,
                opened.plusSeconds(60));
    }
}
