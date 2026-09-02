package com.finapp.sharedkernel.security;

import java.util.Objects;
import java.util.function.Function;

/**
 * A value that must never reach a log, an event payload or an API response ({@code INV-AUD-02}).
 *
 * <p>The point is not that it <em>can</em> be redacted. It is that revealing it requires an
 * explicit, greppable act — {@link #expose()} — and that every accidental path renders a mask
 * instead. {@code INV-AUD-02} specifies its own enforcement as "default-deny redaction", and this
 * is what default-deny means for a value: safe unless somebody deliberately says otherwise.
 *
 * <h2>The accident this exists to prevent</h2>
 *
 * <p>Java generates a {@code toString()} for every record that prints every component:
 *
 * <pre>{@code
 * record Credentials(String username, String password) {}
 * log.info("authenticating {}", credentials);   // prints the password
 * }</pre>
 *
 * <p>Nobody writes anything that looks wrong. There is no call to {@code getPassword()}, no string
 * concatenation, nothing a reviewer would stop at — and the credential is in the log, in the log
 * shipper, and in whatever retains it for ninety days. Wrapping the component makes the same line
 * print {@code Credentials[username=ada, password=«redacted»]}.
 *
 * <h2>Every escape route is closed, not just the obvious one</h2>
 *
 * <ul>
 *   <li>{@link #toString()} masks. So does interpolation, SLF4J's {@code {}}, a record's generated
 *       {@code toString}, and Jackson's fallback for a type it cannot introspect.
 *   <li>{@link #equals(Object)} and {@link #hashCode()} are <strong>identity-based</strong>. Value
 *       equality would turn this into an oracle: an attacker with a guess and a comparison
 *       distinguishes right from wrong, which is how a "safe" wrapper leaks the thing it wraps.
 *       Compare secrets in the component that owns the comparison, not through a wrapper.
 *   <li>There is no getter. {@link #expose()} is named so that a reviewer, a grep and a code search
 *       all find every place a secret is unwrapped.
 * </ul>
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not encryption, not storage protection and not a substitute for keeping a secret out of
 * a process. It stops a value that is legitimately in memory from being written somewhere it does
 * not belong. Key management is a Phase 15 concern.
 *
 * @param <T> the wrapped type. Usually {@code String} or {@code char[]}; anything whose
 *     {@code toString} a log statement might reach
 */
public final class Sensitive<T> {

    /**
     * What is printed instead of the value.
     *
     * <p>Deliberately distinctive rather than {@code ***}: a log search for this string finds every
     * place redaction fired, which is how you tell "the field was empty" from "the field was
     * withheld". Asterisks appear in real data and prove nothing.
     */
    public static final String MASK = "«redacted»";

    private final T value;

    private Sensitive(T value) {
        this.value = value;
    }

    /**
     * Wraps a value that must not be logged.
     *
     * @throws NullPointerException if {@code value} is null. A null secret is a defect at the point
     *     it was constructed, and permitting it here would push the failure somewhere harder to
     *     diagnose - typically an authentication that silently never succeeds
     */
    public static <T> Sensitive<T> of(T value) {
        Objects.requireNonNull(value, "a sensitive value must not be null");
        return new Sensitive<>(value);
    }

    /**
     * Returns the value.
     *
     * <p>Named to be found. Every call is a place where a secret leaves its wrapper, and reviewing
     * this platform's handling of secrets should be a search for this method rather than a reading
     * of every class that might hold one.
     */
    public T expose() {
        return value;
    }

    /**
     * Applies {@code function} to the value and re-wraps the result.
     *
     * <p>So that deriving one secret from another - hashing a token, encoding a key - does not
     * require unwrapping it into a local variable that then exists, unwrapped, for the rest of the
     * method.
     */
    public <R> Sensitive<R> map(Function<? super T, ? extends R> function) {
        Objects.requireNonNull(function, "function must not be null");
        return Sensitive.of(function.apply(value));
    }

    /** Always {@value #MASK}. */
    @Override
    public String toString() {
        return MASK;
    }

    /**
     * Identity equality, deliberately.
     *
     * <p>Value equality would make this an oracle: {@code sensitive.equals(Sensitive.of(guess))}
     * answers whether the guess is right, and a wrapper that answers questions about the value it
     * hides is not hiding it. Two wrappers around equal values are not equal, and that is correct.
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    /** Identity hash, for the reason {@link #equals(Object)} gives. */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
