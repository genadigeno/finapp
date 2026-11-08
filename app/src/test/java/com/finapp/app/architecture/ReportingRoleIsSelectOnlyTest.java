package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reporting role holds {@code SELECT} and nothing else (`P15-TSK-021`; {@code PHASE_15_PLAN.md} C13;
 * {@code INV-ACC-14} at the privilege level; {@code THREAT_MODEL.md} T7, T44).
 *
 * <p>Reads every statement of every module's migrations. Each grant naming {@code finapp_reporting} must be
 * {@code USAGE} on a schema or {@code SELECT} on a table - never a write, a {@code TRUNCATE}, a reference, a trigger,
 * {@code ALL}, a column privilege, a default privilege or a role membership - and the tables granted must be exactly
 * the register below: the tables a report run reads (its generation and a FINAL run's regeneration), recounted from the
 * code at {@code P15-TSK-021} across the fourteen definitions' ports. A table added to report code needs a reviewed
 * grant and a line here; a grant beyond the register is a wider read than reporting needs (T7). What the database does
 * with the grants - a planted write refused, every register table readable, a restricted one refused, a run generated
 * as the role - is {@code ReportingRoleDatabaseTest}'s, owed (C14).
 *
 * <p>The review's fix round (2026-11-08) closed what the rule could not read: a grant {@code WITH GRANT OPTION} (the
 * role could hand its reads on) was silently accepted, and so was a statement naming the role that is not a plain
 * {@code GRANT} - a fragment of a {@code DO} block or an {@code EXECUTE}d string, which the rule skipped. Both are
 * violations now, and any {@code GRANT} written inside a dollar-quoted body ({@code DO} blocks, functions) is refused
 * whoever it names, since its grantee may be built at run time.
 */
@Tag("architecture")
@DisplayName("the reporting role holds SELECT on the report run's tables and nothing else (P15-TSK-021)")
public class ReportingRoleIsSelectOnlyTest {

    public static final String ROLE = "finapp_reporting";

    /** The tables a report run reads, by schema - recounted from the code at {@code P15-TSK-021}. */
    public static final Set<String> REGISTER = Set.of(
            "accounting.accounting_period", "accounting.report_run", "accounting.gl_mapping_version",
            "accounting.gl_account", "accounting.gl_mapping_rule", "accounting.valuation_rate_set",
            "accounting.valuation_rate", "accounting.prior_period_adjustment", "accounting.period_close",
            "accounting.period_close_check",
            "ledger.journal_entry", "ledger.journal_line", "ledger.ledger_account", "ledger.counterparty",
            "ledger.adjustment_proposal",
            "reconciliation.suspense_item", "reconciliation.suspense_release", "reconciliation.break",
            "lending.loan_disbursement", "lending.loan", "lending.loan_agreement", "lending.loan_offer",
            "lending.loan_terms");

    private static final Pattern GRANT = Pattern.compile("(?is)^\\s*GRANT\\s+(.+?)\\s+TO\\s+(.+?)\\s*$");
    private static final Pattern ON = Pattern.compile("(?is)^(.+?)\\s+ON\\s+(.+)$");
    private static final Pattern DEFAULT_PRIVILEGES = Pattern.compile("(?is)\\bALTER\\s+DEFAULT\\s+PRIVILEGES\\b");
    private static final Pattern GRANT_OPTION = Pattern.compile("(?i)\\bWITH\\s+GRANT\\s+OPTION\\b");
    private static final Pattern REVOKE = Pattern.compile("(?is)^\\s*REVOKE\\b");
    /** A dollar-quoted body - a {@code DO} block or a function's - whose text the statement split cannot read. */
    private static final Pattern DOLLAR_BODY = Pattern.compile("(?s)\\$(\\w*)\\$(.*?)\\$\\1\\$");
    private static final Pattern GRANT_WORD = Pattern.compile("(?i)\\bGRANT\\b");

