package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two reaches the owner of a schema, or a role with a path of its own, must not have over financial history
 * ({@code P15-TSK-004}, the two C15 debt rows; {@code THREAT_MODEL.md} T13, T33), held over <em>every</em> module's migrations,
 * read in chain order, by what each statement says rather than by a list of files.
 *
 * <ul>
 *   <li><strong>A table that refuses {@code DELETE} by trigger also refuses {@code TRUNCATE}</strong>: a row trigger does not
 *       fire on {@code TRUNCATE}, so without a statement-level trigger of its own such a table can be emptied by its owner
 *       in one statement.
 *   <li><strong>No function reads a clock function unqualified with a mutable {@code search_path}</strong>: PostgreSQL searches
 *       {@code pg_catalog} first only when the path does not name it, so a session whose path puts another schema first
 *       resolves {@code statement_timestamp()} to that schema's. Every function counts, not only trigger functions: a trigger
 *       that calls a helper reading the clock is reading the clock. {@code CURRENT_TIMESTAMP} and its like are grammar and are
 *       never resolved through a path.
 * </ul>
 *
 * <p>What it finds is <strong>fixed here or named with an owner</strong>, and the lists below are equalities, not
 * allowances: a table fixed later must leave its list, or this goes red. Everything named is held by {@code THREAT_MODEL.md}
 * section 6 item 11, each list a {@code GAP} row of T33 or T13. Text, not behaviour: that the triggers fire and that the clock
 * is read is {@code infra/scripts/migrator-verify.sh} on a real database, and the database cases owed to {@code P15-TST-002}.
 */
@Tag("architecture")
@DisplayName("the migrations close the owner's reach over financial history, or name who will (P15-TSK-004)")
class OwnerReachOverHistoryIsClosedTest {

    /** Every table that refuses DELETE by trigger and not TRUNCATE, by schema. Owner: {@code THREAT_MODEL.md} section 6 item 11. */
    private static final Map<String, String> TRUNCATE_NOT_YET_REFUSED = Map.of(
            "checkout", "checkout_order",
            "crossborder", "beneficiary cancellation_request corridor corridor_availability corridor_enable_request"
                    + " corridor_policy_event corridor_policy_version payment",
            "fx", "availability_enable_request cover cover_attempt cover_execution fx_provider_evidence pair_availability"
                    + " pricing_pair pricing_policy_event pricing_policy_version provider_availability quote quote_event"
                    + " quote_request quote_sourcing_step rate_fetch_permit rate_snapshot trade trade_reversal"
                    + " trade_reversal_event",
            "kyc", "counterparty_screening",
            "ledger", "adjustment_proposal_line counterparty journal_entry journal_line",
            "merchant", "fee_schedule fee_schedule_version merchant_bnpl_fee_schedule_event payment_fee_pin payout_evidence"
                    + " payout_return",
            "payments", "clearing_record outbound_credit outbound_credit_return provider_evidence routing_decision"
                    + " routing_decision_step routing_policy_version routing_rule routing_rule_rail scheme_execution_claim"
                    + " unmatched_confirmation",
            "risk", "monitored_transaction");

