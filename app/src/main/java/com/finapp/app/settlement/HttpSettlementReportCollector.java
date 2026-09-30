package com.finapp.app.settlement;

import com.finapp.settlement.SettlementReportCollector;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * The pull adapter for one settlement source (`P8-TSK-021`, ADR-0066 §1, ADR-0008's SPI shape):
 * {@code GET {base}/settlement/reports/{source}/{businessKey}} with {@code Authorization: Bearer}
 * and the source's OWN confined credential — one instance per pulled source, so the PSP's,
 * the scheme's, the payout provider's and the bank's keys never meet.
 *
 * <p>Every answer is a value: {@code 200} is the report's bytes verbatim, {@code 404} is "not
 * yet", anything else — a refused or unreachable host, a timeout, bytes that are not HTTP, a
 * connection closed with no answer — is a named failure, counted and paced by the caller, never
 * an exception through the sweep. The base URL is admitted by {@code ProviderTransportGuard}
 * before this class exists ({@code https} or {@code sftp} off loopback), and the key is never
 * logged. This adapter speaks HTTP alone: a source URL of any other scheme — {@code sftp},
 * which the guard admits for a source — refuses its construction, so such a configuration
 * fails at startup instead of throwing on every pull (the tests agent's find; an {@code sftp}
 * collector is not built).
 */
public final class HttpSettlementReportCollector implements SettlementReportCollector {

    /** The report path, below the source's base URL: one path per source and business key. */
    public static final String REPORTS_PATH = "/settlement/reports/";

    private final String sourceCode;
    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final HttpClient http;

    public HttpSettlementReportCollector(
            String sourceCode, URI baseUrl, Duration timeout, byte[] key) {
        this.sourceCode = Objects.requireNonNull(sourceCode, "sourceCode must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a pull timeout must be positive");
        }
        String scheme = baseUrl.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            // The scheme alone - a URL can carry credentials in its userinfo.
            throw new IllegalStateException(
                    "the settlement pull adapter for " + sourceCode + " speaks HTTP only,"
                            + " and its source URL's scheme is " + scheme
                            + " - no collector for that transport is built");
        }
        this.key = key.clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String sourceCode() {
        return sourceCode;
    }

    @Override
    public Collected collect(String businessKey) {
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        HttpRequest get =
                HttpRequest.newBuilder(
                                baseUrl.resolve(
                                        REPORTS_PATH
                                                + URLEncoder.encode(
                                                        sourceCode, StandardCharsets.UTF_8)
                                                + "/"
                                                + URLEncoder.encode(
                                                        businessKey, StandardCharsets.UTF_8)))
                        .timeout(timeout)
                        .header(
                                "Authorization",
                                "Bearer " + Base64.getEncoder().encodeToString(key))
                        .GET()
                        .build();
        try {
            HttpResponse<byte[]> answer = http.send(get, HttpResponse.BodyHandlers.ofByteArray());
            return switch (answer.statusCode()) {
                case 200 -> new Collected.Report(answer.body());
                case 404 -> new Collected.NotYet();
                default -> new Collected.Failed(FailureOutcome.REFUSED_ANSWER);
            };
        } catch (HttpTimeoutException slow) {
            return new Collected.Failed(FailureOutcome.TIMEOUT);
        } catch (ConnectException unreachable) {
            return new Collected.Failed(FailureOutcome.UNAVAILABLE);
        } catch (IOException broken) {
            // Bytes that are not HTTP, or a connection closed with no answer: the lost response
            // is pulled again, and the content address deduplicates whatever arrives twice.
            return new Collected.Failed(FailureOutcome.TRANSPORT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Collected.Failed(FailureOutcome.TRANSPORT);
        }
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "HttpSettlementReportCollector[" + sourceCode + "]";
    }
}
