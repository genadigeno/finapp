// A planted finding (infra/scripts/sast.sh): must be refused by finapp-provider-body-unbounded, by name, on every run.
package plant;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

class Plant {
    byte[] answer(HttpClient http, HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }
}
