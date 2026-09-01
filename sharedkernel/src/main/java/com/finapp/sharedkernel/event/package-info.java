/**
 * The event envelope: the metadata every published event carries (P0-TSK-018).
 *
 * <p>{@code CLAUDE.md} §Events and {@code EVENT_ARCHITECTURE.md} both require ten fields on
 * important events, and {@code INV-EVT-03} makes carrying all of them an invariant.
 * {@link com.finapp.sharedkernel.event.EventEnvelope} enforces that at construction, so an
 * event that could not be traced cannot be built.
 *
 * <p><strong>Metadata only, deliberately.</strong> The envelope carries no payload, so an outbox
 * relay, a dead-letter tool or a consumer's deduplication can read, route and store an event
 * without deserialising anything domain-specific — and without a log line ever being able to
 * spill event contents ({@code INV-AUD-02}).
 *
 * <p><strong>What is not here.</strong> The wire format. Choosing one would commit the shared
 * kernel to a serialisation library, and the transport is the outbox's and the relay's decision
 * ({@code P0-EPIC-06}). What the envelope does provide is a canonical textual form whose field
 * set and order are pinned by test, so the contract cannot drift silently.
 *
 * <p>Nothing here reads a clock or generates an identifier by itself: {@code occurredAt} and the
 * generator are supplied, so an event's time is the caller's decision and remains reproducible
 * under test (P0-TSK-013).
 */
package com.finapp.sharedkernel.event;
