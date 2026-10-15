package com.finapp.app.lending;

import com.finapp.app.credit.ExposureCensus;
import com.finapp.ledger.TrialBalance;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanSubledgerProof;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;

/**
 * The lending storm's censuses (`P11-TST-001`, the design's section 21.3): every one read in ONE read-only
 * {@code REPEATABLE READ} snapshot of the application role - a census that read in several snapshots could see half of a
 * commit and report a breach that never existed, or miss one that did. Soft assertions, so a failure names every census
 * that caught it. Two strengths: the SAFETY censuses hold at every commit and are read while the load blows as well as
 * at rest; the LIVENESS censuses ("every elapsed date accrued", "one disbursement per non-cancelled loan", nothing in
 * flight) are only meaningful once the sweeps have caught up, and are read at rest.
 *
 * <p>The censuses assert facts about the books, never the answer of an individual racer: under concurrency who won is
 * legitimately nondeterministic; the state of the books is not. A census never names a loan, a party or an amount in what
 * it reports - counts, arms and kinds only, so a failing reading cannot leak into the captured output the needle walks.
 */
final class LendingStormCensus {

    /** How many readings were taken, and how many of them at rest. */
    final AtomicInteger readings = new AtomicInteger();
    final AtomicInteger restReadings = new AtomicInteger();
    /** What the readings cost, for the run summary. */
    final java.util.concurrent.atomic.AtomicLong readingNanos = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong slowestReadingNanos = new java.util.concurrent.atomic.AtomicLong();
    private final LendingProofs proofs;
    private final LendingCapitalProof capital;
    private final long deadlocksBefore;

    LendingStormCensus(LendingProofs proofs, LendingCapitalProof capital) throws SQLException {
        this.proofs = proofs;
        this.capital = capital;
        this.deadlocksBefore = deadlocks();
    }

    /** The database's deadlock count since the storm began (C14) - the gate says none in any storm run. */
    long deadlocksSinceTheStormBegan() throws SQLException {
        return deadlocks() - deadlocksBefore;
    }

    private static long deadlocks() throws SQLException {
        return Long.parseLong(LendingStormWorld.scalar(
                "SELECT deadlocks::text FROM pg_stat_database WHERE datname = current_database()"));
    }

