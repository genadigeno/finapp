package com.finapp.kyc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP core both verification adapters share (`P2-TSK-009`, ADR-0008).
 *
 * <h2>The wire protocol is ours, and this class is the only place it exists</h2>
 *
 * <p>ADR-0008 simulates providers rather than connecting to real ones, so the "provider
 * vocabulary" this platform must keep out of its domain is the one defined here: the paths, the
 * request JSON, and the response's {@code status} field with its {@code clear}/{@code hit}
 * values. None of it crosses the {@link VerificationProvider} port — the port speaks
 * {@link CheckOutcome} — so a future real adapter replaces this class and touches nothing else.
 *
 * <h2>Every way a provider can misbehave is a result, never an exception</h2>
 *
 * <p>The mapping's default branch is {@link CheckOutcome#INDETERMINATE} — a state we have never
 * seen, a missing field, a truncated body, a 5xx, a timeout, a refused connection, bytes that
 * are not HTTP: all of them normalise to <em>we do not know</em> ({@code INV-LIFE-03}), never to
 * success or failure by assumption. Whatever bytes actually arrived are returned as evidence,
 * verbatim ({@code INV-HIST-02}): the unparseable answer is exactly what an investigation of the
 * provider wants to see.
 *
 * <h2>Bounds</h2>
 *
 * <p>The wait is bounded by the constructor's timeout — an unbounded wait on a provider is a
 * thread held hostage per in-flight check. The body is bounded by
 * {@link DocumentBytes#MAX_BYTES}: verbatim retention of an unbounded stream is not a property
 * anyone can keep, so an over-limit body is treated as garbage — {@code INDETERMINATE}, no
 * evidence, and the limit stated here rather than discovered at the evidence insert.
 */
final class SimulatedProviderClient {

    private static final Pattern STATUS_FIELD =
            Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    SimulatedProviderClient(URI baseUrl, Duration timeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the provider timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    VerificationProvider.ProviderResult ask(
            String path, VerificationProvider.VerificationSubject subject) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        // Identifiers only, and both are UUIDs, so the body needs no escaping machinery.
        String body =
                "{\"caseId\":\"" + subject.caseId().value()
                        + "\",\"customerId\":\"" + subject.customerId() + "\"}";
        HttpRequest request =
                HttpRequest.newBuilder(baseUrl.resolve(path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException nothingArrived) {
            // Timeout, refused connection, bytes that were not HTTP, a response that never came:
            // no answer exists, so there is no evidence to retain and nothing to guess from.
            return VerificationProvider.ProviderResult.withoutEvidence(
                    CheckOutcome.INDETERMINATE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return VerificationProvider.ProviderResult.withoutEvidence(
                    CheckOutcome.INDETERMINATE);
        }

        byte[] received = response.body();
        if (received.length == 0) {
            return VerificationProvider.ProviderResult.withoutEvidence(
                    CheckOutcome.INDETERMINATE);
        }
        if (received.length > DocumentBytes.MAX_BYTES) {
            // The stated bound: an answer too large to retain verbatim is treated as garbage.
            return VerificationProvider.ProviderResult.withoutEvidence(
                    CheckOutcome.INDETERMINATE);
        }
        return VerificationProvider.ProviderResult.of(outcomeOf(response), received);
    }

    private static CheckOutcome outcomeOf(HttpResponse<byte[]> response) {
        if (response.statusCode() != 200) {
            return CheckOutcome.INDETERMINATE;
        }
        Matcher status =
                STATUS_FIELD.matcher(
                        new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        if (!status.find()) {
            return CheckOutcome.INDETERMINATE;
        }
        // One definition of the wire vocabulary, shared with the callback parser (P2-TSK-011):
        // ADR-0008's sentence - an unrecognised provider state maps to indeterminate, never to
        // success or failure by assumption - must not exist twice.
        return CheckOutcome.fromWire(status.group(1));
    }
}
