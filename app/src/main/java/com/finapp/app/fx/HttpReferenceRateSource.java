package com.finapp.app.fx;

import com.finapp.fx.RateObservation;
import com.finapp.fx.RateSource;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code simulated-reference} adapter (`P9-TSK-005`, ADR-0075 §1, ADR-0008's SPI shape):
 * {@code GET {base}/fx/reference/rates} with {@code Authorization: Bearer} and the reference's OWN
 * confined credential.
 *
 * <p><strong>The wire is a strict line format, and every rate stays a decimal string.</strong>
 * One line per observation, {@code BASE/QUOTE,RATE,OBSERVED_AT}, e.g.
 * {@code EUR/USD,1.0812264160,2026-10-04T10:00:00Z}: a JSON number would invite a double between
 * the wire and the type ({@code INV-MON-01}'s reasoning at the boundary), and the line grammar
 * leaves nothing to interpret. A line is admitted only when its pair is one the declaration
 * publishes in its canonical direction and its rate is one {@link ExchangeRate} admits (positive,
 * precision at most 20, scale at most 10 - refused, never rounded); any other line is counted as
 * rejected and never stored. Blank lines are ignored.
 *
 * <p>Every answer is a value: {@code 200} is the body parsed, anything else - another status, a
 * refused or unreachable host, a timeout, a body past {@link #BODY_BOUND_BYTES} - is a named
 * failure, counted by the caller, never an exception through the sweep. The base URL is admitted
 * by {@code ProviderTransportGuard} before this class exists, and the key is never logged.
 */
public final class HttpReferenceRateSource implements RateSource {

    /** The rates path, below the source's base URL. */
    public static final String RATES_PATH = "/fx/reference/rates";

    /** Ten pairs need a few hundred bytes; anything near this is not a reference answer. */
    public static final int BODY_BOUND_BYTES = 64 * 1024;

    private static final Pattern LINE =
            Pattern.compile("^([A-Z]{3})/([A-Z]{3}),([0-9]{1,20}(?:\\.[0-9]{1,10})?),(\\S+)$");

    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final HttpClient http;

    public HttpReferenceRateSource(URI baseUrl, Duration timeout, byte[] key) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a reference fetch timeout must be positive");
        }
        String scheme = baseUrl.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalStateException(
                    "the reference adapter speaks HTTP only, and its URL's scheme is " + scheme);
        }
        this.key = key.clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String sourceCode() {
        return ReferenceSourceDeclaration.SOURCE;
    }

    @Override
    public Fetched fetch() {
        HttpRequest get =
                HttpRequest.newBuilder(baseUrl.resolve(RATES_PATH))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .GET()
                        .build();
        try {
            HttpResponse<InputStream> answer =
                    http.send(get, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = answer.body()) {
                if (answer.statusCode() != 200) {
                    return new Fetched.Failed(FetchFailure.REFUSED_ANSWER);
                }
                byte[] bytes = body.readNBytes(BODY_BOUND_BYTES + 1);
                if (bytes.length > BODY_BOUND_BYTES) {
                    return new Fetched.Failed(FetchFailure.REFUSED_ANSWER);
                }
                return parse(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (HttpTimeoutException slow) {
            return new Fetched.Failed(FetchFailure.TIMEOUT);
        } catch (ConnectException unreachable) {
            return new Fetched.Failed(FetchFailure.UNAVAILABLE);
        } catch (IOException broken) {
            return new Fetched.Failed(FetchFailure.TRANSPORT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Fetched.Failed(FetchFailure.TRANSPORT);
        }
    }

    /** The body's lines, each admitted or counted as rejected - package-visible for its test. */
    static Fetched.Rates parse(String body) {
        List<RateObservation> admitted = new ArrayList<>();
        int rejected = 0;
        for (String raw : body.split("\r?\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            RateObservation observation = admit(line);
            if (observation == null) {
                rejected++;
            } else {
                admitted.add(observation);
            }
        }
        return new Fetched.Rates(admitted, rejected);
    }

    private static RateObservation admit(String line) {
        Matcher fields = LINE.matcher(line);
        if (!fields.matches()) {
            return null;
        }
        try {
            ReferencePair pair = ReferencePair.of(fields.group(1), fields.group(2));
            if (!ReferenceSourceDeclaration.declares(pair)) {
                return null;
            }
            BigDecimal value = new BigDecimal(fields.group(3));
            if (value.signum() <= 0) {
                return null;
            }
            return new RateObservation(
                    ExchangeRate.of(
                            CurrencyCode.of(fields.group(1)),
                            CurrencyCode.of(fields.group(2)),
                            value),
                    Instant.parse(fields.group(4)));
        } catch (IllegalArgumentException | DateTimeParseException refused) {
            return null;
        }
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "HttpReferenceRateSource[" + ReferenceSourceDeclaration.SOURCE + "]";
    }
}
