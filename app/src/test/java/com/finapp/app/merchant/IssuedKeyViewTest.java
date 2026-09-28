package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The merchant API key's issuance view masks its secret - the test its exemption claimed.
 *
 * <p>{@code P6-TSK-002} exempted {@code IssuedKeyView.secret} from three guards so that the one
 * response carrying it can be serialised, and closed the logging half with an override. The
 * override existed; nothing asserted it, so deleting it would have passed every test. The
 * Phase 6 → 7 transition found that gap beside the checkout view's missing override, and closes
 * it the way {@code AuthenticatedSessionTest} closed the first one.
 */
@DisplayName("IssuedKeyView masks its secret (P6-TSK-002's exemption, backed at the Phase 6 -> 7"
        + " transition)")
class IssuedKeyViewTest {

    private static final String SECRET = "fk_live_" + UUID.randomUUID();

    @Test
    @DisplayName("toString, interpolation and formatting mask the secret")
    void everyRenderingPathMasks() {
        MerchantApiKeyOperations.IssuedKeyView view =
                new MerchantApiKeyOperations.IssuedKeyView(
                        UUID.randomUUID().toString(), SECRET, false);

        assertThat(view.toString())
                .as("the exemption permits serialisation, never logging")
                .doesNotContain(SECRET)
                .contains(Sensitive.MASK);
        assertThat("" + view).doesNotContain(SECRET);
        assertThat(String.format("%s", view)).doesNotContain(SECRET);
    }
}
