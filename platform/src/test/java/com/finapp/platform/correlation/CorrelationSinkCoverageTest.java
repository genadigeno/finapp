package com.finapp.platform.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Stops {@link CorrelationPropagationTest} from quietly falling behind the platform.
 *
 * <p><strong>The problem this exists for.</strong> P0-TST-003 requires correlation to reach four
 * sinks — log, trace, outbox row, audit record. Two exist; the other three arrive with
 * P0-EPIC-06, -07 and -09. A test written against today's two would keep passing when the
 * outbox lands, because nothing about adding an outbox makes an existing test fail. The
 * criterion would then be satisfied on paper and not in fact, and nobody would find out until
 * an incident where a payment could not be traced.
 *
 * <p><strong>How it stops that.</strong> The set of concern packages under
 * {@code com.finapp.platform} is derived from the build output and compared against the set
 * below. A new concern — an outbox, an audit store, a telemetry exporter — fails this test with
 * a message asking the one question that matters: is it a correlation sink? Extending
 * {@link CorrelationPropagationTest}, or recording that the new concern is not a sink, is then a
 * deliberate edit rather than something remembered.
 *
 * <p>This is the same reasoning as the architecture rules' coverage guards and the
 * documentation-equivalence check: an expectation derived from the codebase cannot rot, and one
 * maintained by hand always does.
 */
@Tag("architecture")
class CorrelationSinkCoverageTest {

    /**
     * Every platform concern, and whether correlation must reach it.
     *
     * <p>Adding an entry is the deliberate act. The comment against each is the decision.
     */
    private static final Set<String> KNOWN_CONCERNS =
            Set.of(
                    // A correlation sink, asserted by CorrelationPropagationTest: the
                    // idempotency record carries correlation_id NOT NULL.
                    "idempotency",
                    // The mechanism itself.
                    "correlation",
                    // A correlation sink, asserted by CorrelationPropagationTest: every outbox
                    // row carries correlation_id NOT NULL. This is the "emitted event" sink
                    // P0-TSK-014's criterion named and could not verify at the time; the guard
                    // is what made it impossible for the outbox to land without it.
                    "outbox",
                    // A correlation sink, asserted by CorrelationPropagationTest: every inbox
                    // row carries correlation_id NOT NULL. Without it a consumer's effect
                    // cannot be joined to the flow that produced the message it handled, which
                    // is the only question anyone has when tracing a duplicate.
                    "inbox",
                    // A correlation sink, asserted by CorrelationPropagationTest: every audit
                    // record carries correlation_id NOT NULL. This is one of the four sinks
                    // P0-TST-003's criterion named explicitly, and the guard is what stopped the
                    // audit store arriving without the assertion.
                    "audit",
                    // A correlation sink, and the only one a customer ever sees: every
                    // problem-detail response carries the flow's identifier so a person
                    // reporting an error can quote something support can find. Asserted by
                    // ProblemDetailTest hermetically and by ApiErrorHandlerTest over a real
                    // HTTP response - not in CorrelationPropagationTest, because the sink is a
                    // response rather than a row and that class is about what reaches storage.
                    "api",
                    // A correlation sink, and the one P0-TSK-014's criterion named first and
                    // could not verify: there was no tracing. Every span the SDK starts is
                    // stamped with the flow's correlation identifier by a span processor in the
                    // composition root, asserted by TracingTest hermetically and by
                    // TraceAcrossDatabaseTest over a real request that reaches PostgreSQL.
                    //
                    // Stamped once, centrally, because "every span" is not a property any
                    // per-component discipline delivers - and the trace id is not a substitute,
                    // since it is subject to sampling and a sampled-out trace leaves the customer
                    // holding an identifier that matches nothing.
                    "telemetry",
                    // NOT a sink, and emphatically so - the one concern where correlation must be
                    // kept out rather than carried in. A metric tag whose value a request can
                    // influence multiplies one time series into as many as there are requests,
                    // until the metrics backend falls over and takes the ability to observe the
                    // incident with it; and it is a disclosure into a system with different
                    // access control and months of retention (INV-AUD-02). A metric answers how
                    // many, how long and how often - never which one. Correlation belongs on a
                    // trace and in a log, both of which are per-event and searchable.
                    // MetricConventionTest enforces this against the live registry.
                    "metrics",
                    // Not a sink. MoneyColumns is a persistence convention with no flow of its
                    // own; correlation reaches the tables that use it, not the convention.
                    "money",
                    // NOT a sink, and it exists to keep something OUT rather than to carry
                    // something in (`P1-TSK-008`). DatabaseFailure turns a driver exception into a
                    // message safe to log, because PostgreSQL puts the entire failing row in a
                    // constraint violation's DETAIL - so an exception carrying a SQLException
                    // carries a password, a person's name or a login identifier into every log line
                    // that prints it (INV-AUD-02).
                    //
                    // Correlation reaches the log line itself, through the MDC, which is asserted
                    // under "correlation". This concern shapes what the line SAYS and never carries
                    // an identifier of its own; giving it one would imply it knew about a flow,
                    // which it deliberately does not - it is handed an exception and a sentence.
                    "persistence",
                    // NOT a sink, and the distinction is the point rather than a technicality.
                    // SecurityContext answers WHO is acting; correlation answers WHICH FLOW this
                    // is. They travel together and are established at the same entry points, which
                    // is exactly why it is worth saying that they are not the same thing: an actor
                    // identifies a party and is a durable attribution, a correlation identifier
                    // identifies one execution and attributes nothing to anybody. Merging them
                    // would put a customer identifier into every log line and every trace - a
                    // disclosure into systems with different access control (INV-AUD-02) - and
                    // would make the audit trail's actor a function of tracing configuration.
                    //
                    // Correlation reaches the audit RECORD, which is where the two meet, and that
                    // is asserted under "audit" above.
                    "security");

    @Test
    @DisplayName("no platform concern exists without a decision about whether correlation reaches it")
    void everyConcernHasBeenConsideredAsASink() {
        Set<String> present = concernPackages();

        assertThat(present)
                .as(
                        """
                        A platform concern exists that CorrelationSinkCoverageTest has not been told \
                        about. P0-TST-003 requires correlation to reach the log, traces, outbox rows \
                        and audit records. Decide which this is:
                          - a correlation sink -> assert it in CorrelationPropagationTest, then list \
                        it here;
                          - not a sink -> list it here with the reason.
                        Leaving it unlisted is the one option that lets the criterion be satisfied on \
                        paper and not in fact.""")
                .isEqualTo(KNOWN_CONCERNS);
    }

    @Test
    @DisplayName("the guard is not vacuous: it can see the packages it claims to check")
    void theGuardSeesRealPackages() {
        // Without this, a broken path would make the comparison run over an empty set and the
        // guard would pass while checking nothing - the failure mode that makes a coverage
        // guard worse than none.
        assertThat(concernPackages()).contains("correlation", "idempotency");
    }

    /** Top-level concern packages under {@code com.finapp.platform} in the main build output. */
    private static Set<String> concernPackages() {
        Path platformRoot = mainClassesDirectory().resolve("com/finapp/platform");
        try (Stream<Path> entries = Files.list(platformRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list platform concerns in " + platformRoot, e);
        }
    }

    /** The main output beside this test's own output directory. */
    private static Path mainClassesDirectory() {
        try {
            Path testClasses =
                    Path.of(
                            CorrelationSinkCoverageTest.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            // .../build/classes/java/test -> .../build/classes/java/main
            return testClasses.resolveSibling("main");
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate the platform build output", e);
        }
    }
}
