package com.finapp.app.credit;

import java.util.Optional;

/**
 * Resolves a bureau pull's opaque subject reference to the person's identifying facts
 * (`P10-TSK-005`), so those facts are read only where they are sent - inside the bureau adapter -
 * and never travel through {@code credit}.
 *
 * <p>No production implementation exists in Phase 10: no real bureau is connected (unresolved questions #13 and #14 -
 * production composes the fail-safe sources), so only the simulated adapters' contract battery resolves, from a
 * fixture. The party-backed implementation arrives with the first real provider. <em>(This read "arrives with bureau
 * collection (`P10-TSK-006`)" until the Phase 10 exit review, `P10-DOC-001`.)</em>
 */
@FunctionalInterface
public interface CreditDataSubjectResolver {

    /** The subject's identifying facts, or empty when the reference names nobody. */
    Optional<CreditDataSubject> resolve(String subjectReference);
}
