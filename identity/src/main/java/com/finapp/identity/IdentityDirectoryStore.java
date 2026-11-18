package com.finapp.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The staff directory's reads (`P17-TSK-014`; finapp-admin-panel ADR-C132): identifier-first, so nothing here carries a name, a
 * login identifier or a contact detail - the lookup takes a login identifier and answers only the id it resolves to.
 */
public interface IdentityDirectoryStore<T> {

    /** One identity in the directory: its status, when it was created, and the roles it holds now (none when all were revoked). */
    record Entry(IdentityId identityId, IdentityStatus status, Instant createdAt, List<RoleName> liveRoles) {

        public Entry {
            liveRoles = List.copyOf(liveRoles);
        }
    }

    /** Where a page of the directory ended: the last identity it served, by its creation instant and id. */
    record Cursor(Instant createdAt, UUID identityId) {}

    /**
     * A page of the identities that hold, or held, an operator role - newest first by creation, the id breaking the tie - of a live
     * holder of {@code role} when one is named, in {@code status} when one is named, strictly after {@code after}. Driven from
     * the role assignments, so its cost is the staff's number, never the customers'.
     */
    List<Entry> page(T unitOfWork, Optional<RoleName> role, Optional<IdentityStatus> status, Optional<Cursor> after, int limit);

    /** A live holding as the detail serves it: the role, when it was granted, and by whom. */
    record LiveRole(RoleName role, Instant assignedAt, IdentityId assignedBy) {}

    /**
     * One identity's detail: its status - with the instant and the cause of a suspension - whether a factor is enrolled, its live
     * roles, its creation instant.
     */
    record Detail(
            IdentityId identityId,
            IdentityStatus status,
            Optional<Instant> suspendedAt,
            Optional<String> suspensionCause,
            boolean factorEnrolled,
            List<LiveRole> roles,
            Instant createdAt) {

        public Detail {
            roles = List.copyOf(roles);
        }
    }

    Optional<Detail> detail(T unitOfWork, IdentityId identityId);

    /** The identity whose login identifier is exactly {@code loginIdentifier}, or empty. */
    Optional<IdentityId> byLoginIdentifier(T unitOfWork, String loginIdentifier);
}
