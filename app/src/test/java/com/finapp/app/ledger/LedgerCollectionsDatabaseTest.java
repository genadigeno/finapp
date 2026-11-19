package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerCollections;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerCollections;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ledger's operator collections on PostgreSQL (`P17-TSK-022`, gaps G-110, G-111, G-112): the plans ledger {@code V039}'s
 * indexes, {@code V029}'s and {@code V004}'s give each read at a volume where a scan would cost - recorded in
 * {@code OPERATIONS_RUNBOOK.md} section 11.48; each keyset walk serving every row once in its declared order; an entry's movement
 * per currency exact at the lines' own scale; and each queue holding only what its door decides.
 *
 * <p>Every row here is written in one transaction that is rolled back, so the shared database keeps none: the journal is
 * append-only, and its deferred balance and has-lines triggers never fire. The posting dates are in 2090 and 2091, after any
 * posting cutoff a suite leaves behind.
 */
@Tag("database")
@DisplayName("the ledger's operator collections on PostgreSQL (P17-TSK-022)")
class LedgerCollectionsDatabaseTest {

    private static final int ENTRIES = 40_000;
    private static final int WALLETS = 20_000;
    private static final int DECIDED = 20_000;

    private final JdbcLedgerCollections collections = new JdbcLedgerCollections();

    @Test
    @DisplayName("each read is an index range in its declared order with no sort and no scan: the window, a reference, an account,"
            + " a continued page, the register and the two queues; plans printed for the runbook")
    void theIndexesServeTheReads() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                UUID busy = account(statement, "EUR");
                UUID other = account(statement, "EUR");
                UUID rare = account(statement, "EUR");
                statement.executeUpdate(wallets(WALLETS));
                statement.executeUpdate(entries("p17-022-volume", ENTRIES, "2090-01-01", 20));
                statement.executeUpdate(lines("p17-022-volume", busy, other, rare, 1000));
                statement.executeUpdate(decidedProposals(DECIDED));
                statement.executeUpdate(proposal("MANUAL", "MANUAL_CORRECTION", "operator-a"));
                statement.executeUpdate(decidedRebuilds(other, DECIDED));
                statement.executeUpdate(rebuild(rare, "operator-a", true));
                for (String table : new String[] {"journal_entry", "journal_line", "ledger_account", "adjustment_proposal",
                        "projection_rebuild"}) {
                    statement.execute("ANALYZE ledger." + table);
                }
                String entry = "SELECT entry.id FROM ledger.journal_entry entry WHERE entry.posting_date >= '2090-01-01'"
                        + " AND entry.posting_date <= '2090-01-31'";
                String order = " ORDER BY entry.posting_date DESC, entry.id DESC LIMIT 51";
                String window = plan(statement, entry + order);
                String reference = plan(statement, entry + " AND entry.reference = 'p17-022-volume-777'" + order);
                String byAccount = plan(statement, entry + " AND EXISTS (SELECT 1 FROM ledger.journal_line line WHERE"
                        + " line.entry_id = entry.id AND line.ledger_account_id = '" + rare + "')" + order);
                String byBusyAccount = plan(statement, entry + " AND EXISTS (SELECT 1 FROM ledger.journal_line line WHERE"
                        + " line.entry_id = entry.id AND line.ledger_account_id = '" + busy + "')" + order);
                String continued = plan(statement, entry + " AND (entry.posting_date, entry.id) < ('2090-01-10',"
                        + " '00000000-0000-0000-0000-000000000000'::uuid)" + order);
                String account = "SELECT id FROM ledger.ledger_account WHERE TRUE";
                String accountOrder = " ORDER BY purpose, currency, id LIMIT 51";
                String register = plan(statement, account + accountOrder);
                String registerByPurpose = plan(statement, account + " AND purpose = 'FEE_REVENUE'" + accountOrder);
                String registerCommon = plan(statement, account + " AND purpose = 'CUSTOMER_WALLET' AND currency ="
                        + " 'GBP'::char(3)" + accountOrder);
                String registerContinued = plan(statement, account + " AND purpose = 'CUSTOMER_WALLET' AND (purpose, currency,"
                        + " id) > ('CUSTOMER_WALLET', 'EUR'::char(3), '00000000-0000-0000-0000-000000000000'::uuid)"
                        + accountOrder);
                String adjustments = plan(statement, "SELECT id FROM ledger.adjustment_proposal WHERE status = 'PROPOSED'"
                        + " AND origin = 'MANUAL' ORDER BY proposed_at, id LIMIT 51");
                String rebuilds = plan(statement, "SELECT id FROM ledger.projection_rebuild WHERE status = 'PROPOSED'"
                        + " ORDER BY proposed_at, id LIMIT 51");
                System.out.println("P17-TSK-022 plan, window:\n" + window + "\nP17-TSK-022 plan, reference:\n" + reference
                        + "\nP17-TSK-022 plan, account:\n" + byAccount + "\nP17-TSK-022 plan, busy account:\n" + byBusyAccount
                        + "\nP17-TSK-022 plan, continued:\n" + continued
                        + "\nP17-TSK-022 plan, register:\n" + register + "\nP17-TSK-022 plan, register by purpose:\n"
                        + registerByPurpose + "\nP17-TSK-022 plan, register common filter:\n" + registerCommon
                        + "\nP17-TSK-022 plan, register continued:\n" + registerContinued
                        + "\nP17-TSK-022 plan, adjustments:\n" + adjustments + "\nP17-TSK-022 plan, rebuilds:\n" + rebuilds);

