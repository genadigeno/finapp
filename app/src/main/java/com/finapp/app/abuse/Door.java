package com.finapp.app.abuse;

/**
 * The doors a stranger can knock on: every route that needs no session and no signature (`P15-TSK-022`).
 *
 * <p>Each is counted per source in {@code platform.window_counter} under its own scope, and each has its own limit
 * ({@link SourceLimits}). A closed set, so the meters' tag and the table's scopes cannot grow from a request.
 */
public enum Door {
    REGISTRATION("registration"),
    AUTHENTICATION("authentication"),
    RECOVERY("recovery"),
    RECOVERY_COMPLETION("recovery-completion"),
    CHANNEL_VERIFICATION("channel-verification");

    private final String label;

    Door(String label) {
        this.label = label;
    }

    /** The name a meter and the configuration use. */
    public String label() {
        return label;
    }

    /** The scope this door counts under. */
    String scope() {
        return "door." + label;
    }
}
