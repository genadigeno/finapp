package com.finapp.app.credit;

import java.util.Optional;

/**
 * Resolves a bureau pull's opaque subject reference to the person's identifying facts
 * (`P10-TSK-005`), so those facts are read only where they are sent - inside the bureau adapter -
 * and never travel through {@code credit}.
 *
 * <p>Its party-backed implementation arrives with bureau collection (`P10-TSK-006`), the first
 * caller; the contract battery resolves from a fixture.
 */
@FunctionalInterface
public interface BureauSubjectResolver {

    /** The subject's identifying facts, or empty when the reference names nobody. */
    Optional<BureauSubject> resolve(String subjectReference);
}
