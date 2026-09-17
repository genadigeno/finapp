package com.finapp.accounts;

import java.io.Serial;

/**
 * The party holds no customer eligible to hold accounts (`P3-TSK-012`).
 *
 * <p><strong>One refusal for every cause, deliberately.</strong> No customer relationship, a
 * {@code PENDING} verification and a freed terminal slot are indistinguishable here — the
 * message names no cause and no identifier, because which of them refused is exactly what the
 * surface must not disclose, and the exception's text reaches logs. The transaction rolls back
 * with it, so a refused opening writes nothing, structurally (the {@code ConsentRequired}
 * shape). The HTTP mapping is `P3-TSK-013`'s.
 */
public final class AccountOpeningRefusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public AccountOpeningRefusedException() {
        super("the party holds no customer eligible to hold accounts");
    }
}
