package com.finapp.app.kyc;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.consent.ConsentPurpose;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.kyc.CaseKindResolver;
import com.finapp.kyc.CaseOpeningTrail;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.Customer;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The person's own verification case (`P2-TSK-006`): ensuring it exists, and reading its status.
 *
 * <h2>Ownership is enforced by there being no parameter</h2>
 *
 * <p>The {@code /v1/me} shape (`P1-TSK-030`): no request names a customer or a case — the chain
 * is {@code Session.identityId() → Identity.partyId() → the party's LIVE Customer → its case},
 * entirely derived, so an attacker has nothing to point at somebody else's case with.
 *
 * <h2>Opening is the first consent-gated capability over HTTP ({@code INV-CNS-01})</h2>
 *
 * <p>{@link ConsentGate#require} runs <strong>before</strong> the open, on the same unit of
 * work, so the decision and the act it authorises are one snapshot ({@code INV-CNS-03}) — and a
 * refusal throws {@link ConsentNotGrantedException} out of the transaction, rolling it back:
 * nothing written, structurally. The refusal reaches the client as
 * {@code 409 consent.ConsentRequired}, mapped in {@code ApiErrorHandler}; which of the three
 * causes refused — absence, withdrawal, a grant lapsed by a re-consent-demanding version — stays
 * indistinguishable, because the exception carries the purpose and nothing more.
 *
 * <p><strong>The GET is deliberately not gated</strong>: reading the status of one's own case is
 * a mirror of processing, not processing — refusing a withdrawn person the answer "your case is
 * IN_PROGRESS" would withhold their own state from them while the processing question is what
 * consent governs.
 *
 * <h2>"Ensure my case exists", and what a decided customer's POST means</h2>
 *
 * <p>{@code openOrConverge} against the one-open-case index: a double-tap, a retry and the race
 * against the auto-open consumer (`P2-TSK-007`) all land on one case, and the losers are handed
 * the winner's rather than an error. A customer whose latest case is <strong>terminal</strong>
 * opens a <em>successor</em> case — the index frees the slot on decision (`P2-TSK-005`'s
 * demonstrated freed slot), and changed-circumstances re-verification is a new case by design
 * ({@code INV-LIFE-04}); it is gated by consent exactly like the first.
 *
 * <h2>Created records and announces, as the person; converged is silent</h2>
 *
 * <p>The created branch writes {@code kyc.CaseOpened} and publishes {@code kyc.KycCaseOpened}
 * through {@link CaseOpeningTrail} — the same definition the consumer door uses — under the
 * interceptor's proven scope, so the record names the <strong>person</strong>: this door is
 * their own act, unlike the consumer's policy act ({@code AuditNamesTheActorDatabaseTest}'s
 * sweep is the control). The event's cause is the request, the {@code PartyRegistration}
 * flow-root idiom. A converged open records nothing ({@code INV-KYC-03}'s one opening record).
 *
 * <h2>{@code findLatestFor}, not {@code findOpenFor}, on the read</h2>
 *
 * <p>{@code KybService}'s reasoning verbatim: a decided case is the answer the person was
 * waiting for — {@code APPROVED} is their all-clear and {@code REJECTED} must not arrive as a
 * 404. The status crosses the boundary <strong>shaped</strong>
 * ({@code CustomerFacingCaseStatus}), so a screening hit is indistinguishable from ordinary
 * processing in anything this surface answers.
 */
public class KycCaseService {

    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final KycCaseStore<Connection> cases;
    private final CaseKindResolver<Connection> kinds;
    private final ConsentGate<Connection> consentGate;
    private final CaseOpeningTrail trail;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;

    public KycCaseService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            KycCaseStore<Connection> kycCaseStore,
            CaseKindResolver<Connection> caseKindResolver,
            ConsentGate<Connection> consentGate,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.identities = Objects.requireNonNull(identityStore, "identityStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.kinds = Objects.requireNonNull(caseKindResolver, "caseKindResolver must not be null");
        this.consentGate = Objects.requireNonNull(consentGate, "consentGate must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.trail =
                new CaseOpeningTrail(
                        idGenerator,
                        clock,
                        Objects.requireNonNull(auditWriter, "auditWriter must not be null"),
                        Objects.requireNonNull(outboxWriter, "outboxWriter must not be null"));
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /**
     * Ensures the caller's case exists — creating it or converging on the open one — in one
     * transaction: resolve, gate, open, and (created only) record and announce.
     *
     * @throws ConsentNotGrantedException when no current {@code KYC_PROCESSING} basis exists;
     *     the transaction rolls back, so a refusal writes nothing
     */
    public Opening open(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return units.inTransaction(
                unitOfWork -> {
                    Optional<Customer> customer = resolve(unitOfWork, current);
                    if (customer.isEmpty()) {
                        return Opening.NOT_RESOLVABLE;
                    }
                    UUID customerId = customer.get().id().value();
                    // The gate, before anything is written (INV-CNS-01): the basis is the
                    // PARTY's fact, already in hand from the chain.
                    consentGate.require(
                            unitOfWork,
                            customer.get().partyId().value(),
                            ConsentPurpose.KYC_PROCESSING);
                    // The kind stays party's fact through the same resolver the consumer door
                    // uses — this chain resolves the person's own customer, so it answers KYC
                    // by construction, and deriving it keeps that a fact rather than a wish.
                    KycCaseStore.Opening opening =
                            cases.openOrConverge(
                                    unitOfWork,
                                    KycCase.open(
                                            ids,
                                            clock,
                                            customerId,
                                            kinds.kindFor(unitOfWork, customerId)));
                    if (opening.created()) {
                        trail.record(unitOfWork, opening.kycCase(), causedByTheRequest());
                    }
                    return new Opening.Opened(opening.kycCase());
                });
    }

    /** The caller's latest case — open or decided — read on its own transaction, ungated. */
    public View view(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return units.inTransaction(
                unitOfWork -> {
                    Optional<Customer> customer = resolve(unitOfWork, current);
                    if (customer.isEmpty()) {
                        return View.NOT_RESOLVABLE;
                    }
                    return cases.findLatestFor(unitOfWork, customer.get().id().value())
                            .<View>map(View.Found::new)
                            .orElse(View.NO_CASE);
                });
    }

    private Optional<Customer> resolve(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(Identity::partyId)
                .flatMap(party -> parties.findLiveCustomerFor(unitOfWork, PartyId.of(party)));
    }

    /**
     * The flow-root causation ({@code PartyRegistration.causedByTheRequest}'s idiom): at an HTTP
     * root nothing caused this but the request, and the correlation identifier is on the audit
     * record of the same transaction — so the causal chain terminates at something real.
     */
    private static CausationId causedByTheRequest() {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "no correlation in scope; the ingress filter"
                                                        + " enters one per request"));
        return CausationId.of(correlation.correlationId().value());
    }

    /** What ensuring the case came to. A closed set, so the controller maps exhaustively. */
    public sealed interface Opening {

        /** The caller's case — created by this call, or the open one converged on. */
        record Opened(KycCase kycCase) implements Opening {}

        /**
         * The session resolves to no live customer — the {@code MeController} not-resolvable
         * case: structurally possible (ADR-0029 declines the cross-schema FK), never produced
         * by registration, logged as ours.
         */
        record NotResolvable() implements Opening {}

        Opening NOT_RESOLVABLE = new NotResolvable();
    }

    /** What the read came to. */
    public sealed interface View {

        record Found(KycCase kycCase) implements View {}

        /** The customer never had a case: an honest 404, with POST as the remedy. */
        record NoCase() implements View {}

        record NotResolvable() implements View {}

        View NO_CASE = new NoCase();
        View NOT_RESOLVABLE = new NotResolvable();
    }
}
