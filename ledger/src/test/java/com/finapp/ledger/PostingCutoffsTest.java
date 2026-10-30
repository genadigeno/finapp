package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The posting cutoff's API without a database (`P14-TSK-004`, {@code INV-ACC-08}): the one-period shape judged before
 * any statement, and the edge's statements in their order - the exclusive lock first, the conditional {@code UPDATE},
 * the event - with the replay and the stale expectation told apart. The same edges against a live PostgreSQL, and the
 * trigger's re-judgement, are {@code PostingCutoffDatabaseTest}'s (owed, C5).
 */
@DisplayName("the posting cutoff's moves: one period, locked first, evented, replay-safe (P14-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingCutoffsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("1999-06-15T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final LocalDate TODAY = LocalDate.of(1999, 6, 15);
    private static final LocalDate JAN = LocalDate.of(1999, 1, 31);
    private static final LocalDate FEB = LocalDate.of(1999, 2, 28);
    private static final LocalDate MAR = LocalDate.of(1999, 3, 31);
    private static final LocalDate DEC = LocalDate.of(1998, 12, 31);
    private static final String CALLERS_TIMEOUT = "750ms";
    private static final String RELEASE = "RELEASE SAVEPOINT";
    private static final String ROLLBACK_TO = "ROLLBACK TO SAVEPOINT";
    private static final List<String> LOCKED = List.of(PostingCutoffs.READ_LOCK_TIMEOUT,
            PostingCutoffs.SET_LOCK_TIMEOUT, PostingCutoffs.LOCK_SQL, PostingCutoffs.SET_LOCK_TIMEOUT, RELEASE);

    // ------------------------------------------------------------------ the shape, before any statement

    @Test
    @DisplayName("an advance is the first lock (any past month end) or the next month end - across a year too")
    void anAdvanceIsOnePeriod() {
        PostingCutoffs.checkAdvance(Optional.empty(), MAR, TODAY);
        PostingCutoffs.checkAdvance(Optional.of(JAN), FEB, TODAY);
        PostingCutoffs.checkAdvance(Optional.of(FEB), MAR, TODAY);
        PostingCutoffs.checkAdvance(Optional.of(DEC), JAN, TODAY);
        assertThatThrownBy(() -> PostingCutoffs.checkAdvance(Optional.of(JAN), MAR, TODAY))
                .as("two periods at once").isInstanceOf(IllegalCutoffMoveException.class)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("the next is 1999-02-28");
        assertThatThrownBy(() -> PostingCutoffs.checkAdvance(Optional.of(FEB), FEB, TODAY))
                .as("standing still is not an advance").isInstanceOf(IllegalCutoffMoveException.class);
        assertThatThrownBy(() -> PostingCutoffs.checkAdvance(Optional.of(MAR), FEB, TODAY))
                .as("backwards is a move back").isInstanceOf(IllegalCutoffMoveException.class);
    }

    @Test
    @DisplayName("the cutoff never covers today or the future")
    void neverOverToday() {
        LocalDate june = LocalDate.of(1999, 6, 30);
        assertThatThrownBy(() -> PostingCutoffs.checkAdvance(Optional.of(LocalDate.of(1999, 5, 31)), june, TODAY))
                .isInstanceOf(IllegalCutoffMoveException.class).hasMessageContaining("never covers today");
        assertThatThrownBy(() -> PostingCutoffs.checkAdvance(Optional.empty(), june, june))
                .as("the period's last day is still open on that day").isInstanceOf(IllegalCutoffMoveException.class);
        PostingCutoffs.checkAdvance(Optional.empty(), june, june.plusDays(1));
    }

    @Test
    @DisplayName("a move back is the previous month end or unset; never two periods; dates are month ends")
    void aMoveBackIsOnePeriod() {
        PostingCutoffs.checkMoveBack(MAR, Optional.of(FEB));
        PostingCutoffs.checkMoveBack(JAN, Optional.of(DEC));
        PostingCutoffs.checkMoveBack(MAR, Optional.empty());
        assertThatThrownBy(() -> PostingCutoffs.checkMoveBack(MAR, Optional.of(JAN)))
                .isInstanceOf(IllegalCutoffMoveException.class).hasMessageContaining("the previous is 1999-02-28");
        assertThatThrownBy(() -> PostingCutoffs.checkMoveBack(LocalDate.of(1999, 3, 30), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("last day");
        assertThatThrownBy(() -> PostingCutoffs.checkAdvance(Optional.empty(), LocalDate.of(1999, 2, 27), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ the edge's statements

    @Test
    @DisplayName("a move takes the exclusive lock FIRST, then the conditional UPDATE, then its one event row")
    void aMoveIsLockedConditionalAndEvented() throws Exception {
        FakeDatabase db = new FakeDatabase();
        db.revision = 7L;
        PostingCutoffs.Outcome outcome;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            outcome = cutoffs().advance(db.connection(), Optional.of(JAN), FEB, "lock February 1999");
        }
        assertThat(outcome).isEqualTo(PostingCutoffs.Outcome.MOVED);
        assertThat(db.statements).containsExactlyElementsOf(concat(LOCKED, PostingCutoffs.MOVE, PostingCutoffs.EVENT));
        assertThat(db.binds.get(1).get(0)).as("each attempt bounded").isEqualTo("2000ms");
        assertThat(db.binds.get(3).get(0)).as("the caller's own timeout restored once held").isEqualTo(CALLERS_TIMEOUT);
        assertThat(db.binds.get(5)).as("SET to, WHERE from").startsWith(FEB, JAN);
        List<Object> event = db.binds.get(6);
        assertThat(event.get(1)).as("the revision the UPDATE returned").isEqualTo(7L);
        assertThat(event.subList(2, 5)).containsExactly("ADVANCE", JAN, FEB);
        assertThat(event.get(5)).as("the actor, never a default").isNotNull();
        assertThat(event.get(6)).as("the correlation").isNotNull();
        assertThat(event.get(7)).as("the cause, resolved at a flow root").isEqualTo(event.get(6));
        assertThat(event.get(8)).isEqualTo("lock February 1999");
    }

    @Test
    @DisplayName("a replayed move - the cutoff already at its target - answers UNCHANGED and writes no event")
    void aReplayAnswersUnchanged() throws Exception {
        FakeDatabase db = new FakeDatabase();
        db.revision = null;
        db.standing = FEB;
        PostingCutoffs.Outcome outcome;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            outcome = cutoffs().advance(db.connection(), Optional.of(JAN), FEB, "lock February 1999");
        }
        assertThat(outcome).isEqualTo(PostingCutoffs.Outcome.UNCHANGED);
        assertThat(db.statements).containsExactlyElementsOf(concat(LOCKED, PostingCutoffs.MOVE, PostingCutoffs.READ));
    }

    @Test
    @DisplayName("a move back to unset binds NULL; a stale expectation is refused, never applied")
    void aStaleMoveIsRefused() throws Exception {
        FakeDatabase moved = new FakeDatabase();
        moved.revision = 3L;
        FakeDatabase stale = new FakeDatabase();
        stale.revision = null;
        stale.standing = MAR;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThat(cutoffs().moveBack(moved.connection(), JAN, Optional.empty(), "unlock January 1999"))
                    .isEqualTo(PostingCutoffs.Outcome.MOVED);
            assertThatThrownBy(() -> cutoffs().moveBack(stale.connection(), FEB, Optional.of(JAN), "unlock February"))
                    .isInstanceOf(IllegalCutoffMoveException.class)
                    .hasMessageContaining("stands at 1999-03-31, not at 1999-02-28");
        }
        assertThat(moved.binds.get(5)).as("SET NULL, WHERE January").startsWith(null, JAN);
        assertThat(moved.binds.get(6).subList(2, 5)).containsExactly("MOVE_BACK", JAN, null);
        assertThat(stale.statements).doesNotContain(PostingCutoffs.EVENT);
    }

    @Test
    @DisplayName("the schema's refusal of a move that is not one period surfaces as the same named refusal")
    void theSchemasRefusalIsTranslated() throws Exception {
        FakeDatabase db = new FakeDatabase();
        db.moveFailure = new SQLException(
                "ERROR: ledger_posting_cutoff_moves_one_period: the cutoff may not move from 1999-02-28 to"
                        + " 1999-01-31", "23514");
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> cutoffs().moveBack(db.connection(), FEB, Optional.of(JAN), "unlock February"))
                    .isInstanceOf(IllegalCutoffMoveException.class).hasMessageContaining("one period");
        }
    }

    @Test
    @DisplayName("a busy key is retried inside a savepoint, bounded; exhausted, nothing moves and the act is retried later")
    void aBusyLockIsRetriedThenGivenUp() throws Exception {
        FakeDatabase twice = new FakeDatabase();
        twice.revision = 4L;
        twice.busy = 2;
        FakeDatabase always = new FakeDatabase();
        always.busy = PostingCutoffs.LOCK_ATTEMPTS;
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThat(cutoffs().advance(twice.connection(), Optional.of(JAN), FEB, "lock February 1999"))
                    .isEqualTo(PostingCutoffs.Outcome.MOVED);
            assertThatThrownBy(() -> cutoffs().advance(always.connection(), Optional.of(JAN), FEB, "lock February"))
                    .isInstanceOf(PostingCutoffBusyException.class)
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(twice.statements.stream().filter(ROLLBACK_TO::equals)).as("two refused attempts").hasSize(2);
        assertThat(twice.statements).endsWith(PostingCutoffs.MOVE, PostingCutoffs.EVENT);
        assertThat(always.statements.stream().filter(PostingCutoffs.LOCK_SQL::equals))
                .hasSize(PostingCutoffs.LOCK_ATTEMPTS);
        assertThat(always.statements).doesNotContain(PostingCutoffs.MOVE, PostingCutoffs.EVENT);
    }

    @Test
    @DisplayName("a move nobody can be asked about is refused before any statement - no actor, no move")
    void aMoveWithoutAnActorIsRefused() {
        FakeDatabase db = new FakeDatabase();
        db.revision = 1L;
        try (CorrelationContext.Scope flow =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> cutoffs().advance(db.connection(), Optional.of(JAN), FEB, "lock February 1999"))
                    .isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(IllegalCutoffMoveException.class);
        }
        assertThat(db.statements).as("the key is never taken without an actor").isEmpty();
    }

    @Test
    @DisplayName("a malformed move never takes the lock: a blank reason, a date that is not a month end")
    void aMalformedMoveTakesNoLock() {
        FakeDatabase db = new FakeDatabase();
        assertThatThrownBy(() -> cutoffs().advance(db.connection(), Optional.empty(), FEB, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cutoffs().advance(db.connection(), Optional.empty(), LocalDate.of(1999, 2, 1), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.statements).isEmpty();
    }

    // ------------------------------------------------------------------

    private static List<String> concat(List<String> head, String... tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(List.of(tail));
        return all;
    }

    private static PostingCutoffs cutoffs() {
        return new PostingCutoffs(IDS, CLOCK);
    }

    /** A scripted JDBC connection: records each executed statement and its binds, answers MOVE and READ. */
    private static final class FakeDatabase {

        final List<String> statements = new ArrayList<>();
        final List<List<Object>> binds = new ArrayList<>();
        Long revision;
        LocalDate standing;
        SQLException moveFailure;
        int busy;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "prepareStatement" -> statement((String) args[0]);
                            case "setSavepoint" -> null;
                            case "releaseSavepoint" -> {
                                statements.add(RELEASE);
                                binds.add(List.of());
                                yield null;
                            }
                            case "rollback" -> {
                                statements.add(ROLLBACK_TO);
                                binds.add(List.of());
                                yield null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private PreparedStatement statement(String sql) {
            Object[] bound = new Object[12];
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setObject", "setString", "setLong", "setTimestamp" -> {
                            bound[(Integer) args[0] - 1] = args[1];
                            yield null;
                        }
                        case "setNull" -> {
                            bound[(Integer) args[0] - 1] = null;
                            yield null;
                        }
                        case "execute", "executeUpdate", "executeQuery" -> {
                            statements.add(sql);
                            binds.add(Arrays.asList(bound));
                            if (sql.equals(PostingCutoffs.MOVE) && moveFailure != null) {
                                throw moveFailure;
                            }
                            if (sql.equals(PostingCutoffs.LOCK_SQL) && busy > 0) {
                                busy--;
                                throw new SQLException("canceling statement due to lock timeout",
                                        PostingCutoffs.LOCK_NOT_AVAILABLE);
                            }
                            yield switch (method.getName()) {
                                case "execute" -> Boolean.TRUE;
                                case "executeUpdate" -> 1;
                                default -> rows(sql.equals(PostingCutoffs.MOVE) ? revision
                                                : sql.equals(PostingCutoffs.READ_LOCK_TIMEOUT) ? CALLERS_TIMEOUT
                                                : standing,
                                        sql.equals(PostingCutoffs.MOVE) && revision == null);
                            };
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ResultSet rows(Object value, boolean empty) {
            boolean[] read = {empty};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            boolean hasRow = !read[0];
                            read[0] = true;
                            yield hasRow;
                        }
                        case "getLong" -> (Long) value;
                        case "getObject" -> value;
                        case "getString" -> (String) value;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
