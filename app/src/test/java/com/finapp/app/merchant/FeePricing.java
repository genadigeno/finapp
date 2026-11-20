package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A merchant priced over HTTP by the two-person path (`P17-TSK-123`): a holder of {@code FEE_ADMINISTER} (a
 * {@code MERCHANT_ADMINISTRATOR}) creates the schedule, proposes its version and the merchant's assignment, each keyed; a
 * holder of {@code FEE_SCHEDULE_APPROVE} (a {@code FINANCIAL_CONTROLLER}) who did not propose them approves - the version
 * minted, the pointer moved. Every suite that sets a merchant's fees over HTTP prices it here, through its own door, so the
 * fixtures exercise the doors the operator uses, four eyes kept.
 */
public final class FeePricing {

    /** An answer: its status and its body. */
    public record Reply(int status, String body) {}

    /** One exchange over the suite's own client: method, path, JSON body (or null), bearer, {@code Idempotency-Key}. */
    @FunctionalInterface
    public interface Door {
        Reply send(String method, String path, String body, String bearer, String idempotencyKey) throws Exception;
    }

    private final Door door;
    private final String administrator;
    private final String controller;

    /**
     * @param administrator a {@code MULTI_FACTOR} session holding {@code FEE_ADMINISTER}: the proposer
     * @param controller a {@code MULTI_FACTOR} session holding {@code FEE_SCHEDULE_APPROVE}, another person: the approver
     */
    public FeePricing(Door door, String administrator, String controller) {
        this.door = door;
        this.administrator = administrator;
        this.controller = controller;
    }

    /** A door over plain HTTP to {@code http://localhost:<port>}, the database suites' own shape. */
    public static Door overHttp(int port) {
        return (method, path, body, bearer, idempotencyKey) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (idempotencyKey != null) {
                request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
            }
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Reply(response.statusCode(), response.body());
            }
        };
    }

    /** A version's request body; a null {@code effectiveFrom} is omitted, which is "when it is approved". */
    public static String versionBody(String rate, long fixedMinor, String rounding, String refundFeePolicy,
            Instant effectiveFrom, String reason) {
        return "{\"rate\":" + rate + ",\"fixedAmountMinor\":" + fixedMinor + ",\"roundingPolicy\":\"" + rounding
                + "\",\"refundFeePolicy\":\"" + refundFeePolicy + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"")
                + ",\"reason\":\"" + reason + "\"}";
    }

    /** 2.9% plus 30 minor units, rounded half-even, the fee retained on a refund: the suites' standard terms. */
    public static String standardTerms() {
        return versionBody("0.029", 30, "HALF_EVEN", "RETAINED", null, "initial pricing");
    }

    /** A schedule named {@code name} in {@code currency}, created by the administrator: its id. */
    public String schedule(String name, String currency) throws Exception {
        Reply created = door.send("POST", "/v1/operator/fee-schedules",
                "{\"name\":\"" + name + "\",\"currency\":\"" + currency + "\"}", administrator, key());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return field(created.body(), "feeScheduleId");
    }

    /** The administrator's version proposal of {@code versionBody} on {@code schedule}: the answer, unasserted. */
    public Reply proposeVersion(String schedule, String versionBody) throws Exception {
        return door.send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions", versionBody, administrator, key());
    }

    /** {@code versionBody} on {@code schedule}, proposed by the administrator and approved by the controller: the version. */
    public String version(String schedule, String versionBody) throws Exception {
        Reply proposed = proposeVersion(schedule, versionBody);
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        Reply approved = door.send("POST", "/v1/operator/fee-schedule-version-proposals/"
                + field(proposed.body(), "proposalId") + "/approval", "{\"reason\":\"reviewed\"}", controller, key());
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return field(approved.body(), "feeScheduleVersionId");
    }

    /** The administrator's assignment proposal of {@code schedule} to {@code merchant}: the answer, unasserted. */
    public Reply proposeAssignment(String merchant, String schedule, String reason) throws Exception {
        return door.send("PUT", "/v1/operator/merchants/" + merchant + "/fee-schedule",
                "{\"feeScheduleId\":\"" + schedule + "\",\"reason\":\"" + reason + "\"}", administrator, key());
    }

    /** {@code merchant} pointed at {@code schedule}: proposed by the administrator, approved by the controller when proposed. */
    public void assign(String merchant, String schedule, String reason) throws Exception {
        Reply proposed = proposeAssignment(merchant, schedule, reason);
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(200);
        if ("ASSIGNED".equals(field(proposed.body(), "status"))) {
            return;
        }
        Reply approved = door.send("POST", "/v1/operator/fee-assignment-proposals/" + field(proposed.body(), "proposalId")
                + "/approval", "{\"reason\":\"reviewed\"}", controller, key());
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
    }

    /** {@code merchant} priced by a new {@code currency} schedule of {@code versionBody}, four eyes kept: the schedule. */
    public String priced(String merchant, String currency, String versionBody) throws Exception {
        String schedule = schedule("Std " + UUID.randomUUID(), currency);
        version(schedule, versionBody);
        assign(merchant, schedule, "standard terms");
        return schedule;
    }

    /** {@code merchant} priced in EUR at {@link #standardTerms()}: the schedule. */
    public String priced(String merchant) throws Exception {
        return priced(merchant, "EUR", standardTerms());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** A string field of a JSON body. */
    public static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
