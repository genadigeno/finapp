package com.finapp.app.kyc;

import com.finapp.kyc.DocumentBytes;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentId;
import com.finapp.kyc.DocumentStore;
import com.finapp.kyc.DocumentType;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycDocument;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * kyc's documents and cases in memory, for the reviewer's document suites (`P17-TSK-125`): {@link DocumentStore} as the port
 * states it - a page in received order, the document id breaking a tie, strictly after the position given, metadata only; the
 * content of one by id - and {@link KycCaseStore}'s {@code findById} alone. Every call is named in {@link #calls}.
 */
final class InMemoryKycDocuments implements DocumentStore<Connection> {

    final List<String> calls = new ArrayList<>();
    private final IdGenerator ids;
    private final Map<KycCaseId, KycCase> cases = new HashMap<>();
    private final Map<DocumentId, byte[]> contents = new LinkedHashMap<>();
    private final Map<DocumentId, KycDocument> documents = new LinkedHashMap<>();

    InMemoryKycDocuments(IdGenerator ids) {
        this.ids = ids;
    }

    /** A case, open for review. */
    KycCase aCase(Instant at) {
        KycCase kycCase = KycCase.rehydrate(KycCaseId.of(ids.next()), ids.next(), KycCaseKind.KYC, KycCaseStatus.IN_REVIEW,
                new KycPolicyVersion("kyc-2026-09"), at, at);
        cases.put(kycCase.id(), kycCase);
        return kycCase;
    }

    /** A document on {@code kycCase}, received at {@code at}, holding {@code content}. */
    KycDocument aDocument(KycCase kycCase, DocumentType type, DocumentContentType contentType, byte[] content, Instant at) {
        DocumentBytes bytes = DocumentBytes.of(content);
        KycDocument document = KycDocument.rehydrate(DocumentId.of(ids.next()), kycCase.id(), type, contentType,
                sha256(content), bytes.length(), at);
        documents.put(document.id(), document);
        contents.put(document.id(), content.clone());
        return document;
    }

    /** {@link KycCaseStore}, answering {@code findById} from this store's cases and refusing every other call. */
    @SuppressWarnings("unchecked")
    KycCaseStore<Connection> cases() {
        return (KycCaseStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {KycCaseStore.class},
                (proxy, method, args) -> {
                    calls.add("cases." + method.getName());
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(cases.get((KycCaseId) args[1]));
                    }
                    throw new UnsupportedOperationException("KycCaseStore." + method.getName());
                });
    }

    @Override
    public Capture appendOrConverge(Connection unitOfWork, KycDocument document, DocumentBytes content) {
        throw new UnsupportedOperationException("no suite here appends");
    }

    @Override
    public Optional<DocumentContent> readContent(Connection unitOfWork, DocumentId id) {
        calls.add("readContent");
        return Optional.ofNullable(documents.get(id)).map(document -> new DocumentContent(document, contents.get(id)));
    }

    @Override
    public List<KycDocument> documentsOf(Connection unitOfWork, KycCaseId caseId, Optional<DocumentPosition> after, int limit) {
        calls.add("documentsOf");
        Comparator<KycDocument> received = Comparator.comparing(KycDocument::uploadedAt)
                .thenComparing(document -> document.id().value());
        return documents.values().stream()
                .filter(document -> document.caseId().equals(caseId))
                .filter(document -> after.map(position -> {
                    int byInstant = document.uploadedAt().compareTo(position.uploadedAt());
                    return byInstant > 0 || (byInstant == 0 && document.id().value().compareTo(position.id()) > 0);
                }).orElse(true))
                .sorted(received)
                .limit(limit)
                .toList();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
