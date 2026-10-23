package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-JVM digest (`P13-TSK-003`; ADR-0109 decision 3; {@code INV-RSK-01}): the seeded corpus rendered to its
 * canonical inputs forms, evaluated here and in a second JVM with another charset, locale, time zone and identity
 * hash codes ({@link SecondJvm}) - the per-verdict SHA-256s and the digest over all of them byte-identical. A rule
 * reading the clock, a map iterated in a salted order, a locale-sensitive {@code toString} on a figure: each would
 * give the child other bytes. Default tier, as the creditmath precedent: a second JVM needs nothing but the JDK.
 */
@DisplayName("the risk engine gives the same bytes in a second JVM (P13-TSK-003)")
class RiskEngineDigestTest {

    static final int PER_OPERATION = 60;

    @Test
    @DisplayName("every verdict's SHA-256 and the digest over the corpus are identical in a JVM with other defaults")
    void theDigestIsIdenticalInAnotherJvm() throws Exception {
        List<String> corpus = new ArrayList<>();
        for (EvaluationCorpus.Case c : EvaluationCorpus.generate(EvaluationCorpus.SEED, PER_OPERATION)) {
            corpus.add(CanonicalEvaluation.render(c.inputs()));
        }
        assertThat(corpus).hasSize(PER_OPERATION * 6);
        Path file = Files.createTempFile("risk-engine-corpus", ".txt");
        try {
            Files.write(file, corpus, StandardCharsets.UTF_8);
            List<String> here = RiskEngineInAnotherJvm.digests(corpus);
            List<String> there = SecondJvm.run(RiskEngineInAnotherJvm.class, file.toString());
            assertThat(there).as("one digest per verdict and one over all").hasSize(corpus.size() + 1);
            assertThat(there).isEqualTo(here);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
