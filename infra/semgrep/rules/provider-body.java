// The rule's own test (infra/scripts/sast.sh runs `semgrep --test` over this directory, offline, on every run) and the
// planted case of THREAT_MODEL.md T43: each of these is also scanned by the job's real scan, which must refuse it.
package plant;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

class ProviderBody {

    private static final int BOUND = 64 * 1024;

    byte[] wholeBytes(HttpClient http, HttpRequest request) throws Exception {
        // ruleid: finapp-provider-body-unbounded
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    String wholeText(HttpClient http, HttpRequest request) throws Exception {
        // ruleid: finapp-provider-body-unbounded
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    byte[] importedHandlers(HttpClient http, HttpRequest request) throws Exception {
        // ruleid: finapp-provider-body-unbounded
        return http.send(request, BodyHandlers.ofByteArray()).body();
    }

    byte[] aStreamReadWhole(HttpClient http, HttpRequest request) throws Exception {
        try (InputStream body = http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
            // ruleid: finapp-provider-body-unbounded
            return body.readAllBytes();
        }
    }

    byte[] boundedRead(HttpClient http, HttpRequest request) throws Exception {
        // ok: finapp-provider-body-unbounded
        HttpResponse<InputStream> answer = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = answer.body()) {
            // ok: finapp-provider-body-unbounded
            byte[] bytes = body.readNBytes(BOUND + 1);
            if (bytes.length > BOUND) {
                throw new IllegalStateException("oversized");
            }
            return bytes;
        }
    }
}
