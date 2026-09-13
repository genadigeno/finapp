package com.finapp.consent;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client.
 *
 * <p>Namespaced {@code consent.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 *
 * <p><strong>There is no withdrawal code, and that is the module's sharpest absence</strong>: a
 * withdrawal must not be refusable by anything but authentication (`P2-TSK-018`), so there is no
 * withdrawal failure for a code to name. Both codes here concern the <em>grant</em>, whose one
 * domain rule is {@code INV-CNS-04}'s: a grant is a statement about a specific version of
 * specific words.
 */
public enum ConsentErrorCode implements ErrorCode {

    /**
     * The grant names a text version that a later version has superseded with
     * {@code requires_reconsent}.
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong>
     * ({@code P1-TSK-018}'s test for earning one): the remedy is to fetch
     * {@code GET /v1/me/consents}, present the current words to the person, and grant against
     * the current version. Recording the stale grant instead would write a fact the derivation
     * immediately judges basis-less — a "consent" that consents to nothing, which is worse than
     * a refusal because the client walks away believing a basis exists.
     *
     * <p>No enumeration concern applies: consent texts are the platform's most public artefact
     * — the words shown to every customer — so telling the caller a newer version exists
     * discloses nothing.
     */
    RECONSENT_REQUIRED(
            "consent.ReconsentRequired",
            409,
            "The consent text has changed and requires re-consent; grant against the current"
                    + " version."),

    /**
     * The grant names a text version that was never published for this purpose.
     *
     * <p>A {@code 422} rather than folding into {@code consent.ReconsentRequired}, because the
     * two demand different client behaviour: here the integer is a client defect to fix — no
     * amount of re-presenting text produces version 7 of a purpose whose history ends at 2 —
     * while there the request was well-formed and the world moved. The composite FK
     * ({@code consent_record_pins_its_purposes_text}) backs this refusal as defence in depth;
     * refusing at the boundary is what keeps a client mistake from surfacing as our 500
     * ({@code ERROR_CONTRACT.md} §3).
     */
    UNKNOWN_TEXT_VERSION(
            "consent.UnknownTextVersion",
            422,
            "No consent text with this version exists for this purpose.");

    private final String code;
    private final int status;
    private final String title;

    ConsentErrorCode(String code, int status, String title) {
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
