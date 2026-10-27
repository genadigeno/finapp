package com.finapp.app.risk;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.AlertBornOnceCensus;
import com.finapp.risk.AssessmentCensus;
import com.finapp.risk.CaseOneOpenCensus;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.JudgedCommands;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.ProofVerdict;
import com.finapp.risk.RescreeningCensus;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskProofReads;
import com.finapp.risk.VelocityProof;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;

/**
 * The risk storm's census (`P13-TST-001`; {@code PHASE_13_PLAN.md} section 13.3, the entry's "at rest, every round"):
 * every reading ONE read-only {@code REPEATABLE READ} snapshot of the application role (the Phase 11 lesson I2), holding
 * the storm's own statements - written from the owners' tables and risk's, never from the code under test - beside the
 * production censuses read on the same snapshot ({@link AssessmentCensus}, {@link MonitoringCensus},
 * {@link AlertBornOnceCensus}, {@link CaseOneOpenCensus}, {@link FilingCensus}, {@link RescreeningCensus} and
 * {@link VelocityProof} - the six census gauges' and the velocity gauge's inputs). A check names a count of violations
 * and at most a few references; it corrects nothing.
 *
 * <ul>
 *   <li><strong>C1 assessments = judged commands</strong>, per operation, both directions, and one per command;
 *       "neither" - a command the storm started after its operation's first activation answered, sealed with no policy.
 *   <li><strong>C2 windows = their evidence</strong>: every velocity window's count and sum exactly the COUNTED
 *       assessments carrying its subject whose cut falls in it, both directions, every dimension and window kind; and
 *       every reading T-eval sealed equal to the window's figure before it (the battery's item 73, exercised for real).
 *   <li><strong>C3 monitored = journal</strong> up to the watermark, once each.
 *   <li><strong>C4 alerts born once</strong> per key; <strong>C5</strong> one open case per (subject, kind), every
 *       attachment coherent, no alert of a folding kind left OPEN.
 *   <li><strong>C6</strong> every DISPATCHED action concluded or within its permit, every APPROVED one dispatched or
 *       within its permit; one owner's key per dispatched action.
 *   <li><strong>C7</strong> every FILED report acknowledged or within its inquiry permit.
 *   <li><strong>C8 held holds = HELD withdrawals</strong>, both directions; a refused or expired held withdrawal's hold
 *       released; at most one exit from HELD.
 *   <li><strong>C9</strong> the trial balance zero per currency; every journal entry balanced.
 *   <li><strong>C10</strong> signals born once per source event; every delivered signal-source event recorded.
 *   <li><strong>C11</strong> the audit: every risk act's rows against its audit records.
 *   <li><strong>C12</strong> the production censuses' verdicts, never DIVERGED.
 * </ul>
 */
final class RiskStormCensus {

    /** One check's outcome: its name, how many violations, and a few references. */
    record Check(String name, long violations, String detail) {}

    /** One reading: every check, in order, and the figures the summary prints. */
    record Reading(String label, Instant asOf, List<Check> checks, Map<String, Long> figures) {

        List<Check> failing() {
            return checks.stream().filter(check -> check.violations() != 0).toList();
        }
    }

    /** The storm's own knowledge the census needs: the command references started after each first activation. */
    interface Knowledge {

        /** Per operation, the command references the storm started after that operation's first activation answered. */
        Map<String, Set<String>> startedAfterActivation();

        /** The consumed events the storm's relay delivered at least once. */
        Set<String> delivered();

        /** The parties a scene forced unavailable - the only parties an unavailable fail-safe may seal for. */
        Set<String> forced();
    }

    private final ApplicationContext beans;
    private final Supplier<Knowledge> knowledge;

    RiskStormCensus(ApplicationContext beans, Supplier<Knowledge> knowledge) {
        this.beans = beans;
        this.knowledge = knowledge;
    }

