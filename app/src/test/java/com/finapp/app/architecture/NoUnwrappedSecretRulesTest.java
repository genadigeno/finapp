package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.security.Sensitive;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A secret cannot be held in a field that would print itself ({@code INV-AUD-02}).
 *
 * <h2>What "default-deny" means here</h2>
 *
 * <p>{@code INV-AUD-02} does not merely forbid credentials in logs; it specifies the enforcement as
 * <em>default-deny redaction</em>. The usual approach is the opposite — annotate the sensitive
 * fields — and it fails the first time somebody adds a field and does not think about it, which is
 * every time somebody is in a hurry. Here the failure mode is inverted: a field whose name says it
 * holds a secret must be wrapped in {@link Sensitive}, and the build fails until it is.
 *
 * <h2>The accident being prevented</h2>
 *
 * <p>Java writes a {@code toString()} for every record that prints every component. {@code
 * log.info("authenticating {}", credentials)} then prints the password, with no getter call, no
 * concatenation and nothing a reviewer would stop at. This rule makes that record impossible to
 * declare.
 *
 * <h2>Why the vocabulary is narrow rather than broad</h2>
 *
 * <p>{@code key} is not in it, and that is deliberate. An idempotency key is not a secret — {@code
 * API_CONVENTIONS.md} §6 says so explicitly, and it is recorded on the audit record on purpose. A
 * rule that flagged {@code idempotencyKey} would be a rule people turn off, and a rule that is off
 * protects nothing. The vocabulary names things that are secret in every context, and the compound
 * forms ({@code apiKey}, {@code privateKey}) rather than the bare word.
 *
 * <p>Matching is on camel-case word boundaries, so {@code companyName} is not a PAN and {@code
 * spinLock} is not a PIN. A substring match would produce exactly the false positives that get a
 * rule deleted.
 */
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoUnwrappedSecretRulesTest {

    /**
     * Field-name words that mean "this is a secret", in every context.
     *
     * <p>Compound forms where the bare word has innocent uses. Adding an entry is cheap; removing
     * one needs the same scrutiny as changing an invariant, because the removal is invisible in
     * every test that keeps passing afterwards.
     */
    private static final Set<String> SECRET_WORDS =
            Set.of(
                    "password", "passwd", "passphrase",
                    "secret", "apikey", "privatekey", "secretkey", "signingkey",
                    "credential", "credentials",
                    "token", "bearer", "authorization",
                    // Card data. PCI scope is deliberately minimised (DECISIONS.md), and these
                    // exist so that a field that would widen it fails the build rather than
                    // arriving quietly.
                    "pan", "cardnumber", "cvv", "cvc", "cvv2", "pin",
                    // Authentication data.
                    "otp", "mfacode", "sessionid");

    /** Splits camelCase, snake_case and SCREAMING_CASE into lower-case words. */
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[_\\s]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    @ArchTest
    static final ArchRule secretsAreWrapped =
            ArchRuleDefinition.noClasses()
                    .should(declareAnUnwrappedSecretField())
                    .because(
                            "INV-AUD-02 is enforced by default-deny redaction: a field whose name says it "
                                    + "holds a secret must be a Sensitive<?>, so that toString(), a record's "
                                    + "generated toString and a serialiser's fallback all render a mask "
                                    + "instead of the value");

    /**
     * The coverage guard, for the reason every rule suite here has one: a rule that sees nothing
     * passes, and reports safety it never checked.
     */
    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed = new TreeSet<>();
        imported.stream().map(ProductionModules::of).filter(Objects::nonNull).forEach(analysed::add);

        assertThat(analysed)
                .as("the rule must see every module that has production code, or it protects only some of them")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());
    }

    @Test
    @DisplayName("the rule rejects the declaration it exists to catch, and accepts the wrapped form")
    void theRuleHasTeeth() {
        // Both directions, on fixtures, so the rule is proven on every build rather than the day
        // somebody happens to write a credential record. A rule that has never rejected anything
        // is indistinguishable from a rule that cannot.
        JavaClasses fixtures =
                new ClassFileImporter()
                        .importClasses(
                                Leaky.class, Wrapped.class, Innocent.class, HiddenBehindAGetter.class);

        assertThat(violations(fixtures, Leaky.class))
                .as("a raw String field named like a secret must be rejected")
                .isNotEmpty();
        assertThat(violations(fixtures, Wrapped.class))
                .as("the same field wrapped in Sensitive must be accepted")
                .isEmpty();
        assertThat(violations(fixtures, Innocent.class))
                .as("idempotencyKey, companyName and spinLock must not be mistaken for secrets")
                .isEmpty();
        assertThat(violations(fixtures, HiddenBehindAGetter.class))
                .as("a getter is what a serialiser reads, so a field-only rule would miss this")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------
    // Fixtures. Deliberate violations, kept here so the rule is exercised every build.
    // -----------------------------------------------------------------

    /** What this rule exists to make impossible. */
    @SuppressWarnings("unused")
    private record Leaky(String username, String password) {}

    /** The hole a field-only rule leaves: the secret is behind an accessor, not a matching field. */
    @SuppressWarnings("unused")
    private static final class HiddenBehindAGetter {
        private final String pw = "";

        String getPassword() {
            return pw;
        }
    }

    /** The same information, safely. */
    @SuppressWarnings("unused")
    private record Wrapped(String username, Sensitive<String> password) {}

    /**
     * Names that contain a secret word as a substring but are not secrets. {@code idempotencyKey}
     * is the important one: it is recorded in audit on purpose.
     */
    @SuppressWarnings("unused")
    private record Innocent(String idempotencyKey, String companyName, String spinLockName) {}

    // -----------------------------------------------------------------

    private static List<String> violations(JavaClasses classes, Class<?> type) {
        ConditionEvents events = ConditionEvents.Factory.create();
        JavaClass javaClass = classes.get(type);
        declareAnUnwrappedSecretField().check(javaClass, events);
        return events.getViolating().stream().flatMap(event -> event.getDescriptionLines().stream()).toList();
    }

    private static ArchCondition<JavaClass> declareAnUnwrappedSecretField() {
        return new ArchCondition<>("declare a field whose name says it holds a secret without wrapping it") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (!namesASecret(field.getName()) || isWrapped(field.getRawType())) {
                        continue;
                    }
                    events.add(
                            SimpleConditionEvent.violated(
                                    field,
                                    field.getFullName()
                                            + " holds a secret in a "
                                            + field.getRawType().getSimpleName()
                                            + ". Wrap it in Sensitive<> so it cannot print itself (INV-AUD-02)"));
                }

                // Accessors too, and this is not belt-and-braces. A serialiser reads ACCESSORS,
                // not fields: a private `pw` behind a `getPassword()` is invisible to the check
                // above and is exactly what Jackson, a record's toString and a logging framework
                // all reach for. Record components produce an accessor of the same name, so this
                // catches them twice - which is the correct number of times.
                for (JavaMethod method : javaClass.getMethods()) {
                    if (!method.getRawParameterTypes().isEmpty()) {
                        continue;
                    }
                    if (!namesASecret(method.getName()) || isWrapped(method.getRawReturnType())) {
                        continue;
                    }
                    events.add(
                            SimpleConditionEvent.violated(
                                    method,
                                    method.getFullName()
                                            + " exposes a secret as a "
                                            + method.getRawReturnType().getSimpleName()
                                            + ". Return Sensitive<> so a serialiser cannot read it (INV-AUD-02)"));
                }
            }
        };
    }

    private static boolean isWrapped(JavaClass type) {
        return type.getName().equals(Sensitive.class.getName());
    }

    /** True when any camel-case word of {@code fieldName} is in the secret vocabulary. */
    private static boolean namesASecret(String fieldName) {
        for (String word : WORD_BOUNDARY.split(fieldName)) {
            if (SECRET_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

}
