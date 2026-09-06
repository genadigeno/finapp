package com.finapp.party;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client.
 *
 * <p>Namespaced {@code party.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 */
public enum PartyErrorCode implements ErrorCode {

    /**
     * A registration could not be completed. <strong>One code for every reason.</strong>
     *
     * <p>This is the deliberate coarseness {@code INV-IDN-07} requires. The commonest reason is
     * that the login identifier is already in use, and saying so would turn this endpoint into an
     * account-existence oracle: anyone could submit identifiers and read the answer off the status
     * code. So a collision and any other domain refusal produce a byte-identical response, and a
     * legitimate caller who picks a taken identifier gets no explanation.
     *
     * <p>That cost is real and is accepted. It is the same trade every serious platform makes for
     * an email address, and it is the reason this code carries no detail beyond its title.
     *
     * <p>422 rather than 409: a 409 would say <em>this conflicts with something that exists</em>,
     * which is precisely the fact that must not be disclosed. The request was well formed and was
     * not accepted, which is what 422 means ({@code ERROR_CONTRACT.md} §3, 400 versus 422).
     */
    REGISTRATION_REFUSED(
            "party.RegistrationRefused", 422, "This registration could not be completed.");

    private final String code;
    private final int status;
    private final String title;

    PartyErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
