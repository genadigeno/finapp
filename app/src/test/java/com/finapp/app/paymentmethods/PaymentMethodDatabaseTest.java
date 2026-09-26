package com.finapp.app.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.paymentmethods.DestinationReference;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.paymentmethods.PayeeCheck;
import com.finapp.paymentmethods.PaymentMethod;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodKind;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.paymentmethods.TokenReference;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The payment method's schema and store against a real PostgreSQL (`P5-TSK-004`).
 *
 * <p><strong>Self-contained, because the schema is</strong>: {@code payment_method} carries no
 * foreign key — the party is a reference by value (ADR-0029) — so the fixtures are
 * identifiers. What this suite owns is what only the database can prove: the one-live index as
 * the ten-way arbiter, the freed slot ({@code INV-LIFE-04}), the converging conditional detach
 * with its ownership predicate, the every-writer trigger — and <strong>the PAN sweep</strong>:
 * `INV-PAY-02` at {@code DB-CONSTRAINT} rank, proven per column with the column list derived
 * from {@code information_schema} so a column added later is swept without anyone remembering.
 *
 * <p>`P7-TSK-007` adds the bank kind's own batteries: the destination arbiter's ten-way race,
 * the freed destination slot, the <strong>bank-identifier sweep</strong> ({@code INV-RAIL-03}
 * at {@code DB-CONSTRAINT} rank), the per-kind coherence for every writer, and the recreated
 * trigger's NULL-safety — the {@code IS DISTINCT FROM} repair proven on the exact edit the
 * old {@code <>} form let through.
 */
