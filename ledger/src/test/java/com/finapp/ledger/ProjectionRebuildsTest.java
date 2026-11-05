package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
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
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four-eyes projection rebuild without a database (`P14-TSK-026`, ADR-0119 decision 1; {@code INV-BAL-01},
 * {@code INV-BAL-02}, {@code INV-AUD-04}): every door and refusal, and the lock protocol as behaviour. {@link World} is
 * one account's journal, holds and projection row; its {@link World#lockRow} can let a posting <em>commit while the
 * rebuild waits for the row</em> - interleaving 1 - and a posting can land after the rebuild commits - interleaving 2. A
 * rebuild that summed before taking the lock, or wrote the proposal's figures, leaves the row disagreeing with the journal
 * here; that is the hermetic control for the probes ({@code MUTATION_TESTING.md} §2). The real interleaving on PostgreSQL,
 * with latches, is {@code ProjectionRebuildDatabaseTest}'s (owed, C5).
 */
@DisplayName("the four-eyes projection rebuild (P14-TSK-026)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ProjectionRebuildsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Connection UOW = (Connection) java.lang.reflect.Proxy.newProxyInstance(
            ProjectionRebuildsTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor PROPOSER = new Actor("ledger-proposer", ActorType.CUSTOMER);
    private static final Actor APPROVER = new Actor("ledger-approver", ActorType.CUSTOMER);
    private static final Actor THIRD = new Actor("ledger-third", ActorType.EMPLOYEE);

    private final LedgerAccountId account = LedgerAccountId.of(IDS.next());
    private final World world = new World();
    private final Rebuilds store = new Rebuilds();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();

    @Test
    @DisplayName("a drifting projection is proposed with what was observed, and nothing is written to it; the approval"
            + " writes what the journal and the holds derive, and the record keeps both readings")
    void aDriftIsProposedThenApplied() {
        world.journal(10_000, 3).holds(1_500).row(9_000, 1_500, 3);
        ProjectionRebuilds.Outcome proposed = as(PROPOSER, () -> rebuilds().propose(UOW, account, "row lost a posting", "k1"));
        assertThat(proposed.replayed()).isFalse();
        ProjectionRebuild rebuild = proposed.rebuild();
        assertThat(rebuild.status()).isEqualTo(ProjectionRebuildStatus.PROPOSED);
        assertThat(rebuild.observedRow()).contains(new ProjectionFigures(9_000, 1_500, 3));
        assertThat(rebuild.observedDerived()).isEqualTo(new ProjectionFigures(10_000, 1_500, 3));
        assertThat(world.row).contains(new ProjectionFigures(9_000, 1_500, 3));
        assertThat(world.calls).doesNotContain("lockRow", "writeRow", "insertRow", "shareAccount");

        ProjectionRebuilds.Outcome approved = as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        assertThat(approved.rebuild().status()).isEqualTo(ProjectionRebuildStatus.APPLIED);
        assertThat(world.row).contains(new ProjectionFigures(10_000, 1_500, 3));
        assertThat(approved.rebuild().approval().orElseThrow().before()).contains(new ProjectionFigures(9_000, 1_500, 3));
        assertThat(approved.rebuild().approval().orElseThrow().after()).isEqualTo(new ProjectionFigures(10_000, 1_500, 3));
        assertThat(ProjectionRebuilds.driftAsProposed(approved.rebuild())).isTrue();
        assertThat(audited).extracting(record -> record.operation().code())
                .containsExactly("ledger.ProjectionRebuildProposed", "ledger.ProjectionRebuilt");
        assertThat(audited.get(0).reason()).contains("row lost a posting");
        assertThat(audited.get(1).changeSummary().orElseThrow()).contains("outcome=APPLIED").contains("postedMoved=true")
                .contains("holdsMoved=false").contains("seqBefore=3").contains("seqAfter=3")
                .doesNotContain("10000").doesNotContain("9000");
    }

    @Test
    @DisplayName("the lock order is the posting's: the account's key share, then the projection row, then the derivation,"
            + " then the write - never a sum before the lock")
    void theLockOrderIsThePostings() {
        world.journal(5_000, 2).row(4_000, 0, 1);
        ProjectionRebuild rebuild = proposed();
        world.calls.clear();
        as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        assertThat(world.calls).containsExactly("shareAccount", "lockRow", "derive", "writeRow");
    }

    @Test
    @DisplayName("interleaving 1, a posting first: one that commits while the rebuild waits for the row is in the sum it"
            + " writes - the derivation reads after the grant, so the row ends what the journal says")
    void aPostingCommittedWhileTheRebuildWaitedIsInTheSum() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        ProjectionRebuild rebuild = proposed();
        // The posting held the row; the rebuild's FOR UPDATE returns once it committed - its delta on the row and the journal.
        world.onLock = () -> world.post(700);
        ProjectionRebuilds.Outcome applied = as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        assertThat(world.row).contains(world.derived());
        assertThat(world.row).contains(new ProjectionFigures(5_700, 0, 3));
        assertThat(applied.rebuild().approval().orElseThrow().after()).isEqualTo(new ProjectionFigures(5_700, 0, 3));
        assertThat(applied.rebuild().approval().orElseThrow().before()).contains(new ProjectionFigures(4_700, 0, 3));
    }

    @Test
    @DisplayName("interleaving 2, the rebuild first: a posting that commits after it composes on the written row - no lost"
            + " update - and the approval wrote its own reading, not the proposal's")
    void aPostingAfterTheRebuildComposes() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        ProjectionRebuild rebuild = proposed();
        assertThat(rebuild.observedDerived()).isEqualTo(new ProjectionFigures(5_000, 0, 2));
        world.post(300); // between the proposal and the approval: the approval must read it
        as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        assertThat(world.row).contains(new ProjectionFigures(5_300, 0, 3));
        world.post(200); // after the rebuild's commit: the posting's increment re-reads the row
        assertThat(world.row).contains(world.derived()).contains(new ProjectionFigures(5_500, 0, 4));
    }

    @Test
    @DisplayName("a rebuild whose approval finds nothing to correct is UNCHANGED: the projection untouched, the act recorded")
    void nothingToCorrectIsUnchanged() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        ProjectionRebuild rebuild = proposed();
        world.row = Optional.of(world.derived()); // another repair landed first
        ProjectionRebuilds.Outcome outcome = as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        assertThat(outcome.rebuild().status()).isEqualTo(ProjectionRebuildStatus.UNCHANGED);
        assertThat(world.calls).doesNotContain("writeRow", "insertRow");
        assertThat(outcome.rebuild().approval().orElseThrow().unchanged()).isTrue();
        assertThat(ProjectionRebuilds.driftAsProposed(outcome.rebuild())).isFalse();
        assertThat(audited.get(audited.size() - 1).changeSummary().orElseThrow()).contains("outcome=UNCHANGED")
                .contains("driftAsProposed=false");
    }

    @Test
    @DisplayName("four eyes: the proposer's approval is refused and nothing is written; a third person may approve")
    void theProposerCannotApprove() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        ProjectionRebuild rebuild = proposed();
        world.calls.clear();
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().approve(UOW, rebuild.id(), "k2")))
                .isInstanceOf(RebuildSelfApprovalRefusedException.class);
        assertThat(world.calls).isEmpty();
        assertThat(world.row).contains(new ProjectionFigures(4_000, 0, 2));
        assertThat(store.rows.get(rebuild.id()).status()).isEqualTo(ProjectionRebuildStatus.PROPOSED);
        assertThat(as(THIRD, () -> rebuilds().approve(UOW, rebuild.id(), "k3")).rebuild().status())
                .isEqualTo(ProjectionRebuildStatus.APPLIED);
    }

    @Test
    @DisplayName("keyed and converging: a retried key replays; the same approver under a new key converges writing nothing;"
            + " another person meets RebuildNotOpen; a key reused for another account is a conflict")
    void keyedAndConverging() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        ProjectionRebuild rebuild = proposed();
        ProjectionRebuilds.Outcome replay = as(PROPOSER, () -> rebuilds().propose(UOW, account, "row lost a posting", "k1"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.rebuild().id()).isEqualTo(rebuild.id());
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().propose(UOW, LedgerAccountId.of(IDS.next()),
                "row lost a posting", "k1"))).isInstanceOf(IdempotencyConflictException.class);

        as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        int records = audited.size();
        world.calls.clear();
        assertThat(as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2")).replayed()).isTrue();
        assertThat(as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k9")).replayed()).isTrue();
        assertThat(world.calls).isEmpty();
        assertThat(audited).hasSize(records);
        assertThatThrownBy(() -> as(THIRD, () -> rebuilds().approve(UOW, rebuild.id(), "k4")))
                .isInstanceOf(RebuildNotOpenException.class);
        assertThatThrownBy(() -> as(THIRD, () -> rebuilds().reject(UOW, rebuild.id(), "k5")))
                .isInstanceOf(RebuildNotOpenException.class);
    }

    @Test
    @DisplayName("a proposal is refused, nothing written, when the projection agrees, when an entry committed mid-reading,"
            + " when a rebuild already stands, and for an unknown account")
    void theProposalsRefusals() {
        world.journal(5_000, 2).row(5_000, 0, 2);
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().propose(UOW, account, "why", "a")))
                .isInstanceOf(ProjectionNotDriftingException.class).hasMessageContaining("nothing to rebuild");
        world.row(4_000, 0, 2);
        world.unstable = true;
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().propose(UOW, account, "why", "b")))
                .isInstanceOf(ProjectionNotDriftingException.class).hasMessageContaining("mid-reading");
        world.unstable = false;
        proposed();
        assertThatThrownBy(() -> as(THIRD, () -> rebuilds().propose(UOW, account, "why", "c")))
                .isInstanceOf(ProjectionRebuildStandingException.class);
        world.exists = false;
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().propose(UOW, LedgerAccountId.of(IDS.next()), "why", "d")))
                .isInstanceOf(ProjectionRebuildAccountNotFoundException.class);
        assertThat(store.rows).hasSize(1);
        assertThat(world.calls).doesNotContain("writeRow", "insertRow");
    }

    @Test
    @DisplayName("what the door cannot write is refused: a row at a scale its history does not have, a row for an account"
            + " with no entries - the migrator's - and a platform actor proposes nothing")
    void notRebuildableAndNotAPerson() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        world.rowScale = 3;
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().propose(UOW, account, "why", "a")))
                .isInstanceOf(ProjectionNotRebuildableException.class).hasMessageContaining("scale");
        world.rowScale = 2;
        world.journal(0, 0).row(4_000, 0, 1);
        assertThatThrownBy(() -> as(PROPOSER, () -> rebuilds().propose(UOW, account, "why", "b")))
                .isInstanceOf(ProjectionNotRebuildableException.class).hasMessageContaining("no entries");
        world.journal(5_000, 2);
        assertThatThrownBy(() -> as(new Actor("platform", ActorType.SYSTEM), () -> rebuilds().propose(UOW, account, "why", "c")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rows).isEmpty();
    }

    @Test
    @DisplayName("a row a bypass never created is inserted from the journal; if a posting created it first, the rebuild"
            + " re-locks and derives again")
    void aMissingRowIsInserted() {
        world.journal(5_000, 2).holds(0);
        world.row = Optional.empty();
        ProjectionRebuild rebuild = proposed();
        assertThat(rebuild.observedRow()).isEmpty();
        ProjectionRebuilds.Outcome outcome = as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "k2"));
        assertThat(outcome.rebuild().status()).isEqualTo(ProjectionRebuildStatus.APPLIED);
        assertThat(outcome.rebuild().approval().orElseThrow().before()).isEmpty();
        assertThat(world.row).contains(new ProjectionFigures(5_000, 0, 2));
        assertThat(world.calls).containsSubsequence("lockRow", "derive", "insertRow");

        World raced = new World();
        LedgerAccountId other = LedgerAccountId.of(IDS.next());
        raced.journal(5_000, 2).row = Optional.empty();
        ProjectionRebuilds racing = new ProjectionRebuilds(executor(), store, raced, writer(), IDS, CLOCK);
        ProjectionRebuild second = as(PROPOSER, () -> racing.propose(UOW, other, "bypass", "r1")).rebuild();
        // A posting inserts the row (its delta only) and commits before the rebuild's insert lands.
        raced.onInsert = () -> raced.postCreatingTheRow(400);
        ProjectionRebuilds.Outcome reLocked = as(APPROVER, () -> racing.approve(UOW, second.id(), "r2"));
        assertThat(raced.calls).containsSubsequence("insertRow", "lockRow", "derive", "writeRow");
        assertThat(raced.row).contains(new ProjectionFigures(5_400, 0, 3));
        assertThat(reLocked.rebuild().approval().orElseThrow().before()).contains(new ProjectionFigures(400, 0, 1));
    }

    @Test
    @DisplayName("a rejection by anyone holding the door, the proposer's withdrawal included, ends a rebuild; a repeat"
            + " converges; an unknown rebuild is not found")
    void rejectionAndWithdrawal() {
        world.journal(5_000, 2).row(4_000, 0, 2);
        ProjectionRebuild rebuild = proposed();
        ProjectionRebuilds.Outcome withdrawn = as(PROPOSER, () -> rebuilds().reject(UOW, rebuild.id(), "w1"));
        assertThat(withdrawn.rebuild().status()).isEqualTo(ProjectionRebuildStatus.REJECTED);
        assertThat(as(THIRD, () -> rebuilds().reject(UOW, rebuild.id(), "w2")).replayed()).isTrue();
        assertThat(audited).extracting(record -> record.operation().code())
                .containsExactly("ledger.ProjectionRebuildProposed", "ledger.ProjectionRebuildRejected");
        assertThat(audited.get(1).changeSummary().orElseThrow()).contains("byProposer=true");
        assertThat(world.row).contains(new ProjectionFigures(4_000, 0, 2));
        assertThatThrownBy(() -> as(APPROVER, () -> rebuilds().approve(UOW, rebuild.id(), "w3")))
                .isInstanceOf(RebuildNotOpenException.class);
        assertThatThrownBy(() -> as(APPROVER, () -> rebuilds().approve(UOW, ProjectionRebuildId.of(IDS.next()), "w4")))
                .isInstanceOf(ProjectionRebuildNotFoundException.class);
        // A later drift is a new proposal: the standing one ended.
        assertThat(as(THIRD, () -> rebuilds().propose(UOW, account, "again", "w5")).rebuild().status())
                .isEqualTo(ProjectionRebuildStatus.PROPOSED);
    }

    // ------------------------------------------------------------------ the world

    private ProjectionRebuild proposed() {
        return as(PROPOSER, () -> rebuilds().propose(UOW, account, "row lost a posting", "k1")).rebuild();
    }

    private ProjectionRebuilds rebuilds() {
        return new ProjectionRebuilds(executor(), store, world, writer(), IDS, CLOCK);
    }

    private IdempotentExecutor executor() {
        return new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5));
    }

    @SuppressWarnings("unchecked")
    private AuditWriter<Connection> writer() {
        return (AuditWriter<Connection>) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {AuditWriter.class}, (proxy, call, args) -> {
                    if (call.getName().equals("append")) {
                        audited.add((AuditRecord) args[1]);
                        return null;
                    }
                    throw new AssertionError("AuditWriter." + call.getName());
                });
    }

    private static <T> T as(Actor actor, java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    /** One account's journal, holds and projection row, at USD scale 2 - and the calls the rebuild made, in order. */
    private static final class World implements ProjectionRebuildRows<Connection> {
        long posted;
        long entries;
        long held;
        Optional<ProjectionFigures> row = Optional.empty();
        int rowScale = 2;
        boolean exists = true;
        boolean unstable;
        Runnable onLock = () -> { };
        Runnable onInsert = () -> { };
        final List<String> calls = new ArrayList<>();

        World journal(long posted, long entries) {
            this.posted = posted;
            this.entries = entries;
            return this;
        }

        World holds(long held) {
            this.held = held;
            return this;
        }

        World row(long postedMinor, long holdsMinor, long seq) {
            this.row = Optional.of(new ProjectionFigures(postedMinor, holdsMinor, seq));
            return this;
        }

        ProjectionFigures derived() {
            return new ProjectionFigures(posted, held, entries);
        }

        /** A domain posting committing: its lines and its row's increment, together (ADR-0041 rule 1). */
        void post(long delta) {
            posted += delta;
            entries++;
            row = row.map(was -> new ProjectionFigures(was.postedMinor() + delta, was.holdsMinor(), was.appliedEntries() + 1));
        }

        /** A posting that found no row and inserted one with its own delta - the upsert's insert arm. */
        void postCreatingTheRow(long delta) {
            posted += delta;
            entries++;
            row = Optional.of(new ProjectionFigures(delta, 0, 1));
        }

        @Override
        public Observation observe(Connection unitOfWork, LedgerAccountId account) {
            calls.add("observe");
            if (!exists) {
                throw new ProjectionRebuildAccountNotFoundException(account);
            }
            return new Observation(row.map(figures -> new Row(USD, rowScale, figures)), new Derived(USD, 2, derived()), !unstable);
        }

        @Override
        public boolean shareAccount(Connection unitOfWork, LedgerAccountId account) {
            calls.add("shareAccount");
            return true;
        }

        @Override
        public Optional<Row> lockRow(Connection unitOfWork, LedgerAccountId account) {
            calls.add("lockRow");
            Runnable committing = onLock;
            onLock = () -> { };
            committing.run();
            return row.map(figures -> new Row(USD, rowScale, figures));
        }

        @Override
        public Derived derive(Connection unitOfWork, LedgerAccountId account) {
            calls.add("derive");
            return new Derived(USD, 2, derived());
        }

        @Override
        public boolean insertRow(Connection unitOfWork, LedgerAccountId account, Derived derived, Instant at) {
            calls.add("insertRow");
            Runnable racing = onInsert;
            onInsert = () -> { };
            racing.run();
            if (row.isPresent()) {
                return false;
            }
            row = Optional.of(derived.figures());
            return true;
        }

        @Override
        public void writeRow(Connection unitOfWork, LedgerAccountId account, Derived derived, Instant at) {
            calls.add("writeRow");
            row = Optional.of(derived.figures());
        }
    }

    /** The rebuild records, with V031's partial unique index and four-eyes CHECK emulated. */
    private static final class Rebuilds implements ProjectionRebuildStore<Connection> {
        final Map<ProjectionRebuildId, ProjectionRebuild> rows = new HashMap<>();

        @Override
        public void insert(Connection unitOfWork, ProjectionRebuild rebuild) {
            if (standingFor(unitOfWork, rebuild.account()).isPresent()) {
                throw new ProjectionRebuildStandingException(rebuild.account());
            }
            rows.put(rebuild.id(), rebuild);
        }

        @Override
        public Optional<ProjectionRebuild> findById(Connection unitOfWork, ProjectionRebuildId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<ProjectionRebuild> lockById(Connection unitOfWork, ProjectionRebuildId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public Optional<ProjectionRebuild> standingFor(Connection unitOfWork, LedgerAccountId account) {
            return rows.values().stream().filter(row -> row.account().equals(account)
                    && row.status() == ProjectionRebuildStatus.PROPOSED).findFirst();
        }

        @Override
        public boolean decide(Connection unitOfWork, ProjectionRebuildId id, ProjectionRebuildStatus to, String decidedBy,
                Instant decidedAt, Optional<ProjectionRebuild.Approval> approval) {
            ProjectionRebuild held = rows.get(id);
            if (held == null || held.status() != ProjectionRebuildStatus.PROPOSED) {
                return false;
            }
            if (to.approved() && decidedBy.equals(held.proposedBy())) {
                throw new IllegalStateException("projection_rebuild_approver_is_not_proposer");
            }
            rows.put(id, held.decided(to, decidedBy, decidedAt, approval));
            return true;
        }
    }

    /** The idempotency records, in memory; a refused act's claim stays, so every case uses its own keys. */
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
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(),
                    held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            // A refused act's claim stays IN_PROGRESS here (no rollback in memory): treat it as abandoned, as the
            // database's rollback would have left no claim at all.
            return true;
        }
    }

    @SuppressWarnings("unused")
    private static UUID unused() {
        return UUID.randomUUID();
    }
}
