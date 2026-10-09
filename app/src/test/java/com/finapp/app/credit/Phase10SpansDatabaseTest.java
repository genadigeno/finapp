package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.CLOCK;
import static com.finapp.app.credit.CreditWorld.IDS;
import static com.finapp.app.credit.CreditWorld.REQUESTS;
import static com.finapp.app.credit.CreditWorld.TIMING;
import static com.finapp.app.credit.CreditWorld.TRANSACTIONS;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.SpannedCreditDataSource;
import com.finapp.app.telemetry.SpannedDecider;
import com.finapp.credit.CreditAssessments;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSpans;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDataRequestStore;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.PolicyEvaluations;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.Correlation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Phase 10's spans against a real database (`P10-TSK-020`; PHASE_10_PLAN.md section 15): a request driven by the progress
 * sweep - exactly as an instance's schedule drives it - records its collection, freeze, evaluation and decision legs,
 * each with no attribute, and every one carries the correlation the REQUEST stored at submission (the submission's own
 * span ran in that scope, which the desk stored), with the step's own correlation as the cause - the one the step's
 * audit rows carry. One value finds the whole flow; the step's value finds its records.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings its own policies into force, and the
 * sweep claims every due request in its database.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest
@DisplayName("Phase 10's spans, linked by the request's correlation (P10-TSK-020)")
class Phase10SpansDatabaseTest {

    private static boolean policies;

    @BeforeEach
    void world() {
        if (!policies) {
            CreditWorld.seedsInForce();
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
            policies = true;
        }
    }

    @Test
    @DisplayName("the sweep's legs - collection, freeze, evaluation, decision - each carry the request's stored correlation,"
            + " caused by their step, with no attribute; the decision's span cause is its audit row's correlation")
    void theSweepsSpansCarryTheRequestsCorrelation() throws InterruptedException {
        String submission = "credit-flow-" + UUID.randomUUID();
        UUID request = submitted(UUID.randomUUID(), submission);
        Recording spans = new Recording();
        CreditDataCollection collection = CreditWorld.collection(CreditDataCollection.Sources.of(
                new CreditDataCollection.Configured(new SpannedCreditDataSource(CreditWorld.BUREAU, spans), TIMING),
                new CreditDataCollection.Configured(new SpannedCreditDataSource(CreditWorld.FINDATA, spans), TIMING)));
        DecisionProgress progress = new DecisionProgress(TRANSACTIONS, REQUESTS, CreditWorld.POLICIES,
                CreditWorld.SCORECARDS, new JdbcDecisionSnapshotStore(), collection, CreditWorld.freezer(collection),
                new CreditAssessments(new JdbcCreditAssessmentStore(), CreditWorld.SCORECARDS, new JdbcOutboxWriter(), IDS,
                        CLOCK),
                new PolicyEvaluations(new JdbcPolicyEvaluationStore(), CreditWorld.POLICIES, EngineVersions.STANDARD, IDS,
                        CLOCK),
                CreditWorld.STANDING, CreditWorld.GATE, new JdbcOutboxWriter(), IDS, CLOCK,
                new SpannedDecider(CreditWorld.deciding(CLOCK, collection), spans), new JdbcUnderwritingCaseStore(), spans);
        CreditDecisionProgressSchedule schedule = new CreditDecisionProgressSchedule(progress,
                new CreditFlowScope(TRANSACTIONS, REQUESTS, new JdbcCreditDataRequestStore()), IDS, Duration.ofSeconds(1),
                50, Duration.ofMillis(100));

        // Each claim re-stamps the permit 100 ms ahead; the next tick finds the request due again.
        for (int sweep = 0; sweep < 40 && !"DECIDED".equals(status(request)); sweep++) {
            schedule.sweepOnce();
            Thread.sleep(150);
        }
        assertThat(status(request)).isEqualTo("DECIDED");

        List<Recorded> ours = spans.recorded.stream()
                .filter(span -> span.correlation().map(c -> c.correlationId().value().equals(submission)).orElse(false))
                .toList();
        assertThat(ours).extracting(Recorded::name)
                .as("every later leg, in the request's correlation")
                .contains(CreditSpans.COLLECT, CreditSpans.FREEZE, CreditSpans.EVALUATE, CreditSpans.DECIDE);
        assertThat(spans.recorded.stream().filter(span -> span.name().equals(CreditSpans.DECIDE)).count())
                .as("one decision recorded, and it is ours").isEqualTo(ours.stream()
                        .filter(span -> span.name().equals(CreditSpans.DECIDE)).count());
        assertThat(ours).allSatisfy(span -> {
            assertThat(span.attributes()).as("%s carries no attribute", span.name()).isEmpty();
            assertThat(span.correlation().orElseThrow().cause()).as("%s is caused by its step", span.name())
                    .isPresent()
                    .get()
                    .satisfies(cause -> assertThat(cause.value()).isNotEqualTo(submission));
        });
        Recorded decide = ours.stream().filter(span -> span.name().equals(CreditSpans.DECIDE)).findFirst().orElseThrow();
        String decision = scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request);
        assertThat(scalar("SELECT correlation_id FROM platform.audit_record WHERE operation = 'credit.DecisionRecorded'"
                        + " AND target_id = ?", decision))
                .as("the decision span's cause finds the step's own audit row")
                .isEqualTo(decide.correlation().orElseThrow().cause().orElseThrow().value());
        assertThat(CorrelationContext.current()).as("no scope outlives its step").isEmpty();

        // The retry sweep's path: a re-ask of one of the request's data requests runs in the same stored correlation.
        com.finapp.credit.CreditDataRequestId dataRequest = com.finapp.credit.CreditDataRequestId.of(UUID.fromString(
                scalar("SELECT id::text FROM credit.data_request WHERE decision_request_id = ? LIMIT 1", request)));
        com.finapp.sharedkernel.correlation.CorrelationId reask =
                com.finapp.sharedkernel.correlation.CorrelationId.generate(IDS);
        Correlation retried = new CreditFlowScope(TRANSACTIONS, REQUESTS, new JdbcCreditDataRequestStore())
                .forDataRequest(dataRequest, reask, () -> CorrelationContext.current().orElseThrow());
        assertThat(retried.correlationId().value()).isEqualTo(submission);
        assertThat(retried.cause()).map(cause -> cause.value()).contains(reask.value());
    }

    private static String status(UUID request) {
        return scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
    }

    /** A line request born SUBMITTED through the store, storing {@code correlation} as the submission did. */
    private static UUID submitted(UUID party, String correlation) {
        return TRANSACTIONS.inTransaction(uow -> {
            var profile = new JdbcCreditProfiles(IDS).ensure(uow, party);
            DecisionRequestId id = DecisionRequestId.next(IDS);
            REQUESTS.insert(uow, id, party, profile.id(), new DecisionRequest.Application(CreditProduct.CREDIT_LINE,
                    eur(200_000), Optional.empty(), Optional.empty(), Optional.empty()), Duration.ofDays(7), correlation,
                    new Actor(party.toString(), ActorType.CUSTOMER));
            return id.value();
        });
    }

    private record Recorded(String name, Map<String, String> attributes, Optional<Correlation> correlation) {}

    /** Records each span's name, attributes and the correlation scope it ran in - what the span processor stamps. */
    private static final class Recording implements Spans {
        final List<Recorded> recorded = new CopyOnWriteArrayList<>();

        @Override
        public <T> T within(String name, Map<String, String> identifiers, Supplier<T> work) {
            recorded.add(new Recorded(name, identifiers, CorrelationContext.current()));
            return work.get();
        }
    }
}
