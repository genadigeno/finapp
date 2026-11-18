package com.finapp.identity;

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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The staff directory (`P17-TSK-014`; finapp-admin-panel ADR-C132 decisions 3 to 5): whom an administrator acts on, read
 * identifier-first and recorded. Each read runs in the caller's unit of work and writes its audit record there, so a read that
 * served nothing - because the transaction rolled back - left no record of serving anything, and one that served something cannot
 * have gone unrecorded.
 *
 * <p>What the trail holds: the list's filter and how many it served, never the rows; the detail's identity as the target; the
 * lookup's outcome and the identity it resolved to, <strong>never the login identifier</strong> it was asked about.
 */
@RequiredArgsConstructor
public final class IdentityDirectory {

    /** The shape {@code identity.identity} admits (identity V002): anything else can match no identity. */
    private static final Pattern LOGIN_IDENTIFIER = Pattern.compile("[a-z0-9._-]{3,64}");

    @NonNull private final IdentityDirectoryStore<Connection> store;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A page of the directory, recorded once with its filter. */
    public List<IdentityDirectoryStore.Entry> page(
            Connection unitOfWork,
            Optional<RoleName> role,
            Optional<IdentityStatus> status,
            Optional<IdentityDirectoryStore.Cursor> after,
            int limit) {
        List<IdentityDirectoryStore.Entry> page = store.page(unitOfWork, role, status, after, limit);
        record(unitOfWork, IdentityAuditAction.IDENTITY_DIRECTORY_READ, "staff-directory",
                "role=" + role.map(RoleName::name).orElse("any") + ", status=" + status.map(IdentityStatus::name).orElse("any")
                        + ", after=" + after.isPresent() + ", served=" + page.size());
        return page;
    }

    /** One identity's detail, recorded with the identity as the target; an identity that does not exist is no read of anyone. */
    public Optional<IdentityDirectoryStore.Detail> detail(Connection unitOfWork, IdentityId identityId) {
        Optional<IdentityDirectoryStore.Detail> found = store.detail(unitOfWork, identityId);
        found.ifPresent(detail -> record(unitOfWork, IdentityAuditAction.IDENTITY_READ, identityId.value().toString(),
                "roles=" + detail.roles().size() + ", factorEnrolled=" + detail.factorEnrolled()));
        return found;
    }

    /**
     * The identity whose login identifier is exactly the one given - recorded whether it matched or not, because a probe that
     * found nothing is still a probe. A value no identity could carry matches nothing and is not looked for.
     */
    public Optional<IdentityId> lookup(Connection unitOfWork, String loginIdentifier) {
        Optional<IdentityId> found = loginIdentifier != null && LOGIN_IDENTIFIER.matcher(loginIdentifier).matches()
                ? store.byLoginIdentifier(unitOfWork, loginIdentifier)
                : Optional.empty();
        record(unitOfWork, IdentityAuditAction.IDENTITY_LOOKED_UP,
                found.map(id -> id.value().toString()).orElse("no-match"), "matched=" + found.isPresent());
        return found;
    }

    private void record(Connection unitOfWork, IdentityAuditAction action, String targetId, String summary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        action,
                        "identity.Identity",
                        targetId,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(() -> new IllegalStateException("a directory read runs inside a correlation scope"))
                                .correlationId(),
                        Optional.of(summary)));
    }
}
