package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The migrations and the code cannot drift (`P5-TSK-004`, `P7-TSK-007`; the {@code P0-TSK-022}
 * pattern — the {@code BeneficiaryMigrationTest} shape verbatim).
 *
 * <p>The generated artefacts, each with one definition: the status {@code CHECK} from
 * {@link PaymentMethodStatus#sqlValueList()}, the kind and payee {@code CHECK}s from
 * {@link PaymentMethodKind#sqlValueList()} and {@link PayeeCheck#sqlValueList()}, the
 * <strong>one-live index predicates</strong> from
 * {@link PaymentMethodStatus#sqlTerminalValueList()} — a state added without deciding whether
 * it frees the slots either lets a party hold two live rows for one instrument or blocks the
 * successor forever, both silent without this reconciliation — the trigger's edge conditions
 * from {@link PaymentMethodStatus#permittedTransitions()}, and the bounds that mirror the
 * aggregates' own.
 *
 * <p><strong>Applied migrations are frozen (ADR-0011)</strong>: a definition {@code V003}
 * replaces — the trigger function, the suffix rule — is reconciled against {@code V003}'s
 * text, the current definition; {@code V002} keeps the pins on what it still defines.
 */
@DisplayName("payment method migration reconciliation (P5-TSK-004, P7-TSK-007)")
class PaymentMethodMigrationTest {

    private static final String V002 =
            "db/migration/paymentmethods/V002__create_payment_method.sql";

    private static final String V003 =
            "db/migration/paymentmethods/V003__the_bank_account_instrument.sql";

    @Test
    @DisplayName("the status CHECK is generated from the machine")
    void statusCheckMatchesTheEnum() {
        assertThat(migration(V002))
                .contains("CHECK (status IN (" + PaymentMethodStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("both one-live index predicates are generated from the terminal set")
    void oneLiveIndexPredicatesMatchTheTerminalSet() {
        String predicate =
                "WHERE status NOT IN (" + PaymentMethodStatus.sqlTerminalValueList() + ")";
        assertThat(migration(V002))
                .contains("CREATE UNIQUE INDEX payment_method_one_live_per_party_token")
                .contains(predicate);
        assertThat(migration(V003))
                .contains("CREATE UNIQUE INDEX payment_method_one_live_per_party_destination")
                .contains(predicate);
    }

    @Test
    @DisplayName("the trigger's edge conditions are generated from permittedTransitions()")
    void triggerEdgesMatchTheMachine() {
        // V003 CREATE OR REPLACEs the function, so ITS text is the live trigger; the sweep
        // runs against the current definition (V002's copy is applied history).
        for (PaymentMethodStatus from : PaymentMethodStatus.values()) {
            if (from.isTerminal()) {
                // A terminal state must appear in NO edge condition as a source (INV-LIFE-04).
                assertThat(migration(V003)).doesNotContain("OLD.status = '" + from.name() + "'");
                continue;
            }
            assertThat(migration(V003))
                    .as("the trigger must carry %s's exact edge set", from)
                    .contains(
                            "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                                    + from.permittedTransitions().stream()
                                            .map(to -> "'" + to.name() + "'")
                                            .collect(Collectors.joining(", "))
                                    + "))");
        }
    }

    @Test
    @DisplayName("the recreated trigger is NULL-safe: IS DISTINCT FROM on every frozen column")
    void triggerImmutabilityIsNullSafe() {
        // The P7-TSK-007 repair, pinned in its falsifiable form: on per-kind nullable columns
        // the old <> comparison was NULL-blind (NULL <> 'x' is NULL, so a bank row's brand
        // could be edited). Every immutability comparison is IS DISTINCT FROM, the kind and
        // the three bank columns included, and the <> form is gone.
        String trigger = migration(V003);
        for (String frozen :
                new String[] {
                    "id",
                    "party_id",
                    "kind",
                    "token_reference",
                    "brand",
                    "display_suffix",
                    "expiry_month",
                    "expiry_year",
                    "destination_reference",
                    "payee_check",
                    "no_match_acknowledged_at",
                    "created_at"
                }) {
            assertThat(trigger)
                    .contains("OLD." + frozen + " IS DISTINCT FROM NEW." + frozen)
                    .doesNotContain("OLD." + frozen + " <> NEW." + frozen);
        }
    }

    @Test
    @DisplayName("the kind, payee and coherence CHECKs are generated from the enums")
    void kindAndPayeeChecksMatchTheEnums() {
        assertThat(migration(V003))
                .contains("CHECK (kind IN (" + PaymentMethodKind.sqlValueList() + "))")
                .contains("CHECK (payee_check IN (" + PayeeCheck.sqlValueList() + "))")
                // A card is exactly its card facts, a bank account exactly its bank facts.
                .contains("((kind = 'CARD_TOKEN') = (token_reference IS NOT NULL))")
                .contains("((kind = 'CARD_TOKEN') = (brand IS NOT NULL))")
                .contains("((kind = 'CARD_TOKEN') = (expiry_month IS NOT NULL))")
                .contains("((kind = 'CARD_TOKEN') = (expiry_year IS NOT NULL))")
                .contains("((kind = 'BANK_ACCOUNT') = (destination_reference IS NOT NULL))")
                .contains("((kind = 'BANK_ACCOUNT') = (payee_check IS NOT NULL))")
                // The consent rule, NULL-safe both ways (ADR-0062 §2).
                .contains(
                        "CHECK ((payee_check IS NOT DISTINCT FROM 'NO_MATCH')")
                .contains("= (no_match_acknowledged_at IS NOT NULL))");
    }

    @Test
    @DisplayName("the kind is backfilled CARD_TOKEN and frozen NOT NULL - a birth fact")
    void kindIsBackfilledAndFrozen() {
        assertThat(migration(V003))
                .contains("UPDATE paymentmethods.payment_method SET kind = 'CARD_TOKEN';")
                .contains("ALTER COLUMN kind SET NOT NULL");
    }

    @Test
    @DisplayName("the coherence pair and the identifier-refusing bounds match the aggregates")
    void coherenceAndBoundsMatchTheAggregate() {
        assertThat(migration(V002))
                .contains("CHECK ((status = 'ACTIVE') = (detached_at IS NULL))")
                .contains("CHECK (length(token_reference) <= " + TokenReference.MAX_LENGTH + ")")
                // INV-PAY-02's DB-CONSTRAINT half: the not-a-PAN rule the domain type carries,
                // restated for every writer - the file's defining property.
                .contains("CHECK (token_reference !~ '^[0-9-]+$')")
                .contains(
                        "CHECK (expiry_year BETWEEN "
                                + PaymentMethod.MIN_EXPIRY_YEAR
                                + " AND "
                                + PaymentMethod.MAX_EXPIRY_YEAR
                                + ")");
        // INV-RAIL-03's DB-CONSTRAINT half (V003's defining property), bounds from the type.
        assertThat(migration(V003))
                .contains(
                        "CHECK (destination_reference ~ '^[A-Za-z0-9_.:-]{1,"
                                + DestinationReference.MAX_LENGTH
                                + "}$')")
                .contains("CHECK (destination_reference !~ '^[0-9_.:-]+$')")
                .contains(
                        "CHECK (NOT (char_length(destination_reference) <= "
                                + DestinationReference.MAX_INTERNATIONAL_IDENTIFIER_LENGTH)
                .contains("~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}$'))")
                // The per-kind suffix rule replaces V002's digits-only rule.
                .contains("DROP CONSTRAINT payment_method_suffix_is_last4")
                .contains("(kind = 'CARD_TOKEN' AND display_suffix ~ '^[0-9]{4}$')")
                .contains("(kind = 'BANK_ACCOUNT' AND display_suffix ~ '^[A-Za-z0-9]{4}$')");
    }

    @Test
    @DisplayName("the grants are the planned set: the detachment columns and nothing more")
    void grantsAreTheColumnNarrowedSet() {
        assertThat(migration(V002))
                .contains(
                        "GRANT SELECT, INSERT ON paymentmethods.payment_method TO finapp_app;")
                .contains(
                        "GRANT UPDATE (status, detached_at) ON paymentmethods.payment_method"
                                + " TO finapp_app;")
                .doesNotContain("GRANT UPDATE ON paymentmethods")
                .doesNotContain("GRANT DELETE");
        // V003 deliberately grants nothing: the new columns are facts, not fields.
        assertThat(migration(V003)).doesNotContain("GRANT ");
    }

    private static String migration(String path) {
        // The classloader, not a path: a module's tests see its resources inside the jar
        // java-library packs (the P2-TSK-004 finding).
        try (InputStream file =
                PaymentMethodMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(file).as("the migration %s must exist", path).isNotNull();
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
