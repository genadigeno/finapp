package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Regulatory obligation versions - configuration as data, under four eyes (`P14-TSK-018`; ADR-0118 decision 1; {@code PHASE_14_PLAN.md} section 13.7; {@code INV-ACC-13}, {@code INV-AUD-04}). An obligation says which form, drawn
 * from which report definitions line by line, checked by which rules, through which adapter. <strong>Nothing is seeded</strong>: a row arrives by {@link #propose} and a second person's {@link #activate}, and every one is plainly
 * {@code REFERENCE} (owner decision C4): {@code accounting V010} admits nothing else.
 *
 * <p><strong>What the proposal holds to the build:</strong> the adapter is held and files with the jurisdiction, so no row can name an authority no adapter serves; each source definition is held at that version and is never operational
 * ({@code RECONCILIATION_STATUS} and {@code CLOSE_STATUS} are never a run, `P14-TSK-017`). The same is asked again at the activation.
 *
 * <p><strong>The approver activates what they read:</strong> the body names the configuration's hash. The form's rows are locked in id order, so two activations of one form serialise; the second finds the first {@code ACTIVE} and must be a later
 * form version. Activating retires the version in force in the same transaction. A draft pins the version it used and an act on it is refused once that version has retired ({@link RegulatorySubmissions}).
 *
 * <p>A reason is never logged, never in a summary and never in an exception.
 */
@RequiredArgsConstructor
public final class RegulatoryObligations {

    /** The audit records' target type. */
    public static final String TARGET_TYPE = "regulatory_obligation";

    /** The most rows one list answers. */
    public static final int LIST_LIMIT = 100;

    private static final Pattern JURISDICTION = Pattern.compile("[A-Z]{2,8}(-[A-Z0-9]{1,16})?");
    private static final Pattern FORM = Pattern.compile("[A-Z][A-Z0-9_-]{1,63}");
    private static final Pattern ADAPTER = Pattern.compile("[a-z][a-z0-9-]{1,63}");

    @NonNull private final RegulatoryObligationStore store;
    @NonNull private final RegulatoryAdapters adapters;
    @NonNull private final ReportDefinitions definitions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What a proposer states. */
    public record Proposal(String jurisdiction, String form, int formVersion, String adapter, RegulatoryObligationConfiguration configuration) {

        public Proposal {
            Objects.requireNonNull(configuration, "configuration");
        }
    }

    /** An act's outcome; {@code replayed} when this person's act was already done and nothing was written. */
    public record Acted(RegulatoryObligationId id, RegulatoryObligationStatus status, String configurationSha256, boolean replayed) {}

    /** A version as a reader reads it, and whether the build still holds what it names. */
    public record View(RegulatoryObligationStore.Row row, RegulatoryObligationConfiguration configuration, boolean adapterHeld) {}

    /** No obligation version answers the request. */
    public static final class ObligationNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligationNotFound() {
            super("no regulatory obligation answers this request");
        }
    }

    /** The proposal is not coherent, or names what this build does not hold. Names the fact, never a reason. */
    public static final class ObligationInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligationInvalid(String detail) {
            super(detail);
        }
    }

    /** What the act names is no longer what stands. */
    public static final class ObligationStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ObligationStale(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(RegulatoryObligationStore.Row row) {
            super("a regulatory obligation is activated by someone other than its proposer");
            this.target = row.id().value().toString();
            this.summary = "form=" + row.form() + ", formVersion=" + row.formVersion() + ", act=regulatory-obligation:activate";
        }

        /** The refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names. */
        public String summary() {
            return summary;
        }
    }

    /**
     * Proposes a version - nothing can be drafted from it until a second person activates it.
     *
     * @throws ObligationInvalid a code out of shape, an adapter not held or not filing with the jurisdiction, a source not held at that version or operational
     * @throws ObligationStale that form and version already exist
     * @throws AccountingReasons.ReasonRequired no usable reason
     */
    public Acted propose(Connection unitOfWork, Proposal proposal, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        if (proposal.jurisdiction() == null || !JURISDICTION.matcher(proposal.jurisdiction()).matches() || proposal.form() == null || !FORM.matcher(proposal.form()).matches()
                || proposal.adapter() == null || !ADAPTER.matcher(proposal.adapter()).matches() || proposal.formVersion() < 1 || proposal.formVersion() > 9999) {
            throw new ObligationInvalid("an obligation names a jurisdiction code, a form code, a form version of 1 to 9999 and an adapter");
        }
        holds(proposal.adapter(), proposal.jurisdiction(), proposal.configuration());
        String configuration = proposal.configuration().canonical();
        String sha256 = CanonicalJson.sha256(configuration);
        RegulatoryObligationId id = RegulatoryObligationId.next(ids);
        boolean born = store.insertIfAbsent(unitOfWork, new RegulatoryObligationStore.NewObligation(id, proposal.jurisdiction(), proposal.form(), proposal.formVersion(), proposal.adapter(), configuration, sha256, actor,
                reasoned, correlation));
        if (!born) {
            throw new ObligationStale("that form and version already exist; a change is a later form version");
        }
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_OBLIGATION_PROPOSED, id, Optional.of(reasoned), AuditOutcome.SUCCEEDED,
                "jurisdiction=" + proposal.jurisdiction() + ", form=" + proposal.form() + ", formVersion=" + proposal.formVersion() + ", adapter=" + proposal.adapter() + ", lines="
                        + proposal.configuration().lines().size() + ", rules=" + proposal.configuration().rules().size(),
                correlation);
        return new Acted(id, RegulatoryObligationStatus.PROPOSED, sha256, false);
    }

    /**
     * Activates a version - a second person's act, naming the configuration's hash they read; the version in force retires with it. The same approver's repeat converges.
     *
     * @throws ObligationNotFound no such version
     * @throws SelfApprovalRefused the approver proposed it
     * @throws ObligationStale it is no longer {@code PROPOSED}; the hash named is not what stands; a form version in force or retired is at least this one; the adapter or a source is no longer held
     */
    public Acted activate(Connection unitOfWork, RegulatoryObligationId id, String configurationSha256, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        RegulatoryObligationStore.Row named = store.find(unitOfWork, id).orElseThrow(ObligationNotFound::new);
        List<RegulatoryObligationStore.Row> form = store.lockForm(unitOfWork, named.jurisdiction(), named.form());
        RegulatoryObligationStore.Row row = form.stream().filter(held -> held.id().equals(id)).findFirst().orElseThrow(ObligationNotFound::new);
        if (row.status() == RegulatoryObligationStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Acted(id, row.status(), row.configurationSha256(), true);
        }
        if (row.status() != RegulatoryObligationStatus.PROPOSED) {
            throw new ObligationStale("the version is " + row.status().name() + ", no longer awaiting a decision");
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row);
        }
        if (!row.configurationSha256().equals(configurationSha256)) {
            throw new ObligationStale("the configuration named is not the configuration that stands");
        }
        for (RegulatoryObligationStore.Row other : form) {
            if (!other.id().equals(id) && other.formVersion() >= row.formVersion()
                    && (other.status() == RegulatoryObligationStatus.ACTIVE || other.status() == RegulatoryObligationStatus.RETIRED)) {
                throw new ObligationStale("form version " + other.formVersion() + " is in force or retired; only a later one activates");
            }
        }
        try {
            holds(row.adapter(), row.jurisdiction(), RegulatoryObligationConfiguration.parse(row.configuration()));
        } catch (ObligationInvalid | RegulatoryObligationConfiguration.Invalid noLongerHeld) {
            throw new ObligationStale("what the version names is no longer held by this build: " + noLongerHeld.getMessage());
        }
        Optional<RegulatoryObligationStore.Row> inForce = form.stream().filter(held -> held.status() == RegulatoryObligationStatus.ACTIVE).findFirst();
        inForce.ifPresent(retiring -> {
            if (!store.markRetired(unitOfWork, retiring.id())) {
                throw new IllegalStateException("the locked version in force moved under another writer: the FOR UPDATE protocol was bypassed");
            }
        });
        if (!store.markActive(unitOfWork, id, actor, reasoned)) {
            throw new IllegalStateException("the locked version moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_OBLIGATION_ACTIVATED, id, Optional.of(reasoned), AuditOutcome.SUCCEEDED,
                "jurisdiction=" + row.jurisdiction() + ", form=" + row.form() + ", formVersion=" + row.formVersion() + inForce.map(retired -> ", retired=" + retired.formVersion()).orElse(""), correlation);
        return new Acted(id, RegulatoryObligationStatus.ACTIVE, row.configurationSha256(), false);
    }

    /**
     * Rejects a version - final; may be the proposer's. The same person's repeat converges.
     *
     * @throws ObligationNotFound no such version
     * @throws ObligationStale it is no longer {@code PROPOSED}
     */
    public Acted reject(Connection unitOfWork, RegulatoryObligationId id, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        RegulatoryObligationStore.Row named = store.find(unitOfWork, id).orElseThrow(ObligationNotFound::new);
        RegulatoryObligationStore.Row row = store.lockForm(unitOfWork, named.jurisdiction(), named.form()).stream().filter(held -> held.id().equals(id)).findFirst().orElseThrow(ObligationNotFound::new);
        if (row.status() == RegulatoryObligationStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Acted(id, row.status(), row.configurationSha256(), true);
        }
        if (row.status() != RegulatoryObligationStatus.PROPOSED || !store.markRejected(unitOfWork, id, actor, reasoned)) {
            throw new ObligationStale("the version is " + row.status().name() + ", no longer awaiting a decision");
        }
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_OBLIGATION_REJECTED, id, Optional.of(reasoned), AuditOutcome.SUCCEEDED, "form=" + row.form() + ", formVersion=" + row.formVersion(), correlation);
        return new Acted(id, RegulatoryObligationStatus.REJECTED, row.configurationSha256(), false);
    }

    /**
     * One version, as a reader reads it. Unlocked.
     *
     * @throws ObligationNotFound no such version
     */
    public View view(Connection unitOfWork, RegulatoryObligationId id) {
        RegulatoryObligationStore.Row row = store.find(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(ObligationNotFound::new);
        return new View(row, RegulatoryObligationConfiguration.parse(row.configuration()), adapters.named(row.adapter()).filter(adapter -> adapter.declares(row.jurisdiction())).isPresent());
    }

    /** The latest versions, newest first. */
    public List<RegulatoryObligationStore.Row> recent(Connection unitOfWork) {
        return store.recent(unitOfWork, LIST_LIMIT);
    }

    /**
     * Those waiting on a decision {@code reader} could make: never their own - excluded in the statement, before its limit.
     */
    public List<RegulatoryObligationStore.Row> awaitingActivation(Connection unitOfWork, Actor reader) {
        person(reader);
        return store.awaitingActivation(unitOfWork, reader.id(), LIST_LIMIT);
    }

    /** Records an operator's read ({@code regulatory.ObligationRead}) - before it is served. */
    public void recordRead(Connection unitOfWork, String target, String summary, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.REGULATORY_OBLIGATION_READ, TARGET_TYPE, target, Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of(summary)));
    }

    /** Records a refused self-activation {@code FAILED} on {@code unitOfWork}: the caller's own transaction, after the act rolled back. */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.SELF_APPROVAL_REFUSED, TARGET_TYPE, refused.target(), Optional.empty(), AuditOutcome.FAILED, correlation,
                Optional.of(refused.summary())));
    }

    // ------------------------------------------------------------------

    /** The build holds what it names: fail closed. */
    private void holds(String adapter, String jurisdiction, RegulatoryObligationConfiguration configuration) {
        if (adapters.named(adapter).filter(held -> held.declares(jurisdiction)).isEmpty()) {
            throw new ObligationInvalid("no adapter of this name files with this jurisdiction");
        }
        for (RegulatoryObligationConfiguration.Source source : configuration.sources()) {
            ReportDefinition definition = definitions.find(source.definition(), source.version())
                    .orElseThrow(() -> new ObligationInvalid("no report definition " + source.definition() + " version " + source.version() + " is held by this build"));
            if (definition.operational()) {
                throw new ObligationInvalid(source.definition() + " is operational and never a run: a submission's lineage is FINAL runs only");
            }
        }
    }

    private void append(Connection unitOfWork, Actor actor, AccountingAuditAction action, RegulatoryObligationId id, Optional<String> reason, AuditOutcome outcome, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE, id.value().toString(), reason, outcome, correlation, Optional.of(summary)));
    }

    /** An authenticated human: fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a regulatory obligation's act is a person's - never the platform's, a service's or a merchant's");
        }
    }
}
