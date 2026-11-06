package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerErrorCode;
import com.finapp.ledger.ProjectionFigures;
import com.finapp.ledger.ProjectionRebuild;
import com.finapp.ledger.ProjectionRebuildAccountNotFoundException;
import com.finapp.ledger.ProjectionRebuildId;
import com.finapp.ledger.ProjectionRebuildRows;
import com.finapp.ledger.ProjectionRebuildStatus;
import com.finapp.ledger.ProjectionRebuildStore;
import com.finapp.ledger.ProjectionRebuilds;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code /v1/ledger/projection-rebuilds} boundary (`P14-TSK-026`, ADR-0119 decision 1) without a database: the ledger's
 * refusals on the error contract (one {@code 404} for unknown and malformed, the five {@code 409}s, the reason screen's
 * {@code 422}), a refused self-approval recorded {@code FAILED} in a transaction of its own after the act's rolled back (the
 * template's trail read), the figures as exact decimal strings with {@code available = posted - holds}, and the meter - one
 * count per concluded act, a replay counting nothing, a refusal counted {@code refused}. The protocol itself is
 * {@code ProjectionRebuildsTest}'s; the database's {@code ProjectionRebuildDatabaseTest}'s (owed).
 */
@DisplayName("the projection rebuild doors' boundary (P14-TSK-026)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ProjectionRebuildDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Actor PROPOSER = new Actor("ops-1", ActorType.CUSTOMER);
    private static final Actor APPROVER = new Actor("ops-2", ActorType.CUSTOMER);

    private final LedgerAccountId account = LedgerAccountId.of(IDS.next());
    private final Map<ProjectionRebuildId, ProjectionRebuild> rebuilds = new HashMap<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final java.util.List<String> trail = new java.util.ArrayList<>();
    private final java.util.List<AuditRecord> audits = new java.util.ArrayList<>();
    private ProjectionFigures row = new ProjectionFigures(12_345, 2_000, 3);
    private ProjectionFigures journal = new ProjectionFigures(12_500, 2_000, 4);

    @Test
    @DisplayName("a proposal and its approval answer the figures as exact decimals - available is posted less holds - and"
            + " each concluded act is counted once, a replay counting nothing")
    void theFiguresAndTheMeter() {
        LedgerProjectionRebuilds.RebuildView proposed = as(PROPOSER, () -> desk().propose(account.value().toString(),
                "row lost an entry", "p1"));
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.observedRow()).isEqualTo(new LedgerProjectionRebuilds.FiguresView("123.45", "20.00", "103.45", 3));
        assertThat(proposed.observedDerived()).isEqualTo(new LedgerProjectionRebuilds.FiguresView("125.00", "20.00", "105.00", 4));
        assertThat(proposed.before()).isNull();
        assertThat(proposed.driftAsProposed()).isNull();
        assertThat(as(PROPOSER, () -> desk().propose(account.value().toString(), "row lost an entry", "p1")).replayed()).isTrue();

        LedgerProjectionRebuilds.RebuildView applied = as(APPROVER, () -> desk().approve(proposed.rebuildId(), "a1"));
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.after()).isEqualTo(new LedgerProjectionRebuilds.FiguresView("125.00", "20.00", "105.00", 4));
        assertThat(applied.before()).isEqualTo(new LedgerProjectionRebuilds.FiguresView("123.45", "20.00", "103.45", 3));
        assertThat(applied.driftAsProposed()).isTrue();
        assertThat(applied.decidedBy()).isEqualTo("ops-2");
        assertThat(as(APPROVER, () -> desk().approve(proposed.rebuildId(), "a2")).replayed()).isTrue();
        assertThat(as(PROPOSER, () -> desk().view(proposed.rebuildId())).status()).isEqualTo("APPLIED");

        assertThat(count("proposed")).isEqualTo(1);
        assertThat(count("applied")).isEqualTo(1);
        assertThat(count("unchanged")).isZero();
        assertThat(count("refused")).isZero();
    }

    @Test
    @DisplayName("the refusals on the error contract, each counted refused: not drifting, standing, not rebuildable, self"
            + " approval, not open; unknown and malformed one 404")
    void theRefusals() {
        row = journal;
        refused(() -> desk().propose(account.value().toString(), "why", "r1"), LedgerErrorCode.PROJECTION_NOT_DRIFTING, 409);
        row = new ProjectionFigures(1, 0, 4);
        String id = as(PROPOSER, () -> desk().propose(account.value().toString(), "why", "r2")).rebuildId();
        refused(() -> desk().propose(account.value().toString(), "why", "r3"), LedgerErrorCode.PROJECTION_REBUILD_STANDING, 409);
        refusedAs(PROPOSER, () -> desk().approve(id, "r4"), LedgerErrorCode.REBUILD_SELF_APPROVAL_REFUSED, 409);
        as(PROPOSER, () -> desk().reject(id, "r5"));
        refusedAs(APPROVER, () -> desk().approve(id, "r6"), LedgerErrorCode.REBUILD_NOT_OPEN, 409);
        row = new ProjectionFigures(1, 0, 4);
        journal = new ProjectionFigures(0, 0, 0);
        refused(() -> desk().propose(account.value().toString(), "why", "r7"), LedgerErrorCode.PROJECTION_NOT_REBUILDABLE, 409);
        for (Supplier<?> unknown : java.util.List.<Supplier<?>>of(() -> desk().view("not-a-uuid"), () -> desk().view(IDS.next().toString()),
                () -> desk().approve("nope", "r8"), () -> desk().propose("nope", "why", "r9"),
                () -> desk().propose(LedgerAccountId.of(IDS.next()).value().toString(), "why", "r10"))) {
            assertThatThrownBy(() -> as(APPROVER, unknown)).isInstanceOfSatisfying(ApiException.class, refused -> {
                assertThat(refused.errorCode().code()).isEqualTo("api.NotFound");
                assertThat(refused.clientDetail()).contains(LedgerProjectionRebuilds.NOT_FOUND_DETAIL);
            });
        }
        assertThat(count("refused")).as("five refusals; a 404 names nothing and counts nothing").isEqualTo(5);
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    @DisplayName("a refused self-approval is recorded FAILED in a transaction of its own after the act rolled back - the"
            + " refused proposer the actor, the rebuild the target - then answered 409 and counted refused (the fix round's"
            + " I-1, accounting's selfApprovalRecorded shape)")
    void aRefusedSelfApprovalIsRecordedInItsOwnTransaction() {
        String id = as(PROPOSER, () -> desk().propose(account.value().toString(), "row lost an entry", "s1")).rebuildId();
        trail.clear();
        audits.clear();
        refusedAs(PROPOSER, () -> desk().approve(id, "s2"), LedgerErrorCode.REBUILD_SELF_APPROVAL_REFUSED, 409);
        assertThat(trail).as("the act's transaction rolled back; the record's own began after it and committed")
                .containsExactly("begin", "rollback", "begin", "audit ledger.SelfApprovalRefused FAILED", "commit");
        assertThat(audits).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.actor()).isEqualTo(PROPOSER);
            assertThat(record.targetType()).isEqualTo("projection_rebuild");
            assertThat(record.targetId()).isEqualTo(id);
            assertThat(record.changeSummary().orElseThrow()).contains("act=projection-rebuild:approve")
                    .contains(account.value().toString());
        });
        assertThat(rebuilds.values()).singleElement().extracting(ProjectionRebuild::status)
                .isEqualTo(ProjectionRebuildStatus.PROPOSED);
        assertThat(count("refused")).isEqualTo(1);

        trail.clear();
        audits.clear();
        refusedAs(APPROVER, () -> desk().approve("019a0000-0000-7000-8000-000000000000", "s3"), PlatformErrorCode.NOT_FOUND,
                404);
        assertThat(audits).as("only a self-approval is recorded by this path; a 404 records nothing").isEmpty();
    }

    @Test
    @DisplayName("a reason the body's @NotBlank lets through - ideographic or other Unicode whitespace only - and one"
            + " holding a card number or an IBAN are the request's 422, never a 500, the reason never echoed, nothing"
            + " written (the fix round's M-3, M-4)")
    void aReasonTheScreenRefusesIs422() {
        for (String reason : java.util.List.of("　", "　 ", "card 4111 1111 1111 1111 posted twice",
                "IBAN GB82 WEST 1234 5698 7654 32 in the memo")) {
            assertThatThrownBy(() -> as(PROPOSER, () -> desk().propose(account.value().toString(), reason,
                    "m-" + reason.hashCode()))).isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                        assertThat(refused.errorCode().status()).isEqualTo(422);
                        assertThat(refused.clientDetail().orElseThrow()).doesNotContain("4111").doesNotContain("GB82");
                    });
        }
        assertThat(rebuilds).isEmpty();
        assertThat(audits).isEmpty();
    }

    // -----------------------------------------------------------------

    private void refused(Supplier<?> act, ErrorCode code, int status) {
        refusedAs(PROPOSER, act, code, status);
    }

    private void refusedAs(Actor actor, Supplier<?> act, ErrorCode code, int status) {
        assertThatThrownBy(() -> as(actor, act)).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
            assertThat(refused.clientDetail().orElseThrow()).doesNotContain("12345").doesNotContain("123.45");
        });
    }

    private double count(String outcome) {
        return registry.get(ProjectionRebuildMetrics.REBUILD).tag("outcome", outcome).counter().count();
    }

    private LedgerProjectionRebuilds desk() {
        ProjectionRebuilds service = new ProjectionRebuilds(
                new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)), new Store(), new Rows(),
                (uow, record) -> {
                    audits.add(record);
                    trail.add("audit " + record.operation().code() + " " + record.outcome());
                }, IDS, CLOCK);
        return new LedgerProjectionRebuilds(service, NoDatabase.transactions(trail), NoDatabase.oneConnection(),
                new ProjectionRebuildMetrics(registry));
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    /** One USD account at scale 2: its row and what its journal derives. */
    private final class Rows implements ProjectionRebuildRows<Connection> {

        @Override
        public Observation observe(Connection unitOfWork, LedgerAccountId named) {
            if (!named.equals(account)) {
                throw new ProjectionRebuildAccountNotFoundException(named);
            }
            return new Observation(Optional.of(new Row(USD, 2, row)), new Derived(USD, 2, journal), true);
        }

        @Override
        public boolean shareAccount(Connection unitOfWork, LedgerAccountId named) {
            return true;
        }

        @Override
        public Optional<Row> lockRow(Connection unitOfWork, LedgerAccountId named) {
            return Optional.of(new Row(USD, 2, row));
        }

        @Override
        public Derived derive(Connection unitOfWork, LedgerAccountId named) {
            return new Derived(USD, 2, journal);
        }

        @Override
        public boolean insertRow(Connection unitOfWork, LedgerAccountId named, Derived derived, Instant at) {
            throw new AssertionError("the row exists");
        }

        @Override
        public void writeRow(Connection unitOfWork, LedgerAccountId named, Derived derived, Instant at) {
            row = derived.figures();
        }
    }

    private final class Store implements ProjectionRebuildStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, ProjectionRebuild rebuild) {
            if (standingFor(unitOfWork, rebuild.account()).isPresent()) {
                throw new com.finapp.ledger.ProjectionRebuildStandingException(rebuild.account());
            }
            rebuilds.put(rebuild.id(), rebuild);
        }

        @Override
        public Optional<ProjectionRebuild> findById(Connection unitOfWork, ProjectionRebuildId id) {
            return Optional.ofNullable(rebuilds.get(id));
        }

        @Override
        public Optional<ProjectionRebuild> lockById(Connection unitOfWork, ProjectionRebuildId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public Optional<ProjectionRebuild> standingFor(Connection unitOfWork, LedgerAccountId named) {
            return rebuilds.values().stream()
                    .filter(held -> held.account().equals(named) && held.status() == ProjectionRebuildStatus.PROPOSED).findFirst();
        }

        @Override
        public boolean decide(Connection unitOfWork, ProjectionRebuildId id, ProjectionRebuildStatus to, String decidedBy,
                Instant decidedAt, Optional<ProjectionRebuild.Approval> approval) {
            ProjectionRebuild held = rebuilds.get(id);
            if (held == null || held.status() != ProjectionRebuildStatus.PROPOSED) {
                return false;
            }
            rebuilds.put(id, held.decided(to, decidedBy, decidedAt, approval));
            return true;
        }
    }

    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(),
                    correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(),
                    held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            return true;
        }
    }
}
