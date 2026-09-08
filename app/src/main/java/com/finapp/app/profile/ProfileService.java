package com.finapp.app.profile;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.party.Party;
import com.finapp.party.PartyAuditAction;
import com.finapp.party.PartyId;
import com.finapp.party.PartyName;
import com.finapp.party.PartyStore;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A person's own profile (`P1-TSK-030`).
 *
 * <h2>Ownership is enforced by there being no parameter, which is the strongest form</h2>
 *
 * <p>Neither {@code /v1/me} endpoint takes a path variable, a query parameter or a body field
 * naming a party. The chain is entirely derived:
 *
 * <pre>
 *   Bearer token → SessionAuthenticationInterceptor → proven Session
 *                → Session.identityId() → Identity.partyId() → Party
 * </pre>
 *
 * <p>ADR-0031's defect is <em>trusting an identifier out of the request</em>, and here there is
 * <strong>none to trust</strong>. That changes what a negative ownership test can be, and it is
 * worth saying rather than implying the two are the same: an attacker cannot name a victim, because
 * the API gives them nothing to name one with — so the test proves the <em>resolution chain</em> is
 * right rather than that a check refuses.
 *
 * <h2>Why this lives in {@code app}</h2>
 *
 * <p>It spans two bounded contexts and belongs wholly to neither: the session and the Identity are
 * {@code identity}'s, the Party is {@code party}'s, and neither module may see the other
 * (ADR-0029, enforced by the module isolation tests). So {@code app} does what a composition root
 * may — two reads and a transaction — and owns no rule of its own, exactly as
 * {@code RegistrationService} does for the write side.
 *
 * <h2>One transaction, including for the read</h2>
 *
 * <p>{@code GET} performs two reads. Without a transaction they would see two snapshots, and a
 * rename landing between them would produce a response mixing two states — a login identifier from
 * one instant beside a display name from another. Cheap to prevent and confusing to debug.
 */
public class ProfileService {

    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public ProfileService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate profileTransactions,
            DataSource dataSource) {
        this.identities = Objects.requireNonNull(identityStore, "identityStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(profileTransactions, "profileTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** The caller's own profile, or empty if the resolution chain finds nothing. */
    public Optional<Profile> read(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return inOneTransaction(
                unitOfWork ->
                        resolve(unitOfWork, current)
                                .map(
                                        resolved ->
                                                new Profile(
                                                        resolved.party(),
                                                        resolved.identity()
                                                                .loginIdentifier()
                                                                .value())));
    }

    /**
     * Changes the caller's own display name.
     *
     * <p>A rename to the name already held is a <strong>success that writes no audit record</strong>
     * — see {@link PartyStore#rename}. The caller asked for the profile to hold that name and it
     * does.
     */
    public Optional<Profile> rename(Session current, PartyName newName) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(newName, "newName must not be null");

        return inOneTransaction(
                unitOfWork ->
                        resolve(unitOfWork, current)
                                .map(
                                        resolved -> {
                                            parties.rename(
                                                            unitOfWork,
                                                            resolved.party().id(),
                                                            newName)
                                                    .ifPresent(
                                                            replaced ->
                                                                    audit(
                                                                            unitOfWork,
                                                                            resolved.party().id()));
                                            return new Profile(
                                                    resolved.party().rename(newName),
                                                    resolved.identity()
                                                            .loginIdentifier()
                                                            .value());
                                        }));
    }

    // -----------------------------------------------------------------

    private Optional<Resolved> resolve(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .flatMap(
                        identity ->
                                parties.findById(unitOfWork, PartyId.of(identity.partyId()))
                                        .map(party -> new Resolved(identity, party)));
    }

    /**
     * Records that the display name changed — not what it changed to, and not what it was.
     *
     * <p>{@code party.display_name} is {@code RESTRICTED-PII} and
     * {@code audit_record.change_summary} is {@code RESTRICTED-FINANCIAL}. Those are <strong>peers,
     * not a hierarchy</strong>: writing a name into a column whose handling assumes financial data
     * would put it outside the PII rules — retention, subject access, erasure — and ADR-0022 is
     * explicit that a column cannot be reclassified once it holds data.
     *
     * <p>{@code PartyRegistration} made the same choice for the same reason, and
     * {@code PartyAuditAction.PARTY_PROFILE_CHANGED}'s description was corrected by this task
     * because it had promised the before and after values.
     */
    private void audit(Connection unitOfWork, PartyId party) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A profile change must run inside a correlation"
                                                    + " scope: the audit record carries the"
                                                    + " identifier, and a fabricated one would"
                                                    + " point at no flow at all (P0-TSK-014)"));
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        // The person, established by SessionAuthenticationInterceptor. Never the
                        // platform: this is somebody editing their own data (P1-TSK-022).
                        SecurityContext.require(),
                        Instant.now(clock),
                        PartyAuditAction.PARTY_PROFILE_CHANGED,
                        "party.Party",
                        party.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("field=displayName")));
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

    private record Resolved(Identity identity, Party party) {}

    /**
     * What {@code /v1/me} is about.
     *
     * <p>A per-call value carrying two modules' state, never retained. The login identifier is a
     * {@code String} rather than a {@code LoginIdentifier} because {@code app} may not hand
     * {@code identity}'s value types to the web layer without them becoming part of the published
     * contract's shape.
     */
    public record Profile(Party party, String loginIdentifier) {
        public Profile {
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");
        }
    }
}
