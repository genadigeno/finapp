package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proof over fakes (`P13-TSK-006`; {@code INV-RSK-03}; F8): windows equal to their evidence hold; a count drift, a
 * sum drift and a lost window each diverge and are named; a movement within the skew of an edge widens the range rather
 * than raising a false alarm; a window counted after the evidence is complete is pending; a dimension the evidence
 * cannot name is unprovable; and nothing is ever written - the proof reports, it never corrects. Completed by
 * `P13-TSK-023` (the proof made whole): the record's link judges every dimension and the journal's only the windows of
 * transfers alone; a transfer counted and never posted diverges on the journal alone; a lost count names its currency.
 */
@DisplayName("the velocity proof recomputes every provable live window and reports a difference, never correcting it"
        + " (P13-TSK-006, made whole by P13-TSK-023)")
class VelocityProofTest {

    private static final Instant NOW = Instant.parse("2031-03-09T15:30:00Z");
    private static final UUID PARTY = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID DESTINATION = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final Set<VelocityDimension> PROVABLE =
            EnumSet.of(VelocityDimension.SOURCE_ACCOUNT, VelocityDimension.DESTINATION);

    private final FakeVelocityWindowStore store = new FakeVelocityWindowStore(NOW);
    private final VelocityWindows windows = new VelocityWindows(store);
    private final List<VelocityEvidence.Counted> evidence = new ArrayList<>();

    @Test
    @DisplayName("windows counted from exactly the evidence hold; the party's are unprovable, never judged")
    void heldWhenTheWindowsEqualTheEvidence() {
        transfer(Instant.parse("2031-03-09T10:15:00Z"), 120_00);
        transfer(Instant.parse("2031-03-09T11:45:00Z"), 30_00);
        transfer(Instant.parse("2031-03-09T15:01:00Z"), 7_00);
        int written = store.upserted.size();

        VelocityProof.Result result = judge(NOW);
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(result.divergences()).isEmpty();
        // Live windows: H1 15:00 (one movement) and D1, D7, D30 for the source and the destination - and the party's,
        // which the journal cannot name.
        assertThat(result.judged()).isEqualTo(2 * 4);
        assertThat(result.unprovable()).isEqualTo(4);
        assertThat(result.pending()).isZero();
        assertThat(store.upserted).as("the proof never wrote").hasSize(written);
    }

    @Test
    @DisplayName("a count drift - a double count planted - diverges and names the window; nothing is corrected")
    void aCountDriftDiverges() {
        transfer(Instant.parse("2031-03-09T10:15:00Z"), 120_00);
        VelocityWindow day = store.rows.get(key(VelocityDimension.SOURCE_ACCOUNT, SOURCE, WindowKind.D1, "2031-03-09T00:00:00Z"));
        store.plant(new VelocityWindow(day.key(), 2, day.sum().plus(eur(120_00)), day.lastCountedAt()));

        VelocityProof.Result result = judge(NOW);
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(result.divergences()).singleElement().satisfies(divergence -> {
            assertThat(divergence.key()).isEqualTo(day.key());
            assertThat(divergence.recordedCount()).isEqualTo(2);
            assertThat(divergence.fromCount()).isEqualTo(1);
            assertThat(divergence.toCount()).isEqualTo(1);
        });
        assertThat(store.rows.get(day.key()).count()).as("never corrected").isEqualTo(2);
        assertThat(result.toString()).doesNotContain(SOURCE.toString());
        assertThat(result.divergences().getFirst().toString()).doesNotContain(SOURCE.toString());
    }

    @Test
    @DisplayName("a sum drift with the count right diverges too - a window equals its evidence in both figures")
    void aSumDriftDiverges() {
        transfer(Instant.parse("2031-03-09T10:15:00Z"), 120_00);
        VelocityWindow week = store.rows.get(new VelocityWindowKey(VelocityDimension.DESTINATION,
                DESTINATION.toString(), WindowKind.D7, WindowStarts.start(WindowKind.D7, NOW)));
        store.plant(new VelocityWindow(week.key(), 1, eur(119_99), week.lastCountedAt()));

        VelocityProof.Result result = judge(NOW);
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(result.divergences()).singleElement().satisfies(divergence -> {
            assertThat(divergence.key()).isEqualTo(week.key());
            assertThat(divergence.recordedSumMinor()).isEqualTo(119_99);
            assertThat(divergence.fromSumMinor()).isEqualTo(120_00);
        });
    }

