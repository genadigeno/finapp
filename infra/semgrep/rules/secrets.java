// The rules' own test: `semgrep --test` over this directory, offline, on every run of infra/scripts/sast.sh. The values
// are obviously not credentials. The two shaped rules have no fixture here: see secrets.yml.
package plant;

class SecretRules {

    // ruleid: finapp-secret-credential-literal
    private static final String DB_PASSWORD = "correct horse battery staple";

    // ruleid: finapp-secret-credential-literal
    private String apiKey = "not-a-real-key";

    // ok: finapp-secret-credential-literal
    private static final String PASSWORD_HEADER = "X-Password";

    // ok: finapp-secret-credential-literal
    private static final String SECRET_PROPERTY = "finapp.secret";

    // ok: finapp-secret-credential-literal
    private static final String DISPLAY_LABEL = "not a credential";

    // ok: finapp-secret-credential-literal
    private String password = "";

    void assignment() {
        // ruleid: finapp-secret-credential-literal
        secret = "swordfish";
    }

    private String secret;
}
