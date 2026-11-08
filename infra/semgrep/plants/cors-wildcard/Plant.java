// A planted finding (infra/scripts/sast.sh): must be refused by finapp-spring-cors-wildcard, by name, on every run.
package plant;

import org.springframework.web.bind.annotation.CrossOrigin;

class Plant {
    @CrossOrigin(origins = "*")
    String anyOrigin() {
        return "";
    }
}
