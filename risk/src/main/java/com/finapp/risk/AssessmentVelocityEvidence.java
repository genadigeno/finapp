package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link VelocityEvidence} over the sealed assessments (`P13-TSK-004`, closing `P13-TSK-006`'s finding; {@code
 * PHASE_13_PLAN.md} section 12.4; {@code INV-RSK-03}) - the evidence of record for every window. A window counts a
 * movement when it is <em>judged</em>; the assessment that judged it is sealed in the same transaction as the count,
 * names every subject the count was taken in (the party, the source account, the destination, the device, the
 * merchant), the amount with its currency and scale, and the database instant of the cut ({@code velocity_cut_at},
 * the very instant {@link WindowStarts} placed the windows from) - and only a {@link VelocityOutcome#COUNTED}
 * assessment was counted. So every dimension is {@link #provable}, for every operation, whenever its money posts.
 *
 * <p><strong>Complete through the snapshot.</strong> The windows and the assessments commit together or not at all,
 * so one {@code REPEATABLE READ} snapshot (the proof's) sees a count exactly when it sees its assessment; nothing is
 * pending behind the evidence but what is younger than the proof's own skew. {@link JdbcVelocityEvidence} - the
 * journal's transfers - stays as the independent cross-check of the two account dimensions of transfers: a window
 * that equals its assessments but not its postings is a seam that judged a movement that never posted.
 */
public final class AssessmentVelocityEvidence implements VelocityEvidence {

    private final RiskAssessmentStore assessments;
    private final VelocityWindowStore windows;

    public AssessmentVelocityEvidence(RiskAssessmentStore assessments, VelocityWindowStore windows) {
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.windows = Objects.requireNonNull(windows, "windows");
    }

    @Override
    public Set<VelocityDimension> provable() {
        return EnumSet.allOf(VelocityDimension.class);
    }

    @Override
    public Record read(Connection snapshot, Instant from) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(from, "from");
        Instant completeThrough = windows.databaseNow(snapshot);
        // P13-TSK-023: the permitted movements it did not count travel beside - the journal's link subtracts the
        // transfers among them - and the first transfer ever judged, the journal link's fixed floor.
        return new Record(assessments.countedSince(snapshot, from), completeThrough,
                assessments.uncountedSince(snapshot, from), assessments.firstTransferAt(snapshot));
    }
}
