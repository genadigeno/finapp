package com.finapp.app.bnpl;

import com.finapp.app.credit.ExposureCensus;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.ledger.TrialBalance;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanReplayProof;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.SoftAssertions;

/**
 * The BNPL storm's censuses (`P12-TST-001`; the lending storm's `LendingStormCensus` shape): every one read in ONE read-only
 * {@code REPEATABLE READ} snapshot of the application role - a census read in several snapshots could see half of a commit.
 * Soft assertions, so a failure names every census that caught it. The SAFETY censuses hold at every commit and are read
 * while the load blows as well as at rest; the LIVENESS censuses are only meaningful once the sweeps have caught up, and
 * are read at every rest. A census never names an obligation, a purchase, a party or an amount in what it reports - counts,
 * arms and kinds only; a failing census's rows go to {@code build/bnpl-storm-census.txt}, never to the captured output.
 *
 * <p><strong>"After" a closure (A6-03)</strong>: judged by the record's order, never the clock - a fact in ANOTHER
 * transaction than the closure's whose {@code recorded_order} (bnpl {@code V017}, this task's production fix) is greater
 * than the closure's. Every writer draws it after taking B4, which the closure holds to its commit, so the order is exact:
 * no tolerance at all, where lending's storm allows 5 s.
 */
final class BnplStormCensus {

    final AtomicInteger readings = new AtomicInteger();
    final AtomicInteger restReadings = new AtomicInteger();
    final AtomicLong readingNanos = new AtomicLong();
    final AtomicLong slowestReadingNanos = new AtomicLong();
    private final BnplProofs proofs;
    private final LendingProofs lendingProofs;
    private final LendingCapitalProof capital;
    private final MerchantPayable payables;
    private final long deadlocksBefore;
    /** The storm's merchants, whose payables the INV-MER-02 census reads. */
    private final java.util.function.Supplier<List<String>> merchants;
    /** At the last reading: obligations proven and those closed, for the summary. */
    volatile long obligationsProven;
    volatile long merchantsProven;

    BnplStormCensus(BnplProofs proofs, LendingProofs lendingProofs, LendingCapitalProof capital, MerchantPayable payables,
            java.util.function.Supplier<List<String>> merchants) throws SQLException {
        this.proofs = proofs;
        this.lendingProofs = lendingProofs;
        this.capital = capital;
        this.payables = payables;
        this.merchants = merchants;
        this.deadlocksBefore = deadlocks();
    }

    long deadlocksSinceTheStormBegan() throws SQLException {
        return deadlocks() - deadlocksBefore;
    }

    private static long deadlocks() throws SQLException {
        return Long.parseLong(BnplStormWorld.scalar(
                "SELECT deadlocks::text FROM pg_stat_database WHERE datname = current_database()"));
    }

