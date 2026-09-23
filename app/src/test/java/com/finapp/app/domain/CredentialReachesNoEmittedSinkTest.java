package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RawPassword;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sinks a credential could reach that are not a log line (`P1-TSK-009`).
 *
 * <h2>Three sinks whose credential-carrying producer does not exist yet</h2>
 *
 * <p>{@code P1-TST-001} names five sinks. Only the log has a credential-carrying producer today:
 * no event carries a credential ({@code P1-TSK-010} owns the authentication events), no endpoint
 * takes or returns one ({@code P1-TSK-010} and {@code P1-TSK-026}), and neither a span nor a
 * metric touches one.
 *
 * <p><strong>That is a reason to write the guard now, not a reason to defer it</strong> - but the
 * artefact has to be the right one. An assertion that a credential is absent from an empty event
 * stream passes vacuously and proves nothing, which is the "green while checking nothing" failure
 * this repository has met six times. So for a sink with no producer this asserts the
 * <em>mechanism that will refuse the producer</em>, which is a claim with a subject today and is
 * load-bearing the moment the producer arrives.
 *
 * <h2>The span and metric sinks are cited, not duplicated</h2>
 *
 * <p>{@code MetricConventionTest} already fails the build on a meter tag whose value a request
 * could influence, and ADR-0017 keeps statement text off spans for the same reason. Restating
 * either here would be a second copy that drifts while looking authoritative - the reason
 * {@code API_CONVENTIONS.md} references the error-code catalogue rather than pasting it.
 */
@DisplayName("a credential reaches no emitted sink (P1-TSK-009)")
class CredentialReachesNoEmittedSinkTest {

    private static final String PASSWORD = "zqx-sink-marker-9c15b8-never-emitted";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static final Argon2PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    /**
     * Words that mean "this is a secret", in the vocabulary ADR-0019 settled.
     *
     * <p>A <strong>superset</strong> of {@code secretsAreWrapped}'s, reconciled against it by test
     * so the two cannot drift - {@code signingkey} and {@code cvv2} had already gone missing when
     * the completion gate compared them. It adds {@code derivation}, which the field rule has no
     * reason to carry: a derivation is not a secret a <em>field</em> should hold unwrapped, it is a
     * value that must never be <em>published</em>.
     *
     * <p>{@code key} is absent, for ADR-0019's stated reason: an idempotency key is not a secret,
     * it is recorded in audit on purpose, and a rule with false positives is a rule somebody turns
     * off.
     */
    private static final Set<String> SECRET_WORDS =
            Set.of(
                    "password", "passwd", "passphrase",
                    "secret", "apikey", "privatekey", "secretkey", "signingkey",
                    "credential", "credentials", "derivation",
                    "token", "bearer", "authorization",
                    "pan", "cardnumber", "cvv", "cvc", "cvv2", "pin",
                    "otp", "mfacode", "sessionid");

    @Test
    @DisplayName("this vocabulary does not drift from the one the build rule enforces")
    @SuppressWarnings("unchecked")
    void theVocabularyIsReconciledWithTheRule() throws Exception {
        // A second copy of a security vocabulary is drift waiting to happen, and this one had
        // ALREADY drifted when the completion gate compared them: `signingkey` and `cvv2` were in
        // the rule's list and missing here, so a `signingKey` property could have reached the
        // published contract while the build rule forbade the field that would hold it.
        //
        // Reconciled rather than merged, which is this repository's established answer to the same
        // shape (AuditableActionRegistryTest, ApiConventionsAreAccurateTest): the two lists stay
        // where they are, and disagreeing fails the build. Merging them would put ADR-0019's
        // vocabulary in a shared helper and make the rule's own list a redirection, which is worse
        // reading for the rule that matters most.
        java.lang.reflect.Field words =
                Class.forName("com.finapp.app.architecture.NoUnwrappedSecretRulesTest")
                        .getDeclaredField("SECRET_WORDS");
        words.setAccessible(true);
        Set<String> enforcedByTheRule = (Set<String>) words.get(null);

        assertThat(enforcedByTheRule).as("precondition: the rule's vocabulary was read").isNotEmpty();
        assertThat(SECRET_WORDS)
                .as("every word the build rule treats as a secret must also be refused in the"
                        + " published contract; this list may be wider, never narrower")
                .containsAll(enforcedByTheRule);
    }

