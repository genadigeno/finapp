package com.finapp.accounting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Source completeness - section 13.3's third level (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-12},
 * {@code INV-REC-02}): the product proofs' verdicts, read through {@link SourceProofs}, judged into breaks. A proof's
 * finding - a missing or duplicate entry, an amount or currency discrepancy, a reversed source with its entry unreversed,
 * as that proof states it - is a {@link BreakType#SOURCE_DISCREPANCY} on subject {@code <PROOF>:<the proof's subject>},
 * its evidence the proof's own. Pure: the readings arrive; nothing is read or written here.
 *
 * <p><strong>No false pass</strong> ({@code INV-REC-02}): a source that could not be read, a reading that is incomplete,
 * a proof the answer omits or answers twice - each makes that proof <em>unavailable</em>, and an unavailable proof makes
 * {@code SOURCE_COMPLETENESS} {@code FAILED} ({@link AccountingChecks}). The other sources' findings are still raised.
 *
 * <p>{@link BreakType#SOURCE_DELAYED} is not raised: no built proof tells a delay from a discrepancy; accrual lag is the
 * close run's {@code ACCRUALS_COMPLETE} (`P14-TSK-011`, {@code AccrualCompleteness}).
 */
public final class SourceCompleteness {

    /** The catalogue of proofs read - {@link SourceProofs.Proof}'s nine. Raised when a proof is added or withdrawn. */
    public static final int CATALOGUE_VERSION = 1;

    /** The register version every source finding is judged by. */
    public static final String JUDGED_BY = "source-proofs:v" + CATALOGUE_VERSION;

    /** One source judged: its findings and the proofs it could not answer. */
    public record Judged(List<BreakFinding> findings, List<SourceProofs.Proof> unavailable) {

        public Judged {
            findings = List.copyOf(findings);
            unavailable = List.copyOf(unavailable);
        }
    }

    /** {@code source}'s readings judged - every proof it covers answered exactly once and completely, or unavailable. */
    public Judged judge(SourceProofs.Source source, List<SourceProofs.Reading> readings) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(readings, "readings");
        Map<SourceProofs.Proof, List<SourceProofs.Reading>> byProof = new EnumMap<>(SourceProofs.Proof.class);
        for (SourceProofs.Reading reading : readings) {
            byProof.computeIfAbsent(reading.proof(), proof -> new ArrayList<>()).add(reading);
        }
        List<BreakFinding> findings = new ArrayList<>();
        List<SourceProofs.Proof> unavailable = new ArrayList<>();
        for (SourceProofs.Proof proof : source.proofs()) {
            List<SourceProofs.Reading> answered = byProof.getOrDefault(proof, List.of());
            if (answered.size() != 1 || !answered.get(0).complete()) {
                unavailable.add(proof);
                continue;
            }
            for (SourceProofs.Discrepancy discrepancy : answered.get(0).discrepancies()) {
                findings.add(new BreakFinding(CloseCheckCode.SOURCE_COMPLETENESS, BreakType.SOURCE_DISCREPANCY,
                        BreakFinding.cut(proof.name() + ":" + discrepancy.subject(), BreakFinding.MAX_SUBJECT),
                        "proof=" + proof.name() + ", subject=" + discrepancy.subject() + ", " + discrepancy.evidence(),
                        JUDGED_BY));
            }
        }
        for (SourceProofs.Proof stray : byProof.keySet()) {
            if (stray.source() != source) {
                // An answer for another source's proof is the adapter's defect - never trusted, never a pass.
                unavailable.addAll(source.proofs().stream().filter(proof -> !unavailable.contains(proof)).toList());
                return new Judged(List.of(), unavailable);
            }
        }
        return new Judged(findings, unavailable);
    }

    /** {@code source} could not be read: every proof it covers is unavailable. */
    public Judged unreadable(SourceProofs.Source source) {
        return new Judged(List.of(), Objects.requireNonNull(source, "source").proofs());
    }
}
