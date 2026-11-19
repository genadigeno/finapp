package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentReasonCode;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerCollections;
import com.finapp.ledger.NormalBalance;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.ProjectionRebuildId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger collections' request and views, without a database (`P17-TSK-022`): the search's window required, inclusive and at
 * most 31 posting dates; a reference accepted only identifier-shaped; the register's vocabulary; each collection's cursor its own;
 * the queues holding only {@code PROPOSED}; the pending-decision shape with the caller's mark; and a rebuild summarised by which
 * figures drifted, never by its reason. The reads, their order and the records are {@code LedgerCollectionsDatabaseTest}'s and
 * {@code LedgerReadGapsApiDatabaseTest}'s.
 */
@DisplayName("the ledger collections' request and views (P17-TSK-022)")
class LedgerCollectionDeskTest {

    /** The ledger's identifiers are UUIDv7 (ADR-0013); its typed ids refuse any other. */
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("the search's window: both dates required, YYYY-MM-DD, to on or after from, at most 31 posting dates inclusive")
    void theWindowIsRequiredAndBounded() {
        LedgerCollections.EntryFilter month = LedgerCollectionDesk.entryFilter("2026-01-01", "2026-01-31", null, null);
        assertThat(month.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(month.to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(LedgerCollectionDesk.entryFilter("2026-11-19", "2026-11-19", null, null).to())
                .as("one day is a window").isEqualTo(LocalDate.of(2026, 11, 19));

        for (String[] window : new String[][] {{null, "2026-01-31"}, {"2026-01-01", null}, {"2026-01-01", "2026-02-01"},
                {"2026-02-01", "2026-01-31"}, {"2026-1-1", "2026-01-31"}, {"2026-02-30", "2026-03-01"},
                {"2026-01-01T00:00:00Z", "2026-01-02"}}) {
            assertRefused(() -> LedgerCollectionDesk.entryFilter(window[0], window[1], null, null));
        }
    }

    @Test
    @DisplayName("a reference is matched only identifier-shaped - free text, whitespace and over-long values are refused - and an"
            + " account is a well-formed id")
    void aReferenceIsAnIdentifier() {
        UUID account = IDS.next();
        LedgerCollections.EntryFilter filter = LedgerCollectionDesk.entryFilter("2026-01-01", "2026-01-02",
                "transfer:" + IDS.next(), account.toString());
        assertThat(filter.reference()).get().asString().startsWith("transfer:");
        assertThat(filter.account()).contains(LedgerAccountId.of(account));
        assertThat(LedgerCollectionDesk.entryFilter("2026-01-01", "2026-01-02", "ACCOUNTING.PPA_2026-01", null).reference())
                .isPresent();

        for (String reference : new String[] {"rent for John", "transfer: x", "", "-leading", "a".repeat(201), "x%25y",
                "name@example.com"}) {
            assertRefused(() -> LedgerCollectionDesk.entryFilter("2026-01-01", "2026-01-02", reference, null));
        }
        assertRefused(() -> LedgerCollectionDesk.entryFilter("2026-01-01", "2026-01-02", null, "not-an-id"));
    }

    @Test
    @DisplayName("the register's filters: a purpose of the ledger's vocabulary, a supported ISO currency; anything else refused")
    void theRegistersFilters() {
        LedgerCollections.AccountFilter filter = LedgerCollectionDesk.accountFilter("CUSTOMER_WALLET", "EUR");
        assertThat(filter.purpose()).contains(AccountPurpose.CUSTOMER_WALLET);
        assertThat(filter.currency()).contains("EUR");
        assertThat(LedgerCollectionDesk.accountFilter(null, null))
                .isEqualTo(new LedgerCollections.AccountFilter(Optional.empty(), Optional.empty()));

        assertRefused(() -> LedgerCollectionDesk.accountFilter("WALLET", null));
        assertRefused(() -> LedgerCollectionDesk.accountFilter("customer_wallet", null));
        assertRefused(() -> LedgerCollectionDesk.accountFilter(null, "eur"));
        assertRefused(() -> LedgerCollectionDesk.accountFilter(null, "EURO"));
    }

    @Test
    @DisplayName("each collection reads only its own cursor: another's, a malformed one or one whose key will not parse is refused")
    void eachCursorIsItsCollections() {
        UUID id = IDS.next();
        String accounts = OperatorCollection.cursor(LedgerCollectionDesk.ACCOUNTS, "FEE_REVENUE", "EUR", id.toString());
        String entries = OperatorCollection.cursor(LedgerCollectionDesk.ENTRIES, "2026-01-31", id.toString());
        String adjustments = OperatorCollection.cursor(LedgerCollectionDesk.ADJUSTMENTS, "1763510400000000", id.toString());

        assertThat(LedgerCollectionDesk.accountCursor(accounts))
                .contains(new LedgerCollections.AccountCursor(AccountPurpose.FEE_REVENUE, "EUR", id));
        assertThat(LedgerCollectionDesk.entryCursor(entries))
                .contains(new LedgerCollections.EntryCursor(LocalDate.of(2026, 1, 31), id));
        assertThat(LedgerCollectionDesk.pendingCursor(LedgerCollectionDesk.ADJUSTMENTS, adjustments)).get()
                .extracting(LedgerCollections.PendingCursor::proposedAt).isEqualTo(Instant.parse("2025-11-19T00:00:00Z"));
        assertThat(LedgerCollectionDesk.accountCursor(null)).isEmpty();

        assertRefused(() -> LedgerCollectionDesk.accountCursor(entries));
        assertRefused(() -> LedgerCollectionDesk.entryCursor(accounts));
        assertRefused(() -> LedgerCollectionDesk.pendingCursor(LedgerCollectionDesk.REBUILDS, adjustments));
        assertRefused(() -> LedgerCollectionDesk.accountCursor(
                OperatorCollection.cursor(LedgerCollectionDesk.ACCOUNTS, "NO_SUCH", "EUR", id.toString())));
        assertRefused(() -> LedgerCollectionDesk.accountCursor(
                OperatorCollection.cursor(LedgerCollectionDesk.ACCOUNTS, "FEE_REVENUE", "eur", id.toString())));
        assertRefused(() -> LedgerCollectionDesk.entryCursor(
                OperatorCollection.cursor(LedgerCollectionDesk.ENTRIES, "2026-02-30", id.toString())));
        assertRefused(() -> LedgerCollectionDesk.pendingCursor(LedgerCollectionDesk.ADJUSTMENTS,
                OperatorCollection.cursor(LedgerCollectionDesk.ADJUSTMENTS, "soon", id.toString())));
        assertRefused(() -> LedgerCollectionDesk.entryCursor("not-a-cursor"));
    }

    @Test
    @DisplayName("a queue holds only PROPOSED: absent is PROPOSED, anything else refused; a limit outside 1..200 refused")
    void aQueueHoldsWhatAwaitsADecision() {
        assertThat(LedgerCollectionDesk.queue(null, null)).isEqualTo(50);
        assertThat(LedgerCollectionDesk.queue("PROPOSED", "200")).isEqualTo(200);
        assertRefused(() -> LedgerCollectionDesk.queue("APPROVED", null));
        assertRefused(() -> LedgerCollectionDesk.queue("proposed", null));
        assertRefused(() -> LedgerCollectionDesk.queue(null, "201"));
        assertRefused(() -> LedgerCollectionDesk.queue(null, "0"));
    }

    @Test
    @DisplayName("a pending adjustment in the pending-decision shape: its kind, proposer, reason code, the proposal its own"
            + " subject, its posting date, and the caller's mark only on the caller's own")
    void aPendingAdjustmentsShape() {
        UUID id = IDS.next();
        LedgerCollections.PendingAdjustment row = new LedgerCollections.PendingAdjustment(AdjustmentProposalId.of(id), "operator-1",
                Instant.parse("2026-11-19T10:15:30.123456Z"), AdjustmentReasonCode.MANUAL_CORRECTION, LocalDate.of(2026, 11, 19));

        LedgerCollectionDesk.PendingLedgerAdjustment theirs = LedgerCollectionDesk.adjustmentView(row, "operator-2");
        assertThat(theirs).isEqualTo(new LedgerCollectionDesk.PendingLedgerAdjustment(id.toString(), "ledger.ManualAdjustment",
                "operator-1", "2026-11-19T10:15:30.123456Z", "MANUAL_CORRECTION", id.toString(), "LEDGER_ADJUSTMENT",
                "2026-11-19", false));
        assertThat(LedgerCollectionDesk.adjustmentView(row, "operator-1").proposedByCaller()).isTrue();
    }

    @Test
    @DisplayName("a pending rebuild: its subject the account, summarised by which figures drifted - a missing row, or POSTED,"
            + " HOLDS and APPLIED_ENTRIES by name - and marked only for its proposer")
    void aPendingRebuildsShape() {
        UUID id = IDS.next();
        UUID account = IDS.next();
        LedgerCollections.PendingRebuild drifted = rebuild(id, account, true, true, false, true);

        LedgerCollectionDesk.PendingProjectionRebuild view = LedgerCollectionDesk.rebuildView(drifted, "operator-2");
        assertThat(view).isEqualTo(new LedgerCollectionDesk.PendingProjectionRebuild(id.toString(), "ledger.ProjectionRebuild",
                "operator-1", "2026-11-19T10:15:30Z", "DRIFTED:POSTED,APPLIED_ENTRIES", account.toString(), "LEDGER_ACCOUNT",
                "EUR", false));
        assertThat(LedgerCollectionDesk.rebuildView(drifted, "operator-1").proposedByCaller()).isTrue();
        assertThat(LedgerCollectionDesk.drift(rebuild(id, account, false, true, true, true))).isEqualTo("NO_PROJECTION_ROW");
        assertThat(LedgerCollectionDesk.drift(rebuild(id, account, true, false, true, false))).isEqualTo("DRIFTED:HOLDS");
    }

    @Test
    @DisplayName("an account and an entry as they leave: identifiers, codes and dates; an owner a reference or absent; amounts"
            + " exact decimal strings with their currency, trailing zeros kept")
    void theViews() {
        UUID id = IDS.next();
        UUID owner = IDS.next();
        LedgerCollectionDesk.LedgerAccountView wallet = LedgerCollectionDesk.accountView(new LedgerCollections.AccountRow(id,
                AccountPurpose.CUSTOMER_WALLET, OwnerKind.CUSTOMER, Optional.of(owner), "EUR", AccountType.LIABILITY,
                NormalBalance.CREDIT, LedgerAccountStatus.ACTIVE, Instant.parse("2026-01-02T03:04:05Z")));
        assertThat(wallet).isEqualTo(new LedgerCollectionDesk.LedgerAccountView(id.toString(), "CUSTOMER_WALLET", "CUSTOMER",
                owner.toString(), "EUR", "LIABILITY", "CREDIT", "ACTIVE", "2026-01-02T03:04:05Z"));
        assertThat(LedgerCollectionDesk.accountView(new LedgerCollections.AccountRow(id, AccountPurpose.FEE_REVENUE,
                OwnerKind.OPERATIONAL, Optional.empty(), "EUR", AccountType.REVENUE, NormalBalance.CREDIT,
                LedgerAccountStatus.ACTIVE, Instant.EPOCH)).ownerRef()).isNull();

        UUID reverses = IDS.next();
        LedgerCollectionDesk.JournalEntryHeadView entry = LedgerCollectionDesk.entryView(new LedgerCollections.EntryHead(id,
                JournalEntryType.REVERSAL, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 30),
                Instant.parse("2026-01-31T23:59:59.999999Z"), Optional.of(reverses), 4,
                List.of(new LedgerCollections.EntryAmount("EUR", new BigDecimal("10.50")),
                        new LedgerCollections.EntryAmount("JPY", new BigDecimal("1500")),
                        new LedgerCollections.EntryAmount("BHD", new BigDecimal("0.100")))));
        assertThat(entry).isEqualTo(new LedgerCollectionDesk.JournalEntryHeadView(id.toString(), "REVERSAL", "2026-01-31",
                "2026-01-30", "2026-01-31T23:59:59.999999Z", reverses.toString(), 4,
                List.of(new LedgerCollectionDesk.EntryAmountView("EUR", "10.50"),
                        new LedgerCollectionDesk.EntryAmountView("JPY", "1500"),
                        new LedgerCollectionDesk.EntryAmountView("BHD", "0.100"))));
    }

    private static LedgerCollections.PendingRebuild rebuild(UUID id, UUID account, boolean row, boolean posted, boolean holds,
            boolean applied) {
        return new LedgerCollections.PendingRebuild(ProjectionRebuildId.of(id), LedgerAccountId.of(account), "EUR", "operator-1",
                Instant.parse("2026-11-19T10:15:30Z"), row, posted, holds, applied);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
