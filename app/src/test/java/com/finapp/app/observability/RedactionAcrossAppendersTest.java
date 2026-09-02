package com.finapp.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.security.Sensitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Redaction holds on <strong>every</strong> appender, not merely the one a test happened to watch.
 *
 * <h2>Why a second appender is worth the trouble</h2>
 *
 * <p>{@code P0-TST-008} asks for exactly this — "across all appenders" — and the reason is that
 * redaction implemented at the wrong layer passes a one-appender test and fails in production. A
 * masking encoder, a pattern-based scrubber, or a console-only filter all look identical to a test
 * that reads one stream, and all three leak the moment a deployment adds a file or a syslog target.
 *
 * <p>This platform redacts at the <em>value</em>, so appender count should be irrelevant. "Should
 * be" is what {@code P0-TST-007} punctured when a privilege invariant turned out to hold for table
 * grants and not for column grants, so it is asserted rather than reasoned about.
 *
 * <p>Two appenders is enough to distinguish value-level redaction from encoder-level redaction; a
 * third would prove nothing further.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
            // Adds a file appender alongside the console one Boot always configures.
            "logging.file.name=build/test-logs/redaction-appenders.log",
            "logging.structured.format.file=ecs"
        })
@ExtendWith(OutputCaptureExtension.class)
class RedactionAcrossAppendersTest {

    private static final Logger log = LoggerFactory.getLogger(RedactionAcrossAppendersTest.class);

    private static final String CREDENTIAL = "APPENDER-SENTINEL-must-never-be-written-anywhere";

    private record Credentials(String username, Sensitive<String> password) {}

    /**
     * Inside {@code build/}, not the system temp directory.
     *
     * <p>Logback appends, so a file outside the build tree grows on every run for ever - and the
     * negative control below deliberately writes an <strong>unredacted</strong> sentinel to it.
     * A test that leaves plaintext it was written to prove leaks in a directory nothing cleans is
     * the wrong shape for a test about not writing secrets to files. `clean` removes this one.
     */
    private static final Path LOG_FILE = Path.of("build", "test-logs", "redaction-appenders.log");

    @Test
    @DisplayName("the credential reaches neither the console nor the file")
    void redactionHoldsOnBothAppenders(CapturedOutput output) throws IOException {
        log.info("authenticating {}", new Credentials("ada", Sensitive.of(CREDENTIAL)));

        String console = output.getAll();
        String file = Files.readString(LOG_FILE, StandardCharsets.UTF_8);

        // The precondition. Without it a misconfigured file appender - wrong path, wrong level,
        // never created - would make the "does not contain" assertion below pass while reading an
        // empty string, which is the vacuity this suite exists to avoid.
        assertThat(file)
                .as("the file appender must actually have received the line")
                .contains("authenticating");
        assertThat(console).as("and so must the console").contains("authenticating");

        assertThat(console).as("console").doesNotContain(CREDENTIAL);
        assertThat(file).as("file").doesNotContain(CREDENTIAL);
        assertThat(file).as("the file must show the value was withheld").contains(Sensitive.MASK);
    }

    @Test
    @DisplayName("the file appender would show a leak, so its silence means something")
    void theFileAppenderCanSeeALeak(CapturedOutput output) throws IOException {
        // The negative control, on the appender that was added for this test. An assertion that a
        // string is absent from a file proves nothing until something proves the same file records
        // a string that IS present.
        String unwrapped = "APPENDER-CONTROL-" + System.nanoTime();

        log.info("authenticating {}", unwrapped);

        assertThat(Files.readString(LOG_FILE, StandardCharsets.UTF_8))
                .as("an unwrapped value does reach the file, which is why the absence above counts")
                .contains(unwrapped);
    }
}
