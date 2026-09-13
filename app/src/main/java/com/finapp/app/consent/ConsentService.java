package com.finapp.app.consent;

import com.finapp.consent.ConsentAuditAction;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.ConsentStore;
import com.finapp.consent.ConsentText;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A person's own consent posture (`P2-TSK-018`): grant, withdraw, and see the current basis.
 *
 * <h2>Why this lives in {@code app}</h2>
 *
 * <p>It spans two bounded contexts and belongs wholly to neither: the session and the Identity
 * are {@code identity}'s, the history is {@code consent}'s, and neither module may see the
 * other. So {@code app} does what a composition root may — the {@code ProfileService} shape:
 * resolve, act, one transaction — and owns no consent rule of its own. The one domain rule on
 * this surface, {@code INV-CNS-04}'s grant assessment, lives in the {@code consent} module
 * ({@code ConsentStore.assessGrant}); this class only maps its answer to the boundary.
 *
 * <h2>Ownership is enforced by there being no parameter</h2>
 *
 * <p>The chain is entirely derived: {@code Session.identityId() → Identity.partyId()}. The one
 * request-supplied value that is not a payload field — the {@code DELETE}'s purpose — is a
 * closed enum naming a category of processing shared by everyone, not a resource identifier: it
 * cannot name anything of anybody else's.
 *
 * <h2>Withdrawal is refusable by nothing but authentication</h2>
 *
 * <p>There is no rule to fail: the client supplies no version (the record pins the version
 * current when the person withdrew — {@code INV-CNS-04}'s unconditional half, decided
 * server-side), no prior grant is required (a withdrawal with no grant before it is honest
 * history, and absence equals withdrawal to every caller — {@code INV-CNS-01}), and the append
 * has no losing branch. A person can always withdraw.
 */
public class ConsentService {

    private final IdentityStore<Connection> identities;
    private final ConsentStore<Connection> consents;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public ConsentService(
            IdentityStore<Connection> identityStore,
            ConsentStore<Connection> consentStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate consentTransactions,
            DataSource dataSource) {
        this.identities = Objects.requireNonNull(identityStore, "identityStore must not be null");
        this.consents = Objects.requireNonNull(consentStore, "consentStore must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(consentTransactions, "consentTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** How a grant attempt ended. The refusals write nothing: no act occurred to record. */
    public sealed interface GrantResult {
        record Granted(PurposeBasis basis) implements GrantResult {}

        record UnknownVersion() implements GrantResult {}

        record ReconsentRequired() implements GrantResult {}

        record NotResolvable() implements GrantResult {}
    }

    /**
     * Records a grant against the version the person was shown, or refuses it.
     *
     * <p>The assessment and the append read one snapshot, so a grant cannot be judged against
     * text-version state another transaction has already replaced. A repeated grant appends a
     * <strong>new</strong> fact — duplicates are honest history and the derivation absorbs them
     * (ADR-0037), which is what makes a client retry after a lost response safe with no key.
     */
    public GrantResult grant(Session current, ConsentPurpose purpose, int textVersion) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");

        return inOneTransaction(
                unitOfWork -> {
                    Optional<UUID> party = resolve(unitOfWork, current);
                    if (party.isEmpty()) {
                        return new GrantResult.NotResolvable();
                    }
                    return switch (consents.assessGrant(unitOfWork, purpose, textVersion)) {
                        case UNKNOWN_VERSION -> new GrantResult.UnknownVersion();
                        case RECONSENT_REQUIRED -> new GrantResult.ReconsentRequired();
                        case GRANTABLE -> {
                            ConsentRecord record =
                                    ConsentRecord.grant(
                                            ids, clock, party.get(), purpose, textVersion);
                            consents.append(unitOfWork, record);
                            audit(unitOfWork, ConsentAuditAction.CONSENT_GRANTED, record);
                            yield new GrantResult.Granted(basisFor(unitOfWork, party.get(), purpose));
                        }
                    };
                });
    }

    /**
     * Records a withdrawal. {@code false} only when the session resolves to no Identity — the
     * one refusal left, and it is authentication's, not this operation's.
     */
    public boolean withdraw(Session current, ConsentPurpose purpose) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");

        return inOneTransaction(
                unitOfWork -> {
                    Optional<UUID> party = resolve(unitOfWork, current);
                    if (party.isEmpty()) {
                        return false;
                    }
                    // Pins the version current when the person withdrew (INV-CNS-04's
                    // unconditional half) - the server's fact, never a client input, so
                    // nothing about it can refuse the withdrawal.
                    ConsentRecord record =
                            ConsentRecord.withdrawal(
                                    ids,
                                    clock,
                                    party.get(),
                                    purpose,
                                    consents.currentTextFor(unitOfWork, purpose).version());
                    consents.append(unitOfWork, record);
                    audit(unitOfWork, ConsentAuditAction.CONSENT_WITHDRAWN, record);
                    return true;
                });
    }

