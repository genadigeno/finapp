package com.finapp.app.credit;

import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.EngineVersions;
import com.finapp.platform.testing.database.DatabaseRoles;
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
}
