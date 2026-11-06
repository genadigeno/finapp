package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The battery's second JVM starts whatever the classpath's length (`P14-TST-002`, the coordinator's merge guard of 2026-11-06):
 * on a checkout deeper than the one it was built in, {@code -cp} on the command line crossed Windows' 32,767 characters and
 * {@code AccountingBatteryHermeticTest} never started ({@code CreateProcess error=206}). {@link AccountingBatteryInAnotherJvm}
 * now hands the child its options and classpath in an argument file; this holds it so with a real child on a classpath far past
 * the limit, its entries carrying what the file's quoting must keep - spaces, {@code #}, an apostrophe, and backslashes
 * before {@code n} and {@code t}, which an unescaped file would read as a newline and a tab.
 */
@DisplayName("P14-TST-002 - the battery's second JVM starts through an argument file, whatever the classpath's length")
class AccountingBatteryInAnotherJvmTest {

    /** Windows' limit on a command line, in characters. */
    private static final int WINDOWS_COMMAND_LINE = 32_767;

    @Test
    @DisplayName("a child on a classpath of over 100,000 characters, its entries holding spaces, #, an apostrophe and backslashes"
            + " before n and t, starts under the battery's defaults and reads that classpath exactly; its command holds the"
            + " argument file and no classpath, as long as under this JVM's own; every file it used is gone after")
    void aClasspathPastTheLimitStillStarts() throws IOException, InterruptedException {
        String own = System.getProperty("java.class.path");
        System.out.println("P14-TST-002 CLASSPATH: length=" + own.length() + " entries=" + own.split(File.pathSeparator).length);
        StringBuilder padded = new StringBuilder(own);
        // Entries that exist nowhere, after the real ones: the child skips them, and reads them back in its classpath.
        String missing = Path.of(System.getProperty("java.io.tmpdir"), "battery probe #") + File.separator + "dir\\new\\tab's ";
        for (int entry = 0; padded.length() <= 100_000; entry++) {
            padded.append(File.pathSeparator).append(missing).append(entry).append(File.separator).append("x.jar");
        }
        String classpath = padded.toString();
        assertThat(classpath.length()).as("past Windows' limit three times over").isGreaterThan(3 * WINDOWS_COMMAND_LINE);
        assertThat(classpath).contains(" ", "#", "'", "\\new\\tab", File.separator + "x.jar");

        AccountingBatteryInAnotherJvm.Started child = AccountingBatteryInAnotherJvm.launch(classpath, Echo.class, List.of());
        try {
            assertThat(child.command()).as("the argument file, by @").contains("@" + child.options())
                    .as("and no classpath option").doesNotContain("-cp", "-classpath", "--class-path")
                    .as("nor the classpath itself").noneMatch(argument -> argument.contains(own));
            assertThat(child.command()).isEqualTo(AccountingBatteryInAnotherJvm.command(child.options(), Echo.class, child.out(),
                    List.of()));
            assertThat(String.join(" ", child.command()).length()).as("the command's length, the classpath's whatever")
                    .isLessThan(1_000);
            assertThat(Files.readString(child.options(), StandardCharsets.UTF_8)).as("the file holds the options")
                    .contains("\"-Dfile.encoding=UTF-16\"", "\"-cp\"");
        } catch (RuntimeException | Error failed) {
            child.abandon();
            throw failed;
        }
        // join holds the child's first line to CHILD_DEFAULTS - its options reached it through the file - and returns the rest.
        List<String> lines = child.join(Duration.ofMinutes(2));
        assertThat(lines).as("the classpath the child was given, exactly").containsExactly(classpath);
        assertThat(child.options()).as("the argument file deleted").doesNotExist();
        assertThat(child.out()).doesNotExist();
        assertThat(child.log()).doesNotExist();
    }

    @Test
    @DisplayName("the battery's own launch names no classpath on its command line either")
    void theBatteryLaunchCarriesNoClasspath() {
        List<String> command = AccountingBatteryInAnotherJvm.command(Path.of("options.args"), AccountingBatteryInAnotherJvm.class,
                Path.of("out.txt"), List.of("20261106", "10000"));
        assertThat(command).containsSubsequence("@options.args", AccountingBatteryInAnotherJvm.class.getName(), "out.txt",
                "20261106", "10000").doesNotContain("-cp", "-classpath", "--class-path");
        assertThat(AccountingBatteryInAnotherJvm.argumentFile("C:\\Program Files\\new\\x.jar"))
                .contains("\"-cp\"\n\"C:\\\\Program Files\\\\new\\\\x.jar\"\n");
    }

    /** The child: writes its defaults and the classpath it runs on. */
    static final class Echo {

        private Echo() {}

        public static void main(String[] arguments) throws IOException {
            Files.write(Path.of(arguments[0]), List.of(AccountingBatteryInAnotherJvm.defaults(), System.getProperty(
                    "java.class.path")), StandardCharsets.UTF_8);
        }
    }
}
