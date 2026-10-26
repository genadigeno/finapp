package com.finapp.risk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The policy battery's second JVM (`P13-TST-002`; the backlog's "replayed byte-identically in two JVMs";
 * {@code INV-RSK-01}): the same seeded population generated, every verdict evaluated, sealed and replayed by a process with
 * another default charset, locale and time zone and other identity hash codes ({@link SecondJvm#CHILD_DEFAULTS}, the
 * harness `P13-TSK-003`'s digest runs in) - one line per subject (its cohort, its counts, the SHA-256 over its sealed inputs
 * texts and over its verdict texts) and the totals with every fingerprint. {@code RiskPolicyBatteryTest} computes the same
 * lines in its own JVM and requires them equal, every line: both JVMs write the same sealed bytes and replay them to the
 * same verdicts.
 */
final class RiskBatteryInAnotherJvm {

    private RiskBatteryInAnotherJvm() {}

    /** {@code arguments}: the output file, the subjects. */
    public static void main(String[] arguments) throws IOException {
        int subjects = Integer.parseInt(arguments[1]);
        List<RiskBattery.Summary> summaries = IntStream.range(0, subjects).parallel().mapToObj(RiskBattery::summary)
                .toList();
        SecondJvm.write(Path.of(arguments[0]), RiskBattery.lines(summaries));
    }
}
