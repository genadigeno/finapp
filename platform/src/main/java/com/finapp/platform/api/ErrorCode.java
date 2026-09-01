package com.finapp.platform.api;

/**
 * A stable, machine-readable reason an API call failed.
 *
 * <p>The taxonomy {@code .claude/rules/api-design.md} requires when it says API error contracts
 * must be explicit. A client switches on {@link #code()}; everything else in a problem detail is
 * for a human.
 *
 * <h2>Why an interface rather than one enum</h2>
 *
 * <p>The same reason as {@code AuditableAction}: error codes belong to the modules that raise
 * them. {@code transfers.InsufficientFunds} is the Transfers module's concept, and the platform
 * sits <em>below</em> every business module, so an enum here naming their failures would invert
 * the dependency direction and make the platform's vocabulary the union of every domain's.
 *
 * <p>Each module declares its own enum; only {@code app} sees all of them, which is where the
 * catalogue is reconciled ({@code ErrorCodeRegistryTest}).
 *
 * <h2>Why the status lives here</h2>
 *
 * <p>An HTTP status code in a module that knows nothing about HTTP looks misplaced, and the
 * alternative is worse: a mapping from code to status maintained in the rendering layer, far
 * from the code it describes, where two modules can disagree about whether the same failure is a
 * 409 or a 422. The status is part of what the code <em>means</em> — whether the caller did
 * something wrong, and whether retrying could ever help — so it belongs with the code. It is an
 * integer, not a framework type, so nothing about this couples the platform to a web stack.
 *
 * <h2>Codes are permanent</h2>
 *
 * <p>A client's error handling is written against these strings. Renaming one silently changes
 * the meaning of every client's {@code switch}, which is a breaking change wearing a refactor's
 * clothes — and unlike a broken build, it fails at the customer's end. Add a new code and
 * deprecate the old one instead.
 */
public interface ErrorCode {

    /**
     * The stable identifier a client matches on, namespaced by module — {@code api.NotFound},
     * {@code transfers.InsufficientFunds}.
     *
     * <p>Namespaced so two modules cannot collide on a bare name and give one string two
     * meanings; enforced by {@code ErrorCodeRegistryTest}.
     */
    String code();

    /**
     * The HTTP status this failure is reported with.
     *
     * <p>Part of the code's meaning rather than a rendering detail — see the class note.
     */
    int status();

    /**
     * A short, human-readable summary, safe to show anyone.
     *
     * <p>Fixed per code and never built from an exception. This is the sentence that reaches the
     * client, so it must not depend on runtime state: the moment a title is assembled from
     * something a caller supplied or a provider returned, the contract has become a channel for
     * whatever that string happened to contain ({@code INV-AUD-02}).
     */
    String title();
}