    /** One reading - safety censuses, and at rest the liveness censuses - in one snapshot. */
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
                softly.assertThat(new ExposureCensus().take(snapshot).failing())
                        .as("%s: C3 the exposure census - no party's exposure beyond its decision's limit (INV-LND-06,"
                                + " INV-CRD-09)", when).isZero();
                LendingCapitalProof.Report capitalReport = capital.prove(snapshot);
                softly.assertThat(capitalReport.holds()).as("%s: C4 the capital proof - every capital line a"
                        + " recognition's, headroom never below zero (INV-LND-13): %s", when, capitalReport.currencies())
                        .isTrue();
                softly.assertThat(new TrialBalance().sweep(snapshot).outOfBalance())
                        .as("%s: C5 the trial balance - zero per currency (INV-LED-01)", when).isEmpty();
                zeroes(snapshot, when, softly, safety());
                stampDiagnostics(snapshot, when);
                if (atRest) {
                    zeroes(snapshot, when + " (at rest)", softly, liveness());
                    dpdChecked = one(snapshot, "SELECT count(*) FROM " + DPD + " d");
                    dpdPastDue = one(snapshot, "SELECT count(*) FROM " + DPD + " d WHERE d.dpd > 0");
                }
            } finally {
                snapshot.rollback();
            }
        }
        softly.assertThat(deadlocksSinceTheStormBegan()).as("%s: C14 no deadlock (40P01) in the storm", when).isZero();
        long took = System.nanoTime() - began;
        readingNanos.addAndGet(took);
        slowestReadingNanos.accumulateAndGet(took, Math::max);
    }

    /** C1 and C2 from ONE reading of the lending proofs: every account's subledger proof holds and it replays IDENTICAL. */
    private void proofs(Connection snapshot, String when, SoftAssertions softly) {
        LendingProofs.Reading reading = proofs.prove(snapshot);
        Map<String, Long> arms = new TreeMap<>();
        for (LoanSubledgerProof.Verdict verdict : reading.failures()) {
            verdict.failures().forEach(arm -> arms.merge(arm, 1L, Long::sum));
        }
        Map<String, Long> kinds = new TreeMap<>();
        for (LoanReplayProof.Replay replay : reading.divergences()) {
            replay.checks().forEach(check -> kinds.merge(check.name(), 1L, Long::sum));
        }
        softly.assertThat(reading.failing()).as("%s: C1 the subledger proof - every account's six balances explained by"
                + " its rows (INV-LND-01, -04, -08); failing accounts per arm %s", when, arms).isZero();
        softly.assertThat(reading.replaying(LoanReplayProof.Verdict.DIVERGED)).as("%s: C2 the replay - every account"
                + " IDENTICAL (INV-LND-03, -05, -09); diverged accounts per kind %s", when, kinds).isZero();
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

    /**
     * A failing census's rows, for the investigation - written to {@code build/lending-storm-census.txt}, never to the
     * captured output (the needle walks that, and it must hold no loan or party id).
     */
    private static void forensics(Connection snapshot, String what, String sql) {
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
                    // the snapshot is the census's; the next statement reports it
                }
            }
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("build", "lending-storm-census.txt"), text,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // forensics only - the census's own assertion carries the verdict
        }
    }

    /**
     * A diagnostic, never an assertion (`P11-DOC-001`): the facts whose own clock reading falls after their loan's
     * closure, listed with the record's order and the writing transaction beside it - so a run tells a fact truly written
     * after the closure (a later record order, another transaction; C11 above is red too) from one written before it in
     * the closing transaction on a database clock stepped back between two statements (an earlier record order, the
     * closure's own transaction). Written to the census file only when there is any.
     */
    private static void stampDiagnostics(Connection snapshot, String when) throws SQLException {
        String sql = "SELECT count(*) FROM ("
                + "SELECT 'repayment' AS fact, r.loan_id, r.created_at, l.closed_at, r.recorded_order AS fact_order,"
                + " c.recorded_order AS closure_order, r.xmin::text AS fact_xmin, c.xmin::text AS closure_xmin FROM"
                + " lending.repayment r JOIN lending.loan l ON l.id = r.loan_id JOIN lending.loan_closure c ON"
                + " c.loan_id = l.id WHERE r.created_at > l.closed_at UNION ALL SELECT 'draw' AS fact, d.loan_id,"
                + " d.created_at, l.closed_at, d.recorded_order AS fact_order, c.recorded_order AS closure_order,"
                + " d.xmin::text AS fact_xmin, c.xmin::text AS closure_xmin FROM lending.credit_line_draw d JOIN"
                + " lending.loan l ON l.id = d.loan_id JOIN lending.loan_closure c ON c.loan_id = l.id WHERE"
                + " d.created_at > l.closed_at UNION ALL SELECT 'billing' AS fact, b.loan_id, b.created_at,"
                + " l.closed_at, b.recorded_order AS fact_order, c.recorded_order AS closure_order, b.xmin::text AS"
                + " fact_xmin, c.xmin::text AS closure_xmin FROM lending.instalment_billing b JOIN lending.loan l ON"
                + " l.id = b.loan_id JOIN lending.loan_closure c ON c.loan_id = l.id WHERE b.created_at > l.closed_at"
                + " UNION ALL SELECT 'statement' AS fact, s.loan_id, s.created_at, l.closed_at, s.recorded_order AS"
                + " fact_order, c.recorded_order AS closure_order, s.xmin::text AS fact_xmin, c.xmin::text AS"
                + " closure_xmin FROM lending.credit_line_statement s JOIN lending.loan l ON l.id = s.loan_id JOIN"
                + " lending.loan_closure c ON c.loan_id = l.id WHERE s.created_at > l.closed_at UNION ALL SELECT"
                + " 'condition' AS fact, e.loan_id, e.created_at, l.closed_at, NULL::bigint AS fact_order,"
                + " c.recorded_order AS closure_order, e.xmin::text AS fact_xmin, c.xmin::text AS closure_xmin FROM"
                + " lending.loan_condition_event e JOIN lending.loan l ON l.id = e.loan_id JOIN lending.loan_closure c"
                + " ON c.loan_id = l.id WHERE e.created_at > l.closed_at"
                + ") stamped";
        if (one(snapshot, sql) != 0) {
            forensics(snapshot, when + ": DIAGNOSTIC (not asserted) - facts stamped after their loan's closure", sql);
        }
    }

    private static long one(Connection snapshot, String sql) throws SQLException {
        try (Statement read = snapshot.createStatement(); ResultSet row = read.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** The zone each loan is serviced in - its first agreement's pinned terms. */
    private static final String ZONES = "(SELECT g.loan_id, t.servicing_zone FROM lending.loan_agreement g"
            + " JOIN lending.loan_terms t ON t.terms_version_id = g.terms_version_id WHERE g.version = 1)";

    /**
     * C9's own derivation of every account in servicing's days past due, computed here and not by the platform's
     * {@code lending.days_past_due}: its business date (the snapshot's database clock in its pinned zone) less the oldest
     * due date of a billed item - an instalment's billing or a line's statement - fallen due before that date with any
     * component still unpaid; zero when there is none.
     */
    static final String DPD = "(SELECT l.id AS loan_id, t.today, coalesce(t.today - (SELECT min(i.due_date) FROM (SELECT"
            + " b.id, b.due_date FROM lending.instalment_billing b WHERE b.loan_id = l.id UNION ALL SELECT s.id, s.due_date"
            + " FROM lending.credit_line_statement s WHERE s.loan_id = l.id) i WHERE i.due_date < t.today AND EXISTS"
            + " (SELECT 1 FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) c(component) WHERE"
            + " lending.billing_component_paid(i.id, c.component) < lending.billing_component_billed(i.id, c.component))),"
            + " 0) AS dpd FROM lending.loan l JOIN (SELECT g.loan_id, w.servicing_zone FROM lending.loan_agreement g"
            + " JOIN lending.loan_terms w ON w.terms_version_id = g.terms_version_id WHERE g.version = 1)"
            + " z ON z.loan_id = l.id CROSS JOIN LATERAL (SELECT lending.business_date(transaction_timestamp(),"
            + " z.servicing_zone) AS today) t WHERE l.status IN ('ACTIVE', 'CLOSING'))";

    /** The accounts C9's date difference was judged on at the last reading at rest, and those of them past due. */
    volatile long dpdChecked;
    volatile long dpdPastDue;

    /** The business date of the closure of each closed loan, in its zone. */
    private static final String CLOSED_ON = "(SELECT l.id AS loan_id, lending.business_date(l.closed_at, z.servicing_zone)"
            + " AS closed_on FROM lending.loan l JOIN " + ZONES + " z ON z.loan_id = l.id WHERE l.status = 'CLOSED')";

    /** The SAFETY censuses: zero at every commit, read while the load blows. */
    static Map<String, String> safety() {
        Map<String, String> zero = new LinkedHashMap<>();
        // C6 - accruals (INV-LND-02, INV-LND-10, INV-IDEM-02).
        zero.put("C6 an accrual date born twice for one account",
                "SELECT count(*) FROM (SELECT loan_id, accrual_date FROM lending.interest_accrual GROUP BY 1, 2"
                        + " HAVING count(*) > 1) twice");
        zero.put("C6 an accrual entry not under its key lending.accrual:<loan>:<date>, or not dated its date",
                "SELECT count(*) FROM lending.interest_accrual a JOIN ledger.journal_entry e ON e.id = a.journal_entry_id"
                        + " WHERE e.idempotency_scope <> 'ledger.post:lending.accrual:' || a.loan_id || ':' || a.accrual_date"
                        + " OR e.value_date <> a.accrual_date");
        zero.put("C6 two accrual entries under one key",
                "SELECT count(*) FROM (SELECT idempotency_scope FROM ledger.journal_entry WHERE idempotency_scope LIKE"
                        + " 'ledger.post:lending.accrual:%' GROUP BY 1 HAVING count(*) > 1) twice");
        zero.put("C6 an accrual written EARLY - for a date not yet ended on the database clock in the pinned zone",
                "SELECT count(*) FROM lending.interest_accrual a JOIN " + ZONES + " z ON z.loan_id = a.loan_id"
                        + " WHERE a.accrual_date >= lending.business_date(a.created_at, z.servicing_zone)");
        zero.put("C6 an accrual dated before its instalment loan's accrual start",
                "SELECT count(*) FROM lending.interest_accrual a JOIN lending.accrual_start s ON s.loan_id = a.loan_id"
                        + " WHERE a.accrual_date < s.start_date");
        // C7 - the disbursement and the payout (INV-LND-06, INV-LND-07).
        zero.put("C7 two disbursement entries for one loan",
                "SELECT count(*) FROM (SELECT idempotency_scope FROM ledger.journal_entry WHERE idempotency_scope LIKE"
                        + " 'ledger.post:lending.disbursement:%' GROUP BY 1 HAVING count(*) > 1) twice");
        zero.put("C7 an instalment loan in servicing or closed without its POSTED disbursement and entry",
                "SELECT count(*) FROM lending.loan l WHERE l.kind = 'INSTALMENT' AND l.status IN ('ACTIVE', 'CLOSING',"
                        + " 'CLOSED') AND NOT EXISTS (SELECT 1 FROM lending.loan_disbursement d WHERE d.loan_id = l.id"
                        + " AND d.status = 'POSTED' AND d.journal_entry_id IS NOT NULL)");
        zero.put("C7 a POSTED disbursement beside a loan not in servicing or closed (the receivable born apart from the"
                + " wallet credit)", "SELECT count(*) FROM lending.loan_disbursement d JOIN lending.loan l ON l.id ="
                        + " d.loan_id WHERE d.status = 'POSTED' AND l.status NOT IN ('ACTIVE', 'CLOSING', 'CLOSED')");
        zero.put("C7 a disbursement entry no POSTED disbursement names",
                "SELECT count(*) FROM ledger.journal_entry e WHERE e.idempotency_scope LIKE"
                        + " 'ledger.post:lending.disbursement:%' AND NOT EXISTS (SELECT 1 FROM lending.loan_disbursement d"
                        + " WHERE d.journal_entry_id = e.id AND d.status = 'POSTED')");
        zero.put("C7 one decision, two accounts",
                "SELECT count(*) FROM (SELECT decision_id FROM lending.loan GROUP BY 1 HAVING count(*) > 1) twice");
        zero.put("C7 a loan whose decision credit has not consumed exactly once, for its agreement",
                "SELECT count(*) FROM lending.loan l WHERE (SELECT count(*) FROM credit.credit_decision_consumption c"
                        + " WHERE c.decision_id = l.decision_id) <> 1");
        zero.put("C7 two withdrawals for one payout",
                "SELECT count(*) FROM (SELECT instruction_reference FROM payments.withdrawal WHERE instruction_reference"
                        + " LIKE 'lending:%' GROUP BY 1 HAVING count(*) > 1) twice");
        zero.put("C7 a payout whose status disagrees with payments' (PAID_OUT only on COMPLETED, FAILED only on FAILED,"
                + " DISPATCHED only beside a withdrawal)", "SELECT count(*) FROM lending.loan_payout p"
                        + " LEFT JOIN payments.withdrawal w ON w.id = p.withdrawal_id WHERE (p.status = 'PAID_OUT'"
                        + " AND w.status IS DISTINCT FROM 'COMPLETED') OR (p.status = 'FAILED' AND w.status IS DISTINCT FROM"
                        + " 'FAILED') OR (p.status = 'DISPATCHED' AND w.id IS NULL) OR (p.status IN ('PENDING',"
                        + " 'NOT_DISPATCHED') AND p.withdrawal_id IS NOT NULL)");
        zero.put("C7 a payout's withdrawal not the loan's instruction",
                "SELECT count(*) FROM lending.loan_payout p JOIN payments.withdrawal w ON w.id = p.withdrawal_id"
                        + " WHERE w.instruction_reference <> 'lending:' || p.loan_id");
        zero.put("C7 an external payout's interest started before its terminal outcome",
                "SELECT count(*) FROM lending.loan_payout p JOIN lending.accrual_start s ON s.loan_id = p.loan_id"
                        + " WHERE p.status NOT IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED')");
        // C8 - allocation conserves, never over-pays (INV-LND-04).
        zero.put("C8 a repayment whose allocations do not sum to its amount",
                "SELECT count(*) FROM lending.repayment r WHERE r.amount_minor <> (SELECT coalesce(sum(a.amount_minor), 0)"
                        + " FROM lending.repayment_allocation a WHERE a.repayment_id = r.id)");
        zero.put("C8 a billed component paid beyond what was billed",
                "SELECT count(*) FROM (SELECT b.id FROM lending.instalment_billing b UNION ALL SELECT s.id FROM"
                        + " lending.credit_line_statement s) item CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL'))"
                        + " c(component) WHERE lending.billing_component_paid(item.id, c.component)"
                        + " > lending.billing_component_billed(item.id, c.component)");
        zero.put("C8 a reversal whose negated allocations are not its repayment's, exactly",
                "SELECT count(*) FROM lending.repayment_reversal v WHERE v.status = 'APPROVED' AND ((SELECT"
                        + " coalesce(sum(n.amount_minor), 0) FROM lending.repayment_allocation_reversal n WHERE"
                        + " n.reversal_id = v.id) <> (SELECT r.amount_minor FROM lending.repayment r WHERE r.id ="
                        + " v.repayment_id) OR EXISTS (SELECT 1 FROM lending.repayment_allocation_reversal n JOIN"
                        + " lending.repayment_allocation a ON a.id = n.allocation_id WHERE n.reversal_id = v.id AND"
                        + " a.repayment_id <> v.repayment_id))");
        // C9 - delinquency is derived and append-only (INV-LND-12).
        zero.put("C9 a condition whose bucket or default disagrees with its own days past due and the pinned bounds",
                "SELECT count(*) FROM lending.loan_condition_event e WHERE (e.kind = 'DELINQUENCY' AND e.bucket <> CASE"
                        + " WHEN e.days_past_due = 0 THEN 'CURRENT' WHEN e.days_past_due < 30 THEN 'B1_1_29' WHEN"
                        + " e.days_past_due < 60 THEN 'B2_30_59' WHEN e.days_past_due < 90 THEN 'B3_60_89' ELSE"
                        + " 'B4_90_PLUS' END) OR (e.kind = 'DEFAULT' AND e.defaulted AND e.days_past_due < 90)");
        zero.put("C9 two conditions of one kind for one account and business date",
                "SELECT count(*) FROM (SELECT loan_id, business_date, kind FROM lending.loan_condition_event GROUP BY"
                        + " 1, 2, 3 HAVING count(*) > 1) twice");
        // A recorded condition's DPD is a date difference: its business date less the due date of one of the account's
        // billed items (the oldest then unpaid) - never a counter that could have drifted (INV-LND-12).
        zero.put("C9 a condition past due whose business date less its DPD is no billed item's due date",
                "SELECT count(*) FROM lending.loan_condition_event e WHERE e.days_past_due > 0 AND NOT EXISTS (SELECT 1"
                        + " FROM lending.instalment_billing b WHERE b.loan_id = e.loan_id AND b.due_date = e.business_date"
                        + " - e.days_past_due) AND NOT EXISTS (SELECT 1 FROM lending.credit_line_statement s WHERE"
                        + " s.loan_id = e.loan_id AND s.due_date = e.business_date - e.days_past_due)");
        // C10 - a line's drawn principal never above its limit (INV-LND-14), but by the repayments a four-eyes reversal
        // restored: a reversed paydown re-establishes principal the borrower had drawn, and may lift it above a limit
        // since re-drawn (-023's owner-approved path). Bounded by exactly those reversals - never a line excused for ever
        // because it once had one (P11-DOC-001; read "AND NOT EXISTS (an APPROVED reversal)" until then).
        zero.put("C10 a line's principal above its limit beyond what its approved reversals restored",
                "SELECT count(*) FROM lending.loan l JOIN lending.loan_agreement g ON g.loan_id = l.id AND g.version ="
                        + " (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = l.id)"
                        + " WHERE l.kind = 'REVOLVING' AND (SELECT coalesce(sum(CASE j.direction WHEN 'DEBIT' THEN"
                        + " j.amount_minor ELSE -j.amount_minor END), 0) FROM ledger.ledger_account a JOIN"
                        + " ledger.journal_line j ON j.ledger_account_id = a.id WHERE a.owner_kind = 'LOAN' AND"
                        + " a.owner_ref = l.id AND a.purpose IN ('LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE')) > g.amount_minor"
                        + " + (SELECT coalesce(sum(r.amount_minor), 0) FROM lending.repayment_reversal v JOIN"
                        + " lending.repayment r ON r.id = v.repayment_id WHERE v.loan_id = l.id AND v.status = 'APPROVED')");
        // C11 - closed is closed (INV-LND-08).
        zero.put("C11 a CLOSED loan without its one closure",
                "SELECT count(*) FROM lending.loan l WHERE l.status = 'CLOSED' AND (SELECT count(*) FROM"
                        + " lending.loan_closure c WHERE c.loan_id = l.id) <> 1");
        zero.put("C11 a closure beside a loan that is not CLOSED",
                "SELECT count(*) FROM lending.loan_closure c JOIN lending.loan l ON l.id = c.loan_id"
                        + " WHERE l.status <> 'CLOSED'");
        zero.put("C11 an accrual dated after its loan's closure date",
                "SELECT count(*) FROM lending.interest_accrual a JOIN " + CLOSED_ON + " c ON c.loan_id = a.loan_id"
                        + " WHERE a.accrual_date > c.closed_on");
        zero.put("C11 a draw, fee or condition dated after its loan's closure date",
                "SELECT (SELECT count(*) FROM lending.credit_line_draw d JOIN " + CLOSED_ON + " c ON c.loan_id ="
                        + " d.loan_id WHERE d.value_date > c.closed_on) + (SELECT count(*) FROM lending.fee_assessment f"
                        + " JOIN " + CLOSED_ON + " c ON c.loan_id = f.loan_id WHERE f.business_date > c.closed_on)"
                        + " + (SELECT count(*) FROM lending.loan_condition_event e JOIN " + CLOSED_ON + " c ON c.loan_id ="
                        + " e.loan_id WHERE e.business_date > c.closed_on)");
        // A payoff bills every unbilled instalment early (and states a line's current cycle early) BESIDE its closure -
        // due dates after the closure by design (lending V022); what may never be is a billing written after it.
        // "After" is the record's own order (lending V025's recorded_order, drawn under the loan's row lock), never two
        // statements' clock readings: on a database clock stepped back between the closing transaction's statements, a
        // fact written BEFORE the closure in that same transaction reads "after" it. The first run of the storm on the
        // final code (P11-DOC-001, 2026-10-15) was red here - two rows, round 1, unlisted - and these censuses read
        // "b.created_at > l.closed_at" (and its siblings) until then; split one per fact so a failing census lists its rows.
        zero.put("C11 a billing written after its loan's closure (the record's order)",
                "SELECT count(*) FROM lending.instalment_billing b JOIN lending.loan_closure c ON c.loan_id = b.loan_id"
                        + " WHERE b.recorded_order > c.recorded_order");
        zero.put("C11 a statement written after its line's closure (the record's order)",
                "SELECT count(*) FROM lending.credit_line_statement s JOIN lending.loan_closure c ON c.loan_id = s.loan_id"
                        + " WHERE s.recorded_order > c.recorded_order");
        zero.put("C11 a repayment written after the loan's closure (the record's order)",
                "SELECT count(*) FROM lending.repayment r JOIN lending.loan_closure c ON c.loan_id = r.loan_id"
                        + " WHERE r.recorded_order > c.recorded_order");
        zero.put("C11 a draw written after its line's closure (the record's order)",
                "SELECT count(*) FROM lending.credit_line_draw d JOIN lending.loan_closure c ON c.loan_id = d.loan_id"
                        + " WHERE d.recorded_order > c.recorded_order");
        // A condition carries no record order: written by another transaction than the closure's (its xmin), and more
        // than the database clock's largest backward step (ClockStep's 2 s, with margin) after it. The every-writer
        // trigger refuses a condition on a terminal account outright (lending V020); this is its census.
        zero.put("C11 a condition written after the loan's closure",
                "SELECT count(*) FROM lending.loan_condition_event e JOIN lending.loan l ON l.id = e.loan_id"
                        + " JOIN lending.loan_closure c ON c.loan_id = l.id WHERE e.xmin::text <> c.xmin::text"
                        + " AND e.created_at > l.closed_at + interval '5 seconds'");
        // A line's closure request and a draw are written under the line's row lock (L3), each stamped with its own
        // statement's database clock: a draw stamped after the request was drawn on a line already closing (I5).
        zero.put("C11 a draw written after its line's closure request",
                "SELECT count(*) FROM lending.credit_line_draw d JOIN lending.line_closure_request q ON q.loan_id ="
                        + " d.loan_id WHERE d.created_at > q.requested_at");
        zero.put("C11 an edge out of CLOSED or CANCELLED",
                "SELECT count(*) FROM lending.loan_event WHERE from_status IN ('CLOSED', 'CANCELLED')");
        // C12 - every state change's event exactly once (the outbox written in the fact's own transaction).
        zero.put("C12 an (aggregate, event type) whose events are not its facts exactly once", outboxMismatches());
        // C22 - offers: never both accepted and expired; every acceptance before the expiry on the database clock.
        zero.put("C22 an offer accepted and expired",
                "SELECT count(*) FROM lending.loan_offer o WHERE (SELECT count(*) FROM lending.loan_offer_event e"
                        + " WHERE e.offer_id = o.id AND e.to_status IN ('ACCEPTED', 'EXPIRED')) > 1");
        zero.put("C22 an acceptance at or after its offer's expiry",
                "SELECT count(*) FROM lending.loan_acceptance a JOIN lending.loan_offer o ON o.id = a.offer_id"
                        + " WHERE a.accepted_at >= o.expires_at");
        // C23 - the ledger's dates and the deferred write-off (P11-DOC-001: the gate's Ledger criterion, asserted over
        // the data rather than the code's names). Every lending entry is posted and valued on one date; nothing posts
        // to the write-off expense, seeded for Phase 14 and closed until then.
        zero.put("C23 a lending entry whose posting date is not its value date",
                "SELECT count(*) FROM ledger.journal_entry e WHERE (e.idempotency_scope LIKE 'ledger.post:lending.%'"
                        + " OR e.idempotency_scope LIKE 'ledger.reverse:lending.%') AND e.posting_date <> e.value_date");
        zero.put("C23 a journal line on LOAN_WRITE_OFF_EXPENSE (write-off is Phase 14's)",
                "SELECT count(*) FROM ledger.journal_line j JOIN ledger.ledger_account a ON a.id = j.ledger_account_id"
                        + " WHERE a.purpose = 'LOAN_WRITE_OFF_EXPENSE'");
        return zero;
    }

    /**
     * C12's facts: each lending fact, by its aggregate and the event type that announces it - counted once per fact. The
     * census joins them to the outbox's events per (aggregate, type): every count equal, in one statement.
     */
    private static final String[][] FACTS = {
        {"lending.LoanAccepted", "SELECT id, 1 FROM lending.loan"},
        {"lending.LoanDisbursed", "SELECT loan_id, 1 FROM lending.loan_disbursement WHERE status = 'POSTED'"},
        {"lending.LoanCancelled", "SELECT id, 1 FROM lending.loan WHERE status = 'CANCELLED'"},
        {"lending.LoanPayoutConcluded", "SELECT loan_id, 1 FROM lending.loan_payout WHERE status IN ('PAID_OUT', 'FAILED',"
                + " 'NOT_DISPATCHED')"},
        {"lending.InstalmentBilled", "SELECT loan_id, 1 FROM lending.instalment_billing"},
        {"lending.CreditLineStatementIssued", "SELECT loan_id, 1 FROM lending.credit_line_statement"},
        {"lending.CreditLineDrawn", "SELECT loan_id, 1 FROM lending.credit_line_draw"},
        {"lending.RepaymentReceived", "SELECT loan_id, 1 FROM lending.repayment"},
        {"lending.RepaymentReversed", "SELECT loan_id, 1 FROM lending.repayment_reversal WHERE status = 'APPROVED'"},
        {"lending.CollectionFailed", "SELECT loan_id, 1 FROM lending.collection_attempt WHERE outcome IN"
                + " ('INSUFFICIENT_FUNDS', 'WALLET_NOT_POSTABLE')"},
        {"lending.LoanFeeAssessed", "SELECT loan_id, 1 FROM lending.fee_assessment"},
        {"lending.LoanFeeWaived", "SELECT w.loan_id, 1 FROM lending.waiver w JOIN lending.waiver_decision d"
                + " ON d.waiver_id = w.id WHERE w.kind = 'FEE' AND d.outcome = 'APPROVED'"},
        {"lending.LoanInterestWaived", "SELECT w.loan_id, 1 FROM lending.waiver w JOIN lending.waiver_decision d"
                + " ON d.waiver_id = w.id WHERE w.kind = 'INTEREST' AND d.outcome = 'APPROVED'"},
        {"lending.LoanDelinquencyChanged", "SELECT loan_id, 1 FROM lending.loan_condition_event WHERE kind = 'DELINQUENCY'"},
        {"lending.LoanDefaulted", "SELECT loan_id, 1 FROM lending.loan_condition_event WHERE kind = 'DEFAULT' AND defaulted"},
        {"lending.LoanDefaultCleared", "SELECT loan_id, 1 FROM lending.loan_condition_event WHERE kind = 'DEFAULT'"
                + " AND NOT defaulted"},
        {"lending.LoanAmended", "SELECT loan_id, 1 FROM lending.loan_agreement WHERE version > 1"},
        {"lending.LoanClosed", "SELECT loan_id, 1 FROM lending.loan_closure"},
        {"lending.LineClosureRequested", "SELECT loan_id, 1 FROM lending.line_closure_request"},
        {"lending.LoanOffered", "SELECT id, 1 FROM lending.loan_offer"},
        {"lending.LoanOfferClosed", "SELECT id, 1 FROM lending.loan_offer WHERE status IN ('DECLINED', 'EXPIRED')"},
        {"lending.LoanApplicationSubmitted", "SELECT id, 1 FROM lending.loan_application"},
        {"lending.LoanApplicationClosed", "SELECT id, 1 FROM lending.loan_application WHERE status IN ('DECLINED',"
                + " 'CLOSED_UNDECIDED', 'WITHDRAWN')"},
        {"lending.LoanTermsVersionActivated", "SELECT id, 1 FROM lending.loan_terms_version WHERE status IN ('ACTIVE',"
                + " 'RETIRED')"},
    };

    /** C12 as one statement: the (aggregate, event type) pairs whose fact count and event count differ. */
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
     * The LIVENESS censuses: meaningful once the sweeps have caught up - read at rest, every round. Each judges the clock
     * at the SNAPSHOT's own instant ({@code transaction_timestamp()}): the census's readings take seconds, and a later
     * statement's clock against the snapshot's rows would see a claim's lease lapse that the rows cannot yet show.
     */
    static Map<String, String> liveness() {
        Map<String, String> zero = new LinkedHashMap<>();
        zero.put("C6 an account in servicing whose last ended date, with principal outstanding at its end, has no accrual",
                "SELECT count(*) FROM lending.loan l JOIN " + ZONES + " z ON z.loan_id = l.id"
                        + " CROSS JOIN LATERAL (SELECT lending.business_date(transaction_timestamp(), z.servicing_zone) - 1"
                        + " AS yesterday) y LEFT JOIN lending.accrual_start s ON s.loan_id = l.id"
                        + " WHERE l.status IN ('ACTIVE', 'CLOSING') AND (l.kind = 'REVOLVING' OR s.start_date <= y.yesterday)"
                        + " AND lending.business_date(l.opened_at, z.servicing_zone) <= y.yesterday"
                        + " AND (SELECT coalesce(sum(CASE j.direction WHEN 'DEBIT' THEN j.amount_minor ELSE -j.amount_minor"
                        + " END), 0) FROM ledger.ledger_account a JOIN ledger.journal_line j ON j.ledger_account_id = a.id"
                        + " JOIN ledger.journal_entry e ON e.id = j.entry_id WHERE a.owner_kind = 'LOAN' AND a.owner_ref ="
                        + " l.id AND a.purpose IN ('LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE') AND e.value_date <= y.yesterday) > 0"
                        + " AND NOT EXISTS (SELECT 1 FROM lending.interest_accrual a WHERE a.loan_id = l.id"
                        + " AND a.accrual_date = y.yesterday)");
        zero.put("C7 a loan still awaiting its disbursement at rest",
                "SELECT count(*) FROM lending.loan WHERE status = 'PENDING_DISBURSEMENT'");
        zero.put("C7 a non-cancelled instalment loan without exactly one disbursement entry",
                "SELECT count(*) FROM lending.loan l WHERE l.kind = 'INSTALMENT' AND l.status <> 'CANCELLED'"
                        + " AND (SELECT count(*) FROM ledger.journal_entry e WHERE e.idempotency_scope ="
                        + " 'ledger.post:lending.disbursement:' || l.id) <> 1");
        zero.put("C7 a payout still in flight at rest",
                "SELECT count(*) FROM lending.loan_payout WHERE status IN ('PENDING', 'DISPATCHING', 'DISPATCHED')");
        zero.put("C7 a concluded external payout without its accrual start",
                "SELECT count(*) FROM lending.loan_payout p JOIN lending.loan l ON l.id = p.loan_id WHERE p.status IN"
                        + " ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED') AND l.status <> 'CANCELLED' AND NOT EXISTS (SELECT 1"
                        + " FROM lending.accrual_start s WHERE s.loan_id = p.loan_id)");
        zero.put("C9 an account whose DPD is not its business date less its oldest unpaid due date (INV-LND-12)",
                "SELECT count(*) FROM " + DPD + " d WHERE lending.days_past_due(d.loan_id, d.today) <> d.dpd");
        zero.put("rest: an account in servicing not yet serviced for its business date",
                "SELECT count(*) FROM lending.loan WHERE status IN ('ACTIVE', 'CLOSING')"
                        + " AND next_servicing_at <= transaction_timestamp()");
        return zero;
    }

    /** Every census's name - for the run summary and the design's map. */
    static List<String> names() {
        List<String> names = new java.util.ArrayList<>(List.of("C1 subledger", "C2 replay", "C3 exposure", "C4 capital",
                "C5 trial balance", "C14 deadlocks"));
        names.addAll(safety().keySet());
        names.addAll(liveness().keySet());
        return names;
    }
}
