package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V004` and the code cannot drift (`P6-TSK-004`, the {@code P0-TSK-022} pattern): the name
 * bound from {@link FeeSchedule#MAX_NAME_LENGTH}, the rate's type and bound from
 * {@link FeeRate#MAX_SCALE}, the rounding list from {@link RoundingPolicy}, the refund-fee list
 * from {@link RefundFeePolicy#sqlValueList()}, the fixed part from {@link MoneyColumns} — and
 * the three claims the immutability rests on.
 */
@DisplayName("fee schedule migration reconciliation (P6-TSK-004)")
class FeeScheduleMigrationTest {

    private static final String MIGRATION = "db/migration/merchant/V004__create_fee_schedule.sql";

    @Test
    @DisplayName("the rounding policy CHECK is generated from the enum, not hand-listed")
    void roundingPolicyCheckMatchesTheEnum() {
        // INV-MON-03 lives or dies on this column accepting exactly the policies the code can
        // apply: a policy the schema rejects is a version nobody can create, and a policy the
        // schema accepts but RoundingPolicy.ofName does not is a row nobody can READ - which
        // would make a pinned version unrecomputable, the one thing INV-MER-03 promises.
        String list =
                Arrays.stream(RoundingPolicy.values())
                        .map(policy -> "'" + policy.policyName() + "'")
                        .collect(Collectors.joining(", "));
        assertThat(migration()).contains("CHECK (rounding_policy IN (" + list + "))");
    }

    @Test
    @DisplayName("the refund fee policy CHECK is generated from the enum")
    void refundFeePolicyCheckMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (refund_fee_policy IN (" + RefundFeePolicy.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the rate's type and bound are generated from FeeRate")
    void rateTypeAndBoundMatchTheValueObject() {
        // numeric(MAX_SCALE + 1, MAX_SCALE): one integer digit, so the CHECK below is what
        // bounds the value rather than the type silently overflowing it.
        assertThat(migration())
                .contains("numeric(" + (FeeRate.MAX_SCALE + 1) + ", " + FeeRate.MAX_SCALE + ")")
                .contains("CHECK (rate >= 0 AND rate < 1)");
    }

    @Test
    @DisplayName("the fixed part is MoneyColumns' generated three-column shape")
    void fixedPartMatchesMoneyColumns() {
        assertThat(migration()).contains(MoneyColumns.columnsFor("fixed").ddl());
    }

    @Test
    @DisplayName("the name bound is FeeSchedule's, in one place")
    void nameBoundMatchesTheAggregate() {
        assertThat(migration())
                .contains("CHECK (length(name) BETWEEN 1 AND " + FeeSchedule.MAX_NAME_LENGTH + ")");
    }

    @Test
    @DisplayName("THE KEYSTONE: a version cannot be created already effective in the past")
    void versionsTakeEffectForward() {
        // INV-MER-03's load-bearing constraint. Everything else about immutability protects a
        // version once written; this protects the HISTORY from the writing - without it, a
        // backdated version silently reprices every capture since that instant.
        assertThat(migration())
                .contains("CONSTRAINT fee_schedule_version_takes_effect_forward")
                .contains("CHECK (effective_from >= created_at)");
    }

    @Test
    @DisplayName("immutability is a trigger, a withheld grant and no port method")
    void immutabilityIsHeldAtEveryRank() {
        String migration = migration();
        assertThat(migration)
                .as("the trigger is UNCONDITIONAL and covers both tables and both operations")
                .contains("BEFORE UPDATE OR DELETE ON merchant.fee_schedule\n")
                .contains("BEFORE UPDATE OR DELETE ON merchant.fee_schedule_version\n")
                .contains("EXECUTE FUNCTION merchant.fee_definitions_are_immutable()");
        assertThat(migration)
                .as("the application role can insert and read")
                .contains("GRANT SELECT, INSERT ON merchant.fee_schedule TO finapp_app")
                .contains("GRANT SELECT, INSERT ON merchant.fee_schedule_version TO finapp_app");

        // ASSERTED OVER THE GRANT STATEMENTS THEMSELVES, not over the file's text. The first
        // form of this check was `doesNotContain("DELETE ON merchant.fee_schedule")` and it
        // failed on the immutability TRIGGER's own declaration - BEFORE UPDATE OR DELETE ON
        // merchant.fee_schedule. A blunt substring assertion that happens to pass is worse
        // than one that fails, because it reads as proof of something it never checked.
        assertThat(
                        migration
                                .lines()
                                .map(String::trim)
                                .filter(line -> line.startsWith("GRANT "))
                                .filter(line -> line.contains("merchant.fee_schedule"))
                                .toList())
                .as("no grant confers UPDATE or DELETE on a fee definition")
                .isNotEmpty()
                .allSatisfy(
                        grant ->
                                assertThat(grant)
                                        .doesNotContain("UPDATE")
                                        .doesNotContain("DELETE"));

        // The third rank, asserted on the port rather than on prose: there is no method that
        // could express a repricing, so the defect cannot be written before it is refused.
        assertThat(
                        Arrays.stream(FeeScheduleStore.class.getDeclaredMethods())
                                .map(java.lang.reflect.Method::getName))
                .as("no update and no delete against a fee definition, anywhere on the port")
                .noneMatch(name -> name.startsWith("update") || name.startsWith("delete"));
    }

    @Test
    @DisplayName("the version number is unique per schedule - the lock's second rank")
    void versionNumbersAreUniquePerSchedule() {
        assertThat(migration())
                .contains("CONSTRAINT fee_schedule_version_number_is_unique_per_schedule")
                .contains("UNIQUE (fee_schedule_id, version)");
    }

    @Test
    @DisplayName("a version prices its schedule's currency, held by the database")
    void versionCurrencyIsAForeignKey() {
        // The composite FK is what turns "the domain remembers to check" into "the database
        // will not hold the row" - and it needs the redundant UNIQUE (id, currency) to point at.
        assertThat(migration())
                .contains("CONSTRAINT fee_schedule_id_and_currency UNIQUE (id, currency)")
                .contains("FOREIGN KEY (fee_schedule_id, fixed_currency)")
                .contains("REFERENCES merchant.fee_schedule (id, currency)");
    }

    @Test
    @DisplayName("the assignment's grant is narrowed to the pointer, and the history is append-only")
    void assignmentGrantsAreNarrow() {
        assertThat(migration())
                .contains(
                        "GRANT UPDATE (fee_schedule_id, assigned_at, assigned_by)"
                                + " ON merchant.merchant_fee_schedule TO finapp_app")
                .contains(
                        "GRANT SELECT, INSERT ON merchant.merchant_fee_schedule_event"
                                + " TO finapp_app")
                .doesNotContain("DELETE ON merchant.merchant_fee_schedule");
    }

    @Test
    @DisplayName("the assignment reason is NOT NULL, and a recorded move must move")
    void theAssignmentHistoryIsHonest() {
        assertThat(migration())
                .as("INV-AUD-03: changing a counterparty's terms is a commercial judgement")
                .contains("reason               text        NOT NULL")
                .as("a convergent assignment writes nothing, so from = to would be a lie")
                .contains("CHECK (from_fee_schedule_id IS NULL"
                        + " OR from_fee_schedule_id <> to_fee_schedule_id)");
    }

    @Test
    @DisplayName("this schema still holds no balance (INV-MER-02)")
    void noBalanceColumnAppears() {
        // The standing claim V002 made, re-asserted by the task that adds three more tables to
        // the schema - read over the COLUMN DECLARATIONS, because this file's own prose
        // discusses the payable at length and must be allowed to.
        assertThat(columnDeclarations())
                .doesNotContain("balance")
                .doesNotContain("payable")
                .doesNotContain("owed");
    }

    /**
     * Only the {@code CREATE TABLE} bodies, with {@code --} comments stripped — the column and
     * constraint declarations and nothing else.
     *
     * <p>The first form of the no-balance sweep filtered out lines <em>containing</em>
     * {@code COMMENT ON}, and a {@code COMMENT ON COLUMN} whose text wraps onto a second line
     * defeated it — the sweep read prose that says "not what is owed" as a column that holds
     * what is owed. A guard whose subject is column declarations should read column
     * declarations.
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


    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE merchant.fee_schedule_version");
    }

    private static String migration() {
        try (InputStream migration =
                FeeScheduleMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