    /** Every function that reads a clock through the path, by schema. Owner: {@code THREAT_MODEL.md} section 6 item 11. */
    private static final Map<String, String> CLOCK_NOT_YET_QUALIFIED = Map.ofEntries(
            Map.entry("accounting", "accounting_break_permits_only_machine_edges accounting_period_permits_only_machine_edges"
                    + " break_event_is_append_only close_check_permits_only_machine_edges close_run_permits_only_machine_edges"
                    + " gl_mapping_version_event_is_append_only gl_mapping_version_permits_only_machine_edges"
                    + " period_close_permits_only_machine_edges period_event_is_append_only"
                    + " period_reopen_permits_only_machine_edges period_unlock_end_is_the_pending_proposal"
                    + " prior_period_adjustment_permits_only_machine_edges regulatory_obligation_permits_only_machine_edges"
                    + " regulatory_provider_answer_is_append_only regulatory_submission_event_is_append_only"
                    + " regulatory_submission_permits_only_machine_edges report_run_permits_only_machine_edges"
                    + " valuation_rate_set_permits_only_machine_edges"),
            Map.entry("bnpl", "acceptance_history_is_append_only bnpl_acceptance_is_born_once bnpl_agreement_is_born_once"
                    + " bnpl_application_event_is_append_only bnpl_application_permits_only_machine_edges bnpl_billing_is_born_once"
                    + " bnpl_collection_attempt_is_born_once bnpl_condition_event_is_born_once"
                    + " bnpl_credit_balance_application_is_born_once bnpl_dispute_event_is_append_only"
                    + " bnpl_dispute_permits_only_machine_edges bnpl_dispute_proposal_permits_only_machine_edges"
                    + " bnpl_fee_assessment_is_born_once bnpl_interest_accrual_is_born_once bnpl_obligation_closure_is_born_once"
                    + " bnpl_obligation_permits_only_machine_edges bnpl_offer_event_is_append_only"
                    + " bnpl_offer_permits_only_machine_edges bnpl_payoff_execution_is_born_once bnpl_payoff_quote_is_born_once"
                    + " bnpl_refund_is_born_once bnpl_repayment_is_born_once bnpl_repayment_reversal_permits_only_machine_edges"
                    + " bnpl_schedule_is_born_once bnpl_terms_version_event_is_append_only"
                    + " bnpl_terms_version_permits_only_machine_edges bnpl_waiver_decision_is_born_once bnpl_waiver_is_born_once"
                    + " bnpl_write_off_decision_is_born_once bnpl_write_off_is_born_once business_date_latest"
                    + " merchant_financing_is_born_once purchase_permits_only_machine_edges"),
            Map.entry("checkout", "checkout_session_permits_only_machine_edges"),
            Map.entry("credit", "credit_assessment_is_born_once credit_decision_is_born_once credit_evidence_is_born_once"
                    + " credit_policy_event_is_append_only credit_policy_permits_only_machine_edges credit_profile_is_born_once"
                    + " credit_record_is_born_once data_request_permits_only_machine_edges decision_request_event_is_append_only"
                    + " decision_request_permits_only_machine_edges decision_snapshot_is_born_once policy_evaluation_is_born_once"
                    + " scorecard_model_event_is_append_only scorecard_model_permits_only_machine_edges"
                    + " underwriting_case_event_is_append_only underwriting_case_permits_only_machine_edges"),
            Map.entry("crossborder", "cancellation_request_is_born_once"),
            Map.entry("fx", "cover_execution_is_born_coherent cover_machine_is_legal quote_edge_is_legal quote_is_born_issued"
                    + " quote_request_is_stamped_and_append_only rate_snapshot_is_newer_than_latest trade_is_born_booked"
                    + " trade_reversal_edge_is_legal"),
            Map.entry("ledger", "posting_cutoff_moves_one_period"),
            Map.entry("merchant", "bnpl_fee_pin_names_a_returning_version_in_force"
                    + " merchant_bnpl_enablement_permits_only_machine_edges merchant_payout_send_permit_is_the_databases"),
            Map.entry("payments", "dispute_response_send_permit_is_the_databases outbound_credit_machine_is_legal"
                    + " outbound_credit_return_is_born_exact payment_attempt_send_permit_is_the_databases"
                    + " payment_capture_hold_resolves_once refund_send_permit_is_the_databases"
                    + " withdrawal_send_permit_is_the_databases"),
            Map.entry("risk", "customer_risk_profile_moves_with_its_versions customer_risk_profile_version_is_born_in_sequence"
                    + " decision_override_permits_only_machine_edges monitored_transaction_is_stamped_by_the_database"
                    + " monitoring_watermark_derives_its_sequence regulatory_report_event_is_append_only"
                    + " regulatory_report_permits_only_machine_edges rescreening_permit_permits_only_machine_edges"
                    + " risk_action_permits_only_machine_edges risk_alert_permits_only_machine_edges risk_assessment_is_born_stamped"
                    + " risk_case_action_is_append_only risk_case_event_is_append_only risk_case_permits_only_machine_edges"
                    + " risk_policy_version_event_is_append_only risk_policy_version_permits_only_machine_edges"
                    + " risk_signal_is_born_once screening_list_version_permits_only_machine_edges velocity_window_counts_forward"),
            Map.entry("settlement", "source_moves_forward_only"));

