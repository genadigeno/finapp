package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.app.api.OperatorCollection;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.StatementDerivation;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantCollections;
import com.finapp.merchant.MerchantStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operator's merchant collections' requests and pages, without a database (`P17-TSK-033`): the merchants' filters - a
 * standing's words, a name matched exactly and bounded - each collection's cursor its own and exact to the microsecond, the
 * transactions' window defaulted and bounded at the merchant statement's month, and the movement list's total order walked
 * once with no movement served twice or skipped. The reads, the permission, the records and the 404s are
 * {@code MerchantListsApiDatabaseTest}'s.
 */
@DisplayName("the operator's merchant collections (P17-TSK-033)")
class MerchantCollectionDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("a standing is the merchant's own word; a name is matched exactly, 1 to 200 characters, never refused for its content")
    void theMerchantsFilters() {
        assertThat(MerchantCollectionDesk.merchantFilter("SUSPENDED", null))
                .isEqualTo(new MerchantCollections.MerchantFilter(Optional.of(MerchantStatus.SUSPENDED), Optional.empty()));
        assertThat(MerchantCollectionDesk.merchantFilter(null, "Acme GmbH"))
                .isEqualTo(new MerchantCollections.MerchantFilter(Optional.empty(), Optional.of("Acme GmbH")));
        assertThat(MerchantCollectionDesk.merchantFilter(null, "a%_*").name())
                .as("a name is a value, never a pattern: matched as typed")
                .contains("a%_*");
        assertThat(MerchantCollectionDesk.merchantFilter(null, "x".repeat(Merchant.MAX_NAME_LENGTH)).name()).isPresent();

        assertRefused(() -> MerchantCollectionDesk.merchantFilter("active", null));
        assertRefused(() -> MerchantCollectionDesk.merchantFilter("DELETED", null));
        assertRefused(() -> MerchantCollectionDesk.merchantFilter(null, ""));
        assertRefused(() -> MerchantCollectionDesk.merchantFilter(null, "x".repeat(Merchant.MAX_NAME_LENGTH + 1)));
    }

    @Test
    @DisplayName("each collection's cursor is its own, carries the instant to the microsecond, and is refused anywhere else")
    void eachCursorIsItsOwn() {
        Instant onboarded = Instant.parse("2026-11-19T10:15:30.123456Z");
        UUID id = IDS.next();
        String merchants = OperatorCollection.cursor(MerchantCollectionDesk.MERCHANTS, onboarded.toString(), id.toString());
        assertThat(MerchantCollectionDesk.merchantCursor(merchants))
                .contains(new MerchantCollections.MerchantCursor(onboarded, id));
        assertThat(MerchantCollectionDesk.merchantCursor(null)).isEmpty();
        String payouts = OperatorCollection.cursor(MerchantCollectionDesk.PAYOUTS, onboarded.toString(), id.toString());
        assertThat(MerchantCollectionDesk.payoutCursor(payouts)).contains(new MerchantCollections.PayoutCursor(onboarded, id));
        String transactions = OperatorCollection.cursor(MerchantCollectionDesk.TRANSACTIONS, "2026-11-19", id.toString(), "EUR");
        assertThat(MerchantCollectionDesk.movementCursor(transactions))
                .contains(new MerchantTransactionReport.MovementCursor(LocalDate.parse("2026-11-19"), id, CurrencyCode.of("EUR")));

        assertRefused(() -> MerchantCollectionDesk.payoutCursor(merchants));
        assertRefused(() -> MerchantCollectionDesk.merchantCursor(payouts));
        assertRefused(() -> MerchantCollectionDesk.movementCursor(merchants));
        assertRefused(() -> MerchantCollectionDesk.merchantCursor(
                OperatorCollection.cursor(MerchantCollectionDesk.MERCHANTS, "yesterday", id.toString())));
        assertRefused(() -> MerchantCollectionDesk.movementCursor(
                OperatorCollection.cursor(MerchantCollectionDesk.TRANSACTIONS, "2026-11-19", id.toString(), "euro")));
        assertRefused(() -> MerchantCollectionDesk.movementCursor("not-a-cursor"));
    }

    @Test
    @DisplayName("the transactions' window: today and the thirty days before unless named, thirty-one days at most, never reversed")
    void theTransactionsWindow() {
        LocalDate today = LocalDate.parse("2026-11-19");
        assertThat(MerchantCollectionDesk.window(null, null, today))
                .isEqualTo(new MerchantCollectionDesk.Window(LocalDate.parse("2026-10-20"), today));
        assertThat(MerchantCollectionDesk.window("2026-10-01", "2026-10-31", today))
                .isEqualTo(new MerchantCollectionDesk.Window(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")));
        assertThat(MerchantCollectionDesk.window(null, "2026-02-28", today).from()).isEqualTo(LocalDate.parse("2026-01-29"));

        assertRefused(() -> MerchantCollectionDesk.window("2026-10-01", "2026-11-01", today));
        assertRefused(() -> MerchantCollectionDesk.window("2026-11-02", "2026-11-01", today));
        assertRefused(() -> MerchantCollectionDesk.window("2026-11-1", null, today));
        assertRefused(() -> MerchantCollectionDesk.window("2026-02-30", "2026-03-01", today));
    }

    @Test
    @DisplayName("the movement list's order is total - date, entry, then currency - and a walk serves every movement once")
    void theMovementListWalksOnce() {
        CurrencyCode eur = CurrencyCode.of("EUR");
        CurrencyCode gbp = CurrencyCode.of("GBP");
        UUID shared = IDS.next();
        List<StatementDerivation.StatementLine> eurLines = new ArrayList<>();
        List<StatementDerivation.StatementLine> gbpLines = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            for (int n = 0; n < 3; n++) {
                eurLines.add(line(IDS.next(), LocalDate.of(2026, 11, day), Money.ofMinorUnits(100 + n, eur)));
            }
        }
        // One entry on both payables, a capture's two lines on one, and a GBP movement on a day of its own.
        eurLines.add(line(shared, LocalDate.of(2026, 11, 3), Money.ofMinorUnits(7, eur)));
        gbpLines.add(line(shared, LocalDate.of(2026, 11, 3), Money.ofMinorUnits(9, gbp)));
        UUID capture = IDS.next();
        eurLines.add(line(capture, LocalDate.of(2026, 11, 4), Money.ofMinorUnits(1000, eur)));
        eurLines.add(line(capture, LocalDate.of(2026, 11, 4), Money.ofMinorUnits(30, eur)));
        gbpLines.add(line(IDS.next(), LocalDate.of(2026, 11, 6), Money.ofMinorUnits(5, gbp)));

        List<MerchantTransactionReport.EntryLines> all = MerchantTransactionReport.entries(List.of(
                new StatementDerivation.AccountStatement(LedgerAccountId.of(IDS.next()), Money.ofMinorUnits(0, eur), eurLines,
                        Money.ofMinorUnits(0, eur)),
                new StatementDerivation.AccountStatement(LedgerAccountId.of(IDS.next()), Money.ofMinorUnits(0, gbp), gbpLines,
                        Money.ofMinorUnits(0, gbp))));
        assertThat(all).as("one movement per entry per payable: the capture's two lines are one").hasSize(19);

        List<MerchantTransactionReport.EntryLines> walked = new ArrayList<>();
        Optional<MerchantTransactionReport.MovementCursor> after = Optional.empty();
        while (true) {
            List<MerchantTransactionReport.EntryLines> page = MerchantTransactionReport.pageOf(all, after, 4);
            walked.addAll(page);
            if (page.size() < 4) {
                break;
            }
            MerchantTransactionReport.EntryLines tail = page.get(page.size() - 1);
            after = Optional.of(new MerchantTransactionReport.MovementCursor(
                    tail.postingDate(), tail.entry().value(), tail.currency()));
        }
        assertThat(walked).hasSize(19).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        // The declared order, checked from the fields themselves - never through the comparator under test, which would
        // agree with itself however it ordered (P17-TSK-033's probe P3 found exactly that).
        for (int i = 1; i < walked.size(); i++) {
            MerchantTransactionReport.EntryLines before = walked.get(i - 1);
            MerchantTransactionReport.EntryLines next = walked.get(i);
            int byDate = before.postingDate().compareTo(next.postingDate());
            assertThat(byDate).as("posting date descending").isGreaterThanOrEqualTo(0);
            if (byDate == 0) {
                int byEntry = before.entry().value().toString().compareTo(next.entry().value().toString());
                assertThat(byEntry).as("then the entry, descending by its canonical text").isGreaterThanOrEqualTo(0);
                if (byEntry == 0) {
                    assertThat(before.currency().code()).as("then the currency, ascending").isLessThan(next.currency().code());
                }
            }
        }
        assertThat(walked.get(0).postingDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        int sharedAt = indexOf(walked, shared, eur);
        assertThat(walked.get(sharedAt + 1).entry().value())
                .as("an entry on two payables: EUR then GBP, adjacent")
                .isEqualTo(shared);
        assertThat(walked.get(sharedAt + 1).currency()).isEqualTo(gbp);
    }

    private static int indexOf(List<MerchantTransactionReport.EntryLines> walked, UUID entry, CurrencyCode currency) {
        for (int i = 0; i < walked.size(); i++) {
            if (walked.get(i).entry().value().equals(entry) && walked.get(i).currency().equals(currency)) {
                return i;
            }
        }
        throw new AssertionError("not walked: " + entry);
    }

    private static StatementDerivation.StatementLine line(UUID entry, LocalDate posted, Money amount) {
        return new StatementDerivation.StatementLine(JournalEntryId.of(entry), posted, posted, JournalEntryType.POSTING,
                "ref-" + entry, Direction.CREDIT, amount, false);
    }

    private static void assertRefused(ThrowableAssert.ThrowingCallable call) {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(call)
                .satisfies(refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
