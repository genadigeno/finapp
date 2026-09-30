package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The value-date group's pure seat (`P8-TSK-016`, ADR-0068 §3): an exact total matches and
 * names every member, any other total waits — never a subset — the untouched, date, kind,
 * direction and currency screens run before anything counts, a moved membership is never
 * judged, and the candidates' order does not matter ({@code INV-REC-04} as amended).
 */
@DisplayName("the value-date group decides purely (P8-TSK-016)")
class GroupMatchTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final LocalDate VALUE_DATE = LocalDate.parse("2026-10-02");
    private static final Optional<ExpectationKind> REMITTANCE =
            Optional.of(ExpectationKind.REMITTANCE);

    @Test
    @DisplayName("the line equals its date's candidates' total exactly: MATCH, every member"
            + " named in id order with the total")
    void anExactTotalMatches() {
        MatchEngine.HitFacts first = candidate(30_00, 30_00, VALUE_DATE, EUR);
        MatchEngine.HitFacts second = candidate(70_00, 70_00, VALUE_DATE, EUR);

        GroupMatch.Verdict verdict =
                GroupMatch.decide(
                        item(100_00, EUR), VALUE_DATE, REMITTANCE, List.of(second, first),
                        true);

        assertThat(verdict.kind()).isEqualTo(GroupMatch.Kind.MATCH);
        assertThat(verdict.candidates())
                .extracting(MatchEngine.HitFacts::expectationId)
                .isSorted()
                .containsExactlyInAnyOrder(first.expectationId(), second.expectationId());
        assertThat(verdict.total()).contains(Money.ofPersisted(100_00, EUR, 2));
    }

    @Test
    @DisplayName("a total that differs by one minor unit waits - and a subset that WOULD"
            + " equal the line is never searched for (INV-REC-08)")
    void aDifferingTotalWaitsAndNoSubsetIsChosen() {
        MatchEngine.HitFacts forty = candidate(40_00, 40_00, VALUE_DATE, EUR);
        MatchEngine.HitFacts sixty = candidate(60_00, 60_00, VALUE_DATE, EUR);
        MatchEngine.HitFacts third = candidate(25_00, 25_00, VALUE_DATE, EUR);

        GroupMatch.Verdict threeWay =
                GroupMatch.decide(
                        item(100_00, EUR), VALUE_DATE, REMITTANCE,
                        List.of(forty, sixty, third), true);
        assertThat(threeWay.kind()).isEqualTo(GroupMatch.Kind.TOTAL_DIFFERS);
        assertThat(threeWay.candidates()).hasSize(3);
        assertThat(threeWay.total()).contains(Money.ofPersisted(125_00, EUR, 2));

        GroupMatch.Verdict oneUnitShort =
                GroupMatch.decide(
                        item(99_99, EUR), VALUE_DATE, REMITTANCE, List.of(forty, sixty), true);
        assertThat(oneUnitShort.kind()).isEqualTo(GroupMatch.Kind.TOTAL_DIFFERS);

        // The gate's find (P8-TSK-016): with random ids the subset 40 + 60 was only SOMETIMES
        // first in id order, so a search walking the members in that order slipped past this
        // test. Every assignment of three fixed ids to the three amounts is judged, so any
        // order-based search meets the case where the subset leads.
        long[][] orders = {{1, 2, 3}, {1, 3, 2}, {2, 1, 3}, {2, 3, 1}, {3, 1, 2}, {3, 2, 1}};
        for (long[] ids : orders) {
            List<MatchEngine.HitFacts> members =
                    List.of(
                            candidateWithId(ids[0], 40_00, VALUE_DATE),
                            candidateWithId(ids[1], 60_00, VALUE_DATE),
                            candidateWithId(ids[2], 25_00, VALUE_DATE));
            GroupMatch.Verdict verdict =
                    GroupMatch.decide(item(100_00, EUR), VALUE_DATE, REMITTANCE, members, true);
            assertThat(verdict.kind())
                    .as("ids %s: the date's whole is 125.00 - 40 + 60 is never chosen",
                            java.util.Arrays.toString(ids))
                    .isEqualTo(GroupMatch.Kind.TOTAL_DIFFERS);
            assertThat(verdict.candidates()).hasSize(3);
        }
    }

    private static MatchEngine.HitFacts candidateWithId(
            long id, long amountMinor, LocalDate expectedBy) {
        return new MatchEngine.HitFacts(
                new UUID(0L, id),
                ExpectationKind.REMITTANCE,
                ExpectationDirection.INBOUND,
                Money.ofPersisted(amountMinor, EUR, 2),
                amountMinor,
                Instant.parse("2026-09-30T12:00:00Z"),
                expectedBy,
                Optional.empty(),
                "op-ref");
    }

    @Test
    @DisplayName("the screens run first: a touched candidate, another date, another kind,"
            + " direction or currency is no member - nothing left is NO_CANDIDATES")
    void theScreensRunBeforeTheTotal() {
        MatchEngine.HitFacts touched = candidate(100_00, 40_00, VALUE_DATE, EUR);
        MatchEngine.HitFacts emptied = candidate(100_00, 0, VALUE_DATE, EUR);
        MatchEngine.HitFacts otherDate = candidate(100_00, 100_00, VALUE_DATE.plusDays(1), EUR);
        MatchEngine.HitFacts otherCurrency = candidate(100_00, 100_00, VALUE_DATE, GBP);
        MatchEngine.HitFacts otherDirection =
                new MatchEngine.HitFacts(
                        UUID.randomUUID(), ExpectationKind.REMITTANCE,
                        ExpectationDirection.OUTBOUND, Money.ofPersisted(100_00, EUR, 2),
                        100_00, Instant.parse("2026-09-30T12:00:00Z"), VALUE_DATE,
                        Optional.empty(), "op-ref");
        MatchEngine.HitFacts otherKind =
                new MatchEngine.HitFacts(
                        UUID.randomUUID(), ExpectationKind.CARD_CAPTURE,
                        ExpectationDirection.INBOUND, Money.ofPersisted(100_00, EUR, 2),
                        100_00, Instant.parse("2026-09-30T12:00:00Z"), VALUE_DATE,
                        Optional.empty(), "op-ref");

        GroupMatch.Verdict verdict =
                GroupMatch.decide(
                        item(100_00, EUR), VALUE_DATE, REMITTANCE,
                        List.of(touched, emptied, otherDate, otherCurrency, otherDirection,
                                otherKind),
                        true);

        assertThat(verdict.kind()).isEqualTo(GroupMatch.Kind.NO_CANDIDATES);
        assertThat(verdict.candidates()).isEmpty();
        assertThat(verdict.total()).isEmpty();
    }

    @Test
    @DisplayName("a membership that moved under the lock is never judged, even when the"
            + " locked part would total exactly")
    void aMovedMembershipWaits() {
        MatchEngine.HitFacts whole = candidate(100_00, 100_00, VALUE_DATE, EUR);

        GroupMatch.Verdict verdict =
                GroupMatch.decide(
                        item(100_00, EUR), VALUE_DATE, REMITTANCE, List.of(whole), false);

        assertThat(verdict.kind()).isEqualTo(GroupMatch.Kind.MEMBERSHIP_MOVED);
        assertThat(verdict.candidates()).containsExactly(whole);
        // Nor is "nothing locked" concluded as NO_CANDIDATES: the unlocked newcomer may own
        // the line, so the grace leg must not park beside it.
        assertThat(GroupMatch.decide(item(100_00, EUR), VALUE_DATE, REMITTANCE, List.of(), false)
                        .kind())
                .isEqualTo(GroupMatch.Kind.MEMBERSHIP_MOVED);
    }

    @Test
    @DisplayName("the shuffled-order property: the same candidates in any order produce the"
            + " same verdict, byte for byte")
    void shuffledCandidatesDecideIdentically() {
        List<MatchEngine.HitFacts> candidates =
                new ArrayList<>(
                        List.of(
                                candidate(10_00, 10_00, VALUE_DATE, EUR),
                                candidate(20_00, 20_00, VALUE_DATE, EUR),
                                candidate(30_00, 30_00, VALUE_DATE, EUR),
                                candidate(40_00, 0, VALUE_DATE, EUR),
                                candidate(40_00, 40_00, VALUE_DATE.minusDays(1), EUR)));
        MatchEngine.ItemFacts line = item(60_00, EUR);
        GroupMatch.Verdict first =
                GroupMatch.decide(line, VALUE_DATE, REMITTANCE, candidates, true);

        Random random = new Random(2026_10_02L); // Seeded: the property is reproducible.
        for (int round = 0; round < 50; round++) {
            Collections.shuffle(candidates, random);
            assertThat(GroupMatch.decide(line, VALUE_DATE, REMITTANCE, candidates, true))
                    .as("round %s: order must not matter", round)
                    .isEqualTo(first);
        }
        assertThat(first.kind()).isEqualTo(GroupMatch.Kind.MATCH);
        assertThat(first.candidates()).hasSize(3);
    }

    // ----------------------------------------------------------------- fixtures

    private static MatchEngine.ItemFacts item(long minor, CurrencyCode currency) {
        return new MatchEngine.ItemFacts(
                UUID.randomUUID(),
                ExternalLineType.BANK_CREDIT,
                ExpectationDirection.INBOUND,
                Money.ofPersisted(minor, currency, 2),
                VALUE_DATE,
                Optional.empty(),
                false);
    }

    private static MatchEngine.HitFacts candidate(
            long amountMinor, long remainderMinor, LocalDate expectedBy,
            CurrencyCode currency) {
        return new MatchEngine.HitFacts(
                UUID.randomUUID(),
                ExpectationKind.REMITTANCE,
                ExpectationDirection.INBOUND,
                Money.ofPersisted(amountMinor, currency, 2),
                remainderMinor,
                Instant.parse("2026-09-30T12:00:00Z"),
                expectedBy,
                Optional.empty(),
                "op-ref");
    }
}
