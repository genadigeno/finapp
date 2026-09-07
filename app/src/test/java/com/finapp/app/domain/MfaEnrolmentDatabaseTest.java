package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcMfaEnrolmentStore;
import com.finapp.identity.MfaEnrolment;
import com.finapp.identity.MfaEnrolmentService;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorStatus;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.SecretCipher;
import com.finapp.identity.TotpParameters;
import com.finapp.identity.TotpVerifier;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MFA enrolment against a real database (`P1-TSK-017`, {@code INV-IDN-05}, {@code INV-IDN-08}).
 *
 * <h2>The acceptance criterion is that a partial enrolment changes nothing</h2>
 *
 * <p>Starting an enrolment must not produce a factor. If it did, reaching this endpoint on somebody
 * else's session would be enough to attach a second factor to their account — which is not a
 * hardening but an attack.
 */
@Tag("database")
@DisplayName("MFA enrolment (P1-TSK-017)")
class MfaEnrolmentDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    /** A fixed key, so a decryption failure here is never "the key changed between runs". */
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) i;
        }
    }

    private final MfaEnrolmentStore<Connection> enrolments = new JdbcMfaEnrolmentStore();
    private final SecretCipher cipher = new SecretCipher(KEY, 1, RANDOMNESS);

    // -----------------------------------------------------------------
    // The acceptance criterion

    @Test
    @DisplayName("a started enrolment is PENDING and satisfies nothing")
    void aStartedEnrolmentIsNotUsable() throws Exception {
        IdentityId identity = givenAnIdentity();

        inAFlow(app -> service().begin(app, identity, AssuranceLevel.PASSWORD).orElseThrow());

        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findPending(app, identity, MfaFactorType.TOTP))
                    .as("the enrolment exists")
                    .isPresent();

            // The criterion. `findActive` is what a challenge will use, and it returns nothing -
            // because `status = 'ACTIVE'` is in the statement rather than checked afterwards.
            assertThat(enrolments.findActive(app, identity, MfaFactorType.TOTP))
                    .as("partial enrolment leaves assurance unchanged: there is no usable factor")
                    .isEmpty();
            assertThat(enrolments.findPending(app, identity, MfaFactorType.TOTP).orElseThrow()
                            .isUsable())
                    .as("and the aggregate agrees with the query about what usable means")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a correct code confirms it, and only then is it usable")
    void aCorrectCodeConfirms() throws Exception {
        IdentityId identity = givenAnIdentity();
        Sensitive<String> secret = beginAndCaptureSecret(identity);

        boolean[] confirmed = {false};
        inAFlow(app -> confirmed[0] = service().confirm(app, identity, codeFor(secret)));

        assertThat(confirmed[0]).isTrue();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findActive(app, identity, MfaFactorType.TOTP))
                    .as("only a proven code makes a factor usable")
                    .isPresent()
                    .get()
                    .satisfies(active -> assertThat(active.isUsable()).isTrue());
        }
    }

    @Test
    @DisplayName("a wrong code leaves it PENDING, so assurance is still unchanged")
    void aWrongCodeChangesNothing() throws Exception {
        IdentityId identity = givenAnIdentity();
        beginAndCaptureSecret(identity);

        boolean[] confirmed = {true};
        inAFlow(app -> confirmed[0] = service().confirm(app, identity, "000000"));

        assertThat(confirmed[0]).isFalse();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findActive(app, identity, MfaFactorType.TOTP)).isEmpty();
            assertThat(enrolments.findPending(app, identity, MfaFactorType.TOTP))
                    .as("and it stays available to confirm: a wrong code is not a lockout")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("confirming twice with the same code does nothing the second time")
    void aReplayedCodeConfirmsNothing() throws Exception {
        IdentityId identity = givenAnIdentity();
        Sensitive<String> secret = beginAndCaptureSecret(identity);
        String code = codeFor(secret);

        boolean[] outcomes = {false, true};
        inAFlow(app -> outcomes[0] = service().confirm(app, identity, code));
        inAFlow(app -> outcomes[1] = service().confirm(app, identity, code));

        // The state machine is the replay defence on this path: the second attempt finds no PENDING
        // enrolment. Challenge replay is P1-TSK-018's, and needs a different mechanism because a
        // challenge has no state to consume.
        assertThat(outcomes[0]).isTrue();
        assertThat(outcomes[1]).isFalse();
    }

    @Test
    @DisplayName("ten instances confirming one enrolment produce one confirmation and one event")
    void oneConfirmationUnderContention() throws Exception {
        IdentityId identity = givenAnIdentity();
        Sensitive<String> secret = beginAndCaptureSecret(identity);
        String code = codeFor(secret);

        // Added because a mutation SURVIVED: removing `AND status = 'PENDING'` from the store's
        // confirm changed nothing sequentially, since findPending already returns empty the second
        // time. The conditional is load-bearing only under CONCURRENCY - ten instances that all
        // read PENDING, all verify the same valid code, and all try to confirm. Without it, one
        // enrolment produces ten audit records and ten events.
        long auditBefore = auditCount(identity);
        long outboxBefore = outboxCount(identity);
        int instances = 10;
        var ready = new java.util.concurrent.CountDownLatch(instances);
        var go = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(instances);

        try {
            var outcomes = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < instances; i++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await();
                                    // Own connection each - the P0-TST-009 convention.
                                    boolean[] confirmed = {false};
                                    inAFlow(app -> confirmed[0] =
                                            service().confirm(app, identity, code));
                                    return confirmed[0];
                                }));
            }
            assertThat(ready.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();

            long winners = 0;
            for (var outcome : outcomes) {
                try {
                    if (outcome.get(60, java.util.concurrent.TimeUnit.SECONDS)) {
                        winners++;
                    }
                } catch (java.util.concurrent.ExecutionException losing) {
                    // A loser may collide with the partial unique index instead of being told it
                    // lost. That is still exactly one winner, which is the property under test.
                }
            }

            assertThat(winners)
                    .as("the conditional UPDATE's row count is the outcome: one confirmation")
                    .isEqualTo(1);
            assertThat(auditCount(identity) - auditBefore)
                    .as("one audit record, not ten: nine records for a confirmation that did not"
                            + " happen would put decisions nobody made into the trail")
                    .isEqualTo(1);
            assertThat(outboxCount(identity) - outboxBefore)
                    .as("and one announcement - an event cannot be retracted once consumed")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // The secret

    @Test
    @DisplayName("the secret appears in no column of the stored row")
    void theSecretIsNotStoredInTheClear() throws Exception {
        IdentityId identity = givenAnIdentity();
        Sensitive<String> secret = beginAndCaptureSecret(identity);

        // Every column, with the list derived from information_schema - the P1-TSK-007 idiom. The
        // obvious version of this test checks the column its author was thinking of, and passes
        // against an implementation that also wrote the secret somewhere else.
        try (Connection app = DatabaseRoles.application()) {
            for (String column : columnsOf(app, "mfa_enrolment")) {
                try (PreparedStatement read =
                        app.prepareStatement(
                                "SELECT " + column + "::text FROM identity.mfa_enrolment"
                                        + " WHERE identity_id = ?")) {
                    read.setObject(1, identity.value());
                    try (var rows = read.executeQuery()) {
                        while (rows.next()) {
                            String value = rows.getString(1);
                            assertThat(value == null ? "" : value)
                                    .as("column %s must not contain the secret", column)
                                    .doesNotContain(secret.expose());
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a tampered ciphertext fails rather than decrypting to something else")
    void aTamperedCiphertextIsRefused() throws Exception {
        IdentityId identity = givenAnIdentity();
        beginAndCaptureSecret(identity);

        // The authentication half of GCM, and it is why the mode was chosen. Without it, an
        // attacker with WRITE access to this column could substitute a secret they control - a
        // second factor that authenticates the attacker, silently.
        try (Connection app = DatabaseRoles.application();
                PreparedStatement tamper =
                        app.prepareStatement(
                                "UPDATE identity.mfa_enrolment"
                                        + " SET secret_ciphertext = overlay(secret_ciphertext"
                                        + " placing '\\x00'::bytea from 1 for 1)"
                                        + " WHERE identity_id = ?")) {
            tamper.setObject(1, identity.value());
            tamper.executeUpdate();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> inAFlow(app -> service().confirm(app, identity, "123456")))
                .as("a substituted secret must be unusable, never silently different")
                .isInstanceOf(IllegalStateException.class)
                // No cause, and that is deliberate rather than incidental: SecretCipher drops the
                // provider exception so it cannot carry key material into a log (INV-AUD-02, the
                // P1-TSK-008 DatabaseFailure precedent). The first version of this assertion looked
                // for a root cause and failed for that reason.
                .hasNoCause()
                .hasMessageNotContaining("key");
    }

    @Test
    @DisplayName("a different key cannot read the secret")
    void anotherKeyCannotDecrypt() throws Exception {
        IdentityId identity = givenAnIdentity();
        beginAndCaptureSecret(identity);

        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 9);
        SecretCipher wrong = new SecretCipher(otherKey, 2, RANDOMNESS);

        MfaEnrolment stored;
        try (Connection app = DatabaseRoles.application()) {
            stored = enrolments.findPending(app, identity, MfaFactorType.TOTP).orElseThrow();
        }

        // INV-IDN-08's actual claim: a database leak alone does not yield the secret. This is that
        // claim, with the database in hand and the key absent.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> wrong.decrypt(stored.encryptedSecret()))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------
    // Re-enrolment

    @Test
    @DisplayName("starting again discards the pending enrolment and issues a different secret")
    void startingAgainReplacesThePendingEnrolment() throws Exception {
        IdentityId identity = givenAnIdentity();
        Sensitive<String> first = beginAndCaptureSecret(identity);
        Sensitive<String> second = beginAndCaptureSecret(identity);

        assertThat(second.expose()).isNotEqualTo(first.expose());

        try (Connection app = DatabaseRoles.application()) {
            // The row is MARKED, not deleted: the application role holds no DELETE, and an
            // abandoned enrolment is evidence somebody began adding a factor.
            assertThat(statusCount(app, identity, MfaFactorStatus.DISCARDED)).isEqualTo(1);
            assertThat(statusCount(app, identity, MfaFactorStatus.PENDING)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("starting a new enrolment never disturbs a confirmed factor")
    void anActiveFactorSurvivesANewEnrolment() throws Exception {
        IdentityId identity = givenAnIdentity();
        Sensitive<String> secret = beginAndCaptureSecret(identity);
        inAFlow(app -> service().confirm(app, identity, codeFor(secret)));

        // The rule changed under this test, and the new one is STRICTER. `P1-TSK-019` found that a
        // PASSWORD session could begin a replacement enrolment with an attacker-controlled secret;
        // it is now refused outright, so the factor is not merely undisturbed - the operation does
        // not happen.
        boolean[] refused = {true};
        inAFlow(
                app ->
                        refused[0] =
                                service()
                                        .begin(app, identity, AssuranceLevel.PASSWORD)
                                        .isEmpty());
        assertThat(refused[0])
                .as("replacing a confirmed factor requires that factor (INV-IDN-05)")
                .isTrue();

        // And at MULTI_FACTOR it proceeds, with the existing factor STILL ACTIVE - a customer
        // setting up a new phone keeps a working second factor until they confirm the new one.
        inAFlow(
                app ->
                        assertThat(service().begin(app, identity, AssuranceLevel.MULTI_FACTOR))
                                .as("the positive control: the rule is conditional, not a refusal"
                                        + " of every replacement")
                                .isPresent());

        try (Connection app = DatabaseRoles.application()) {
            assertThat(enrolments.findActive(app, identity, MfaFactorType.TOTP))
                    .as("a confirmed factor is not removed by beginning a new enrolment")
                    .isPresent();
            assertThat(enrolments.findPending(app, identity, MfaFactorType.TOTP))
                    .as("and the replacement is pending beside it, usable by neither until confirmed")
                    .isPresent();
        }
    }

    // -----------------------------------------------------------------

    private MfaEnrolmentService service() {
        return new MfaEnrolmentService(
                enrolments,
                cipher,
                new TotpVerifier(CLOCK),
                RANDOMNESS,
                IDS,
                CLOCK,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter());
    }

    /** A one-element holder, because a generic array cannot be created. */
    private static final class Captured {
        private Sensitive<String> secret;
    }

    private Sensitive<String> beginAndCaptureSecret(IdentityId identity) throws SQLException {
        Captured captured = new Captured();
        inAFlow(app -> captured.secret = service().begin(app, identity, AssuranceLevel.PASSWORD).orElseThrow().secret());
        return captured.secret;
    }

    /** What the customer's phone would show. Generating codes is the device's job, not ours. */
    private static String codeFor(Sensitive<String> secret) {
        return com.finapp.identity.Authenticator.codeNow(secret, TotpParameters.current(), CLOCK);
    }

    private static long auditCount(IdentityId identity) throws SQLException {
        return countWhere(
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'identity.MfaEnrolmentConfirmed'"
                        + " AND change_summary IS NOT NULL"
                        + " AND actor_id IS NOT NULL"
                        + " AND target_id IN (SELECT id::text FROM identity.mfa_enrolment"
                        + " WHERE identity_id = ?)",
                identity.value());
    }

    private static long outboxCount(IdentityId identity) throws SQLException {
        return countWhere(
                "SELECT count(*) FROM platform.outbox_event"
                        + " WHERE event_type = 'identity.MfaEnrolled' AND aggregate_id = ?",
                identity.value());
    }

    /**
     * Binds the parameter explicitly rather than inferring its type from the SQL.
     *
     * <p>The first version guessed - {@code sql.contains("::text")} - and was wrong, because the
     * audit query contains that cast too. A heuristic over the statement text is a second, silent
     * definition of what the statement means.
     */
    private static long countWhere(String sql, Object parameter) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(sql)) {
            count.setObject(1, parameter);
            try (var rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long statusCount(Connection app, IdentityId identity, MfaFactorStatus status)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM identity.mfa_enrolment"
                                + " WHERE identity_id = ? AND status = ?")) {
            count.setObject(1, identity.value());
            count.setString(2, status.name());
            try (var rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static java.util.List<String> columnsOf(Connection app, String table)
            throws SQLException {
        java.util.List<String> columns = new java.util.ArrayList<>();
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'identity' AND table_name = ?")) {
            read.setString(1, table);
            try (var rows = read.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        assertThat(columns).as("the sweep must actually see columns").isNotEmpty();
        return columns;
    }

    private interface Work {
        void run(Connection app) throws SQLException;
    }

    private static void inAFlow(Work work) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            work.run(app);
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
