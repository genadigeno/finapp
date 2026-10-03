package com.finapp.settlement;

import java.util.Objects;

/**
 * How the platform fetches one source's report (`P8-TSK-021`, ADR-0066 §1, ADR-0008's SPI
 * shape): an {@code app} adapter per pulled source, over that source's own confined credential,
 * to a transport {@code ProviderTransportGuard} admits.
 *
 * <p>{@link #collect} is an external read with an ambiguous outcome — at-least-once — and the
 * caller holds NO database connection across it (ADR-0046). Every answer is a value, never an
 * exception: a pull that fails is counted and paced, never thrown through a sweep.
 */
public interface SettlementReportCollector {

    /** The declared source this collector fetches for. */
    String sourceCode();

    /**
     * Fetches the report {@code businessKey} names — an ISO business date, or a scheme cycle
     * token — as the counterparty publishes it.
     */
    Collected collect(String businessKey);

    /** What one fetch came to. */
    sealed interface Collected {

        /** The counterparty answered with a report: its bytes, verbatim. */
        record Report(byte[] content) implements Collected {
            public Report {
                Objects.requireNonNull(content, "content must not be null");
                content = content.clone();
            }

            @Override
            public byte[] content() {
                return content.clone();
            }

            /** Never the bytes. */
            @Override
            public String toString() {
                return "Report[" + content.length + " bytes]";
            }
        }

        /** The counterparty has not published it yet — asked again as the permit paces. */
        record NotYet() implements Collected {}

        /** Nothing usable came back; the outcome names why, for the failure counter. */
        record Failed(FailureOutcome outcome) implements Collected {
            public Failed {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }
    }

    /** Why a fetch produced nothing usable — the {@code outcome} tag's closed vocabulary. */
    enum FailureOutcome {
        /** Refused or unreachable: nothing was sent, or nothing listened. */
        UNAVAILABLE,
        /** No answer within the timeout. */
        TIMEOUT,
        /** The connection answered bytes that are not HTTP, or closed with no response. */
        TRANSPORT,
        /** An HTTP answer the collector does not read as a report: 4xx other than 404, 5xx. */
        REFUSED_ANSWER
    }
}
