package com.finapp.accounting.reporting;

/**
 * A report definition that is <strong>never FINAL and never a run</strong> (`P14-TSK-017`; ADR-0115 As built;
 * {@code INV-ACC-04}): it reads state that keeps moving after a period's lock, so no run of it could regenerate byte for
 * byte, and a FINAL run would keep answering its first answer for as long as the lock stands
 * ({@code ReportRuns.answers}). Nor can it be a labelled PROVISIONAL run: {@code accounting V004} holds a PROVISIONAL run to an
 * {@code OPEN} period for every writer.
 *
 * <p>So nothing of it is stored. {@code ReportRuns.claim} refuses it; the operational door generates it on a verified
 * read-only snapshot, in the same canonical form and under the door's own time budget, records the read and serves it, and the
 * content's {@code basis} says {@code OPERATIONAL}. Its {@link ReportInputs} carry the snapshot's database instant as
 * {@code cutAt}, which is what every age and "as of" in its body is measured from - never a clock of its own.
 *
 * <p>The moving reads are held to these classes: {@code ReportingNeverWritesTest} refuses them to any other report class.
 */
public interface OperationalReportDefinition extends ReportDefinition {

    @Override
    default boolean operational() {
        return true;
    }
}
