/**
 * Correlation and causation identifiers — the value types, not the mechanism.
 *
 * <p><strong>Why these are here and the context is not.</strong> A correlation identifier is a
 * validated value with no dependencies, and it appears in the event envelope, in idempotency
 * records and in audit records — types that every module handles. That makes it exactly what
 * the shared kernel is for. {@code CorrelationContext}, which carries the current flow across
 * threads and writes it into SLF4J's MDC, is a mechanism with a logging dependency and stays in
 * {@code platform}.
 *
 * <p>The split is the general rule rather than a special case: value types sit below the
 * mechanisms that move them around. It is also what lets the event envelope live here at all —
 * {@code MODULE_ARCHITECTURE.md} §2 places the envelope in the shared kernel, and an envelope
 * carrying correlation could not honour that while these types sat a layer above it.
 *
 * <p>Neither type is a business concept, so neither offends the rule that domain nouns belong
 * to their owning module.
 */
package com.finapp.sharedkernel.correlation;