                assertThat(window).contains("journal_entry_by_posting_date").contains("Backward").doesNotContain("Seq Scan");
                assertThat(sorts(window)).as("no sort of the window").isZero();
                assertThat(reference).contains("journal_entry_by_reference").doesNotContain("Seq Scan");
                assertThat(sorts(reference)).as("a reference's entries are read in the index's order").isZero();
                // A rare account's entries come through its own lines - an index leading with the account (V004's or V038's) -
                // and only those rows are sorted; a busy account's walk the window, probing its lines, and stop at the page.
                assertThat(byAccount).containsPattern("journal_line_by_account(_and_transaction)? ").doesNotContain("Seq Scan");
                assertThat(byBusyAccount).contains("journal_entry_by_posting_date").doesNotContain("Seq Scan");
                assertThat(sorts(byBusyAccount)).as("a busy account's page is the window's order").isZero();
                assertThat(continued).contains("journal_entry_by_posting_date").doesNotContain("Seq Scan");
                assertThat(sorts(continued)).as("no sort of a continued page").isZero();
                for (String plan : List.of(register, registerByPurpose, registerContinued)) {
                    assertThat(plan).contains("ledger_account_register").doesNotContain("Seq Scan");
                    assertThat(sorts(plan)).as("the register is read in the index's order").isZero();
                }
                // A filter a third of the wallets match: the planner may walk the key, the order then the id's alone - never a
                // scan, never a sort.
                assertThat(registerCommon).doesNotContain("Seq Scan");
                assertThat(sorts(registerCommon)).isZero();
                assertThat(adjustments).contains("adjustment_proposal_pending_manual").doesNotContain("Seq Scan");
                assertThat(rebuilds).contains("projection_rebuild_pending").doesNotContain("Seq Scan");
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("a keyset walk serves each entry of the window once, newest posting date first, the id breaking the tie; a"
            + " reference and an account narrow it; each entry's movement exact per currency at the lines' scale")
    void theSearchWalksOnceAndSumsExactly() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                String marker = "p17-022-walk-" + UUID.randomUUID().toString().substring(0, 8);
                UUID eurA = account(statement, "EUR");
                UUID eurB = account(statement, "EUR");
                UUID jpyA = account(statement, "JPY");
                UUID jpyB = account(statement, "JPY");
                statement.executeUpdate(entries(marker, 7, "2091-02-01", 3));
                // Every entry 10.50 EUR from A to B; entry 3 also 1500 JPY from A to B, four lines.
                statement.executeUpdate("INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction,"
                        + " amount_minor, currency, scale, seq) SELECT gen_random_uuid(), entry.id, line.account, line.direction,"
                        + " line.minor, line.currency, line.scale, line.seq FROM ledger.journal_entry entry"
                        + " CROSS JOIN (VALUES ('" + eurA + "'::uuid, 'DEBIT', 1050, 'EUR', 2, 0),"
                        + " ('" + eurB + "'::uuid, 'CREDIT', 1050, 'EUR', 2, 1)) AS line (account, direction, minor, currency,"
                        + " scale, seq) WHERE entry.idempotency_scope LIKE '" + marker + ":%'");
                statement.executeUpdate("INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction,"
                        + " amount_minor, currency, scale, seq) SELECT gen_random_uuid(), entry.id, line.account, line.direction,"
                        + " 1500, 'JPY', 0, line.seq FROM ledger.journal_entry entry CROSS JOIN (VALUES ('" + jpyA + "'::uuid,"
                        + " 'DEBIT', 2), ('" + jpyB + "'::uuid, 'CREDIT', 3)) AS line (account, direction, seq)"
                        + " WHERE entry.idempotency_scope = '" + marker + ":3'");
                LedgerCollections.EntryFilter window = new LedgerCollections.EntryFilter(LocalDate.parse("2091-02-01"),
                        LocalDate.parse("2091-02-03"), Optional.empty(), Optional.empty());

