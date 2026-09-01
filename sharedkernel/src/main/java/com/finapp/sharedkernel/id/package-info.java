/**
 * Typed, time-ordered aggregate identifiers (P0-TSK-012, ADR-0013).
 *
 * <p>Two independent problems are solved here, and they are worth keeping apart:
 *
 * <ul>
 *   <li><strong>Type confusion.</strong> {@link com.finapp.sharedkernel.id.EntityId} makes each
 *       aggregate's identifier a distinct type, so passing a customer identifier where an
 *       account identifier is required does not compile. Untyped {@code UUID} parameters make
 *       that mistake invisible until it has already produced a posting against the wrong
 *       entity.
 *   <li><strong>Index locality.</strong> {@link com.finapp.sharedkernel.id.IdGenerator}
 *       produces UUIDv7 values, which sort by creation time. A random primary key scatters
 *       inserts across the whole index; at ledger volume that turns an append-only workload
 *       into a random-write one.
 * </ul>
 *
 * <p><strong>This package defines no identifier for anything.</strong> {@code CustomerId}
 * belongs to {@code party}, {@code AccountId} to {@code accounts}, {@code JournalEntryId} to
 * {@code ledger}. The shared kernel holds the mechanism; a shared kernel holding the business
 * nouns would be the coupling sink a modular monolith exists to prevent
 * ({@code MODULE_ARCHITECTURE.md} §2).
 *
 * <p>Nothing here reads ambient time or ambient randomness: both are constructor arguments, so
 * generation is reproducible under test and the eventual no-ambient-clock rule (P0-TSK-013)
 * has nothing to forbid here.
 */
package com.finapp.sharedkernel.id;
