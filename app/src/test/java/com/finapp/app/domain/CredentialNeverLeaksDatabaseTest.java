package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code INV-IDN-01}: no persisted or emitted representation contains the password (`P1-TSK-007`).
 *
 * <h2>Asserted against every byte of the row, not against the column somebody remembered</h2>
 *
 * <p>The obvious version of this test checks that {@code derivation} is not the plaintext, and it
 * would pass against an implementation that also wrote the password into a second column, into a
 * comment, or into an audit record. So this reads <strong>the whole row, every column, as text</strong>
 * and asserts the password appears in none of it - which is a claim about the table rather than
 * about the field the author was thinking of.
 *
 * <p>A distinctive plaintext is used for the same reason {@code CallerCorrelationIsNotPropagatedTest}
 * uses the values a probe actually found: a password of "password" could appear by coincidence, and
 * an assertion that can pass by coincidence proves nothing.
 */
@Tag("database")
@DisplayName("a credential never leaks its plaintext (P1-TSK-007)")
class CredentialNeverLeaksDatabaseTest {

    /**
     * Distinctive enough that finding it anywhere is proof rather than coincidence, and long enough
     * that a substring search cannot match by accident.
     */
    private static final String PLAINTEXT = "zqx-plaintext-marker-8f31d7-never-stored";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    @Test
    @DisplayName("no column of the stored row contains the password, or any word of it")
    void nothingPersistedContainsThePassword() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            Credential credential = derive(identity);
            new JdbcCredentialStore().insert(app, credential);

            String wholeRow = wholeRowAsText(app, identity);

            assertThat(wholeRow).as("the row is really there to inspect").isNotBlank();
            assertThat(wholeRow)
                    .as("INV-IDN-01: no stored representation may contain the input")
                    .doesNotContain(PLAINTEXT);
            for (String fragment : PLAINTEXT.split("-")) {
                if (fragment.length() >= 5) {
                    assertThat(wholeRow)
                            .as("not even a fragment of it: '%s'", fragment)
                            .doesNotContain(fragment);
                }
            }
        }
    }

    @Test
    @DisplayName("the derivation is not reversible, and the same password never derives twice alike")
    void theDerivationIsNotAnEncoding() throws SQLException {
        // The check that separates a derivation from an encoding. Base64 of the password would
        // satisfy "does not contain the plaintext" perfectly - and would be reversible, and would
        // be identical every time. Salting is what rules both out.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            Credential credential = derive(identity);
            new JdbcCredentialStore().insert(app, credential);

            String stored = storedDerivation(app, identity);

            assertThat(stored).doesNotContain(java.util.Base64.getEncoder().encodeToString(PLAINTEXT.getBytes()));
            assertThat(stored)
                    .as("a second derivation of the same password differs, so this is not an encoding")
                    .isNotEqualTo(DERIVER.derive(RawPassword.of(PLAINTEXT)).expose());
        }
    }

    @Test
    @DisplayName("nothing renders the plaintext: not the wrapper, not the credential, not an exception")
    void nothingRendersThePlaintext() {
        RawPassword password = RawPassword.of(PLAINTEXT);
        Credential credential = derive(IDS.next());

        assertThat(password.toString())
                .as("Sensitive masks every rendering path, including a record's generated toString")
                .doesNotContain(PLAINTEXT);
        assertThat(credential.toString())
                .as("the aggregate omits the derivation entirely rather than masking it")
                .doesNotContain(PLAINTEXT)
                .doesNotContain(credential.credentialDerivation().expose());
        assertThat(credential.credentialDerivation().toString())
                .as("even the derivation does not print itself: it is offline-crackable material")
                .doesNotContain(credential.credentialDerivation().expose());
    }

    @Test
    @DisplayName("a rejected password is not echoed by the exception that rejects it")
    void aRejectionDoesNotEchoThePassword() {
        // The likeliest accidental disclosure: a validation message that helpfully repeats what it
        // refused, into a log line (INV-AUD-02).
        String tooShort = "zqx1";

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                        () -> RawPassword.of(tooShort))
                                .getMessage())
                .doesNotContain(tooShort);
    }

    // -----------------------------------------------------------------

    private static Credential derive(UUID identity) {
        return Credential.forPassword(
                IDS,
                CLOCK,
                IdentityId.of(identity),
                CredentialType.PASSWORD,
                DERIVER,
                RawPassword.of(PLAINTEXT));
    }

    /**
     * Every column of the credential row, concatenated as text.
     *
     * <p>Built from {@code information_schema} rather than from a list, so a column added later is
     * inspected without anybody remembering to add it here - which is the difference between this
     * test and one that checks the column its author had in mind.
     */
    private static String wholeRowAsText(Connection connection, UUID identity) throws SQLException {
        StringBuilder columns = new StringBuilder();
        try (PreparedStatement names =
                        connection.prepareStatement(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_schema = 'identity' AND table_name = 'credential'"
                                        + " ORDER BY ordinal_position");
                ResultSet rows = names.executeQuery()) {
            while (rows.next()) {
                if (columns.length() > 0) {
                    columns.append(" || ' ' || ");
                }
                columns.append("coalesce(").append(rows.getString(1)).append("::text, '')");
            }
        }
        if (columns.length() == 0) {
            throw new IllegalStateException("No columns found; the guard would be vacuous");
        }

        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT " + columns + " FROM identity.credential WHERE identity_id = ?")) {
            select.setObject(1, identity);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static String storedDerivation(Connection connection, UUID identity) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT derivation FROM identity.credential WHERE identity_id = ?")) {
            select.setObject(1, identity);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static UUID givenAnIdentity(Connection connection) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (PreparedStatement insertParty =
                connection.prepareStatement(
                        "INSERT INTO party.party (id, kind, display_name, registered_at)"
                                + " VALUES (?, 'PERSON', 'Ada Lovelace', now())")) {
            insertParty.setObject(1, party);
            insertParty.executeUpdate();
        }
        try (PreparedStatement insertIdentity =
                connection.prepareStatement(
                        "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                                + " created_at, status_changed_at)"
                                + " VALUES (?, ?, ?, 'ACTIVE', now(), now())")) {
            insertIdentity.setObject(1, identity);
            insertIdentity.setObject(2, party);
            insertIdentity.setString(
                    3, "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            insertIdentity.executeUpdate();
        }
        return identity;
    }
}
