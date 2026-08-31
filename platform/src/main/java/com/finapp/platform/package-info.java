/**
 * Platform kernel: the correctness primitives every module depends on.
 *
 * <p><strong>What belongs here.</strong> Cross-cutting mechanism, not business rules:
 * the transactional outbox and inbox, the idempotency kernel, the audit trail, correlation
 * and causation propagation, telemetry, the API error contract, and the provider adapter
 * SPI. None of these exist yet; they arrive in P0-TSK-014 onward.
 *
 * <p><strong>Why these live in one place.</strong> They cannot be retrofitted. Once
 * financial history exists, adding idempotency, an outbox or an audit trail means migrating
 * immutable records. Phase 0 is the only phase in which they are free.
 *
 * <p><strong>Transaction semantics.</strong> Platform components participate in the
 * caller's transaction rather than opening their own. The outbox write, the idempotency
 * record and the audit record all commit with the state change that produced them
 * ({@code INV-EVT-01}, {@code INV-IDEM-01}, {@code INV-AUD-01}). A platform component that
 * opens its own transaction has defeated its own purpose.
 *
 * <p><strong>What may never belong here.</strong> Any business concept. Platform is the
 * module every other module may depend on, so anything placed here is coupled to
 * everything. Domain nouns belong to their owning module.
 *
 * <p><strong>Dependency direction.</strong> {@code app -> platform -> sharedkernel}.
 * Platform may depend on sharedkernel and on nothing else in this build.
 *
 * <p>See {@code docs/architecture/MODULE_ARCHITECTURE.md} in the repository.
 */
package com.finapp.platform;