    // -----------------------------------------------------------------
    // The event sink.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an event payload cannot carry a derivation at all")
    void anEventPayloadRefusesADerivation() {
        // Argon2's encoded form is $argon2id$v=19$m=...,t=...,p=...$<salt>$<hash> - it contains
        // '$', '=', ',' and base64's '+' and '/', none of which EventPayload permits.
        //
        // That is true TODAY, and it is true by coincidence of a rule written for a different
        // reason: EventPayload's charset exists to keep names and login identifiers out. Coincidental
        // safety is the shape P0-TSK-030 found with Jackson - correct until somebody changes the
        // shape, and silent when they do. Asserting it turns the coincidence into a stated property
        // with a failing test behind it.
        String derivation = derivedCredential().credentialDerivation().expose();

        assertThat(derivation).as("precondition: this really is the encoded form").startsWith("$argon2id$");
        assertThat(catchThrowable(() -> EventPayload.of().with("derivation", derivation)))
                .as("a derivation is offline-crackable material; an event stream is not the place")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the refusal does not repeat the derivation it refused")
    void theRefusalDoesNotEchoTheDerivation() {
        String derivation = derivedCredential().credentialDerivation().expose();

        assertThat(catchThrowable(() -> EventPayload.of().with("derivation", derivation)))
                .hasMessageNotContaining(derivation);
    }

    @Test
    @DisplayName("the limit, stated: a punctuation-free password WOULD be accepted")
    void theEventPayloadIsNotACredentialFilter() {
        // Recorded rather than glossed. EventPayload is a charset, not a secret detector: a password
        // of "hunter2" satisfies [A-Za-z0-9_-] perfectly and would be published.
        //
        // So what actually keeps credentials out of events is that no event declares a credential
        // field - a P1-TSK-010 design property, not this one. Asserting the opposite here would
        // overclaim, and a suite believed to cover more than it does is worse than one that covers
        // less: the second gets a second control, the first does not.
        assertThatCode(() -> EventPayload.of().with("probe", "hunter2"))
                .as("this passing is the limit being true, not the guard being broken")
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------
    // The response sink.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a secret appears in the published contract only where a caller must send one")
    void thePublishedContractCarriesASecretOnlyInARequestBody() {
        // This guard was written by `P1-TSK-009` with no subject, and `P1-TSK-010` gave it one
        // the very next task - which is what it was for. It then FIRED, and it was right to fire
        // and wrong about why: it forbade a credential-named member anywhere, and an
        // authentication request body must declare a password or no client can call the endpoint.
        //
        // The precise property is narrower and more useful: a secret may be SENT and must never be
        // RETURNED or put where a URL goes. A password in a response schema is a password in a
        // client's memory and logs; in a query parameter or a header it is in every access log,
        // proxy log and browser history between here and the caller. A request body is the one
        // place it legitimately appears, and it is the place this platform already protects - the
        // idempotency fingerprint deliberately excludes it (`P1-TSK-006`), and `Sensitive` wraps it
        // the moment it is deserialised (`P1-TSK-010`).
        String contract = readRepositoryFile("docs/api/openapi.json");

        assertThat(contract)
                .as("precondition: the document must be there to inspect")
                .contains("\"openapi\"");

        assertThat(secretNamedMembersOutsideRequestBodiesIn(contract))
                .as(
                        "a secret may be sent, never returned and never put in a URL or a header:"
                            + " those reach access logs, proxies and browser history (INV-AUD-02)")
                .isEmpty();
    }

    @Test
    @DisplayName("the exemption is exactly one request body, not a hole")
    void theRequestBodyExemptionIsBounded() {
        // Without this the exemption could quietly widen: every schema in the document is
        // reachable from SOME request body once there are enough endpoints, and the guard would
        // then permit a secret anywhere while still looking like a control.
        String contract = readRepositoryFile("docs/api/openapi.json");

        assertThat(schemasReachableFromRequestBodies(contract))
                .as("the schemas a secret is permitted in, named so that widening is visible")
                .containsExactlyInAnyOrder(
                        "AuthenticationRequest",
                        // P1-TSK-026 changed what this entry MEANS without changing the entry, and
                        // that is worth recording rather than leaving as a silent reclassification.
                        // It was here because the set is every schema REACHABLE from a request
                        // body; it now carries a `password` as well. The set did not have to
                        // change, so nothing would have prompted a reader to notice - which is the
                        // "reads as reviewed and is not" shape this repository keeps meeting.
                        "RegistrationRequest",
                        // P1-TSK-017. Reachable from a request body, so it joins the set the moment
                        // it exists - which is the guard working rather than a hole opening. It
                        // carries a TOTP `code`, deliberately not named `otp`: a name in the
                        // vocabulary would demand a `Sensitive` wrapper, and a name chosen to avoid
                        // one would be dodging. A code is valid for ninety seconds against a single
                        // pending enrolment, which is why `code` is the honest name.
                        "MfaConfirmationRequest",
                        // P1-TSK-023. Four at once, because recovery needed a channel that did not
                        // exist - and each is a deliberate decision rather than a consequence.
                        //
                        // RecoveryCompletionRequest carries BOTH a token and a password, and the
                        // token is the sharper of the two: whoever holds it can replace the
                        // credential without knowing the old one, so it is a bearer credential with
                        // MORE power than the password it replaces. It is `Sensitive` for exactly
                        // that reason, and it is in a request body because there is nowhere else a
                        // client could put it - a URL would place it in every access log and proxy
                        // log between the mailbox and here.
                        "RecoveryCompletionRequest",
                        "ContactChannelVerificationRequest",
                        // These two carry no secret at all and are here because the set is every
                        // schema REACHABLE from a request body, not every schema containing one.
                        // Naming them is the price of the guard being unable to widen quietly, and
                        // the price is worth paying: an entry appearing without a reason is the
                        // signal.
                        //
                        // ContactChannelRequest carries an email address, which is RESTRICTED-PII
                        // rather than a secret - wrapped for the same reason, because a record's
                        // generated toString prints every component.
                        "ContactChannelRequest",
                        "RecoveryInitiationRequest",
                        // P1-TSK-028. Neither carries a secret. They are here for the same reason
                        // the two above are: the set is every schema REACHABLE from a request body,
                        // not every schema containing one, and naming them is the price of the
                        // guard being unable to widen quietly.
                        //
                        // Both carry a free-text `reason`, which is the one thing worth a second
                        // look - an administrator can type anything into it, including something
                        // they should not. That is a handling question about audit content rather
                        // than a leak of a platform secret, and what bounds it is that the field
                        // never reaches an event payload: IdentityAdministration.announce carries
                        // identifiers and an enumerated status, deliberately (INV-AUD-02).
                        "SuspensionRequest",
                        // P1-TSK-032: the mirror of SuspensionRequest - a reason, never a secret,
                        // and the same bounds cited from the same constants.
                        "ReinstatementRequest",
                        // P1-TSK-033: the body of POST /v1/me/credential carries the current and
                        // new passwords. A secret may be SENT - a request body is the one place it
                        // legitimately appears - never returned and never in a URL or header.
                        "ChangePasswordRequest",
                        "RoleAssignmentRequest",
                        // P2-TSK-012: the reviewer's resolution reason - free prose, never a
                        // secret, the SuspensionRequest shape with the same bounds cited from the
                        // same constants. What bounds the prose is that it reaches exactly two
                        // sinks, both designed for it: the audit record's reason column and
                        // review_task.resolution_reason (RESTRICTED-PII at its ceiling, rendered
                        // only on the reviewer surface). No event carries it.
                        "ResolutionRequest",
                        // P2-TSK-013: the decision's outcome and reason - an enumerated value
                        // and free prose, never a secret. The ResolutionRequest shape: the
                        // reason reaches exactly the audit record and kyc_decision.reason
                        // (RESTRICTED-PII at its ceiling), and no event carries it.
                        "DecisionRequest",
                        // P2-TSK-008. Carries no secret and no PII field by name - the document
                        // CONTENT is RESTRICTED-PII, and what protects it is not the secret
                        // vocabulary (a field named `content` is exactly the innocent name the
                        // vocabulary must not match) but the store: encrypted at rest, plaintext
                        // in no column, one audited read path (INV-KYC-06). Here because the set
                        // is every schema REACHABLE from a request body.
                        "DocumentUploadRequest",
                        // P1-TSK-030. Carries a display name - RESTRICTED-PII, and the clearest
                        // such column on the platform - which is why it is here and worth a second
                        // look. It is a request body rather than a response field, so the secret
                        // vocabulary is not the control: what keeps the name out of anywhere it
                        // should not be is that ProfileService's audit record records the FIELD
                        // that changed and not its value (INV-AUD-02, and audit_record.change_
                        // summary is RESTRICTED-FINANCIAL rather than RESTRICTED-PII).
                        "ProfileUpdateRequest",
                        // P2-TSK-016. Carries an organisation's name - a legal name, not a
                        // person's, and no secret; here because the set is every schema
                        // reachable from a request body.
                        "OrganisationRegistrationRequest",
                        // P2-TSK-016. Carries a PartyId (the declaration's subject), a stake
                        // and a control role - identifiers and enumerations, no secret. The
                        // pairing it creates is CONFIDENTIAL and lives in the store's
                        // classification, not in this vocabulary.
                        "OwnerDeclarationRequest",
                        // P2-TSK-018. Carries a purpose (a closed enum) and a text version (an
                        // integer) - no secret and no PII; here because the set is every schema
                        // REACHABLE from a request body. The pairing the grant creates - who
                        // consented to what - is CONFIDENTIAL and lives in consent_record's
                        // classification, not in this vocabulary.
                        "ConsentGrantRequest",
                        // P3-TSK-013. Carries a product type (a closed enum) and an ISO
                        // currency code - no secret and no PII; here because the set is every
                        // schema REACHABLE from a request body. What the opening creates is
                        // classified in accounts.customer_account's register rows, not in
                        // this vocabulary.
                        "AccountOpenRequest",
                        // P4-TSK-007. Carries a display name (RESTRICTED-PII at the register,
                        // never a secret) and a destination account identifier - here because
                        // the set is every schema REACHABLE from a request body. What creating
                        // a beneficiary stores is classified in transfers.beneficiary's
                        // register rows, not in this vocabulary.
                        "BeneficiaryCreateRequest",
                        // P4-TSK-008. Carries the caller's product and destination identifiers,
                        // an amount as an exact decimal string and a free-text reference
                        // (RESTRICTED-PII at the register, never a secret; disclosed only to
                        // the customer it belongs to) - here because the set is every schema
                        // REACHABLE from a request body. What executing a transfer stores is
                        // classified in transfers.transfer's register rows, not here.
                        "TransferCreateRequest",
                        // P4-TSK-009. Carries only the operator's REASON - free prose by a
                        // person, bound for the audit record's reason column
                        // (RESTRICTED-FINANCIAL, never rendered by any toString), never a
                        // secret - here because the set is every schema REACHABLE from a
                        // request body. The SuspensionRequest shape with the same bounds
                        // cited from the same constants.
                        "TransferReversalRequest",
                        // P5-TSK-011. Carries the caller's own payment-method identifier, an
                        // amount as an exact decimal string and an ISO currency code - no
                        // secret (a method id is not the token: the PAN-adjacent value stays
                        // behind the paymentmethods boundary, INV-PAY-02) - here because the
                        // set is every schema REACHABLE from a request body. What creating a
                        // payment stores is classified in payments.payment_intent's register
                        // rows, not in this vocabulary.
                        "PaymentCreateRequest",
                        // P5-TSK-015. Carries an amount as an exact decimal string, an ISO
                        // currency code, and a REASON (free prose by an operator, bound for
                        // the audit record's reason column and payments.refund's reason
                        // column - RESTRICTED-FINANCIAL, never rendered by any toString). No
                        // secret; here because the set is every schema REACHABLE from a
                        // request body. The TransferReversalRequest shape, at the refund.
                        "RefundRequest",
                        // P3-TSK-017. Carries dates, a reference, a REASON (free prose by a
                        // person, bound for the reason columns - RESTRICTED-FINANCIAL, never
                        // rendered by any toString) and lines of account/direction/amount/
                        // currency - the first request body to carry amounts, as exact
                        // decimal strings (INV-MON-01 past the boundary, inbound). No secret;
                        // here because the set is every schema REACHABLE from a request body.
                        "AdjustmentRequest",
                        // P5-TSK-005. Carries the one-time tokenisation grant - a genuinely
                        // secret-ish value (a chargeable-instrument reference for its validity
                        // window), which is why the field is Sensitive<String> and travels
                        // wrapped end to end: the deserialiser at the boundary, the
                        // TokenisationGrant type in the domain, one unwrap at the exchange
                        // wire. Never persisted, never in the trail.
                        "AttachPaymentMethodRequest",
                        // P6-TSK-003. Carries the operator's assertion of a party identifier,
                        // two business NAMES and an ISO currency code - no secret, and no
                        // person's name (PartyKind.ORGANISATION gates onboarding). Here
                        // because the set is every schema REACHABLE from a request body; what
                        // onboarding stores is classified in merchant.merchant's register
                        // rows, not in this vocabulary.
                        "OnboardMerchantRequest",
                        // P6-TSK-003. Carries ONE field: a REASON (free prose by an operator,
                        // bound for the audit record's reason column - RESTRICTED-FINANCIAL,
                        // never rendered by any toString), on all three standing moves. No
                        // secret; the TransferReversalRequest shape, at the counterparty.
                        "MerchantStandingRequest",
                        // P6-TSK-008. Carries ONE field: a REQUIRED reason, in a merchant's own
                        // words, bound for the audit record's reason column. The checkout
                        // module's only reasoned action (INV-AUD-03), and the MerchantStanding
                        // shape at a third surface. No secret, and nothing about the customer:
                        // a merchant withdrawing an offer names the session by identifier.
                        "AbandonSessionRequest",
                        // P6-TSK-007. Carries the checkout session's TOKEN - a real bearer
                        // credential, wrapped in Sensitive from the moment it is deserialised
                        // (P1-TSK-010's shape) - and a payment method identifier. It is in
                        // this set rather than in EMITTED_SECRET_SCHEMAS because the guard's
                        // precise property is that a secret may be SENT and never returned:
                        // this is the sending half, and the reason the token left the URL.
                        "ConfirmSessionRequest",
                        // P6-TSK-004. Carries a schedule NAME (the platform's own vocabulary,
                        // not anyone's data) and an ISO currency code. No secret; here because
                        // the set is every schema REACHABLE from a request body.
                        "CreateFeeScheduleRequest",
                        // P6-TSK-004. Carries the terms of a price: a RATE as an exact decimal
                        // (never a double - a rate bound through one would misprice every
                        // capture), a fixed part in minor units, two policy NAMES, an instant,
                        // and a REASON (free prose by an operator, bound for the audit record's
                        // reason column - RESTRICTED-FINANCIAL, never rendered by any
                        // toString). No secret; the AdjustmentRequest shape, at the price.
                        "CreateFeeScheduleVersionRequest",
                        // P6-TSK-004. Carries a schedule identifier and a REASON, on the
                        // pointer move. No secret; the MerchantStandingRequest shape.
                        "AssignFeeScheduleRequest",
                        // P6-TSK-007. Carries an amount, an ISO currency code and a LINE
                        // SUMMARY - display text about what one person is buying, classified
                        // RESTRICTED-PII at its column for that reason. No secret; here
                        // because the set is every schema REACHABLE from a request body, and
                        // no merchant identifier either: the tenant comes from the API key.
                        "CreateSessionRequest");
    }

    @Test
    @DisplayName("the contract guard is not vacuous: it sees each shape a secret could take")
    void theContractGuardHasTeeth() {
        // Four shapes, asserted individually. An aggregate probe reporting "caught" would say
        // nothing about WHICH of them it caught - the lesson the P0-TSK-032 review recorded after
        // five bypass shapes were probed together, where one catch could have masked four misses.
        assertThat(secretNamedMembersIn("{\"properties\":{\"password\":{}}}"))
                .as("a schema property")
                .containsExactly("password");
        assertThat(secretNamedMembersIn("{\"properties\":{\"cardNumber\":{}}}"))
                .as("a compound spelling, invisible without adjacent-pair matching")
                .containsExactly("cardNumber");
        assertThat(secretNamedMembersIn("{\"parameters\":[{\"name\":\"token\",\"in\":\"query\"}]}"))
                .as("a parameter, where the name is a VALUE and a key-only scan sees nothing")
                .containsExactly("token");
        assertThat(secretNamedMembersIn("{\"properties\":{\"idempotencyKey\":{},\"companyName\":{}}}"))
                .as("and the false-positive direction: ADR-0019 excluded `key` deliberately")
                .isEmpty();
    }

    @Test
    @DisplayName("the limit, stated: a credential in an example VALUE is not caught")
    void theContractGuardMatchesNamesNotValues() {
        // Recorded rather than glossed, and it is a real risk: P0-TSK-031 found this repository's
        // own documentation tripping the secret scanner because it quoted realistic example keys.
        // A name-matching guard cannot see `"example": "hunter2"`, and nothing here pretends it can
        // - the control for that is the gitleaks scan over the whole repository, which does read
        // values.
        assertThat(secretNamedMembersIn("{\"schema\":{\"example\":\"hunter2\"}}"))
                .as("this being empty is the limit being true, not the guard being broken")
                .isEmpty();
    }

    // -----------------------------------------------------------------
    // Exception messages, which are a sink in their own right.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("no rejection on the credential path repeats what it refused")
    void noRejectionEchoesItsInput() {
        // An exception message is a String by the time it exists, so Sensitive is not in the path
        // at all - which makes this the one disclosure route the wrapper structurally cannot close.
        // Three of them, each the message a caller would actually trigger.
        String tooShort = "zqx-9c1";
        String tooLong = "z".repeat(RawPassword.MAX_LENGTH + 1);

        assertThat(catchThrowable(() -> RawPassword.of(tooShort)))
                .hasMessageNotContaining(tooShort);
        assertThat(catchThrowable(() -> RawPassword.of(tooLong)))
                .hasMessageNotContaining(tooLong);
        assertThat(
                        catchThrowable(
                                () ->
                                        DERIVER.parametersOf(
                                                com.finapp.sharedkernel.security.Sensitive.of(
                                                        PASSWORD))))
                .as("asked to read parameters out of something that is not a derivation")
                .hasMessageNotContaining(PASSWORD);
    }

    /**
     * The schemas a request body refers to, by name.
     *
     * <p>Read structurally rather than by regex, because this decides what the guard
     * <em>permits</em> and a loose match here silently widens the exemption.
     */
    private static java.util.SortedSet<String> schemasReachableFromRequestBodies(String document) {
        java.util.SortedSet<String> reachable = new java.util.TreeSet<>();
        collectRequestBodySchemas(
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(document),
                false,
                reachable);
        return reachable;
    }

    private static void collectRequestBodySchemas(
            tools.jackson.databind.JsonNode node,
            boolean insideARequestBody,
            java.util.Set<String> reachable) {
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                boolean nowInside = insideARequestBody || "requestBody".equals(field);
                tools.jackson.databind.JsonNode child = node.get(field);
                if (nowInside && "$ref".equals(field) && child.isString()) {
                    String reference = child.stringValue();
                    reachable.add(reference.substring(reference.lastIndexOf('/') + 1));
                }
                collectRequestBodySchemas(child, nowInside, reachable);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectRequestBodySchemas(child, insideARequestBody, reachable));
        }
    }

