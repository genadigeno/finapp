package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditAuditAction;
import com.finapp.credit.CreditCollections;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.CreditPolicyStatus;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditPolicyVersionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequestStatus;
import com.finapp.credit.RequestOrigin;
import com.finapp.credit.Scorecard;
import com.finapp.credit.ScorecardFamily;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.credit.ScorecardStatus;
import com.finapp.credit.ScorecardStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Credit's operator collections, hermetic (`P17-TSK-035`): the desk's request rules, its pages walked over a fake read model,
 * every serving recorded once on the read's own unit of work, a version's proposer named - the caller's own marked - and the
 * scorecard read with its points table. Over PostgreSQL through the doors: {@code CreditReadGapsApiDatabaseTest}.
 */
@DisplayName("credit's operator collections and the reads an approver lacked (P17-TSK-035)")
@SuppressWarnings("try") // The correlation and actor scopes are used for their close side effect (the established idiom).
class CreditCollectionDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor INVESTIGATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditWriter<Connection> writer = (unitOfWork, record) -> written.add(record);
    private final Fake collections = new Fake();
    private final CreditCollectionDesk desk = new CreditCollectionDesk(collections, unsupported(CreditPolicyStore.class),
            scorecards(), new RecordedReads(writer, IDS, CLOCK), new com.finapp.credit.TransactionRunner() {
                @Override
                public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                    return work.apply(null);
                }
            });

    @Test
    @DisplayName("the decision requests walk once, newest first, page by page - the last page carries no cursor - and every"
            + " page is recorded credit.DecisionRequestsRead, never per row")
    void theDecisionRequestsWalkOnce() {
        for (int i = 0; i < 5; i++) {
            collections.requests.add(request(Instant.parse("2026-11-01T00:00:00Z").plusSeconds(i * 60L)));
        }
        List<String> walked = new ArrayList<>();
        List<Integer> asked = new ArrayList<>();
        collections.asked = asked;
        String cursor = null;
        int pages = 0;
        do {
            String after = cursor;
            CreditCollectionDesk.CreditDecisionRequestPage page = as(() -> desk.decisionRequests(null, null, after, "2"));
            page.items().forEach(item -> walked.add(item.requestId()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(asked).as("one row beyond each page, to know whether more remain").containsOnly(3);
        assertThat(walked).containsExactlyElementsOf(collections.requests.stream()
                .sorted(Comparator.comparing(CreditCollections.DecisionRequestHead::submittedAt).reversed())
                .map(head -> head.id().value().toString()).toList());
        assertThat(written).hasSize(3).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(CreditAuditAction.DECISION_REQUESTS_READ);
            assertThat(record.actor()).isEqualTo(INVESTIGATOR);
            assertThat(record.targetType()).isEqualTo("credit_decision_requests");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        });
        assertThat(written).extracting(record -> record.changeSummary().orElseThrow())
                .containsExactly("status=any, product=any, continued=false, served=2",
                        "status=any, product=any, continued=true, served=2",
                        "status=any, product=any, continued=true, served=1");
    }

    @Test
    @DisplayName("a decision request serves its party, product, door, status, the amount asked as an exact decimal and the"
            + " decision with its outcome")
    void aDecisionRequestServesItsHead() {
        CreditCollections.DecisionRequestHead head = request(Instant.parse("2026-11-02T00:00:00Z"));
        collections.requests.add(head);
        CreditCollectionDesk.CreditDecisionRequestHeadView view = as(() -> desk.decisionRequests("DECIDED", "PERSONAL_LOAN",
                null, null)).items().getFirst();
        assertThat(view.partyId()).isEqualTo(head.party().toString());
        assertThat(view.product()).isEqualTo("PERSONAL_LOAN");
        assertThat(view.origin()).isEqualTo("CUSTOMER_DOOR");
        assertThat(view.requestedAmount()).isEqualTo("1500.00");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.decisionId()).isEqualTo(head.decision().orElseThrow().value().toString());
        assertThat(view.outcome()).isEqualTo("APPROVED");
        assertThat(collections.filter).isEqualTo("DECIDED|PERSONAL_LOAN");
    }

    @Test
    @DisplayName("the request rules are the collections': a limit outside 1..200 refused, an unknown status or product refused,"
            + " another collection's cursor refused - each 422 api.ValidationFailed")
    void theRequestRulesAreTheCollections() {
        for (String limit : List.of("201", "0", "-1", "ten")) {
            assertRefused(() -> desk.decisionRequests(null, null, null, limit));
        }
        assertRefused(() -> desk.decisionRequests("OPENED", null, null, null));
        assertRefused(() -> desk.policyVersions("MORTGAGE", null, null, null));
        assertRefused(() -> desk.scorecards("LIVE", null, null));
        String foreign = OperatorCollection.cursor("credit.scorecards", "1", UUID.randomUUID().toString());
        assertRefused(() -> desk.decisionRequests(null, null, foreign, null));
        String garbage = OperatorCollection.cursor(CreditCollectionDesk.DECISION_REQUESTS, "soon", "nobody");
        assertRefused(() -> desk.decisionRequests(null, null, garbage, null));
        assertThat(written).as("a refused request reads nothing and records nothing").isEmpty();
    }

    @Test
    @DisplayName("a policy version names its proposer and marks the caller's own; its decision's stamp and reason, never its"
            + " decider; the list recorded credit.PolicyRead")
    void aPolicyVersionNamesItsProposer() {
        collections.versions.add(version("PERSONAL_LOAN", INVESTIGATOR.id(), Optional.empty()));
        collections.versions.add(version("CREDIT_LINE", "someone-else", Optional.of(Instant.parse("2026-11-03T00:00:00Z"))));
        List<CreditCollectionDesk.CreditPolicyVersionHeadView> items = as(() -> desk.policyVersions(null, null, null, null))
                .items();
        assertThat(items).extracting(CreditCollectionDesk.CreditPolicyVersionHeadView::proposedBy)
                .containsExactlyInAnyOrder(INVESTIGATOR.id(), "someone-else");
        assertThat(items).filteredOn(CreditCollectionDesk.CreditPolicyVersionHeadView::proposedByCaller).singleElement()
                .satisfies(own -> assertThat(own.proposedBy()).isEqualTo(INVESTIGATOR.id()));
        assertThat(items).filteredOn(item -> item.decidedAt() != null).singleElement()
                .satisfies(decided -> assertThat(decided.decisionReason()).isEqualTo("reviewed"));
        assertThat(CreditCollectionDesk.CreditPolicyVersionHeadView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).doesNotContain("decidedBy");
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(CreditAuditAction.POLICY_READ);
            assertThat(record.targetType()).isEqualTo("credit_policy_versions");
        });
    }

    @Test
    @DisplayName("a scorecard version is read with its base points and every attribute's bands, recorded credit.ScorecardRead;"
            + " an unknown id, a UUID of another version and a malformed one are the uniform 404 credit.NotFound, recording"
            + " nothing - never a 500")
    void aScorecardIsReadWithItsTable() {
        CreditCollections.VersionHead head = version(ScorecardFamily.RETAIL_SCORECARD.name(), "proposer-1", Optional.empty());
        collections.scorecards.add(head);
        CreditCollectionDesk.ScorecardVersionView view = as(() -> desk.scorecard(head.id().toString()));
        assertThat(view.version().family()).isEqualTo("RETAIL_SCORECARD");
        assertThat(view.version().proposedBy()).isEqualTo("proposer-1");
        assertThat(view.version().proposedByCaller()).isFalse();
        assertThat(view.basePoints()).isEqualTo(600);
        assertThat(view.attributes()).singleElement().satisfies(attribute -> {
            assertThat(attribute.code()).isEqualTo("BUREAU_EXTERNAL_SCORE");
            assertThat(attribute.absentPoints()).isEqualTo(-20);
            assertThat(attribute.bands()).extracting(CreditCollectionDesk.ScorecardBandView::kind,
                    CreditCollectionDesk.ScorecardBandView::lower, CreditCollectionDesk.ScorecardBandView::upper,
                    CreditCollectionDesk.ScorecardBandView::points)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("RANGE", null, 500L, 10),
                            org.assertj.core.groups.Tuple.tuple("RANGE", 500L, null, 50));
        });
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(CreditAuditAction.SCORECARD_READ);
            assertThat(record.targetId()).isEqualTo(head.id().toString());
        });
        written.clear();
        for (String unknown : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-an-id")) {
            assertThatThrownBy(() -> as(() -> desk.scorecard(unknown))).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(CreditErrorCode.NOT_FOUND));
            assertThatThrownBy(() -> as(() -> desk.policyVersion(unknown))).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(CreditErrorCode.NOT_FOUND));
        }
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private <T> T as(Supplier<T> read) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(INVESTIGATOR)) {
            return read.get();
        }
    }

    private void assertRefused(Runnable request) {
        assertThatThrownBy(() -> as(() -> {
            request.run();
            return null;
        })).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }

    private static CreditCollections.DecisionRequestHead request(Instant submitted) {
        return new CreditCollections.DecisionRequestHead(DecisionRequestId.of(IDS.next()), UUID.randomUUID(),
                CreditProduct.PERSONAL_LOAN, RequestOrigin.CUSTOMER_DOOR, DecisionRequestStatus.DECIDED, Optional.empty(),
                Money.ofMinorUnits(1_500_00, CurrencyCode.of("EUR")), Optional.of(12), submitted, submitted.plusSeconds(86_400),
                Optional.of(CreditPolicyVersionId.of(IDS.next())), Optional.of(ScorecardModelVersionId.of(IDS.next())),
                Optional.of(CreditDecisionId.of(IDS.next())), Optional.of(DecisionOutcome.APPROVED));
    }

    private static CreditCollections.VersionHead version(String subject, String proposer, Optional<Instant> decided) {
        return new CreditCollections.VersionHead(IDS.next(), subject, 1, decided.isPresent() ? "ACTIVE" : "PROPOSED", proposer,
                Instant.parse("2026-11-01T00:00:00Z"), "the proposal", decided, decided.map(at -> "reviewed"), decided,
                Optional.empty());
    }

    private ScorecardStore scorecards() {
        Scorecard table = new Scorecard(600, List.of(new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, -20,
                List.of(new Scorecard.Range(null, 500L, 10), new Scorecard.Range(500L, null, 50)))));
        return (ScorecardStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ScorecardStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("model")) {
                        ScorecardModelVersionId id = (ScorecardModelVersionId) args[1];
                        return collections.scorecards.stream().filter(head -> head.id().equals(id.value())).findFirst()
                                .map(head -> new ScorecardStore.ModelVersion(new ScorecardStore.VersionRow(id,
                                        ScorecardFamily.RETAIL_SCORECARD, 1, ScorecardStatus.PROPOSED, head.proposedBy(),
                                        Optional.empty()), table, Optional.empty(), Optional.empty()));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static <T> T unsupported(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(CreditCollectionDeskTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    /** The read model over lists, honouring the keyset, the order and the limit as the SQL does. */
    private static final class Fake implements CreditCollections {

        final List<DecisionRequestHead> requests = new ArrayList<>();
        final List<VersionHead> versions = new ArrayList<>();
        final List<VersionHead> scorecards = new ArrayList<>();
        List<Integer> asked = new ArrayList<>();
        String filter;

        @Override
        public List<DecisionRequestHead> decisionRequests(Connection unitOfWork, Optional<DecisionRequestStatus> status,
                Optional<CreditProduct> product, Optional<Cursor> after, int limit) {
            asked.add(limit);
            filter = status.map(Enum::name).orElse("") + "|" + product.map(Enum::name).orElse("");
            Comparator<DecisionRequestHead> order = Comparator.comparing(DecisionRequestHead::submittedAt)
                    .thenComparing(head -> head.id().value());
            return requests.stream().sorted(order.reversed())
                    .filter(head -> after.isEmpty() || order.compare(head, cursorHead(after.get())) < 0)
                    .limit(limit).toList();
        }

        private DecisionRequestHead cursorHead(Cursor cursor) {
            return requests.stream().filter(head -> head.id().value().equals(cursor.id())).findFirst().orElseThrow();
        }

        @Override
        public List<VersionHead> policyVersions(Connection unitOfWork, Optional<CreditProduct> product,
                Optional<CreditPolicyStatus> status, Optional<Cursor> after, int limit) {
            return versions.stream().limit(limit).toList();
        }

        @Override
        public Optional<VersionHead> policyVersion(Connection unitOfWork, CreditPolicyVersionId id) {
            return versions.stream().filter(head -> head.id().equals(id.value())).findFirst();
        }

        @Override
        public List<VersionHead> scorecardVersions(Connection unitOfWork, Optional<ScorecardStatus> status,
                Optional<Cursor> after, int limit) {
            return scorecards.stream().limit(limit).toList();
        }

        @Override
        public Optional<VersionHead> scorecardVersion(Connection unitOfWork, ScorecardModelVersionId id) {
            return scorecards.stream().filter(head -> head.id().equals(id.value())).findFirst();
        }

        @Override
        public List<VersionHead> pendingPolicyVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }

        @Override
        public List<VersionHead> pendingScorecardVersions(Connection unitOfWork, Optional<Cursor> after, int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }

        @Override
        public List<PendingSecondApproval> pendingSecondApprovals(Connection unitOfWork, Optional<Cursor> after, int limit) {
            throw new UnsupportedOperationException("the pending queues are not under test here");
        }
    }
}
