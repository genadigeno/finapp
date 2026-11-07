package com.finapp.app.credit;

import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.EngineVersions;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The battery's second JVM (`P10-TST-002`): every decision in the battery's database replayed by a process with another
 * default charset, locale and time zone - and other identity hash codes, so any order a hash set or map lends a result
 * differs from the first JVM's. It writes, in UTF-8 whatever its default, the defaults it ran under and every verdict,
 * one line per decision, oldest first.
 *
 * <p>Run by {@code DecisionReproducibilityBatteryTest} and by the storm's replay census
 * ({@code CreditDecisionStormDatabaseTest}, `P10-TST-001`), with the database's coordinates as system properties.
 */
final class ReplayInAnotherJvm {

    private ReplayInAnotherJvm() {}

    public static void main(String[] arguments) throws Exception {
        CreditReplayProof proof = new CreditReplayProof(ReproducibilityWorld.replayer(EngineVersions.STANDARD));
        CreditReplayProof.Report report = new CreditReadingSnapshot(DatabaseRoles::application).read(proof::prove);
        List<String> lines = new ArrayList<>();
        lines.add(defaults());
        for (DecisionReplayer.Replay replay : report.replays()) {
            lines.add(line(replay));
        }
        Files.write(Path.of(arguments[0]), lines, StandardCharsets.UTF_8);
    }

    /** The defaults this JVM runs under. */
    static String defaults() {
        return "charset=" + Charset.defaultCharset().name() + " locale=" + Locale.getDefault().toLanguageTag() + " zone="
                + TimeZone.getDefault().getID();
    }

    /** One verdict: the decision, the verdict and the differences by kind, in their declared order. */
    static String line(DecisionReplayer.Replay replay) {
        return replay.decision().value() + " " + replay.verdict() + " "
                + replay.divergences().stream().sorted().map(Enum::name).toList();
    }

    /** The child's defaults, deliberately unlike any build machine's: its first line must read so. */
    static final String CHILD_DEFAULTS = "charset=UTF-16 locale=ar-EG zone=America/Adak";

    /** The database's coordinates, the only system properties the child is handed. */
    private static final List<String> COORDINATES = List.of("finapp.db.url", "finapp.db.app.user", "finapp.db.app.password");

    /**
     * This class's {@code main} started in a second JVM on this JVM's classpath, writing to {@code out}, its output to {@code
     * log}: the database's coordinates, its options and the classpath in {@code options}, an argument file
     * ({@link #launch}).
     */
    static Process start(Path options, Path out, Path log) throws IOException {
        List<String> coordinates = new ArrayList<>();
        for (String property : COORDINATES) {
            coordinates.add("-D" + property + "=" + DatabaseRoles.required(property));
        }
        return launch(options, coordinates, System.getProperty("java.class.path"), ReplayInAnotherJvm.class, out, log);
    }

    /**
     * The class {@code main} started in a JVM with {@link #CHILD_DEFAULTS}, {@code properties} and {@code classpath}, its
     * output file its one argument. <strong>Everything but the class and the output file goes in the argument file {@code
     * options}</strong>, read by the launcher from an {@code @}-prefixed argument (JDK 9+), never on the command line:
     * Windows refuses a command line past 32,767 characters, and {@code app}'s test classpath names the checkout once for each
     * module's build output - some 32,300 characters from a checkout root of 75, so a deeper checkout fails the child before
     * its first line ({@code CreateProcess error=206}; {@code P14-TST-002}'s merge guard found it on the accounting battery,
     * {@code P14-DOC-001} moved these two launches). The database's coordinates were already in such a file, so no other
     * process on the machine can list them; now the classpath is too. The caller deletes {@code options}.
     */
    static Process launch(Path options, List<String> properties, String classpath, Class<?> main, Path out, Path log)
            throws IOException {
        Files.writeString(options, argumentFile(properties, classpath), launcherEncoding());
        return new ProcessBuilder(command(options, main, out)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    /** The child's options, one to a line, each {@link #quoted}: {@code properties}, its heap, its defaults and the classpath. */
    static String argumentFile(List<String> properties, String classpath) {
        List<String> options = new ArrayList<>(properties);
        options.addAll(List.of("-Xmx1g", "-Dfile.encoding=UTF-16", "-Duser.language=ar", "-Duser.country=EG",
                "-Duser.timezone=America/Adak", "-cp", classpath));
        StringBuilder file = new StringBuilder();
        for (String option : options) {
            file.append(quoted(option)).append('\n');
        }
        return file.toString();
    }

    /** The command: the launcher, {@code @options}, the main class and its output file - no classpath, no coordinate. */
    static List<String> command(Path options, Class<?> main, Path out) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + options, main.getName(),
                out.toString());
    }

    /**
     * One argument-file argument: in double quotes, so a space or a {@code #} is part of it, each backslash and quote escaped,
     * since inside quotes a backslash escapes the next character ({@code C:\new} unescaped reads as {@code C:}, a newline
     * and {@code ew}).
     */
    static String quoted(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The encoding the launcher reads an argument file in - the platform's ({@code native.encoding}), as the accounting
     * battery's launcher writes it. A character it cannot carry is refused by {@code Files.writeString}, never read as another.
     */
    private static Charset launcherEncoding() {
        return Charset.forName(System.getProperty("native.encoding", StandardCharsets.UTF_8.name()));
    }
}
