/**
 * The mechanism every provider adapter's HTTP exchange goes through (`P16-TSK-018`; {@code PHASE_16_PLAN.md} section 8;
 * {@code docs/architecture/TIMEOUTS_AND_RETRIES.md}): {@link com.finapp.platform.outbound.OutboundExchange} bounds an
 * exchange end to end, its response body included, which the JDK's own request timeout does not. {@code platform} because
 * every adapter module may see it and it holds no provider's vocabulary: no path, no status, no classification - each adapter
 * still decides what a failure means for its operation.
 */
package com.finapp.platform.outbound;
