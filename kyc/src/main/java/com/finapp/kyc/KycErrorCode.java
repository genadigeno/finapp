package com.finapp.kyc;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client.
 *
 * <p>Namespaced {@code kyc.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 */
public enum KycErrorCode implements ErrorCode {

    /**
     * The caller has no open KYC case to act on.
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong>
     * ({@code P1-TSK-018}'s test for earning one): the caller's case was decided — changed
     * circumstances are a <em>new</em> case ({@code INV-LIFE-04}), so the remedy is opening one
     * ({@code POST /v1/me/kyc}), not retrying the upload. No enumeration concern applies: the
     * caller is the authenticated owner asking about their own case, which is the opposite of
     * {@code party.RegistrationRefused}'s stranger.
     */
    NO_OPEN_CASE(
            "kyc.NoOpenCase", 409, "You have no open verification case for this to apply to.");

    private final String code;
    private final int status;
    private final String title;

    KycErrorCode(String code, int status, String title) {
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
