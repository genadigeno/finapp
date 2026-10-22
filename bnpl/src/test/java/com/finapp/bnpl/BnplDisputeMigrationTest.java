package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V015}'s text held to the code (`P12-TSK-023`; {@code BnplPayoffMigrationTest}'s idiom - hermetic, so it runs
 * on every build while the live ranks are {@code BnplDisputeDatabaseTest}'s): one open dispute per purchase, the machine's
 * edges and their complementary deadline conditionals, the four-eyes {@code CHECK} and the edge's four-eyes rank, the claim's
 * bound under B5, the refund's door {@code DISPUTE} and its refusal beside an open dispute, the hold's derivations and
 * {@code days_past_due} replaced around them, the fee's and the collection's hold ranks, the money-free machine row and its
 * grants, and every stamp after the locks.
 */
@DisplayName("bnpl V015 holds the dispute, its hold and its one bound as the code declares them (P12-TSK-023)")
class BnplDisputeMigrationTest {

    private static final String V015 = "db/migration/bnpl/V015__bnpl_disputes_and_the_servicing_hold.sql";

    @Test
    @DisplayName("one open dispute per purchase, one per customer's key, one refund and one proposal per dispute")
    void theUniques() {
        assertThat(statements())
                .contains("CREATE UNIQUE INDEX bnpl_dispute_one_open_per_purchase ON bnpl.bnpl_dispute (purchase_id)\n"
                        + "    WHERE status IN ('OPEN', 'MERCHANT_RESPONDED');")
                .contains("CONSTRAINT bnpl_dispute_request_unique UNIQUE (party_id, request_key)")
                .contains("CONSTRAINT bnpl_dispute_refund_unique UNIQUE (refund_id)")
                .contains("ALTER TABLE bnpl.bnpl_refund ADD CONSTRAINT bnpl_refund_dispute_unique UNIQUE (dispute_id);")
                .contains("CREATE UNIQUE INDEX bnpl_dispute_proposal_one_live ON bnpl.bnpl_dispute_proposal (dispute_id)\n"
                        + "    WHERE status IN ('PROPOSED', 'APPROVED');");
    }

    @Test
    @DisplayName("the machine: born OPEN under B5 then B4, the deadline stamped from the pinned window; the merchant only"
            + " before the deadline and the system only at or after it - complementary on one clock; terminal is terminal")
    void theMachine() {
        String edges = body("bnpl.bnpl_dispute_permits_only_machine_edges()");
        assertThat(edges)
                .contains("SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id FOR UPDATE;")
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR UPDATE;")
                .contains("NEW.response_deadline_at := NEW.opened_at + make_interval(days => terms.dispute_response_days);")
                .contains("NEW.opened_at := clock_timestamp();")
                .contains("OR OLD.response_deadline_at <= now_at OR NEW.resolved_by IS NOT NULL")
                .contains("IF OLD.response_deadline_at <= now_at OR NEW.merchant_statement IS NULL THEN")
                .contains("IF OLD.response_deadline_at > now_at OR NEW.merchant_statement IS NOT NULL THEN")
                .contains("RAISE EXCEPTION 'a % BNPL dispute is terminal (P12-TSK-023, INV-LIFE-04)', OLD.status;")
                .contains("NEW.resolved_at := GREATEST(now_at, OLD.opened_at);");
        assertThat(statements())
                .contains("status IN ('OPEN', 'MERCHANT_RESPONDED', 'RESOLVED_REFUND', 'RESOLVED_UPHELD', 'WITHDRAWN')")
                .contains("AND (status = 'RESOLVED_REFUND') = (refund_id IS NOT NULL)");
    }

    @Test
    @DisplayName("four-eyes at two ranks: the proposal's CHECK refuses its proposer's approval, and the edge above the pinned"
            + " threshold requires a proposal another operator approved")
    void theFourEyes() {
        assertThat(statements())
                .contains("CONSTRAINT bnpl_dispute_proposal_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)");
        assertThat(body("bnpl.bnpl_dispute_permits_only_machine_edges()"))
                .contains("IF disputed > terms.dispute_four_eyes_threshold_minor THEN")
                .contains("OR proposal.proposed_by = proposal.decided_by THEN");
        assertThat(body("bnpl.bnpl_dispute_proposal_permits_only_machine_edges()"))
                .contains("OR dispute.status <> 'MERCHANT_RESPONDED' OR disputed <= threshold THEN")
                .contains("NEW.decided_at := GREATEST(clock_timestamp(), OLD.proposed_at);");
    }