    @Test
    @DisplayName("a movement the evidence holds whose window has no row - a lost count - diverges with nothing recorded")
    void aLostWindowDiverges() {
        evidence.add(new VelocityEvidence.Counted(movement(55_00), Instant.parse("2031-03-09T12:00:00Z")));

        VelocityProof.Result result = judge(NOW);
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        // The source's and the destination's D1, D7 and D30 windows are live; the 12:00 H1 window has ended.
        assertThat(result.divergences()).hasSize(2 * 3).allSatisfy(divergence -> {
            assertThat(divergence.recordedCount()).isZero();
            assertThat(divergence.fromCount()).isEqualTo(1);
            assertThat(divergence.fromSumMinor()).isEqualTo(55_00);
        });
    }

    @Test
    @DisplayName("a movement stamped within the skew of a window's edge may have been cut on either side - the range"
            + " admits both, so an instance clock five seconds off never raises a false alarm")
    void theSkewAtAnEdgeIsAdmitted() {
        // Counted by the database at 15:00:01 into the 15:00 hour; its journal stamp, from an instance clock four seconds
        // behind, reads 14:59:57 - the previous hour on paper.
        store.now = Instant.parse("2031-03-09T15:00:01Z");
        windows.increment(StandInConnections.unused(), windows.cut(StandInConnections.unused(), movement(10_00)),
                RiskDecision.ALLOW, false);
        evidence.add(new VelocityEvidence.Counted(movement(10_00), Instant.parse("2031-03-09T14:59:57Z")));

        VelocityProof.Result result = judge(Instant.parse("2031-03-09T15:10:00Z"));
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.HELD);

