package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The two administrative endpoints, over real HTTP (`P1-TSK-028`).
 *
 * <h2>The assertion that matters most is that a suspension ends live sessions</h2>
 *
 * <p>{@code JdbcSessionStore.findByToken} filters on the <strong>session's</strong> status and never
 * joins {@code identity.identity}, and {@code CredentialVerifier} refuses a suspended identity only
 * at <em>authentication</em>. So without the revocation, suspending somebody would stop their next
 * login and leave the session an attacker is holding right now working until its absolute bound
 * expired — a success response for an operation that did almost nothing.
 *
 * <p>{@link #aSuspensionEndsLiveSessions} drives that end to end: the subject's token works, an
 * administrator suspends, and the <strong>same token</strong> is refused. A new token would prove
 * only that a suspended identity cannot log in, which was already true.
 *
 * <h2>Ownership is inverted here, and both halves are asserted</h2>
 *
 * <p>ADR-0031 wants permission at the boundary and ownership in the domain. The ownership rule is
 * the opposite of every other one in this codebase — the subject must <strong>not</strong> be the
 * actor — so the negative ownership tests are {@link #anAdministratorCannotSuspendThemselves} and
 * {@link #anAdministratorCannotElevateThemselves}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the administrative endpoints (P1-TSK-028)")
class IdentityAdministrationDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private static final String REASON = "{\"reason\":\"offboarding, ticket OPS-4417\"}";

    /** A distinct ticket, so an audit assertion cannot be satisfied by the suspension's record. */
    private static final String REINSTATEMENT_REASON =
            "{\"reason\":\"incident closed, ticket OPS-9921\"}";

    /** A role's revocation (`P15-TSK-032`): its own ticket, so no other record can satisfy an assertion about it. */
    private static final String REVOCATION_REASON = "{\"reason\":\"the incident closed, ticket OPS-8812\"}";

    private static final String PASSWORD = "a-long-enough-password-for-administration";

    @LocalServerPort private int port;

    @Autowired private Authorization authorization;

    @Autowired private IdentityAdministration administration;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------
    // The acceptance criterion: refused without the role, and the refusal is audited

    @Test
    @DisplayName("a session holding no role is refused by both endpoints")
    void bothEndpointsRefuseASessionWithNoRole() throws Exception {
        IdentityId caller = givenAnIdentity();
        String session = givenASessionFor(caller);
        IdentityId subject = givenAnIdentity();

        assertThat(post(suspensionOf(subject), session, REASON).statusCode())
                .as("a valid session is not a role")
                .isEqualTo(403);
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(403);

        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("each refusal is audited against the person who attempted it")
    void everyRefusalIsAudited() throws Exception {
        IdentityId caller = givenAnIdentity();
        String session = givenASessionFor(caller);
        IdentityId subject = givenAnIdentity();

        long before = denialsFor(caller);
        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(403);
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(403);

        // A refused privileged attempt is the only durable trace an attacker probing for
        // administrative endpoints leaves behind (INV-AUD-03, and P1-TSK-020's reasoning).
        assertThat(denialsFor(caller) - before).isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // The positive controls, so refusal is not blanket

    @Test
    @DisplayName("an administrator can suspend somebody else, and it is audited against them")
    void anAdministratorCanSuspend() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();

        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(204);
        assertThat(statusOf(subject)).isEqualTo("SUSPENDED");

        // The actor is the administrator and the target is the subject. Recording it the other way
        // round would make every administrative action read as self-inflicted.
        assertThat(auditOf("identity.IdentitySuspended", subject, admin))
                .as("the trail names who acted, on whom, and why")
                .contains("OPS-4417");
    }

    @Test
    @DisplayName("an administrator can grant a role to somebody else")
    void anAdministratorCanGrantARole() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();
        // ADMINISTRATOR needs its holder to have enrolled a factor (P15-TSK-032, item 42 (e)).
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).as("no factor yet").isEqualTo(409);
        givenAConfirmedFactorFor(subject);

        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(204);
        assertThat(auditOf("identity.RoleAssigned", subject, admin)).contains("OPS-4417");

        // And the grant is real: the subject can now do what only an administrator may.
        IdentityId victim = givenAnIdentity();
        assertThat(post(suspensionOf(victim), givenASessionFor(subject), REASON).statusCode())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("a KYC reviewer is refused by both administrative endpoints (P2-TSK-004)")
    void aReviewerIsRefusedByBothAdministrativeEndpoints() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId reviewer = givenAnIdentity();

        // Granted through the REAL endpoint rather than a fixture row, so the whole path is
        // exercised: the request enum admits the new value at the boundary, V013's regenerated
        // constraint admits the row, and the grant is audited like any other.
        assertThat(post(rolesOf(reviewer), givenASessionFor(admin), reviewerRole()).statusCode())
                .isEqualTo(204);

        String reviewerSession = givenASessionFor(reviewer);
        IdentityId subject = givenAnIdentity();
        assertThat(post(suspensionOf(subject), reviewerSession, REASON).statusCode())
                .as("the least-privilege split: reviewing cases is not managing identities")
                .isEqualTo(403);
        assertThat(post(rolesOf(subject), reviewerSession, adminRole()).statusCode())
                .as("and certainly not granting roles - the permission that grants permissions")
                .isEqualTo(403);
        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a ledger operator is refused by both administrative endpoints (P3-TSK-007)")
    void aLedgerOperatorIsRefusedByBothAdministrativeEndpoints() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId operator = givenAnIdentity();
        givenAConfirmedFactorFor(operator); // LEDGER_OPERATOR can pay a merchant out: it needs one (P15-TSK-039).

        // Granted through the REAL endpoint (the P2-TSK-004 reviewer precedent), so the whole
        // path is exercised: the request enum admits the new value at the boundary, V014's
        // regenerated constraint admits the row, and the grant is audited like any other.
        assertThat(
                        post(rolesOf(operator), givenASessionFor(admin), ledgerOperatorRole())
                                .statusCode())
                .isEqualTo(204);

        String operatorSession = givenASessionFor(operator);
        IdentityId subject = givenAnIdentity();
        assertThat(post(suspensionOf(subject), operatorSession, REASON).statusCode())
                .as("posting the platform's money is not managing the people who hold it")
                .isEqualTo(403);
        assertThat(post(rolesOf(subject), operatorSession, adminRole()).statusCode())
                .as("and certainly not granting roles - the permission that grants permissions")
                .isEqualTo(403);
        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the two credit roles are assignable, and each is refused by both administrative endpoints (P10-TSK-003)")
    void theCreditRolesAreAssignableAndRefusedAdministration() throws Exception {
        IdentityId admin = givenAnAdministrator();
        for (String role : java.util.List.of("CREDIT_POLICY_OFFICER", "UNDERWRITER")) {
            IdentityId holder = givenAnIdentity();

            // Granted through the REAL endpoint (the P2-TSK-004 precedent): the request enum admits
            // the value at the boundary, V020's regenerated constraint admits the row, and the grant
            // is audited like any other.
            assertThat(post(rolesOf(holder), givenASessionFor(admin),
                                    "{\"role\":\"" + role + "\",\"reason\":\"OPS-4417 credit desk\"}")
                            .statusCode())
                    .as(role).isEqualTo(204);
            assertThat(auditOf("identity.RoleAssigned", holder, admin)).as(role).contains("OPS-4417");

            String holderSession = givenASessionFor(holder);
            IdentityId subject = givenAnIdentity();
            assertThat(post(suspensionOf(subject), holderSession, REASON).statusCode())
                    .as("%s: deciding credit is not managing identities", role)
                    .isEqualTo(403);
            assertThat(post(rolesOf(subject), holderSession, adminRole()).statusCode())
                    .as("%s: and certainly not granting roles", role)
                    .isEqualTo(403);
            assertThat(statusOf(subject)).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("the three lending roles are assignable, and each is refused by both administrative endpoints (P11-TSK-002)")
    void theLendingRolesAreAssignableAndRefusedAdministration() throws Exception {
        IdentityId admin = givenAnAdministrator();
        for (String role : java.util.List.of("LENDING_OFFICER", "LENDING_TREASURY_OFFICER", "LOAN_SERVICING_AGENT")) {
            IdentityId holder = givenAnIdentity();

            // Granted through the REAL endpoint (the P2-TSK-004 precedent): the request enum admits
            // the value at the boundary, V021's regenerated constraint admits the row, and the grant
            // is audited like any other.
            assertThat(post(rolesOf(holder), givenASessionFor(admin),
                                    "{\"role\":\"" + role + "\",\"reason\":\"OPS-5102 lending desk\"}")
                            .statusCode())
                    .as(role).isEqualTo(204);
            assertThat(auditOf("identity.RoleAssigned", holder, admin)).as(role).contains("OPS-5102");

            String holderSession = givenASessionFor(holder);
            IdentityId subject = givenAnIdentity();
            assertThat(post(suspensionOf(subject), holderSession, REASON).statusCode())
                    .as("%s: lending is not managing identities", role)
                    .isEqualTo(403);
            assertThat(post(rolesOf(subject), holderSession, adminRole()).statusCode())
                    .as("%s: and certainly not granting roles", role)
                    .isEqualTo(403);
            assertThat(statusOf(subject)).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("the two BNPL roles are assignable, and each is refused by both administrative endpoints (P12-TSK-002)")
    void theBnplRolesAreAssignableAndRefusedAdministration() throws Exception {
        IdentityId admin = givenAnAdministrator();
        for (String role : java.util.List.of("BNPL_OFFICER", "BNPL_SERVICING_AGENT")) {
            IdentityId holder = givenAnIdentity();

            // Granted through the REAL endpoint (the P2-TSK-004 precedent): the request enum admits the value at the
            // boundary, identity V022's regenerated constraint admits the row, and the grant is audited like any other.
            assertThat(post(rolesOf(holder), givenASessionFor(admin),
                                    "{\"role\":\"" + role + "\",\"reason\":\"OPS-5203 bnpl desk\"}")
                            .statusCode())
                    .as(role).isEqualTo(204);
            assertThat(auditOf("identity.RoleAssigned", holder, admin)).as(role).contains("OPS-5203");

            String holderSession = givenASessionFor(holder);
            IdentityId subject = givenAnIdentity();
            assertThat(post(suspensionOf(subject), holderSession, REASON).statusCode())
                    .as("%s: BNPL is not managing identities", role)
                    .isEqualTo(403);
            assertThat(post(rolesOf(subject), holderSession, adminRole()).statusCode())
                    .as("%s: and certainly not granting roles", role)
                    .isEqualTo(403);
            assertThat(statusOf(subject)).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("the three risk roles are assignable, and each is refused by both administrative endpoints (P13-TSK-001)")
    void theRiskRolesAreAssignableAndRefusedAdministration() throws Exception {
        IdentityId admin = givenAnAdministrator();
        for (String role : java.util.List.of("RISK_ANALYST", "RISK_OFFICER", "AML_OFFICER")) {
            IdentityId holder = givenAnIdentity();

            // Granted through the REAL endpoint (the P2-TSK-004 precedent): the request enum admits the value at the
            // boundary, identity V023's regenerated constraint admits the row, and the grant is audited like any other.
            assertThat(post(rolesOf(holder), givenASessionFor(admin),
                                    "{\"role\":\"" + role + "\",\"reason\":\"OPS-6101 risk desk\"}")
                            .statusCode())
                    .as(role).isEqualTo(204);
            assertThat(auditOf("identity.RoleAssigned", holder, admin)).as(role).contains("OPS-6101");

            String holderSession = givenASessionFor(holder);
            IdentityId subject = givenAnIdentity();
            assertThat(post(suspensionOf(subject), holderSession, REASON).statusCode())
                    .as("%s: a risk desk is not managing identities - a suspension is an action through identity's"
                            + " door, keyed and approved, never a holder's own call", role)
                    .isEqualTo(403);
            assertThat(post(rolesOf(subject), holderSession, adminRole()).statusCode())
                    .as("%s: and certainly not granting roles", role)
                    .isEqualTo(403);
            assertThat(statusOf(subject)).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("the three accounting roles are assignable - one identity may hold two of them - and each is refused by"
            + " both administrative endpoints (P14-TSK-001)")
    void theAccountingRolesAreAssignableAndRefusedAdministration() throws Exception {
        IdentityId admin = givenAnAdministrator();
        for (String role : java.util.List.of("ACCOUNTANT", "FINANCIAL_CONTROLLER", "REGULATORY_REPORTER")) {
            IdentityId holder = givenAnIdentity();

            // Granted through the REAL endpoint: the request enum admits the value at the boundary, identity V025's
            // regenerated constraint admits the row, and the grant is audited like any other.
            assertThat(post(rolesOf(holder), givenASessionFor(admin),
                                    "{\"role\":\"" + role + "\",\"reason\":\"OPS-6201 finance desk\"}")
                            .statusCode())
                    .as(role).isEqualTo(204);
            assertThat(auditOf("identity.RoleAssigned", holder, admin)).as(role).contains("OPS-6201");

            String holderSession = givenASessionFor(holder);
            IdentityId subject = givenAnIdentity();
            assertThat(post(suspensionOf(subject), holderSession, REASON).statusCode())
                    .as("%s: a finance desk is not managing identities", role)
                    .isEqualTo(403);
            assertThat(post(rolesOf(subject), holderSession, adminRole()).statusCode())
                    .as("%s: and certainly not granting roles", role)
                    .isEqualTo(403);
            assertThat(statusOf(subject)).isEqualTo("ACTIVE");
        }

        // The grants are disjoint, the holders need not be: identity's arbiter is one live assignment of EACH role, so a
        // second accounting role on the same identity is admitted - which is why approver <> proposer is a CHECK on every
        // four-eyes row and a refusal at the domain (accounting.SelfApprovalRefused, INV-AUD-04), never the roles alone.
        IdentityId both = givenAnIdentity();
        for (String role : java.util.List.of("ACCOUNTANT", "FINANCIAL_CONTROLLER")) {
            assertThat(post(rolesOf(both), givenASessionFor(admin),
                                    "{\"role\":\"" + role + "\",\"reason\":\"OPS-6202 two desks\"}")
                            .statusCode())
                    .as(role).isEqualTo(204);
        }
    }

    // -----------------------------------------------------------------
    // The inverted ownership rule

    @Test
    @DisplayName("an administrator cannot suspend themselves")
    void anAdministratorCannotSuspendThemselves() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);

        assertThat(post(suspensionOf(admin), session, REASON).statusCode())
                .as("reinstatement needs a SECOND administrator, which the platform does not"
                        + " guarantee exists - and the trail must never hold a self-loop"
                        + " (P1-TSK-032 revisited this and kept the refusal)")
                .isEqualTo(422);
        assertThat(statusOf(admin)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an administrator cannot assign a role to themselves")
    void anAdministratorCannotElevateThemselves() throws Exception {
        IdentityId admin = givenAnAdministrator();

        assertThat(post(rolesOf(admin), givenASessionFor(admin), adminRole()).statusCode())
                .as("every escalation must name two parties, so the trail can never hold a"
                        + " self-loop that reads like a system action")
                .isEqualTo(422);
    }

    // -----------------------------------------------------------------
    // The suspension has to actually suspend

    @Test
    @DisplayName("a suspension ends the subject's live sessions, on the very next request")
    void aSuspensionEndsLiveSessions() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId subject = givenAnIdentity();
        String victimSession = givenASessionFor(subject);

        assertThat(get("/sessions", victimSession).statusCode())
                .as("precondition: the session works before the suspension")
                .isEqualTo(200);

        assertThat(post(suspensionOf(subject), givenASessionFor(admin), REASON).statusCode())
                .isEqualTo(204);

        // The SAME token. Issuing a new one would prove only that a suspended identity cannot log
        // in, which CredentialVerifier already enforced and which is not what suspension is for.
        assertThat(get("/sessions", victimSession).statusCode())
                .as("a suspended identity that keeps a live session is suspended in name only")
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // A first role ends what the customer held (P15-TSK-043)

    @Test
    @DisplayName("P15-TSK-043: a first role ends every session the grantee held - the stepped-up one included - on the very"
            + " next request, the grant's record saying so; a session issued after it serves")
    void aFirstRoleEndsTheGranteesSessions() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId subject = givenAnIdentity();
        String asACustomer = givenAPasswordSessionFor(subject);
        String steppedUp = givenASessionAt(subject, AssuranceLevel.MULTI_FACTOR);
        assertThat(get("/sessions", asACustomer).statusCode()).as("precondition: the customer's session serves").isEqualTo(200);
        assertThat(get("/sessions", steppedUp).statusCode()).as("precondition: so does the stepped-up one").isEqualTo(200);

        assertThat(post(rolesOf(subject), givenASessionFor(admin), reviewerRole()).statusCode()).isEqualTo(204);

        // The SAME tokens, as the suspension's test reads them: a session issued before the grant is a customer's, and a
        // MULTI_FACTOR one stepped up from the internet would otherwise reach every MULTI_FACTOR operator route.
        assertThat(get("/sessions", asACustomer).statusCode()).as("the customer's session ended at the grant").isEqualTo(401);
        assertThat(get("/sessions", steppedUp).statusCode()).as("the stepped-up session ended at the grant").isEqualTo(401);
        assertThat(summariesOf("identity.RoleAssigned", subject, admin)).contains("firstRole=true, sessionsRevoked=2");
        assertThat(summariesOf("identity.SessionRevoked", subject, admin)).as("the administrator decided it").contains("sessionsRevoked=2");
        assertThat(get("/sessions", givenASessionFor(subject)).statusCode()).as("staff sign in again, and that session serves")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("P15-TSK-043: a second role ends nothing - its holder's sessions were issued at doors that knew it was staff;"
            + " a retried first grant ends nothing more")
    void aSecondRoleEndsNoSession() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String adminSession = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();
        assertThat(post(rolesOf(subject), adminSession, reviewerRole()).statusCode()).isEqualTo(204);
        String asStaff = givenASessionFor(subject);

        assertThat(post(rolesOf(subject), adminSession, "{\"role\":\"ACCOUNTANT\",\"reason\":\"OPS-6203 finance desk\"}")
                .statusCode()).isEqualTo(204);
        assertThat(post(rolesOf(subject), adminSession, reviewerRole()).statusCode()).as("a retry after a lost answer").isEqualTo(204);

        assertThat(get("/sessions", asStaff).statusCode()).as("a second role and a retry end nothing").isEqualTo(200);
        assertThat(summariesOf("identity.RoleAssigned", subject, admin)).contains("granted=ACCOUNTANT, firstRole=false")
                .contains("granted=KYC_REVIEWER, firstRole=true, sessionsRevoked=0");
    }

    // -----------------------------------------------------------------
    // A role taken away, and the last one ending what the holder held (P15-TSK-032)

    @Test
    @DisplayName("P15-TSK-032: the last role revoked ends every session its former holder held, on the very next request, both"
            + " records written; a retry answers the same and records and ends nothing more; a session issued after serves")
    void theLastRoleRevokedEndsTheSessions() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String steppedUp = givenASessionAt(admin, AssuranceLevel.MULTI_FACTOR);
        IdentityId subject = givenAnIdentity();
        assertThat(post(rolesOf(subject), givenASessionFor(admin), reviewerRole()).statusCode()).isEqualTo(204);
        String asStaff = givenASessionAt(subject, AssuranceLevel.MULTI_FACTOR);
        assertThat(get("/sessions", asStaff).statusCode()).as("precondition: the staff session serves").isEqualTo(200);

        assertThat(delete(roleOf(subject, "KYC_REVIEWER"), steppedUp, REVOCATION_REASON).statusCode()).isEqualTo(204);

        // The SAME token: issued at a door that knew the identity was staff, and not bound to the surface that issued it.
        assertThat(get("/sessions", asStaff).statusCode()).as("the staff session ended at the last role's revocation").isEqualTo(401);
        assertThat(liveHoldingsOf(subject)).isZero();
        assertThat(auditOf("identity.RoleAssigned", subject, admin)).as("the reason on the record").contains("OPS-8812");
        assertThat(summariesOf("identity.RoleAssigned", subject, admin))
                .contains("revoked=KYC_REVIEWER, lastRole=true, sessionsRevoked=1");
        assertThat(summariesOf("identity.SessionRevoked", subject, admin)).as("the administrator decided it")
                .contains("sessionsRevoked=1");

        long recorded = auditCountOf("identity.RoleAssigned", subject);
        String asACustomer = givenASessionFor(subject);
        assertThat(delete(roleOf(subject, "KYC_REVIEWER"), steppedUp, REVOCATION_REASON).statusCode())
                .as("a retry after a lost answer must not look like a failure").isEqualTo(204);
        assertThat(auditCountOf("identity.RoleAssigned", subject)).as("and records nothing more").isEqualTo(recorded);
        assertThat(get("/sessions", asACustomer).statusCode()).as("nor ends the customer's session issued since").isEqualTo(200);
    }

    @Test
    @DisplayName("P15-TSK-032: a role revoked beside another ends nothing - the sessions serve what the identity still is")
    void aRoleRevokedBesideAnotherEndsNoSession() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String steppedUp = givenASessionAt(admin, AssuranceLevel.MULTI_FACTOR);
        IdentityId subject = givenAnIdentity();
        assertThat(post(rolesOf(subject), givenASessionFor(admin), reviewerRole()).statusCode()).isEqualTo(204);
        assertThat(post(rolesOf(subject), givenASessionFor(admin), "{\"role\":\"ACCOUNTANT\",\"reason\":\"OPS-6203 finance desk\"}")
                .statusCode()).isEqualTo(204);
        String asStaff = givenASessionFor(subject);

        assertThat(delete(roleOf(subject, "ACCOUNTANT"), steppedUp, REVOCATION_REASON).statusCode()).isEqualTo(204);

        assertThat(get("/sessions", asStaff).statusCode()).isEqualTo(200);
        assertThat(liveHoldingsOf(subject)).isEqualTo(1);
        assertThat(summariesOf("identity.RoleAssigned", subject, admin)).contains("revoked=ACCOUNTANT, lastRole=false");
    }

    @Test
    @DisplayName("P15-TSK-032: the revocation steps up, refuses oneself, names nobody 404 and an unknown role 400, refuses a caller"
            + " with no role, and leaves the role held through every refusal")
    void theRevocationsRefusals() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String steppedUp = givenASessionAt(admin, AssuranceLevel.MULTI_FACTOR);
        IdentityId subject = givenAnIdentity();
        assertThat(post(rolesOf(subject), givenASessionFor(admin), reviewerRole()).statusCode()).isEqualTo(204);

        String onAPassword = givenAPasswordSessionFor(admin);
        HttpResponse<String> revocationOnAPassword = delete(roleOf(subject, "KYC_REVIEWER"), onAPassword, REVOCATION_REASON);
        assertThat(revocationOnAPassword.statusCode()).as("not a stolen password's act").isEqualTo(403);
        assertThat(revocationOnAPassword.body()).contains("identity.AssuranceRequired");
        // Item 42 (e), decided: nor a grant, a suspension or a reinstatement.
        assertThat(post(rolesOf(subject), onAPassword, "{\"role\":\"ACCOUNTANT\",\"reason\":\"OPS-6203 finance desk\"}").body())
                .contains("identity.AssuranceRequired");
        assertThat(post(suspensionOf(subject), onAPassword, REASON).body()).contains("identity.AssuranceRequired");
        assertThat(delete(suspensionOf(subject), onAPassword, REINSTATEMENT_REASON).body()).contains("identity.AssuranceRequired");
        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
        assertThat(delete(roleOf(admin, "ADMINISTRATOR"), steppedUp, REVOCATION_REASON).statusCode())
                .as("every administrative record names two parties").isEqualTo(422);
        assertThat(delete(roleOf(IdentityId.of(IDS.next()), "KYC_REVIEWER"), steppedUp, REVOCATION_REASON).statusCode())
                .isEqualTo(404);
        HttpResponse<String> unknownRole = delete("/identities/" + subject.value() + "/roles/SUPERUSER", steppedUp, REVOCATION_REASON);
        assertThat(unknownRole.statusCode()).isEqualTo(400);
        assertThat(unknownRole.body()).doesNotContain("api.InternalError");
        assertThat(delete(roleOf(subject, "KYC_REVIEWER"), steppedUp, "{}").statusCode()).as("a reason is required").isEqualTo(422);

        IdentityId caller = givenAnIdentity();
        long before = denialsFor(caller);
        assertThat(delete(roleOf(subject, "KYC_REVIEWER"), givenASessionAt(caller, AssuranceLevel.MULTI_FACTOR), REVOCATION_REASON)
                .statusCode()).as("a valid session is not a role").isEqualTo(403);
        assertThat(denialsFor(caller) - before).isEqualTo(1);

        assertThat(liveHoldingsOf(subject)).isEqualTo(1);
        assertThat(liveHoldingsOf(admin)).isEqualTo(1);
    }

    /**
     * Two revocations of one identity's last two roles at once, through the domain on two connections: the identity's lock makes
     * the second wait and read the first's revocation, so the sessions end exactly once - by the second, the one that took the
     * last role - and the records say so. Without the lock each would read the other's role still live and neither would end
     * them. Deterministic, as {@link #aGrantAndARevocationOfOneIdentitySerialise} is: the first is held open after its
     * revocation, the second is seen waiting behind it, and only then does the first commit - so the two always overlap.
     */
    @Test
    @DisplayName("P15-TSK-032: two revocations of an identity's last two roles at once - both land, and the sessions end once, by the"
            + " one that took the last")
    void twoRevocationsOfTheLastTwoRolesEndTheSessionsOnce() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId subject = givenAnIdentity();
        grantBypassingTheRule(subject, admin, RoleName.KYC_REVIEWER);
        grantBypassingTheRule(subject, admin, RoleName.ACCOUNTANT);
        String asStaff = givenASessionFor(subject);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enter(
                new com.finapp.platform.security.Actor(admin.value().toString(), com.finapp.platform.security.ActorType.EMPLOYEE));
        try (Connection first = DatabaseRoles.application()) {
            first.setAutoCommit(false);
            assertThat(administration.revokeRole(first, subject, RoleName.KYC_REVIEWER, admin, "the incident closed, OPS-8812"))
                    .isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);

            java.util.concurrent.Future<IdentityAdministration.RoleRevocation> second =
                    pool.submit(() -> revokeAsTheDoorDoes(null, subject, RoleName.ACCOUNTANT, admin));
            awaitBlockedBehind(first, second);
            first.commit();

            assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);
        } finally {
            actor.close();
            correlation.close();
            pool.shutdownNow();
        }

        assertThat(liveHoldingsOf(subject)).isZero();
        assertThat(get("/sessions", asStaff).statusCode()).isEqualTo(401);
        String summaries = summariesOf("identity.RoleAssigned", subject, admin);
        assertThat(summaries).containsOnlyOnce("lastRole=true, sessionsRevoked=1").containsOnlyOnce("lastRole=false");
    }

    /**
     * A grant and a revocation of one identity at once (`P15-TSK-032`): the grant holds the identity's lock, uncommitted; the
     * revocation of the identity's other role waits for it, then reads the granted role and so ends nothing - the identity is
     * staff throughout. The store's half is {@code RoleAssignmentConcurrencyDatabaseTest#theIdentitysLockSerialisesItsRoleChanges}.
     */
    @Test
    @DisplayName("P15-TSK-032: a revocation racing a grant to the same identity waits for the grant, reads its role, and ends nothing")
    void aGrantAndARevocationOfOneIdentitySerialise() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId subject = givenAnIdentity();
        grantBypassingTheRule(subject, admin, RoleName.KYC_REVIEWER);
        String asStaff = givenASessionFor(subject);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enter(
                new com.finapp.platform.security.Actor(admin.value().toString(), com.finapp.platform.security.ActorType.EMPLOYEE));
        try (Connection granting = DatabaseRoles.application()) {
            granting.setAutoCommit(false);
            assertThat(administration.assignRole(granting, subject, RoleName.ACCOUNTANT, admin, "OPS-6203 finance desk"))
                    .isEqualTo(IdentityAdministration.RoleGrant.GRANTED);

            java.util.concurrent.Future<IdentityAdministration.RoleRevocation> revoking =
                    pool.submit(() -> revokeAsTheDoorDoes(null, subject, RoleName.KYC_REVIEWER, admin));
            awaitBlockedBehind(granting, revoking);
            granting.commit();

            assertThat(revoking.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);
        } finally {
            actor.close();
            correlation.close();
            pool.shutdownNow();
        }

        assertThat(summariesOf("identity.RoleAssigned", subject, admin))
                .contains("granted=ACCOUNTANT, firstRole=false").contains("revoked=KYC_REVIEWER, lastRole=false");
        assertThat(get("/sessions", asStaff).statusCode()).as("staff throughout: nothing ended").isEqualTo(200);
        assertThat(liveHoldingsOf(subject)).isEqualTo(1);
    }

    /** The door's domain call on a connection of its own, under the scopes the door runs in, committed. */
    private IdentityAdministration.RoleRevocation revokeAsTheDoorDoes(
            java.util.concurrent.CyclicBarrier start, IdentityId subject, RoleName role, IdentityId admin) throws Exception {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enter(
                new com.finapp.platform.security.Actor(admin.value().toString(), com.finapp.platform.security.ActorType.EMPLOYEE));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            if (start != null) {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            IdentityAdministration.RoleRevocation outcome =
                    administration.revokeRole(app, subject, role, admin, "the incident closed, OPS-8812");
            app.commit();
            return outcome;
        } finally {
            actor.close();
            correlation.close();
        }
    }

    /** A session active and waiting on a lock {@code holder}'s session holds, awaited about 30 s. */
    private static void awaitBlockedBehind(Connection holder, java.util.concurrent.Future<?> racer) throws Exception {
        int blocker;
        try (PreparedStatement select = holder.prepareStatement("SELECT pg_backend_pid()");
                ResultSet row = select.executeQuery()) {
            row.next();
            blocker = row.getInt(1);
        }
        for (int attempt = 0; attempt < 1500; attempt++) {
            if (racer.isDone()) {
                throw new AssertionError("the racer finished while the holder held its lock: " + racer.get());
            }
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                            + " WHERE ? = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= 1) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no session waiting on a lock behind session " + blocker + " within about 30 s");
    }

    private static String roleOf(IdentityId subject, String role) {
        return "/identities/" + subject.value() + "/roles/" + role;
    }

    // -----------------------------------------------------------------
    // Everything else

    @Test
    @DisplayName("suspending an identity that is not ACTIVE is a conflict, not a second suspension")
    void aSecondSuspensionIsAConflict() throws Exception {
        IdentityId admin = givenAnAdministrator();
        String session = givenASessionFor(admin);
        IdentityId subject = givenAnIdentity();

        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(204);
        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(409);

        assertThat(auditCountOf("identity.IdentitySuspended", subject))
                .as("one transition, one record: a second would describe something that did not"
                        + " happen")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a CLOSED identity is refused too, and the outcome does not claim it was suspended")
    void aClosedIdentityCannotBeSuspended() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();
        closeIdentity(subject);

        // The same 409 as an already-suspended one, and that is right: both mean "not ACTIVE". What
        // the completion gate found was the OUTCOME NAME - ALREADY_SUSPENDED is a claim that is
        // false here, since a CLOSED identity is gone permanently rather than suspended, and a
        // caller reading it would conclude the operation had effectively succeeded.
        assertThat(post(suspensionOf(subject), session, REASON).statusCode()).isEqualTo(409);
        assertThat(statusOf(subject)).isEqualTo("CLOSED");
        assertThat(auditCountOf("identity.IdentitySuspended", subject))
                .as("a refused transition writes no record of a transition")
                .isZero();
    }

    @Test
    @DisplayName("an unknown or malformed identity is one answer, and it creates nothing")
    void anUnknownSubjectIs404() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());

        // IDS.next(), not UUID.randomUUID(). IdentityId.of validates UUIDv7 (P0-TSK-012), so a v4
        // is refused as MALFORMED and never reaches the service - which is a different 404 for a
        // different reason. The first version of this test used randomUUID and passed for exactly
        // that wrong reason; a mutation removing assignRole's existence check survived it, which is
        // how the substitution was found.
        assertThat(post("/identities/" + IDS.next() + "/suspension", session, REASON).statusCode())
                .as("well-formed, and names nobody")
                .isEqualTo(404);
        assertThat(post("/identities/not-a-uuid/suspension", session, REASON).statusCode())
                .isEqualTo(404);

        // Role assignment too, and this half was added because a mutation survived without it:
        // removing the existence check from assignRole broke nothing, because only suspension was
        // covered. Without the check the assignment reaches the foreign key on
        // identity.role_assignment and arrives as a storage exception rendered api.InternalError -
        // our fault reported for a caller's typo (ERROR_CONTRACT.md section 3), retryable for ever
        // on a request that can never succeed. The javadoc on assignRole predicted exactly that
        // and no test held it.
        assertThat(post("/identities/" + IDS.next() + "/roles", session, adminRole()).statusCode())
                .isEqualTo(404);

        // And reinstatement answers the same way (P1-TSK-032).
        assertThat(
                        delete(
                                        "/identities/" + IDS.next() + "/suspension",
                                        session,
                                        REINSTATEMENT_REASON)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        delete("/identities/not-a-uuid/suspension", session, REINSTATEMENT_REASON)
                                .statusCode())
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------
    // Reinstatement (P1-TSK-032)

    /**
     * The acceptance criterion end to end: <em>a suspended identity can authenticate again
     * afterwards</em> — so the subject is registered over HTTP rather than inserted, because a
     * fixture row without a credential cannot authenticate at all and the assertion would be
     * unreachable.
     */
    @Test
    @DisplayName("a reinstated person can authenticate again, and the old sessions stay dead")
    void aReinstatedIdentityCanAuthenticateAgain() throws Exception {
        String login = "ada." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(registerPerson(login).statusCode()).isEqualTo(201);
        String preSuspension = tokenIn(authenticate(login).body());
        assertThat(get("/sessions", preSuspension).statusCode())
                .as("precondition: the person is logged in before the suspension")
                .isEqualTo(200);

        IdentityId subject = identityIdOf(login);
        IdentityId admin = givenAnAdministrator();
        String adminSession = givenASessionFor(admin);

        assertThat(post(suspensionOf(subject), adminSession, REASON).statusCode()).isEqualTo(204);
        assertThat(authenticate(login).statusCode())
                .as("precondition: a suspended identity cannot log in")
                .isEqualTo(401);

        assertThat(
                        delete(suspensionOf(subject), adminSession, REINSTATEMENT_REASON)
                                .statusCode())
                .isEqualTo(204);
        assertThat(statusOf(subject)).isEqualTo("ACTIVE");

        // The acceptance: the person can authenticate again.
        assertThat(authenticate(login).statusCode()).isEqualTo(201);

        // And the sessions the suspension revoked STAY revoked. The revocations happened and
        // INV-HIST-01 does not un-happen things - a resurrected bearer token would come back to
        // life in whoever's hands last held it, possibly the attacker whose activity is why the
        // account was suspended in the first place.
        assertThat(get("/sessions", preSuspension).statusCode())
                .as("reinstatement restores the ability to log in, never the old sessions")
                .isEqualTo(401);

        // Audited against the administrator, naming the subject and carrying the reason.
        assertThat(auditOf("identity.IdentityReinstated", subject, admin))
                .as("the trail's answer to WHY this person was let back in")
                .contains("OPS-9921");
    }

    @Test
    @DisplayName(
            "reinstating an identity that is not SUSPENDED is a conflict, and CLOSED stays closed")
    void reinstatingANonSuspendedIdentityIsAConflict() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());

        IdentityId active = givenAnIdentity();
        assertThat(delete(suspensionOf(active), session, REINSTATEMENT_REASON).statusCode())
                .as("ACTIVE has no suspension to lift, and reporting success would claim a"
                        + " transition that did not happen")
                .isEqualTo(409);

        // The half that matters: CLOSED is terminal (INV-LIFE-04) and must not come back through
        // this door. The conditional moveStatus(from = SUSPENDED) refuses it at the write; the
        // aggregate's own transition check refuses it independently (INV-LIFE-02).
        IdentityId closed = givenAnIdentity();
        closeIdentity(closed);
        assertThat(delete(suspensionOf(closed), session, REINSTATEMENT_REASON).statusCode())
                .isEqualTo(409);
        assertThat(statusOf(closed)).isEqualTo("CLOSED");
        assertThat(auditCountOf("identity.IdentityReinstated", closed))
                .as("a refused transition writes no record of a transition")
                .isZero();
    }

    @Test
    @DisplayName(
            "the reinstatement endpoint refuses a session with no role, and the refusal is audited")
    void reinstatementRefusesWithoutTheRole() throws Exception {
        IdentityId caller = givenAnIdentity();
        String session = givenASessionFor(caller);
        IdentityId subject = givenAnIdentity();

        long before = denialsFor(caller);
        assertThat(delete(suspensionOf(subject), session, REINSTATEMENT_REASON).statusCode())
                .as("a valid session is not a role")
                .isEqualTo(403);
        // INV-AUD-03: a refused privileged attempt is the only durable trace a prober leaves.
        assertThat(denialsFor(caller) - before).isEqualTo(1);
    }

    /**
     * Driven at the domain rather than over HTTP, because HTTP cannot reach it: a suspended
     * identity holds no live session — the suspension revoked them — so a person cannot present a
     * session while being {@code SUSPENDED}, except in the race where they are suspended
     * mid-request. The branch exists for that race and for the property the whole class protects:
     * no administrative record ever names one party twice.
     */
    @Test
    @DisplayName("an administrator cannot reinstate themselves, checked at the domain")
    void anAdministratorCannotReinstateThemselves() throws Exception {
        IdentityId admin = givenAnAdministrator();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(administration.reinstate(app, admin, admin, "self"))
                    .isEqualTo(IdentityAdministration.Reinstatement.SELF);
        }
    }

    @Test
    @DisplayName("a missing reason is a 422 naming the field, never a 500")
    void aMissingReasonIsRefusedAtTheBoundary() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();

        HttpResponse<String> response = post(suspensionOf(subject), session, "{}");

        // AuditRecord refuses construction without a reason for this action, so an absent one could
        // never reach the table - but it would arrive as api.InternalError, our fault reported for
        // the caller's omission (ERROR_CONTRACT.md section 3).
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed").contains("reason");
        assertThat(statusOf(subject)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an unknown role is refused at the boundary, not by valueOf three layers down")
    void anUnknownRoleIsRefused() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();

        HttpResponse<String> response =
                post(rolesOf(subject), session, "{\"role\":\"SUPERUSER\",\"reason\":\"x\"}");

        assertThat(response.statusCode()).isIn(400, 422);
        assertThat(response.body()).doesNotContain("api.InternalError");
    }

    @Test
    @DisplayName("granting a role the identity already holds is success, not a conflict")
    void aRepeatedGrantIsSuccess() throws Exception {
        String session = givenASessionFor(givenAnAdministrator());
        IdentityId subject = givenAnIdentity();
        givenAConfirmedFactorFor(subject); // ADMINISTRATOR needs one (P15-TSK-032)

        assertThat(post(rolesOf(subject), session, adminRole()).statusCode()).isEqualTo(204);
        assertThat(post(rolesOf(subject), session, adminRole()).statusCode())
                .as("a retry after a lost response must not look like a failure")
                .isEqualTo(204);
    }

    // -----------------------------------------------------------------

    private static String suspensionOf(IdentityId subject) {
        return "/identities/" + subject.value() + "/suspension";
    }

    private static String rolesOf(IdentityId subject) {
        return "/identities/" + subject.value() + "/roles";
    }

    @Test
    @DisplayName("a role that can redirect a merchant's money is refused 409 without a factor, nothing granted, the refusal"
            + " on the record; granted once a factor is confirmed (P15-TSK-039)")
    void aMoneyRedirectingRoleNeedsAFactor() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId subject = givenAnIdentity();
        String session = givenASessionFor(admin);

        HttpResponse<String> refused = post(rolesOf(subject), session, ledgerOperatorRole());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("identity.FactorRequiredForRole");
        assertThat(liveHoldingsOf(subject)).isZero();
        assertThat(auditOf("identity.RoleAssigned", subject, admin)).as("no grant was recorded").isEmpty();
        assertThat(doorRecordsOf(admin, "identity.FactorRequiredForRole"))
                .as("the refused grant is on the record, in its own transaction")
                .isEqualTo(1);

        givenAConfirmedFactorFor(subject);
        assertThat(post(rolesOf(subject), session, ledgerOperatorRole()).statusCode()).isEqualTo(204);
        assertThat(liveHoldingsOf(subject)).isEqualTo(1);
    }

    @Test
    @DisplayName("the check names a standing holding with no factor, and stops naming it once the holder enrols (P15-TSK-039)")
    void theCheckNamesStandingHoldings() throws Exception {
        IdentityId admin = givenAnAdministrator();
        IdentityId standing = givenAnIdentity();
        grantBypassingTheRule(standing, admin, RoleName.LEDGER_OPERATOR);
        IdentityId notNamed = givenAnIdentity();
        grantBypassingTheRule(notNamed, admin, RoleName.KYC_REVIEWER);
        String session = givenASessionAt(admin, AssuranceLevel.MULTI_FACTOR);

        assertThat(get("/operator/access-review/factorless-holdings", givenAPasswordSessionFor(admin)).statusCode())
                .as("a list of money-role holders with no second factor is not served on a password alone")
                .isEqualTo(403);
        String before = get("/operator/access-review/factorless-holdings", session).body();
        assertThat(before).contains(standing.value().toString()).contains("LEDGER_OPERATOR");
        assertThat(before).doesNotContain(notNamed.value().toString());

        givenAConfirmedFactorFor(standing);
        assertThat(get("/operator/access-review/factorless-holdings", session).body())
                .doesNotContain(standing.value().toString());
    }

    private static long liveHoldingsOf(IdentityId subject) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(*) FROM identity.role_assignment WHERE identity_id = ? AND revoked_at IS NULL")) {
            count.setObject(1, subject.value());
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long doorRecordsOf(IdentityId actor, String code) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = 'door.ActRefused' AND actor_id = ?"
                                + " AND outcome = 'DENIED' AND change_summary LIKE ?")) {
            count.setString(1, actor.value().toString());
            count.setString(2, "code=" + code + ",%");
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** A holding that predates the rule: written around the door, as every such row was. */
    private void grantBypassingTheRule(IdentityId subject, IdentityId grantor, RoleName role) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, subject, role, grantor, "standing since before the rule");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
    }

    /** A confirmed factor, written directly: this suite is about the grant, not enrolment. */
    private static void givenAConfirmedFactorFor(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert = app.prepareStatement(
                        "INSERT INTO identity.mfa_enrolment (id, identity_id, type, secret_ciphertext, secret_nonce, key_version,"
                                + " algorithm, digits, period_seconds, status, created_at, confirmed_at)"
                                + " VALUES (?, ?, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE', now(), now())")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, identity.value());
            insert.setBytes(3, new byte[32]); // never decrypted: V008 sizes it, 16 to 1024
            insert.setBytes(4, new byte[12]); // a 96-bit nonce, per V008
            insert.executeUpdate();
        }
    }

    private static String adminRole() {
        return "{\"role\":\"ADMINISTRATOR\",\"reason\":\"offboarding, ticket OPS-4417\"}";
    }

    private static String reviewerRole() {
        return "{\"role\":\"KYC_REVIEWER\",\"reason\":\"reviewer onboarding, ticket OPS-5510\"}";
    }

    private static String ledgerOperatorRole() {
        return "{\"role\":\"LEDGER_OPERATOR\",\"reason\":\"operator onboarding, ticket OPS-7003\"}";
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token, String body) throws Exception {
        // A DELETE with a body: the reason is free prose and must not travel in a URL, which
        // reaches access logs (INV-AUD-02). See ReinstatementRequest.
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> registerPerson(String login) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/registrations"))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\""
                                                + login
                                                + "\",\"displayName\":\"Ada Lovelace\","
                                                + "\"password\":\""
                                                + PASSWORD
                                                + "\"}"))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/authentications",
                null,
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private static String tokenIn(String body) {
        String marker = "\"sessionToken\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        return body.substring(start, body.indexOf('\"', start));
    }

    private static IdentityId identityIdOf(String login) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            select.setString(1, login);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return IdentityId.of((UUID) rows.getObject(1));
            }
        }
    }

    private static void closeIdentity(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.identity SET status = 'CLOSED', status_changed_at = now()"
                            + " WHERE id = ?",
                    identity.value());
        }
    }

    private static String statusOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static String auditOf(String operation, IdentityId target, IdentityId actor)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(reason || ' ' ||"
                                        + " coalesce(change_summary, ''), ' '), '')"
                                        + " FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, target.value().toString());
            select.setString(3, actor.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /**
     * Every change summary of {@code operation} on {@code target} by {@code actor} (`P15-TSK-043`): {@link #auditOf} joins the
     * reason, which is null on a record that needs none, and a null reason empties its row.
     */
    private static String summariesOf(String operation, IdentityId target, IdentityId actor) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT coalesce(string_agg(change_summary, ' '), '') FROM platform.audit_record"
                                + " WHERE operation = ? AND target_id = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, target.value().toString());
            select.setString(3, actor.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long auditCountOf(String operation, IdentityId target) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ?")) {
            select.setString(1, operation);
            select.setString(2, target.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long denialsFor(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'identity.AuthorizationDenied'"
                                        + " AND actor_id = ?")) {
            count.setString(1, identity.value().toString());
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * The first administrator, created out of band.
     *
     * <p>There is no API route to this state and that is a decision rather than a gap:
     * {@code ROLE_ASSIGN} is held only by {@code ADMINISTRATOR}, so the first assignment cannot come
     * through the endpoint that requires it. An operator with database access performs it, and the
     * consequence is recorded — that first grant has <strong>no actor in the audit trail</strong>,
     * because no authenticated actor performed it.
     */
    private IdentityId givenAnAdministrator() throws SQLException {
        IdentityId admin = givenAnIdentity();
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, admin, RoleName.ADMINISTRATOR, admin, "bootstrap");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
        return admin;
    }

    /**
     * A {@code MULTI_FACTOR} session (`P15-TSK-032`): every administrative door here steps up since item 42 (e), so a session
     * that should reach a door's permission check - or be refused by it - has stepped up. A customer's plain session, and one
     * a step-up refuses, is {@link #givenAPasswordSessionFor}'s.
     */
    private String givenASessionFor(IdentityId identity) throws SQLException {
        return givenASessionAt(identity, AssuranceLevel.MULTI_FACTOR);
    }

    private String givenAPasswordSessionFor(IdentityId identity) throws SQLException {
        return givenASessionAt(identity, AssuranceLevel.PASSWORD);
    }

    private String givenASessionAt(IdentityId identity, AssuranceLevel assurance) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session.Draft session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        assurance,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
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
                    // Back-dated deliberately, not written at now().
                    //
                    // `identity_status_change_is_not_before_creation` compares this row's
                    // created_at against the status_changed_at a suspension writes - and the two
                    // come from DIFFERENT CLOCKS: this fixture uses the container's, while
                    // production uses the injected JVM Clock. CURRENT_STATE.md records that the
                    // local container's clock runs fast and is corrected backwards, so `now()` can
                    // be ahead of the JVM by hundreds of milliseconds and the constraint fires on a
                    // suspension that is perfectly correct.
                    //
                    // The constraint is right and the fixture was fragile - P1-TSK-031's finding,
                    // met here for the first time by production code rather than by another
                    // fixture. Back-dating is the established remedy (OutboxRelayTest.backDate).
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
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