    @Test
    @DisplayName("every grant to the reporting role is USAGE on a schema or SELECT on a register table, and the"
            + " register is granted whole")
    void theRoleReadsTheRegisterAndNothingElse() throws IOException {
        List<String> scripts = migrations();
        Grants grants = grants(statements(scripts));
        assertThat(grants.violations).as("a privilege the reporting role must never hold").isEmpty();
        assertThat(dynamicGrants(scripts)).as("a grant the rule cannot read: written inside a DO block or a function")
                .isEmpty();
        assertThat(grants.tables).as("exactly the tables a report run reads").isEqualTo(new TreeSet<>(REGISTER));
        assertThat(grants.schemas).as("USAGE on the register's schemas alone")
                .isEqualTo(new TreeSet<>(REGISTER.stream().map(table -> table.substring(0, table.indexOf('.'))).toList()));
    }

    @Test
    @DisplayName("the rule can fail: a write, ALL, a column privilege, a default privilege, a membership and a table"
            + " beyond the register are each caught; the role named beside another grantee is still read")
    void theRuleCanFail() {
        assertThat(grants(List.of("GRANT INSERT ON accounting.report_run TO finapp_reporting")).violations).hasSize(1);
        assertThat(grants(List.of("GRANT SELECT, UPDATE ON ledger.journal_entry TO finapp_app, finapp_reporting"))
                .violations).hasSize(1);
        assertThat(grants(List.of("GRANT ALL ON ledger.journal_line TO finapp_reporting")).violations).hasSize(1);
        assertThat(grants(List.of("GRANT SELECT (id) ON ledger.journal_line TO finapp_reporting")).violations).hasSize(1);
        assertThat(grants(List.of("ALTER DEFAULT PRIVILEGES IN SCHEMA ledger GRANT SELECT ON TABLES TO finapp_reporting"))
                .violations).hasSize(1);
        assertThat(grants(List.of("GRANT finapp_app TO finapp_reporting")).violations).hasSize(1);
        assertThat(grants(List.of("GRANT CREATE ON SCHEMA ledger TO finapp_reporting")).violations).hasSize(1);
        assertThat(grants(List.of("GRANT SELECT ON identity.mfa_enrolment TO finapp_reporting")).tables)
                .containsExactly("identity.mfa_enrolment");
        assertThat(grants(List.of("GRANT SELECT ON ledger.journal_entry TO finapp_app")).tables).isEmpty();
        Grants admitted = grants(List.of("GRANT USAGE ON SCHEMA ledger TO finapp_reporting",
                "GRANT SELECT ON ledger.journal_entry, ledger.journal_line TO finapp_reporting"));
        assertThat(admitted.violations).isEmpty();
        assertThat(admitted.schemas).containsExactly("ledger");
        assertThat(admitted.tables).containsExactly("ledger.journal_entry", "ledger.journal_line");

        // The review's fix round: a grant option, a grant the rule cannot read, and one written at run time.
        assertThat(grants(List.of("GRANT SELECT ON ledger.journal_entry TO finapp_reporting WITH GRANT OPTION"))
                .violations).singleElement().asString().contains("grant option");
        assertThat(grants(List.of("GRANT SELECT ON ledger.journal_entry TO finapp_reporting GRANTED BY finapp_migrator"))
                .tables).containsExactly("ledger.journal_entry");
        assertThat(grants(List.of("DO $$ BEGIN EXECUTE 'GRANT INSERT ON accounting.report_run TO finapp_reporting'"))
                .violations).hasSize(1);
        assertThat(grants(List.of("REVOKE SELECT ON ledger.journal_entry FROM finapp_reporting")).violations).isEmpty();
        assertThat(dynamicGrants(List.of("DO $$ DECLARE t text; BEGIN FOR t IN SELECT 1 LOOP"
                + " EXECUTE format('GRANT SELECT ON %I TO %I', t, current_setting('app.role')); END LOOP; END $$;")))
                .hasSize(1);
        assertThat(dynamicGrants(List.of("CREATE FUNCTION f() RETURNS trigger LANGUAGE plpgsql AS $fn$ BEGIN"
                + " RAISE EXCEPTION 'no'; END; $fn$;"))).isEmpty();
    }

