package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentType;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycDocument;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The KYC reviewer's documents (`P17-TSK-125`; the owner's D-25 decision 2): a case's documents as a §7 page of metadata, and one
 * document's content through the real {@code DocumentAccess} - its {@code kyc.DocumentContentRead} committed before a byte is
 * returned, and nothing returned when the record, the commit or the allow-list says no. kyc's stores in memory; the same reads
 * on PostgreSQL are owed with the database tier (the owner's ruling of 2026-11-20).
 */
@DisplayName("the KYC reviewer's documents: metadata pages, and content recorded before it is served (P17-TSK-125)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class KycDocumentReadDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant T = Instant.parse("2026-11-01T08:00:00.123456Z");
    private static final Actor REVIEWER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final byte[] PASSPORT = "%PDF-1.7 a passport, as a test's bytes".getBytes(StandardCharsets.US_ASCII);

    private final List<AuditRecord> written = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final AuditWriter<Connection> audit = (unitOfWork, record) -> {
        events.add("write:" + record.operation().code());
        written.add(record);
    };
    private final InMemoryKycDocuments store = new InMemoryKycDocuments(IDS);
    private final KycDocumentReadDesk desk = HermeticKycDocuments.desk(store, store.cases(), audit, IDS, CLOCK, events);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/operator/kyc/documents/x/content");
    private CorrelationId correlation;
    private CorrelationContext.Scope flow;
    private SecurityContext.Scope actor;

    @BeforeEach
    void enter() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        correlation = CorrelationId.generate(IDS);
        flow = CorrelationContext.enter(Correlation.startingWith(correlation));
        actor = SecurityContext.enter(REVIEWER);
    }

    @AfterEach
    void leave() {
        actor.close();
        flow.close();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ a case's documents

    @Test
    @DisplayName("a page lists a case's documents in the order received - identifier, type, content type, size, received-at -"
            + " and names the next page exactly while one remains")
    void aPageListsMetadataInReceivedOrder() {
        KycCase kycCase = store.aCase(T);
        KycDocument back = store.aDocument(kycCase, DocumentType.ID_CARD_BACK, DocumentContentType.PNG, bytes(3), T.plusSeconds(20));
        KycDocument front = store.aDocument(kycCase, DocumentType.ID_CARD_FRONT, DocumentContentType.JPEG, bytes(5), T.plusSeconds(10));
        KycDocument proof = store.aDocument(kycCase, DocumentType.PROOF_OF_ADDRESS, DocumentContentType.PDF, bytes(7), T.plusSeconds(30));
        store.aDocument(store.aCase(T), DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);

        KycDocumentReadDesk.CaseDocumentsPage first = desk.page(id(kycCase), null, "2");

        assertThat(first.items()).extracting(KycDocumentReadDesk.CaseDocument::documentId).containsExactly(id(front), id(back));
        assertThat(first.items().get(0)).isEqualTo(new KycDocumentReadDesk.CaseDocument(id(front), "ID_CARD_FRONT", "JPEG", 5,
                T.plusSeconds(10)));
        assertThat(first.nextCursor()).isNotNull();

        KycDocumentReadDesk.CaseDocumentsPage second = desk.page(id(kycCase), first.nextCursor(), "2");

        assertThat(second.items()).extracting(KycDocumentReadDesk.CaseDocument::documentId).containsExactly(id(proof));
        assertThat(second.nextCursor()).as("the last page names none").isNull();
        assertThat(desk.page(id(kycCase), null, "3").nextCursor())
                .as("a last page exactly full names none: the row read past it is what says so").isNull();
        assertThat(Arrays.stream(KycDocumentReadDesk.CaseDocument.class.getRecordComponents()).map(RecordComponent::getName))
                .as("metadata only: never content, never the checksum (a possession oracle over the bytes)")
                .containsExactly("documentId", "documentType", "contentType", "sizeBytes", "receivedAt");
    }

    @Test
    @DisplayName("every page is recorded once, kyc.CaseDocumentsListed in its read's transaction: the case, whether it followed a"
            + " cursor, how many it served")
    void everyPageIsRecordedOnce() {
        KycCase kycCase = store.aCase(T);
        for (int i = 0; i < 3; i++) {
            store.aDocument(kycCase, DocumentType.PASSPORT, DocumentContentType.PDF, bytes(i + 1), T.plusSeconds(i));
        }

        String next = desk.page(id(kycCase), null, "2").nextCursor();
        desk.page(id(kycCase), next, "2");

        assertThat(written).extracting(AuditRecord::operation).containsOnly(KycAuditAction.CASE_DOCUMENTS_LISTED);
        assertThat(written).extracting(record -> record.changeSummary().orElseThrow())
                .containsExactly("continued=false, served=2", "continued=true, served=1");
        assertThat(written).allSatisfy(record -> {
            assertThat(record.actor()).isEqualTo(REVIEWER);
            assertThat(record.targetType()).isEqualTo("KycCase");
            assertThat(record.targetId()).isEqualTo(id(kycCase));
            assertThat(record.correlationId()).isEqualTo(correlation);
        });
        assertThat(events).containsExactly("begin", "write:kyc.CaseDocumentsListed", "commit",
                "begin", "write:kyc.CaseDocumentsListed", "commit");
        assertThat(store.calls).as("metadata alone: no content was read").doesNotContain("readContent");
    }

    @Test
    @DisplayName("an unknown, a malformed and a non-v7 case are one 404, recording nothing and listing nothing; a case with no"
            + " document answers an empty page, recorded")
    void anUnknownCaseIsNotFound() {
        for (String id : List.of(IDS.next().toString(), "not-a-case", UUID.randomUUID().toString())) {
            assertThatThrownBy(() -> desk.page(id, null, null)).as(id).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));
        }
        assertThat(written).isEmpty();
        assertThat(store.calls).doesNotContain("documentsOf");

        KycDocumentReadDesk.CaseDocumentsPage empty = desk.page(id(store.aCase(T)), null, null);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        assertThat(written).singleElement().satisfies(record -> assertThat(record.changeSummary())
                .hasValue("continued=false, served=0"));
    }

    @Test
    @DisplayName("a limit outside 1 to 200 and an unreadable cursor are 422 api.ValidationFailed, nothing read or recorded")
    void aBadLimitOrCursorIsRefused() {
        KycCase kycCase = store.aCase(T);
        for (String limit : List.of("0", "201", "ten", "-1")) {
            assertThatThrownBy(() -> desk.page(id(kycCase), null, limit)).as(limit).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> desk.page(id(kycCase), "not-a-cursor", null)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        assertThat(written).isEmpty();
        assertThat(store.calls).isEmpty();
    }

    // ------------------------------------------------------------------ one document's content

    @Test
    @DisplayName("a document's content is read through DocumentAccess, its kyc.DocumentContentRead - the reviewer, the document,"
            + " its case, the correlation - committed before the bytes are returned; one record, the request marked")
    void theContentIsRecordedBeforeItIsServed() {
        KycCase kycCase = store.aCase(T);
        KycDocument passport = store.aDocument(kycCase, DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);

        KycDocumentReadDesk.Download download = desk.content(id(passport));

        assertThat(download.content()).isEqualTo(PASSPORT);
        assertThat(download.mediaType()).isEqualTo("application/pdf");
        assertThat(download.fileName()).as("the document's identifier, never its type or the customer")
                .isEqualTo(id(passport) + ".pdf");
        assertThat(events).as("the record committed with the read, before anything was returned")
                .containsExactly("begin", "write:kyc.DocumentContentRead", "commit");
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.DOCUMENT_CONTENT_READ);
            assertThat(record.actor()).isEqualTo(REVIEWER);
            assertThat(record.targetType()).isEqualTo("Document");
            assertThat(record.targetId()).isEqualTo(id(passport));
            assertThat(record.changeSummary()).hasValue("case=" + kycCase.id());
            assertThat(record.correlationId()).isEqualTo(correlation);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        });
        assertThat(request.getAttribute(RecordedReads.class.getName() + ".recorded"))
                .as("held at the answer by the module's own record").isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("each allow-listed type is served under its own media type and file extension")
    void eachTypeIsServedUnderItsOwn() {
        KycCase kycCase = store.aCase(T);
        KycDocument jpeg = store.aDocument(kycCase, DocumentType.ID_CARD_FRONT, DocumentContentType.JPEG, bytes(4), T);
        KycDocument png = store.aDocument(kycCase, DocumentType.ID_CARD_BACK, DocumentContentType.PNG, bytes(6), T);

        assertThat(desk.content(id(jpeg))).satisfies(download -> {
            assertThat(download.mediaType()).isEqualTo("image/jpeg");
            assertThat(download.fileName()).isEqualTo(id(jpeg) + ".jpg");
        });
        assertThat(desk.content(id(png))).satisfies(download -> {
            assertThat(download.mediaType()).isEqualTo("image/png");
            assertThat(download.fileName()).isEqualTo(id(png) + ".png");
        });
    }

    @Test
    @DisplayName("an unknown, a malformed and a non-v7 document are one 404, recording nothing: there is no content to account"
            + " for")
    void anUnknownDocumentIsNotFound() {
        for (String id : List.of(IDS.next().toString(), "../etc/passwd", UUID.randomUUID().toString())) {
            assertThatThrownBy(() -> desk.content(id)).as(id).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));
        }
        assertThat(written).isEmpty();
        assertThat(request.getAttribute(RecordedReads.class.getName() + ".recorded")).isNull();
    }

    @Test
    @DisplayName("a record that cannot be written serves nothing: the read rolls back and no byte is returned")
    void anUnwritableRecordServesNothing() {
        KycDocument passport = store.aDocument(store.aCase(T), DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);
        KycDocumentReadDesk failing = HermeticKycDocuments.desk(store, store.cases(), (unitOfWork, record) -> {
            events.add("write-failed");
            throw new IllegalStateException("the audit table is unreachable");
        }, IDS, CLOCK, events);

        assertThatThrownBy(() -> failing.content(id(passport))).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(new String(PASSPORT, StandardCharsets.US_ASCII));
        assertThat(events).containsExactly("begin", "write-failed", "rollback");
        assertThat(request.getAttribute(RecordedReads.class.getName() + ".recorded")).isNull();
    }

    @Test
    @DisplayName("a commit that fails serves nothing: the bytes are returned only after the record committed")
    void aFailedCommitServesNothing() {
        KycDocument passport = store.aDocument(store.aCase(T), DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);
        KycDocumentReadDesk failing = HermeticKycDocuments.desk(store, store.cases(), audit, IDS, CLOCK, events,
                KycDocumentReadDesk.ALLOW_LIST, HermeticKycDocuments.Commit.FAILS);

        assertThatThrownBy(() -> failing.content(id(passport))).isInstanceOf(TransactionSystemException.class);
        assertThat(events).containsExactly("begin", "write:kyc.DocumentContentRead", "commit-failed");
    }

    @Test
    @DisplayName("a declared type off the allow-list is refused inside the read's transaction: its record rolled back, nothing"
            + " served, the message naming the document and never a byte")
    void aTypeOffTheAllowListIsRefused() {
        KycDocument png = store.aDocument(store.aCase(T), DocumentType.ID_CARD_FRONT, DocumentContentType.PNG, PASSPORT, T);
        KycDocumentReadDesk narrower = HermeticKycDocuments.desk(store, store.cases(), audit, IDS, CLOCK, events,
                Map.of(DocumentContentType.PDF, KycDocumentReadDesk.ALLOW_LIST.get(DocumentContentType.PDF)),
                HermeticKycDocuments.Commit.SUCCEEDS);

        assertThatThrownBy(() -> narrower.content(id(png))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id(png)).hasMessageContaining("allow-list")
                .hasMessageNotContaining(new String(PASSPORT, StandardCharsets.US_ASCII));
        assertThat(events).as("the record was written in the read's unit of work, and rolled back with it")
                .containsExactly("begin", "write:kyc.DocumentContentRead", "rollback");
        assertThat(request.getAttribute(RecordedReads.class.getName() + ".recorded")).isNull();
    }

    @Test
    @DisplayName("the allow-list is PDF, JPEG and PNG under the media types their uploads declare, and the download produces"
            + " exactly those")
    void theAllowListIsPdfJpegPng() throws NoSuchMethodException {
        assertThat(KycDocumentReadDesk.ALLOW_LIST.keySet())
                .containsExactlyInAnyOrder(DocumentContentType.PDF, DocumentContentType.JPEG, DocumentContentType.PNG);
        KycDocumentReadDesk.ALLOW_LIST.forEach((type, served) ->
                assertThat(served.mediaType()).as(type.name()).isEqualTo(type.mediaType()));
        String[] produces = KycDocumentReadController.class.getMethod("readKycDocumentContent", String.class)
                .getAnnotation(GetMapping.class).produces();
        assertThat(Arrays.stream(produces).collect(Collectors.toSet())).isEqualTo(KycDocumentReadDesk.ALLOW_LIST.values()
                .stream().map(KycDocumentReadDesk.Served::mediaType).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("a download renders no byte, and hands out a copy of its bytes, never its own")
    void aDownloadRendersNoByte() {
        KycDocument passport = store.aDocument(store.aCase(T), DocumentType.PASSPORT, DocumentContentType.PDF, PASSPORT, T);
        KycDocumentReadDesk.Download download = desk.content(id(passport));

        assertThat(download.toString()).doesNotContain(new String(PASSPORT, StandardCharsets.US_ASCII))
                .isEqualTo("Download[" + id(passport) + ", application/pdf, " + PASSPORT.length + " bytes]");
        download.content()[0] = 0;
        assertThat(download.content()).isEqualTo(PASSPORT);
    }

    // -----------------------------------------------------------------

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) ('a' + length));
        return bytes;
    }

    private static String id(KycCase kycCase) {
        return kycCase.id().value().toString();
    }

    private static String id(KycDocument document) {
        return document.id().value().toString();
    }
}
