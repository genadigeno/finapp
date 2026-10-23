package com.finapp.risk;

import com.finapp.risk.testing.RiskPolicyFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The risk engine's second JVM (`P13-TSK-003`; the backlog's two-JVM digest; {@code INV-RSK-01}): reads the corpus
 * the parent rendered to a file - one canonical inputs form per line - parses each strictly, evaluates it under its
 * operation's fixture, and writes the SHA-256 of each verdict's canonical form and, last, the SHA-256 over every
 * verdict form joined. {@code RiskEngineDigestTest} computes the same lines in its own JVM and requires them
 * identical. Parsing from the text rather than regenerating from the seed is deliberate: it is the sealed text
 * `-004` replays from that must evaluate the same everywhere.
 */
final class RiskEngineInAnotherJvm {

    private RiskEngineInAnotherJvm() {}

    public static void main(String[] arguments) throws IOException {
        List<String> corpus = Files.readAllLines(Path.of(arguments[1]), StandardCharsets.UTF_8);
        SecondJvm.write(Path.of(arguments[0]), digests(corpus));
    }

    /** One SHA-256 per verdict, then the SHA-256 over all verdict forms joined with a newline. */
    static List<String> digests(List<String> corpus) {
        List<String> lines = new ArrayList<>(corpus.size() + 1);
        StringBuilder all = new StringBuilder();
        for (String canonical : corpus) {
            EvaluationInputs inputs = CanonicalEvaluation.parseInputs(canonical);
            Verdict verdict = RiskEngineV1.evaluate(RiskPolicyFixtures.v1(inputs.operation()), inputs);
            String rendered = CanonicalEvaluation.render(verdict);
            lines.add(CanonicalEvaluation.sha256(rendered));
            all.append(rendered).append('\n');
        }
        lines.add(CanonicalEvaluation.sha256(all.toString()));
        return lines;
    }
}