    /** The clock functions resolved through a path. {@code CURRENT_TIMESTAMP} and its like are keywords, so are absent by design. */
    private static final Set<String> CLOCK_FUNCTIONS =
            Set.of("now", "statement_timestamp", "clock_timestamp", "transaction_timestamp", "timeofday");

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern CREATE_TRIGGER = Pattern.compile("(?is)^CREATE\\s+(?:CONSTRAINT\\s+)?TRIGGER\\s+(\\w+)\\s+"
            + "(BEFORE|AFTER|INSTEAD\\s+OF)\\s+(.*?)\\s+ON\\s+([a-z_]+\\.\\w+)\\b(.*)$");
    private static final Pattern DROP_TRIGGER =
            Pattern.compile("(?is)^DROP\\s+TRIGGER\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+([a-z_]+\\.\\w+)");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^CREATE\\s+(?:UNLOGGED\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern CREATE_FUNCTION =
            Pattern.compile("(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+([a-z_]+\\.\\w+)\\s*\\(");
    private static final Pattern DROP_FUNCTION = Pattern.compile("(?is)^DROP\\s+FUNCTION\\s+(?:IF\\s+EXISTS\\s+)?([a-z_]+\\.\\w+)");
    private static final Pattern SET_SEARCH_PATH =
            Pattern.compile("(?is)\\bSET\\s+search_path\\s*(?:=|TO)\\s*([^;]*?)(?=\\s+(?:AS|LANGUAGE|SECURITY|STABLE|VOLATILE|IMMUTABLE)\\b|\\s*$)");

    /** The chain, as it stands after its last migration: every trigger on every table, and the latest of every function. */
    private static final Map<String, Trigger> TRIGGERS = new TreeMap<>();

    private static final Map<String, String> FUNCTIONS = new TreeMap<>();
    private static final Set<String> TABLES = new TreeSet<>();
    private static int modulesRead;

    record Trigger(String table, String timing, String events) {}

    @BeforeAll
    static void readEveryMigrationInChainOrder() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> modules;
        try (Stream<Path> children = Files.list(root)) {
            modules = children.filter(module -> Files.isDirectory(migrations(module))).sorted().toList();
        }
        modulesRead = modules.size();
        for (Path module : modules) {
            List<Path> scripts;
            try (Stream<Path> files = Files.list(migrations(module))) {
                scripts = files.filter(file -> VERSIONED.matcher(file.getFileName().toString()).matches())
                        .sorted(Comparator.comparingInt(OwnerReachOverHistoryIsClosedTest::version))
                        .toList();
            }
            for (Path script : scripts) {
                readStatements(EvidenceCitations.read(script));
            }
        }
    }

    private static Path migrations(Path module) {
        return module.resolve("src/main/resources/db/migration").resolve(module.getFileName());
    }

