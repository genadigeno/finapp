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
            "The destination does not resolve to a platform account that can receive funds."),

    /**
     * The named source resolves to no product of the caller's that can send funds
     * (`P4-TSK-008`).
     *
     * <p>A {@code 422}: a body field the caller supplied and must be able to correct — a 404
     * would describe the request URI, and {@code POST /v1/transfers} exists. <strong>One code
     * for unknown, not-yours and malformed alike</strong>: the resolution port answers all
     * three with one empty ({@code TransferParticipants.sourceOwnedBy}, {@code INV-IDN-07}'s
     * reasoning at a port), and the surface keeps the fold — a split would make the transfer
     * endpoint an oracle over other people's products. Distinct from
     * {@link #UNKNOWN_DESTINATION} because the remedies differ: a different field to fix.
     */
    UNKNOWN_SOURCE(
            "transfers.UnknownSource",
            422,
            "The source does not resolve to an account of the caller's that can send funds."),

    /**
     * The transfer is not in a state the machine permits a reversal to leave (`P4-TSK-009`).
     *
     * <p>A {@code 409}: the request was well formed and the state refuses it. <strong>One code
     * for {@code FAILED}, already-{@code REVERSED} and the loser of a concurrent race
     * alike</strong> — named for what is <em>checked</em> (the machine's edge, the
     * {@code NOT_ACTIVE} lesson), never for the commonest cause: a {@code FAILED} transfer
     * moved no money and has nothing to reverse, a {@code REVERSED} one is already corrected,
     * and both answers send the operator to the same place — {@code GET /v1/transfers/'{id}'},
     * whose view carries the state and the reversal when it exists. The caller is a
     * {@code TRANSFER_REVERSE} holder, so naming the state discloses nothing an operator does
     * not already read.
     */
    NOT_REVERSIBLE(
            "transfers.NotReversible",
            409,
            "The transfer is not in a state that can be reversed.");

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
