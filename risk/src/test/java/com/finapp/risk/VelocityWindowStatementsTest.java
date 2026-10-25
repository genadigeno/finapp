package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The velocity windows' statements and shapes, pinned (`P13-TSK-006`; {@code INV-RSK-03}, {@code INV-CON-03}) - the
 * hermetic half of what the owed {@code VelocityWindowDatabaseTest} proves live: the count is ONE statement whose
 * arithmetic is the database's (no figure read earlier is written back - the read-then-write {@code INV-CON-03} calls a
 * limit that does not exist), R2a is namespace 131 per (dimension, subject), the cut is {@code clock_timestamp()}, the
 * reads lock nothing, and no velocity type holds a clock an instance could cut a window with.
 */
@DisplayName("the velocity statements are one upsert, one lock and the database's clock (P13-TSK-006)")
class VelocityWindowStatementsTest {

    @Test
    @DisplayName("the count is one INSERT ... ON CONFLICT DO UPDATE ... RETURNING, its arithmetic the database's and its"
            + " sum never added across currencies")
    void theCountIsOneStatement() {
        assertThat(JdbcVelocityWindowStore.UPSERT_SQL)
                .startsWith("INSERT INTO risk.velocity_window AS w")
                .contains(" VALUES (?, ?, ?, ?, 1, ?, ?, ?)")
                .contains(" ON CONFLICT (dimension, subject_ref, window_kind, window_start) DO UPDATE")
                .contains(" SET count = w.count + 1, sum_minor = w.sum_minor + EXCLUDED.sum_minor")
                .contains(" WHERE w.currency = EXCLUDED.currency AND w.scale = EXCLUDED.scale")
                .contains(" RETURNING ")
                .doesNotContain(";")
                .doesNotContainIgnoringCase("SELECT");
        assertThat(JdbcVelocityWindowStore.UPSERT_SQL)
                .as("the trigger stamps the count's instant; the statement passes none")
                .doesNotContain("last_counted_at,");
    }

    @Test
    @DisplayName("R2a is namespace 131, blocking and transaction-scoped, keyed per (dimension, subject); the cut is"
            + " clock_timestamp(); the reads take no lock")
    void theLockTheClockAndTheReads() {
        assertThat(JdbcVelocityWindowStore.NAMESPACE).isEqualTo(131)
                .isNotEqualTo(JdbcRiskPolicyStore.NAMESPACE);
        assertThat(JdbcVelocityWindowStore.SERIALISE_SQL).isEqualTo("SELECT pg_advisory_xact_lock(131, ?)");
        assertThat(JdbcVelocityWindowStore.NOW_SQL).isEqualTo("SELECT clock_timestamp()");
        for (String read : List.of(JdbcVelocityWindowStore.FIND_SQL, JdbcVelocityWindowStore.LIVE_SQL,
                JdbcVelocityWindowStore.FIRST_COUNTED_SQL)) {
            assertThat(read).startsWith("SELECT ").doesNotContain("FOR UPDATE").doesNotContain("FOR SHARE")
                    .doesNotContain("now()");
        }
        assertThat(JdbcVelocityWindowStore.LIVE_SQL).contains("> clock_timestamp()");
        // P13-TSK-013, a subject's first sighting: the primary key's prefix, the finest window - one index probe.
        assertThat(JdbcVelocityWindowStore.FIRST_COUNTED_SQL).isEqualTo("SELECT min(w.window_start) FROM"
                + " risk.velocity_window w WHERE w.dimension = ? AND w.subject_ref = ? AND w.window_kind = 'H1'");
    }

    @Test
    @DisplayName("no velocity type holds a clock: the only instant a window is cut at is the one the store read from the"
            + " database, and a WindowCut cannot be built outside this package")
    void noVelocityTypeHoldsAClock() throws NoSuchMethodException {
        for (Class<?> type : List.of(VelocityWindows.class, WindowStarts.class, WindowCut.class, VelocityProof.class,
                JdbcVelocityWindowStore.class, JdbcVelocityEvidence.class)) {
            for (Field field : type.getDeclaredFields()) {
                assertThat(field.getType()).as(type.getSimpleName() + "." + field.getName())
                        .isNotEqualTo(Clock.class).isNotEqualTo(InstantSource.class);
            }
            assertThat(Arrays.stream(type.getDeclaredConstructors()).flatMap(c -> Arrays.stream(c.getParameterTypes())))
                    .as(type.getSimpleName() + "'s constructors")
                    .doesNotContain(Clock.class, InstantSource.class);
        }
        assertThat(Arrays.stream(WindowCut.class.getConstructors())).as("no public constructor").isEmpty();
        int modifiers = WindowCut.class.getDeclaredConstructor(VelocityMovement.class, java.time.Instant.class)
                .getModifiers();
        assertThat(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)).isFalse();
    }
}