    /**
     * Secret-named members anywhere except inside a schema a request body refers to, or one this
     * test names as a deliberate emission.
     *
     * <p>A secret may be <strong>sent</strong>, and it must never be somewhere a URL or a header
     * goes — those reach access logs, proxies and browser history. Everything outside the permitted
     * schemas is scanned as text, so a header name, a parameter name and a response property are
     * covered by one rule rather than by three that could each be forgotten.
     *
     * <h3>"Never returned" was too broad, and `P1-TSK-018` is where that showed</h3>
     *
     * <p>The rule's own reason is about URLs and headers. A response <em>body</em> over TLS is where
     * every session token in the world is delivered, and a step-up that withheld the session it just
     * issued would log the customer out at the moment they proved a second factor.
     *
     * <p>So the emission is <strong>named</strong> rather than the rule relaxed: {@link
     * #EMITTED_SECRET_SCHEMAS} is one entry, so widening it is a visible decision. This is the
     * second narrowing of this guard — {@code P1-TSK-010} made the first, when it forbade a
     * credential-named member anywhere and an authentication request had to declare a password.
     * Each time the precise property turned out to be narrower than the blanket one.
     */
    /**
     * Response schemas that deliberately carry a secret to the client.
     *
     * <p>One entry. {@code ElevatedSession} carries the session a step-up produced, and there is no
     * design in which it does not: elevation <strong>rotates</strong> the identifier
     * ({@code P1-TSK-015}), so withholding the replacement logs the customer out.
     *
     * <p>The same decision reaches three guards — this one, and {@code secretsAreWrapped}'s field
     * and accessor checks. That is one decision with three enforcement points rather than three
     * concessions: each guard encodes <em>"secrets do not leave"</em>, and a session token is the one
     * value whose purpose is to leave.
     */
    private static final java.util.Set<String> EMITTED_SECRET_SCHEMAS =
            java.util.Set.of(
                    "ElevatedSession",
                    "AuthenticatedSession",
                    // P6-TSK-002. The merchant API key's issuance response: the SECRET is the
                    // one value whose whole purpose is to be transmitted, exactly once, and a
                    // Sensitive would render as the mask. The logging half is closed the same
                    // way - IssuedKeyView overrides toString. Unlike the two above, it is
                    // NULL on a replay: the secret is never stored, so there is nothing to
                    // re-show (INV-IDN-01 winning over the replay discipline).
                    "IssuedKeyView",
                    // P6-TSK-007. The checkout session's creation response: the TOKEN is the
                    // one value whose whole purpose is to be transmitted, exactly once, to the
                    // merchant who will hand it to a customer. Like IssuedKeyView and unlike
                    // the two session entries, it is NULL on a replay - the claim records the
                    // session id alone, so there is nothing to re-show (INV-IDN-01 winning
                    // over the replay discipline, for the second credential).
                    //
                    // THIS GUARD ALSO CHANGED THE DESIGN rather than merely admitting it: the
                    // confirmation's token was in the URL path, and the guard refused it on
                    // its own reasoning that a secret in a URL reaches every access log. It
                    // now travels in a request body, which is why ConfirmSessionRequest
                    // appears in the bounded set below rather than here.
                    "CreatedSessionView");