    /** What the statements grant the role, and what they grant it that it must never hold. */
    record Grants(Set<String> tables, Set<String> schemas, List<String> violations) {}

    static Grants grants(List<String> statements) {
        Set<String> tables = new TreeSet<>();
        Set<String> schemas = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        for (String statement : statements) {
            String flat = statement.replaceAll("\\s+", " ").trim();
            if (!names(flat)) {
                continue;
            }
            if (DEFAULT_PRIVILEGES.matcher(flat).find()) {
                violations.add("a default privilege: " + flat);
                continue;
            }
            Matcher grant = GRANT.matcher(flat);
            if (!grant.matches()) {
                if (!REVOKE.matcher(flat).find()) {
                    violations.add("the role named outside a plain GRANT (a DO block, an EXECUTE): " + flat);
                }
                continue;
            }
            if (!grantees(grant.group(2)).contains(ROLE)) {
                violations.add("the role named in a grant the rule cannot read: " + flat);
                continue;
            }
            if (GRANT_OPTION.matcher(grant.group(2)).find()) {
                violations.add("a grant option - the role could pass its reads on: " + flat);
                continue;
            }
            Matcher on = ON.matcher(grant.group(1));
            if (!on.matches()) {
                violations.add("a role membership: " + flat);
                continue;
            }
            String privileges = on.group(1).trim().toUpperCase(Locale.ROOT);
            String objects = on.group(2).trim();
            if (objects.toUpperCase(Locale.ROOT).startsWith("SCHEMA ")) {
                if (!privileges.equals("USAGE")) {
                    violations.add("more than USAGE on a schema: " + flat);
                    continue;
                }
                list(objects.substring("SCHEMA ".length())).forEach(schemas::add);
                continue;
            }
            if (!privileges.equals("SELECT")) {
                violations.add("more than SELECT on a table: " + flat);
                continue;
            }
            String target = objects.toUpperCase(Locale.ROOT).startsWith("TABLE ") ? objects.substring(6) : objects;
            list(target).forEach(tables::add);
        }
        return new Grants(tables, schemas, violations);
    }

    private static boolean names(String statement) {
        return Pattern.compile("(?i)\\b" + ROLE + "\\b").matcher(statement).find();
    }

    private static List<String> grantees(String clause) {
        return list(clause.replaceAll("(?i)\\bWITH\\s+GRANT\\s+OPTION\\b", "")
                .replaceAll("(?i)\\bGRANTED\\s+BY\\s+\\S+", "").replaceAll("(?i)\\bGROUP\\b", ""));
    }

    /** Every {@code GRANT} written inside a dollar-quoted body: its grantee may be built at run time. */
    static List<String> dynamicGrants(List<String> scripts) {
        List<String> found = new ArrayList<>();
        for (String script : scripts) {
            Matcher body = DOLLAR_BODY.matcher(script);
            while (body.find()) {
                if (GRANT_WORD.matcher(body.group(2)).find()) {
                    found.add(body.group(2).replaceAll("\\s+", " ").trim());
                }
            }
        }
        return found;
    }

    private static List<String> statements(List<String> scripts) {
        List<String> statements = new ArrayList<>();
        scripts.forEach(script -> statements.addAll(Arrays.asList(script.split(";"))));
        return statements;
    }

    private static List<String> list(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).map(token -> token.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isEmpty()).toList();
    }

    /** Every module's migration scripts, comments removed. */
    private static List<String> migrations() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<String> scripts = new ArrayList<>();
        int files = 0;
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path directory = module.resolve("src/main/resources/db/migration").resolve(module.getFileName());
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> listed = Files.list(directory)) {
                    for (Path script : listed.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                        files++;
                        scripts.add(EvidenceCitations.read(script).replaceAll("--[^\\n]*", ""));
                    }
                }
            }
        }
        assertThat(files).as("the census: every module's migrations were read").isGreaterThan(300);
        return scripts;
    }
}
