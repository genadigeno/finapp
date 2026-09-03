package com.finapp.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.security.Sensitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A credential placed into a logged object does not reach the log ({@code INV-AUD-02}).
 *
 * <h2>Captured output, not a list appender</h2>
 *
 * <p>This reads what was actually <strong>written</strong> — through the ECS encoder, past every
 * layer that could serialise a value. A Logback list appender holds the event before encoding, so
 * a redaction defect introduced by the encoder, by a serialiser's fallback, or by structured
 * logging lifting an argument into a field would be invisible to it. The thing that matters is the
 * bytes that leave the process, because those are what the log shipper takes.
 *
 * <h2>Why the object is logged the lazy way</h2>
 *
 * <p>{@code log.info("...{}", credentials)} is how the leak happens in real code: no getter call,
 * no concatenation, nothing a reviewer stops at. The test does exactly that rather than something
 * more careful, because something more careful would prove a property nobody violates.
 */
@Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionTest {

    private static final Logger log = LoggerFactory.getLogger(LogRedactionTest.class);

    @Autowired private ObjectMapper mapper;

    /** Distinctive enough that finding it anywhere in the output is unambiguous. */
    private static final String CREDENTIAL = "s3cr3t-PLAINTEXT-CREDENTIAL-must-never-appear";

    /** What a leak actually looks like: a record, logged whole. */
    private record Credentials(String username, Sensitive<String> password) {}

    /** The same shape without the wrapper, to prove the test can see a leak at all. */
    private record LeakyCredentials(String username, String password) {}

    @Test
    @DisplayName("a credential inside a logged object does not appear in the output")
    void aWrappedCredentialIsNotLogged(CapturedOutput output) {
        Credentials credentials = new Credentials("ada", Sensitive.of(CREDENTIAL));

        log.info("authenticating {}", credentials);

        assertThat(emitted(output))
                .as("the line must have been written, or this test proves nothing")
                .contains("authenticating");
        assertThat(emitted(output))
                .as("the credential must not appear anywhere in the emitted output")
                .doesNotContain(CREDENTIAL);
        assertThat(emitted(output))
                .as("and the reader should be able to tell a withheld value from an absent one")
                .contains(Sensitive.MASK);
    }

    @Test
    @DisplayName("the test can see a leak: the unwrapped form does appear")
    void theTestWouldNoticeALeak(CapturedOutput output) {
        // The negative control, and it is not optional. Without it, a capture that silently caught
        // nothing - a changed appender, a swallowed line, a filter set to WARN - would make every
        // "does not contain" assertion above pass while checking nothing at all. This is the same
        // failure the correlation tests guard against with an unwrapped handoff.
        log.info("authenticating {}", new LeakyCredentials("ada", CREDENTIAL));

        assertThat(emitted(output))
                .as("an unwrapped credential DOES leak, which is exactly why the wrapper exists")
                .contains(CREDENTIAL);
    }

    @Test
    @DisplayName("the redaction survives interpolation, concatenation and toString")
    void everyRenderingPathIsMasked(CapturedOutput output) {
        // Three paths a value can take to a log line. The wrapper has to close all of them,
        // because the one it leaves open is the one somebody uses.
        Sensitive<String> secret = Sensitive.of(CREDENTIAL);

        log.info("placeholder {}", secret);
        log.info("concatenated " + secret);
        log.info("explicit {}", secret.toString());

        assertThat(emitted(output)).doesNotContain(CREDENTIAL);
        assertThat(emitted(output).lines().filter(line -> line.contains(Sensitive.MASK)).count())
                .as("all three lines must be masked, not merely one of them")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a log line is JSON, and carries the correlation identifier as a field")
    void everyLineIsStructuredAndCorrelated(CapturedOutput output) {
        CorrelationId correlationId = CorrelationId.of("redaction-test-correlation");

        CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(correlationId));
        log.info("a correlated line");
        scope.close();

        String line =
                emitted(output)
                        .lines()
                        .filter(candidate -> candidate.contains("a correlated line"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the line was not emitted"));

        // Parsed, not pattern-matched. A log aggregator parses; a test that only looked for a
        // substring would pass on output no aggregator could read.
        JsonNode event = JsonMapper.builder().build().readTree(line);
        assertThat(event.path("message").stringValue()).isEqualTo("a correlated line");
        assertThat(event.path(CorrelationContext.CORRELATION_ID_KEY).stringValue())
                .as("correlation must be a queryable field, not text inside the message")
                .isEqualTo(correlationId.value());
    }

    @Test
    @DisplayName("a sensitive value renders as the mask in JSON, and not by accident")
    void serialisationMasksRatherThanHappeningNotToLeak() {
        // INV-AUD-02 covers API responses as firmly as logs, and this half was missing until the
        // review probed it. Jackson already declined to reveal the value - it found no properties
        // and emitted `{}` - but that is safety by accident, and this codebase has been bitten by
        // exactly that shape before: ProblemDetail serialised directly produced
        // "correlationId":{}, an empty object where a value was expected, with nothing failing.
        //
        // WithAGetter is the point of the test. It has an accessible property, so the accidental
        // protection does not apply to it; only the registered serialiser does.
        String json = mapper.writeValueAsString(new WithAGetter(Sensitive.of(CREDENTIAL)));

        assertThat(json).doesNotContain(CREDENTIAL);
        assertThat(json)
                .as("a withheld value must be distinguishable from an absent one")
                .contains(Sensitive.MASK);
    }

    /** A wrapper with an accessible property, so accidental non-serialisation cannot save it. */
    private record WithAGetter(Sensitive<String> apiToken) {}

    // -----------------------------------------------------------------

    private static String emitted(CapturedOutput output) {
        return output.getAll();
    }
}
