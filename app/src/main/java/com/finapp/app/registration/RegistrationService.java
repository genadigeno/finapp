package com.finapp.app.registration;

import com.finapp.identity.IdentityRegistration;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.LoginIdentifierAlreadyTakenException;
import com.finapp.identity.RawPassword;
import com.finapp.party.PartyName;
import com.finapp.party.PartyRegistration;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers a person: one Party, one Customer, one Identity, one commit, or none (`P1-TSK-006`).
 *
 * <h2>Why the orchestration lives in {@code app}</h2>
 *
 * <p>Registration spans two bounded contexts and belongs wholly to neither. {@code party} cannot
 * host it because it would have to see {@code identity}, which the module isolation tests forbid
 * and ADR-0029 makes structural; {@code identity} cannot host it symmetrically. So {@code app}
 * does what a composition root may do - <strong>two calls and a transaction</strong> - and owns no
 * rule, no event and no audit record of its own. Each module writes its own. That is the same test
 * {@code MODULE_ARCHITECTURE.md} §M2 applies to {@code checkout}: a thing that merely orchestrates
 * two modules is not a module.
 *
 * <h2>One transaction, and how the connection is shared</h2>
 *
 * <p>{@code TransactionTemplate}, not {@code @Transactional}: the annotation fails <em>silently</em>
 * on self-invocation, and this is the one flow where a transaction that quietly did not start
 * would leave a half-registered person (ADR-0033).
 *
 * <p>Inside it, {@link DataSourceUtils#getConnection} returns the <strong>transaction-bound</strong>
 * connection, which is what the Phase 0 ports take as their unit of work. Opening a connection from
 * the {@code DataSource} directly - or handing the modules a {@code JdbcClient} built on it - would
 * enlist a second transaction, and the atomicity would be gone while every test still passed. The
 * atomicity test is the only thing that would notice, which is why it exists.
 *
 * <h2>The savepoint</h2>
 *
 * <p>A taken login identifier arrives as a unique-index violation, and PostgreSQL <em>aborts the
 * transaction</em> when it raises one. Without a savepoint nothing further could be written -
 * including the idempotency outcome - so the whole transaction would roll back and the client's
 * retry would re-run the command rather than replay its refusal. Rolling back to a savepoint taken
 * before the first insert undoes the Party and the Customer, leaves the idempotency claim (taken
 * before it) intact, and returns a usable connection. The inbox uses the same pattern for the same
 * reason.
 *
 * <h2>The actor</h2>
 *
 * <p>{@link SecurityContext#enterSystem()}. The caller is unauthenticated, so there is no actor,
 * and this is what that call is documented to say. Attributing the action to the Party it creates
 * was considered and rejected: it is circular, and - decisively - it is unavailable on the refusal
 * path, where nothing was created. An actor that differs between success and failure is worse than
 * a uniform honest one. What carries the information is the audit record's <em>target</em>, which
 * is the login identifier on both paths.
 */
public final class RegistrationService {

    /**
     * The idempotency scope.
     *
     * <p>ADR-0004 says a scope carries the command type <strong>and the owning principal</strong>.
     * Registration is permanently the one endpoint that cannot carry a principal, because it is the
     * endpoint that creates one - a property of what it does rather than an omission.
     *
     * <p>Scoping by the login identifier instead was considered and rejected: it is
     * {@code CONFIDENTIAL} ({@code DATA_CLASSIFICATION.md} §4, because it carries <em>existence</em>)
     * and {@code idempotency_record.scope} is {@code INTERNAL}. Putting it there would force a
     * Phase 0 column to be reclassified, which is the one thing ADR-0022 says must not happen.
     */
    static final String SCOPE = "party.RegisterCustomer";

    /** {@code finapp.<module>.<noun>}, enforced against the live registry by the build. */
    static final String REGISTRATION_COUNTER = "finapp.party.registration";

    private final IdempotentExecutor executor;
    private final PartyRegistration partyRegistration;
    private final IdentityRegistration identityRegistration;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final MeterRegistry meters;

    /**
     * Registered at CONSTRUCTION, one per outcome, never on first increment.
     *
     * <p>{@code MeterRegistry.counter(name, tags)} creates the meter on the first call, so a
     * freshly started instance would publish <strong>no series at all</strong> until the flow ran
     * once. An alert written on a rate then has nothing to evaluate at precisely the moment it
     * needed a series sitting at zero - a counter that starts existing when the thing it counts
     * happens is a delayed notification, not monitoring. Found by {@code P1-TSK-029}.
     */
    private final java.util.Map<Outcome, io.micrometer.core.instrument.Counter> registrations =
            new java.util.EnumMap<>(Outcome.class);

    public RegistrationService(
            IdempotentExecutor executor,
            PartyRegistration partyRegistration,
            IdentityRegistration identityRegistration,
            TransactionTemplate transactions,
            DataSource dataSource,
            MeterRegistry meters) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.partyRegistration =
                Objects.requireNonNull(partyRegistration, "partyRegistration must not be null");
        this.identityRegistration =
                Objects.requireNonNull(identityRegistration, "identityRegistration must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        for (Outcome outcome : Outcome.values()) {
            registrations.put(
                    outcome, meters.counter(REGISTRATION_COUNTER, "outcome", outcome.tag()));
        }
    }

    /**
     * Registers, replays a previous registration, or refuses.
     *
     * @param idempotencyKey already validated for shape by {@code IdempotencyKeyInterceptor}, and
     *     never rewritten: a key the platform adjusted would not match the caller's retry, which is
     *     the one thing that must never happen
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public Outcome register(RegistrationRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        // Normalisation happens here, once, before the fingerprint is taken - so a retry that
        // typed a capital letter differently still hashes to the same request.
        LoginIdentifier login = new LoginIdentifier(request.loginIdentifier());
        PartyName name = new PartyName(request.displayName());
        RawPassword password = usablePassword(request.password());

        IdempotencyKey key = new IdempotencyKey(SCOPE, idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(canonicalForm(login, name));

        // Before the transaction, and this is the ordering the whole design rests on. Argon2id
        // costs ~46 ms of CPU and ~19 MiB by design (ADR-0032); doing it while holding one of
        // eight pooled connections (P1-TSK-004) would turn a registration flood into
        // connection-timeout errors pointing at a database that is perfectly healthy.
        //
        // It also makes the work equivalent for a successful and a refused registration, which is
        // defence in depth rather than the control: a 201 and a 422 are already distinguishable,
        // and necessarily so, because a registration endpoint must tell you the name is taken.
        IdentityRegistration.Enrolment enrolment = identityRegistration.prepare(password);

        Outcome outcome;
        // The platform is acting, because the caller is unauthenticated and there is nobody else
        // it could be. Greppable on purpose: this is one of the call sites Phase 1 revisits, and
        // the answer for registration is that it stays.
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            outcome =
                    transactions.execute(
                            status -> {
                                Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                                try {
                                    IdempotentExecutor.ExecutionOutcome executed =
                                            executor.execute(
                                                    unitOfWork,
                                                    key,
                                                    fingerprint,
                                                    uow ->
                                                            registerOnce(
                                                                    uow, enrolment, login, name));
                                    return outcomeOf(executed);
                                } finally {
                                    // A no-op for a transaction-bound connection, and the correct
                                    // call regardless: closing it here would end the transaction
                                    // this method is the boundary of.
                                    DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                                }
                            });
        }
        registrations.get(Objects.requireNonNull(outcome))
                .increment();
        return outcome;
    }

    // -----------------------------------------------------------------

    /**
     * The command, run at most once per idempotency key.
     *
     * <p>Everything it writes goes on {@code unitOfWork}: two module's rows, two audit records and
     * three outbox rows, all committing with the idempotency record that says it happened.
     */
    private CommandResult registerOnce(
            Connection unitOfWork,
            IdentityRegistration.Enrolment enrolment,
            LoginIdentifier login,
            PartyName name) {

        PartyRegistration.AuditSubject subject =
                new PartyRegistration.AuditSubject(
                        IdentityRegistration.AUDIT_TARGET_TYPE, login.value());
        try {
            Savepoint beforeEffect = unitOfWork.setSavepoint("before_registration_effect");
            try {
                PartyRegistration.RegisteredParty registered =
                        partyRegistration.register(unitOfWork, name, subject);
                identityRegistration.create(
                        unitOfWork, enrolment, registered.party().id().value(), login);
                unitOfWork.releaseSavepoint(beforeEffect);
                return CommandResult.succeeded(StoredResponse.empty());
            } catch (LoginIdentifierAlreadyTakenException taken) {
                // The transaction is aborted at this point; this is what makes it usable again.
                unitOfWork.rollback(beforeEffect);
                // After the rollback, so the refusal survives and everything it would have
                // described does not.
                partyRegistration.recordRefusedRegistration(unitOfWork, subject);
                // A definitive failure, recorded: a retry replays this refusal rather than
                // re-attempting a registration that cannot succeed.
                return CommandResult.failed(StoredResponse.empty());
            }
        } catch (SQLException e) {
            throw new RegistrationStorageException(
                    "Could not manage the registration savepoint; the transaction must not commit",
                    e);
        }
    }

    /**
     * Turns the request's secret into the domain type, or refuses the request.
     *
     * <p>{@code RawPassword} bounds length as a denial-of-service control, and its refusal must
     * reach the caller as {@code api.ValidationFailed} rather than as an {@code
     * IllegalArgumentException} rendered {@code api.InternalError} - our fault reported for their
     * input, which {@code ERROR_CONTRACT.md} §3 forbids and which a client may retry for ever. The
     * annotation that would normally do this cannot: Bean Validation cannot see inside {@code
     * Sensitive}, and a constraint that unwrapped it would put a plaintext in {@code app}.
     *
     * <p><strong>The exception's message is not passed on, and nothing here is logged.</strong> It
     * names the bounds and never the value (asserted by {@code P1-TSK-009}), but the client detail
     * is written here rather than inherited, so a future change to that message cannot become a
     * change to what a stranger is told.
     *
     * <p>This is the one unwrap-adjacent line in {@code app} on this path, and it is immediately
     * re-wrapped by {@code RawPassword} - the same shape, and the same reasoning, as {@code
     * AuthenticationService.verify}.
     */
    private static RawPassword usablePassword(Sensitive<String> secret) {
        try {
            return new RawPassword(secret);
        } catch (IllegalArgumentException notAUsablePassword) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "Registration rejected: the password is outside the accepted length",
                    "password must be between "
                            + RawPassword.MIN_LENGTH
                            + " and "
                            + RawPassword.MAX_LENGTH
                            + " characters");
        }
    }

    /**
     * What the idempotency mechanism compares two requests by.
     *
     * <p>The semantically significant fields and nothing else: a length prefix on each so that
     * {@code ("ab", "c")} and {@code ("a", "bc")} cannot hash alike, which is the whole point of a
     * canonical form.
     *
     * <p><strong>There is deliberately no credential here, and {@code P1-TSK-026} kept it that way
     * rather than inheriting the decision.</strong> {@code RequestFingerprint} is a single-round
     * SHA-256 and
     * {@code idempotency_record.request_fingerprint} is durable, so hashing a body containing a
     * password would store an offline-crackable derivation of it - {@code INV-IDN-01} violated by
     * the idempotency mechanism. {@code RequestFingerprint} leaves the choice of significant fields
     * to the command precisely so a command can make this decision; this is it being made.
     *
     * <p>The consequence is stated rather than left to be discovered: <strong>a retry carrying the
     * same key and a <em>different</em> password replays the original outcome</strong> instead of
     * being refused as a fingerprint conflict ({@code INV-IDEM-03}). That is the right trade -
     * the alternative stores an offline-crackable derivation of a password for ever - and the
     * residual is bounded by the empty response body ({@code P1-TSK-006}), so a caller who changed
     * the password learns only that the request succeeded.
     */
    static byte[] canonicalForm(LoginIdentifier login, PartyName name) {
        String canonical =
                SCOPE
                        + ""
                        + login.value().length()
                        + ":"
                        + login.value()
                        + ""
                        + name.value().length()
                        + ":"
                        + name.value();
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static Outcome outcomeOf(IdempotentExecutor.ExecutionOutcome executed) {
        if (executed.state() != IdempotencyState.COMPLETED) {
            return Outcome.REFUSED;
        }
        return executed.replayed() ? Outcome.REPLAYED : Outcome.REGISTERED;
    }

    /**
     * What happened.
     *
     * <p>{@link #REPLAYED} is distinguished from {@link #REGISTERED} for the metric and for nothing
     * else. It is deliberately <strong>not</strong> visible to the caller: a response header saying
     * "this was a replay" would tell a stranger holding the idempotency key that the login
     * identifier already exists, which is precisely {@code INV-IDN-07}.
     */
    public enum Outcome {
        REGISTERED("created"),
        REPLAYED("replayed"),
        REFUSED("refused");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        /** A fixed vocabulary, so the metric's cardinality is bounded by this enum (ADR-0018). */
        String tag() {
            return tag;
        }

        public boolean accepted() {
            return this != REFUSED;
        }
    }

    /** A savepoint could not be taken or released; the transaction must not commit. */
    static final class RegistrationStorageException extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        RegistrationStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
