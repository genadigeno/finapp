package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V010` and the code cannot drift (`P12-TSK-006`; the {@code MerchantMigrationTest} / {@code PaymentFeePinMigrationTest}
 * pattern): the enablement's status CHECK, one-live index and trigger edges from {@link BnplEnablementStatus}; the
 * product CHECKs from {@link MerchantBnplProduct}; the reason bound from {@link BnplEnablement#MAX_REASON_LENGTH}; the
 * four-eyes CHECK; the pin's primary key, immutability reused from V004 and its insert-only grant; the gross's columns
 * from {@link MoneyColumns}. The live behaviour is {@code MerchantBnplEnablementDatabaseTest}'s.
 */
@DisplayName("merchant BNPL migration reconciliation (P12-TSK-006)")
class BnplEnablementMigrationTest {

    private static final String MIGRATION = "db/migration/merchant/V010__bnpl_enablement_and_fee_schedule.sql";

    @Test
    @DisplayName("the enablement's status CHECK and one-live index are generated from the machine")
    void statusListsMatchTheEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + BnplEnablementStatus.sqlValueList() + "))")
                .contains("WHERE status IN (" + BnplEnablementStatus.liveSqlValueList() + ");");
    }

    @Test
    @DisplayName("every trigger edge is the machine's, and a terminal state has none")
    void triggerEdgesMatchTheMachine() {
        String migration = migration();
        for (BnplEnablementStatus from : BnplEnablementStatus.values()) {
            if (from.isTerminal()) {
                assertThat(migration).doesNotContain("OLD.status = '" + from.name() + "' AND NEW.status");
                continue;
            }
            String targets = from.permittedTransitions().stream()
                    .map(to -> "'" + to.name() + "'")
                    .collect(Collectors.joining(", "));
            assertThat(migration)
                    .as("the trigger's %s edge set is the machine's", from)
                    .contains("(OLD.status = '" + from.name() + "' AND NEW.status IN (" + targets + "))");
        }
    }

    @Test
    @DisplayName("FOUR EYES at DB-CONSTRAINT rank: the approver is not the proposer, for every approved state")
    void fourEyesIsAConstraint() {
        assertThat(migration()).contains("CONSTRAINT merchant_bnpl_enablement_approver_is_not_proposer\n"
                + "        CHECK (status NOT IN ('ACTIVE', 'WITHDRAWN') OR decided_by <> proposed_by)");
    }

    @Test
    @DisplayName("every instant on the enablement is the database's, written in the trigger after the lock")
    void stampsAreTheDatabases() {
        assertThat(migration())
                .contains("NEW.proposed_at := clock_timestamp();")
                .contains("NEW.decided_at := GREATEST(clock_timestamp(), OLD.proposed_at);")
                .contains("NEW.withdrawn_at := GREATEST(clock_timestamp(), OLD.decided_at);");
    }

    @Test
    @DisplayName("every product CHECK is generated from merchant's product names")
    void productChecksMatchTheEnum() {
        String check = "CHECK (product IN (" + MerchantBnplProduct.sqlValueList() + "))";
        assertThat(migration().split(java.util.regex.Pattern.quote(check), -1)).hasSize(4);
    }

    @Test
    @DisplayName("every reason is bounded by the domain's own constant")
    void reasonBoundsMatchTheDomain() {
        String bound = "BETWEEN 1 AND " + BnplEnablement.MAX_REASON_LENGTH + ")";
        assertThat(migration())
                .contains("CHECK (length(proposal_reason) " + bound)
                .contains("CHECK (decision_reason IS NULL OR length(decision_reason) " + bound)
                .contains("CHECK (withdrawal_reason IS NULL OR length(withdrawal_reason) " + bound)
                .contains("CHECK (length(reason) " + bound);
    }

    @Test
    @DisplayName("ONE PIN PER PURCHASE: the purchase is the pin's primary key")
    void onePinPerPurchase() {
        assertThat(migration()).contains("CONSTRAINT bnpl_fee_pin_one_per_purchase PRIMARY KEY (purchase_id)");
    }

    @Test
    @DisplayName("the pin's gross is MoneyColumns' shape, and the pin is immutable by V004's own function")
    void thePinIsMoneyShapedAndImmutable() {
        assertThat(migration())
                .contains(MoneyColumns.columnsFor("gross").ddl())
                .contains("BEFORE UPDATE OR DELETE ON merchant.bnpl_fee_pin")
                .contains("EXECUTE FUNCTION merchant.fee_definitions_are_immutable()");
    }

    @Test
    @DisplayName("no grant confers DELETE anywhere, nor UPDATE on the pin or the history")
    void theGrantsAreNarrow() {
        java.util.List<String> grants = migration().lines()
                .map(String::trim)
                .filter(line -> line.startsWith("GRANT ") || line.startsWith("ON merchant."))
                .toList();
        assertThat(grants).isNotEmpty().noneMatch(grant -> grant.contains("DELETE"));
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.bnpl_fee_pin TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON merchant.merchant_bnpl_fee_schedule_event TO finapp_app;")
                .doesNotContain("ON merchant.bnpl_fee_pin TO finapp_app;\nGRANT UPDATE")
                .doesNotContain("GRANT UPDATE (decided_at")
                .contains("GRANT UPDATE (status, decided_by, decision_reason, withdrawn_by, withdrawal_reason)");
    }

    @Test
    @DisplayName("a schedule that retains its fee is refused for BNPL, and a pin names only a returning version")
    void retainedIsRefusedForEveryWriter() {
        assertThat(migration())
                .contains("v.refund_fee_policy <> 'RETURNED'")
                .contains("IF pinned.refund_fee_policy <> 'RETURNED' THEN")
                .contains("IF pinned.effective_from > NEW.pinned_at THEN");
    }

    @Test
    @DisplayName("V012 (the Phase 12 -> 13 gate's A5-F5): the twin masks exactly InstrumentShapes' platform identifiers and"
            + " scans its runs and tokens, and every BNPL reason column carries its CHECK")
    void v012IsTheInstrumentScreensTwin() {
        String sql = migration(V012);
        assertThat(sql)
                .contains("'" + com.finapp.sharedkernel.security.InstrumentShapes.PLATFORM_IDENTIFIER_REGEX + "'")
                .contains("'" + com.finapp.sharedkernel.security.InstrumentShapes.DIGIT_RUN_REGEX + "'")
                .contains("'" + com.finapp.sharedkernel.security.InstrumentShapes.ALPHANUMERIC_RUN_REGEX + "'");
        for (String column : java.util.List.of("merchant_bnpl_enablement.proposal_reason",
                "merchant_bnpl_enablement.decision_reason", "merchant_bnpl_enablement.withdrawal_reason",
                "merchant_bnpl_fee_schedule_event.reason")) {
            String table = column.substring(0, column.indexOf('.'));
            String name = column.substring(column.indexOf('.') + 1);
            assertThat(sql).as(column).contains("ALTER TABLE merchant." + table + "\n    ADD CONSTRAINT "
                    + column.replace('.', '_') + "_no_instrument_shape CHECK (\n        NOT"
                    + " merchant.holds_instrument_shape(" + name + "));");
        }
    }

    @Test
    @DisplayName("V012 (the Phase 12 -> 13 gate's A3-M1): the pin's instant is its own transaction's on the database clock,"
            + " for every writer - V010's checks kept, an immediate version (effective_from = created_at) in force from"
            + " its commit")
    void v012BindsThePinsInstantToTheDatabaseClock() {
        assertThat(migration(V012))
                .contains("IF NEW.pinned_at < transaction_timestamp() OR NEW.pinned_at > clock_timestamp() THEN")
                .contains("IF pinned.refund_fee_policy <> 'RETURNED' THEN")
                .contains("IF pinned.effective_from > NEW.pinned_at AND pinned.effective_from <> pinned.created_at THEN")
                .contains("CREATE OR REPLACE FUNCTION merchant.bnpl_fee_pin_names_a_returning_version_in_force()");
    }

    private static final String V012 =
            "db/migration/merchant/V012__bnpl_reasons_screened_and_the_pin_on_the_database_clock.sql";

    private static String migration() {
        return migration(MIGRATION);
    }

    private static String migration(String path) {
        try (InputStream in = BnplEnablementMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("the migration is on the classpath: %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
