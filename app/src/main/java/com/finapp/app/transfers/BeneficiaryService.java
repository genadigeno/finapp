package com.finapp.app.transfers;

import com.finapp.app.telemetry.TransferMetrics;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.Beneficiary;
import com.finapp.transfers.BeneficiaryCreation;
import com.finapp.transfers.BeneficiaryId;
import com.finapp.transfers.BeneficiaryStore;
import com.finapp.transfers.TransfersAuditAction;
import com.finapp.transfers.TransfersErrorCode;
import com.finapp.transfers.UnknownBeneficiaryDestinationException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/beneficiaries` slice behind {@link BeneficiaryController} (`P4-TSK-007`).
 *
 * <h2>The caller can name nobody as an owner</h2>
 *
 * <p>Every operation resolves {@code Session → Identity → Party} inside its own transaction —
 * a beneficiary is the <strong>Party's</strong> (`P4-TSK-006`), so the chain is one hop shorter
 * than the accounts slice's and deliberately has <strong>no live-customer step</strong>: saving
 * an address-book entry is not a customer capability, and no verification gate is declared for
 * it. The one path identifier ({@code {id}} on the removal) is resolved through statements
 * whose ownership predicate is {@code party_id = ?} (ADR-0031).
 *
 * <h2>Creation is the step-up point, conditionally</h2>
 *
 * <p><strong>{@code MULTI_FACTOR} is required exactly of an identity that has a factor</strong>
 * — the {@code CredentialChange} conditional restated at this orchestration, because the check
 * spans two modules only this surface joins ({@code identity}'s enrolments and assurance,
 * {@code transfers}' creation) and a static annotation would lock out every password-only
 * customer (`P1-TSK-033`'s finding, plan §11). Judged from an authoritative read inside the
 * creation's own transaction, <strong>before any write</strong>, so the refusal commits
 * nothing; the distinct, actionable {@code identity.AssuranceRequired} discloses only what the
 * caller already knows — they enrolled MFA and are holding a password session. The value
 * threshold the delivery plan once named is deliberately absent (a per-currency versioned
 * policy artefact with nothing to calibrate it — the `P3-TSK-021` argument); the structural
 * trigger is what ships.
 */
public final class BeneficiaryService {

    private final BeneficiaryCreation<Connection> creation;
    private final BeneficiaryStore<Connection> beneficiaries;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final IdentityStore<Connection> identities;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final TransferMetrics metrics;

    public BeneficiaryService(
            BeneficiaryCreation<Connection> creation,
            BeneficiaryStore<Connection> beneficiaries,
            MfaEnrolmentStore<Connection> enrolments,
            IdentityStore<Connection> identities,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate beneficiaryTransactions,
            DataSource dataSource,
            TransferMetrics metrics) {
        this.creation = Objects.requireNonNull(creation, "creation must not be null");
        this.beneficiaries =
                Objects.requireNonNull(beneficiaries, "beneficiaries must not be null");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(
                        beneficiaryTransactions, "beneficiaryTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /** The rendered saved destination — never more than identifiers, the name and an instant. */
    public record BeneficiaryView(
            String id, String displayName, String destinationAccountId, String createdAt) {

        static BeneficiaryView of(Beneficiary beneficiary) {
            return new BeneficiaryView(
                    beneficiary.id().value().toString(),
                    beneficiary.displayName(),
                    beneficiary.destinationAccountId().toString(),
                    beneficiary.createdAt().toString());
        }
    }

    /**
     * Saves the destination for the caller's party, or converges on the live row already
     * holding the (party, destination) slot — 201 either way (the convergence idiom,
     * `P2-TSK-016`); what distinguishes creation is the records, never the answer. A converged
     * answer carries the <em>existing</em> row, its existing display name included: renaming is
     * remove-and-recreate (`P4-TSK-006`).
     */
    public BeneficiaryView create(Session current, String displayName, String destinationRaw) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(destinationRaw, "destinationRaw must not be null");

        // Malformed folds into the unknown-destination refusal below (malformed-equals-absent
        // for a third party's identifier), decided before any transaction opens.
        UUID destination;
        try {
            destination = UUID.fromString(destinationRaw);
        } catch (IllegalArgumentException malformed) {
            throw unknownDestination();
        }

        // The AccountService.openedNow shape (P3-TSK-020): the acting flag crosses the
        // transaction boundary so the count can wait for the commit.
        AtomicBoolean addedNow = new AtomicBoolean(false);
        BeneficiaryView view = inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    // The step-up gate, before any write: the ApiException aborts the
                    // transaction, so a refused creation commits nothing.
                    requireConditionalAssurance(unitOfWork, current);
                    BeneficiaryStore.Creation created;
                    try {
                        created =
                                creation.createOrConverge(
                                        unitOfWork, partyId, displayName, destination);
                    } catch (UnknownBeneficiaryDestinationException unknown) {
                        throw unknownDestination();
                    } catch (IllegalArgumentException invalidName) {
                        // The domain's sharper name rule (control/format/surrogate/private-use/
                        // unassigned characters) - the bound itself is refused by the boundary
                        // annotation. Names the field, never the RESTRICTED-PII value.
                        throw new ApiException(
                                PlatformErrorCode.VALIDATION_FAILED,
                                "A beneficiary display name was refused by the domain rule",
                                "displayName must not contain control or formatting"
                                        + " characters.");
                    }
                    if (created.created()) {
                        audit(
                                unitOfWork,
                                TransfersAuditAction.BENEFICIARY_ADDED,
                                created.beneficiary().id(),
                                Optional.of(
                                        "destination="
                                                + created.beneficiary()
                                                        .destinationAccountId()));
                        addedNow.set(true);
                    }
                    return BeneficiaryView.of(created.beneficiary());
                });
        // After the commit and only for the acting call (P3-TSK-020): a converged retry -
        // the different-display-name converge included - is never throughput.
        if (addedNow.get()) {
            metrics.beneficiaryAdded();
        }
        return view;
    }

    /** The caller's live beneficiaries, oldest first. */
    public List<BeneficiaryView> list(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return inOneTransaction(
                unitOfWork ->
                        beneficiaries
                                .listLiveFor(unitOfWork, partyOf(unitOfWork, current))
                                .stream()
                                .map(BeneficiaryView::of)
                                .toList());
    }

    /**
     * Removes the caller's beneficiary — or converges on one already removed, which is the
     * retry story of a lost {@code DELETE} response.
     *
     * @return {@code true} when the identifier names a row of the caller's (removed now, or
     *     already removed — the converging 204); {@code false} when it names nothing of theirs
     *     — unknown and not-yours one indistinguishable answer, the surface's one 404
     */
    public boolean delete(Session current, BeneficiaryId beneficiary) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        AtomicBoolean removedNow = new AtomicBoolean(false);
        boolean owned = inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    if (beneficiaries.remove(
                            unitOfWork, beneficiary, partyId, Instant.now(clock))) {
                        // The winning removal is the act; a converged retry and a stranger's
                        // attempt moved nothing and record nothing.
                        audit(
                                unitOfWork,
                                TransfersAuditAction.BENEFICIARY_REMOVED,
                                beneficiary,
                                Optional.empty());
                        removedNow.set(true);
                        return true;
                    }
                    // The conditional matched nothing: the caller's already-removed row
                    // converges, anything else is the one 404 (P4-TSK-006's port shape).
                    return beneficiaries
                            .findOwned(unitOfWork, beneficiary, partyId)
                            .isPresent();
                });
        // After the commit and only for the winning removal (P3-TSK-020).
        if (removedNow.get()) {
            metrics.beneficiaryRemoved();
        }
        return owned;
    }

    // -----------------------------------------------------------------

    /**
     * {@code MULTI_FACTOR} required exactly of an identity that has an active factor — an
     * authoritative read per decision, never a cache (the {@code ConsentGate} discipline).
     */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor =
                enrolments
                        .findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP)
                        .isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(
                    IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A beneficiary creation from an MFA-enrolled identity requires a"
                            + " MULTI_FACTOR session");
        }
    }

    private static ApiException unknownDestination() {
        // One shape for unknown and malformed alike: byte-identical, so neither is readable
        // from the response (TransfersErrorCode.UNKNOWN_DESTINATION's reasoning).
        return new ApiException(
                TransfersErrorCode.UNKNOWN_DESTINATION,
                "A beneficiary destination resolved to no customer wallet");
    }

    /** {@code Session → Identity → Party}: the {@code ProfileService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A proven session resolved to no identity; registration"
                                                + " should make this impossible"));
    }

    /**
     * The person's own act, attributed to the person ({@code SecurityContext.require()} — the
     * interceptor's actor, never {@code enterSystem()}). Identifiers only: the display name is
     * {@code RESTRICTED-PII} and never enters the trail ({@code INV-AUD-02}).
     */
    private void audit(
            Connection unitOfWork,
            TransfersAuditAction action,
            BeneficiaryId beneficiary,
            Optional<String> changeSummary) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A beneficiary act must run inside a correlation"
                                                        + " scope: the audit record carries the"
                                                        + " identifier (P0-TSK-014)"));
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        action,
                        "transfers.Beneficiary",
                        beneficiary.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        changeSummary));
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
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
}