                List<LedgerCollections.EntryHead> walked = walkEntries(root, window, 3);

                assertThat(walked).hasSize(7);
                assertThat(new HashSet<>(walked.stream().map(LedgerCollections.EntryHead::id).toList())).hasSize(7);
                // PostgreSQL orders uuid by its bytes, the lowercase hex text's order - not UUID.compareTo's signed longs.
                assertThat(walked).isSortedAccordingTo(Comparator.comparing(LedgerCollections.EntryHead::postingDate)
                        .thenComparing((LedgerCollections.EntryHead head) -> head.id().toString()).reversed());
                LedgerCollections.EntryHead fx = walked.stream().filter(head -> head.lines() == 4).findFirst().orElseThrow();
                assertThat(fx.amounts()).containsExactly(new LedgerCollections.EntryAmount("EUR", new BigDecimal("10.50")),
                        new LedgerCollections.EntryAmount("JPY", new BigDecimal("1500")));
                assertThat(walked).filteredOn(head -> head.lines() == 2).hasSize(6).allSatisfy(head -> assertThat(head.amounts())
                        .containsExactly(new LedgerCollections.EntryAmount("EUR", new BigDecimal("10.50"))));

                assertThat(walkEntries(root, new LedgerCollections.EntryFilter(window.from(), window.to(),
                        Optional.of(marker + "-3"), Optional.empty()), 3)).singleElement().isEqualTo(fx);
                assertThat(walkEntries(root, new LedgerCollections.EntryFilter(window.from(), window.to(), Optional.empty(),
                        Optional.of(LedgerAccountId.of(jpyB))), 3)).singleElement().isEqualTo(fx);
                assertThat(walkEntries(root, new LedgerCollections.EntryFilter(window.from(), window.from(), Optional.empty(),
                        Optional.of(LedgerAccountId.of(eurA))), 50)).as("n = 3 and 6 post on the window's first date")
                        .hasSize(2).allSatisfy(head -> assertThat(head.postingDate()).isEqualTo(window.from()));
                assertThat(walkEntries(root, new LedgerCollections.EntryFilter(window.from(), window.to(),
                        Optional.of(marker + "-99"), Optional.empty()), 3)).isEmpty();
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("the register walks each account once in (purpose, currency, id) order, the platform's own and the owned ones;"
            + " a purpose and a currency narrow it")
    void theRegisterWalksOnceInItsOrder() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                statement.executeUpdate(wallets(30));
                List<UUID> seeded = new ArrayList<>();
                // now() is this transaction's start, every account it created stamped with it.
                try (ResultSet rows = statement.executeQuery("SELECT id FROM ledger.ledger_account WHERE created_at = now()")) {
                    while (rows.next()) {
                        seeded.add(rows.getObject(1, UUID.class));
                    }
                }
                assertThat(seeded).hasSize(30);

