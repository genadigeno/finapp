package com.finapp.app.kyc;

import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.kyc.DocumentBytes;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentStore;
import com.finapp.kyc.DocumentType;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycDocument;
import com.finapp.party.Customer;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A person uploads a document onto their own open KYC case (`P2-TSK-008`).
 *
 * <h2>Ownership is enforced by there being no parameter</h2>
 *
 * <p>The endpoint takes no path variable, no query parameter and no body field naming a case or a
 * customer. The chain is entirely derived — the {@code /v1/me} shape (`P1-TSK-030`):
 *
 * <pre>
 *   Bearer token → SessionAuthenticationInterceptor → proven Session
 *                → Session.identityId() → Identity.partyId()
 *                → the party's LIVE Customer → that customer's OPEN case
 * </pre>
 *
 * <p>An attacker cannot name a victim's case, because the API gives them nothing to name one
 * with; the test proves the resolution chain rather than a refusal.
 *
 * <h2>Why this lives in {@code app}</h2>
 *
 * <p>The upload spans three bounded contexts — the session is {@code identity}'s, the Customer is
 * {@code party}'s, the case and document are {@code kyc}'s — and no module may see a sibling. So
 * {@code app} contributes the reads and one transaction and owns no rule of its own, exactly as
 * {@code RegistrationService} and {@code ProfileService} do.
 *
 * <h2>One transaction, and what it buys</h2>
 *
 * <p>The case lookup and the document insert commit together or not at all: a failure anywhere
 * leaves no orphaned evidence ({@code PHASE_2_PLAN.md} failure scenario 4), and the schema's FK
 * makes the orphan impossible even for a writer that skips this service. The checksum and the
 * encryption are CPU work measured in microseconds over a bounded 512 KiB — nothing here is the
 * `P1-TSK-026` derivation-outside-the-transaction case, and splitting the capture out would buy
 * a two-phase shape for no held-connection relief worth having.
 *
 * <h2>One accepted race, stated rather than glossed</h2>
 *
 * <p>{@code findOpenFor} is a plain read; a decision (`P2-TSK-013`) may commit between it and the
 * insert, landing this document on a just-decided case. That is harmless and permitted: no
 * invariant forbids evidence recorded after a decision — the decision references its evidence
 * explicitly ({@code INV-KYC-02}), so a late document simply is not referenced. Preventing it
 * would take {@code FOR UPDATE} on the case row for every upload, serialising all uploads against
 * all case writes to enforce a rule that does not exist.
 */
public class DocumentUploadService {

    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final KycCaseStore<Connection> cases;
    private final DocumentStore<Connection> documents;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public DocumentUploadService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            KycCaseStore<Connection> kycCaseStore,
            DocumentStore<Connection> documentStore,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.identities = Objects.requireNonNull(identityStore, "identityStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.documents = Objects.requireNonNull(documentStore, "documentStore must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(kycTransactions, "kycTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** Uploads a document onto the caller's open case, or converges on the identical one. */
    public Result upload(
            Session current,
            DocumentType type,
            DocumentContentType contentType,
            DocumentBytes content) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");

        return inOneTransaction(
                unitOfWork -> {
                    Optional<Customer> customer =
                            identities
                                    .findById(unitOfWork, current.identityId())
                                    .flatMap(
                                            identity ->
                                                    parties.findLiveCustomerFor(
                                                            unitOfWork,
                                                            PartyId.of(identity.partyId())));
                    if (customer.isEmpty()) {
                        return Result.NOT_RESOLVABLE;
                    }
                    Optional<KycCase> open =
                            cases.findOpenFor(unitOfWork, customer.get().id().value());
                    if (open.isEmpty()) {
                        return Result.NO_OPEN_CASE;
                    }
                    KycDocument captured =
                            KycDocument.capture(
                                    ids, clock, open.get().id(), type, contentType, content);
                    return new Result.Uploaded(
                            documents.appendOrConverge(unitOfWork, captured, content).document());
                });
    }

    private <T> T inOneTransaction(Function<Connection, T> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }

    /** What an upload can come to. A closed set, so the controller's mapping is exhaustive. */
    public sealed interface Result {

        /** The document now on the case — created by this call, or converged on by a retry. */
        record Uploaded(KycDocument document) implements Result {}

        /**
         * The chain resolved a customer whose every case is decided ({@code INV-LIFE-04}): the
         * remedy is a new case, so this is actionable and distinct — {@code kyc.NoOpenCase}.
         */
        record NoOpenCase() implements Result {}

        /**
         * The session resolves to no live customer. Structurally possible (ADR-0029 declines the
         * cross-schema FK) and never produced by registration; the {@code MeController}
         * not-resolvable case, one hop further along the chain.
         */
        record NotResolvable() implements Result {}

        Result NO_OPEN_CASE = new NoOpenCase();
        Result NOT_RESOLVABLE = new NotResolvable();
    }
}
