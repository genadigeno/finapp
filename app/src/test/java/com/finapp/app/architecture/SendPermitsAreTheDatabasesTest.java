package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.payments.DisputeResponseStore;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.RefundStore;
import com.finapp.payments.WithdrawalStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No send permit is written from an instance's clock (`X-TSK-013`, ADR-0057 section 4).
 *
 * <h2>Why a build rule beside the trigger and the skew races</h2>
 *
 * <p>payments {@code V028} and merchant {@code V009} re-stamp every forward permit write to the
 * database's instant, and the {@code ...SkewDatabaseTest} races prove the five flows judge on the
 * database's clock. Neither stops the next change from binding {@code Instant.now(clock)} back into a
 * birth permit (no trigger re-stamps an INSERT: test fixtures seed aged rows) or adding an
 * {@code Instant at} back to a renewal "for testability" - the parameter all five renewals had, and
 * the whole defect: every caller filled it from its own clock. So the shape itself is refused, read
 * from the stores' string literals only (the {@code OwnershipIsScopedTest} lesson: prose matched).
 *
 * <h2>Three rules, each with a planted violation</h2>
 *
 * <ul>
 *   <li><strong>Every assignment of a permit</strong> ({@code SET <permit> = ...}, up to its
 *       {@code WHERE}) reads {@code statement_timestamp()} and binds nothing.
 *   <li><strong>Every birth permit</strong> is {@code GREATEST(<created_at bound>,
 *       statement_timestamp())} - never older than the database's clock, never before
 *       {@code created_at} (each table's CHECK).
 *   <li><strong>No renewal port takes a clock.</strong> The pay-in's {@code expected} is the permit
 *       its caller READ - a stored value for the conditional, never a new permit - and is the one
 *       temporal parameter any renewal declares.
 * </ul>
 */
@Tag("architecture")
@DisplayName("send permits are the database's (X-TSK-013)")
class SendPermitsAreTheDatabasesTest {

    /** Each store and its permit column. */
    private static final Map<String, String> STORES =
            Map.of(
                    "com.finapp.payments.JdbcPaymentAttemptStore", "last_dispatched_at",
                    "com.finapp.payments.JdbcWithdrawalStore", "last_dispatched_at",
                    "com.finapp.payments.JdbcRefundStore", "last_dispatched_at",
                    "com.finapp.payments.JdbcDisputeResponseStore", "send_permit",
                    "com.finapp.merchant.JdbcMerchantPayoutStore", "last_dispatched_at");

    /**
     * The Phase 9 stores born under a database-stamped permit (`P9-TSK-012`, `P9-TSK-019`): their permits are stamped by
     * their own triggers at birth, so only the assignment rule applies - added by the P9-DOC-001 exit review, which found
     * "no permit anywhere is written from an instance clock" held over the five Phase 5-7 stores alone.
     */
    private static final Map<String, String> BORN_BY_THE_DATABASE =
            Map.of(
                    "com.finapp.payments.JdbcOutboundCreditStore", "last_dispatched_at",
                    "com.finapp.fx.JdbcCoverStore", "last_dispatched_at");

    private static final List<Class<?>> PORTS =
            List.of(
                    PaymentAttemptStore.class,
                    WithdrawalStore.class,
                    RefundStore.class,
                    DisputeResponseStore.class,
                    MerchantPayoutStore.class);

    private static final List<Class<?>> TEMPORAL =
            List.of(
                    Instant.class,
                    Clock.class,
                    InstantSource.class,
                    OffsetDateTime.class,
                    ZonedDateTime.class,
                    LocalDateTime.class,
                    java.util.Date.class,
                    java.sql.Timestamp.class);

    private static final Pattern BIRTH =
            Pattern.compile(
                    "GREATEST\\(CAST\\(\\? AS timestamptz\\), (CASE WHEN \\? THEN )?statement_timestamp\\(\\)");

    @Test
    @DisplayName("every permit assignment in the five stores reads statement_timestamp() and binds nothing")
    void everyPermitAssignmentIsTheDatabases() {
        for (Map.Entry<String, String> store : STORES.entrySet()) {
            String statements = statementsIn(readSourceOf(store.getKey()));
            List<String> assignments = assignmentsOf(statements, store.getValue());
            assertThat(assignments)
                    .as(store.getKey() + " renews its permit somewhere - the rule read real statements")
                    .isNotEmpty();
            assertThat(violationsIn(statements, store.getValue()))
                    .as(store.getKey() + ": a permit assigned from a bind parameter is a caller's clock"
                            + " stamping it - the defect X-TSK-013 removed")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the Phase 9 stores' permit assignments read statement_timestamp() and bind nothing too")
    void thePhaseNineStoresAssignOnlyTheDatabasesInstant() {
        for (Map.Entry<String, String> store : BORN_BY_THE_DATABASE.entrySet()) {
            String statements = statementsIn(readSourceOf(store.getKey()));
            assertThat(assignmentsOf(statements, store.getValue()))
                    .as(store.getKey() + " renews its permit somewhere - the rule read real statements").isNotEmpty();
            assertThat(violationsIn(statements, store.getValue()))
                    .as(store.getKey() + ": a permit assigned from a bind parameter is a caller's clock stamping it")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("every birth permit is GREATEST(created_at, statement_timestamp())")
    void everyBirthPermitIsNeverOlderThanTheDatabasesClock() {
        for (String store : STORES.keySet()) {
            assertThat(BIRTH.matcher(statementsIn(readSourceOf(store))).find())
                    .as(store + "'s INSERT must write its birth permit as GREATEST(<created_at>,"
                            + " statement_timestamp()): an instance running behind would otherwise"
                            + " birth a permit already older than it is")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no renewal port takes a clock; the pay-in's one instant is the permit it read")
    void noRenewalPortTakesAClock() {
        List<String> seen = new ArrayList<>();
        for (Class<?> port : PORTS) {
            for (Method method : port.getDeclaredMethods()) {
                if (method.isSynthetic() || !method.getName().startsWith("renew")) {
                    continue;
                }
                seen.add(port.getSimpleName() + "." + method.getName());
                List<Parameter> temporal = temporalParametersOf(method);
                if (port == PaymentAttemptStore.class) {
                    assertThat(temporal)
                            .as("the pay-in renewal's one instant is the permit its caller read")
                            .hasSize(1)
                            .allSatisfy(parameter -> {
                                if (parameter.isNamePresent()) {
                                    assertThat(parameter.getName()).isEqualTo("expected");
                                }
                            });
                } else {
                    assertThat(temporal)
                            .as(port.getSimpleName() + "." + method.getName() + " stamps a permit, so the"
                                    + " database's clock does it - a temporal parameter would be a caller's")
                            .isEmpty();
                }
            }
        }
        assertThat(seen).as("all five renewals were found").hasSize(5);
    }

    @Test
    @DisplayName("the rules are not vacuous: each planted violation is refused")
    void plantedViolationsAreRefused() {
        // The withdrawal's and the payout's renewals before X-TSK-013.
        assertThat(violationsIn(
                        "UPDATE payments.withdrawal SET last_dispatched_at = ? WHERE id = ?",
                        "last_dispatched_at"))
                .isNotEmpty();
        // The refund's and the dispute response's renewals before X-TSK-013.
        assertThat(violationsIn(
                        "UPDATE payments.refund SET last_dispatched_at = GREATEST( last_dispatched_at +"
                                + " interval '1 microsecond', CAST(? AS timestamptz)) WHERE id = ?",
                        "last_dispatched_at"))
                .isNotEmpty();
        // A permit assigned beside a status move, from a bind.
        assertThat(violationsIn(
                        "UPDATE payments.withdrawal SET status = ?, last_dispatched_at = ? WHERE id = ?",
                        "last_dispatched_at"))
                .isNotEmpty();
        // The accepted shape.
        assertThat(violationsIn(
                        "UPDATE payments.refund SET last_dispatched_at = GREATEST(last_dispatched_at +"
                                + " interval '1 microsecond', statement_timestamp()) WHERE id = ?",
                        "last_dispatched_at"))
                .isEmpty();
        // A birth permit bound straight from the instance.
        assertThat(BIRTH.matcher("INSERT INTO payments.refund (a, last_dispatched_at) VALUES (?, ?)").find())
                .isFalse();
        assertThat(BIRTH.matcher("VALUES (?, GREATEST(CAST(? AS timestamptz), statement_timestamp()))").find())
                .isTrue();
    }

    // -----------------------------------------------------------------

    /** Each assignment of the permit - first or later in a SET list - up to the next assignment or WHERE. */
    private static List<String> assignmentsOf(String statements, String column) {
        List<String> found = new ArrayList<>();
        Matcher assignment =
                Pattern.compile("(?:SET |, )" + Pattern.quote(column) + " ?= ?(.*?)(?= WHERE |, [a-z_]+ ?= |$)")
                        .matcher(statements);
        while (assignment.find()) {
            found.add(assignment.group(1));
        }
        return found;
    }

    private static List<String> violationsIn(String statements, String column) {
        return assignmentsOf(statements, column).stream()
                .filter(value -> value.contains("?") || !value.contains("statement_timestamp()"))
                .toList();
    }

    private static List<Parameter> temporalParametersOf(Method method) {
        return Arrays.stream(method.getParameters())
                .filter(parameter ->
                        TEMPORAL.stream().anyMatch(temporal -> temporal.isAssignableFrom(parameter.getType())))
                .toList();
    }

    /**
     * The source's string literals, their contents joined and whitespace collapsed - each SQL
     * statement as the driver receives it, never the prose around it.
     */
    private static String statementsIn(String source) {
        StringBuilder joined = new StringBuilder();
        Matcher quoted = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"").matcher(source);
        while (quoted.find()) {
            joined.append(quoted.group(1));
        }
        return joined.toString().replaceAll("\\s+", " ");
    }

    private static String readSourceOf(String type) {
        String module = type.substring("com.finapp.".length());
        module = module.substring(0, module.indexOf('.'));
        Path path =
                repositoryRoot()
                        .resolve(module)
                        .resolve("src/main/java")
                        .resolve(type.replace('.', '/') + ".java");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the source of " + type, e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
