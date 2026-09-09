package com.finapp.app.credential;

import com.finapp.app.authentication.AuthenticatedSession;
import com.finapp.identity.CredentialChange;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.RawPassword;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary for a password change (`P1-TSK-033`).
 *
 * <h2>The new credential is derived before the transaction opens</h2>
 *
 * <p>{@link CredentialChange#prepare} does the ~46&nbsp;ms / ~19&nbsp;MiB Argon2 derivation with no
 * connection held, so a burst of changes cannot exhaust the pool ({@code P1-TSK-026}'s reasoning).
 * The transaction then re-proves the current password, supersedes, revokes and rotates — all
 * committing together or not at all: a superseded credential without the session revocations it
 * authorised would be exactly the half-done state this endpoint exists to prevent.
 *
 * <h2>Takes the proven {@link Session}, never an identifier</h2>
 *
 * <p>The {@code /me} shape: the identity comes from the session the boundary proved, so there is
 * nothing in the request to name a victim with (ADR-0031, {@code SESSION_DERIVED}).
 */
@Service
public class ChangePasswordService {

    private final CredentialChange changes;
    private final SessionPolicy policy;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public ChangePasswordService(
            CredentialChange changes,
            SessionPolicy sessionPolicy,
            TransactionTemplate credentialChangeTransactions,
            DataSource dataSource) {
        this.changes = Objects.requireNonNull(changes, "changes must not be null");
        this.policy = Objects.requireNonNull(sessionPolicy, "sessionPolicy must not be null");
        this.transactions =
                Objects.requireNonNull(
                        credentialChangeTransactions,
                        "credentialChangeTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public AuthenticatedSession change(
            Session current, Sensitive<String> currentPassword, Sensitive<String> newPassword) {
        Objects.requireNonNull(current, "current must not be null");

        // The NEW password is the caller's choice and must be correctable: a length refusal is a
        // 422 naming the field, decided before any work (RegistrationService's mapping, and the
        // reason it cannot be a Bean Validation annotation - Sensitive is opaque to it).
        RawPassword replacement = usableNewPassword(newPassword);

        // The CURRENT password is a credential being re-proven. A malformed one cannot be the
        // stored credential, so it is a failed re-proof rather than a validation error - and it
        // collapses into the uniform refusal below rather than telling the caller which of "wrong"
        // and "malformed" it was. Constructed here so a value RawPassword rejects does not throw
        // out of the transaction as a 500.
        RawPassword proof;
        try {
            proof = new RawPassword(currentPassword);
        } catch (IllegalArgumentException notAUsablePassword) {
            throw authenticationFailed();
        }

        // Derived OUTSIDE the transaction.
        CredentialChange.Prepared prepared = changes.prepare(current.identityId(), replacement);

        CredentialChange.Result result =
                transactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return changes.apply(
                                        unitOfWork, current, proof, prepared, policy.idleTimeout());
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        });

        return switch (Objects.requireNonNull(result, "the transaction returned no outcome")) {
            case CredentialChange.Changed changed ->
                    new AuthenticatedSession(
                            changed.rotated().token().presentedValue().expose(),
                            changed.rotated().session().assurance().name(),
                            changed.rotated().session().idleExpiresAt());
            case CredentialChange.AssuranceRequired ignored ->
                    // Actionable, so distinguished (P1-TSK-018): a client can step up and retry,
                    // and the caller learns only that they enrolled MFA and used a password
                    // session - which they already knew.
                    throw new ApiException(
                            IdentityErrorCode.ASSURANCE_REQUIRED,
                            "A password change from an MFA-enrolled identity requires a"
                                    + " MULTI_FACTOR session");
            case CredentialChange.Refused ignored -> throw authenticationFailed();
        };
    }

    private static RawPassword usableNewPassword(Sensitive<String> secret) {
        try {
            return new RawPassword(secret);
        } catch (IllegalArgumentException notAUsablePassword) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "Password change rejected: the new password is outside the accepted length",
                    "newPassword must be between "
                            + RawPassword.MIN_LENGTH
                            + " and "
                            + RawPassword.MAX_LENGTH
                            + " characters");
        }
    }

    private static ApiException authenticationFailed() {
        // Uniform: a wrong current password, a lock, a lost race, a concurrently-killed session -
        // one shape, so none is readable from the response. The reasons are in the audit trail.
        return new ApiException(
                IdentityErrorCode.AUTHENTICATION_FAILED, "The password change was refused");
    }
}
