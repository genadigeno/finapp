package com.finapp.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` and the code cannot drift (`P6-TSK-006`, the {@code P0-TSK-022} pattern): the status
 * {@code CHECK}s from {@link CheckoutSessionStatus#sqlValueList()}, the trigger's edges from
 * {@code permittedTransitions()}, the amount from {@link MoneyColumns}, the token's shape and
 * algorithm from {@link CheckoutSessionToken} — and the claims the module's design rests on.
 */
@DisplayName("checkout session migration reconciliation (P6-TSK-006)")
class CheckoutSessionMigrationTest {

    private static final String MIGRATION =
            "db/migration/checkout/V002__create_checkout_session_and_order.sql";

    /**
     * The migration holding the trigger's CURRENT definition (`P12-TSK-007`): `V004` replaces
     * `V002`'s function whole, so the edge and set-once guards read it here - the definition the
     * database runs, not the one it ran first.
     */
    private static final String FINANCING =
            "db/migration/checkout/V004__completed_by_financing.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = CheckoutSessionStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("every trigger edge is generated from the machine, and no terminal state has one"
            + " - in the trigger's current definition (V004)")
    void triggerEdgesMatchTheMachine() {
        for (CheckoutSessionStatus from : CheckoutSessionStatus.values()) {
            if (from.isTerminal()) {
                assertThat(currentTrigger())
                        .as("a terminal state has no trigger edge (%s)", from)
                        .doesNotContain("OLD.status = '" + from.name() + "' AND NEW.status");
                continue;
            }
            String targets =
                    from.permittedTransitions().stream()
                            .map(to -> "'" + to.name() + "'")
                            .collect(Collectors.joining(", "));
            assertThat(currentTrigger())
                    .as("the trigger's %s edge set is the machine's", from)
                    .contains(
                            "(OLD.status = '" + from.name() + "' AND NEW.status IN (" + targets
                                    + "))");
        }
    }

    @Test
    @DisplayName("NO EDGE TARGETS OPEN: birth is the only door, at the schema as at the aggregate")
    void nothingTransitionsIntoOpen() {
        assertThat(
                        Arrays.stream(CheckoutSessionStatus.values())
                                .anyMatch(
                                        from ->
                                                from.canTransitionTo(
                                                        CheckoutSessionStatus.OPEN)))
                .isFalse();
        assertThat(migration()).doesNotContain("NEW.status IN ('OPEN'");
        assertThat(currentTrigger()).doesNotContain("NEW.status IN ('OPEN'");
    }

    @Test
    @DisplayName("INV-MER-06's edge is in the trigger: EXPIRED -> COMPLETED_LATE")
    void landedMoneyHasADestinationInTheSchema() {
        // The race rule as a database fact. Without this edge the trigger would refuse the
        // very transition that keeps a late capture from being orphaned - and the refusal
        // would arrive inside the capture's transaction, after the money had moved.
        assertThat(migration())
                .contains("(OLD.status = 'EXPIRED' AND NEW.status IN ('COMPLETED_LATE'))");
        assertThat(currentTrigger())
                .contains("(OLD.status = 'EXPIRED' AND NEW.status IN ('COMPLETED_LATE'))");
    }

    @Test
    @DisplayName("the payment intent reference is SET ONCE - one payment intent per session")
    void thePaymentIntentIsSetOnceInTheTrigger() {
        assertThat(currentTrigger())
                .contains("IF OLD.payment_intent_ref IS NOT NULL")
                .contains("NEW.payment_intent_ref IS DISTINCT FROM OLD.payment_intent_ref");
    }

    @Test
    @DisplayName("the offer is frozen: everything but status, its timestamp and the intent ref")
    void theOfferIsFrozen() {
        assertThat(currentTrigger())
                .contains("NEW.merchant_ref IS DISTINCT FROM OLD.merchant_ref")
                .contains("NEW.amount_minor IS DISTINCT FROM OLD.amount_minor")
                .contains("NEW.line_summary IS DISTINCT FROM OLD.line_summary")
                .contains(
                        "NEW.fee_schedule_version_ref IS DISTINCT FROM"
                                + " OLD.fee_schedule_version_ref")
                .contains("NEW.token_hash IS DISTINCT FROM OLD.token_hash")
                .contains("NEW.expires_at IS DISTINCT FROM OLD.expires_at");
    }

    @Test
    @DisplayName("no column could hold a token, and the hash's shape is constrained")
    void noColumnCouldHoldAToken() {
        // Asserted over the DECLARED COLUMN NAMES rather than the file's text: a substring
        // search finds `token_hash` in its own CHECK constraint as well as in its declaration,
        // which proves nothing about what columns exist.
        assertThat(declaredColumnNames().stream().filter(name -> name.contains("token")).toList())
                .as("the ONLY token-named column is the hash")
                .containsExactly("token_hash");
        assertThat(columnDeclarations()).doesNotContain("plaintext");
        assertThat(migration())
                .as("the hash is length- and alphabet-bounded, so a plaintext would not fit")
                .contains("CHECK (token_hash ~ '^[A-Za-z0-9+/]{43}=$')");
    }

    @Test
    @DisplayName("one token, one session, across the platform")
    void theTokenIsUniquePlatformWide() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX checkout_session_one_row_per_token");
    }

    @Test
    @DisplayName("ONE ORDER PER SESSION, held by the key rather than by a read-then-write")
    void oneOrderPerSession() {
        assertThat(migration())
                .contains("session_ref        uuid        NOT NULL UNIQUE REFERENCES");
    }

    @Test
    @DisplayName("THE ORDER HAS NO STATUS COLUMN: an order that exists is paid")
    void theOrderHasNoStatus() {
        // Refund standing is derived from the payment's refund rows at read time (ADR-0053 §6);
        // a stored flag would be a second authority beside the rows that moved the money.
        String orderTable =
                migration()
                        .substring(migration().indexOf("CREATE TABLE checkout.checkout_order"));
        String declarations = orderTable.substring(0, orderTable.indexOf(");"));
        assertThat(declarations.toLowerCase())
                .doesNotContain("status")
                .doesNotContain("refund")
                .doesNotContain("fulfil");
    }

    @Test
    @DisplayName("the order is append-only at every rank, by ITS OWN function")
    void theOrderIsAppendOnly() {
        // Its own checkout.orders_are_immutable() rather than merchant's: within a schema,
        // reuse (P6-TSK-005's reasoning); ACROSS schemas the reasoning inverts, because a
        // schema that depends on another's function cannot be created or dropped on its own.
        assertThat(migration())
                .contains("CREATE FUNCTION checkout.orders_are_immutable()")
                .contains("BEFORE UPDATE OR DELETE ON checkout.checkout_order");
        // Asserted over the STATEMENTS, not the file's text: this migration's own prose names
        // merchant's function in order to say it is deliberately NOT used here, and a guard
        // that reads prose reads the opposite of what it means (the P6-TSK-005 finding,
        // applied where the same trap was one line away).
        assertThat(statements())
                .as("no cross-schema function dependency")
                .doesNotContain("merchant.fee_definitions_are_immutable");

        assertThat(
                        migration()
                                .lines()
                                .map(String::trim)
                                .filter(line -> line.startsWith("GRANT "))
                                .filter(line -> line.contains("checkout_order"))
                                .toList())
                .isNotEmpty()
                .allSatisfy(
                        grant ->
                                assertThat(grant)
                                        .doesNotContain("UPDATE")
                                        .doesNotContain("DELETE"));
    }

    @Test
    @DisplayName("the session's UPDATE grant is the three columns that ever change")
    void theSessionGrantIsNarrow() {
        assertThat(migration())
                .contains(
                        "GRANT UPDATE (status, status_changed_at, payment_intent_ref)"
                                + " ON checkout.checkout_session TO finapp_app")
                .contains("GRANT SELECT, INSERT ON checkout.checkout_session TO finapp_app")
                .contains(
                        "GRANT SELECT, INSERT ON checkout.checkout_session_event TO finapp_app");
    }

    @Test
    @DisplayName("the amounts are MoneyColumns' generated shape, and positive")
    void amountsMatchMoneyColumns() {
        assertThat(migration())
                .contains(
                        new MoneyColumns.ColumnNames(
                                        "amount_minor", "amount_currency", "amount_scale")
                                .ddl())
                .contains("CHECK (amount_minor > 0)");
    }

    @Test
    @DisplayName("the line-summary bound is the aggregate's own constant")
    void lineSummaryBoundMatchesTheAggregate() {
        assertThat(migration())
                .contains(
                        "CHECK (length(line_summary) BETWEEN 1 AND "
                                + CheckoutSession.MAX_LINE_SUMMARY_LENGTH
                                + ")");
    }

    @Test
    @DisplayName("NO CROSS-SCHEMA FOREIGN KEYS: all four outward references travel by value")
    void noCrossSchemaForeignKeys() {
        assertThat(migration())
                .doesNotContain("REFERENCES merchant.")
                .doesNotContain("REFERENCES payments.")
                .doesNotContain("REFERENCES ledger.")
                .as("the same-schema FKs are legal and right")
                .contains("REFERENCES checkout.checkout_session (id)");
    }

    @Test
    @DisplayName("the sweeper's read has an index, and it is PARTIAL on the live states")
    void theSweeperHasItsIndex() {
        assertThat(migration())
                .contains("CREATE INDEX checkout_session_live_by_deadline")
                .as("the terminal states are ABSENT from the index the sweeper scans")
                .contains("WHERE status IN ('OPEN', 'PAYMENT_PENDING')");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE checkout.checkout_session");
        assertThat(financing()).contains("ADD COLUMN financed_by_ref uuid");
    }

    // ----------------------------------------------------------------- P12-TSK-007 (V004)

    @Test
    @DisplayName("V004 replaces the trigger's function whole")
    void theFinancingMigrationIsTheTriggersCurrentDefinition() {
        assertThat(statementsOf(financing()))
                .contains(
                        "CREATE OR REPLACE FUNCTION"
                                + " checkout.checkout_session_permits_only_machine_edges()");
        assertThat(currentTrigger()).contains("RETURN NEW;");
    }

    @Test
    @DisplayName("THE FINANCING EDGE BESIDE ITS EVIDENCE: OPEN -> COMPLETED requires the financing"
            + " reference, no intent, and the deadline not passed on the database clock")
    void theFinancingEdgeRequiresItsEvidence() {
        assertThat(
                        CheckoutSessionStatus.isFinancingEdge(
                                CheckoutSessionStatus.OPEN, CheckoutSessionStatus.COMPLETED))
                .isTrue();
        assertThat(currentTrigger())
                .contains("IF OLD.status = 'OPEN' AND NEW.status = 'COMPLETED' THEN")
                .contains(
                        "IF NEW.financed_by_ref IS NULL OR NEW.payment_intent_ref IS NOT NULL"
                                + " THEN")
                .contains("IF OLD.expires_at <= statement_timestamp() THEN");
        // Every other edge into COMPLETED is a capture's: only OPEN's is the financing edge.
        for (CheckoutSessionStatus from : CheckoutSessionStatus.values()) {
            if (from != CheckoutSessionStatus.OPEN) {
                assertThat(
                                CheckoutSessionStatus.isFinancingEdge(
                                        from, CheckoutSessionStatus.COMPLETED))
                        .as("%s -> COMPLETED is not the financing edge", from)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("the financing reference is SET ONCE, and only by the financing edge")
    void theFinancingReferenceIsSetOnceAndOnlyOnItsEdge() {
        assertThat(currentTrigger())
                .contains("IF OLD.financed_by_ref IS NOT NULL")
                .contains("NEW.financed_by_ref IS DISTINCT FROM OLD.financed_by_ref")
                .contains("IF OLD.financed_by_ref IS NULL AND NEW.financed_by_ref IS NOT NULL")
                .contains("AND NOT (OLD.status = 'OPEN' AND NEW.status = 'COMPLETED')");
    }

    @Test
    @DisplayName("NEVER BOTH, NEVER LATE: a financed session is COMPLETED without an intent, by"
            + " CHECK - so there is no COMPLETED_LATE by financing")
    void aFinancedSessionIsCompletedWithoutAnIntent() {
        assertThat(statementsOf(financing()))
                .contains(
                        "CHECK (financed_by_ref IS NULL OR (status = 'COMPLETED' AND"
                                + " payment_intent_ref IS NULL))");
    }

    @Test
    @DisplayName("one session per purchase, by a partial unique index - V003's shape for intents")
    void oneSessionPerPurchase() {
        assertThat(statementsOf(financing()))
                .contains("CREATE UNIQUE INDEX checkout_session_one_session_per_financing")
                .contains("WHERE financed_by_ref IS NOT NULL");
    }

    @Test
    @DisplayName("a session completed by financing commits only beside its order - deferred")
    void aFinancedSessionCommitsBesideItsOrder() {
        String statements = statementsOf(financing());
        assertThat(statements)
                .contains("CREATE CONSTRAINT TRIGGER checkout_session_financed_beside_its_order")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("WHEN (OLD.status = 'OPEN' AND NEW.status = 'COMPLETED')")
                .contains("FROM checkout.checkout_order o WHERE o.session_ref = NEW.id");
        // Over the function BODIES, not the file: the column's own COMMENT names the purchase's
        // table by value, which is the reference travelling as data rather than a read.
        String bodies =
                java.util.Arrays.stream(statements.split("\\$\\$"))
                        .filter(part -> part.contains("BEGIN"))
                        .collect(Collectors.joining("\n"));
        assertThat(bodies).as("the guard reads the two function bodies").contains("BEGIN");
        assertThat(bodies)
                .as("no cross-schema read: the purchase's half is bnpl's (ADR-0029)")
                .doesNotContain("bnpl.")
                .doesNotContain("ledger.")
                .doesNotContain("merchant.");
    }

    @Test
    @DisplayName("the financing reference's grant arrives with its column - and nothing wider")
    void theFinancingGrantIsTheOneColumn() {
        assertThat(
                        financing()
                                .lines()
                                .map(String::trim)
                                .filter(line -> line.startsWith("GRANT "))
                                .toList())
                .containsExactly(
                        "GRANT UPDATE (financed_by_ref) ON checkout.checkout_session TO"
                                + " finapp_app;");
    }

    /**
     * Only the {@code CREATE TABLE} bodies, with {@code --} comments stripped — the column and
     * constraint declarations and nothing else ({@code FeeScheduleMigrationTest}'s helper, and
     * its recorded reason: a guard whose subject is column declarations should read column
     * declarations rather than the file's prose).
     */
    private static String columnDeclarations() {
        StringBuilder declarations = new StringBuilder();
        boolean inside = false;
        for (String raw : migration().lines().toList()) {
            String line = raw.replaceFirst("--.*$", "");
            if (line.stripLeading().startsWith("CREATE TABLE")) {
                inside = true;
                continue;
            }
            if (inside) {
                if (line.startsWith(");")) {
                    inside = false;
                    continue;
                }
                declarations.append(line).append('\n');
            }
        }
        return declarations.toString().toLowerCase();
    }

    /** Every column name the migration declares, across all three tables. */
    private static java.util.List<String> declaredColumnNames() {
        return columnDeclarations()
                .lines()
                .map(String::strip)
                .filter(line -> line.matches("^[a-z_]+\s+(uuid|text|bigint|char|smallint|timestamptz|integer|numeric).*"))
                .map(line -> line.split("\s+")[0])
                .toList();
    }

    /** The migration with every {@code --} comment stripped — statements only. */
    private static String statements() {
        return migration()
                .lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        return read(MIGRATION);
    }

    private static String financing() {
        return read(FINANCING);
    }

    /** The trigger function as the database now runs it: `V004`'s definition, statements only. */
    private static String currentTrigger() {
        String text = statementsOf(financing());
        int start =
                text.indexOf(
                        "CREATE OR REPLACE FUNCTION"
                                + " checkout.checkout_session_permits_only_machine_edges()");
        assertThat(start).as("V004 redefines the trigger function").isNotNegative();
        return text.substring(start, text.indexOf("$$;", start));
    }

    /** {@code text} with every {@code --} comment stripped. */
    private static String statementsOf(String text) {
        return text.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .collect(Collectors.joining("\n"));
    }

    private static String read(String resource) {
        try (InputStream migration =
                CheckoutSessionMigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(resource)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the classpath: " + resource);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