    private static int version(Path script) {
        Matcher matcher = VERSIONED.matcher(script.getFileName().toString());
        if (!matcher.matches()) {
            throw new UncheckedIOException(new IOException("Not a versioned migration: " + script));
        }
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    @DisplayName("the walk read the whole chain: every module with migrations, the tables and triggers the recount found")
    void theGuardReadTheChain() {
        assertThat(modulesRead).as("the modules with migrations").isGreaterThanOrEqualTo(21);
        assertThat(TABLES).as("reconciliation's 33 tables and settlement's 13 at the least").hasSizeGreaterThanOrEqualTo(331);
        assertThat(TABLES.stream().filter(table -> table.startsWith("reconciliation."))).hasSize(33);
        assertThat(TABLES.stream().filter(table -> table.startsWith("settlement."))).hasSize(13);
        assertThat(refusing("DELETE")).as("the row triggers refusing DELETE").hasSizeGreaterThanOrEqualTo(259);
        assertThat(FUNCTIONS).as("every function's latest definition").hasSizeGreaterThanOrEqualTo(400);
        assertThat(FUNCTIONS.keySet().stream().filter(function -> function.startsWith("lending.") && reachesTheClock(function)))
                .as("lending's functions that read a clock were found, so a green rule below is not a parse that saw none")
                .hasSizeGreaterThanOrEqualTo(39);
    }

    @Test
    @DisplayName("every table that refuses DELETE by trigger refuses TRUNCATE too, or is named with its owner")
    void everyTableThatRefusesDeleteRefusesTruncate() {
        Set<String> refusesTruncate = refusing("TRUNCATE");
        Map<String, Set<String>> found = new TreeMap<>();
        for (String table : refusing("DELETE")) {
            if (!refusesTruncate.contains(table)) {
                found.computeIfAbsent(schemaOf(table), schema -> new TreeSet<>()).add(nameOf(table));
            }
        }
        assertThat(found)
                .as("a statement-level BEFORE TRUNCATE trigger in a forward migration, or a name in this list with an owner; a"
                        + " table already fixed must leave the list")
                .isEqualTo(expected(TRUNCATE_NOT_YET_REFUSED));
    }

    @Test
    @DisplayName("no function reads a clock function unqualified with a mutable search_path, or it is named with its owner")
    void noFunctionReadsTheClockThroughTheSearchPath() {
        Map<String, Set<String>> found = new TreeMap<>();
        FUNCTIONS.forEach((function, statement) -> {
            if (!hasAPinnedPath(statement) && !clockCalls(statement).stream().allMatch(call -> "pg_catalog".equals(call))) {
                found.computeIfAbsent(schemaOf(function), schema -> new TreeSet<>()).add(nameOf(function));
            }
        });
        assertThat(found)
                .as("every clock call pg_catalog-qualified or the function's search_path fixed, or a name in this list with"
                        + " an owner; a function already fixed must leave the list")
                .isEqualTo(expected(CLOCK_NOT_YET_QUALIFIED));
    }

    @Test
    @DisplayName("the rule reads code: a clock in a comment or a string is not a call, and a pinned path or another schema is told apart")
    void theRuleReadsCodeNotText() {
        assertThat(clockCalls(function("$$ BEGIN -- statement_timestamp()\n /* now() */ RETURN NEW; END; $$"))).isEmpty();
        assertThat(clockCalls(function("$$ BEGIN RAISE EXCEPTION 'read clock_timestamp() late'; RETURN NEW; END; $$"))).isEmpty();
        assertThat(clockCalls(function("$$ BEGIN NEW.at := statement_timestamp(); RETURN NEW; END; $$"))).containsExactly("");
        assertThat(clockCalls(function("$$ BEGIN NEW.at := NOW ( ); RETURN NEW; END; $$"))).containsExactly("");
        assertThat(clockCalls(function("$$ BEGIN NEW.at := pg_catalog.now(); RETURN NEW; END; $$"))).containsExactly("pg_catalog");
        assertThat(clockCalls(function("$$ BEGIN NEW.at := evil.now(); RETURN NEW; END; $$"))).containsExactly("evil");
        assertThat(clockCalls(function("$fn$ SELECT LEAST(now(), pg_catalog.now()) $fn$"))).containsExactly("", "pg_catalog");
        assertThat(clockCalls(function("$$ BEGIN NEW.nowhere := now_or_never(); RETURN NEW; END; $$"))).isEmpty();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int LANGUAGE sql SET search_path = pg_catalog, pg_temp AS $$ SELECT 1 $$"))
                .isTrue();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int SET search_path = a AS $$ SELECT 1 $$"))
                .as("pg_catalog not named is searched first")
                .isTrue();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int SET search_path = evil, pg_catalog AS $$ SELECT 1 $$"))
                .as("a schema named before pg_catalog is searched before it")
                .isFalse();
        assertThat(hasAPinnedPath("CREATE FUNCTION a.b() RETURNS int AS $$ SET search_path = pg_catalog; SELECT 1 $$"))
                .as("inside the body it is no pin")
                .isFalse();
    }

    private static String function(String body) {
        return "CREATE FUNCTION a.b() RETURNS trigger LANGUAGE plpgsql AS " + body;
    }

    private static boolean reachesTheClock(String function) {
        return !clockCalls(FUNCTIONS.get(function)).isEmpty();
    }

    private static Set<String> refusing(String operation) {
        Set<String> tables = new TreeSet<>();
        for (Trigger trigger : TRIGGERS.values()) {
            if (trigger.timing().equals("BEFORE") && List.of(trigger.events().split("\\s+OR\\s+")).contains(operation)) {
                tables.add(trigger.table());
            }
        }
        return tables;
    }

    private static Map<String, Set<String>> expected(Map<String, String> named) {
        Map<String, Set<String>> expected = new TreeMap<>();
        named.forEach((schema, names) -> expected.put(schema, new TreeSet<>(List.of(names.split("\\s+")))));
        return expected;
    }

    private static String schemaOf(String qualified) {
        return qualified.substring(0, qualified.indexOf('.'));
    }

    private static String nameOf(String qualified) {
        return qualified.substring(qualified.indexOf('.') + 1);
    }

    private static void readStatements(String sql) {
        for (String statement : statementsOf(sql)) {
            Matcher matcher = DROP_TRIGGER.matcher(statement);
            if (matcher.find()) {
                TRIGGERS.remove(matcher.group(2).toLowerCase(Locale.ROOT) + "#" + matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = CREATE_TRIGGER.matcher(statement);
            if (matcher.find()) {
                String table = matcher.group(4).toLowerCase(Locale.ROOT);
                // Words only, UPDATE's column list dropped, so "UPDATE OF a, b OR DELETE" reads as its two events.
                String events = matcher.group(3).toUpperCase(Locale.ROOT).replaceAll("UPDATE\\s+OF\\s+[^\\s,]+(\\s*,\\s*[^\\s,]+)*", "UPDATE");
                TRIGGERS.put(table + "#" + matcher.group(1).toLowerCase(Locale.ROOT),
                        new Trigger(table, matcher.group(2).toUpperCase(Locale.ROOT), events.trim()));
                continue;
            }
            matcher = DROP_TABLE.matcher(statement);
            if (matcher.find()) {
                TABLES.remove(matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = CREATE_TABLE.matcher(statement);
            if (matcher.find()) {
                TABLES.add(matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = DROP_FUNCTION.matcher(statement);
            if (matcher.find()) {
                FUNCTIONS.remove(matcher.group(1).toLowerCase(Locale.ROOT));
                continue;
            }
            matcher = CREATE_FUNCTION.matcher(statement);
            if (matcher.find()) {
                FUNCTIONS.put(matcher.group(1).toLowerCase(Locale.ROOT), statement);
            }
        }
    }

    /** {@code [start, end)} of a dollar-quoted string beginning at {@code from}, or null when none begins there. */
    private static int[] dollarQuote(String text, int from) {
        int tagEnd = from + 1;
        while (tagEnd < text.length() && (Character.isLetterOrDigit(text.charAt(tagEnd)) || text.charAt(tagEnd) == '_')) {
            tagEnd++;
        }
        if (tagEnd >= text.length() || text.charAt(tagEnd) != '$' || Character.isDigit(text.charAt(from + 1))) {
            return null;
        }
        String delimiter = text.substring(from, tagEnd + 1);
        int close = text.indexOf(delimiter, tagEnd + 1);
        if (close < 0) {
            throw new IllegalStateException("Unterminated dollar quote " + delimiter);
        }
        return new int[] {from, close + delimiter.length(), tagEnd + 1, close};
    }

    private static int afterString(String text, int from) {
        int at = from + 1;
        while (at < text.length()) {
            if (text.charAt(at) == '\'') {
                if (at + 1 < text.length() && text.charAt(at + 1) == '\'') {
                    at += 2;
                    continue;
                }
                return at + 1;
            }
            at++;
        }
        throw new IllegalStateException("Unterminated string");
    }

    /** The script's statements with comments removed and strings and dollar quotes kept whole. */
    private static List<String> statementsOf(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int at = 0;
        while (at < sql.length()) {
            char character = sql.charAt(at);
            if (sql.startsWith("--", at)) {
                int end = sql.indexOf('\n', at);
                at = end < 0 ? sql.length() : end;
            } else if (sql.startsWith("/*", at)) {
                at = sql.indexOf("*/", at) + 2;
            } else if (character == '\'') {
                int end = afterString(sql, at);
                current.append(sql, at, end);
                at = end;
            } else if (character == '$' && dollarQuote(sql, at) != null) {
                int end = dollarQuote(sql, at)[1];
                current.append(sql, at, end);
                at = end;
            } else if (character == ';') {
                statements.add(current.toString().strip());
                current.setLength(0);
                at++;
            } else {
                current.append(character);
                at++;
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    /** One entry per clock call in the body's code: its qualifier, or {@code ""} when it has none. */
    private static List<String> clockCalls(String statement) {
        int[] body = bodyOf(statement);
        List<String> calls = new ArrayList<>();
        if (body == null) {
            return calls;
        }
        String code = statement.substring(body[0], body[1]);
        int at = 0;
        while (at < code.length()) {
            char character = code.charAt(at);
            if (code.startsWith("--", at)) {
                int end = code.indexOf('\n', at);
                at = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", at)) {
                at = code.indexOf("*/", at) + 2;
            } else if (character == '\'') {
                at = afterString(code, at);
            } else if (character == '$' && dollarQuote(code, at) != null) {
                at = dollarQuote(code, at)[1];
            } else if (Character.isLetter(character) || character == '_') {
                int end = at;
                while (end < code.length() && (Character.isLetterOrDigit(code.charAt(end)) || code.charAt(end) == '_')) {
                    end++;
                }
                if (CLOCK_FUNCTIONS.contains(code.substring(at, end).toLowerCase(Locale.ROOT)) && nextIsParenthesis(code, end)) {
                    calls.add(qualifierBefore(code, at));
                }
                at = end;
            } else {
                at++;
            }
        }
        return calls;
    }

    private static boolean nextIsParenthesis(String code, int from) {
        int at = from;
        while (at < code.length() && Character.isWhitespace(code.charAt(at))) {
            at++;
        }
        return at < code.length() && code.charAt(at) == '(';
    }

    private static String qualifierBefore(String code, int at) {
        int before = at - 1;
        while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
            before--;
        }
        if (before < 0 || code.charAt(before) != '.') {
            return "";
        }
        int end = before;
        before--;
        while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
            before--;
        }
        int last = before + 1;
        while (before >= 0 && (Character.isLetterOrDigit(code.charAt(before)) || code.charAt(before) == '_')) {
            before--;
        }
        return end == 0 ? "" : code.substring(before + 1, last).toLowerCase(Locale.ROOT);
    }

    /** {@code [start, end)} of the function body's text, or null when the statement holds no dollar quote. */
    private static int[] bodyOf(String statement) {
        int at = 0;
        while (at < statement.length()) {
            char character = statement.charAt(at);
            if (character == '\'') {
                at = afterString(statement, at);
            } else if (character == '$' && dollarQuote(statement, at) != null) {
                int[] quote = dollarQuote(statement, at);
                return new int[] {quote[2], quote[3]};
            } else {
                at++;
            }
        }
        return null;
    }

    /** A {@code SET search_path} on the function itself, outside its body, under which {@code pg_catalog} is searched first. */
    private static boolean hasAPinnedPath(String statement) {
        int[] body = bodyOf(statement);
        String outside = body == null ? statement : statement.substring(0, body[0]) + " " + statement.substring(body[1]);
        Matcher set = SET_SEARCH_PATH.matcher(outside.replace("$$", " "));
        if (!set.find()) {
            return false;
        }
        List<String> path = List.of(set.group(1).trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        return !path.contains("pg_catalog") || path.get(0).equals("pg_catalog");
    }
}
