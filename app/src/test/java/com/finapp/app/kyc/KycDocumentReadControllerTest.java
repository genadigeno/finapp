package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.finapp.app.api.ApiErrorHandler;
import com.finapp.app.api.DatabaseUnreachableAnswer;
import com.finapp.app.api.UnrecordedReadAdvice;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.StepUpRefusals;
import com.finapp.app.surface.OperatorResponsesAreNotStored;
import com.finapp.app.surface.RequestSurfaces;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentType;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycDocument;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reviewer's document doors on the wire (`P17-TSK-125`; the owner's D-25 decision 2; {@code THREAT_MODEL.md} T51, T55): the
 * real controller over the real desk and {@code DocumentAccess}, kyc's stores in memory, behind the application's own answer
 * path - {@code OperatorResponsesAreNotStored}, {@code UnrecordedReadAdvice} and {@code ApiErrorHandler}. A download is a file to
 * save under an allow-listed type, never a page to render, never stored, and never served unrecorded. Who may knock is
 * {@code KycDocumentAndIdentityDoorsTest}'s.
 */
@DisplayName("the KYC document download on the wire: an attachment, nosniff, an allow-listed type, never stored, never"
        + " unrecorded (P17-TSK-125)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class KycDocumentReadControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant T = Instant.parse("2026-11-01T08:00:00.123456Z");
    private static final byte[] PASSPORT = "%PDF-1.7 a passport, as a test's bytes".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PLANTED = "<html><script>steal(document.cookie)</script>".getBytes(StandardCharsets.US_ASCII);

    private final List<AuditRecord> written = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final InMemoryKycDocuments store = new InMemoryKycDocuments(IDS);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new KycDocumentReadController(HermeticKycDocuments.desk(store, store.cases(),
                    (unitOfWork, record) -> written.add(record), IDS, CLOCK, events)), new Unmarked())
            .setControllerAdvice(new UnrecordedReadAdvice(), new ApiErrorHandler(
                    new StaticListableBeanFactory().getBeanProvider(StepUpRefusals.class),
                    new StaticListableBeanFactory().getBeanProvider(DatabaseUnreachableAnswer.class)))
            .addFilters(new OperatorResponsesAreNotStored(new RequestSurfaces(true)))
            .build();

    private CorrelationContext.Scope flow;
    private SecurityContext.Scope actor;

    @BeforeEach
    void enter() {
        flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
        actor = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE));
    }

    @AfterEach
    void leave() {
        actor.close();
        flow.close();
    }

    /** A planted door under the permission that forgot its record: what the guard at the answer is for. Not production code. */
    @RestController
    static class Unmarked {

        @GetMapping(path = "/planted/kyc/documents/content", produces = "application/pdf")
        @RequiresPermission(PermissionName.KYC_DOCUMENT_READ)
        @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
        public ResponseEntity<byte[]> plantedUnrecordedDownload() {
            return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF).body(PLANTED);
        }
    }

    @Test
    @DisplayName("a document is served as an attachment named by its id, under its declared type, nosniff and no-store, its"
            + " bytes exactly the bytes received, its record written")
    void aDownloadIsAnAttachment() throws Exception {
        KycDocument passport = store.aDocument(store.aCase(T), DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);

        MockHttpServletResponse answer = download(id(passport));

        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getContentType()).isEqualTo("application/pdf");
        assertThat(answer.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"" + id(passport) + ".pdf\"");
        assertThat(answer.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(answer.getHeader("Cache-Control")).as("the operator surface's, set by its filter alone").isEqualTo("no-store");
        assertThat(answer.getContentAsByteArray()).isEqualTo(PASSPORT);
        assertThat(written).extracting(record -> record.operation().code()).containsExactly("kyc.DocumentContentRead");
    }

    @Test
    @DisplayName("each allow-listed type is served under its own media type: a JPEG as image/jpeg, a PNG as image/png")
    void eachTypeIsServedUnderItsOwn() throws Exception {
        KycCase kycCase = store.aCase(T);
        KycDocument jpeg = store.aDocument(kycCase, DocumentType.ID_CARD_FRONT, DocumentContentType.JPEG, PLANTED, T);
        KycDocument png = store.aDocument(kycCase, DocumentType.ID_CARD_BACK, DocumentContentType.PNG, PASSPORT, T);

        MockHttpServletResponse jpegAnswer = download(id(jpeg));
        assertThat(jpegAnswer.getContentType()).as("whatever the bytes look like, the declared type: never sniffed as HTML")
                .isEqualTo("image/jpeg");
        assertThat(jpegAnswer.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"" + id(jpeg) + ".jpg\"");
        assertThat(jpegAnswer.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download(id(png)).getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("an unknown document is a 404 problem: no byte, no record, still no-store")
    void anUnknownDocumentIsAProblem() throws Exception {
        MockHttpServletResponse answer = download(IDS.next().toString());

        assertThat(answer.getStatus()).isEqualTo(404);
        assertThat(answer.getContentType()).isEqualTo("application/problem+json");
        assertThat(answer.getHeader("Content-Disposition")).isNull();
        assertThat(answer.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("a case's documents are a section 7 page of metadata - no content, no checksum - recorded, and never stored")
    void theListIsAPageOfMetadata() throws Exception {
        KycCase kycCase = store.aCase(T);
        KycDocument passport = store.aDocument(kycCase, DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);

        MockHttpServletResponse answer = mvc.perform(get("/operator/kyc/cases/" + kycCase.id().value() + "/documents"))
                .andReturn().getResponse();

        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode page = JsonMapper.builder().build().readTree(answer.getContentAsString());
        assertThat(page.propertyNames()).as("nextCursor left out on the last page").containsExactly("items");
        JsonNode item = page.get("items").get(0);
        assertThat(item.propertyNames())
                .containsExactlyInAnyOrder("documentId", "documentType", "contentType", "sizeBytes", "receivedAt");
        assertThat(item.get("documentId").asString()).isEqualTo(id(passport));
        assertThat(item.get("sizeBytes").asInt()).isEqualTo(PASSPORT.length);
        assertThat(answer.getContentAsString()).doesNotContain("PDF-1.7");
        assertThat(written).extracting(record -> record.operation().code()).containsExactly("kyc.CaseDocumentsListed");
    }

    @Test
    @DisplayName("a download under KYC_DOCUMENT_READ whose read recorded nothing is refused 500 at the answer: none of its bytes"
            + " leave")
    void anUnrecordedDownloadServesNothing() throws Exception {
        MockHttpServletResponse answer = mvc.perform(get("/planted/kyc/documents/content")).andReturn().getResponse();

        assertThat(answer.getStatus()).isEqualTo(500);
        assertThat(answer.getContentAsString()).doesNotContain("steal").contains("api.InternalError");
        assertThat(answer.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    // -----------------------------------------------------------------

    private MockHttpServletResponse download(String documentId) throws Exception {
        return mvc.perform(get("/operator/kyc/documents/" + documentId + "/content")).andReturn().getResponse();
    }

    private static String id(KycDocument document) {
        return document.id().value().toString();
    }
}
