package com.finapp.app.surface;

/**
 * The doors that issue or strengthen a session, or change what can prove an identity, and so refuse a role-holding identity on
 * the public surface (`P15-TSK-042`).
 *
 * <p>Which handlers are doors is not this list's to say: {@code OperatorsRefusedOnPublicDoorsTest} reads it from the bytecode,
 * every handler that reaches a session's issue or rotation, a factor's enrolment, a credential's replacement through recovery,
 * or a recovery channel's addition. Each door's operation is its route as Spring maps it, and the same test holds that too.
 */
public enum SurfaceDoor {
    AUTHENTICATION("authentication", "POST /v1/authentications"),
    MFA_CHALLENGE("mfa_challenge", "POST /v1/authentications/mfa"),
    MFA_ENROLMENT("mfa_enrolment", "POST /v1/me/mfa"),
    MFA_CONFIRMATION("mfa_confirmation", "POST /v1/me/mfa/confirmation"),
    CREDENTIAL_CHANGE("credential_change", "POST /v1/me/credential"),
    CHANNEL_ADDITION("channel_addition", "POST /v1/me/channels"),
    RECOVERY("recovery", "POST /v1/recoveries"),
    RECOVERY_COMPLETION("recovery_completion", "POST /v1/recoveries/{id}/completion");

    private final String tag;
    private final String operation;

    SurfaceDoor(String tag, String operation) {
        this.tag = tag;
        this.operation = operation;
    }

    /** The meter's {@code type}: a closed set. */
    public String tag() {
        return tag;
    }

    /** {@code <METHOD> <route pattern>}, as the audit record names it: never a path's values. */
    public String operation() {
        return operation;
    }
}
