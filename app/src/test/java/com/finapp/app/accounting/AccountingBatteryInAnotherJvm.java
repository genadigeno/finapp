package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * The accounting battery's second JVM (`P14-TST-002`; the backlog's "regeneration in two JVMs byte-identical"): the same seeded
 * journal generated, posted through the real posting service and reported by the production folds in a process with another
 * default charset, locale and time zone and other identity hash codes - the journal's fingerprint, every report's content hash
 * (thirty-six: three reports in twelve months) and the digest of every fact. {@code AccountingBatteryHermeticTest} computes the
 * same lines in its own JVM and requires them equal, every line.
 *
 * <p><strong>The launcher is here, in {@code app}'s tests</strong>, and is creditmath's {@code SecondJvm} in shape - the same
 * defaults, {@link #CHILD_DEFAULTS}, the first line the child writes - but not a dependency on it: that harness is a published
 * test fixture, and a fixture on {@code app}'s test classpath is swept by {@code NoAmbientTimeRulesTest} (its {@code
 * DoNotIncludeTests} option excludes test classes, not fixtures), which a launcher must break to read the process's defaults.
 * The gate found it so.
 */
final class AccountingBatteryInAnotherJvm {

    private AccountingBatteryInAnotherJvm() {}

    /** The child JVM's defaults, deliberately unlike any build machine's - creditmath's {@code SecondJvm}'s. */
    static final String CHILD_DEFAULTS = "charset=UTF-16 locale=ar-EG zone=America/Adak";

    /** {@code arguments}: the output file, the seed, the entries. */
    public static void main(String[] arguments) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(defaults());
        lines.addAll(lines(Long.parseLong(arguments[1]), Integer.parseInt(arguments[2])));
        Files.write(Path.of(arguments[0]), lines, StandardCharsets.UTF_8);
    }

    /** The lines both JVMs write for {@code (seed, entries)}. */
    static List<String> lines(long seed, int entries) {
        List<Entry> journal = AccountingBatteryWorld.generate(seed, entries);
        AccountingBatteryBooks.Books books = AccountingBatteryBooks.books(AccountingBatteryBooks.post(journal, seed), Chart.BASE,
                Optional.empty());
        return AccountingBatteryBooks.lines(AccountingBatteryWorld.fingerprint(journal),
                AccountingBatteryBooks.report(books, month -> AccountingBatteryWorld.rates(seed, month)));
    }

    /** The defaults this JVM runs under. */
    static String defaults() {
        return "charset=" + Charset.defaultCharset().name() + " locale=" + Locale.getDefault().toLanguageTag() + " zone="
                + TimeZone.getDefault().getID();
    }

    /** A second JVM started on this JVM's classpath with {@link #CHILD_DEFAULTS}, writing to its own file. */
    static Started start(long seed, int entries) throws IOException {
        Path out = Files.createTempFile("accounting-battery", ".txt");
        Path log = Files.createTempFile("accounting-battery", ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-Xmx1g", "-Dfile.encoding=UTF-16", "-Duser.language=ar", "-Duser.country=EG",
                "-Duser.timezone=America/Adak", "-cp", System.getProperty("java.class.path"),
                AccountingBatteryInAnotherJvm.class.getName(), out.toString(), Long.toString(seed), Integer.toString(entries));
        try {
            return new Started(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start(), out, log);
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            throw failed;
        }
    }

    /** A started second JVM: its process, its output file and its log. */
    record Started(Process child, Path out, Path log) {

        /** For a caller that failed before joining: the child destroyed and its files deleted - nothing left behind. */
        void abandon() throws IOException {
            child.destroyForcibly();
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
        }

        /**
         * Waits at most {@code timeout} for the child and returns the lines it wrote after its defaults line - which must be
         * {@link #CHILD_DEFAULTS}, and this JVM's must differ.
         */
        List<String> join(Duration timeout) throws IOException, InterruptedException {
            try {
                boolean finished = child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    child.destroyForcibly();
                }
                assertThat(finished).as("the second JVM finished").isTrue();
                assertThat(child.exitValue()).as(Files.readString(log, StandardCharsets.UTF_8)).isZero();
                List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
                assertThat(lines).as("the second JVM wrote its defaults").isNotEmpty();
                assertThat(lines.get(0)).as("the defaults it ran under").isEqualTo(CHILD_DEFAULTS);
                assertThat(defaults()).as("and this JVM's differ").isNotEqualTo(lines.get(0));
                return List.copyOf(lines.subList(1, lines.size()));
            } finally {
                Files.deleteIfExists(out);
                Files.deleteIfExists(log);
            }
        }
    }
}
