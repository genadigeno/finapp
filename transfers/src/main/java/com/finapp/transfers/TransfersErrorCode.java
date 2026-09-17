package com.finapp.transfers;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client (`P4-TSK-007`).
 *
 * <p>Namespaced {@code transfers.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 *
 * <p><strong>There is no beneficiary not-found code, deliberately</strong>: an unknown,
 * not-yours or malformed beneficiary identifier on the removal endpoint is {@code api.NotFound},
 * byte-identical across its causes — a distinct code would make the endpoint an oracle over
 * other people's saved destinations (the {@code P1-TSK-016} reasoning).
 */
public enum TransfersErrorCode implements ErrorCode {

    /**
     * The named destination resolves to no customer wallet (`P4-TSK-007`).
     *
     * <p>A {@code 422}: the destination identifier is a value the caller supplied and must be
     * able to correct. <strong>One code for unknown and malformed alike</strong>
     * (malformed-equals-absent, the {@code kyc.OwnerNotEligible} shape): the identifier names a
     * <em>third party's</em> product, so the refusal is uniform across its causes — what it
     * discloses is bounded to "no product with a wallet answers to this identifier", which is
     * exactly what any transfer naming it would disclose, behind an unguessable UUIDv7.
     */
    UNKNOWN_DESTINATION(
            "transfers.UnknownDestination",
            422,
            "The destination does not resolve to a platform account that can receive funds.");

    private final String code;
    private final int status;
    private final String title;

    TransfersErrorCode(String code, int status, String title) {
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