                List<LedgerCollections.AccountRow> wallets = walkAccounts(root, new LedgerCollections.AccountFilter(
                        Optional.of(AccountPurpose.CUSTOMER_WALLET), Optional.empty()), 7);
                assertThat(wallets).extracting(LedgerCollections.AccountRow::id).containsAll(seeded).doesNotHaveDuplicates();
                assertThat(wallets).allSatisfy(row -> {
                    assertThat(row.purpose()).isEqualTo(AccountPurpose.CUSTOMER_WALLET);
                    assertThat(row.ownerRef()).isPresent();
                });
                assertThat(wallets).isSortedAccordingTo(Comparator.comparing(LedgerCollections.AccountRow::currency)
                        .thenComparing((LedgerCollections.AccountRow row) -> row.id().toString()));
                assertThat(walkAccounts(root, new LedgerCollections.AccountFilter(Optional.of(AccountPurpose.CUSTOMER_WALLET),
                        Optional.of("GBP")), 4)).extracting(LedgerCollections.AccountRow::currency).containsOnly("GBP")
                        .hasSizeGreaterThanOrEqualTo(10);

                List<LedgerCollections.AccountRow> all = walkAccounts(root, new LedgerCollections.AccountFilter(Optional.empty(),
                        Optional.empty()), 200);
                assertThat(all).extracting(LedgerCollections.AccountRow::id).containsAll(seeded).doesNotHaveDuplicates();
                assertThat(all).isSortedAccordingTo(Comparator.comparing((LedgerCollections.AccountRow row) -> row.purpose().name())
                        .thenComparing(LedgerCollections.AccountRow::currency)
                        .thenComparing((LedgerCollections.AccountRow row) -> row.id().toString()));
                assertThat(all).as("the platform's own accounts are in the register, owned by no one")
                        .anySatisfy(row -> assertThat(row.ownerRef()).isEmpty());
            } finally {
                root.rollback();
            }
        }
    }

    @Test
    @DisplayName("each queue walks its PROPOSED rows once, oldest first: the adjustments' only MANUAL ones - a reconciliation's,"
            + " an accounting correction's and a decided one never; the rebuilds' with which figures drifted")
    void theQueuesHoldWhatTheirDoorsDecide() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try (Statement statement = root.createStatement()) {
                List<UUID> manual = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    manual.add(id(statement, proposal("MANUAL", "MANUAL_CORRECTION", "operator-" + i) + " RETURNING id"));
                }
                UUID reconciliation = id(statement, proposal("RECONCILIATION", "RECONCILIATION_WRITE_OFF", "operator-r")
                        + " RETURNING id");
                UUID accounting = id(statement, proposal("ACCOUNTING", "PRIOR_PERIOD_ADJUSTMENT", "operator-c") + " RETURNING id");
                UUID decided = id(statement, decidedProposals(1) + " RETURNING id");
                UUID missing = account(statement, "EUR");
                UUID drifting = account(statement, "EUR");
                UUID standing = id(statement, rebuild(missing, "operator-a", false) + " RETURNING id");
                UUID drifted = id(statement, rebuild(drifting, "operator-b", true) + " RETURNING id");
                UUID rejected = id(statement, decidedRebuilds(drifting, 1) + " RETURNING id");

                List<LedgerCollections.PendingAdjustment> adjustments = LedgerCollectionsDatabaseTest.<LedgerCollections.PendingAdjustment,
                        LedgerCollections.PendingCursor>walk(cursor -> collections.pendingAdjustments(root,
                        cursor, 2), row -> new LedgerCollections.PendingCursor(row.proposedAt(), row.id().value()));
                assertThat(adjustments).extracting(row -> row.id().value()).containsAll(manual).doesNotHaveDuplicates()
                        .doesNotContain(reconciliation, accounting, decided);
                assertThat(adjustments).isSortedAccordingTo(Comparator.comparing(LedgerCollections.PendingAdjustment::proposedAt)
                        .thenComparing((LedgerCollections.PendingAdjustment row) -> row.id().value().toString()));

                List<LedgerCollections.PendingRebuild> rebuilds = LedgerCollectionsDatabaseTest.<LedgerCollections.PendingRebuild,
                        LedgerCollections.PendingCursor>walk(cursor -> collections.pendingRebuilds(root, cursor, 2),
                        row -> new LedgerCollections.PendingCursor(row.proposedAt(), row.id().value()));
                assertThat(rebuilds).extracting(row -> row.id().value()).contains(standing, drifted).doesNotContain(rejected)
                        .doesNotHaveDuplicates();
                LedgerCollections.PendingRebuild noRow = rebuilds.stream().filter(row -> row.id().value().equals(standing))
                        .findFirst().orElseThrow();
                assertThat(noRow.rowObserved()).isFalse();
                LedgerCollections.PendingRebuild moved = rebuilds.stream().filter(row -> row.id().value().equals(drifted))
                        .findFirst().orElseThrow();
                assertThat(moved.rowObserved()).isTrue();
                assertThat(moved.postedDrifted()).as("observed 100, the journal derives 250").isTrue();
                assertThat(moved.holdsDrifted()).isFalse();
                assertThat(moved.appliedEntriesDrifted()).isTrue();
                assertThat(LedgerCollectionDesk.drift(moved)).isEqualTo("DRIFTED:POSTED,APPLIED_ENTRIES");
            } finally {
                root.rollback();
            }
        }
    }

    // ------------------------------------------------------------------------------------------- walks

    private List<LedgerCollections.EntryHead> walkEntries(Connection root, LedgerCollections.EntryFilter filter, int size) {
        return LedgerCollectionsDatabaseTest.<LedgerCollections.EntryHead, LedgerCollections.EntryCursor>walk(
                cursor -> collections.entries(root, filter, cursor, size),
                head -> new LedgerCollections.EntryCursor(head.postingDate(), head.id()));
    }

    private List<LedgerCollections.AccountRow> walkAccounts(Connection root, LedgerCollections.AccountFilter filter, int size) {
        return LedgerCollectionsDatabaseTest.<LedgerCollections.AccountRow, LedgerCollections.AccountCursor>walk(
                cursor -> collections.accounts(root, filter, cursor, size),
                row -> new LedgerCollections.AccountCursor(row.purpose(), row.currency(), row.id()));
    }

    /** Pages until one comes back short, each resuming after the last row served - the desk's walk, without its extra row. */
    private static <R, C> List<R> walk(Function<Optional<C>, List<R>> page, Function<R, C> key) {
        List<R> walked = new ArrayList<>();
        Optional<C> cursor = Optional.empty();
        for (int pages = 0; pages < 10_000; pages++) {
            List<R> rows = page.apply(cursor);
            walked.addAll(rows);
            if (rows.isEmpty()) {
                return walked;
            }
            cursor = Optional.of(key.apply(rows.get(rows.size() - 1)));
        }
        throw new AssertionError("the walk did not end");
    }

    // ------------------------------------------------------------------------------------------- seeding

    /** An ACTIVE customer wallet of a fresh owner, in {@code currency}; its id. */
    private static UUID account(Statement statement, String currency) throws SQLException {
        return id(statement, "INSERT INTO ledger.ledger_account (id, account_type, normal_balance, currency, owner_kind, owner_ref,"
                + " purpose, gl_code, status, created_at, status_changed_at) VALUES (uuidv7(), 'LIABILITY', 'CREDIT', '" + currency
                + "', 'CUSTOMER', gen_random_uuid(), 'CUSTOMER_WALLET', NULL, 'ACTIVE', now(), now()) RETURNING id");
    }

    private static String wallets(int count) {
        return "INSERT INTO ledger.ledger_account (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose,"
                + " gl_code, status, created_at, status_changed_at) SELECT uuidv7(), 'LIABILITY', 'CREDIT',"
                + " (ARRAY['EUR', 'GBP', 'USD'])[1 + n % 3], 'CUSTOMER', gen_random_uuid(), 'CUSTOMER_WALLET', NULL, 'ACTIVE',"
                + " now(), now() FROM generate_series(1, " + count + ") AS n";
    }

    /** {@code count} POSTING entries, entry n on {@code first} + n % {@code days}, its reference {@code marker-n}. */
    private static String entries(String marker, int count, String first, int days) {
        return "INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, reason, reverses_entry_id,"
                + " actor_id, correlation_id, causation_id, idempotency_scope, created_at) SELECT uuidv7(), DATE '" + first
                + "' + n % " + days + ", DATE '" + first + "' + n % " + days + ", 'POSTING', '" + marker + "-' || n, NULL, NULL,"
                + " 'system:p17-022', 'p17-022', 'p17-022', '" + marker + ":' || n, now()"
                + " FROM generate_series(1, " + count + ") AS n";
    }

    /** Two lines per entry of {@code marker}: a debit on {@code busy}, a credit on {@code other} - on {@code rare} every n-th. */
    private static String lines(String marker, UUID busy, UUID other, UUID rare, int every) {
        String n = "split_part(entry.idempotency_scope, ':', 2)::int";
        return "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction, amount_minor, currency, scale, seq)"
                + " SELECT gen_random_uuid(), entry.id, CASE WHEN line.seq = 0 THEN '" + busy + "'::uuid WHEN " + n + " % "
                + every + " = 0 THEN '" + rare + "'::uuid ELSE '" + other + "'::uuid END,"
                + " CASE WHEN line.seq = 0 THEN 'DEBIT' ELSE 'CREDIT' END, 100, 'EUR', 2, line.seq"
                + " FROM ledger.journal_entry entry CROSS JOIN (VALUES (0), (1)) AS line (seq)"
                + " WHERE entry.idempotency_scope LIKE '" + marker + ":%'";
    }

    private static String proposal(String origin, String code, String proposer) {
        return "INSERT INTO ledger.adjustment_proposal (id, status, posting_date, value_date, reference, reason, reason_code, origin,"
                + " proposed_by, proposed_at, decided_by, decided_at, journal_entry_id) VALUES (uuidv7(), 'PROPOSED',"
                + " DATE '2091-03-01', DATE '2091-03-01', 'p17-022-proposal', 'a person''s reason', '" + code + "', '" + origin
                + "', '" + proposer + "', clock_timestamp(), NULL, NULL, NULL)";
    }

    private static String decidedProposals(int count) {
        return "INSERT INTO ledger.adjustment_proposal (id, status, posting_date, value_date, reference, reason, reason_code, origin,"
                + " proposed_by, proposed_at, decided_by, decided_at, journal_entry_id) SELECT gen_random_uuid(), 'REJECTED',"
                + " DATE '2091-03-01', DATE '2091-03-01', 'p17-022-decided', 'a person''s reason', 'MANUAL_CORRECTION', 'MANUAL',"
                + " 'operator-d', now() - n * interval '1 minute', 'operator-e', now(), NULL FROM generate_series(1, " + count
                + ") AS n";
    }

    /**
     * A standing rebuild on {@code account}: a projection row observed at 100 posted, 0 held, 3 applied - the journal deriving
     * 250, 0, 4 - or, unless {@code observedRow}, no row at all.
     */
    private static String rebuild(UUID account, String proposer, boolean observedRow) {
        String observed = observedRow ? "TRUE, 100, 0, 3" : "FALSE, NULL, NULL, NULL";
        return "INSERT INTO ledger.projection_rebuild (id, ledger_account_id, currency, scale, status, reason, proposed_by,"
                + " proposed_at, observed_row, observed_posted_minor, observed_holds_minor, observed_seq,"
                + " observed_derived_posted_minor, observed_derived_holds_minor, observed_derived_seq) VALUES (uuidv7(), '"
                + account + "', 'EUR', 2, 'PROPOSED', 'a person''s reason', '" + proposer + "', clock_timestamp(), " + observed
                + ", 250, 0, 4)";
    }

    private static String decidedRebuilds(UUID account, int count) {
        return "INSERT INTO ledger.projection_rebuild (id, ledger_account_id, currency, scale, status, reason, proposed_by,"
                + " proposed_at, observed_row, observed_posted_minor, observed_holds_minor, observed_seq,"
                + " observed_derived_posted_minor, observed_derived_holds_minor, observed_derived_seq, decided_by, decided_at)"
                + " SELECT gen_random_uuid(), '" + account + "', 'EUR', 2, 'REJECTED', 'a person''s reason', 'operator-d',"
                + " now() - n * interval '1 minute', TRUE, 100, 0, 3, 250, 0, 4, 'operator-e', now()"
                + " FROM generate_series(1, " + count + ") AS n";
    }

    private static UUID id(Statement statement, String insertReturningId) throws SQLException {
        try (ResultSet row = statement.executeQuery(insertReturningId)) {
            row.next();
            return row.getObject(1, UUID.class);
        }
    }

    private static String plan(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
            StringBuilder plan = new StringBuilder();
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
            return plan.toString();
        }
    }

    /** The plan's sort nodes that sort a whole input - an incremental sort over a tie the index already ordered is not one. */
    private static long sorts(String plan) {
        return plan.lines().filter(line -> line.contains("Sort") && !line.contains("Incremental Sort")
                && !line.contains("Sort Key") && !line.contains("Presorted Key")).count();
    }
}
