/**
 * Shared kernel: framework-free value types shared by every module.
 *
 * <p><strong>What belongs here.</strong> Only concepts that are genuinely universal
 * <em>and</em> stable: {@code Money}, {@code CurrencyCode}, rounding policy, typed
 * identifiers, the event envelope, and the {@code Clock} abstraction. Money (P0-TSK-009,
 * P0-TSK-010) and typed identifiers (P0-TSK-012) exist; the event envelope and the clock
 * abstraction arrive in P0-TSK-013 and P0-TSK-018.
 *
 * <p><strong>What may never belong here.</strong> Any business concept. {@code Account},
 * {@code Customer}, {@code Payment}, {@code Transfer} and every other domain noun belong to
 * exactly one owning module. A shared kernel that accumulates domain types becomes the
 * coupling sink that a modular monolith exists to prevent.
 *
 * <p><strong>No framework.</strong> No Spring, no JPA, no persistence, no I/O, no secrets.
 * This constraint is what lets the financial kernel be unit-tested without a container, and
 * it is verified by {@code SharedKernelIsolationTest}.
 *
 * <p><strong>No dependencies on other modules.</strong> This package sits at the bottom of
 * the dependency graph: {@code app -> platform -> sharedkernel}.
 *
 * <p>See {@code docs/architecture/MODULE_ARCHITECTURE.md} in the repository.
 */
package com.finapp.sharedkernel;
