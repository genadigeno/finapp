package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.finapp.platform.api.ApiVersion;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * The published OpenAPI document is generated on every build, and a change to it cannot pass
 * unnoticed.
 *
 * <h2>The gate</h2>
 *
 * <p>The document is generated from the running application and compared byte for byte against
 * {@code docs/api/openapi.json}. <strong>Any</strong> difference fails the build. That is
 * deliberately blunter than "fail on a breaking change": a classifier clever enough to decide would
 * have to be right about every possible OpenAPI edit, and its one dangerous mistake - calling a
 * breaking change compatible - fails at the customer end, months later, in someone else's code. An
 * exact comparison has no such failure mode. {@link OpenApiChange} then labels each difference so
 * the failure message says which kind of decision is being asked for.
 *
 * <p>This is the same argument {@code ERROR_CONTRACT.md} §4 makes about renaming an error code: a
 * breaking change wearing a refactor's clothes, which unlike a broken build fails at the customer
 * end. The build is where it should be caught, because it is the last place we control.
 *
 * <h2>Why the document is not empty in a phase with no endpoints</h2>
 *
 * <p>Phase 0 publishes no business endpoint, so {@code paths} is empty. The platform nevertheless
 * has a published contract already - the error codes, the problem-detail shape and the correlation
 * header that {@code P0-TSK-024} and {@code P0-TSK-025} committed to - and that contract is what
 * this document describes. It is real, and a client is entitled to rely on it.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    static {
        // `Sensitive<T>` is a wrapper with no accessible property, so springdoc models it as an
        // EMPTY schema and publishes `password: {$ref: SensitiveString}` pointing at `{}`. A
        // generated client would then model a password as an untyped object and would not know to
        // send a JSON string - a broken contract that still parses, still diffs, and still resolves
        // every $ref.
        //
        // Found by generating the document rather than reasoning about it (`P1-TSK-010`), which is
        // how the two `P1-TSK-006` contract defects were found as well.
        //
        // Told to springdoc rather than patched afterwards in OpenApiDocument: on the wire the value
        // IS a string, so this is the model being made correct rather than the output being
        // corrected. It lives in test scope because springdoc does (ADR-0015) - the running
        // application ships no documentation library and must not carry an annotation for one.
        org.springdoc.core.utils.SpringDocUtils.getConfig()
                .replaceWithClass(com.finapp.sharedkernel.security.Sensitive.class, String.class);
    }

    /** The committed contract. Reviewed, versioned in git, and what clients are written against. */
    private static final String BASELINE = "docs/api/openapi.json";

    /** Where the freshly generated document is left, so a legitimate change is easy to accept. */
    private static final Path GENERATED = Path.of("build", "openapi", "openapi.json");

    @LocalServerPort private int port;

    @Test
    @DisplayName("the generated document matches the committed contract, difference by difference")
    void theContractHasNotChangedUnnoticed() throws Exception {
        String published = publishedDocument();
        Path written = writeGenerated(published);

        Path baselinePath;
        try {
            baselinePath = RepositoryPaths.locate(BASELINE);
        } catch (IllegalStateException missing) {
            fail(
                    """
                    %s does not exist. The generated document has been written to %s; \
                    review it and commit it as the contract baseline.""",
                    BASELINE, written.toAbsolutePath());
            return;
        }

        String committed = Files.readString(baselinePath, StandardCharsets.UTF_8);
        if (committed.equals(published)) {
            return;
        }

        List<OpenApiChange> changes =
                OpenApiChange.between(OpenApiDocument.parse(committed), OpenApiDocument.parse(published));
        String report =
                changes.isEmpty()
                        ? "  (the documents differ only in formatting)"
                        : changes.stream()
                                .map(change -> "  " + change)
                                .collect(Collectors.joining("\n"));

        fail(
                """
                The API contract has changed.

                %s

                %s

                If every difference above is COMPATIBLE, accept the change:
                    copy %s over %s

                If any is BREAKING, it must not be published under version %d. Either withdraw \
                the change, or open the next version (ADR-0015).""",
                OpenApiChange.anyBreaking(changes)
                        ? "This change BREAKS clients written against the current contract."
                        : "Every difference is backwards compatible.",
                report,
                written.toAbsolutePath(),
                baselinePath,
                ApiVersion.CURRENT);
    }

    @Test
    @DisplayName("the document is not vacuously correct: it carries the contract that exists today")
    void theDocumentDescribesTheContractThatExists() throws Exception {
        // Without this, a generator that quietly produced an empty components block would match an
        // equally empty baseline for ever, and the gate above would guard nothing. The assertion is
        // against the declared codes rather than a written list, so it cannot go stale.
        JsonNode document = OpenApiDocument.parse(publishedDocument());

        JsonNode responses = document.path("components").path("responses");
        List<String> codes = DeclaredErrorCodes.all().stream().map(ErrorCode::code).sorted().toList();
        assertThat(codes).as("the taxonomy itself must not be empty").isNotEmpty();
        assertThat(responses.propertyNames().stream().sorted().toList())
                .as("every declared error code is published as a reusable response")
                .isEqualTo(codes);

        JsonNode schema = document.path("components").path("schemas").path("ProblemDetail");
        assertThat(schema.path("properties").propertyNames())
                .as("the wire members of the problem detail")
                .contains("type", "title", "status", "code", "detail", "instance", "correlationId");
        assertThat(schema.path("required").toString())
                .as("the members a client may always read")
                .isEqualTo("[\"type\",\"title\",\"status\",\"code\"]");

        assertThat(document.path("info").path("version").stringValue())
                .as("the document states the contract version that its paths carry")
                .isEqualTo(String.valueOf(ApiVersion.CURRENT));
    }

    @Test
    @DisplayName("no error response is published without the correlation header a client must quote")
    void everyErrorResponseCarriesTheCorrelationHeader() throws Exception {
        // The one member of the contract that exists purely so a human can get help. A response
        // documented without it tells a client to quote something it was never told to expect.
        JsonNode responses = OpenApiDocument.parse(publishedDocument()).path("components").path("responses");

        for (String code : responses.propertyNames()) {
            JsonNode response = responses.get(code);
            assertThat(response.path("headers").path(OpenApiDocument.CORRELATION_HEADER).path("$ref").stringValue())
                    .as("%s must document the correlation header", code)
                    .isEqualTo("#/components/headers/" + OpenApiDocument.CORRELATION_HEADER);
            assertThat(response.path("content").propertyNames())
                    .as("%s must be returned as a problem detail, not as anything else", code)
                    .containsExactly(OpenApiDocument.PROBLEM_JSON);
        }
    }

    @Test
    @DisplayName("each error response pins its status, code and type as data, not only as prose")
    void statusAndCodeArePublishedAsData() throws Exception {
        // Mutation found the absence of this. With the status living only inside a description,
        // changing api.Conflict from 409 to 422 was reported as a compatible rewording - a change
        // that breaks every client switching on the status. It is also a gap in the contract on its
        // own terms: ERROR_CONTRACT.md §1 says the status is fixed per code, and until now a client
        // had no machine-readable way to learn what it was fixed to.
        JsonNode responses = OpenApiDocument.parse(publishedDocument()).path("components").path("responses");

        for (ErrorCode code : DeclaredErrorCodes.all()) {
            JsonNode pinned =
                    responses
                            .path(code.code())
                            .path("content")
                            .path(OpenApiDocument.PROBLEM_JSON)
                            .path("schema")
                            .path("allOf")
                            .path(1)
                            .path("properties");

            assertThat(pinned.path("status").path("const").intValue())
                    .as("%s must publish the status it always arrives with", code.code())
                    .isEqualTo(code.status());
            assertThat(pinned.path("code").path("const").stringValue())
                    .as("%s must publish the code a client switches on", code.code())
                    .isEqualTo(code.code());
            assertThat(pinned.path("type").path("const").stringValue())
                    .as("%s must publish its stable problem type", code.code())
                    .endsWith(code.code());
        }
    }

    @Test
    @DisplayName("every $ref in the published document resolves")
    void everyReferenceResolves() throws Exception {
        // A contract whose references do not resolve is one no client generator can read, and the
        // failure is silent: the document still parses, still diffs, still looks complete. The
        // schema name is written both as a component key and as the target of every response
        // reference, so it is exactly the kind of rename that leaves one of the two behind.
        JsonNode document = OpenApiDocument.parse(publishedDocument());
        List<String> references = new ArrayList<>();
        collectReferences(document, references);

        assertThat(references).as("the document must actually contain references").isNotEmpty();
        for (String reference : references) {
            assertThat(reference).as("only internal references are used").startsWith("#/");
            JsonNode target = document.at(reference.substring(1));
            assertThat(target.isMissingNode())
                    .as("%s points at nothing", reference)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the published contract contains no test fixture")
    void noProbeRouteIsPublished() throws Exception {
        // Several suites register probe controllers through @Import. Spring caches a context per
        // distinct configuration, so theirs are separate from this one - but "separate" is a
        // property of the cache key, not something anyone declared. If a probe ever reached this
        // context it would be published as a real endpoint, and it would be published silently,
        // because a baseline generated with it present looks exactly as authoritative.
        String document = publishedDocument();

        assertThat(document).doesNotContain("probe");

        // Every published path must be one this platform means to publish. The assertion used to
        // be that there were none at all, which was true while Phase 0 had no endpoint and stopped
        // being a check the moment `P1-TSK-006` added one. A list is the honest replacement: adding
        // an endpoint is a deliberate act, and this is one of the places it has to be declared.
        assertThat(OpenApiDocument.parse(document).path("paths").propertyNames())
                .as("a route in the published contract that nobody declared here")
                .containsExactlyInAnyOrder(
                        ApiVersion.CURRENT_PREFIX + "/registrations",
                        ApiVersion.CURRENT_PREFIX + "/authentications",
                        ApiVersion.CURRENT_PREFIX + "/sessions",
                        // P3-TSK-013: the account product - open/list, and the balance read
                        // whose response names its numbers and its nature (ADR-0041).
                        ApiVersion.CURRENT_PREFIX + "/me/accounts",
                        // P3-TSK-014: DELETE lives on the account path itself.
                        ApiVersion.CURRENT_PREFIX + "/me/accounts/{id}",
                        ApiVersion.CURRENT_PREFIX + "/me/accounts/{id}/balance",
                        // P3-TSK-018: the period statement, derived from postings - the
                        // drill-down shape (INV-ACC-02), opening + lines = closing.
                        ApiVersion.CURRENT_PREFIX + "/me/accounts/{id}/statement",
                        // P4-TSK-007: saved destinations - the create is the step-up point
                        // (MULTI_FACTOR when a factor is enrolled, decided in the domain).
                        ApiVersion.CURRENT_PREFIX + "/beneficiaries",
                        ApiVersion.CURRENT_PREFIX + "/beneficiaries/{id}",
                        // P4-TSK-008: the transfer surface - the POST answers 201 with the
                        // judgement in the body (a FAILED outcome is never an HTTP error),
                        // and the reads are ownership-scoped (customer_id = ?).
                        ApiVersion.CURRENT_PREFIX + "/transfers",
                        ApiVersion.CURRENT_PREFIX + "/transfers/{id}",
                        ApiVersion.CURRENT_PREFIX + "/transfers/{id}/reversal",
                        // P3-TSK-017: the ledger's one public surface - the adjustment,
                        // behind LEDGER_ADJUST, reason required (INV-REV-04).
                        ApiVersion.CURRENT_PREFIX + "/ledger/adjustments",
                        // P3-TSK-021: the four-eyes lifecycle - the proposal read, the
                        // second person's approval, and the rejection/withdrawal.
                        ApiVersion.CURRENT_PREFIX + "/ledger/adjustments/{id}",
                        ApiVersion.CURRENT_PREFIX + "/ledger/adjustments/{id}/approval",
                        // P5-TSK-005: the instrument surface - attach through the
                        // tokenisation exchange (the body carries only the one-time
                        // grant, INV-PAY-02), list, and the ownership-scoped detach.
                        ApiVersion.CURRENT_PREFIX + "/me/payment-methods",
                        ApiVersion.CURRENT_PREFIX + "/me/payment-methods/{id}",
                        // P5-TSK-011: the payment surface - the keyed create, the
                        // confirmation that answers the intent's real state (honestly
                        // PROCESSING), the window-bounded cancel, and the ownership-scoped
                        // reads (party_id = ?).
                        ApiVersion.CURRENT_PREFIX + "/payments",
                        ApiVersion.CURRENT_PREFIX + "/payments/{id}",
                        ApiVersion.CURRENT_PREFIX + "/payments/{id}/confirmation",
                        ApiVersion.CURRENT_PREFIX + "/sessions/{id}",
                        ApiVersion.CURRENT_PREFIX + "/sessions/current",
                        ApiVersion.CURRENT_PREFIX + "/me/mfa",
                        ApiVersion.CURRENT_PREFIX + "/me/mfa/confirmation",
                        ApiVersion.CURRENT_PREFIX + "/authentications/mfa",
                        // P1-TSK-023. Recovery is UNAUTHENTICATED by definition - it is for
                        // somebody who cannot log in - so these two are the widest surface
                        // the platform has, and declaring them here is the deliberate act.
                        ApiVersion.CURRENT_PREFIX + "/recoveries",
                        ApiVersion.CURRENT_PREFIX + "/recoveries/{id}/completion",
                        ApiVersion.CURRENT_PREFIX + "/me/channels",
                        ApiVersion.CURRENT_PREFIX + "/me/channels/verification",
                        // P1-TSK-028. The only two routes in the phase behind
                        // @RequiresPermission, and the only two the plan listed that nobody owned.
                        ApiVersion.CURRENT_PREFIX + "/identities/{id}/suspension",
                        ApiVersion.CURRENT_PREFIX + "/identities/{id}/roles",
                        // P1-TSK-030. Declared by the plan for the whole phase and owned by no
                        // task until the review found it.
                        ApiVersion.CURRENT_PREFIX + "/me",
                        ApiVersion.CURRENT_PREFIX + "/me/credential",
                        // P2-TSK-008. The phase's first customer-facing kyc surface: the upload
                        // onto one's own open case, the /v1/me ownership-by-absence shape.
                        ApiVersion.CURRENT_PREFIX + "/me/kyc/documents",
                        // P2-TSK-011. The inbound provider door: unauthenticated by honest
                        // declaration, authenticated in fact by the HMAC signature over the raw
                        // body - the deliberate act of publishing a machine-facing route.
                        ApiVersion.CURRENT_PREFIX + "/providers/kyc/callbacks",
                        // P5-TSK-012. The payments inbound door: unauthenticated by honest
                        // declaration, authenticated in fact by the HMAC over
                        // timestamp + "." + raw body with a freshness window (ADR-0047) -
                        // the second machine-facing route, published deliberately.
                        ApiVersion.CURRENT_PREFIX + "/providers/payments/webhooks",
                        // P2-TSK-012. The reviewer surface, the phase's privileged endpoints:
                        // both behind @RequiresPermission(KYC_REVIEW), the read audited.
                        ApiVersion.CURRENT_PREFIX + "/kyc/cases/{id}",
                        ApiVersion.CURRENT_PREFIX + "/kyc/cases/{id}/reviews/{taskId}/resolution",
                        // P2-TSK-013. The decision: the one recorded act every later phase
                        // gates on (INV-KYC-02), behind the same permission.
                        ApiVersion.CURRENT_PREFIX + "/kyc/cases/{id}/decision",
                        // P2-TSK-016. The KYB surface: the /v1/me ownership-by-absence shape -
                        // registration, the shaped view, and the owner declaration whose one
                        // identifier names the declaration's subject, never a resource.
                        ApiVersion.CURRENT_PREFIX + "/me/organisations",
                        ApiVersion.CURRENT_PREFIX + "/me/kyb",
                        ApiVersion.CURRENT_PREFIX + "/me/kyb/owners",
                        // P2-TSK-018. The consent surface: the /v1/me shape, whose one path
                        // variable is a closed enum naming a category of processing shared by
                        // everyone - not a resource identifier, so there is still nothing here
                        // an attacker can point at a victim.
                        ApiVersion.CURRENT_PREFIX + "/me/consents",
                        ApiVersion.CURRENT_PREFIX + "/me/consents/{purpose}",
                        // P2-TSK-006. The person's own case: ensure-exists and the shaped view,
                        // no identifier anywhere in either request.
                        ApiVersion.CURRENT_PREFIX + "/me/kyc");
    }

    @Test
    @DisplayName("no published schema is empty, so no client models a value as an untyped object")
    void everyPublishedSchemaSaysWhatItIs() throws Exception {
        // The guard for the defect `P1-TSK-010` hit: a wrapper type with no accessible property
        // publishes as `{}`, and every other check passes over it - it parses, it diffs, and
        // `everyReferenceResolves` is satisfied because the schema exists. What a client generator
        // does with it is model the value as an untyped object.
        //
        // Asserted on the whole document rather than on the one type, because the next wrapper will
        // not be called Sensitive.
        var schemas = OpenApiDocument.parse(publishedDocument()).path("components").path("schemas");

        assertThat(schemas.propertyNames())
                .as("precondition: there must be schemas to inspect")
                .isNotEmpty();

        List<String> empty = new java.util.ArrayList<>();
        schemas.propertyNames()
                .forEach(
                        name -> {
                            if (schemas.get(name).isEmpty()) {
                                empty.add(name);
                            }
                        });

        assertThat(empty)
                .as("an empty schema tells a generated client nothing about the value it models")
                .isEmpty();
    }

    // -----------------------------------------------------------------

    /**
     * Generated afresh per test. Booting the context is the expensive part; the fetch is a
     * localhost round trip, and caching it in a static field bought nothing while introducing
     * shared mutable state between tests - guarded, on the first attempt, by a {@code
     * synchronized} on an instance method, which locks a different instance for every test method
     * and therefore guards nothing at all.
     */
    private String publishedDocument() throws Exception {
        return OpenApiDocument.publish(fetch(OpenApiDocument.SPRINGDOC_PATH));
    }

    private static void collectReferences(JsonNode node, List<String> references) {
        if (node.isObject()) {
            for (java.util.Map.Entry<String, JsonNode> property : node.properties()) {
                if (property.getKey().equals("$ref") && property.getValue().isString()) {
                    references.add(property.getValue().stringValue());
                } else {
                    collectReferences(property.getValue(), references);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectReferences(element, references);
            }
        }
    }

    private String fetch(String path) throws Exception {
        HttpResponse<String> response =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("springdoc must serve the document at %s", path)
                .isEqualTo(200);
        return response.body();
    }

    private static Path writeGenerated(String document) throws IOException {
        Files.createDirectories(GENERATED.getParent());
        Files.writeString(GENERATED, document, StandardCharsets.UTF_8);
        return GENERATED;
    }
}