    /** Reads every check in ONE read-only REPEATABLE READ snapshot of the application role. */
    Reading read(String label) throws SQLException {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            try {
                List<Check> checks = new ArrayList<>();
                Map<String, Long> figures = new LinkedHashMap<>();
                Instant asOf = Instant.parse(RiskStormWorld.row(snapshot, "SELECT to_char(now() AT TIME ZONE 'UTC',"
                        + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")[0]);
                assessments(snapshot, checks, figures);
                windows(snapshot, checks, figures);
                monitoring(snapshot, checks, figures);
                alertsAndCases(snapshot, checks, figures);
                actionsAndFilings(snapshot, checks, figures);
                holds(snapshot, checks, figures);
                ledger(snapshot, checks);
                signals(snapshot, checks, figures);
                audit(snapshot, checks);
                production(snapshot, asOf, checks);
                return new Reading(label, asOf, List.copyOf(checks), figures);
            } finally {
                snapshot.rollback();
            }
        }
    }

    // ================================================================== C1

    private static final String JUDGED_TRANSFERS = "SELECT id::text AS ref FROM transfers.transfer WHERE status IN"
            + " ('COMPLETED', 'REVERSED') OR failure_reason IN ('LIMIT_REFUSED', 'RISK_REFUSED')";
    private static final String JUDGED_WITHDRAWALS = "SELECT id::text AS ref FROM payments.withdrawal WHERE"
            + " instruction_reference IS NULL";
    private static final String JUDGED_CARD = "SELECT i.id::text AS ref FROM payments.payment_intent i"
            + " JOIN paymentmethods.payment_method m ON m.id = i.payment_method_id"
            + " JOIN ledger.ledger_account a ON a.id = i.credit_account_id WHERE m.kind = 'CARD_TOKEN'"
            + " AND a.purpose = 'MERCHANT_PAYABLE' AND i.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')";
    private static final String JUDGED_WALLET = "SELECT i.id::text AS ref FROM payments.payment_intent i"
            + " JOIN ledger.ledger_account a ON a.id = i.credit_account_id WHERE i.debit_account_id IS NOT NULL"
            + " AND a.purpose = 'MERCHANT_PAYABLE' AND i.status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')";

    private void assessments(Connection snapshot, List<Check> checks, Map<String, Long> figures) throws SQLException {
        checks.add(check(snapshot, "C1 one assessment per command", "SELECT operation || ':' || command_ref FROM"
                + " risk.risk_assessment GROUP BY operation, command_ref HAVING count(*) > 1"));
        Map<String, String> judged = Map.of("TRANSFER", JUDGED_TRANSFERS, "WITHDRAWAL", JUDGED_WITHDRAWALS,
                "CARD_PAYMENT", JUDGED_CARD, "WALLET_PAYMENT", JUDGED_WALLET);
        for (Map.Entry<String, String> operation : judged.entrySet()) {
            checks.add(check(snapshot, "C1 " + operation.getKey() + " judged without its assessment",
                    "SELECT j.ref FROM (" + operation.getValue() + ") j WHERE NOT EXISTS (SELECT 1 FROM"
                            + " risk.risk_assessment a WHERE a.operation = '" + operation.getKey()
                            + "' AND a.command_ref = j.ref)"));
        }
        checks.add(check(snapshot, "C1 TRANSFER assessment without its transfer", "SELECT a.command_ref FROM"
                + " risk.risk_assessment a WHERE a.operation = 'TRANSFER' AND NOT EXISTS (SELECT 1 FROM"
                + " transfers.transfer t WHERE t.id::text = a.command_ref)"));
        checks.add(check(snapshot, "C1 WITHDRAWAL assessment without its withdrawal", "SELECT a.command_ref FROM"
                + " risk.risk_assessment a WHERE a.operation = 'WITHDRAWAL' AND NOT EXISTS (SELECT 1 FROM"
                + " payments.withdrawal w WHERE w.id::text = a.command_ref)"));
        checks.add(check(snapshot, "C1 payment assessment without its judged intent", "SELECT a.command_ref FROM"
                + " risk.risk_assessment a WHERE (a.operation = 'CARD_PAYMENT' AND a.command_ref NOT IN (SELECT ref FROM ("
                + JUDGED_CARD + ") c)) OR (a.operation = 'WALLET_PAYMENT' AND a.command_ref NOT IN (SELECT ref FROM ("
                + JUDGED_WALLET + ") w))"));
        // Cross-border: a permitting seal is exactly a payment row (a permit later refused rolls back with its seal);
        // a refusing seal leaves crossborder no row (section 7.3) - and no payment exists unsealed.
        checks.add(check(snapshot, "C1 CROSS_BORDER payment without its assessment", "SELECT p.quote_id::text FROM"
                + " crossborder.payment p WHERE NOT EXISTS (SELECT 1 FROM risk.risk_assessment a WHERE a.operation ="
                + " 'CROSS_BORDER' AND a.command_ref = p.quote_id::text)"));
        checks.add(check(snapshot, "C1 CROSS_BORDER permit without its payment", "SELECT a.command_ref FROM"
                + " risk.risk_assessment a WHERE a.operation = 'CROSS_BORDER' AND (a.decision = 'ALLOW' OR"
                + " (a.decision = 'CHALLENGE' AND a.challenge_satisfied)) AND NOT EXISTS (SELECT 1 FROM"
                + " crossborder.payment p WHERE p.quote_id::text = a.command_ref)"));
        // "Neither": every command the storm started after its operation's first activation answered pins a version.
        long neither = 0;
        StringBuilder named = new StringBuilder();
        for (Map.Entry<String, Set<String>> after : knowledge.get().startedAfterActivation().entrySet()) {
            if (after.getValue().isEmpty()) {
                continue;
            }
            for (String[] row : RiskStormWorld.rows(snapshot, "SELECT command_ref FROM risk.risk_assessment WHERE"
                    + " operation = ? AND policy_version_id IS NULL AND command_ref = ANY (?)", after.getKey(),
                    snapshot.createArrayOf("text", after.getValue().toArray()))) {
                neither++;
                if (named.length() < 200) {
                    named.append(after.getKey()).append(':').append(row[0]).append(' ');
                }
            }
        }
        checks.add(new Check("C1 neither - a command after its first activation sealed unpinned", neither,
                named.toString()));
        // The budget held wherever the storm did not break it: an unavailable fail-safe (a timeout, a failed read) is
        // sealed only for a party a scene forced - one elsewhere is an evaluation past its 250 ms budget under the load's
        // own contention (a lock order skipped, a subject queued), which the 50 ms p99 is there to prevent.
        checks.add(check(snapshot, "C1 an unavailable fail-safe no scene forced", "SELECT operation || ':' ||"
                + " unavailable_cause FROM risk.risk_assessment WHERE fail_safe_reason = 'RISK_UNAVAILABLE' AND NOT"
                + " (party_id::text = ANY (?))", snapshot.createArrayOf("text", knowledge.get().forced().toArray())));
        for (String[] row : RiskStormWorld.rows(snapshot, "SELECT operation || ' ' || decision || ' ' ||"
                + " coalesce(fail_safe_reason, 'JUDGED') || ' ' || velocity_outcome, count(*) FROM risk.risk_assessment"
                + " GROUP BY 1 ORDER BY 1")) {
            figures.put("assessments " + row[0], Long.parseLong(row[1]));
        }
    }

    // ================================================================== C2

    /** Every (dimension, subject) a COUNTED assessment carries, as rows, with its cut, its amount and its id. */
    private static final String COUNTED_SUBJECTS = "SELECT a.id, d.dimension, d.subject_ref, a.velocity_cut_at AS cut,"
            + " a.amount_minor FROM risk.risk_assessment a CROSS JOIN LATERAL (VALUES ('PARTY', a.party_id::text),"
            + " ('SOURCE_ACCOUNT', a.source_account_id::text), ('DESTINATION', a.destination_id::text),"
            + " ('DEVICE', a.device_hash), ('MERCHANT', a.merchant_subject_id::text), ('SESSION', a.session_id::text))"
            + " AS d (dimension, subject_ref) WHERE a.velocity_outcome = 'COUNTED' AND d.subject_ref IS NOT NULL";

    /** Each counted subject in each window kind: the window's start by risk V005's own alignment. */
    private static final String EXPECTED_WINDOWS = "SELECT c.dimension, c.subject_ref, k.kind AS window_kind,"
            + " date_bin(risk.velocity_window_length(k.kind), c.cut, TIMESTAMPTZ '1970-01-05 00:00:00+00')"
            + " AS window_start, count(*) AS count, sum(c.amount_minor) AS sum_minor FROM (" + COUNTED_SUBJECTS
            + ") c CROSS JOIN (VALUES ('H1'), ('D1'), ('D7'), ('D30')) AS k (kind) GROUP BY 1, 2, 3, 4";

    private void windows(Connection snapshot, List<Check> checks, Map<String, Long> figures) throws SQLException {
        checks.add(check(snapshot, "C2 a window not its evidence", "SELECT coalesce(w.dimension, e.dimension) || ':'"
                + " || coalesce(w.window_kind, e.window_kind) || ' recorded ' || coalesce(w.count, 0) || '/'"
                + " || coalesce(w.sum_minor, 0) || ' expected ' || coalesce(e.count, 0) || '/' || coalesce(e.sum_minor, 0)"
                + " FROM risk.velocity_window w FULL JOIN (" + EXPECTED_WINDOWS + ") e ON e.dimension = w.dimension"
                + " AND e.subject_ref = w.subject_ref AND e.window_kind = w.window_kind AND e.window_start ="
                + " w.window_start WHERE w.count IS DISTINCT FROM e.count OR w.sum_minor IS DISTINCT FROM e.sum_minor"));
        // Every reading T-eval sealed (the canonical inputs' velocity map), exercised for real for every dimension and
        // window the seals name (the battery's item 73): R2a serialises a subject's read-evaluate-count, so the COUNTED
        // movements that read a window read pairwise different figures, each below the window's final count, and no
        // reader - counted or refused - read more than the window holds. Order-free on purpose: the database clock this
        // runs on steps backwards (the Docker VM's, measured), so a later cut can carry an earlier instant.
        String readings = "SELECT a.velocity_outcome, v ->> 'dimension' AS dimension, v ->> 'window' AS window_kind,"
                + " date_bin(risk.velocity_window_length(v ->> 'window'), a.velocity_cut_at, TIMESTAMPTZ '1970-01-05"
                + " 00:00:00+00') AS window_start, (v ->> 'count')::bigint AS read_count, CASE v ->> 'dimension'"
                + " WHEN 'PARTY' THEN a.party_id::text WHEN 'SOURCE_ACCOUNT' THEN a.source_account_id::text"
                + " WHEN 'DESTINATION' THEN a.destination_id::text WHEN 'DEVICE' THEN a.device_hash WHEN 'MERCHANT'"
                + " THEN a.merchant_subject_id::text WHEN 'SESSION' THEN a.session_id::text END AS subject_ref"
                + " FROM risk.risk_assessment a JOIN risk.risk_assessment_input i ON i.assessment_id = a.id"
                + " CROSS JOIN LATERAL jsonb_array_elements(i.inputs_canonical::jsonb -> 'velocity') v"
                + " WHERE a.velocity_cut_at IS NOT NULL AND a.fail_safe_reason IS NULL";
        checks.add(check(snapshot, "C2 two counted movements read one figure (a lost update)", "SELECT r.dimension"
                + " || ':' || r.window_kind || ' read ' || r.read_count || ' twice' FROM (" + readings + ") r WHERE"
                + " r.velocity_outcome = 'COUNTED' AND r.subject_ref IS NOT NULL GROUP BY r.dimension, r.subject_ref,"
                + " r.window_kind, r.window_start, r.read_count HAVING count(*) > 1"));
        checks.add(check(snapshot, "C2 a sealed reading beyond its window's count", "SELECT r.dimension || ':'"
                + " || r.window_kind || ' read ' || r.read_count || ' of ' || coalesce(w.count, 0) FROM (" + readings
                + ") r LEFT JOIN risk.velocity_window w ON w.dimension = r.dimension AND w.subject_ref = r.subject_ref"
                + " AND w.window_kind = r.window_kind AND w.window_start = r.window_start WHERE r.subject_ref IS NOT NULL"
                + " AND (r.read_count > coalesce(w.count, 0) OR (r.velocity_outcome = 'COUNTED' AND r.read_count >="
                + " coalesce(w.count, 0)))"));
        figures.put("velocity windows", RiskStormWorld.rows(snapshot, "SELECT count(*) FROM risk.velocity_window")
                .stream().mapToLong(row -> Long.parseLong(row[0])).sum());
        figures.put("sealed readings", Long.parseLong(RiskStormWorld.row(snapshot, "SELECT count(*) FROM"
                + " risk.risk_assessment_input i CROSS JOIN LATERAL jsonb_array_elements(i.inputs_canonical::jsonb"
                + " -> 'velocity') v")[0]));
    }

    // ================================================================== C3

    private void monitoring(Connection snapshot, List<Check> checks, Map<String, Long> figures) throws SQLException {
        checks.add(check(snapshot, "C3 a journal entry behind the watermark not monitored", "SELECT e.id::text FROM"
                + " ledger.journal_entry e, risk.monitoring_watermark w WHERE w.source = 'ledger.journal' AND e.id <="
                + " w.last_entry_id AND NOT EXISTS (SELECT 1 FROM risk.monitored_transaction m WHERE m.journal_entry_id"
                + " = e.id)"));
        checks.add(check(snapshot, "C3 a monitored transaction without its journal entry", "SELECT m.id::text FROM"
                + " risk.monitored_transaction m WHERE NOT EXISTS (SELECT 1 FROM ledger.journal_entry e WHERE e.id ="
                + " m.journal_entry_id)"));
        checks.add(check(snapshot, "C3 a journal entry monitored twice", "SELECT journal_entry_id::text FROM"
                + " risk.monitored_transaction GROUP BY journal_entry_id HAVING count(*) > 1"));
        figures.put("monitored", count(snapshot, "SELECT count(*) FROM risk.monitored_transaction"));
        figures.put("journal entries", count(snapshot, "SELECT count(*) FROM ledger.journal_entry"));
        figures.put("journal entries ahead of the watermark", count(snapshot, "SELECT count(*) FROM"
                + " ledger.journal_entry e, risk.monitoring_watermark w WHERE w.source = 'ledger.journal' AND e.id >"
                + " w.last_entry_id"));
    }

    // ================================================================== C4, C5

    private void alertsAndCases(Connection snapshot, List<Check> checks, Map<String, Long> figures)
            throws SQLException {
        checks.add(check(snapshot, "C4 an alert born twice", "SELECT subject_ref || ':' || rule_code FROM risk.risk_alert"
                + " GROUP BY subject_ref, rule_code, window_start, fact_signal_id HAVING count(*) > 1"));
        checks.add(check(snapshot, "C5 two open cases of one subject and kind", "SELECT subject_ref || ':' || kind FROM"
                + " risk.risk_case WHERE status <> 'DISPOSED' GROUP BY subject_ref, kind HAVING count(*) > 1"));
        // A default's alert is about the agreement and folds into its party's COLLECTIONS case (P13-TSK-025): its case's
        // subject is the agreement's party, through the modules' published views - every other alert's, its own.
        checks.add(check(snapshot, "C5 an alert attached to another subject's or kind's case", "SELECT a.id::text FROM"
                + " risk.risk_alert a JOIN risk.risk_case c ON c.id = a.attached_case_id WHERE a.status = 'ATTACHED'"
                + " AND (c.subject_ref <> CASE WHEN a.kind = 'OBLIGATION_DEFAULTED' THEN coalesce((SELECT"
                + " l.party_id::text FROM lending.party_principal_exposure l WHERE l.loan_id::text = a.subject_ref),"
                + " (SELECT o.party_id::text FROM bnpl.party_principal_exposure o WHERE o.obligation_id::text ="
                + " a.subject_ref)) ELSE a.subject_ref END OR c.kind <> CASE a.kind WHEN 'FRAUD' THEN 'FRAUD'"
                + " WHEN 'ACCOUNT_TAKEOVER' THEN 'ATO' WHEN 'MERCHANT_ABUSE' THEN 'MERCHANT_ABUSE' WHEN 'AML' THEN"
                + " 'AML' WHEN 'SCREENING_HIT' THEN 'AML' WHEN 'OBLIGATION_DEFAULTED' THEN 'COLLECTIONS' END)"));
        // At rest the case leg has folded every alert of a folding kind: none left OPEN without a standing suppression.
        checks.add(check(snapshot, "C5 an alert of a folding kind left OPEN at rest", "SELECT id::text FROM"
                + " risk.risk_alert WHERE status = 'OPEN' AND kind IN ('FRAUD', 'ACCOUNT_TAKEOVER', 'MERCHANT_ABUSE',"
                + " 'AML', 'SCREENING_HIT', 'OBLIGATION_DEFAULTED') AND suppression_proposed_by IS NULL"));
        // No lost update: every revision is one history row (OPENED at 0), so the history is the revision plus one.
        checks.add(check(snapshot, "C5 a case's history not one row per revision", "SELECT c.id::text FROM"
                + " risk.risk_case c WHERE (SELECT count(*) FROM risk.risk_case_event e WHERE e.case_id = c.id) <>"
                + " c.revision + 1"));
        for (String[] row : RiskStormWorld.rows(snapshot, "SELECT 'alerts ' || kind || ' ' || status, count(*) FROM"
                + " risk.risk_alert GROUP BY 1 UNION ALL SELECT 'cases ' || kind || ' ' || status, count(*) FROM"
                + " risk.risk_case GROUP BY 1 ORDER BY 1")) {
            figures.put(row[0], Long.parseLong(row[1]));
        }
    }

    // ================================================================== C6, C7

    private void actionsAndFilings(Connection snapshot, List<Check> checks, Map<String, Long> figures)
            throws SQLException {
        checks.add(check(snapshot, "C6 a DISPATCHED action past its permit", "SELECT id::text FROM risk.risk_action"
                + " WHERE status = 'DISPATCHED' AND next_attempt_at < now() - interval '20 seconds'"));
        checks.add(check(snapshot, "C6 an APPROVED action never dispatched", "SELECT id::text FROM risk.risk_action"
                + " WHERE status = 'APPROVED' AND next_attempt_at < now() - interval '20 seconds'"));
        checks.add(check(snapshot, "C6 a dispatched action's owner key not once", "SELECT a.id::text FROM"
                + " risk.risk_action a WHERE a.status = 'DONE' AND a.kind IN ('SUSPEND_IDENTITY', 'SUSPEND_MERCHANT',"
                + " 'REVOKE_SESSIONS') AND (SELECT count(*) FROM platform.idempotency_record r WHERE r.idempotency_key ="
                + " 'risk.action:' || a.id::text) <> 1"));
        checks.add(check(snapshot, "C6 a held withdrawal's action DONE without its one exit", "SELECT a.id::text FROM"
                + " risk.risk_action a WHERE a.kind IN ('RELEASE_HELD_WITHDRAWAL', 'DENY_HELD_WITHDRAWAL') AND a.status"
                + " = 'DONE' AND a.outcome = 'DONE' AND (SELECT count(*) FROM payments.withdrawal_event e WHERE"
                + " e.withdrawal_id = a.target_ref AND e.from_status = 'HELD') <> 1"));
        checks.add(check(snapshot, "C7 a FILED report past its inquiry permit", "SELECT id::text FROM"
                + " risk.regulatory_report WHERE status = 'FILED' AND next_inquiry_at < now() - interval '20 seconds'"));
        checks.add(check(snapshot, "C7 an acknowledged report without the authority's reference", "SELECT id::text FROM"
                + " risk.regulatory_report WHERE status = 'ACKNOWLEDGED' AND provider_reference IS NULL"));
        for (String[] row : RiskStormWorld.rows(snapshot, "SELECT 'actions ' || kind || ' ' || status || ' ' ||"
                + " coalesce(outcome, '-'), count(*) FROM risk.risk_action GROUP BY 1 UNION ALL SELECT 'reports ' ||"
                + " status, count(*) FROM risk.regulatory_report GROUP BY 1 UNION ALL SELECT 'overrides ' || status,"
                + " count(*) FROM risk.decision_override GROUP BY 1 ORDER BY 1")) {
            figures.put(row[0], Long.parseLong(row[1]));
        }
    }

    // ================================================================== C8

    private void holds(Connection snapshot, List<Check> checks, Map<String, Long> figures) throws SQLException {
        checks.add(check(snapshot, "C8 a HELD withdrawal without its ACTIVE hold", "SELECT w.id::text FROM"
                + " payments.withdrawal w LEFT JOIN ledger.hold h ON h.id = w.hold_reference WHERE w.status = 'HELD'"
                + " AND (h.id IS NULL OR h.status <> 'ACTIVE')"));
        checks.add(check(snapshot, "C8 a withdrawal's hold ACTIVE while it is not HELD or in flight", "SELECT"
                + " w.id::text FROM payments.withdrawal w JOIN ledger.hold h ON h.id = w.hold_reference WHERE h.status ="
                + " 'ACTIVE' AND w.status IN ('FAILED', 'COMPLETED', 'RETURNED')"));
        checks.add(check(snapshot, "C8 a refused withdrawal's hold not released", "SELECT w.id::text FROM"
                + " payments.withdrawal w JOIN ledger.hold h ON h.id = w.hold_reference WHERE w.status = 'FAILED' AND"
                + " h.status <> 'RELEASED'"));
        checks.add(check(snapshot, "C8 more than one exit from HELD", "SELECT withdrawal_id::text FROM"
                + " payments.withdrawal_event WHERE from_status = 'HELD' GROUP BY withdrawal_id HAVING count(*) > 1"));
        checks.add(check(snapshot, "C8 a HELD withdrawal sealed other than HOLD", "SELECT w.id::text FROM"
                + " payments.withdrawal w JOIN risk.risk_assessment a ON a.operation = 'WITHDRAWAL' AND a.command_ref ="
                + " w.id::text WHERE (w.status = 'HELD' OR EXISTS (SELECT 1 FROM payments.withdrawal_event e WHERE"
                + " e.withdrawal_id = w.id AND e.from_status = 'HELD')) AND a.decision <> 'HOLD'"));
        for (String[] row : RiskStormWorld.rows(snapshot, "SELECT 'withdrawals ' || status || coalesce(' ' ||"
                + " failure_reason, ''), count(*) FROM payments.withdrawal GROUP BY 1 ORDER BY 1")) {
            figures.put(row[0], Long.parseLong(row[1]));
        }
        figures.put("withdrawals exited HELD", count(snapshot, "SELECT count(DISTINCT withdrawal_id) FROM"
                + " payments.withdrawal_event WHERE from_status = 'HELD'"));
    }

    // ================================================================== C9

    private void ledger(Connection snapshot, List<Check> checks) throws SQLException {
        checks.add(check(snapshot, "C9 the trial balance per currency", "SELECT currency FROM ledger.journal_line GROUP BY"
                + " currency HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0"));
        checks.add(check(snapshot, "C9 an unbalanced journal entry", "SELECT entry_id::text FROM ledger.journal_line"
                + " GROUP BY entry_id, currency HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE"
                + " -amount_minor END) <> 0"));
    }

    // ================================================================== C10

    private void signals(Connection snapshot, List<Check> checks, Map<String, Long> figures) throws SQLException {
        checks.add(check(snapshot, "C10 a source event recorded twice", "SELECT source_event_id::text FROM"
                + " risk.risk_signal WHERE source_event_id IS NOT NULL GROUP BY source_event_id HAVING count(*) > 1"));
        Set<String> delivered = knowledge.get().delivered();
        if (!delivered.isEmpty()) {
            // The signal sources that record unconditionally (not a completion hint, not attributed from a judged payment).
            checks.add(check(snapshot, "C10 a delivered signal-source event not recorded", "SELECT e.event_id::text FROM"
                    + " platform.outbox_event e WHERE e.event_id = ANY (?) AND e.event_type IN ("
                    + "'identity.CredentialChanged', 'identity.RecoveryInitiated', 'identity.RecoveryCompleted',"
                    + " 'identity.IdentitySuspended', 'accounts.AccountOpened', 'crossborder.BeneficiaryRegistered',"
                    + " 'crossborder.BeneficiaryActivated', 'crossborder.BeneficiaryBlocked', 'merchant.MerchantOnboarded')"
                    + " AND NOT EXISTS (SELECT 1 FROM risk.risk_signal s WHERE s.source_event_id = e.event_id)",
                    snapshot.createArrayOf("uuid", delivered.toArray())));
        }
        for (String[] row : RiskStormWorld.rows(snapshot, "SELECT 'signals ' || kind, count(*) FROM risk.risk_signal"
                + " GROUP BY 1 ORDER BY 1")) {
            figures.put(row[0], Long.parseLong(row[1]));
        }
    }

    // ================================================================== C11

    private void audit(Connection snapshot, List<Check> checks) throws SQLException {
        Map<String, String> acts = new LinkedHashMap<>();
        acts.put("risk.PolicyActivated", "SELECT count(*) FROM risk.risk_policy_version WHERE status IN ('ACTIVE',"
                + " 'RETIRED')");
        acts.put("risk.CaseOpened", "SELECT count(*) FROM risk.risk_case_event WHERE kind = 'OPENED'");
        acts.put("risk.CaseTaken", "SELECT count(*) FROM risk.risk_case_action WHERE kind = 'TAKEN'");
        acts.put("risk.DispositionApproved", "SELECT count(*) FROM risk.risk_case_action WHERE kind ="
                + " 'DISPOSITION_APPROVED'");
        acts.put("risk.OverrideProposed", "SELECT count(*) FROM risk.decision_override");
        acts.put("risk.OverrideApproved", "SELECT count(*) FROM risk.decision_override WHERE status = 'APPROVED'");
        acts.put("risk.ActionApproved", "SELECT count(*) FROM risk.risk_action WHERE source = 'CASE' AND status NOT IN"
                + " ('PROPOSED', 'REJECTED')");
        acts.put("risk.ReportDrafted", "SELECT count(*) FROM risk.regulatory_report");
        for (Map.Entry<String, String> act : acts.entrySet()) {
            long rows = count(snapshot, act.getValue());
            long audited = count(snapshot, "SELECT count(*) FROM platform.audit_record WHERE operation = '" + act.getKey()
                    + "' AND outcome = 'SUCCEEDED'");
            checks.add(new Check("C11 " + act.getKey() + " rows against their audit records", Math.abs(rows - audited),
                    rows + " rows, " + audited + " audited"));
        }
    }

    // ================================================================== C12

    private void production(Connection snapshot, Instant asOf, List<Check> checks) {
        RiskProofReads reads = beans.getBean(RiskProofReads.class);
        RiskDay today = RiskDay.containing(asOf);
        List<RiskDay> days = List.of(today, RiskDay.containing(asOf.minusSeconds(86_400)));
        for (RiskDay day : days) {
            AssessmentCensus.Result assessments = new AssessmentCensus(reads, beans.getBean(JudgedCommands.class))
                    .run(snapshot, day);
            checks.add(verdict("C12 AssessmentCensus " + day, assessments.verdict(), assessments.toString()));
            MonitoringCensus.Result monitored = new MonitoringCensus(beans.getBean(MonitoredLedger.class),
                    beans.getBean(MonitoringWatermarkStore.class), reads).run(snapshot, day);
            checks.add(verdict("C12 MonitoringCensus " + day, monitored.verdict(), monitored.toString()));
            AlertBornOnceCensus.Result alerts = new AlertBornOnceCensus(reads).run(snapshot, day);
            checks.add(verdict("C12 AlertBornOnceCensus " + day, alerts.verdict(), alerts.toString()));
        }
        CaseOneOpenCensus.Result cases = new CaseOneOpenCensus(reads).run(snapshot);
        checks.add(verdict("C12 CaseOneOpenCensus", cases.verdict(), cases.toString()));
        FilingCensus.Result filings = new FilingCensus(reads).run(snapshot);
        checks.add(verdict("C12 FilingCensus", filings.verdict(), filings.toString()));
        RescreeningCensus.Result rescreening = new RescreeningCensus(reads).run(snapshot);
        checks.add(verdict("C12 RescreeningCensus", rescreening.verdict(), rescreening.toString()));
        VelocityProof.Whole velocity = beans.getBean(VelocityProof.class).prove(snapshot);
        checks.add(new Check("C12 VelocityProof (record)", velocity.record().divergences().size(),
                velocity.record() + " " + firstOf(velocity.record().divergences())));
        checks.add(new Check("C12 VelocityProof (journal)", velocity.journal().map(j -> (long) j.divergences().size())
                .orElse(0L), velocity.journal().map(j -> j + " " + firstOf(j.divergences())).orElse("-")));
    }

    private static String firstOf(List<?> divergences) {
        return divergences.subList(0, Math.min(5, divergences.size())).toString();
    }

    private static Check verdict(String name, ProofVerdict verdict, String detail) {
        return new Check(name, verdict == ProofVerdict.DIVERGED ? 1 : 0, verdict + " " + abbreviated(detail));
    }

    // ================================================================== plumbing

    private static Check check(Connection snapshot, String name, String violationsSql, Object... parameters)
            throws SQLException {
        List<String[]> found = RiskStormWorld.rows(snapshot, violationsSql, parameters);
        StringBuilder named = new StringBuilder();
        for (int i = 0; i < Math.min(5, found.size()); i++) {
            named.append(found.get(i)[0]).append("; ");
        }
        return new Check(name, found.size(), named.toString());
    }

    static long count(Connection snapshot, String sql) throws SQLException {
        return Long.parseLong(RiskStormWorld.row(snapshot, sql)[0]);
    }

    private static String abbreviated(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "...";
    }
}
