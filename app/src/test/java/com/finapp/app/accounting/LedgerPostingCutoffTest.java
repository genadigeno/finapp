package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.PostingCutoff;
import com.finapp.ledger.IllegalCutoffMoveException;
import com.finapp.ledger.PostingCutoffBusyException;
import com.finapp.ledger.PostingCutoffs;
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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * accounting's {@link PostingCutoff} over the ledger's {@link PostingCutoffs} (`P14-TSK-004`, ADR-0113 decision 3): one
 * namespace, two keys that never meet; the ledger's outcome mapped to the port's {@code boolean}; the ledger's refusal
 * crossing as the JDK type the port documents. The edges against PostgreSQL are {@code PostingCutoffDatabaseTest}'s.
 */
@DisplayName("accounting's cutoff port over the ledger's API (P14-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class LedgerPostingCutoffTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("1999-06-15T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final LocalDate JAN = LocalDate.of(1999, 1, 31);
    private static final LocalDate FEB = LocalDate.of(1999, 2, 28);

    @Test
    @DisplayName("namespace 14 holds two keys - cutoff and mapping - and they are different keys")
    void theTwoKeysOfNamespaceFourteenNeverMeet() {
        assertThat(PostingCutoffs.NAMESPACE).isEqualTo(JdbcGlMappingStore.NAMESPACE).isEqualTo(14);
        assertThat(PostingCutoffs.KEY).isNotEqualTo(JdbcGlMappingStore.KEY);
        assertThat(PostingCutoffs.LOCK_SQL).doesNotContain(JdbcGlMappingStore.KEY);
    }

    @Test
    @DisplayName("MOVED is true, a replay (UNCHANGED) is false, and the read passes through")
    void theOutcomeIsTheBoolean() throws Exception {
        PostingCutoff port = new LedgerPostingCutoff(new PostingCutoffs(IDS, CLOCK));
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThat(port.advance(connection(5L, JAN), Optional.of(JAN), FEB, "lock February 1999")).isTrue();
            assertThat(port.advance(connection(null, FEB), Optional.of(JAN), FEB, "lock February 1999")).isFalse();
            assertThat(port.moveBack(connection(null, JAN), FEB, Optional.of(JAN), "unlock February")).isFalse();
            assertThat(port.closedThrough(connection(null, JAN))).contains(JAN);
            assertThat(port.closedThrough(connection(null, null))).isEmpty();
        }
    }

    @Test
    @DisplayName("the ledger's refusal is the IllegalStateException the port documents")
    void theRefusalIsAnIllegalStateException() throws Exception {
        PostingCutoff port = new LedgerPostingCutoff(new PostingCutoffs(IDS, CLOCK));
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> port.advance(connection(null, LocalDate.of(1999, 3, 31)), Optional.of(JAN), FEB,
                            "lock February 1999"))
                    .isInstanceOf(IllegalStateException.class)
                    .isInstanceOf(IllegalCutoffMoveException.class);
        }
        assertThat(IllegalStateException.class).isAssignableFrom(IllegalCutoffMoveException.class);
    }

    @Test
    @DisplayName("P14-TSK-005: the ledger's busy key crosses as the port's CutoffBusy - retryable, told apart from a"
            + " refused move, still the IllegalStateException the port documents")
    void aBusyKeyIsTheCutoffBusy() throws Exception {
        PostingCutoff port = new LedgerPostingCutoff(new PostingCutoffs(IDS, CLOCK));
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> port.advance(busy(), Optional.of(JAN), FEB, "lock February 1999"))
                    .isInstanceOf(PostingCutoff.CutoffBusy.class)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(PostingCutoffBusyException.class);
            assertThatThrownBy(() -> port.moveBack(busy(), FEB, Optional.of(JAN), "unlock February 1999"))
                    .isInstanceOf(PostingCutoff.CutoffBusy.class);
        }
    }

    /** Every attempt at the exclusive key times out (55P03); the reads answer nothing. */
    private static Connection busy() {
        return (Connection) Proxy.newProxyInstance(LedgerPostingCutoffTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        return null;
                    }
                    String sql = (String) args[0];
                    return Proxy.newProxyInstance(LedgerPostingCutoffTest.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (statement, call, values) -> {
                                if (call.getName().equals("execute") && sql.equals(PostingCutoffs.LOCK_SQL)) {
                                    throw new SQLException("canceling statement due to lock timeout", "55P03");
                                }
                                return switch (call.getName()) {
                                    case "execute" -> Boolean.TRUE;
                                    case "executeUpdate" -> 1;
                                    case "executeQuery" -> rows(new Object[] {null});
                                    default -> null;
                                };
                            });
                });
    }

    /** MOVE answers {@code revision} (or no row); READ answers {@code standing}; everything else succeeds. */
    private static Connection connection(Long revision, LocalDate standing) {
        return (Connection) Proxy.newProxyInstance(LedgerPostingCutoffTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        return null; // the savepoints around the bounded lock
                    }
                    String sql = (String) args[0];
                    return Proxy.newProxyInstance(LedgerPostingCutoffTest.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (statement, call, values) -> switch (call.getName()) {
                                case "execute" -> Boolean.TRUE;
                                case "executeUpdate" -> 1;
                                case "executeQuery" -> rows(sql.startsWith("UPDATE")
                                        ? (revision == null ? new Object[0] : new Object[] {revision})
                                        : new Object[] {standing});
                                default -> null;
                            });
                });
    }

    private static ResultSet rows(Object[] row) {
        boolean[] read = {row.length == 0};
        return (ResultSet) Proxy.newProxyInstance(LedgerPostingCutoffTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean has = !read[0];
                        read[0] = true;
                        yield has;
                    }
                    case "getLong", "getObject" -> row[0];
                    case "getString" -> row[0] == null ? null : row[0].toString();
                    default -> null;
                });
    }
}
