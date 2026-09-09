package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.ContactChannel;
import com.finapp.identity.ContactChannelKind;
import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialType;
import com.finapp.identity.EmailAddress;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcContactChannelStore;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcRecoveryRequestStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequest;
import com.finapp.identity.RecoveryService;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The {@code INV-IDN-06} abuse cases, one test each (`P1-TSK-023`).
 *
 * <h2>Why each is its own test</h2>
 *
 * <p>{@code INV-IDN-06} names its verification method: <em>"abuse-case tests: unverified channel,
 * recently changed channel, concurrent recovery and login, replayed recovery token."</em> One test
 * asserting "recovery is safe" would satisfy the letter and not the point — when it failed, nobody
 * would know <strong>which</strong> door had opened. Recovery is the account-takeover vector by
 * construction, so the failure message has to name the route.
 */
@Tag("database")
@DisplayName("recovery abuse cases (P1-TSK-023, INV-IDN-06)")
class RecoveryAbuseDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final ContactChannelStore<Connection> channels = new JdbcContactChannelStore();
    private final CredentialStore<Connection> credentials = new JdbcCredentialStore();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final AuditWriter<Connection> audit = new JdbcAuditWriter();
    private final OutboxWriter<Connection> outbox = new JdbcOutboxWriter();
    /**
     * Deliberately weak parameters.
     *
     * <p>This suite derives several credentials per test and the shipped work factor costs ~46 ms
     * each (ADR-0032). Nothing here is about the derivation's strength - {@code Argon2PasswordDeriverTest}
     * owns that, and {@code CredentialSchemaDatabaseTest} owns the recorded parameters - so paying
     * for it would make a security suite slow enough that somebody eventually skips it.
     */
    private final PasswordDeriver deriver =
            new Argon2PasswordDeriver(new com.finapp.identity.DerivationParameters(1024, 1, 1));

    private final ContactChannelService channelService =
            new ContactChannelService(channels, IDS, CLOCK, RANDOMNESS, audit);
    private final RecoveryService recoveries =
            new RecoveryService(
                    new JdbcRecoveryRequestStore(IDS),
                    credentials,
                    sessions,
                    deriver,
                    IDS,
                    CLOCK,
                    RANDOMNESS,
                    audit,
                    outbox);

    // -----------------------------------------------------------------
    // INV-IDN-06's four named abuse cases

    @Test
    @DisplayName("a replayed token is refused")
    void aReplayedTokenIsRefused() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        Started started = whenRecoveryIsInitiatedFor(identity);

        assertThat(complete(started, "the-first-replacement")).isTrue();

        // The token is spent, not merely stale. Single use is a conditional UPDATE whose row count
        // is the outcome, so a second presentation finds nothing to consume - and that is what makes
        // a token read from a mailbox archive months later worthless.
        assertThat(complete(started, "the-second-replacement"))
                .as("a recovery token must be spendable exactly once")
                .isFalse();
    }

    @Test
    @DisplayName("a token cancelled by a later initiation is dead, with the credential unchanged")
    void aCancelledTokenIsRefused() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        givenACredentialFor(identity, "the-original-password");
        Started attackers = whenRecoveryIsInitiatedFor(identity);

        // The customer initiates their own recovery, which cancels the attacker's. Past the
        // cooling-off window, because that is the realistic timeline and because cooling-off would
        // otherwise refuse the second initiation and this test would prove nothing.
        pushInitiationsPastCoolingOff(identity);
        whenRecoveryIsInitiatedFor(identity);

        // THIS TEST EXISTS BECAUSE A MUTATION SURVIVED, and the survivor was informative.
        //
        // Removing `status = 'INITIATED'` from consume left aReplayedTokenIsRefused green: a
        // completed recovery has REPLACED the credential, so the binding predicate refuses the
        // replay on its own. Two mechanisms, one outcome, and an outcome-only test cannot name
        // which produced it - the P1-TSK-018 finding.
        //
        // Here the credential has NOT changed, so the binding matches and only the status check
        // stands between the attacker and the account. It is also a real attack rather than a
        // contrivance: the customer doing the sensible thing - starting their own recovery - is
        // precisely what must not leave the attacker's earlier token usable.
        assertThat(complete(attackers, "the-attackers-password"))
                .as("a token cancelled by a later initiation must be dead even though the"
                        + " credential it was bound to is unchanged")
                .isFalse();
    }

    @Test
    @DisplayName("an identity whose channel is unverified cannot recover")
    void anUnverifiedChannelCannotRecover() throws Exception {
        IdentityId identity = givenAnIdentity();
        inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress()));

        // THE ACCEPTANCE CRITERION. INV-IDN-06: no recovery "without proving control of a previously
        // registered and VERIFIED channel". A channel that has been added and not proven is exactly
        // the attacker's position after registering their own mailbox - so if this passed, adding a
        // channel would BE the takeover.
        assertThat(initiate(identity))
                .as("recovery requires a channel somebody has proven they control (INV-IDN-06)")
                .isEmpty();
    }

    @Test
    @DisplayName("a recently changed channel is visible on the request, so a takeover leaves a trace")
    void aRecentlyChangedChannelIsVisible() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        whenRecoveryIsInitiatedFor(identity);

        // INV-IDN-06 names "recently changed channel" as an abuse case, and what Phase 1 can do about
        // it is make it VISIBLE rather than refuse it. Refusing recovery for a channel verified
        // recently would lock out the ordinary customer who has just moved email provider, and the
        // signal an investigator needs is when control was proven relative to when recovery ran.
        //
        // So the channel records verifiedAt rather than a boolean, the recovery request records which
        // channel it went to, and both are in the audit trail. Stating the limit is the honest part:
        // acting on that signal is a risk decision, and Phase 13 owns risk decisions.
        try (Connection app = DatabaseRoles.application()) {
            Optional<ContactChannel> channel =
                    channels.findVerified(app, identity, ContactChannelKind.EMAIL);
            assertThat(channel).isPresent();
            assertThat(channel.orElseThrow().verifiedAt())
                    .as("when control was proven must be recorded, not merely that it was")
                    .isPresent();
        }
        assertThat(auditedOperations(identity))
                .as("the trail must show both the channel becoming trusted and the recovery using it")
                .contains("identity.ContactChannelVerified", "identity.RecoveryInitiated");
    }

    @Test
    @DisplayName("a credential change between initiation and completion kills the token")
    void concurrentRecoveryAndLoginKillsTheToken() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        givenACredentialFor(identity, "the-original-password");
        Started attackers = whenRecoveryIsInitiatedFor(identity);

        // The customer notices and changes their password. Modelled directly rather than through an
        // endpoint, because P1-TSK-026 owns credential change and does not exist yet - and this
        // control must not wait for it.
        inAFlow(
                unitOfWork -> {
                    Credential active =
                            credentials
                                    .findActive(unitOfWork, identity, CredentialType.PASSWORD)
                                    .orElseThrow();
                    credentials.supersede(unitOfWork, active.id(), java.time.Instant.now(CLOCK));
                    credentials.insert(
                            unitOfWork,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    identity,
                                    CredentialType.PASSWORD,
                                    deriver,
                                    new RawPassword(
                                            com.finapp.sharedkernel.security.Sensitive.of(
                                                    "the-customers-new-password"))));
                    return null;
                });

        // THE DANGEROUS FORM OF THIS ABUSE CASE. The attacker initiates at T0, the customer fixes
        // things at T1, and without this the attacker completes at T2 and wins anyway - having
        // watched the customer do the one thing they thought would save them.
        //
        // The control is a predicate rather than "revoke recovery requests when the credential
        // changes", because a predicate cannot be forgotten by a future credential-change caller.
        assertThat(complete(attackers, "the-attackers-password"))
                .as("a token raised against a credential that has since changed must be dead")
                .isFalse();
    }

    // -----------------------------------------------------------------
    // The fifth case: recovery must not lower the assurance required

    @Test
    @DisplayName("recovery issues no session, so it cannot reach a MULTI_FACTOR operation")
    void recoveryIssuesNoSession() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        givenACredentialFor(identity, "the-original-password");
        givenASessionFor(identity);
        Started started = whenRecoveryIsInitiatedFor(identity);

        long before = liveSessionsOf(identity);
        assertThat(complete(started, "a-replacement-password")).isTrue();

        // THE FIFTH ABUSE CASE, and the design answer to it rather than a check bolted onto one.
        //
        // "Recovery used to reach an operation requiring MULTI_FACTOR" cannot even be ATTEMPTED,
        // because recovery produces nothing to attempt it with. The conventional design logs you in
        // on completion, and that IS the lowering INV-IDN-06's second clause forbids: an attacker
        // holding the mailbox would skip the credential AND whatever stood behind it.
        //
        // Setting the credential and stopping means the customer authenticates normally afterwards,
        // so MFA applies in full - and MfaBypassPathsAreEnumeratedTest's statement stays true,
        // which is the question it listed recovery as a recorded remainder for.
        assertThat(liveSessionsOf(identity))
                .as("recovery must issue no session: producing one is how recovery becomes the way in")
                .isZero();
        assertThat(before).as("the fixture must have had a session to lose").isPositive();
    }

    @Test
    @DisplayName("completion ends every session, in both directions")
    void completionEndsEverySession() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        givenACredentialFor(identity, "the-original-password");
        givenASessionFor(identity);
        givenASessionFor(identity);
        Started started = whenRecoveryIsInitiatedFor(identity);

        assertThat(liveSessionsOf(identity)).isEqualTo(2);
        assertThat(complete(started, "a-replacement-password")).isTrue();

        // Both directions matter, and they are the same assertion. If the ATTACKER recovered, the
        // customer's session must die. If the CUSTOMER recovered because they suspected a
        // compromise, the attacker's must. A credential change spares the session performing it
        // (P1-TSK-014); recovery has no such session, so it spares nothing.
        assertThat(liveSessionsOf(identity))
                .as("recovery must end every session, because either party may be the attacker")
                .isZero();
    }

    // -----------------------------------------------------------------
    // Ownership, named by OwnershipIsScopedTest

    @Test
    @DisplayName("a channel is not readable by another identity")
    void aChannelIsNotReadableByAnotherIdentity() throws Exception {
        IdentityId victim = givenAnIdentityWithAVerifiedChannel();
        IdentityId attacker = givenAnIdentity();

        try (Connection app = DatabaseRoles.application()) {
            ContactChannel theirs =
                    channels.findVerified(app, victim, ContactChannelKind.EMAIL).orElseThrow();

            // ADR-0031's ownership half. A channel is the thing an attacker most wants to read and
            // then point at their own mailbox, so reading somebody else's must be impossible rather
            // than merely unusual - and the check is `identity_id = ?` in the STATEMENT, never a
            // load-then-compare, which is a TOCTOU race against a copy of the truth.
            assertThat(channels.findOwned(app, theirs.id(), attacker))
                    .as("one identity must not read another's contact channel")
                    .isEmpty();
            assertThat(channels.findOwned(app, theirs.id(), victim))
                    .as("and the owner must still be able to, so the refusal is not blanket")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("a suspended identity cannot recover")
    void aSuspendedIdentityCannotRecover() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        whenSuspended(identity);

        // FOUND BY A SURVIVING MUTATION: removing `i.status = 'ACTIVE'` changed nothing, because
        // nothing here ever suspended anybody.
        //
        // It matters more than a missing case usually does. Suspension is the most consequential
        // thing one person can do to another's account (IdentityAuditAction.IDENTITY_SUSPENDED
        // requires a REASON for that reason), and if recovery ignored it, the suspended party could
        // undo an administrator's decision by using the front door - which is INV-IDN-06's
        // "recovery cannot elevate an attacker" in its most literal form.
        assertThat(initiate(identity))
                .as("a suspended identity must not recover its way back in")
                .isEmpty();
    }

    @Test
    @DisplayName("a verification token cannot be spent twice")
    void aVerificationTokenCannotBeSpentTwice() throws Exception {
        IdentityId identity = givenAnIdentity();
        ContactChannelService.Added added =
                inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress()));

        Optional<ContactChannel> first =
                inAFlow(
                        unitOfWork ->
                                channelService.verify(
                                        unitOfWork, added.challenge().presentedValue()));
        assertThat(first).isPresent();

        // A verification link is as replayable as a recovery one - it sits in a mailbox for years.
        // THREE mechanisms refuse the second use, and that is stated rather than left implicit
        // because a mutation removing one of them SURVIVED: the token is cleared on success, the
        // statement requires `verified_at IS NULL`, and a CHECK constraint forbids a verified row
        // from holding a live challenge at all. Removing the middle one changes nothing, which is
        // defence in depth working rather than a gap - the P1-TSK-018 finding, recorded rather than
        // tidied away.
        Optional<ContactChannel> second =
                inAFlow(
                        unitOfWork ->
                                channelService.verify(
                                        unitOfWork, added.challenge().presentedValue()));
        assertThat(second)
                .as("a verification token must be spendable exactly once")
                .isEmpty();
    }

    @Test
    @DisplayName("cooling-off refuses a second initiation, and says nothing about why")
    void coolingOffRefusesASecondInitiation() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        assertThat(initiate(identity)).isPresent();

        // Without cooling-off, anybody who knows a login identifier can make somebody's inbox ring
        // indefinitely - and the customer learns to ignore the message that matters, which turns a
        // nuisance into the reason the real takeover succeeds.
        assertThat(initiate(identity))
                .as("a second initiation within the cooling-off window must be refused")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown login identifier is refused exactly as a known one without a channel is")
    void anUnknownIdentifierIsIndistinguishable() throws Exception {
        IdentityId withoutAChannel = givenAnIdentity();

        Optional<RecoveryRequest> unknown =
                inAFlow(
                        unitOfWork ->
                                recoveries
                                        .initiate(
                                                unitOfWork,
                                                new com.finapp.identity.LoginIdentifier(
                                                        "nobody" + UUID.randomUUID().toString()
                                                                .replace("-", "")
                                                                .substring(0, 12)))
                                        .map(RecoveryService.Initiated::request));

        // INV-IDN-07 applied to recovery. This endpoint needs nothing to call, so it is the first
        // one an attacker probes - and a response that varied would turn it into an
        // account-existence oracle for the whole platform.
        assertThat(unknown).isEmpty();
        assertThat(initiate(withoutAChannel))
                .as("an account that exists but cannot recover must look exactly like one that does"
                        + " not exist")
                .isEqualTo(unknown);
    }

    // -----------------------------------------------------------------

    private interface Work<T> {
        T run(Connection unitOfWork) throws SQLException;
    }

    /** Runs inside a correlation scope and as the platform, which is what recovery really does. */
    private static <T> T inAFlow(Work<T> work) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            T outcome = work.run(app);
            app.commit();
            return outcome;
        } finally {
            actor.close();
            correlation.close();
        }
    }

    /**
     * Back-dates this identity's requests so cooling-off permits another initiation.
     *
     * <p>Rather than sleeping for the cooling-off period, which would make this suite five minutes
     * long, or shortening the policy, which would test a configuration nothing ships. The row is
     * back-dated the way {@code OutboxRelayTest} back-dates an outbox row, and for the same reason:
     * a test must not wait for a duration when the thing under test is a predicate over a timestamp.
     */
    private static void pushInitiationsPastCoolingOff(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE identity.recovery_request"
                                        + " SET initiated_at = initiated_at - interval '1 day'"
                                        + " WHERE identity_id = ?")) {
            update.setObject(1, identity.value());
            update.executeUpdate();
        }
    }

    private Optional<RecoveryRequest> initiate(IdentityId identity) throws SQLException {
        return inAFlow(
                unitOfWork ->
                        recoveries
                                .initiate(unitOfWork, loginOf(identity))
                                .map(RecoveryService.Initiated::request));
    }

    /**
     * A recovery request and its token, held together.
     *
     * <p>The first version of this fixture returned only the token and looked the request up as
     * "the latest for this identity" - and {@code aCancelledTokenIsRefused} then passed for the
     * WRONG REASON, because after the customer's second initiation the latest request is theirs,
     * so the attacker's token was being presented against the customer's identifier and refused on
     * the token match rather than on the status. A mutation removing the status predicate SURVIVED
     * and is what exposed it.
     */
    private record Started(com.finapp.identity.RecoveryRequestId id, String token) {}

    private Started whenRecoveryIsInitiatedFor(IdentityId identity) throws SQLException {
        return inAFlow(
                unitOfWork -> {
                    RecoveryService.Initiated started =
                            recoveries.initiate(unitOfWork, loginOf(identity)).orElseThrow();
                    return new Started(
                            started.request().id(),
                            started.token().presentedValue().expose());
                });
    }

    private boolean complete(Started started, String replacement) throws SQLException {
        com.finapp.identity.RecoveryRequestId id = started.id();
        String token = started.token();
        return inAFlow(
                unitOfWork ->
                        recoveries.complete(
                                unitOfWork,
                                id,
                                com.finapp.sharedkernel.security.Sensitive.of(token),
                                new RawPassword(
                                        com.finapp.sharedkernel.security.Sensitive.of(replacement))));
    }

    // -----------------------------------------------------------------
    // Fixtures

    private IdentityId givenAnIdentityWithAVerifiedChannel() throws SQLException {
        IdentityId identity = givenAnIdentity();
        ContactChannelService.Added added =
                inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress()));
        inAFlow(
                unitOfWork ->
                        channelService.verify(
                                unitOfWork, added.challenge().presentedValue()));
        return identity;
    }

    private void givenACredentialFor(IdentityId identity, String password) throws SQLException {
        inAFlow(
                unitOfWork -> {
                    credentials.insert(
                            unitOfWork,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    identity,
                                    CredentialType.PASSWORD,
                                    deriver,
                                    new RawPassword(
                                            com.finapp.sharedkernel.security.Sensitive.of(password))));
                    return null;
                });
    }

    private void givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
    }

    /** Suspension has no endpoint until `P1-TSK-028`; the domain rule is what is under test. */
    private static void whenSuspended(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE identity.identity"
                                        + " SET status = 'SUSPENDED', status_changed_at = now()"
                                        + " WHERE id = ?")) {
            update.setObject(1, identity.value());
            update.executeUpdate();
        }
    }

    private static EmailAddress anAddress() {
        return new EmailAddress(
                "ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                        + "@example.com");
    }

    private static long liveSessionsOf(IdentityId identity) throws SQLException {
        return scalar(
                "SELECT count(*) FROM identity.session WHERE identity_id = ? AND status = 'ACTIVE'",
                identity.value());
    }

    private static java.util.List<String> auditedOperations(IdentityId identity)
            throws SQLException {
        java.util.List<String> operations = new java.util.ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT operation FROM platform.audit_record"
                                        + " WHERE change_summary LIKE ?")) {
            select.setString(1, "%" + identity.value() + "%");
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    operations.add(rows.getString(1));
                }
            }
        }
        return operations;
    }

    private static long scalar(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static com.finapp.identity.LoginIdentifier loginOf(IdentityId identity)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT login_identifier FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return new com.finapp.identity.LoginIdentifier(rows.getString(1));
            }
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
                    // Back-dated: this fixture is later moved to another status by an UPDATE that
                    // reads now() again, and the local container's clock is corrected backwards
                    // between statements (P1-TSK-031; observed 225 ms). The ordering constraint is
                    // right and a fixture must not depend on two now() reads being ordered.
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                        + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                        + " now() - interval '1 hour', now() - interval '1 hour')",
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
