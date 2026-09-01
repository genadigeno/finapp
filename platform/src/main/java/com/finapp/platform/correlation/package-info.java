/**
 * Correlation and causation: what ties one business flow together, and what caused each step
 * (P0-TSK-014).
 *
 * <p>{@code CLAUDE.md} requires observability across customer → request → domain operation →
 * provider → ledger → settlement → reconciliation. That chain crosses threads, transactions,
 * message brokers and days, so it cannot be reconstructed from timestamps; it has to be carried.
 * Two identifiers carry it, and they answer different questions:
 *
 * <ul>
 *   <li>{@link com.finapp.platform.correlation.CorrelationId} — flat and stable for the whole
 *       flow. Answers <em>what belongs together</em>.
 *   <li>{@link com.finapp.platform.correlation.CausationId} — a link to the immediate parent.
 *       The chain answers <em>what caused what</em>, which is what makes a balance explainable
 *       rather than merely attributable.
 * </ul>
 *
 * <p>{@link com.finapp.platform.correlation.CorrelationContext} holds the current context and,
 * more importantly, carries it across threads. It does not use {@code InheritableThreadLocal}:
 * that copies at thread creation rather than at submission, so a pooled worker keeps the context
 * of whichever request happened to create it and stamps that identifier on every later request
 * it serves. The failure is not missing correlation but confidently wrong correlation.
 *
 * <h2>What is not here yet, and why</h2>
 *
 * <p>The task description also names an HTTP ingress filter and propagation into traces, outbox
 * events and audit records. None of those subsystems exists:
 *
 * <ul>
 *   <li>There is no HTTP surface to filter — {@code P0-EPIC-08}, milestone M0.4.
 *   <li>There is no outbox — {@code P0-EPIC-06}, milestone M0.3.
 *   <li>There is no audit store — {@code P0-EPIC-07}, milestone M0.3.
 *   <li>There is no tracing exporter — {@code P0-EPIC-09}, milestone M0.4.
 * </ul>
 *
 * <p>Each is a short call onto this API rather than a change to it, and building a filter for a
 * server that does not exist, or an exporter for traces nobody emits, would be inventing the
 * subsystem in the wrong task. The log half of the criterion — a request's log lines carrying
 * one identifier — is real and proven; {@code P0-TST-003} verifies the rest as the subsystems
 * arrive. See {@code CURRENT_STATE.md} §Partially Satisfied Definition of Done.
 *
 * <p>No Spring here. The kernel and its propagation are framework-free, which is what lets them
 * be tested without a container and reused by a scheduled job, a Kafka consumer and an HTTP
 * filter alike.
 */
package com.finapp.platform.correlation;
