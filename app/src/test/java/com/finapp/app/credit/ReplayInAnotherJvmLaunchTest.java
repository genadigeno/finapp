package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credit replay's second JVM starts whatever the classpath's length ({@code P14-DOC-001}, routed by {@code P14-TST-002}'s
 * merge guard): {@code DecisionReproducibilityBatteryTest} and {@code CreditDecisionStormDatabaseTest} passed {@code -cp} on
 * the command line, and {@code app}'s test classpath - some 32,300 characters from a checkout root of 75 - is past Windows'
 * 32,767 from a deeper checkout ({@code CreateProcess error=206}), so both would fail before their first case.
 * {@link ReplayInAnotherJvm#launch} now hands the child the database's coordinates, its options and the classpath in one
 * argument file. This holds it so with a real child - no database needed - on a classpath far past the limit, its entries and
 * a coordinate carrying what the file's quoting must keep: spaces, {@code #}, an apostrophe, a double quote, and backslashes
 * before {@code n} and {@code t}, which an unescaped file would read as a newline and a tab.
 */
@DisplayName("P14-DOC-001 - the credit replay's second JVM starts through an argument file, whatever the classpath's length")
class ReplayInAnotherJvmLaunchTest {

    /** Windows' limit on a command line, in characters. */
    private static final int WINDOWS_COMMAND_LINE = 32_767;

    /** A coordinate the quoting must carry intact: a space, a {@code #}, a quote and backslashes before n and t. */
    private static final String SECRET = "s3cr#t \"pass\" C:\\new\\tab";

    @Test
    @DisplayName("a child on a classpath of over 100,000 characters starts under the replay's defaults, reads that classpath"
            + " and a coordinate exactly; its command names neither, and is as short whatever the classpath")
    void aClasspathPastTheLimitStillStarts() throws IOException, InterruptedException {
        String own = System.getProperty("java.class.path");
        StringBuilder padded = new StringBuilder(own);
        // Entries that exist nowhere, after the real ones: the child skips them, and reads them back in its classpath.
        String missing = Path.of(System.getProperty("java.io.tmpdir"), "replay probe #") + File.separator + "dir\\new\\tab's ";
        for (int entry = 0; padded.length() <= 100_000; entry++) {
            padded.append(File.pathSeparator).append(missing).append(entry).append(File.separator).append("x.jar");
        }
        String classpath = padded.toString();
        assertThat(classpath.length()).as("past Windows' limit three times over").isGreaterThan(3 * WINDOWS_COMMAND_LINE);

        Path out = Files.createTempFile("replay-launch", ".txt");
        Path log = Files.createTempFile("replay-launch", ".log");
        Path options = Files.createTempFile("replay-launch", ".args");
        try {
            Process child = ReplayInAnotherJvm.launch(options, List.of("-Dfinapp.db.app.password=" + SECRET), classpath,
                    Echo.class, out, log);
            List<String> command = ReplayInAnotherJvm.command(options, Echo.class, out);
            assertThat(command).as("the argument file, by @").contains("@" + options)
                    .as("and no classpath option").doesNotContain("-cp", "-classpath", "--class-path")
                    .as("nor the classpath, nor the coordinate").noneMatch(argument -> argument.contains(own)
                            || argument.contains("s3cr#t"));
            assertThat(String.join(" ", command).length()).as("the command's length, the classpath's whatever")
                    .isLessThan(1_000);
            assertThat(child.waitFor(2, TimeUnit.MINUTES)).as("the child finished").isTrue();
            assertThat(child.exitValue()).as(Files.readString(log, StandardCharsets.UTF_8)).isZero();
            assertThat(Files.readAllLines(out, StandardCharsets.UTF_8)).as("its defaults, its classpath, its coordinate")
                    .containsExactly(ReplayInAnotherJvm.CHILD_DEFAULTS, classpath, SECRET);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            Files.deleteIfExists(options);
        }
    }

    @Test
    @DisplayName("the replay's own command names no classpath and no coordinate; the file quotes and escapes each option")
    void theReplaysLaunchCarriesNoClasspath() {
        List<String> command = ReplayInAnotherJvm.command(Path.of("options.args"), ReplayInAnotherJvm.class, Path.of("out.txt"));
        assertThat(command).containsSubsequence("@options.args", ReplayInAnotherJvm.class.getName(), "out.txt")
                .doesNotContain("-cp", "-classpath", "--class-path").hasSize(4);
        assertThat(ReplayInAnotherJvm.argumentFile(List.of("-Dfinapp.db.url=jdbc:x"), "C:\\Program Files\\new\\x.jar"))
                .startsWith("\"-Dfinapp.db.url=jdbc:x\"\n")
                .contains("\"-Duser.timezone=America/Adak\"\n", "\"-cp\"\n\"C:\\\\Program Files\\\\new\\\\x.jar\"\n");
    }

    /** The child: writes its defaults, the classpath it runs on and the coordinate it was handed. */
    static final class Echo {

        private Echo() {}

        public static void main(String[] arguments) throws IOException {
            Files.write(Path.of(arguments[0]), List.of(ReplayInAnotherJvm.defaults(), System.getProperty("java.class.path"),
                    System.getProperty("finapp.db.app.password")), StandardCharsets.UTF_8);
        }
    }
}
