package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Hostile input in the one free-text field, over real HTTP (`P1-TSK-006` gate).
 *
 * <h2>Why this exists as a test rather than as a probe somebody ran once</h2>
 *
 * <p>It began as a throwaway probe during the completion gate and <strong>found a real defect</strong>:
 * a NUL byte in {@code displayName} produced <strong>{@code 500 api.InternalError}</strong>. A NUL
 * cannot be stored in a PostgreSQL {@code text} column at all, so the driver rejected it three
 * layers below the boundary and the catch-all rendered it as our failure. That is precisely what
 * {@code ERROR_CONTRACT.md} §3 forbids — *a client's mistake is never reported as ours* — and it is
 * not cosmetic: a client may retry a 500 for ever on a request that can never succeed, and a spike
 * of them is indistinguishable from an outage on every error-rate dashboard.
 *
 * <p>The same probe showed CR, LF, tab and a bidirectional override being accepted <em>into a
 * {@code RESTRICTED-PII} column</em>. Nothing prints a name today, so nothing was forged; that is
 * exactly the shape of accidental safety this repository has resolved to write down rather than
 * rely on, and it is the weak point {@code DATA_CLASSIFICATION.md} §5 names in this scheme.
 *
 * <p>{@code P0-TST-008}'s finding was that a probe living only in a shell does not survive the
 * person who ran it. So it lives here.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HostileInputProbeDatabaseTest {

    @LocalServerPort private int port;

    @Test
    @DisplayName("no control character in a name is ever answered with a 500")
    void controlCharactersAreRefusedAtTheBoundary() throws Exception {
        // Each is written as a JSON escape, so the hostile character reaches the handler as a real
        // character rather than as the six letters that spell it.
        String[][] hostile = {
            {"NUL", "Ada\\u0000Lovelace"},
            {"line feed", "Ada\\nLovelace"},
            {"carriage return", "Ada\\rLovelace"},
            {"tab", "Ada\\tLovelace"},
            {"backspace", "Ada\\bLovelace"},
            {"bidirectional override", "Ada\\u202ELovelace"},
            {"zero-width joiner", "Ada\\u200DLovelace"},
        };

        for (String[] probe : hostile) {
            HttpResponse<String> response = register(probe[1]);

            assertThat(response.statusCode())
                    .as("%s must be the caller's mistake, never ours", probe[0])
                    .isEqualTo(422);
            assertThat(response.body())
                    .as("%s", probe[0])
                    .contains("api.ValidationFailed")
                    .contains("displayName");
        }
    }

    @Test
    @DisplayName("real names are still accepted, which is what makes the rule above a control")
    void realNamesAreUnaffected() throws Exception {
        // The other half, and the more important one. A rule that rejected legitimate names would
        // be worse than the defect it closed: PartyName's own documentation refuses a charset
        // restriction for exactly this reason, and the exclusion is narrow enough not to be one.
        for (String name :
                new String[] {
                    "O'Brien",
                    "Jean-Luc Picard",
                    "Ægir Þórsson",
                    "李雷",
                    "Иван Петров",
                    "María José de la Cruz-Fernández",
                    "Ada 😀"
                }) {
            HttpResponse<String> response = register(jsonEscaped(name));

            assertThat(response.statusCode()).as("%s is somebody's actual name", name).isEqualTo(201);
        }
    }

    // -----------------------------------------------------------------

    /** Escapes only what JSON requires, so the name reaches the handler unchanged. */
    private static String jsonEscaped(String name) {
        StringBuilder escaped = new StringBuilder();
        for (char character : name.toCharArray()) {
            if (character == '"' || character == '\\') {
                escaped.append('\\');
            }
            if (character < 0x20) {
                escaped.append(String.format("\\u%04x", (int) character));
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private HttpResponse<String> register(String displayNameLiteral) throws Exception {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/registrations"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\""
                                                + login
                                                + "\",\"displayName\":\""
                                                + displayNameLiteral
                                                + "\",\"password\":\"not-a-real-password\"}"))
                        .build();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