    /**
     * The caller's basis per purpose — every purpose, one snapshot, so the rows describe one
     * instant. Empty only when the session resolves to no Identity.
     */
    public Optional<List<PurposeBasis>> currentBases(Session current) {
        Objects.requireNonNull(current, "current must not be null");

        return inOneTransaction(
                unitOfWork ->
                        resolve(unitOfWork, current)
                                .map(
                                        party -> {
                                            List<PurposeBasis> bases = new ArrayList<>();
                                            for (ConsentPurpose purpose :
                                                    ConsentPurpose.values()) {
                                                bases.add(
                                                        basisFor(unitOfWork, party, purpose));
                                            }
                                            return List.copyOf(bases);
                                        }));
    }

    // -----------------------------------------------------------------

    private PurposeBasis basisFor(Connection unitOfWork, UUID party, ConsentPurpose purpose) {
        ConsentText text = consents.currentTextFor(unitOfWork, purpose);
        return new PurposeBasis(
                purpose,
                consents.hasCurrentBasis(unitOfWork, party, purpose),
                text.version(),
                text.body());
    }

    private Optional<UUID> resolve(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId());
    }

    /**
     * The trail of the act ({@code INV-AUD-01}), in the record's own transaction: no consent
     * fact without its trail, no trail without its fact.
     *
     * <p>The actor is the <strong>person</strong>, established by
     * {@code SessionAuthenticationInterceptor} — never {@code enterSystem()}, because granting
     * or withdrawing consent is the most personal act on the platform and attributing it to the
     * platform would record nobody as having consented to anything. The change summary names
     * the purpose and the pinned text version ({@code INV-CNS-04}'s reconstructability — the
     * promise {@code ConsentAuditAction}'s own javadoc makes), never the words: the words are
     * the version's, permanently, in {@code consent_text}.
     */
    private void audit(Connection unitOfWork, ConsentAuditAction action, ConsentRecord record) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A consent act must run inside a correlation"
                                                    + " scope: the audit record carries the"
                                                    + " identifier, and a fabricated one would"
                                                    + " point at no flow at all (P0-TSK-014)"));
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        action,
                        "consent.ConsentRecord",
                        record.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "purpose=" + record.purpose()
                                        + " textVersion=" + record.textVersion())));
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

    /**
     * One purpose's row in the caller's posture.
     *
     * <p>{@code granted} is the derivation's answer and nothing else: a withdrawal and an
     * absence are one {@code false}, indistinguishable by construction ({@code INV-CNS-01}).
     * The current text rides along because the words are what a person consents to — without
     * them no client can present a grant flow — and {@code consent_text.body} is the platform's
     * first genuinely {@code PUBLIC} column, published to exactly the audience {@code PUBLIC}
     * means.
     */
    public record PurposeBasis(
            ConsentPurpose purpose, boolean granted, int currentTextVersion, String currentText) {
        public PurposeBasis {
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(currentText, "currentText must not be null");
        }
    }
}
