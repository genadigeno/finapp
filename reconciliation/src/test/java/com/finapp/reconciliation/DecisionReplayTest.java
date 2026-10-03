package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decision replay's golden snapshots (`P8-TSK-022`, ADR-0068 §9.1; `INV-REC-04`,
 * `INV-HIST-04`): one stored decision per verdict of every pure function, each written as the
 * UNPERTURBED engine concluded it, replayed {@code IDENTICAL}; and each kind of divergence -
 * verdict, allocation, outcome, a fee priced differently - caught. Because these snapshots were
 * fixed when written, a perturbed strategy constant replays them {@code DIVERGED}: the
 * replay-perturbation probe's catcher (a running suite cannot perturb both the deciding and the
 * replaying code, so live decisions alone could never catch it).
 */
@DisplayName("decision replay: golden snapshots per verdict, and every divergence caught (P8-TSK-022)")
class DecisionReplayTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final LocalDate DAY = LocalDate.parse("2026-09-29");
    private static final Instant OPENED = Instant.parse("2026-09-28T09:00:00Z");
    private static final UUID ITEM = UUID.fromString("01a0e2bd-0000-7000-8000-000000000001");
    private static final UUID E1 = UUID.fromString("01a0e2bd-0000-7000-8000-0000000000e1");
    private static final UUID E2 = UUID.fromString("01a0e2bd-0000-7000-8000-0000000000e2");

    private static final DecisionReplay.RuleFacts CAPTURE_RULE =
            new DecisionReplay.RuleFacts(
                    1, Optional.of(KeyKind.PSP_CAPTURE_REF), Optional.of(ExpectationKind.CARD_CAPTURE),
                    Cardinality.ONE_TO_ONE, 48);
    private static final DecisionReplay.RuleFacts GROUP_RULE =
            new DecisionReplay.RuleFacts(
                    3, Optional.empty(), Optional.of(ExpectationKind.REMITTANCE),
                    Cardinality.GROUP_BY_VALUE_DATE, 48);
    private static final DecisionReplay.RuleFacts CORRECTION_RULE =
            new DecisionReplay.RuleFacts(
                    2, Optional.empty(), Optional.empty(), Cardinality.CORRECTION, 48);

    // ----------------------------------------------------------------- the matching engine

    @Test
    @DisplayName("ALLOCATE: one live candidate - its allocation, MATCHED; an excess parks")
    void allocate() {
        assertIdentical(match(DecisionVerdict.ALLOCATE, JudgedStatus.PENDING, 100_00,
                item(100_00, EUR, false), List.of(hit(E1, 100_00, 100_00, EUR,
                        ExpectationDirection.INBOUND)),
                DecisionOutcome.MATCHED, List.of(alloc(E1, 100_00))));
        assertIdentical(match(DecisionVerdict.ALLOCATE, JudgedStatus.PENDING, 120_00,
                item(120_00, EUR, false), List.of(hit(E1, 100_00, 100_00, EUR,
                        ExpectationDirection.INBOUND)),
                DecisionOutcome.PARKED, List.of(alloc(E1, 100_00))));
    }

    @Test
    @DisplayName("AMBIGUOUS: two live candidates - nothing allocated, PARKED")
    void ambiguous() {
        assertIdentical(match(DecisionVerdict.AMBIGUOUS, JudgedStatus.PENDING, 100_00,
                item(100_00, EUR, false),
                List.of(hit(E1, 100_00, 100_00, EUR, ExpectationDirection.INBOUND),
                        hit(E2, 100_00, 100_00, EUR, ExpectationDirection.INBOUND)),
                DecisionOutcome.PARKED, List.of()));
    }

    @Test
    @DisplayName("DUPLICATE: a fingerprint seen earlier, or every candidate exhausted")
    void duplicate() {
        assertIdentical(match(DecisionVerdict.DUPLICATE, JudgedStatus.PENDING, 100_00,
                item(100_00, EUR, true),
                List.of(hit(E1, 100_00, 100_00, EUR, ExpectationDirection.INBOUND)),
                DecisionOutcome.PARKED, List.of()));
        assertIdentical(match(DecisionVerdict.DUPLICATE, JudgedStatus.PENDING, 100_00,
                item(100_00, EUR, false),
                List.of(hit(E1, 100_00, 0, EUR, ExpectationDirection.INBOUND)),
                DecisionOutcome.PARKED, List.of()));
    }

    @Test
    @DisplayName("contradictions: a direction, then a currency - the listed precedence")
    void contradictions() {
        assertIdentical(match(DecisionVerdict.DIRECTION_CONTRADICTED, JudgedStatus.PENDING,
                100_00, item(100_00, EUR, false),
                List.of(hit(E1, 100_00, 100_00, EUR, ExpectationDirection.OUTBOUND)),
                DecisionOutcome.PARKED, List.of()));
        assertIdentical(match(DecisionVerdict.CURRENCY_CONTRADICTED, JudgedStatus.PENDING,
                100_00, item(100_00, EUR, false),
                List.of(hit(E1, 100_00, 100_00, GBP, ExpectationDirection.INBOUND)),
                DecisionOutcome.PARKED, List.of()));
    }

    @Test
    @DisplayName("NO_CANDIDATES waits or is typed; NO_RULE when no landed rule exists")
    void nothingReached() {
        assertIdentical(new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(DecisionVerdict.NO_CANDIDATES),
                Optional.of(JudgedStatus.PENDING), 100_00, DecisionOutcome.UNMATCHED,
                item(100_00, EUR, false), DAY, Optional.empty(), true, 2, List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of()));
        assertIdentical(new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(DecisionVerdict.NO_RULE),
                Optional.of(JudgedStatus.PENDING), 100_00, DecisionOutcome.UNMATCHED,
                item(100_00, EUR, false), DAY, Optional.empty(), false, 2, List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of()));
    }

    @Test
    @DisplayName("a parked value is allocated whole - or, for a reprocess, examined unchanged")
    void parkedJudgedWhole() {
        DecisionReplay.StoredDecision whole =
                new DecisionReplay.StoredDecision(
                        UUID.randomUUID(), DecisionOrigin.REMATCH,
                        Optional.of(DecisionVerdict.ALLOCATE), Optional.of(JudgedStatus.PARKED),
                        40_00, DecisionOutcome.MATCHED, item(100_00, EUR, false), DAY,
                        Optional.of(CAPTURE_RULE), true, 2,
                        List.of(hit(E1, 40_00, 40_00, EUR, ExpectationDirection.INBOUND)),
                        List.of(), Optional.empty(), Optional.empty(), List.of(alloc(E1, 40_00)));
        assertIdentical(whole);
        DecisionReplay.StoredDecision partial =
                new DecisionReplay.StoredDecision(
                        UUID.randomUUID(), DecisionOrigin.REPROCESS,
                        Optional.of(DecisionVerdict.ALLOCATE), Optional.of(JudgedStatus.PARKED),
                        40_00, DecisionOutcome.PARKED, item(100_00, EUR, false), DAY,
                        Optional.of(CAPTURE_RULE), true, 2,
                        List.of(hit(E1, 40_00, 30_00, EUR, ExpectationDirection.INBOUND)),
                        List.of(), Optional.empty(), Optional.empty(), List.of());
        assertIdentical(partial);
    }

    // ----------------------------------------------------------------- the value-date group

    @Test
    @DisplayName("GROUP_MATCH allocates each untouched member whole; other group verdicts wait")
    void group() {
        LocalDate groupDate = DAY;
        List<MatchEngine.HitFacts> members =
                List.of(groupHit(E1, 60_00, groupDate), groupHit(E2, 40_00, groupDate));
        assertIdentical(group(DecisionVerdict.GROUP_MATCH, item(100_00, EUR, false), members,
                true, DecisionOutcome.MATCHED, List.of(alloc(E1, 60_00), alloc(E2, 40_00))));
        assertIdentical(group(DecisionVerdict.GROUP_TOTAL_DIFFERS, item(90_00, EUR, false),
                members, true, DecisionOutcome.UNMATCHED, List.of()));
        assertIdentical(group(DecisionVerdict.GROUP_MEMBERSHIP_MOVED, item(100_00, EUR, false),
                members, false, DecisionOutcome.UNMATCHED, List.of()));
    }

    // ----------------------------------------------------------------- the correction engine

    @Test
    @DisplayName("TOP_UP, OFFSET and CORRECTION_UNREACHED from the stored hits and originals")
    void correction() {
        assertIdentical(new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(DecisionVerdict.TOP_UP),
                Optional.of(JudgedStatus.PENDING), 10_00, DecisionOutcome.MATCHED,
                item(10_00, EUR, false), DAY, Optional.of(CORRECTION_RULE), true, 2,
                List.of(hit(E1, 100_00, 10_00, EUR, ExpectationDirection.INBOUND)), List.of(),
                Optional.empty(), Optional.empty(), List.of(alloc(E1, 10_00))));
        MatchEngine.ItemFacts clawBack =
                new MatchEngine.ItemFacts(
                        ITEM, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                        ExpectationDirection.OUTBOUND, Money.ofPersisted(20_00, EUR, 2), DAY,
                        Optional.empty(), false);
        assertIdentical(new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(DecisionVerdict.OFFSET),
                Optional.of(JudgedStatus.PENDING), 20_00, DecisionOutcome.OFFSET, clawBack, DAY,
                Optional.of(CORRECTION_RULE), true, 2, List.of(),
                List.of(parked(SuspenseSide.CREDIT, 20_00)), Optional.empty(), Optional.empty(),
                List.of()));
        assertIdentical(new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN,
                Optional.of(DecisionVerdict.CORRECTION_UNREACHED),
                Optional.of(JudgedStatus.PENDING), 20_00, DecisionOutcome.UNMATCHED, clawBack,
                DAY, Optional.of(CORRECTION_RULE), true, 2, List.of(),
                List.of(parked(SuspenseSide.CREDIT, 25_00)), Optional.empty(), Optional.empty(),
                List.of()));
    }

    // ----------------------------------------------------------------- the fee check

    @Test
    @DisplayName("a fee is re-priced from its gross and pinned terms: within, beyond, and a"
            + " differently priced expectation diverges")
    void fee() {
        FeeCheck.Schedule terms =
                new FeeCheck.Schedule(new BigDecimal("0.015000"), 25, 2, RoundingMode.HALF_UP);
        // 1.5% of 100.00 + 0.25 = 1.75.
        assertIdentical(fee(DecisionVerdict.FEE_WITHIN, 1_75, terms, 175));
        assertIdentical(fee(DecisionVerdict.FEE_BEYOND, 3_00, terms, 175));
        assertThat(DecisionReplay.replay(fee(DecisionVerdict.FEE_WITHIN, 1_75, terms, 160)).result())
                .as("the stored expected fee no longer follows from the terms")
                .isEqualTo(DecisionReplay.Result.DIVERGED);
    }

    // ----------------------------------------------------------------- a person, a contained

    @Test
    @DisplayName("MANUAL_CHOICE: the chosen candidate, its cardinality's amount; a choice"
            + " outside the snapshot diverges")
    void manual() {
        assertIdentical(manual(E1, 30_00));
        assertThat(DecisionReplay.replay(manual(E2, 30_00)).result())
                .isEqualTo(DecisionReplay.Result.DIVERGED);
    }

    @Test
    @DisplayName("an ERRORED decision and one written before V012 stored a verdict are not"
            + " replayed - never counted as divergence")
    void notReplayed() {
        DecisionReplay.StoredDecision errored =
                new DecisionReplay.StoredDecision(
                        UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(DecisionVerdict.ERRORED),
                        Optional.of(JudgedStatus.PENDING), 100_00, DecisionOutcome.ERRORED,
                        item(100_00, EUR, false), DAY, Optional.empty(), true, 2, List.of(),
                        List.of(), Optional.empty(), Optional.empty(), List.of());
        assertThat(DecisionReplay.replay(errored).result())
                .isEqualTo(DecisionReplay.Result.NOT_REPLAYED);
        DecisionReplay.StoredDecision unsnapshotted =
                new DecisionReplay.StoredDecision(
                        UUID.randomUUID(), DecisionOrigin.RUN, Optional.empty(), Optional.empty(),
                        0, DecisionOutcome.MATCHED, item(100_00, EUR, false), DAY,
                        Optional.empty(), true, 2, List.of(), List.of(), Optional.empty(),
                        Optional.empty(), List.of());
        assertThat(DecisionReplay.replay(unsnapshotted).result())
                .isEqualTo(DecisionReplay.Result.NOT_REPLAYED);
    }

    // ----------------------------------------------------------------- divergence

    @Test
    @DisplayName("divergence of each kind: the verdict, the allocation, the outcome")
    void everyDivergenceIsCaught() {
        List<MatchEngine.HitFacts> two =
                List.of(hit(E1, 100_00, 100_00, EUR, ExpectationDirection.INBOUND),
                        hit(E2, 100_00, 100_00, EUR, ExpectationDirection.INBOUND));
        assertThat(DecisionReplay.replay(match(DecisionVerdict.ALLOCATE, JudgedStatus.PENDING,
                        100_00, item(100_00, EUR, false), two, DecisionOutcome.MATCHED,
                        List.of(alloc(E1, 100_00)))))
                .as("a stored ALLOCATE over two live candidates")
                .extracting(DecisionReplay.Replayed::result)
                .isEqualTo(DecisionReplay.Result.DIVERGED);
        assertThat(DecisionReplay.replay(match(DecisionVerdict.ALLOCATE, JudgedStatus.PENDING,
                        100_00, item(100_00, EUR, false),
                        List.of(hit(E1, 100_00, 100_00, EUR, ExpectationDirection.INBOUND)),
                        DecisionOutcome.MATCHED, List.of(alloc(E1, 99_99)))).result())
                .as("an allocation one minor unit short")
                .isEqualTo(DecisionReplay.Result.DIVERGED);
        assertThat(DecisionReplay.replay(match(DecisionVerdict.ALLOCATE, JudgedStatus.PENDING,
                        120_00, item(120_00, EUR, false),
                        List.of(hit(E1, 100_00, 100_00, EUR, ExpectationDirection.INBOUND)),
                        DecisionOutcome.MATCHED, List.of(alloc(E1, 100_00)))).result())
                .as("an excess recorded as MATCHED instead of parked")
                .isEqualTo(DecisionReplay.Result.DIVERGED);
        DecisionReplay.Replayed first =
                DecisionReplay.replay(match(DecisionVerdict.ALLOCATE, JudgedStatus.PENDING,
                        100_00, item(100_00, EUR, false), two, DecisionOutcome.MATCHED,
                        List.of(alloc(E1, 100_00))));
        assertThat(DecisionReplay.firstDivergent(List.of(
                        new DecisionReplay.Replayed(UUID.randomUUID(),
                                DecisionReplay.Result.IDENTICAL, ""),
                        first)))
                .contains(first.decisionId());
    }

    // ----------------------------------------------------------------- fixtures

    private static void assertIdentical(DecisionReplay.StoredDecision stored) {
        DecisionReplay.Replayed replayed = DecisionReplay.replay(stored);
        assertThat(replayed.result())
                .as("%s replayed: %s", stored.verdict().orElse(null), replayed.detail())
                .isEqualTo(DecisionReplay.Result.IDENTICAL);
    }

    private static MatchEngine.ItemFacts item(long minor, CurrencyCode currency, boolean seen) {
        return new MatchEngine.ItemFacts(
                ITEM, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                Money.ofPersisted(minor, currency, 2), DAY, Optional.empty(), seen);
    }

    private static MatchEngine.HitFacts hit(
            UUID id, long amount, long remainder, CurrencyCode currency,
            ExpectationDirection direction) {
        return new MatchEngine.HitFacts(
                id, ExpectationKind.CARD_CAPTURE, direction, Money.ofPersisted(amount, currency, 2),
                remainder, OPENED, DAY, Optional.of(KeyKind.PSP_CAPTURE_REF), "op-" + id,
                Optional.empty());
    }

    private static MatchEngine.HitFacts groupHit(UUID id, long amount, LocalDate expectedBy) {
        return new MatchEngine.HitFacts(
                id, ExpectationKind.REMITTANCE, ExpectationDirection.INBOUND,
                Money.ofPersisted(amount, EUR, 2), amount, OPENED, expectedBy, Optional.empty(),
                "rem-" + id, Optional.empty());
    }

    private static CorrectionEngine.ParkedOriginal parked(SuspenseSide side, long remainder) {
        return new CorrectionEngine.ParkedOriginal(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), side, remainder, EUR, 2,
                new UUID(0L, 0L));
    }

    private static DecisionReplay.Allocation alloc(UUID expectation, long minor) {
        return new DecisionReplay.Allocation(expectation, minor);
    }

    private static DecisionReplay.StoredDecision match(
            DecisionVerdict verdict,
            JudgedStatus judged,
            long judgedMinor,
            MatchEngine.ItemFacts item,
            List<MatchEngine.HitFacts> candidates,
            DecisionOutcome outcome,
            List<DecisionReplay.Allocation> allocations) {
        return new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(verdict), Optional.of(judged),
                judgedMinor, outcome, item, DAY, Optional.of(CAPTURE_RULE), true, 2, candidates,
                List.of(), Optional.empty(), Optional.empty(), allocations);
    }

    private static DecisionReplay.StoredDecision group(
            DecisionVerdict verdict,
            MatchEngine.ItemFacts item,
            List<MatchEngine.HitFacts> members,
            boolean membershipComplete,
            DecisionOutcome outcome,
            List<DecisionReplay.Allocation> allocations) {
        return new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(verdict),
                Optional.of(JudgedStatus.PENDING), item.amount().minorUnits(), outcome, item, DAY,
                Optional.of(GROUP_RULE), true, 2, members, List.of(),
                Optional.of(membershipComplete), Optional.empty(), allocations);
    }

    private static DecisionReplay.StoredDecision fee(
            DecisionVerdict verdict, long reported, FeeCheck.Schedule terms, long storedExpected) {
        MatchEngine.ItemFacts feeLine =
                new MatchEngine.ItemFacts(
                        ITEM, ExternalLineType.PROCESSING_FEE, ExpectationDirection.OUTBOUND,
                        Money.ofPersisted(reported, EUR, 2), DAY, Optional.empty(), false);
        return new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.RUN, Optional.of(verdict),
                Optional.of(JudgedStatus.PENDING), reported, DecisionOutcome.CHECKED, feeLine, DAY,
                Optional.empty(), false, 2, List.of(), List.of(), Optional.empty(),
                Optional.of(new DecisionReplay.FeeFacts(
                        Optional.of(terms), 2, Optional.of(100_00L), storedExpected)),
                List.of());
    }

    private static DecisionReplay.StoredDecision manual(UUID chosen, long allocated) {
        return new DecisionReplay.StoredDecision(
                UUID.randomUUID(), DecisionOrigin.MANUAL,
                Optional.of(DecisionVerdict.MANUAL_CHOICE), Optional.of(JudgedStatus.PARKED),
                30_00, DecisionOutcome.MATCHED, item(30_00, EUR, false), DAY, Optional.empty(),
                true, 2, List.of(hit(E1, 30_00, 30_00, EUR, ExpectationDirection.INBOUND)),
                List.of(), Optional.empty(), Optional.empty(), List.of(alloc(chosen, allocated)));
    }
}