    /** One reading - the safety censuses, and at rest the liveness censuses - in one snapshot. */
    void read(String when, boolean atRest, SoftAssertions softly) throws SQLException {
        long began = System.nanoTime();
        readings.incrementAndGet();
        if (atRest) {
            restReadings.incrementAndGet();
        }
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                proofs(snapshot, when, softly);
                daysPastDue(snapshot, when, softly);
                softly.assertThat(new ExposureCensus().take(snapshot).failing())
                        .as("%s: C5 the exposure census (v3) - no party's exposure beyond its decision's limit"
                                + " (INV-BNPL-10, INV-CRD-09)", when).isZero();
                LendingCapitalProof.Report capitalReport = capital.prove(snapshot);
                softly.assertThat(capitalReport.holds()).as("%s: C4 the capital proof with the BNPL term - every capital"
                        + " line a recognition's, headroom never below zero (INV-LND-13, INV-BNPL-10): %s", when,
                        capitalReport.currencies()).isTrue();
                softly.assertThat(new TrialBalance().sweep(snapshot).outOfBalance())
                        .as("%s: C6 the trial balance - zero per currency (INV-LED-01)", when).isEmpty();
                payables(snapshot, when, softly);
                zeroes(snapshot, when, softly, safety());
                if (atRest) {
                    zeroes(snapshot, when + " (at rest)", softly, liveness());
                }
            } finally {
                snapshot.rollback();
            }
        }
        softly.assertThat(deadlocksSinceTheStormBegan()).as("%s: C15 no deadlock (40P01) in the storm", when).isZero();
        long took = System.nanoTime() - began;
        readingNanos.addAndGet(took);
        slowestReadingNanos.accumulateAndGet(took, Math::max);
    }

    /**
     * C19 - days past due (`P12-DOC-001`; `PHASE_GATES.md` section 5 Phase 12, Delinquency: "DPD equals the date difference
     * ... for 100% of obligations in every storm round"): for every {@code ACTIVE} obligation, the Java rule
     * ({@code BnplDelinquencyState}'s - the oldest billed instalment still owing, not held on the date, counted from its
     * clock start) over its billed items and its dispute hold, against {@code bnpl V015}'s {@code bnpl.days_past_due} on
     * the same business date. Both read paid amounts through {@code V008}'s one definition (the Phase 11 storm's I6, carried
     * as recorded); what the census holds apart is the date arithmetic and the hold - two implementations.
     */
    private void daysPastDue(Connection snapshot, String when, SoftAssertions softly) throws SQLException {
        com.finapp.bnpl.JdbcBnplPurchaseStore purchases = new com.finapp.bnpl.JdbcBnplPurchaseStore();
        java.time.LocalDate today;
        try (Statement statement = snapshot.createStatement();
                ResultSet row = statement.executeQuery("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date")) {
            row.next();
            today = row.getObject(1, java.time.LocalDate.class);
        }
        Map<UUID, Integer> sql = new LinkedHashMap<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT id, bnpl.days_past_due(id, ?)"
                + " FROM bnpl.bnpl_obligation WHERE status = 'ACTIVE' ORDER BY id")) {
            select.setObject(1, today);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    sql.put(rows.getObject(1, UUID.class), rows.getInt(2));
                }
            }
        }
        int differing = 0;
        int pastDue = 0;
        for (Map.Entry<UUID, Integer> obligation : sql.entrySet()) {
            com.finapp.bnpl.BnplObligationId id = com.finapp.bnpl.BnplObligationId.of(obligation.getKey());
            com.finapp.bnpl.BnplDisputeHold hold = purchases.disputeHold(snapshot, id);
            java.util.Optional<java.time.LocalDate> oldest = purchases.billedItems(snapshot, id).stream()
                    .filter(item -> item.dueTotal().isPositive() && !hold.heldOn(item.dueDate(), today))
                    .map(item -> hold.clockFrom(item.dueDate(), today))
                    .filter(from -> from.isBefore(today))
                    .min(java.time.LocalDate::compareTo);
            int derived = oldest.map(from -> Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(from, today)))
                    .orElse(0);
            if (derived > 0) {
                pastDue++;
            }
            if (derived != obligation.getValue()) {
                differing++;
                System.out.println(when + ": C19 DPD differs for " + obligation.getKey() + " - Java " + derived + ", SQL "
                        + obligation.getValue());
            }
        }
        dpdReadings.incrementAndGet();
        dpdPastDueSeen.accumulateAndGet(pastDue, Math::max);
        softly.assertThat(differing).as("%s: C19 an ACTIVE obligation whose days past due differ between the Java rule"
                + " and bnpl.days_past_due on the business date (%s of %s past due)", when, pastDue, sql.size()).isZero();
    }

    /** Readings C19 took, and the most obligations it found past due at one - so a vacuous census shows. */
    final AtomicInteger dpdReadings = new AtomicInteger();
    final AtomicInteger dpdPastDueSeen = new AtomicInteger();

    /** C1-C3 from ONE reading of the BNPL proofs, and lending's (C1L, C2L) on the shared parties' loans. */
    private void proofs(Connection snapshot, String when, SoftAssertions softly) {
        BnplProofs.Reading reading = proofs.prove(snapshot);
        obligationsProven = reading.obligations().size();
        Map<String, Long> arms = new TreeMap<>();
        for (BnplSubledgerProof.Verdict verdict : reading.failures()) {
            verdict.failures().forEach(arm -> arms.merge(String.valueOf(arm), 1L, Long::sum));
        }
        Map<String, Long> kinds = new TreeMap<>();
        for (BnplReplayProof.Replay replay : reading.divergences()) {
            replay.checks().forEach(check -> kinds.merge(String.valueOf(check), 1L, Long::sum));
            dump(snapshot, when + ": DIVERGED " + replay.findings(), replay.obligation().value());
        }
        for (BnplSubledgerProof.Verdict verdict : reading.failures()) {
            dump(snapshot, when + ": subledger FAILS " + verdict.failures(), verdict.obligation().value());
        }
        Map<String, String> threeWay = new TreeMap<>();
        for (BnplThreeWayProof.CurrencyVerdict currency : reading.currencies()) {
            if (!currency.holds()) {
                threeWay.put(String.valueOf(currency.currency()), String.valueOf(currency.failures()));
            }
        }
        softly.assertThat(reading.currencies(false)).as("%s: C1 the three-way proof - merchant leg, customer leg,"
                + " economic identity and ledger zero per currency (INV-BNPL-04, -06, -07); failing %s", when, threeWay)
                .isZero();
        softly.assertThat(reading.failing()).as("%s: C2 the subledger proof - every obligation's six balances explained"
                + " by its rows (INV-BNPL-01, -09); failing obligations per arm %s", when, arms).isZero();
        softly.assertThat(reading.replaying(BnplReplayProof.Verdict.DIVERGED)).as("%s: C3 the replay - every obligation"
                + " IDENTICAL (INV-BNPL-05, -06); diverged obligations per check %s", when, kinds).isZero();
        LendingProofs.Reading lending = lendingProofs.prove(snapshot);
        softly.assertThat(lending.failing()).as("%s: C1L lending's subledger proof on the shared parties' accounts",
                when).isZero();
        softly.assertThat(lending.replaying(LoanReplayProof.Verdict.DIVERGED)).as("%s: C2L lending's replay IDENTICAL",
                when).isZero();
    }

    /**
     * C11 - `INV-MER-02` per payable: the drill-down's position equals the payable's own journal lines (independent SQL),
     * its BNPL terms equal the bnpl rows - financed gross and fee, refunds' gross and fee returned - and its payouts the
     * completed payouts; nothing unexplained. And B7 (`INV-MER-07` amended): a payable below zero only by BNPL refunds'
     * debits, each purchase's refunds debiting at most its net - the per-purchase arm counted on its own since the Phase 12
     * &rarr; 13 transition gate (A6-F6): the payable-wide bound alone cannot see one purchase over its net beside another
     * under it.
     */
    private void payables(Connection snapshot, String when, SoftAssertions softly) throws SQLException {
        int proven = 0;
        Map<String, Integer> broken = new TreeMap<>();
        for (String merchant : merchants.get()) {
            for (MerchantPayable.Payable payable : payables.payablesOf(snapshot, MerchantId.of(UUID.fromString(merchant)))) {
                proven++;
                long[] rows = longs(snapshot, "SELECT (SELECT coalesce(sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor"
                        + " ELSE -l.amount_minor END), 0) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                        + " l.ledger_account_id WHERE a.owner_ref = ?::uuid AND a.purpose = 'MERCHANT_PAYABLE'),"
                        + " (SELECT coalesce(sum(f.gross_minor), 0) FROM bnpl.merchant_financing f WHERE f.merchant_id ="
                        + " ?::uuid), (SELECT coalesce(sum(f.fee_minor), 0) FROM bnpl.merchant_financing f WHERE"
                        + " f.merchant_id = ?::uuid), (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_refund r"
                        + " WHERE r.merchant_id = ?::uuid), (SELECT coalesce(sum(r.fee_returned_minor), 0) FROM"
                        + " bnpl.bnpl_refund r WHERE r.merchant_id = ?::uuid), (SELECT coalesce(sum(p.amount_minor), 0) FROM"
                        + " merchant.merchant_payout p WHERE p.merchant_id = ?::uuid AND p.status = 'COMPLETED'),"
                        + " (SELECT coalesce(sum(r.payable_debit_minor), 0) FROM bnpl.bnpl_refund r WHERE r.merchant_id ="
                        + " ?::uuid), (SELECT count(*) FROM bnpl.merchant_financing f JOIN (SELECT r.purchase_id,"
                        + " sum(r.payable_debit_minor) AS debited FROM bnpl.bnpl_refund r GROUP BY r.purchase_id) d ON"
                        + " d.purchase_id = f.purchase_id WHERE f.merchant_id = ?::uuid AND d.debited > f.net_minor)",
                        merchant, merchant, merchant, merchant, merchant, merchant, merchant, merchant);
                check(broken, "position = its journal lines", payable.position().minorUnits() == rows[0]);
                check(broken, "the drill-down sums to the position",
                        payable.terms().minorUnits() == payable.position().minorUnits());
                check(broken, "bnplFinanced = the financings' gross", payable.bnplFinanced().minorUnits() == rows[1]);
                check(broken, "bnplFees = the financings' fee", payable.bnplFees().minorUnits() == rows[2]);
                check(broken, "bnplRefunded = the refunds' gross", payable.bnplRefunded().minorUnits() == rows[3]);
                check(broken, "bnplFeesReturned = the refunds' fee returned",
                        payable.bnplFeesReturned().minorUnits() == rows[4]);
                check(broken, "paidOut = the completed payouts", payable.paidOut().minorUnits() == rows[5]);
                check(broken, "nothing unexplained (other = 0)", payable.other().isZero());
                check(broken, "B7 - below zero only by the BNPL refunds' debits",
                        payable.position().minorUnits() >= -rows[6]);
                // B7 per purchase (the 12 -> 13 transition gate, A6-F6): the payable-wide bound above holds even if one
                // purchase's refunds took back more than it financed while another's took back less - so each purchase's
                // refunds together debit at most that purchase's net, counted from the rows, never the engine.
                check(broken, "B7 - each purchase's refunds debit the payable at most its financed net", rows[7] == 0);
            }
        }
        merchantsProven = proven;
        softly.assertThat(broken).as("%s: C11 INV-MER-02's equation per payable and B7's bound - arms broken (count of"
                + " payables)", when).isEmpty();
    }

    private static final char NL = (char) 10;

    /** An obligation's whole record, for the investigation - to the census file, never the output. */
    static void dump(Connection snapshot, String what, UUID obligation) {
        StringBuilder text = new StringBuilder("== ").append(what).append(" obligation ").append(obligation).append(NL);
        String o = "'" + obligation + "'::uuid";
        List<String> reads = List.of(
                "SELECT * FROM bnpl.bnpl_obligation WHERE id = " + o,
                "SELECT * FROM bnpl.purchase WHERE id = (SELECT purchase_id FROM bnpl.bnpl_obligation WHERE id = " + o + ")",
                "SELECT agreement_id, version, schedule_version, refund_id, effective_from, created_at FROM bnpl.bnpl_agreement"
                        + " WHERE obligation_id = " + o + " ORDER BY version",
                "SELECT * FROM bnpl.bnpl_schedule WHERE obligation_id = " + o + " ORDER BY schedule_version",
                "SELECT i.* FROM bnpl.bnpl_instalment i WHERE i.obligation_id = " + o + " ORDER BY i.schedule_id, i.sequence",
                "SELECT * FROM bnpl.bnpl_billing WHERE obligation_id = " + o + " ORDER BY billed_at",
                "SELECT * FROM bnpl.bnpl_repayment WHERE obligation_id = " + o + " ORDER BY received_at",
                "SELECT a.* FROM bnpl.bnpl_repayment_allocation a JOIN bnpl.bnpl_repayment r ON r.id = a.repayment_id WHERE"
                        + " r.obligation_id = " + o + " ORDER BY r.received_at, a.sequence",
                "SELECT * FROM bnpl.bnpl_credit_balance_application WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_payoff_quote WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_payoff_execution WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_refund WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_collection_attempt WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_interest_accrual WHERE obligation_id = " + o + " ORDER BY accrual_date",
                "SELECT * FROM bnpl.bnpl_repayment_reversal WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_obligation_closure WHERE obligation_id = " + o,
                "SELECT * FROM bnpl.bnpl_condition_event WHERE obligation_id = " + o,
                "SELECT a.purpose, b.* FROM ledger.ledger_account a JOIN ledger.account_balance b ON b.ledger_account_id ="
                        + " a.id WHERE a.owner_ref = (SELECT party_id FROM bnpl.bnpl_obligation WHERE id = " + o + ")");
        for (String sql : reads) {
            java.sql.Savepoint before = null;
            try {
                before = snapshot.setSavepoint();
                try (Statement read = snapshot.createStatement(); ResultSet row = read.executeQuery(sql)) {
                    int columns = row.getMetaData().getColumnCount();
                    text.append("-- ").append(sql, 0, Math.min(60, sql.length())).append(NL);
                    while (row.next()) {
                        for (int i = 1; i <= columns; i++) {
                            text.append(row.getMetaData().getColumnName(i)).append('=').append(row.getString(i))
                                    .append(' ');
                        }
                        text.append(NL);
                    }
                }
            } catch (SQLException unreadable) {
                text.append("(unreadable: ").append(")").append(NL);
                try {
                    if (before != null) {
                        snapshot.rollback(before);
                    }
                } catch (SQLException ignored) {
                    // forensics only
                }
            }
        }
        write(text.toString());
    }

    private static void check(Map<String, Integer> broken, String arm, boolean holds) {
        if (!holds) {
            broken.merge(arm, 1, Integer::sum);
        }
    }

    private static long[] longs(Connection snapshot, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                read.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                long[] values = new long[row.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = row.getLong(i + 1);
                }
                return values;
            }
        }
    }

    private static void zeroes(Connection snapshot, String when, SoftAssertions softly, Map<String, String> censuses)
            throws SQLException {
        for (Map.Entry<String, String> census : censuses.entrySet()) {
            long found = one(snapshot, census.getValue());
            if (found != 0) {
                forensics(snapshot, when + ": " + census.getKey(), census.getValue());
            }
            softly.assertThat(found).as("%s: %s", when, census.getKey()).isZero();
        }
    }

    /** A failing census's rows, for the investigation - to {@code build/bnpl-storm-census.txt}, never the output. */
    static void forensics(Connection snapshot, String what, String sql) {
        StringBuilder text = new StringBuilder("== ").append(what).append('\n');
        if (sql.startsWith("SELECT count(*) FROM ")) {
            java.sql.Savepoint before;
            try {
                before = snapshot.setSavepoint();
            } catch (SQLException unreadable) {
                return;
            }
            try (Statement read = snapshot.createStatement();
                    ResultSet row = read.executeQuery("SELECT * FROM " + sql.substring("SELECT count(*) FROM ".length())
                            + " LIMIT 10")) {
                int columns = row.getMetaData().getColumnCount();
                while (row.next()) {
                    for (int i = 1; i <= columns; i++) {
                        text.append(row.getMetaData().getColumnName(i)).append('=').append(row.getString(i)).append(' ');
                    }
                    text.append('\n');
                }
            } catch (SQLException unreadable) {
                text.append("(rows unreadable: ").append(unreadable.getSQLState()).append(")\n");
                try {
                    snapshot.rollback(before);
                } catch (SQLException ignored) {
                    // the census's own assertion carries the verdict
                }
            }
        }
        write(text.toString());
    }

    static void write(String text) {
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("build", "bnpl-storm-census.txt"), text,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // forensics only
        }
    }

    static long one(Connection snapshot, String sql) throws SQLException {
        try (Statement read = snapshot.createStatement(); ResultSet row = read.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** A fact table and its trigger stamp - the closure census's facts. */
    private static final String[][] SERVICING_FACTS = {
        {"bnpl.bnpl_billing", "billed_at", "a billing"},
        {"bnpl.bnpl_repayment", "received_at", "a repayment"},
        {"bnpl.bnpl_interest_accrual", "created_at", "an accrual"},
        {"bnpl.bnpl_collection_attempt", "created_at", "a collection attempt"},
        {"bnpl.bnpl_fee_assessment", "created_at", "a fee"},
        {"bnpl.bnpl_condition_event", "created_at", "a condition"},
        {"bnpl.bnpl_credit_balance_application", "created_at", "a credit-balance application"},
    };

    /** The SAFETY censuses: zero at every commit, read while the load blows. */
    static Map<String, String> safety() {
        Map<String, String> zero = new LinkedHashMap<>();
        // C7 - one session, one purchase, one financing, one order (INV-BNPL-02, -03, -04).
        zero.put("C7 two purchases for one checkout session",
                "SELECT count(*) FROM (SELECT checkout_session_id FROM bnpl.purchase GROUP BY 1 HAVING count(*) > 1) twice");
        zero.put("C7 a purchase without exactly one financing",
                "SELECT count(*) FROM bnpl.purchase p WHERE (SELECT count(*) FROM bnpl.merchant_financing f"
                        + " WHERE f.purchase_id = p.id) <> 1");
        zero.put("C7 a purchase without its session's one order",
                "SELECT count(*) FROM bnpl.purchase p WHERE (SELECT count(*) FROM checkout.checkout_order o"
                        + " WHERE o.session_ref = p.checkout_session_id) <> 1");
        zero.put("C7 a BNPL-paid session whose order is not the financing's (paid twice)",
                "SELECT count(*) FROM bnpl.purchase p JOIN bnpl.merchant_financing f ON f.purchase_id = p.id"
                        + " JOIN checkout.checkout_order o ON o.session_ref = p.checkout_session_id"
                        + " WHERE o.captured_entry_ref <> f.journal_entry_id");
        zero.put("C7 a financing whose gross is not its session's amount, or whose fee and net do not make it",
                "SELECT count(*) FROM bnpl.purchase p JOIN bnpl.merchant_financing f ON f.purchase_id = p.id"
                        + " JOIN checkout.checkout_session s ON s.id = p.checkout_session_id"
                        + " WHERE f.gross_minor <> s.amount_minor OR f.gross_minor <> f.fee_minor + f.net_minor");
        zero.put("C7 a financing entry no financing row names, or two under one purchase's key",
                "SELECT count(*) FROM ledger.journal_entry e WHERE e.idempotency_scope LIKE 'ledger.post:bnpl.financing:%'"
                        + " AND (NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.journal_entry_id = e.id)"
                        + " OR e.idempotency_scope <> 'ledger.post:bnpl.financing:' || (SELECT f.purchase_id::text FROM"
                        + " bnpl.merchant_financing f WHERE f.journal_entry_id = e.id))");
        zero.put("C7 an obligation whose decision credit has not consumed exactly once",
                "SELECT count(*) FROM bnpl.bnpl_obligation o WHERE (SELECT count(*) FROM"
                        + " credit.credit_decision_consumption c WHERE c.decision_id = o.decision_id) <> 1");
        zero.put("C7 a purchase whose offer is not ACCEPTED, or an ACCEPTED offer without its purchase",
                "SELECT count(*) FROM bnpl.bnpl_offer o WHERE (o.status = 'ACCEPTED') <> EXISTS (SELECT 1 FROM"
                        + " bnpl.purchase p WHERE p.offer_id = o.id)");
        zero.put("C7 an offer that reached two of ACCEPTED, DECLINED and EXPIRED",
                "SELECT count(*) FROM bnpl.bnpl_offer o WHERE (SELECT count(*) FROM bnpl.bnpl_offer_event e"
                        + " WHERE e.offer_id = o.id AND e.to_status IN ('ACCEPTED', 'DECLINED', 'EXPIRED')) > 1");
        zero.put("C7 an acceptance at or after its offer's expiry on the database clock",
                "SELECT count(*) FROM bnpl.purchase p JOIN bnpl.bnpl_offer o ON o.id = p.offer_id"
                        + " WHERE p.authorised_at >= o.expires_at");
        // C8 - refunds (INV-BNPL-06, -07, -08, -09).
        zero.put("C8 a purchase refunded beyond its gross (merchant, operator and dispute refunds together)",
                "SELECT count(*) FROM (SELECT r.purchase_id FROM bnpl.bnpl_refund r JOIN bnpl.merchant_financing f ON"
                        + " f.purchase_id = r.purchase_id GROUP BY r.purchase_id, f.gross_minor HAVING sum(r.amount_minor)"
                        + " > f.gross_minor) over");
        zero.put("C8 a refund whose refunded-before is not the sum of the purchase's earlier refunds",
                "SELECT count(*) FROM (SELECT r.refunded_before_minor, lag(r.refunded_before_minor + r.amount_minor, 1,"
                        + " 0::bigint) OVER (PARTITION BY r.purchase_id ORDER BY r.refunded_before_minor) AS expected"
                        + " FROM bnpl.bnpl_refund r) chain WHERE chain.refunded_before_minor <> chain.expected");
        zero.put("C8 a refund that does not split into cancellations and excess, or whose adjustments are not its"
                + " cancellations (INV-BNPL-06)", "SELECT count(*) FROM bnpl.bnpl_refund r WHERE r.amount_minor <>"
                        + " r.cancelled_unbilled_minor + r.cancelled_billed_minor + r.excess_minor"
                        + " OR r.cancelled_unbilled_minor + r.cancelled_billed_minor <> (SELECT"
                        + " coalesce(sum(a.principal_cancelled_minor), 0) FROM bnpl.bnpl_refund_adjustment a WHERE"
                        + " a.refund_id = r.id) OR r.payable_debit_minor <> r.amount_minor - r.fee_returned_minor");
        zero.put("C8 a refund that cancelled part of an instalment and an earlier one too - not latest first"
                + " (INV-BNPL-06)", "SELECT count(*) FROM bnpl.bnpl_refund_adjustment a WHERE NOT a.whole AND EXISTS"
                        + " (SELECT 1 FROM bnpl.bnpl_refund_adjustment b WHERE b.refund_id = a.refund_id"
                        + " AND b.sequence < a.sequence)");
        // The full walk (the 12 -> 13 transition gate, A6-F7): the census above sees only a partial cancellation beside an
        // earlier one, never a refund that skipped an owing instalment, took the wrong amount from one, or walked from the
        // wrong end across refunds. Here every refund's position is derived from the rows, independently of BnplRefunds:
        // each instalment billed before it at its billed principal, less what every payer settled before it (repayments,
        // credit-balance applications), plus what approved reversals re-opened before it, less what earlier refunds
        // cancelled of it; each still unbilled in the latest schedule version before it (never its own) at the billing
        // rule's walk over the principal not yet due (the gross, less every billing, less earlier refunds' unbilled
        // cancellations) - all before it in the record's order (bnpl V017), never a clock. The walk is then the rule's own
        // arithmetic - from the latest, min(what is left of R, the instalment's unpaid principal) - and every refund's
        // adjustments must be exactly it: the amount per instalment, the whole flag, billed or not, and no instalment
        // cancelled that the position does not hold. Proven on the bnpl database tier's refunds (72, 0 found) and with a
        // negative control (one refund's latest and earliest cancellations swapped - found) before it joined the storm.
        zero.put("C8 a refund whose cancellations are not the latest-first walk over its position, derived from the rows"
                + " in the record's order (INV-BNPL-06)",
                "WITH r AS (SELECT id, purchase_id, obligation_id, amount_minor, recorded_order FROM"
                        + " bnpl.bnpl_refund), billed AS (SELECT r.id AS refund_id, i.sequence, b.id AS billing_id,"
                        + " b.instalment_id, b.principal_minor FROM r JOIN bnpl.bnpl_billing b ON b.obligation_id ="
                        + " r.obligation_id AND b.recorded_order < r.recorded_order JOIN bnpl.bnpl_instalment i ON i.id"
                        + " = b.instalment_id), paid AS (SELECT r.id AS refund_id, a.instalment_id, sum(a.amount_minor)"
                        + " AS amount FROM r JOIN bnpl.bnpl_repayment p ON p.obligation_id = r.obligation_id AND"
                        + " p.recorded_order < r.recorded_order JOIN bnpl.bnpl_repayment_allocation a ON a.repayment_id"
                        + " = p.id WHERE a.target = 'DUE_ITEM' AND a.component = 'PRINCIPAL' GROUP BY 1, 2), applied AS"
                        + " (SELECT r.id AS refund_id, a.billing_id, sum(a.amount_minor) AS amount FROM r JOIN"
                        + " bnpl.bnpl_credit_balance_application c ON c.obligation_id = r.obligation_id AND"
                        + " c.recorded_order < r.recorded_order JOIN bnpl.bnpl_credit_balance_allocation a ON"
                        + " a.application_id = c.id WHERE a.component = 'PRINCIPAL' GROUP BY 1, 2), reopened AS (SELECT"
                        + " r.id AS refund_id, a.instalment_id, sum(n.amount_minor) AS amount FROM r JOIN"
                        + " bnpl.bnpl_repayment_reversal v ON v.obligation_id = r.obligation_id AND v.decided_order <"
                        + " r.recorded_order JOIN bnpl.bnpl_repayment_allocation_reversal n ON n.reversal_id = v.id"
                        + " JOIN bnpl.bnpl_repayment_allocation a ON a.id = n.allocation_id WHERE a.target = 'DUE_ITEM'"
                        + " AND a.component = 'PRINCIPAL' GROUP BY 1, 2), earlier AS (SELECT r.id AS refund_id,"
                        + " x.instalment_id, x.kind, sum(x.principal_cancelled_minor) AS amount FROM r JOIN"
                        + " bnpl.bnpl_refund e ON e.obligation_id = r.obligation_id AND e.recorded_order <"
                        + " r.recorded_order JOIN bnpl.bnpl_refund_adjustment x ON x.refund_id = e.id GROUP BY 1, 2,"
                        + " 3), latest AS (SELECT DISTINCT ON (r.id) r.id AS refund_id, s.id AS schedule_id FROM r JOIN"
                        + " bnpl.bnpl_schedule s ON s.obligation_id = r.obligation_id AND s.recorded_order <"
                        + " r.recorded_order AND s.refund_id IS DISTINCT FROM r.id ORDER BY r.id, s.schedule_version"
                        + " DESC), not_due AS (SELECT r.id AS refund_id, f.gross_minor - coalesce(bs.amount, 0) -"
                        + " coalesce(us.amount, 0) AS n FROM r JOIN bnpl.merchant_financing f ON f.purchase_id ="
                        + " r.purchase_id LEFT JOIN (SELECT refund_id, sum(principal_minor) AS amount FROM billed GROUP"
                        + " BY 1) bs ON bs.refund_id = r.id LEFT JOIN (SELECT refund_id, sum(amount) AS amount FROM"
                        + " earlier WHERE kind = 'UNBILLED' GROUP BY 1) us ON us.refund_id = r.id), unbilled AS (SELECT"
                        + " l.refund_id, i.sequence, i.principal_minor, coalesce(sum(i.principal_minor) OVER (PARTITION"
                        + " BY l.refund_id ORDER BY i.sequence ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS"
                        + " before, i.sequence = max(i.sequence) OVER (PARTITION BY l.refund_id) AS last FROM latest l"
                        + " JOIN bnpl.bnpl_instalment i ON i.schedule_id = l.schedule_id WHERE NOT EXISTS (SELECT 1"
                        + " FROM billed b WHERE b.refund_id = l.refund_id AND b.sequence = i.sequence)), position AS"
                        + " (SELECT b.refund_id, b.sequence, true AS billed, b.principal_minor - coalesce(p.amount, 0)"
                        + " - coalesce(a.amount, 0) + coalesce(o.amount, 0) - coalesce(e.amount, 0) AS unpaid FROM"
                        + " billed b LEFT JOIN paid p ON p.refund_id = b.refund_id AND p.instalment_id ="
                        + " b.instalment_id LEFT JOIN applied a ON a.refund_id = b.refund_id AND a.billing_id ="
                        + " b.billing_id LEFT JOIN reopened o ON o.refund_id = b.refund_id AND o.instalment_id ="
                        + " b.instalment_id LEFT JOIN earlier e ON e.refund_id = b.refund_id AND e.instalment_id ="
                        + " b.instalment_id AND e.kind = 'BILLED_UNPAID' UNION ALL SELECT u.refund_id, u.sequence,"
                        + " false, CASE WHEN u.last THEN d.n - LEAST(u.before, d.n) ELSE LEAST(u.before +"
                        + " u.principal_minor, d.n) - LEAST(u.before, d.n) END FROM unbilled u JOIN not_due d ON"
                        + " d.refund_id = u.refund_id), walk AS (SELECT p.refund_id, p.sequence, p.billed, p.unpaid,"
                        + " LEAST(p.unpaid, GREATEST(0, r.amount_minor - coalesce(sum(p.unpaid) OVER (PARTITION BY"
                        + " p.refund_id ORDER BY p.sequence DESC ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),"
                        + " 0))) AS expected FROM position p JOIN r ON r.id = p.refund_id), done AS (SELECT"
                        + " x.refund_id, x.sequence, sum(x.principal_cancelled_minor) AS cancelled, bool_and(x.whole)"
                        + " FILTER (WHERE x.principal_cancelled_minor > 0) AS whole, bool_and(x.kind = 'BILLED_UNPAID')"
                        + " FILTER (WHERE x.principal_cancelled_minor > 0) AS billed FROM bnpl.bnpl_refund_adjustment x"
                        + " GROUP BY 1, 2) SELECT count(*) FROM walk w FULL JOIN done d ON d.refund_id = w.refund_id"
                        + " AND d.sequence = w.sequence WHERE (w.refund_id IS NULL AND d.cancelled > 0) OR"
                        + " coalesce(d.cancelled, 0) <> w.expected OR (d.cancelled > 0 AND (d.whole IS DISTINCT FROM"
                        + " (d.cancelled = w.unpaid) OR d.billed IS DISTINCT FROM w.billed))");
        zero.put("C8 a purchase's fee returned beyond its fee, or a full refund whose fee returned is not exactly f"
                + " (the telescoping)", "SELECT count(*) FROM bnpl.merchant_financing f JOIN (SELECT r.purchase_id,"
                        + " sum(r.amount_minor) AS refunded, sum(r.fee_returned_minor) AS returned FROM bnpl.bnpl_refund r"
                        + " GROUP BY 1) r ON r.purchase_id = f.purchase_id WHERE r.returned > f.fee_minor"
                        + " OR (r.refunded = f.gross_minor AND r.returned <> f.fee_minor)");
        zero.put("C8 a purchase FULLY_REFUNDED exactly when its refunds reach its gross",
                "SELECT count(*) FROM bnpl.purchase p JOIN bnpl.merchant_financing f ON f.purchase_id = p.id"
                        + " WHERE (p.status = 'FULLY_REFUNDED') <> ((SELECT coalesce(sum(r.amount_minor), 0) FROM"
                        + " bnpl.bnpl_refund r WHERE r.purchase_id = p.id) = f.gross_minor)");
        zero.put("C8 a dispute resolved for the customer without exactly one refund, or a refund naming a dispute that"
                + " was not (INV-BNPL-08)", "SELECT count(*) FROM bnpl.bnpl_dispute d WHERE (d.status ="
                        + " 'RESOLVED_REFUND') <> ((SELECT count(*) FROM bnpl.bnpl_refund r WHERE r.dispute_id = d.id) = 1)"
                        + " OR (SELECT count(*) FROM bnpl.bnpl_refund r WHERE r.dispute_id = d.id) > 1");
        zero.put("C8 two disputes open on one purchase",
                "SELECT count(*) FROM (SELECT purchase_id FROM bnpl.bnpl_dispute WHERE status IN ('OPEN',"
                        + " 'MERCHANT_RESPONDED') GROUP BY 1 HAVING count(*) > 1) twice");
        // C9 - accruals (INV-IDEM-02).
        zero.put("C9 an accrual date born twice for one obligation",
                "SELECT count(*) FROM (SELECT obligation_id, accrual_date FROM bnpl.bnpl_interest_accrual GROUP BY 1, 2"
                        + " HAVING count(*) > 1) twice");
        zero.put("C9 an accrual entry not under its key bnpl.accrual:<obligation>:<date>, or not dated its date",
                "SELECT count(*) FROM bnpl.bnpl_interest_accrual a JOIN ledger.journal_entry e ON e.id ="
                        + " a.journal_entry_id WHERE e.idempotency_scope <> 'ledger.post:bnpl.accrual:' || a.obligation_id"
                        + " || ':' || a.accrual_date OR e.value_date <> a.accrual_date");
        zero.put("C9 two accrual entries under one key",
                "SELECT count(*) FROM (SELECT idempotency_scope FROM ledger.journal_entry WHERE idempotency_scope LIKE"
                        + " 'ledger.post:bnpl.accrual:%' GROUP BY 1 HAVING count(*) > 1) twice");
        zero.put("C9 an accrual written EARLY - for a date not yet ended on the database clock in UTC",
                "SELECT count(*) FROM bnpl.bnpl_interest_accrual a WHERE a.accrual_date >= (a.created_at AT TIME ZONE"
                        + " 'UTC')::date");
        zero.put("C9 an accrual dated before its obligation's purchase date",
                "SELECT count(*) FROM bnpl.bnpl_interest_accrual a JOIN bnpl.bnpl_obligation o ON o.id = a.obligation_id"
                        + " WHERE a.accrual_date < o.purchase_date");
        // C10 - allocation conserves, never over-pays (INV-BNPL-01, INV-LND-04's rule).
        zero.put("C10 a repayment whose allocations do not sum to its amount",
                "SELECT count(*) FROM bnpl.bnpl_repayment r WHERE r.amount_minor <> (SELECT coalesce(sum(a.amount_minor),"
                        + " 0) FROM bnpl.bnpl_repayment_allocation a WHERE a.repayment_id = r.id)");
        zero.put("C10 a billed component paid beyond what it billed",
                "SELECT count(*) FROM bnpl.bnpl_billing b CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) c"
                        + " (component) WHERE bnpl.billing_component_paid(b.id, c.component)"
                        + " > bnpl.billing_component_billed(b.id, c.component)");
        zero.put("C10 an approved reversal whose negations are not its repayment's allocations, exactly",
                "SELECT count(*) FROM bnpl.bnpl_repayment_reversal v WHERE v.status = 'APPROVED' AND ((SELECT"
                        + " coalesce(sum(n.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation_reversal n WHERE"
                        + " n.reversal_id = v.id) <> (SELECT r.amount_minor FROM bnpl.bnpl_repayment r WHERE r.id ="
                        + " v.repayment_id) OR EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_allocation_reversal n JOIN"
                        + " bnpl.bnpl_repayment_allocation a ON a.id = n.allocation_id WHERE n.reversal_id = v.id AND"
                        + " a.repayment_id <> v.repayment_id))");
        zero.put("C10 a credit-balance application whose allocations do not sum to it",
                "SELECT count(*) FROM bnpl.bnpl_credit_balance_application c WHERE c.amount_minor <> (SELECT"
                        + " coalesce(sum(a.amount_minor), 0) FROM bnpl.bnpl_credit_balance_allocation a WHERE"
                        + " a.application_id = c.id)");
        zero.put("C10 a collection attempt born twice for one instalment and date",
                "SELECT count(*) FROM (SELECT instalment_id, attempt_date FROM bnpl.bnpl_collection_attempt GROUP BY 1, 2"
                        + " HAVING count(*) > 1) twice");
        zero.put("C10 a reversal decided by its proposer, or a waiver approved by its proposer (four eyes)",
                "SELECT (SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE decided_by = proposed_by)"
                        + " + (SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE decided_by = proposed_by)"
                        + " + (SELECT count(*) FROM bnpl.bnpl_dispute_proposal WHERE decided_by = proposed_by)");
        // C12 - closed is closed (INV-BNPL-09; A6-03: judged by the record's order, in another transaction).
        zero.put("C12 a CLOSED obligation without its one closure, or a closure beside an obligation not CLOSED",
                "SELECT count(*) FROM bnpl.bnpl_obligation o WHERE (o.status = 'CLOSED') <> ((SELECT count(*) FROM"
                        + " bnpl.bnpl_obligation_closure c WHERE c.obligation_id = o.id) = 1)");
        zero.put("C12 an edge out of CLOSED",
                "SELECT count(*) FROM bnpl.bnpl_obligation_event WHERE from_status = 'CLOSED'");
        for (String[] fact : SERVICING_FACTS) {
            zero.put("C12 " + fact[2] + " written after its obligation's closure (another transaction, the record's"
                    + " order)", "SELECT count(*) FROM " + fact[0] + " f JOIN bnpl.bnpl_obligation_closure c ON"
                            + " c.obligation_id = f.obligation_id WHERE f.xmin::text <> c.xmin::text"
                            + " AND f.recorded_order > c.recorded_order");
        }
        zero.put("C12 an accrual dated after its obligation's closure date",
                "SELECT count(*) FROM bnpl.bnpl_interest_accrual a JOIN bnpl.bnpl_obligation_closure c ON"
                        + " c.obligation_id = a.obligation_id WHERE a.accrual_date > (c.created_at AT TIME ZONE 'UTC')::date");
        // C13 - payouts concluded once, posted once (INV-MER-05, INV-PAY-04).
        zero.put("C13 a COMPLETED payout without exactly one posting, or a payout posting beside a payout not COMPLETED",
                "SELECT count(*) FROM merchant.merchant_payout p WHERE (p.status = 'COMPLETED') <> ((SELECT count(*) FROM"
                        + " ledger.journal_entry e WHERE e.idempotency_scope = 'ledger.post:merchant-payout:' || p.id) = 1)");
        zero.put("C13 a COMPLETED payout without exactly one MERCHANT_PAYOUT expectation on its own posting for its amount"
                + " (INV-SET-02, ADR-0067), or an expectation beside a payout not COMPLETED",
                "SELECT count(*) FROM merchant.merchant_payout p WHERE (p.status = 'COMPLETED') <> ((SELECT count(*) FROM"
                        + " reconciliation.expectation e JOIN ledger.journal_entry j ON j.id = e.journal_entry_id WHERE"
                        + " e.kind = 'MERCHANT_PAYOUT' AND e.operation_ref = p.id::text AND e.amount_minor = p.amount_minor"
                        + " AND j.idempotency_scope = 'ledger.post:merchant-payout:' || p.id) = 1)");
        // C14 - every state change's event exactly once (the outbox written in the fact's own transaction).
        zero.put("C14 an (aggregate, event type) whose events are not its facts exactly once", outboxMismatches());
        return zero;
    }

    /**
     * C14's facts: each bnpl fact by its aggregate and the event type that announces it, once per fact.
     * {@code bnpl.InstalmentPaid} is not judged (one per repayment that pays, not a row the census can count alone).
     */
    static final String[][] FACTS = {
        {"bnpl.BnplApplicationSubmitted", "SELECT id, 1 FROM bnpl.bnpl_application"},
        {"bnpl.BnplApplicationClosed", "SELECT id, 1 FROM bnpl.bnpl_application WHERE status IN ('DECLINED',"
                + " 'CLOSED_UNDECIDED', 'WITHDRAWN')"},
        {"bnpl.BnplEligibilityAssessed", "SELECT id, 1 FROM bnpl.bnpl_application WHERE status IN ('OFFERED',"
                + " 'DECLINED') OR (status = 'CLOSED_UNDECIDED' AND closure_reason = 'APPROVED_BELOW_PURCHASE')"},
        {"bnpl.BnplOffered", "SELECT id, 1 FROM bnpl.bnpl_offer"},
        {"bnpl.BnplOfferClosed", "SELECT id, 1 FROM bnpl.bnpl_offer WHERE status IN ('DECLINED', 'EXPIRED')"},
        {"bnpl.BnplAgreementCreated", "SELECT agreement_id, 1 FROM bnpl.bnpl_obligation"},
        {"bnpl.MerchantFinanced", "SELECT purchase_id, 1 FROM bnpl.merchant_financing"},
        {"bnpl.InstalmentDue", "SELECT obligation_id, 1 FROM bnpl.bnpl_billing"},
        {"bnpl.BnplCollectionFailed", "SELECT obligation_id, 1 FROM bnpl.bnpl_collection_attempt WHERE outcome IN"
                + " ('INSUFFICIENT_FUNDS', 'WALLET_NOT_POSTABLE')"},
        {"bnpl.BnplDelinquencyChanged", "SELECT obligation_id, 1 FROM bnpl.bnpl_condition_event WHERE kind ="
                + " 'DELINQUENCY'"},
        {"bnpl.BnplDefaulted", "SELECT obligation_id, 1 FROM bnpl.bnpl_condition_event WHERE kind = 'DEFAULT' AND defaulted"},
        {"bnpl.BnplDefaultCleared", "SELECT obligation_id, 1 FROM bnpl.bnpl_condition_event WHERE kind = 'DEFAULT'"
                + " AND NOT defaulted"},
        {"bnpl.BnplLateFeeAssessed", "SELECT obligation_id, 1 FROM bnpl.bnpl_fee_assessment"},
        {"bnpl.BnplRefundApplied", "SELECT purchase_id, 1 FROM bnpl.bnpl_refund"},
        {"bnpl.BnplDisputeOpened", "SELECT id, 1 FROM bnpl.bnpl_dispute"},
        {"bnpl.BnplDisputeResolved", "SELECT id, 1 FROM bnpl.bnpl_dispute WHERE status IN ('RESOLVED_REFUND',"
                + " 'RESOLVED_UPHELD', 'WITHDRAWN')"},
        {"bnpl.BnplRepaymentReversed", "SELECT obligation_id, 1 FROM bnpl.bnpl_repayment_reversal WHERE status ="
                + " 'APPROVED'"},
        {"bnpl.BnplFeeWaived", "SELECT w.obligation_id, 1 FROM bnpl.bnpl_waiver w JOIN bnpl.bnpl_waiver_decision d ON"
                + " d.waiver_id = w.id WHERE d.outcome = 'APPROVED'"},
        {"bnpl.BnplAgreementClosed", "SELECT obligation_id, 1 FROM bnpl.bnpl_obligation_closure"},
        {"bnpl.BnplTermsVersionActivated", "SELECT id, 1 FROM bnpl.bnpl_terms_version WHERE status IN ('ACTIVE',"
                + " 'RETIRED')"},
    };

    /** C14 as one statement: the (aggregate, event type) pairs whose fact count and event count differ. */
    static String outboxMismatches() {
        StringBuilder types = new StringBuilder();
        StringBuilder facts = new StringBuilder();
        for (String[] fact : FACTS) {
            types.append(types.length() == 0 ? "" : ", ").append('\'').append(fact[0]).append('\'');
            facts.append(facts.length() == 0 ? "" : " UNION ALL ").append("SELECT f.*, '").append(fact[0])
                    .append("' FROM (").append(fact[1]).append(") f");
        }
        return "SELECT count(*) FROM (WITH ev AS (SELECT aggregate_id AS id, event_type, count(*) AS n FROM"
                + " platform.outbox_event WHERE event_type IN (" + types + ") GROUP BY 1, 2), fa AS (SELECT id,"
                + " event_type, count(*) AS n FROM (" + facts + ") x(id, one, event_type) GROUP BY 1, 2)"
                + " SELECT coalesce(fa.id, ev.id) AS aggregate, coalesce(fa.event_type, ev.event_type) AS event_type,"
                + " coalesce(fa.n, 0) AS facts, coalesce(ev.n, 0) AS events FROM fa FULL JOIN ev ON ev.id = fa.id"
                + " AND ev.event_type = fa.event_type WHERE coalesce(fa.n, 0) <> coalesce(ev.n, 0)) mismatched";
    }

    /**
     * The LIVENESS censuses: meaningful once the sweeps have caught up - read at every rest. Each judges the clock at the
     * SNAPSHOT's own instant ({@code transaction_timestamp()}).
     */
    static Map<String, String> liveness() {
        Map<String, String> zero = new LinkedHashMap<>();
        zero.put("rest: an ACTIVE obligation not yet serviced for its business date",
                "SELECT count(*) FROM bnpl.bnpl_obligation WHERE status = 'ACTIVE' AND next_servicing_at <="
                        + " transaction_timestamp()");
        zero.put("C9 an ACTIVE pay-in-N obligation whose last ended date, with principal outstanding at its end and inside"
                + " its schedule, has no accrual", "SELECT count(*) FROM bnpl.bnpl_obligation o CROSS JOIN LATERAL"
                        + " (SELECT (transaction_timestamp() AT TIME ZONE 'UTC')::date - 1 AS yesterday) y WHERE"
                        + " o.status = 'ACTIVE' AND o.product = 'BNPL_PAY_IN_N' AND y.yesterday >= o.purchase_date"
                        + " AND y.yesterday < (SELECT max(i.due_date) FROM bnpl.bnpl_instalment i JOIN bnpl.bnpl_schedule s"
                        + " ON s.id = i.schedule_id WHERE s.obligation_id = o.id AND s.schedule_version = (SELECT"
                        + " max(x.schedule_version) FROM bnpl.bnpl_schedule x WHERE x.obligation_id = o.id))"
                        + " AND (SELECT coalesce(sum(CASE j.direction WHEN 'DEBIT' THEN j.amount_minor ELSE -j.amount_minor"
                        + " END), 0) FROM ledger.ledger_account a JOIN ledger.journal_line j ON j.ledger_account_id = a.id"
                        + " JOIN ledger.journal_entry e ON e.id = j.entry_id WHERE a.owner_ref = o.id AND a.purpose IN"
                        + " ('BNPL_PRINCIPAL', 'BNPL_PRINCIPAL_DUE') AND e.value_date <= y.yesterday) > 0"
                        + " AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_interest_accrual a WHERE a.obligation_id = o.id"
                        + " AND a.accrual_date = y.yesterday)");
        zero.put("rest: a BNPL application or credit request still in progress",
                "SELECT (SELECT count(*) FROM bnpl.bnpl_application WHERE status IN ('SUBMITTED', 'AWAITING_DECISION'))"
                        + " + (SELECT count(*) FROM credit.decision_request WHERE status IN ('SUBMITTED', 'COLLECTING',"
                        + " 'READY', 'EVALUATED'))");
        zero.put("rest: an offer OFFERED past its expiry",
                "SELECT count(*) FROM bnpl.bnpl_offer WHERE status = 'OFFERED' AND expires_at <= transaction_timestamp()"
                        + " - interval '5 seconds'");
        zero.put("rest: a payout still in flight (DISPATCHED or UNKNOWN)",
                "SELECT count(*) FROM merchant.merchant_payout WHERE status IN ('DISPATCHED', 'UNKNOWN')");
        zero.put("rest: a card payment still in flight",
                "SELECT count(*) FROM payments.payment_attempt WHERE rail = 'card' AND status IN ('AUTH_DISPATCHED',"
                        + " 'AUTH_UNKNOWN', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'VOID_DISPATCHED', 'VOID_UNKNOWN')");
        zero.put("rest: a dispute OPEN past its response deadline",
                "SELECT count(*) FROM bnpl.bnpl_dispute WHERE status = 'OPEN' AND response_deadline_at <="
                        + " transaction_timestamp() - interval '5 seconds'");
        zero.put("rest: a loan still awaiting its disbursement",
                "SELECT count(*) FROM lending.loan WHERE status = 'PENDING_DISBURSEMENT'");
        return zero;
    }
}
