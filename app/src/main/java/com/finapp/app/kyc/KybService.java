package com.finapp.app.kyc;

import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.kyc.BeneficialOwnerStore;
import com.finapp.kyc.ControlRole;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.OrganisationRegistration;
import com.finapp.party.PartyId;
import com.finapp.party.PartyName;
import com.finapp.party.PartyStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The organisation's acting person's surface (`P2-TSK-016`): registering the organisation,
 * declaring owners onto its KYB case, and seeing the graph's progress.
 *
 * <h2>Every resource is session-derived</h2>
 *
 * <p>No request names an organisation, a customer or a case — the {@code /v1/me} shape
 * (`P1-TSK-030`): the chain is Session → Identity → the acting person's Party → the organisation
 * that party registered ({@code organisationRegisteredBy}, whose statement carries the ownership
 * predicate) → its latest case. A stranger has nothing to point at another organisation with;
 * their own chain simply resolves to nothing. The one request-supplied identifier anywhere is
 * {@code ownerPartyId} — the declaration's <em>subject</em>, never the resource acted on.
 *
 * <h2>The KYB case is opened in the registration's own transaction</h2>
 *
 * <p>Deterministic for the acting person (the case exists the moment the 201 does), and
 * convergent with the auto-open consumer (`P2-TSK-007`): the {@code party.CustomerOpened} this
 * registration publishes will have the consumer attempt the same open later and converge on the
 * one-open-case index — the `P2-TSK-005` semantics, from the other direction.
 *
 * <h2>{@code findLatestFor}, not {@code findOpenFor}</h2>
 *
 * <p>The acting person must still see a decided case — APPROVED is the answer they were waiting
 * for and REJECTED is one they must not discover by a 404 — and the declaration path lets the
 * store's frozen-set check answer authoritatively under its lock rather than pre-filtering here.
 */
public class KybService {

    /** What a view or declaration resolved to when the chain finds no organisation. */
    public sealed interface Registration {
        record Registered(PartyId organisation, CustomerId customer) implements Registration {}

        record Converged(PartyId organisation, CustomerId customer) implements Registration {}

        Registration NAME_CONFLICT = new NameConflict();

        record NameConflict() implements Registration {}
    }

    /** The acting person's view: the case raw, its owners with verification status. */
    public record KybFile(KycCase kybCase, List<BeneficialOwnerStore.DeclaredOwner> owners) {}

    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final OrganisationRegistration organisationRegistration;
    private final com.finapp.kyc.KycCaseStore<Connection> cases;
    private final BeneficialOwnerStore<Connection> owners;
    private final OwnerDeclaration ownerDeclaration;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;

    public KybService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            OrganisationRegistration organisationRegistration,
            com.finapp.kyc.KycCaseStore<Connection> kycCaseStore,
            BeneficialOwnerStore<Connection> beneficialOwnerStore,
            OwnerDeclaration ownerDeclaration,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.identities = Objects.requireNonNull(identityStore, "identityStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.organisationRegistration =
                Objects.requireNonNull(
                        organisationRegistration, "organisationRegistration must not be null");
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.owners =
                Objects.requireNonNull(beneficialOwnerStore, "beneficialOwnerStore must not be null");
        this.ownerDeclaration =
                Objects.requireNonNull(ownerDeclaration, "ownerDeclaration must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /**
     * Registers the caller's organisation, or converges on the one they already registered —
     * organisation, customer, registrant record, audit, events and the KYB case in one
     * transaction, or none of it.
     */
    public Optional<Registration> register(Session current, PartyName name) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(name, "name must not be null");
        return units.inTransaction(
                unitOfWork ->
                        actingParty(unitOfWork, current)
                                .map(
                                        party -> {
                                            OrganisationRegistration.Result result =
                                                    organisationRegistration.registerOrConverge(
                                                            unitOfWork, party, name);
                                            return switch (result) {
                                                case OrganisationRegistration.Result.Registered
                                                                created -> {
                                                    // The KYB case, atomically with the
                                                    // organisation it verifies. The kind is
                                                    // known here by construction - this
                                                    // transaction just wrote the ORGANISATION
                                                    // party - so the resolver port is not
                                                    // consulted twice for one fact.
                                                    cases.openOrConverge(
                                                            unitOfWork,
                                                            KycCase.open(
                                                                    ids,
                                                                    clock,
                                                                    created.customerId().value(),
                                                                    KycCaseKind.KYB));
                                                    yield (Registration)
                                                            new Registration.Registered(
                                                                    created.partyId(),
                                                                    created.customerId());
                                                }
                                                case OrganisationRegistration.Result.Converged
                                                                existing ->
                                                        new Registration.Converged(
                                                                existing.partyId(),
                                                                existing.customerId());
                                                case OrganisationRegistration.Result.NameConflict
                                                                ignored ->
                                                        Registration.NAME_CONFLICT;
                                            };
                                        }));
    }

    /**
     * Declares an owner onto the caller's organisation's case.
     *
     * <p>Resolution and declaration are two transactions on purpose: the chain is a stable read
     * (the registrant record is append-only and a customer's case history only grows), and the
     * declaration's own transaction re-validates everything that matters under the case-row
     * lock (`P2-TSK-015`'s lock-then-look), so nothing rests on this read staying true.
     */
    public Optional<OwnerDeclaration.Declaration> declareOwner(
            Session current,
            PartyId ownerPartyId,
            OptionalInt stakeBasisPoints,
            Optional<ControlRole> controlRole) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(ownerPartyId, "ownerPartyId must not be null");
        return organisationCase(current)
                .map(
                        kybCase ->
                                ownerDeclaration.declare(
                                        kybCase, ownerPartyId, stakeBasisPoints, controlRole));
    }

    /** The acting person's view of their organisation's case and graph, if they have one. */
    public Optional<KybFile> view(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return units.inTransaction(
                unitOfWork ->
                        organisationCustomer(unitOfWork, current)
                                .flatMap(
                                        customer ->
                                                cases.findLatestFor(
                                                        unitOfWork, customer.id().value()))
                                .map(
                                        kybCase ->
                                                new KybFile(
                                                        kybCase,
                                                        owners.ownersOf(
                                                                unitOfWork, kybCase.id()))));
    }

    // -----------------------------------------------------------------

    private Optional<KycCaseId> organisationCase(Session current) {
        return units.inTransaction(
                unitOfWork ->
                        organisationCustomer(unitOfWork, current)
                                .flatMap(
                                        customer ->
                                                cases.findLatestFor(
                                                        unitOfWork, customer.id().value()))
                                .map(KycCase::id));
    }

    private Optional<Customer> organisationCustomer(Connection unitOfWork, Session current) {
        return actingParty(unitOfWork, current)
                .flatMap(party -> parties.organisationRegisteredBy(unitOfWork, party));
    }

    private Optional<PartyId> actingParty(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> PartyId.of(identity.partyId()));
    }
}