    @Test
    @DisplayName("the one bound (INV-BNPL-08): the claim judged under B5 within G - refunded; no refund beside an open"
            + " dispute but its own of exactly its claim; a refund outcome commits beside that refund")
    void theOneBound() {
        assertThat(body("bnpl.bnpl_dispute_claim_is_born_once()"))
                .contains("PERFORM 1 FROM bnpl.purchase p WHERE p.id = dispute.purchase_id FOR UPDATE;")
                .contains("IF NEW.refunded_before_minor <> refunded OR refunded + NEW.amount_minor > financing.gross_minor THEN");
        assertThat(body("bnpl.bnpl_refund_is_not_beside_an_open_dispute()"))
                .contains("AND d.id IS DISTINCT FROM NEW.dispute_id) THEN")
                .contains("AND c.amount_minor = NEW.amount_minor) THEN");
        assertThat(body("bnpl.bnpl_dispute_is_whole()"))
                .contains("AND r.door = 'DISPUTE' AND r.amount_minor = claim.amount_minor) THEN");
        assertThat(statements())
                .contains("CHECK (door IN ('MERCHANT', 'OPERATOR', 'DISPUTE'))")
                .contains("OR (door = 'DISPUTE' AND dispute_id IS NOT NULL AND requested_by = 'DISPUTE:' || dispute_id::text\n"
                        + "        AND refund_key = dispute_id::text)");
    }

    @Test
    @DisplayName("the hold: held while opened and not resolved by the date; the clock from the latest lift; days_past_due"
            + " replaced around both; the fee and the collection refused on a held instalment for every writer")
    void theHold() {
        assertThat(body("bnpl.dispute_holds(obligation uuid, due date, on_date date)"))
                .contains("AND d.opened_business_date <= on_date")
                .contains("AND (d.resolved_business_date IS NULL OR d.resolved_business_date > on_date))");
        assertThat(body("bnpl.dispute_clock_start(obligation uuid, due date, on_date date)"))
                .contains("SELECT GREATEST(due, coalesce((SELECT max(d.resolved_business_date)")
                .contains("AND d.resolved_business_date <= on_date), due))");
        assertThat(body("bnpl.days_past_due(obligation uuid, on_date date)"))
                .contains("SELECT coalesce(on_date - min(bnpl.dispute_clock_start(obligation, b.value_date, on_date)), 0)")
                .contains("AND NOT bnpl.dispute_holds(obligation, b.value_date, on_date)")
                .contains("FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)) > 0");
        assertThat(body("bnpl.bnpl_fee_assessment_respects_the_dispute_hold()"))
                .contains("IF bnpl.dispute_holds(NEW.obligation_id, billing.value_date, NEW.business_date)")
                .contains("< late_day THEN");
        assertThat(body("bnpl.bnpl_collection_attempt_respects_the_dispute_hold()"))
                .contains("IF bnpl.dispute_holds(NEW.obligation_id, due, NEW.attempt_date) THEN");
    }

    @Test
    @DisplayName("the machine row carries no money (its claim and hold do, INSERT-only); its UPDATE grant is its status,"
            + " permit and decision columns alone; the sweep's permit moves only on an OPEN dispute")
    void theGrantsAndTheMoneyFreeRow() {
        String sql = statements();
        String table = sql.substring(sql.indexOf("CREATE TABLE bnpl.bnpl_dispute ("),
                sql.indexOf(");", sql.indexOf("CREATE TABLE bnpl.bnpl_dispute (")));
        assertThat(table).doesNotContain("_minor");
        assertThat(sql)
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_dispute_claim TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_dispute_hold TO finapp_app;")
                .contains("GRANT UPDATE (status, next_sweep_at, merchant_statement, merchant_responded_at, resolved_by,"
                        + " resolution_reason, resolved_at,\n              resolved_business_date, refund_id, proposal_id)"
                        + " ON bnpl.bnpl_dispute TO finapp_app;")
                .doesNotContain("GRANT UPDATE ON")
                .doesNotContain("GRANT DELETE");
        assertThat(body("bnpl.bnpl_dispute_permits_only_machine_edges()"))
                .contains("IF OLD.status <> 'OPEN' OR NEW.next_sweep_at IS NOT DISTINCT FROM OLD.next_sweep_at");
    }