    private static List<String> secretNamedMembersOutsideRequestBodiesIn(String document) {
        tools.jackson.databind.JsonNode root =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(document);
        java.util.SortedSet<String> permitted = schemasReachableFromRequestBodies(document);
        permitted.addAll(EMITTED_SECRET_SCHEMAS);

        tools.jackson.databind.node.ObjectNode pruned =
                (tools.jackson.databind.node.ObjectNode) root.deepCopy();
        tools.jackson.databind.JsonNode schemas = pruned.path("components").path("schemas");
        if (schemas.isObject()) {
            permitted.forEach(name -> ((tools.jackson.databind.node.ObjectNode) schemas).remove(name));
        }
        return secretNamedMembersIn(pruned.toString());
    }

    // -----------------------------------------------------------------

    private static Credential derivedCredential() {
        return Credential.forPassword(
                IDS,
                CLOCK,
                IdentityId.of(IDS.next()),
                CredentialType.PASSWORD,
                DERIVER,
                RawPassword.of(PASSWORD));
    }

    private static final Pattern WORD_BOUNDARY =
            Pattern.compile("[_\\s-]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * Every name in the document: JSON keys, plus the value of every {@code "name"} member.
     *
     * <p>The second half is not tidiness. OpenAPI declares a parameter or a header as
     * {@code {"name": "token", "in": "query"}} - the secret name is a <strong>value</strong> there,
     * not a key, so a key-only scan reports a clean contract for a
     * {@code GET /v1/things?token=…}. Found by probing shapes the first version was not designed
     * against, rather than by re-reading it.
     */
    private static List<String> secretNamedMembersIn(String document) {
        List<String> offending = new ArrayList<>();
        Matcher keys = Pattern.compile("\"([A-Za-z][A-Za-z0-9_-]{2,63})\"\\s*:").matcher(document);
        while (keys.find()) {
            if (namesASecret(keys.group(1))) {
                offending.add(keys.group(1));
            }
        }
        Matcher declared =
                Pattern.compile("\"name\"\\s*:\\s*\"([A-Za-z][A-Za-z0-9_-]{2,63})\"")
                        .matcher(document);
        while (declared.find()) {
            if (namesASecret(declared.group(1))) {
                offending.add(declared.group(1));
            }
        }
        return offending;
    }

    /**
     * Camel-case word matching, so {@code companyName} is not a PAN. ADR-0019's reasoning.
     *
     * <p>Single words <strong>and adjacent pairs</strong> - the same correction this gate had to
     * make to {@code secretsAreWrapped} itself, which carried six compound entries the splitter
     * guaranteed could never match. Without pairs, {@code apiKey} and {@code cardNumber} would be
     * invisible here too, and this list would look like coverage it did not have.
     */
    private static boolean namesASecret(String name) {
        String[] words = WORD_BOUNDARY.split(name);
        for (int index = 0; index < words.length; index++) {
            if (SECRET_WORDS.contains(words[index].toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (index + 1 < words.length
                    && SECRET_WORDS.contains(
                            (words[index] + words[index + 1]).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String readRepositoryFile(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException(
                    "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        try {
            return Files.readString(directory.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + relativePath, e);
        }
    }
}
