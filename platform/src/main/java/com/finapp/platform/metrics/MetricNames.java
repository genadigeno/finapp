package com.finapp.platform.metrics;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The naming convention every meter this platform registers must satisfy.
 *
 * <p>Framework-free, and in {@code platform} for the reason {@code P0-TSK-029} exists at all:
 * <em>metric naming decided late becomes inconsistent across contexts</em>. Twenty-four modules
 * each inventing a name gives an operator twenty-four vocabularies and a dashboard that cannot be
 * written once. Deciding it now costs a class; deciding it after Phase 8 costs every dashboard,
 * every alert rule and every runbook that was written against the old names.
 *
 * <h2>The shape</h2>
 *
 * <pre>finapp.&lt;module&gt;.&lt;noun&gt;[.&lt;noun&gt;]</pre>
 *
 * <p>Dots, lower case, no camel case. Micrometer translates dots to the idiom of whichever
 * backend is attached — {@code finapp_outbox_pending} in Prometheus — so writing the Prometheus
 * form here would bake one backend into the name and lose the translation.
 *
 * <p>The {@code finapp.} prefix is not decoration. A scrape carries JVM, HTTP, pool and framework
 * meters alongside ours, and an operator writing an alert needs to know at a glance whether a
 * series is something this platform promised or something a library happened to expose. Only the
 * former is a contract.
 *
 * <h2>Tags: the rule that matters</h2>
 *
 * <p><strong>A tag value must come from a small, closed set that is fixed at compile time.</strong>
 * Never an identifier, a correlation id, an account, a customer, an amount, a key, a path, or
 * anything else a request can influence.
 *
 * <p>Two independent reasons, either sufficient:
 *
 * <ul>
 *   <li><strong>Cardinality.</strong> Every distinct tag combination is a separate time series
 *       held in memory and stored for the retention period. One tag carrying an account
 *       identifier turns one series into as many series as there are accounts, and the failure is
 *       not a warning — it is the metrics backend falling over, taking the ability to observe the
 *       incident with it.
 *   <li><strong>{@code INV-AUD-02}.</strong> A metric is scraped continuously and retained for
 *       months, by a system with different access control from the database. An identifier in a
 *       tag is a disclosure that outlives the request that produced it by a long way — worse than
 *       the same value in a log line, which at least gets rotated.
 * </ul>
 *
 * <p>Correlation belongs on a <em>trace</em> and in the <em>log</em>, both of which are per-event
 * and searchable. A metric answers "how many, how long, how often" — never "which one".
 */
public final class MetricNames {

    /** Everything this platform publishes starts here. */
    public static final String PREFIX = "finapp.";

    /**
     * {@code finapp.<module>.<noun>[.<noun>...]}
     *
     * <p>At least two segments after the prefix, so {@code finapp.errors} — a name that tells an
     * operator nothing about who owns it — cannot be registered.
     */
    public static final Pattern NAME = Pattern.compile("finapp\\.[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+");

    /**
     * Tag keys permitted on a {@code finapp.} meter.
     *
     * <p>A closed set, deliberately. Adding one is a decision about cardinality and disclosure,
     * and making it an edit here is what forces that decision to be made by somebody rather than
     * arrived at by an autocomplete.
     */
    public static final Set<String> ALLOWED_TAG_KEYS =
            Set.of(
                    // Which of a fixed set of outcomes. Bounded by an enum, always.
                    "outcome",
                    // Which module owns the meter, where one meter serves several.
                    "module",
                    // The kind of thing acted on - a type name, never an instance identifier.
                    "type",
                    // Which consent purpose (P2-TSK-020). Bounded by the closed public
                    // ConsentPurpose enum - a category of processing shared by everyone, which
                    // can never name a person or a resource (P2-TSK-018's reasoning for the one
                    // /v1/me path variable). Added deliberately, which is this list's job: the
                    // plan's own table says "counter by purpose", and unlike P1-TSK-029's
                    // refused `stage` there is no naming that carries the signal without it.
                    "purpose",
                    // Which currency (P3-TSK-019). Bounded by ISO 4217 - a closed standard
                    // vocabulary that names a category shared by everyone and structurally
                    // cannot name a person or a resource. Added deliberately, which is this
                    // list's job: the Phase 3 plan's own §15 table says the trial-balance
                    // gauge is "per currency", and a per-currency name split would invent
                    // series the plan does not carry (the `purpose` precedent, P2-TSK-020).
                    "currency",
                    // Which external provider (P5-TSK-017). Bounded by DEPLOYMENT, not by a
                    // request: the value is an adapter's own compile-time constant
                    // (SimulatedCardPspAdapter.NAME) naming an ORGANISATION this platform
                    // integrates with, so it can never name a person, a resource or anything
                    // a caller supplies. Added deliberately, which is this list's job: the
                    // Phase 5 plan's own table says the provider timer is "by provider,
                    // operation", and ADR-0049 records that one provider becomes several -
                    // at which point a name split would invent a series per integration and
                    // make "which provider is slow" unanswerable in one query.
                    "provider",
                    // Which provider operation (P5-TSK-017). Bounded by the PaymentProvider
                    // port's own methods - authorize, capture, refund, query - a closed set
                    // the compiler owns. Never the provider's status vocabulary
                    // (INV-PAY-03): what is tagged is OUR word for the call we made.
                    "operation");

    /**
     * Allowed keys the fragment rule below would otherwise refuse <strong>on a spelling
     * accident</strong> (`P5-TSK-017`).
     *
     * <p>{@code provider} contains the letters {@code id}. The fragment rule is about what a
     * key's NAME IMPLIES — {@code account_id}, {@code correlation_id}, {@code userId} — and
     * "provider" implies an organisation this platform integrates with, which is a category,
     * not an instance. The alternatives were worse: renaming the tag would make the plan's
     * own §15 table and every dashboard read differently from the code, and softening the
     * rule to whole-word matching would let {@code accountid} through, which is the exact
     * shape the rule exists to catch.
     *
     * <p>Membership here is <strong>not</strong> a licence: a key listed here must still be
     * in {@link #ALLOWED_TAG_KEYS}, so it has already been argued for in writing above, and
     * the guard asserts that containment rather than trusting it.
     */
    public static final Set<String> FRAGMENT_EXEMPT_TAG_KEYS = Set.of("provider");

    /** Substrings that must never appear in a tag KEY, because of what they imply about values. */
    public static final Set<String> FORBIDDEN_TAG_KEY_FRAGMENTS =
            Set.of("id", "key", "account", "customer", "amount", "correlation", "user", "path", "email");

    private MetricNames() {
        throw new AssertionError("not instantiable");
    }

    /** True when {@code name} satisfies the convention. */
    public static boolean isWellFormed(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** True when {@code name} is one this platform publishes, rather than a library's. */
    public static boolean isOurs(String name) {
        return name != null && name.startsWith(PREFIX);
    }
}
