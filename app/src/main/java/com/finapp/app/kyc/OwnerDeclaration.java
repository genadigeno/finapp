package com.finapp.app.kyc;

import com.finapp.kyc.BeneficialOwner;
import com.finapp.kyc.BeneficialOwnerStore;
import com.finapp.kyc.ControlRole;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.Customer;
import com.finapp.party.PartyId;
import com.finapp.party.PartyKind;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Declares a beneficial owner onto a KYB case (`P2-TSK-015`) — the {@code app} orchestration,
 * because the declaration is precisely a cross-context act: the graph lives in {@code kyc},
 * and who the owner <em>is</em> (a Party, of a kind, with a live customer relationship) is
 * {@code party}'s to answer — the ADR-0035 shape, a second time.
 *
 * <h2>What a declaration pins, and the two recorded Phase 2 bounds</h2>
 *
 * <p>The owner's verification is <strong>their own KYC-kind case</strong>, resolved here and
 * pinned on the row ({@code INV-HIST-04}'s shape): the open case while one runs, else the
 * latest decided one — the owner's current verification standing, which is what the
 * organisation's decision will rest on. Two refusals are deliberate Phase 2 bounds rather
 * than domain truths, each recorded where it bites:
 *
 * <ul>
 *   <li><strong>An organisation owner is refused</strong> — the glossary's recursive graph is
 *       bounded at depth one: an owner who is an organisation needs their own KYB case, and
 *       letting {@code KYB} appear as a verification kind is the deliberate later act that
 *       lifts the bound (V008's composite FK is the schema's copy of it).
 *   <li><strong>An owner with no case is refused</strong> — the only verification pipeline
 *       that exists is customer-keyed (`P1-TSK-005` deliberately models owners who are
 *       <em>not</em> customers, and verifying one needs the subject-generalised case a later
 *       phase brings), so a declarable owner is a registered customer, whose case the
 *       registration consumer opened. Declaration never opens cases as a side effect: an
 *       auto-opened case would sit forever with nothing driving its checks, which is a
 *       blocked organisation wearing a progress label.
 * </ul>
 *
 * <p>The pre-reads here are advisory ordering for honest refusals; the authoritative checks —
 * kind, status, duplicates, the stake sum — run in the store <em>under the case-row lock</em>
 * ({@code BeneficialOwnerStore#declare}), which is what makes them answers rather than
 * guesses under N instances.
 */
public class OwnerDeclaration {

    /** What a declaration attempt came to. `P2-TSK-016`'s endpoint maps these onto HTTP. */
    public enum Declaration {
        DECLARED,
        ALREADY_DECLARED,
        /** No such case. */
        NOT_FOUND,
        /** The case exists and is a person's — a KYC case cannot grow owners. */
        NOT_A_KYB_CASE,
        /** The set is frozen: the case is at {@code READY_FOR_DECISION} or terminal. */
        CASE_NOT_ACCEPTING_OWNERS,
        /** The named party is an organisation — the bounded-depth refusal. */
        OWNER_NOT_A_NATURAL_PERSON,
        /**
         * The named party cannot be verified in Phase 2: unknown, no live customer
         * relationship, or no case — the registered-customer bound above.
         */
        OWNER_NOT_VERIFIABLE,
        /** The declared stake would take the case's total past 10000 basis points. */
        STAKE_EXCEEDS_WHOLE
    }

    private final KycCaseStore<Connection> cases;
    private final BeneficialOwnerStore<Connection> owners;
    private final PartyStore<Connection> parties;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;

    public OwnerDeclaration(
            KycCaseStore<Connection> kycCaseStore,
            BeneficialOwnerStore<Connection> beneficialOwnerStore,
            PartyStore<Connection> partyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.owners =
                Objects.requireNonNull(
                        beneficialOwnerStore, "beneficialOwnerStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /**
     * Declares {@code ownerPartyId} onto {@code kybCaseId}, in one transaction with its audit
     * record ({@code kyc.OwnerDeclared}).
     *
     * <p>Idempotent by {@code (case, owner party)}: a lost-response retry lands on
     * {@link Declaration#ALREADY_DECLARED} with no second row and no second audit record.
     */
    public Declaration declare(
            KycCaseId kybCaseId,
            PartyId ownerPartyId,
            OptionalInt stakeBasisPoints,
            Optional<ControlRole> controlRole) {
        Objects.requireNonNull(kybCaseId, "kybCaseId must not be null");
        Objects.requireNonNull(ownerPartyId, "ownerPartyId must not be null");
        Objects.requireNonNull(stakeBasisPoints, "stakeBasisPoints must not be null");
        Objects.requireNonNull(controlRole, "controlRole must not be null");
        return units.inTransaction(
                unitOfWork -> {
                    Optional<KycCase> kybCase = cases.findById(unitOfWork, kybCaseId);
                    if (kybCase.isEmpty()) {
                        return Declaration.NOT_FOUND;
                    }
                    if (kybCase.get().kind() != KycCaseKind.KYB) {
                        return Declaration.NOT_A_KYB_CASE;
                    }
                    Optional<PartyKind> ownerKind = parties.kindOf(unitOfWork, ownerPartyId);
                    if (ownerKind.isEmpty()) {
                        return Declaration.OWNER_NOT_VERIFIABLE;
                    }
                    if (ownerKind.get() != PartyKind.PERSON) {
                        return Declaration.OWNER_NOT_A_NATURAL_PERSON;
                    }
                    Optional<KycCase> verification =
                            parties.findLiveCustomerFor(unitOfWork, ownerPartyId)
                                    .map(Customer::id)
                                    .flatMap(
                                            customerId ->
                                                    cases.findLatestFor(
                                                            unitOfWork, customerId.value()));
                    if (verification.isEmpty()) {
                        return Declaration.OWNER_NOT_VERIFIABLE;
                    }
                    BeneficialOwner owner =
                            BeneficialOwner.declare(
                                    ids,
                                    clock,
                                    kybCaseId,
                                    ownerPartyId.value(),
                                    verification.get().id(),
                                    stakeBasisPoints,
                                    controlRole);
                    return switch (owners.declare(unitOfWork, owner)) {
                        case DECLARED -> {
                            audit(unitOfWork, owner);
                            yield Declaration.DECLARED;
                        }
                        case ALREADY_DECLARED -> Declaration.ALREADY_DECLARED;
                        case CASE_NOT_ACCEPTING -> Declaration.CASE_NOT_ACCEPTING_OWNERS;
                        case STAKE_EXCEEDS_WHOLE -> Declaration.STAKE_EXCEEDS_WHOLE;
                    };
                });
    }

    private void audit(Connection unitOfWork, BeneficialOwner owner) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        // The declarant's own act, under the scope the caller established -
                        // never the platform. P2-TSK-016's endpoint brings the interceptor-
                        // proven person.
                        SecurityContext.require(),
                        Instant.now(clock),
                        KycAuditAction.KYB_OWNER_DECLARED,
                        "KycCase",
                        owner.caseId().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation(),
                        // Identifiers, enumerated names and numbers only (INV-AUD-02).
                        Optional.of(
                                "owner=" + owner.id()
                                        + " ownerParty=" + owner.ownerPartyId()
                                        + " verification=" + owner.verificationCaseId()
                                        + owner.stakeBasisPoints()
                                                .stream()
                                                .mapToObj(stake -> " stakeBp=" + stake)
                                                .findFirst()
                                                .orElse("")
                                        + owner.controlRole()
                                                .map(role -> " role=" + role.name())
                                                .orElse(""))));
    }

    private static com.finapp.sharedkernel.correlation.CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a declaration must run inside a correlation scope: the"
                                                + " record carries the flow's identifier"))
                .correlationId();
    }
}
