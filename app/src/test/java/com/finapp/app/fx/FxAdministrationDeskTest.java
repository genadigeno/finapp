package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.fx.AvailabilityStore;
import com.finapp.fx.AvailabilitySubject;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.FxErrorCode;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyId;
import com.finapp.fx.PricingPolicyProposal;
import com.finapp.fx.PricingPolicyStatus;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FX controller's reads without a database (`P17-TSK-118`; finapp-admin-panel B9.5): the pricing-policy list paged
 * by a §7 cursor - newest first, every version once, the limit refused rather than reduced, the cursor its own - each
 * version naming its proposer and never its decider, and an enable request read by its identifier. The SQL keyset, the
 * permissions over HTTP and the real rows are {@code FxAdministrationEndpointDatabaseTest}'s.
 */
@DisplayName("the FX controller's reads (P17-TSK-118)")
class FxAdministrationDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Instant PROPOSED_AT = Instant.parse("2026-11-19T10:15:30.123456Z");

    @Test
    @DisplayName("the versions walk once, newest first, page by page; the last page carries no cursor; each names its"
            + " proposer and never its decider")
    void theVersionsWalkOnceNewestFirst() {
        Versions store = new Versions(7);
        FxAdministrationDesk desk = desk(store, new Requests(Map.of()));

        List<Integer> walked = new ArrayList<>();
        FxAdministrationDesk.PolicyPage page = desk.policies(null, "3");
        int pages = 1;
        while (true) {
            page.items().forEach(item -> walked.add(item.version()));
            assertThat(page.items()).allSatisfy(item -> {
                assertThat(item.proposedBy()).isEqualTo("proposer-of-v" + item.version());
                assertThat(item.toString()).doesNotContain("decider-of-v");
            });
            if (page.nextCursor() == null) {
                break;
            }
            page = desk.policies(page.nextCursor(), "3");
            pages++;
        }
        assertThat(walked).containsExactly(7, 6, 5, 4, 3, 2, 1);
        assertThat(pages).isEqualTo(3);
        assertThat(store.limits).as("each read asks for one more than it serves - the next page's witness")
                .containsExactly(4, 4, 4);

        FxAdministrationDesk.PolicyPage exact = desk.policies(null, "7");
        assertThat(exact.items()).hasSize(7);
        assertThat(exact.nextCursor()).as("a page that ends the walk exactly carries no cursor").isNull();
        assertThat(desk.policies(null, null).items()).as("the default limit, 50, holds all seven").hasSize(7);
    }

    @Test
    @DisplayName("the limit is 1..100, refused rather than reduced; a cursor is this collection's own, or refused - 422")
    void theRequestRulesAreTheCollections() {
        FxAdministrationDesk desk = desk(new Versions(3), new Requests(Map.of()));
        assertThat(desk.policies(null, "100").items()).hasSize(3);
        for (String limit : List.of("101", "0", "-1", "ten", "1.5")) {
            assertValidationFailed(() -> desk.policies(null, limit));
        }

        assertThat(FxAdministrationDesk.versionCursor(OperatorCollection.cursor(FxAdministrationDesk.PRICING_POLICIES, "7")))
                .contains(7);
        assertThat(FxAdministrationDesk.versionCursor(null)).isEmpty();
        assertValidationFailed(() -> FxAdministrationDesk.versionCursor(
                OperatorCollection.cursor("accounting.mapping-versions", "7")));
        assertValidationFailed(() -> FxAdministrationDesk.versionCursor(
                OperatorCollection.cursor(FxAdministrationDesk.PRICING_POLICIES, "0")));
        assertValidationFailed(() -> FxAdministrationDesk.versionCursor(
                OperatorCollection.cursor(FxAdministrationDesk.PRICING_POLICIES, "seven")));
        assertValidationFailed(() -> FxAdministrationDesk.versionCursor("not-a-cursor"));
    }

    @Test
    @DisplayName("an enable request is read by its identifier: its subject, proposer, stamp and reason, and the decision"
            + " once decided; an unknown or malformed identifier is 404 fx.NotFound")
    void anEnableRequestIsReadByItsIdentifier() {
        UUID pending = IDS.next();
        UUID decided = IDS.next();
        Instant decidedAt = PROPOSED_AT.plusSeconds(60);
        FxAdministrationDesk desk = desk(new Versions(0), new Requests(Map.of(
                pending, new AvailabilityStore.RequestView(pending, AvailabilitySubject.pair("GBP-JPY"),
                        AvailabilityStore.EnableRequestStatus.PROPOSED, "proposer-1", PROPOSED_AT, "the provider recovered",
                        Optional.empty(), Optional.empty()),
                decided, new AvailabilityStore.RequestView(decided, AvailabilitySubject.provider("fx-sim-a", Set.of("fx-sim-a")),
                        AvailabilityStore.EnableRequestStatus.REJECTED, "proposer-2", PROPOSED_AT, "back up",
                        Optional.of(decidedAt), Optional.of("not yet")))));

        assertThat(desk.enableRequest(pending.toString())).isEqualTo(new FxAdministrationDesk.EnableRequestView(
                pending.toString(), "PAIR", "GBP-JPY", "PROPOSED", "proposer-1", PROPOSED_AT.toString(),
                "the provider recovered", null, null));
        assertThat(desk.enableRequest(decided.toString())).isEqualTo(new FxAdministrationDesk.EnableRequestView(
                decided.toString(), "PROVIDER", "fx-sim-a", "REJECTED", "proposer-2", PROPOSED_AT.toString(), "back up",
                decidedAt.toString(), "not yet"));

        for (String unknown : List.of(IDS.next().toString(), "not-an-id")) {
            assertThatThrownBy(() -> desk.enableRequest(unknown))
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(FxErrorCode.NOT_FOUND));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static FxAdministrationDesk desk(PricingPolicyStore versions, AvailabilityStore requests) {
        Set<String> declared = Set.of("fx-sim-a");
        return new FxAdministrationDesk(
                new PricingPolicyAdministration(versions, (uow, record) -> {}, (uow, envelope, payload, type) -> {}, IDS,
                        declared),
                new FxAvailability(requests, (uow, record) -> {}, (uow, envelope, payload, type) -> {}, IDS),
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), Clock.systemUTC(), Duration.ofDays(1),
                        Duration.ofMinutes(1)),
                new com.finapp.fx.TransactionRunner() {
                    @Override
                    public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                        return work.apply(null);
                    }
                },
                Clock.systemUTC(),
                declared);
    }

    private static void assertValidationFailed(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }

    /** Versions 1..n, as the store's keyset serves them: newest first, below the cursor's version, at most the limit. */
    private static final class Versions implements PricingPolicyStore {

        private final List<VersionView> all = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();

        Versions(int count) {
            for (int version = 1; version <= count; version++) {
                all.add(new VersionView(new VersionRow(PricingPolicyId.of(IDS.next()), version,
                        version == count ? PricingPolicyStatus.PROPOSED : PricingPolicyStatus.RETIRED, 5,
                        "proposer-of-v" + version, Optional.of("decider-of-v" + version)), PROPOSED_AT, List.of()));
            }
        }

        @Override
        public List<VersionView> versions(Connection unitOfWork, Optional<Integer> beforeVersion, int limit) {
            assertThat(limit).isBetween(1, PAGE_READ_BOUND);
            limits.add(limit);
            return all.stream()
                    .filter(view -> beforeVersion.map(before -> view.row().version() < before).orElse(true))
                    .sorted(Comparator.comparingInt((VersionView view) -> view.row().version()).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public int maxVersion(Connection unitOfWork) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertProposal(Connection unitOfWork, PricingPolicyId id, int version, PricingPolicyProposal proposal,
                String proposedBy, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VersionRow> lock(Connection unitOfWork, PricingPolicyId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VersionRow> lockActive(Connection unitOfWork) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean decide(Connection unitOfWork, PricingPolicyId id, PricingPolicyStatus from, PricingPolicyStatus to,
                String decidedBy, String reason, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retire(Connection unitOfWork, PricingPolicyId id, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendEvent(Connection unitOfWork, PricingPolicyId id, Optional<PricingPolicyStatus> from,
                PricingPolicyStatus to, String actorId, String reason, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VersionView> active(Connection unitOfWork) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VersionView> version(Connection unitOfWork, PricingPolicyId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean holdActive(Connection unitOfWork, PricingPolicyId id) {
            throw new UnsupportedOperationException();
        }
    }

    /** Enable requests by identifier - the read alone. */
    private record Requests(Map<UUID, RequestView> byId) implements AvailabilityStore {

        @Override
        public Optional<RequestView> request(Connection unitOfWork, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public void lockSubject(Connection unitOfWork, AvailabilitySubject subject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAvailable(Connection unitOfWork, AvailabilitySubject subject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertFact(Connection unitOfWork, AvailabilitySubject subject, boolean available, String actorId,
                String reason, Instant at, Optional<UUID> enableRequestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertRequest(Connection unitOfWork, UUID id, AvailabilitySubject subject, String proposedBy,
                String reason, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RequestRow> lockRequest(Connection unitOfWork, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean decideRequest(Connection unitOfWork, UUID id, EnableRequestStatus to, String decidedBy,
                String reason, Instant at) {
            throw new UnsupportedOperationException();
        }
    }
}
