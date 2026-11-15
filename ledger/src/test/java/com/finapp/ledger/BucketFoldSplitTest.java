package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.PositionBreakdown.Bucket;
import com.finapp.ledger.PositionBreakdown.Counterparty;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryOverflowException;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fold's split (`P16-REM-001`; ADR-0133 decision 3's hermetic property): a checkpoint - the buckets of every line below a
 * watermark - folded with the lines at or above it is the fold of every line, whatever the history, the split or the order the lines
 * arrive in, and refuses exactly where the whole fold refuses. That is the identity {@link CheckpointedPositionBreakdown} rests on; the
 * watermark's own argument, that the lines below it are final, is {@code PositionCheckpointDatabaseTest}'s.
 */
@DisplayName("a checkpoint and the lines since it fold to the whole history (P16-REM-001)")
class BucketFoldSplitTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LedgerAccountId ACCOUNT =
            LedgerAccountId.next(new IdGenerator(Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC), new SecureRandom()));
    private static final int HISTORIES = 500;

    private static final List<Optional<Counterparty>> FACING = List.of(
            Optional.empty(),
            Optional.of(new Counterparty(AccountPurpose.SETTLEMENT_CLEARING, Direction.DEBIT)),
            Optional.of(new Counterparty(AccountPurpose.SETTLEMENT_CLEARING, Direction.CREDIT)),
            Optional.of(new Counterparty(AccountPurpose.PAYOUT_CLEARING, Direction.CREDIT)));

    private static final List<Optional<String>> NAMESPACES = List.of(Optional.empty(), Optional.of("bnpl.financing:"), Optional.of("bnpl.refund:"));

    private static List<Bucket> history(Random random) {
        List<Bucket> lines = new ArrayList<>();
        int size = random.nextInt(60);
        for (int index = 0; index < size; index++) {
            List<Optional<AdjustmentOrigin>> origins = new ArrayList<>();
            origins.add(Optional.empty());
            origins.add(Optional.empty());
            for (AdjustmentOrigin origin : AdjustmentOrigin.values()) {
                origins.add(Optional.of(origin));
            }
            lines.add(Bucket.of(
                    random.nextBoolean() ? Direction.DEBIT : Direction.CREDIT,
                    Money.ofPersisted(1 + random.nextInt(1_000_000), EUR, 2),
                    origins.get(random.nextInt(origins.size())),
                    FACING.get(random.nextInt(FACING.size())),
                    NAMESPACES.get(random.nextInt(NAMESPACES.size()))));
        }
        return lines;
    }

    private static BucketFold fold(List<Bucket> lines) {
        BucketFold fold = new BucketFold(EUR);
        lines.forEach(fold::add);
        return fold;
    }

    /** A checkpoint below {@code split}, then the lines at or above it: what the checkpointed read folds. */
    private static BucketFold split(List<Bucket> lines, int split) {
        BucketFold checkpoint = fold(lines.subList(0, split));
        BucketFold read = new BucketFold(EUR);
        checkpoint.buckets().values().forEach(read::add);
        lines.subList(split, lines.size()).forEach(read::add);
        return read;
    }

    private static Map<String, Money> byKey(PositionBreakdown.Breakdown breakdown) {
        return breakdown.buckets().stream().collect(Collectors.toMap(BucketFold::key, Bucket::total));
    }

    @Test
    @DisplayName("for every history and every split, the checkpoint and the lines since give the whole fold's position and buckets")
    void everySplitIsTheWholeFold() {
        Random random = new Random(20261115L);
        int compared = 0;
        for (int history = 0; history < HISTORIES; history++) {
            List<Bucket> lines = history(random);
            PositionBreakdown.Breakdown whole = fold(lines).breakdown(ACCOUNT, NormalBalance.CREDIT);
            for (int split = 0; split <= lines.size(); split++) {
                PositionBreakdown.Breakdown read = split(lines, split).breakdown(ACCOUNT, NormalBalance.CREDIT);
                assertThat(read.position()).as("history %d split at %d", history, split).isEqualTo(whole.position());
                assertThat(byKey(read)).as("history %d split at %d", history, split).isEqualTo(byKey(whole));
                compared++;
            }
        }
        assertThat(compared).as("splits compared").isGreaterThan(HISTORIES * 10);
    }

    @Test
    @DisplayName("the order lines arrive in changes nothing: commit order is not insertion order")
    void theOrderChangesNothing() {
        Random random = new Random(7L);
        for (int history = 0; history < HISTORIES; history++) {
            List<Bucket> lines = history(random);
            List<Bucket> shuffled = new ArrayList<>(lines);
            Collections.shuffle(shuffled, random);
            PositionBreakdown.Breakdown whole = fold(lines).breakdown(ACCOUNT, NormalBalance.DEBIT);
            PositionBreakdown.Breakdown reordered = split(shuffled, random.nextInt(shuffled.size() + 1)).breakdown(ACCOUNT, NormalBalance.DEBIT);
            assertThat(reordered.position()).isEqualTo(whole.position());
            assertThat(byKey(reordered)).isEqualTo(byKey(whole));
        }
    }

    @Test
    @DisplayName("a mixed scale is refused by the split fold as by the whole, on either side of the watermark (INV-MON-03)")
    void aMixedScaleIsRefusedEitherSide() {
        Bucket cents = Bucket.of(Direction.CREDIT, Money.ofPersisted(100, EUR, 2), Optional.empty(), Optional.empty(), Optional.empty());
        Bucket mills = Bucket.of(Direction.CREDIT, Money.ofPersisted(100, EUR, 3), Optional.empty(), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> fold(List.of(cents, mills))).isInstanceOf(ScaleMismatchException.class);
        assertThatThrownBy(() -> split(List.of(cents, mills), 1)).isInstanceOf(ScaleMismatchException.class);
    }

    @Test
    @DisplayName("an overflowing side is refused by the split fold as by the whole, at the same total (INV-MON-06)")
    void anOverflowIsRefusedEitherSide() {
        Bucket big = Bucket.of(Direction.CREDIT, Money.ofPersisted(Long.MAX_VALUE - 1, EUR, 2), Optional.empty(), Optional.empty(), Optional.empty());
        Bucket two = Bucket.of(Direction.CREDIT, Money.ofPersisted(2, EUR, 2), Optional.empty(), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> fold(List.of(big, two))).isInstanceOf(MonetaryOverflowException.class);
        assertThatThrownBy(() -> split(List.of(big, two), 1)).isInstanceOf(MonetaryOverflowException.class);
    }
}
