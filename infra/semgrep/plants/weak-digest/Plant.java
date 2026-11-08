// A planted finding (infra/scripts/sast.sh): must be refused by finapp-java-weak-digest, by name, on every run.
package plant;

import java.security.MessageDigest;

class Plant {
    byte[] digest(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("MD5").digest(bytes);
    }
}