@Tag("database")
@DisplayName("payment method schema and store (P5-TSK-004, P7-TSK-007)")
class PaymentMethodDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** The shapes a card number is written in — what the sweep plants into every text column. */
    private static final List<String> PAN_SHAPES =
            List.of("4111111111111111", "4111-1111-1111-1111", "378282246310005");

    private final PaymentMethodStore<Connection> store = new JdbcPaymentMethodStore();

    @Test
    @DisplayName("ten concurrent attaches of one instrument produce one live row, nine converged")
    void tenConcurrentAttachesProduceOneLiveRow() throws Exception {
        UUID party = UUID.randomUUID();
        TokenReference token = TokenReference.of("tok_race-" + UUID.randomUUID());

        List<Callable<PaymentMethodStore.Attachment>> attaches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attaches.add(
                    () -> {
                        // Own connection per simulated instance (P0-TST-009).
                        try (Connection app = DatabaseRoles.application()) {
                            app.setAutoCommit(false);
                            PaymentMethodStore.Attachment attachment =
                                    store.attachOrConverge(
                                            app,
                                            PaymentMethod.attachCard(
                                                    PaymentMethodId.next(IDS),
                                                    party,
                                                    token,
                                                    "Visa",
                                                    "4242",
                                                    12,
                                                    2030,
                                                    CLOCK));
                            app.commit();
                            return attachment;
                        }
                    });
        }

        ExecutorService racers = Executors.newFixedThreadPool(10);
        List<PaymentMethodStore.Attachment> outcomes = new ArrayList<>();
        try {
            for (Future<PaymentMethodStore.Attachment> outcome : racers.invokeAll(attaches)) {
                outcomes.add(outcome.get());
            }
        } finally {
            racers.shutdown();
        }

        // Counted in the table, never inferred from return values.
        assertThat(rowsFor(party)).hasSize(1);
        assertThat(outcomes.stream().filter(PaymentMethodStore.Attachment::created)).hasSize(1);
        UUID winner =
                outcomes.stream()
                        .filter(PaymentMethodStore.Attachment::created)
                        .findFirst()
                        .orElseThrow()
                        .method()
                        .id()
                        .value();
        assertThat(outcomes)
                .as("the nine losers converge onto the winner's row, not an error")
                .allSatisfy(
                        outcome -> assertThat(outcome.method().id().value()).isEqualTo(winner));
    }

    @Test
    @DisplayName("a detached slot is re-attachable, and the detached row survives as evidence")
    void aDetachedSlotIsReattachable() throws Exception {
        UUID party = UUID.randomUUID();
        TokenReference token = TokenReference.of("tok_slot-" + UUID.randomUUID());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);

            PaymentMethod first = attach(app, party, token);
            assertThat(store.detach(app, first.id(), party, Instant.now(CLOCK))).isTrue();
            app.commit();

            // INV-LIFE-04's freed-slot asymmetry: detaching frees the (party, token) slot, so
            // attaching the same instrument again is a NEW aggregate - not a resurrection.
            PaymentMethodStore.Attachment second =
                    store.attachOrConverge(
                            app,
                            PaymentMethod.attachCard(
                                    PaymentMethodId.next(IDS),
                                    party,
                                    token,
                                    "Visa",
                                    "4242",
                                    12,
                                    2031,
                                    CLOCK));
            app.commit();

            assertThat(second.created()).isTrue();
            assertThat(second.method().id()).isNotEqualTo(first.id());
            // Two rows counted in the table: the evidence survives, exactly one live.
            assertThat(rowsFor(party))
                    .containsExactlyInAnyOrder(
                            PaymentMethodStatus.DETACHED.name(),
                            PaymentMethodStatus.ACTIVE.name());
        }
    }

    @Test
    @DisplayName("detachment converges retries and carries the ownership predicate")
    void detachmentConvergesAndIsOwnershipScoped() throws Exception {
        UUID party = UUID.randomUUID();
        TokenReference token = TokenReference.of("tok_own-" + UUID.randomUUID());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PaymentMethod saved = attach(app, party, token);
            app.commit();

            // A stranger's attempt matches nothing: party_id = ? is the ownership check, in
            // the statement (ADR-0031) - and "not yours" is indistinguishable from "already
            // detached", the one-404 shape prepared at the port for P5-TSK-005.
            assertThat(store.detach(app, saved.id(), UUID.randomUUID(), Instant.now(CLOCK)))
                    .isFalse();
            app.commit();
            assertThat(statusOf(saved.id().value())).isEqualTo("ACTIVE");

            // Truncated to the column's own microsecond resolution: the driver ROUNDS
            // nanoseconds (the P3-TSK-005 finding), and this assertion compares instants.
            Instant detachedAt =
                    Instant.now(CLOCK).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertThat(store.detach(app, saved.id(), party, detachedAt)).isTrue();
            app.commit();

            // The retry converges on false, and the recorded instant is the first detach's -
            // the conditional's row count is the outcome, so nothing is written twice.
            assertThat(store.detach(app, saved.id(), party, detachedAt.plusSeconds(60)))
                    .isFalse();
            app.commit();
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT detached_at FROM paymentmethods.payment_method"
                                    + " WHERE id = ?")) {
                read.setObject(1, saved.id().value());
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getTimestamp(1).toInstant()).isEqualTo(detachedAt);
                }
            }
        }
    }

    @Test
    @DisplayName("raw SQL cannot resurrect, edit or incoherently store a payment method")
    void rawSqlCannotResurrectOrEditARow() throws Exception {
        UUID party = UUID.randomUUID();
        UUID detachedRow;
        UUID liveRow;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PaymentMethod saved =
                    attach(app, party, TokenReference.of("tok_frozen-" + UUID.randomUUID()));
            store.detach(app, saved.id(), party, Instant.now(CLOCK));
            liveRow =
                    attach(app, party, TokenReference.of("tok_live-" + UUID.randomUUID()))
                            .id()
                            .value();
            app.commit();
            detachedRow = saved.id().value();
        }

        // As the MIGRATOR - the writer the grants do not bind. The trigger is the control
        // (INV-LIFE-04 at DB-CONSTRAINT rank, the beneficiary V003 shape).
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                refusedUpdate(
                        migrator,
                        "a DETACHED payment method cannot be resurrected by any writer",
                        "UPDATE paymentmethods.payment_method"
                                + " SET status = 'ACTIVE', detached_at = NULL WHERE id = ?",
                        detachedRow,
                        RAISED);
                // The probe that isolates the FROZEN half: an edit smuggled inside the legal
                // detach edge. A status-preserving edit is refused by the edge check too, so
                // only this shape proves the freeze is load-bearing on its own (P4-TSK-006's
                // probe design).
                refusedUpdate(
                        migrator,
                        "the token is immutable even on the legal edge:"
                                + " updating is detach-and-reattach",
                        "UPDATE paymentmethods.payment_method SET status = 'DETACHED',"
                                + " detached_at = created_at, token_reference = 'tok_edited'"
                                + " WHERE id = ?",
                        liveRow,
                        RAISED);

                // The coherence pair, from scratch: a live row carrying a detachment instant
                // and an unknown status are each refused at CHECK rank for every writer.
                refusedInsert(
                        migrator,
                        "an ACTIVE row carrying a detachment instant is unsplittable",
                        "tok_probe-a1",
                        "ACTIVE",
                        true,
                        CHECK_VIOLATION);
                // With a detachment instant, so the coherence pair is satisfied and the
                // refusal is isolated to the status CHECK (the P0-TSK-031 isolation lesson).
                refusedInsert(
                        migrator,
                        "an unknown status is refused",
                        "tok_probe-a2",
                        "SUSPENDED",
                        true,
                        CHECK_VIOLATION);
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("a PAN cannot be stored in any column, swept from information_schema")
    void aPanCannotBeStoredInAnyColumn() throws Exception {
        // INV-PAY-02 at DB-CONSTRAINT rank, per column: every text column refuses PAN-shaped
        // values by its own CHECK, and the non-text columns are structurally unable (uuid,
        // integer-with-range, timestamptz). Derived from information_schema, so a column added
        // later is swept - and a text column added without a PAN-refusing shape FAILS here,
        // which is the point: the sweep is the reviewer that never forgets.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                List<String[]> columns = columnsWithTypes(migrator);
                assertThat(columns).as("the derived sweep has subjects").isNotEmpty();
                for (String[] column : columns) {
                    String name = column[0];
                    String type = column[1];
                    if (!"text".equals(type)) {
                        // uuid, integer (bounded 1-12 / 2000-2100) and timestamptz cannot hold
                        // a 13-19 digit value; integers are additionally range-CHECKed above
                        // any PAN's numeric value... except expiry_year, whose 2000-2100 bound
                        // is what refuses it - both proven by the expiry probes in
                        // PaymentMethodTest and the range CHECKs exercised below.
                        continue;
                    }
                    for (String pan : PAN_SHAPES) {
                        refusedPanPlant(migrator, name, pan);
                    }
                }
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("the grants are the detachment columns and nothing more, swept per column")
    void theGrantsAreTheNarrowedSet() throws Exception {
        UUID party = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                PaymentMethod saved =
                        attach(app, party, TokenReference.of("tok_grant-" + UUID.randomUUID()));

                // Derived from information_schema (the P0-TST-007 idiom), so a column added
                // later is swept without anyone remembering. The granted pair is exercised as
                // the positive control by every detach in this suite.
                List<String> frozen = new ArrayList<>();
                for (String[] column : columnsWithTypes(app)) {
                    frozen.add(column[0]);
                }
                frozen.removeAll(List.of("status", "detached_at"));
                assertThat(frozen).as("the derived sweep has subjects").isNotEmpty();
                for (String column : frozen) {
                    Savepoint before = app.setSavepoint();
                    assertThatThrownBy(
                                    () -> {
                                        try (PreparedStatement update =
                                                app.prepareStatement(
                                                        "UPDATE paymentmethods.payment_method"
                                                                + " SET " + column + " = "
                                                                + column + " WHERE id = ?")) {
                                            update.setObject(1, saved.id().value());
                                            update.executeUpdate();
                                        }
                                    })
                            .as("the application role must hold no UPDATE on %s", column)
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback(before);
                }

                Savepoint before = app.setSavepoint();
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement delete =
                                            app.prepareStatement(
                                                    "DELETE FROM"
                                                            + " paymentmethods.payment_method"
                                                            + " WHERE id = ?")) {
                                        delete.setObject(1, saved.id().value());
                                        delete.executeUpdate();
                                    }
                                })
                        .as("a payment method's end is a status, never an absence")
                        .isInstanceOf(SQLException.class)
                        .extracting(f -> ((SQLException) f).getSQLState())
                        .isEqualTo(INSUFFICIENT_PRIVILEGE);
                app.rollback(before);
            } finally {
                app.rollback();
            }
        }
    }

    // ------------------------------------------------------------------
    // P7-TSK-007: the bank account kind (INV-RAIL-03 at DB-CONSTRAINT rank).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ten concurrent registers of one destination produce one live row (P7-TSK-007)")
    void tenConcurrentBankRegistersProduceOneLiveRow() throws Exception {
        UUID party = UUID.randomUUID();
        DestinationReference destination =
                DestinationReference.of("dest-race-" + UUID.randomUUID());

        List<Callable<PaymentMethodStore.Attachment>> registers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            registers.add(
                    () -> {
                        try (Connection app = DatabaseRoles.application()) {
                            app.setAutoCommit(false);
                            PaymentMethodStore.Attachment attachment =
                                    store.attachOrConverge(
                                            app, bank(party, destination, PayeeCheck.MATCH));
                            app.commit();
                            return attachment;
                        }
                    });
        }
        ExecutorService racers = Executors.newFixedThreadPool(10);
        List<PaymentMethodStore.Attachment> outcomes = new ArrayList<>();
        try {
            for (Future<PaymentMethodStore.Attachment> outcome : racers.invokeAll(registers)) {
                outcomes.add(outcome.get());
            }
        } finally {
            racers.shutdown();
        }
        assertThat(rowsFor(party)).hasSize(1);
        assertThat(outcomes.stream().filter(PaymentMethodStore.Attachment::created)).hasSize(1);
        UUID winner =
                outcomes.stream()
                        .filter(PaymentMethodStore.Attachment::created)
                        .findFirst()
                        .orElseThrow()
                        .method()
                        .id()
                        .value();
        assertThat(outcomes)
                .as("the nine losers converge onto the winner's bank row")
                .allSatisfy(
                        outcome -> assertThat(outcome.method().id().value()).isEqualTo(winner));
    }

    @Test
    @DisplayName("a bank row round-trips beside a card and its detached slot frees (P7-TSK-007)")
    void bankRowRoundTripsAndFreesItsSlot() throws Exception {
        UUID party = UUID.randomUUID();
        DestinationReference destination =
                DestinationReference.of("dest-slot-" + UUID.randomUUID());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            attach(app, party, TokenReference.of("tok_beside-" + UUID.randomUUID()));
            PaymentMethodStore.Attachment first =
                    store.attachOrConverge(
                            app, bank(party, destination, PayeeCheck.NO_MATCH));
            app.commit();
            assertThat(first.created()).isTrue();

            // The rehydrate arm, proven on the stored row: kinds, payee word and the consent
            // instant come back exactly, and the card beside it keeps its own facts.
            List<PaymentMethod> live = store.listLiveFor(app, party);
            assertThat(live).hasSize(2);
            PaymentMethod bankRow =
                    live.stream()
                            .filter(m -> m.kind() == PaymentMethodKind.BANK_ACCOUNT)
                            .findFirst()
                            .orElseThrow();
            assertThat(bankRow.payeeCheck()).contains(PayeeCheck.NO_MATCH);
            assertThat(bankRow.noMatchAcknowledgedAt()).isPresent();
            assertThat(bankRow.token()).isEmpty();
            assertThat(bankRow.destination().orElseThrow().expose())
                    .isEqualTo(destination.expose());

            // INV-LIFE-04's freed-slot asymmetry, at the destination arbiter.
            assertThat(store.detach(app, first.method().id(), party, Instant.now(CLOCK)))
                    .isTrue();
            app.commit();
            PaymentMethodStore.Attachment second =
                    store.attachOrConverge(app, bank(party, destination, PayeeCheck.MATCH));
            app.commit();
            assertThat(second.created()).isTrue();
            assertThat(second.method().id()).isNotEqualTo(first.method().id());
        }
    }

    @Test
    @DisplayName("bank identifier shapes cannot be stored in the destination (INV-RAIL-03)")
    void bankIdentifierShapesCannotBeStored() throws Exception {
        // The three refusals on an otherwise-coherent BANK row, as the MIGRATOR - the shape
        // rules bind every writer, not the domain type's callers. An account number, a
        // sort-coded string and a phone have no letter; the IBAN is the identifier shape.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                for (String identifierShaped :
                        new String[] {
                            "12345678",
                            "12-34-56:12345678",
                            "447911123456",
                            "GB29NWBK60161331926819",
                            "DE89370400440532013000"
                        }) {
                    refusedBankInsert(
                            migrator,
                            "the destination must refuse " + identifierShaped,
                            identifierShaped,
                            "MATCH",
                            null,
                            CHECK_VIOLATION);
                }
                // And the shape rules' recorded limit holds in the schema exactly as in the
                // type: past an IBAN's own 34-character bound the reference is lawful.
                Savepoint before = migrator.setSavepoint();
                insertBankRow(
                        migrator, "GB29" + "a".repeat(31), "MATCH", null, "ACTIVE", null);
                migrator.rollback(before);
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("the per-kind coherence binds every writer (P7-TSK-007)")
    void kindCoherenceBindsEveryWriter() throws Exception {
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                // A NO_MATCH without its acknowledgement instant - the consent CHECK.
                refusedBankInsert(
                        migrator,
                        "an unacknowledged NO_MATCH instrument cannot exist",
                        "dest-consent-" + UUID.randomUUID(),
                        "NO_MATCH",
                        null,
                        CHECK_VIOLATION);
                // An acknowledgement riding a MATCH - the same CHECK's other direction.
                refusedBankInsert(
                        migrator,
                        "consent to a mismatch that did not happen is not a fact",
                        "dest-consent-" + UUID.randomUUID(),
                        "MATCH",
                        Instant.now(CLOCK),
                        CHECK_VIOLATION);
                // An unknown payee word.
                refusedBankInsert(
                        migrator,
                        "an unknown payee word is refused",
                        "dest-word-" + UUID.randomUUID(),
                        "PARTIAL",
                        null,
                        CHECK_VIOLATION);
                // A bank row missing its own facts (no destination).
                refusedRawInsert(
                        migrator,
                        "a bank row without its destination is incoherent",
                        "BANK_ACCOUNT",
                        null,
                        null,
                        "6819",
                        null,
                        null,
                        null,
                        "MATCH",
                        null,
                        CHECK_VIOLATION);
                // A card row dressed in bank facts.
                refusedRawInsert(
                        migrator,
                        "a card row cannot carry a destination",
                        "CARD_TOKEN",
                        "tok_dressed-" + UUID.randomUUID(),
                        "Visa",
                        "4242",
                        12,
                        2030,
                        "dest-dressed-x1",
                        "MATCH",
                        null,
                        CHECK_VIOLATION);
                // An unknown kind.
                refusedRawInsert(
                        migrator,
                        "an unknown kind is refused",
                        "WALLET",
                        "tok_kind-" + UUID.randomUUID(),
                        "Visa",
                        "4242",
                        12,
                        2030,
                        null,
                        null,
                        null,
                        CHECK_VIOLATION);
                // The per-kind suffix: alphanumerics are the BANK kind's licence, not the
                // card's; five characters are nobody's.
                refusedRawInsert(
                        migrator,
                        "a card suffix must stay four digits",
                        "CARD_TOKEN",
                        "tok_suffix-" + UUID.randomUUID(),
                        "Visa",
                        "4a42",
                        12,
                        2030,
                        null,
                        null,
                        null,
                        CHECK_VIOLATION);
                refusedBankInsert(
                        migrator,
                        "a five-character bank suffix is refused",
                        "dest-suffix-" + UUID.randomUUID(),
                        "MATCH",
                        null,
                        CHECK_VIOLATION,
                        "68195");
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("the recreated trigger is NULL-safe: a bank row's NULL card facts are frozen")
    void triggerFreezesNullColumnsToo() throws Exception {
        // THE P7-TSK-007 REPAIR'S PROOF: V002's OLD.col <> NEW.col was NULL-blind, so on a
        // bank row an edit of brand (NULL -> 'Visa') smuggled inside the legal detach edge
        // would have sailed through. IS DISTINCT FROM catches it - for every writer.
        UUID party = UUID.randomUUID();
        UUID bankRow;
        UUID cardRow;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PaymentMethodStore.Attachment registered =
                    store.attachOrConverge(
                            app,
                            bank(
                                    party,
                                    DestinationReference.of("dest-frozen-" + UUID.randomUUID()),
                                    PayeeCheck.MATCH));
            bankRow = registered.method().id().value();
            cardRow =
                    attach(app, party, TokenReference.of("tok_nullsafe-" + UUID.randomUUID()))
                            .id()
                            .value();
            app.commit();
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                refusedUpdate(
                        migrator,
                        "a bank row's NULL brand cannot be edited to a value, even on the"
                                + " legal edge (the IS DISTINCT FROM repair)",
                        "UPDATE paymentmethods.payment_method SET status = 'DETACHED',"
                                + " detached_at = created_at, brand = 'Visa' WHERE id = ?",
                        bankRow,
                        RAISED);
                refusedUpdate(
                        migrator,
                        "a card row's NULL destination cannot be edited to a value",
                        "UPDATE paymentmethods.payment_method SET status = 'DETACHED',"
                                + " detached_at = created_at,"
                                + " destination_reference = 'dest-smuggled-x1' WHERE id = ?",
                        cardRow,
                        RAISED);
                refusedUpdate(
                        migrator,
                        "the kind is a frozen birth fact",
                        "UPDATE paymentmethods.payment_method SET status = 'DETACHED',"
                                + " detached_at = created_at, kind = 'CARD_TOKEN',"
                                + " token_reference = 'tok_rekinded', brand = 'Visa',"
                                + " expiry_month = 12, expiry_year = 2030,"
                                + " destination_reference = NULL, payee_check = NULL"
                                + " WHERE id = ?",
                        bankRow,
                        RAISED);
            } finally {
                migrator.rollback();
            }
        }
    }

    // -----------------------------------------------------------------

    private static PaymentMethod bank(
            UUID party, DestinationReference destination, PayeeCheck check) {
        return PaymentMethod.registerBankAccount(
                PaymentMethodId.next(IDS), party, destination, "6819", check, true, CLOCK);
    }

    /** A coherent BANK row with one field bent — the refusal isolated to the shape or
     * consent constraint under test (the P0-TSK-031 isolation lesson). */
    private void refusedBankInsert(
            Connection connection,
            String what,
            String destination,
            String payeeCheck,
            Instant acknowledgedAt,
            String sqlState)
            throws SQLException {
        refusedBankInsert(
                connection, what, destination, payeeCheck, acknowledgedAt, sqlState, "6819");
    }

    private void refusedBankInsert(
            Connection connection,
            String what,
            String destination,
            String payeeCheck,
            Instant acknowledgedAt,
            String sqlState,
            String suffix)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () ->
                                insertBankRow(
                                        connection,
                                        destination,
                                        payeeCheck,
                                        acknowledgedAt,
                                        "ACTIVE",
                                        suffix))
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        connection.rollback(before);
    }

    private void insertBankRow(
            Connection connection,
            String destination,
            String payeeCheck,
            Instant acknowledgedAt,
            String status,
            String suffix)
            throws SQLException {
        rawInsert(
                connection,
                "BANK_ACCOUNT",
                null,
                null,
                suffix == null ? "6819" : suffix,
                null,
                null,
                destination,
                payeeCheck,
                acknowledgedAt,
                status,
                null);
    }

    private void refusedRawInsert(
            Connection connection,
            String what,
            String kind,
            String token,
            String brand,
            String suffix,
            Integer expiryMonth,
            Integer expiryYear,
            String destination,
            String payeeCheck,
            Instant acknowledgedAt,
            String sqlState)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () ->
                                rawInsert(
                                        connection,
                                        kind,
                                        token,
                                        brand,
                                        suffix,
                                        expiryMonth,
                                        expiryYear,
                                        destination,
                                        payeeCheck,
                                        acknowledgedAt,
                                        "ACTIVE",
                                        null))
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        connection.rollback(before);
    }

    private void rawInsert(
            Connection connection,
            String kind,
            String token,
            String brand,
            String suffix,
            Integer expiryMonth,
            Integer expiryYear,
            String destination,
            String payeeCheck,
            Instant acknowledgedAt,
            String status,
            Instant detachedAt)
            throws SQLException {
        Instant now = Instant.now(CLOCK);
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO paymentmethods.payment_method"
                                + " (id, party_id, kind, token_reference, brand,"
                                + " display_suffix, expiry_month, expiry_year,"
                                + " destination_reference, payee_check,"
                                + " no_match_acknowledged_at, status, created_at, detached_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, UUID.randomUUID());
            insert.setString(3, kind);
            insert.setString(4, token);
            insert.setString(5, brand);
            insert.setString(6, suffix);
            if (expiryMonth == null) {
                insert.setNull(7, java.sql.Types.INTEGER);
            } else {
                insert.setInt(7, expiryMonth);
            }
            if (expiryYear == null) {
                insert.setNull(8, java.sql.Types.INTEGER);
            } else {
                insert.setInt(8, expiryYear);
            }
            insert.setString(9, destination);
            insert.setString(10, payeeCheck);
            insert.setTimestamp(
                    11, acknowledgedAt == null ? null : Timestamp.from(acknowledgedAt));
            insert.setString(12, status);
            insert.setTimestamp(13, Timestamp.from(now));
            insert.setTimestamp(14, detachedAt == null ? null : Timestamp.from(detachedAt));
            insert.executeUpdate();
        }
    }

    private PaymentMethod attach(Connection app, UUID party, TokenReference token) {
        PaymentMethodStore.Attachment attachment =
                store.attachOrConverge(
                        app,
                        PaymentMethod.attachCard(
                                PaymentMethodId.next(IDS),
                                party,
                                token,
                                "Visa",
                                "4242",
                                12,
                                2030,
                                CLOCK));
        assertThat(attachment.created()).isTrue();
        return attachment.method();
    }

    /**
     * Plants a PAN shape into one text column of an otherwise-legal row and requires the
     * refusal — each column's own {@code CHECK} is what makes {@code INV-PAY-02} physical.
     */
    private static void refusedPanPlant(Connection connection, String column, String pan)
            throws SQLException {
        Instant now = Instant.now(CLOCK);
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement insert =
                                    connection.prepareStatement(
                                            "INSERT INTO paymentmethods.payment_method"
                                                + " (id, party_id, kind, token_reference,"
                                                + " brand, display_suffix, expiry_month,"
                                                + " expiry_year, destination_reference,"
                                                + " payee_check, status, created_at,"
                                                + " detached_at)"
                                                + " VALUES"
                                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                insert.setObject(1, IDS.next());
                                insert.setObject(2, UUID.randomUUID());
                                insert.setString(3, "kind".equals(column) ? pan : "CARD_TOKEN");
                                insert.setString(
                                        4,
                                        "token_reference".equals(column)
                                                ? pan
                                                : "tok_sweep-" + UUID.randomUUID());
                                insert.setString(5, "brand".equals(column) ? pan : "Visa");
                                insert.setString(
                                        6, "display_suffix".equals(column) ? pan : "4242");
                                insert.setInt(7, 12);
                                insert.setInt(8, 2030);
                                // A PAN planted into a bank column of a card row is refused
                                // by the per-kind coherence CHECK - still 23514, still
                                // physically unstorable; the bank row's own shape sweep is
                                // bankIdentifierShapesCannotBeStored.
                                insert.setString(
                                        9, "destination_reference".equals(column) ? pan : null);
                                insert.setString(10, "payee_check".equals(column) ? pan : null);
                                insert.setString(11, "status".equals(column) ? pan : "ACTIVE");
                                insert.setTimestamp(12, Timestamp.from(now));
                                insert.setTimestamp(13, null);
                                insert.executeUpdate();
                            }
                        })
                .as("column %s must refuse the PAN shape %s", column, pan)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        connection.rollback(before);
    }

    private static void refusedUpdate(
            Connection connection, String what, String statement, UUID id, String sqlState)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement update =
                                    connection.prepareStatement(statement)) {
                                update.setObject(1, id);
                                update.executeUpdate();
                            }
                        })
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        connection.rollback(before);
    }

    private void refusedInsert(
            Connection connection,
            String what,
            String token,
            String status,
            boolean withDetachmentInstant,
            String sqlState)
            throws SQLException {
        // One instant for both columns, so detachment-follows-creation is satisfied and the
        // refusal is isolated to the constraint under test.
        Instant now = Instant.now(CLOCK);
        Instant detachedAt = withDetachmentInstant ? now : null;
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement insert =
                                    connection.prepareStatement(
                                            "INSERT INTO paymentmethods.payment_method"
                                                + " (id, party_id, kind, token_reference,"
                                                + " brand, display_suffix, expiry_month,"
                                                + " expiry_year, status, created_at,"
                                                + " detached_at)"
                                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                insert.setObject(1, IDS.next());
                                insert.setObject(2, UUID.randomUUID());
                                insert.setString(3, "CARD_TOKEN");
                                insert.setString(4, token);
                                insert.setString(5, "Visa");
                                insert.setString(6, "4242");
                                insert.setInt(7, 12);
                                insert.setInt(8, 2030);
                                insert.setString(9, status);
                                insert.setTimestamp(10, Timestamp.from(now));
                                insert.setTimestamp(
                                        11,
                                        detachedAt == null ? null : Timestamp.from(detachedAt));
                                insert.executeUpdate();
                            }
                        })
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        connection.rollback(before);
    }

    private static List<String> rowsFor(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM paymentmethods.payment_method"
                                        + " WHERE party_id = ?")) {
            read.setObject(1, party);
            try (ResultSet rows = read.executeQuery()) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString(1));
                }
                return statuses;
            }
        }
    }

    private static String statusOf(UUID id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM paymentmethods.payment_method"
                                        + " WHERE id = ?")) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static List<String[]> columnsWithTypes(Connection connection) throws SQLException {
        List<String[]> columns = new ArrayList<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns"
                                + " WHERE table_schema = 'paymentmethods'"
                                + " AND table_name = 'payment_method'"
                                + " ORDER BY ordinal_position")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(new String[] {rows.getString(1), rows.getString(2)});
                }
            }
        }
        return columns;
    }
}