        VelocityProof.Result strict = VelocityProof.judge(Instant.parse("2031-03-09T15:10:00Z"), store.live(null),
                new VelocityEvidence.Record(evidence, Instant.parse("2031-03-09T15:09:00Z")), PROVABLE, Duration.ZERO);
        assertThat(strict.verdict()).as("with no skew admitted the same evidence reads as a lost count")
                .isEqualTo(VelocityProof.Verdict.DIVERGED);
    }

    @Test
    @DisplayName("a window counted after the evidence is complete through is pending, not judged; with no provable live"
            + " window the proof is vacuous")
    void pendingAndVacuous() {
        transfer(Instant.parse("2031-03-09T10:15:00Z"), 120_00);
        VelocityProof.Result pending = VelocityProof.judge(NOW, store.live(null),
                new VelocityEvidence.Record(List.of(), Instant.parse("2031-03-09T10:00:00Z")), PROVABLE, VelocityProof.SKEW);
        assertThat(pending.verdict()).isEqualTo(VelocityProof.Verdict.VACUOUS);
        assertThat(pending.pending()).isEqualTo(2 * 3);
        assertThat(pending.judged()).isZero();

        VelocityProof.Result nothing = VelocityProof.judge(NOW, List.of(),
                new VelocityEvidence.Record(List.of(), NOW), PROVABLE, VelocityProof.SKEW);
        assertThat(nothing.verdict()).isEqualTo(VelocityProof.Verdict.VACUOUS);
        assertThatThrownBy(() -> new VelocityProof.Result(VelocityProof.Verdict.DIVERGED, NOW, NOW, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("run: one REPEATABLE READ snapshot - the database's clock, the live windows, the evidence of the longest"
            + " window back - and refused in any other isolation; no write in either case")
    void theRunReadsOneSnapshot() {
        transfer(Instant.parse("2031-03-09T10:15:00Z"), 120_00);
        int calls = store.calls.size();
        int written = store.upserted.size();
        List<Instant> asked = new ArrayList<>();
        VelocityEvidence fake = new VelocityEvidence() {
            @Override
            public Set<VelocityDimension> provable() {
                return PROVABLE;
            }

            @Override
            public Record read(Connection snapshot, Instant from) {
                asked.add(from);
                return new Record(evidence, NOW.minusSeconds(60));
            }
        };
        VelocityProof proof = new VelocityProof(store, fake);

        VelocityProof.Result result = proof.run(StandInConnections.snapshot());
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(result.asOf()).isEqualTo(NOW);
        assertThat(asked).containsExactly(NOW.minus(Duration.ofDays(30)).minus(VelocityProof.SKEW));
        assertThat(store.calls.subList(calls, store.calls.size())).containsExactly("now", "live");

        assertThatThrownBy(() -> proof.run(StandInConnections.declaring(true, false, Connection.TRANSACTION_READ_COMMITTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPEATABLE READ");
        assertThat(store.upserted).as("the proof never wrote").hasSize(written);
    }

    // ------------------------------------------------------------------ made whole (P13-TSK-023)

    @Test
    @DisplayName("made whole: the record judges every dimension; the journal only the windows of transfers alone - a"
            + " window that also counts a withdrawal, which posts later or never, is unprovable there, never diverged")
    void theJournalJudgesTheWindowsOfTransfersAlone() {
        countAndPost(Instant.parse("2031-03-09T10:15:00Z"), movement(120_00), RiskOperation.TRANSFER, true);
        VelocityMovement withdrawal = VelocityMovement.of(eur(40_00)).party(PARTY).sourceAccount(SOURCE)
                .destinationBeneficiary(UUID.fromString("018f0000-0000-7000-8000-000000000009")).build();
        countAndPost(Instant.parse("2031-03-09T11:00:00Z"), withdrawal, RiskOperation.WITHDRAWAL, false);
        int written = store.upserted.size();

        VelocityProof.Whole whole = whole().prove(StandInConnections.snapshot());
        assertThat(whole.record().verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        // Live at 15:30: D1, D7 and D30 of each subject (the H1 windows of 10:00 and 11:00 have closed).
        assertThat(whole.record().judged()).as("party, source, the account and the beneficiary destinations")
                .isEqualTo(4 * 3);
        VelocityProof.Result journal = whole.journal().orElseThrow();
        assertThat(journal.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(journal.judged()).as("the transfer's destination account's live windows alone").isEqualTo(3);
        assertThat(journal.unprovable()).as("the party's, the mixed source's, the beneficiary's").isEqualTo(3 * 3);
        assertThat(whole.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(store.upserted).as("the proof never wrote").hasSize(written);
    }

    @Test
    @DisplayName("made whole: a transfer counted and never posted holds against the record and diverges against the"
            + " journal - the seam that counted a movement that never moved is named, and nothing is corrected")
    void aTransferThatNeverPostedDivergesOnTheJournal() {
        countAndPost(Instant.parse("2031-03-09T10:15:00Z"), movement(120_00), RiskOperation.TRANSFER, true);
        countAndPost(Instant.parse("2031-03-09T10:45:00Z"), movement(30_00), RiskOperation.TRANSFER, false);
        int written = store.upserted.size();

        VelocityProof.Whole whole = whole().prove(StandInConnections.snapshot());
        assertThat(whole.record().verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(whole.journal().orElseThrow().verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(whole.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(whole.diverged()).as("the source's and the destination's live windows").isEqualTo(2 * 3);
        assertThat(whole.journal().orElseThrow().divergences()).allSatisfy(divergence -> {
            assertThat(divergence.recordedCount()).isEqualTo(2);
            assertThat(divergence.toCount()).isEqualTo(1);
            assertThat(divergence.currency()).isEqualTo(RiskPolicyFixtures.EUR);
            assertThat(divergence.scale()).isEqualTo(2);
        });
        assertThat(store.upserted).as("named, never corrected").hasSize(written);
        assertThatThrownBy(() -> whole().prove(StandInConnections.declaring(true, false, Connection.TRANSACTION_READ_COMMITTED)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> whole().prove(StandInConnections.declaring(false, false,
                Connection.TRANSACTION_REPEATABLE_READ))).as("a connection that could write is refused too (M9)")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
    }

    @Test
    @DisplayName("a lost count is named in its own currency: a USD movement whose source's window counts EUR has no row"
            + " in its currency - one divergence per live window kind, in USD")
    void aLostCountNamesItsCurrency() {
        transfer(Instant.parse("2031-03-09T10:15:00Z"), 120_00);
        evidence.add(new VelocityEvidence.Counted(VelocityMovement.of(Money.ofMinorUnits(9_00,
                com.finapp.sharedkernel.money.CurrencyCode.of("USD"))).sourceAccount(SOURCE).build(),
                Instant.parse("2031-03-09T10:30:00Z")));

        VelocityProof.Result result = judge(NOW);
        assertThat(result.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(result.divergences()).hasSize(3).allSatisfy(divergence -> {
            assertThat(divergence.currency().code()).isEqualTo("USD");
            assertThat(divergence.recordedCount()).isZero();
            assertThat(divergence.fromSumMinor()).isEqualTo(9_00);
        });
    }

    @Test
    @DisplayName("the journal link explains, never diverges on, the windows of a transfer permitted uncounted - the"
            + " fail-safe's money moves uncounted by design; without the record naming it, the same posting diverges")
    void anUncountedTransferIsExplainedNotDiverged() {
        countAndPost(Instant.parse("2031-03-09T10:15:00Z"), movement(120_00), RiskOperation.TRANSFER, true);
        VelocityEvidence.Counted uncounted = new VelocityEvidence.Counted(movement(30_00),
                Instant.parse("2031-03-09T10:45:00Z"), java.util.Optional.of(RiskOperation.TRANSFER));
        posted.add(uncounted);

        VelocityProof.Result blind = whole().prove(StandInConnections.snapshot()).journal().orElseThrow();
        assertThat(blind.verdict()).as("a record that does not name the uncounted movement").isEqualTo(
                VelocityProof.Verdict.DIVERGED);

        this.uncounted.add(uncounted);
        VelocityProof.Whole whole = whole().prove(StandInConnections.snapshot());
        VelocityProof.Result journal = whole.journal().orElseThrow();
        assertThat(journal.divergences()).isEmpty();
        assertThat(journal.explained()).as("the source's and the destination's live windows").isEqualTo(2 * 3);
        assertThat(whole.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
    }

    @Test
    @DisplayName("an uncounted movement explains only itself: a transfer counted and never posted still diverges beside"
            + " an uncounted transfer in its windows - subtracted, never the window set aside - and an uncounted"
            + " withdrawal, which never posts as a transfer, explains nothing")
    void anUncountedMovementExplainsOnlyItself() {
        countAndPost(Instant.parse("2031-03-09T10:15:00Z"), movement(120_00), RiskOperation.TRANSFER, true);
        countAndPost(Instant.parse("2031-03-09T10:30:00Z"), movement(40_00), RiskOperation.TRANSFER, false);
        VelocityEvidence.Counted uncountedTransfer = new VelocityEvidence.Counted(movement(30_00),
                Instant.parse("2031-03-09T10:45:00Z"), java.util.Optional.of(RiskOperation.TRANSFER));
        posted.add(uncountedTransfer);
        uncounted.add(uncountedTransfer);
        uncounted.add(new VelocityEvidence.Counted(VelocityMovement.of(eur(70_00)).party(PARTY).sourceAccount(SOURCE)
                .build(), Instant.parse("2031-03-09T11:00:00Z"), java.util.Optional.of(RiskOperation.WITHDRAWAL)));

        VelocityProof.Result journal = whole().prove(StandInConnections.snapshot()).journal().orElseThrow();
        assertThat(journal.verdict()).as("the never-posted 40.00 is not excused by the uncounted 30.00")
                .isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(journal.divergences()).as("the source's and the destination's live windows").hasSize(2 * 3)
                .allSatisfy(divergence -> {
                    assertThat(divergence.recordedCount()).isEqualTo(2);
                    assertThat(divergence.toCount()).as("two postings less the uncounted transfer").isEqualTo(1);
                    assertThat(divergence.recordedSumMinor()).isEqualTo(160_00);
                    assertThat(divergence.toSumMinor()).isEqualTo(120_00);
                });
        assertThat(journal.explained()).as("the windows the uncounted transfer adjusted").isEqualTo(2 * 3);
    }

    @Test
    @DisplayName("the floor is the first transfer the record ever holds, not the read's: a posting after the go-live with"
            + " no counterpart in the record is judged - and diverges - however far before the read's first transfer")
    void theFloorIsTheGoLiveNotTheRead() {
        posted.add(new VelocityEvidence.Counted(movement(55_00), Instant.parse("2031-03-09T01:00:00Z"),
                java.util.Optional.of(RiskOperation.TRANSFER)));
        countAndPost(Instant.parse("2031-03-09T10:15:00Z"), movement(120_00), RiskOperation.TRANSFER, true);
        goLive = Instant.parse("2031-01-15T09:00:00Z");

        VelocityProof.Result journal = whole().prove(StandInConnections.snapshot()).journal().orElseThrow();
        assertThat(journal.beforeFloor()).isZero();
        assertThat(journal.verdict()).as("the 01:00 posting no record holds").isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(VelocityProof.transferFloor(new VelocityEvidence.Record(record, NOW, List.of(),
                java.util.Optional.of(goLive)))).contains(goLive);
    }

    @Test
    @DisplayName("a posting older than the record's first transfer is before the floor - the seams judged nothing before"
            + " they went live - counted, never a lost count")
    void postingsBeforeTheFloorAreNotJudged() {
        posted.add(new VelocityEvidence.Counted(movement(55_00), Instant.parse("2031-03-09T01:00:00Z"),
                java.util.Optional.of(RiskOperation.TRANSFER)));
        countAndPost(Instant.parse("2031-03-09T10:15:00Z"), movement(120_00), RiskOperation.TRANSFER, true);

        VelocityProof.Result journal = whole().prove(StandInConnections.snapshot()).journal().orElseThrow();
        assertThat(journal.beforeFloor()).isEqualTo(1);
        assertThat(journal.divergences()).isEmpty();
        assertThat(journal.verdict()).isEqualTo(VelocityProof.Verdict.HELD);
        assertThat(VelocityProof.transferFloor(new VelocityEvidence.Record(record, NOW)))
                .contains(Instant.parse("2031-03-09T10:15:00Z"));
        assertThat(VelocityProof.transferFloor(new VelocityEvidence.Record(List.of(), NOW))).isEmpty();
    }

    // -----------------------------------------------------------------

    private final List<VelocityEvidence.Counted> record = new ArrayList<>();
    private final List<VelocityEvidence.Counted> posted = new ArrayList<>();
    private final List<VelocityEvidence.Counted> uncounted = new ArrayList<>();

    /** {@code movement} counted by T-eval at {@code at} as {@code operation}, and - when {@code posts} - journalled. */
    private void countAndPost(Instant at, VelocityMovement movement, RiskOperation operation, boolean posts) {
        store.now = at;
        windows.increment(StandInConnections.unused(), windows.cut(StandInConnections.unused(), movement),
                RiskDecision.ALLOW, false);
        record.add(new VelocityEvidence.Counted(movement, at, java.util.Optional.of(operation)));
        if (posts) {
            posted.add(new VelocityEvidence.Counted(movement, at, java.util.Optional.of(RiskOperation.TRANSFER)));
        }
        store.now = NOW;
    }

    /** The record's go-live when the suite sets one older than its read - null: the read is the whole history. */
    private Instant goLive;

    /** The proof made whole over the planted record (every dimension) and journal (the two account dimensions). */
    private VelocityProof whole() {
        return new VelocityProof(store, fixed(EnumSet.allOf(VelocityDimension.class), record, uncounted, goLive),
                fixed(PROVABLE, posted, List.of(), null));
    }

    private static VelocityEvidence fixed(Set<VelocityDimension> provable, List<VelocityEvidence.Counted> movements,
            List<VelocityEvidence.Counted> uncounted, Instant goLive) {
        return new VelocityEvidence() {
            @Override
            public Set<VelocityDimension> provable() {
                return provable;
            }

            @Override
            public Record read(Connection snapshot, Instant from) {
                return goLive == null ? new Record(movements, NOW.minusSeconds(60), List.copyOf(uncounted))
                        : new Record(movements, NOW.minusSeconds(60), List.copyOf(uncounted),
                                java.util.Optional.of(goLive));
            }
        };
    }

    /** A transfer counted by T-eval on the database's clock at {@code at}, and the journal's record of it. */
    private void transfer(Instant at, long minor) {
        store.now = at;
        windows.increment(StandInConnections.unused(), windows.cut(StandInConnections.unused(), movement(minor)),
                RiskDecision.ALLOW, false);
        evidence.add(new VelocityEvidence.Counted(movement(minor), at));
        store.now = NOW;
    }

    private VelocityProof.Result judge(Instant asOf) {
        store.now = asOf;
        return VelocityProof.judge(asOf, store.live(null), new VelocityEvidence.Record(evidence, asOf.minusSeconds(60)),
                PROVABLE, VelocityProof.SKEW);
    }

    private static VelocityMovement movement(long minor) {
        return VelocityMovement.of(eur(minor)).party(PARTY).sourceAccount(SOURCE).destinationAccount(DESTINATION)
                .build();
    }

    private static VelocityWindowKey key(VelocityDimension dimension, UUID subject, WindowKind kind, String start) {
        return new VelocityWindowKey(dimension, subject.toString(), kind, Instant.parse(start));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
    }
}
