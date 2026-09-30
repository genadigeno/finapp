package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HTTP pull adapter's construction (`P8-TSK-021`, the tests agent's find): it speaks HTTP
 * alone, so a source URL the transport guard admits but this adapter cannot speak — {@code sftp}
 * — refuses at startup rather than throwing on every pull; and it never prints its key.
 */
@DisplayName("the HTTP settlement report collector (P8-TSK-021)")
class HttpSettlementReportCollectorTest {

    private static final byte[] KEY = new byte[32];

    @Test
    @DisplayName("a source URL of any scheme but http or https refuses construction, naming the"
            + " scheme and never the URL")
    void onlyHttpIsSpoken() {
        for (String unspoken :
                List.of("sftp://reporter:hunter2@reports.bank.example.com/outbox",
                        "ftp://reports.bank.example.com")) {
            assertThatThrownBy(
                            () ->
                                    new HttpSettlementReportCollector(
                                            "simulated-bank.statement",
                                            URI.create(unspoken),
                                            Duration.ofSeconds(5),
                                            KEY))
                    .as(unspoken)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("simulated-bank.statement")
                    .hasMessageNotContaining("reports.bank.example.com")
                    .hasMessageNotContaining("hunter2");
        }
        for (String spoken :
                List.of("https://reports.psp.example.com", "http://localhost:8089")) {
            assertThatCode(
                            () ->
                                    new HttpSettlementReportCollector(
                                            "simulated-psp.settlement",
                                            URI.create(spoken),
                                            Duration.ofSeconds(5),
                                            KEY))
                    .as(spoken)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("its string form names the source and never the key")
    void neverPrintsItsKey() {
        byte[] key =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        HttpSettlementReportCollector collector =
                new HttpSettlementReportCollector(
                        "simulated-psp.settlement",
                        URI.create("https://reports.psp.example.com"),
                        Duration.ofSeconds(5),
                        key);
        assertThat(collector.toString())
                .isEqualTo("HttpSettlementReportCollector[simulated-psp.settlement]")
                .doesNotContain("0123456789abcdef");
    }
}