    private static String body(String signature) {
        String sql = statements();
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION " + signature);
        assertThat(start).as(signature).isNotNegative();
        int end = sql.indexOf("$$;", sql.indexOf("$$", start) + 2);
        return sql.substring(start, end);
    }

    private static String statements() {
        return migration().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        return read(V015);
    }

    private static String read(String resource) {
        try (InputStream in = BnplDisputeMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    // ------------------------------------------------------------------ bnpl V018 (the Phase 12 -> 13 gate's R1)

    private static final String V018 = "db/migration/bnpl/V018__the_dispute_clock_four_eyes_and_queue_repairs.sql";

    @Test
    @DisplayName("bnpl V018 (the Phase 12 to 13 gate's R1): the clock start excludes a withdrawn dispute's lift (A5-F2); the"
            + " four-eyes amount is the claim plus every other dispute's refund on the purchase, read by both triggers"
            + " (A5-F7); the contested queue's index (A7-2); no money written, no column added")
    void theGateRepairs() {
        String sql = read(V018).lines().filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
        assertThat(sql).contains("CREATE OR REPLACE FUNCTION bnpl.dispute_clock_start(obligation uuid, due date, on_date date)")
                .contains("AND d.status <> 'WITHDRAWN'\n                                      AND d.resolved_business_date <= on_date), due))")
                .contains("SELECT c.amount_minor + coalesce((SELECT sum(r.amount_minor)")
                .contains("WHERE r.purchase_id = d.purchase_id AND r.door = 'DISPUTE'")
                .contains("AND r.dispute_id IS DISTINCT FROM d.id), 0)")
                .contains("disputed := bnpl.dispute_four_eyes_amount(OLD.id);")
                .contains("disputed := bnpl.dispute_four_eyes_amount(NEW.dispute_id);")
                .contains("IF disputed > terms.dispute_four_eyes_threshold_minor THEN")
                .contains("OR dispute.status <> 'MERCHANT_RESPONDED' OR disputed <= threshold THEN")
                .contains("CREATE INDEX bnpl_dispute_contested ON bnpl.bnpl_dispute (merchant_responded_at, id) WHERE status ="
                        + " 'MERCHANT_RESPONDED';")
                .doesNotContain("ALTER TABLE").doesNotContain("INSERT INTO").doesNotContain("UPDATE bnpl.")
                .doesNotContain("GRANT");
        // The replaced edge function keeps V015's body but for the four-eyes amount: every other rank verbatim.
        String v015Edges = body("bnpl.bnpl_dispute_permits_only_machine_edges()");
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_permits_only_machine_edges()");
        String v018Edges = sql.substring(start, sql.indexOf("$$;", sql.indexOf("$$", start) + 2));
        assertThat(v018Edges.replace("    -- V018 (A5-F7): the cumulative refunded-by-dispute amount of the purchase, this"
                        + " dispute's claim included.\n", "")
                .replace("    disputed := bnpl.dispute_four_eyes_amount(OLD.id);",
                        "    SELECT c.amount_minor INTO disputed FROM bnpl.bnpl_dispute_claim c WHERE c.dispute_id = OLD.id;")
                .replace("above its four-eyes threshold - its purchase''s refunds by dispute and its own claim - is resolved"
                        + " by a proposal another operator approved (P12-TSK-023, bnpl V018, INV-AUD-04)",
                        "above its four-eyes threshold is resolved by a proposal another operator approved (P12-TSK-023,"
                                + " INV-AUD-04)")).isEqualTo(v015Edges);
    }
}
