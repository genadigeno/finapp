package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.platform.retention.RetentionSweep;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The session sweep as the application builds it, with no database (`P16-TSK-010`). */
@DisplayName("the session sweep: identity's own function, its grace in milliseconds, refused under a minute (P16-TSK-010)")
class SessionRetentionTest {

    @Test
    @DisplayName("a batch calls identity.sweep_expired_sessions with the grace in milliseconds and the batch size, and commits")
    void aBatchCallsIdentitysFunction() {
        List<Object> seen = new ArrayList<>();
        RetentionSweep sweep = SessionRetention.sweep(() -> connection(seen), Duration.ofDays(30), 500);

        assertThat(sweep.sweepBatch()).isEqualTo(3);
        assertThat(seen).containsExactly("SELECT identity.sweep_expired_sessions(?, ?)", Duration.ofDays(30).toMillis(), 500, "commit", "close");
        assertThat(sweep.table()).isEqualTo("session");
        assertThat(sweep.bound()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    @DisplayName("a grace under a minute is refused at start, as the function refuses it; a minute is accepted")
    void aGraceUnderAMinuteIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SessionRetention.sweep(() -> connection(new ArrayList<>()), Duration.ofSeconds(59), 500))
                .withMessageContaining("at least");
        assertThat(SessionRetention.sweep(() -> connection(new ArrayList<>()), SessionRetention.MIN_GRACE, 500).bound())
                .isEqualTo(Duration.ofMinutes(1));
    }

    private static Connection connection(List<Object> seen) {
        return proxy(Connection.class, (self, method, arguments) -> switch (method.getName()) {
            case "setAutoCommit" -> null;
            case "prepareStatement" -> {
                seen.add(arguments[0]);
                yield proxy(PreparedStatement.class, (s, m, a) -> switch (m.getName()) {
                    case "setLong", "setInt" -> {
                        seen.add(a[1]);
                        yield null;
                    }
                    case "executeQuery" -> proxy(ResultSet.class, (r, rm, ra) -> switch (rm.getName()) {
                        case "next" -> true;
                        case "getInt" -> 3;
                        case "close" -> null;
                        default -> throw new AssertionError(rm.getName());
                    });
                    case "close" -> null;
                    default -> throw new AssertionError(m.getName());
                });
            }
            case "commit", "close" -> {
                seen.add(method.getName());
                yield null;
            }
            default -> throw new AssertionError(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
