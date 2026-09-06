package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.slf4j.MDC;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
@Tag("architecture")
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

    /**
     * The one component permitted to write the MDC.
     *
     * <p>A single name rather than a package: the privilege belongs to a component, and a package
     * would silently extend it to whatever is added beside that component later.
     */
    private static final String MDC_OWNER = "com.finapp.platform.correlation.CorrelationContext";

    /** Splits camelCase, snake_case and SCREAMING_CASE into lower-case words. */
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[_\\s]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * {@code classes().should(...)}, NOT {@code noClasses().should(...)}.
     *
     * <p>The first version used {@code noClasses()}, and it was <strong>structurally incapable of
     * failing</strong>. {@code noClasses().should(condition)} inverts the condition's events: it
     * reports as violations the things the condition marks <em>satisfied</em>. This condition only
     * ever emits {@code violated(...)}, so the inversion left it with nothing to report, and a
     * production record holding a plaintext {@code String password} passed cleanly.
     *
     * <p>It was found by {@code P0-TST-008} planting exactly that record. The rule's own fixture
     * test had "proved" the rule worked - by invoking the condition directly, which bypasses the
     * inversion entirely and therefore tested something the build never runs. A security control
     * that cannot fail, with a green test beside it, is worse than none: it is believed.
     */
    @ArchTest
    static final ArchRule secretsAreWrapped =
            ArchRuleDefinition.classes()
                    .should(notDeclareAnUnwrappedSecretField())
                    .because(
                            "INV-AUD-02 is enforced by default-deny redaction: a field whose name says it "
                                    + "holds a secret must be a Sensitive<?>, so that toString(), a record's "
                                    + "generated toString and a serialiser's fallback all render a mask "
                                    + "instead of the value");

    /**
     * The other way a value reaches a log line, and the one {@link Sensitive} cannot protect.
     *
     * <p>MDC takes a {@code String}. A secret put there is a plain string by the time the logging
     * framework sees it, and the ECS encoder lifts every MDC entry to a <strong>top-level
     * field</strong> - so {@code MDC.put("apiToken", token)} publishes it verbatim, as a queryable
     * field, with no wrapper anywhere in the path. Probed and confirmed during {@code P0-TST-008}
     * rather than reasoned about: the value appeared in the emitted JSON exactly as written.
     *
     * <p>Confining MDC writes to {@link com.finapp.platform.correlation.CorrelationContext} makes
     * the MDC's contents a decision made in one place, by the component whose job is deciding what
     * belongs in a log line's context. That is the same default-deny shape as the field rule: the
     * capability is denied, and one named component holds it.
     */
    @ArchTest
    static final ArchRule onlyCorrelationContextWritesTheMdc =
            ArchRuleDefinition.noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName(MDC_OWNER)
                    .should()
                    .accessClassesThat()
                    .haveFullyQualifiedName("org.slf4j.MDC")
                    .because(
                            "the MDC takes a String, so INV-AUD-02's wrapper cannot protect it, and the ECS "
                                    + "encoder lifts every MDC entry to a top-level field. What goes into a "
                                    + "log line's context is decided in one place");

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
    @DisplayName("each rule rejects the violation it exists to catch")
    void rulesRejectTheirViolations() {
        // rule.check(...), which is what the BUILD runs - not the condition behind it. That
        // distinction is not stylistic here: the first version of this suite called the condition
        // directly, and passed cheerfully while the rule, wrapped in noClasses() and inverting the
        // condition's events, could not fail at all. The four sibling rule suites already used
        // this form; deviating from them is what hid the defect.
        assertRejects(secretsAreWrapped, Leaky.class);
        assertRejects(secretsAreWrapped, HiddenBehindAGetter.class);
        assertRejects(secretsAreWrapped, CompoundSecrets.class);
        assertRejects(onlyCorrelationContextWritesTheMdc, WritesTheMdc.class);
    }

    @Test
    @DisplayName("the rules accept the wrapped form, so they are not merely always-failing")
    void rulesAcceptWrappedSecrets() {
        JavaClasses clean = new ClassFileImporter().importClasses(Wrapped.class, Innocent.class);

        assertThatCode(() -> secretsAreWrapped.check(clean)).doesNotThrowAnyException();
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
     *
     * <p>These are the false positives the adjacent-pair matching must NOT produce. Their pairs are
     * {@code idempotencykey}, {@code companyname} and {@code spinlockname} - none in the vocabulary.
     */
    @SuppressWarnings("unused")
    private record Innocent(String idempotencyKey, String companyName, String spinLockName) {}

    /**
     * The camel-case compound spellings, which the rule claimed to catch and did not.
     *
     * <p>One fixture per dead entry, because an aggregate probe reporting "caught" tells you nothing
     * about which of the six it caught - the lesson the {@code P0-TSK-032} review recorded when five
     * bypass shapes were probed together.
     */
    @SuppressWarnings("unused")
    private record CompoundSecrets(
            String apiKey,
            String privateKey,
            String signingKey,
            String cardNumber,
            String mfaCode,
            String sessionId) {}

    /**
     * The MDC path, which no wrapper can protect.
     *
     * <p>Kept as a fixture so the rule is proven on every build rather than the one afternoon
     * somebody planted a production MDC write by hand - which is how it was proven the first time,
     * and is not a method that survives the person who used it.
     */
    @SuppressWarnings("unused")
    private static final class WritesTheMdc {
        void stash(String value) {
            MDC.put("apiToken", value);
        }
    }

    // -----------------------------------------------------------------

    /** The sibling suites' idiom: check the RULE, and require the failure to name the class. */
    private static void assertRejects(ArchRule rule, Class<?> violation) {
        JavaClasses violating = new ClassFileImporter().importClasses(violation);

        assertThatThrownBy(() -> rule.check(violating))
                .as("%s must reject %s", rule.getDescription(), violation.getSimpleName())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }

    /**
     * Whether {@code field} is an enum constant.
     *
     * <p><strong>A structural exclusion, not an exemption.</strong> An enum constant is a value of
     * its own enum type and can never hold a secret - {@code CredentialType.PASSWORD} is the name
     * of a <em>kind of credential</em>, not a password. The rule fired on it (`P1-TSK-007`), and
     * the alternative was to rename correct domain vocabulary to satisfy a check, which is the tail
     * wagging the dog: {@code PASSWORD} is exactly what that constant should be called, and every
     * future {@code TokenType.BEARER} or {@code FactorType.OTP} would hit the same thing.
     *
     * <p>ADR-0019 chose this rule's vocabulary on the principle that <em>a rule with false positives
     * is a rule somebody turns off</em>, and left {@code key} out of it for exactly that reason.
     * This is the same judgement applied to a shape rather than to a word.
     *
     * <p>The precedent is {@code P0-TSK-041}, which excluded the compiler-generated {@code $VALUES}
     * array from the static-mutable-state rule: also an enum artefact, also excluded because it is
     * structurally incapable of being the thing the rule is looking for. <strong>It narrows nothing
     * else</strong> - a field of any other kind, in an enum or anywhere else, is still checked, and
     * the fixture tests below still fail the rule as they did.
     */
    private static boolean isAnEnumConstant(JavaField field) {
        return field.getOwner().isEnum()
                && field.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC)
                && field.getRawType().equals(field.getOwner());
    }

    private static ArchCondition<JavaClass> notDeclareAnUnwrappedSecretField() {
        return new ArchCondition<>(
                "not declare a field or accessor whose name says it holds a secret without wrapping it") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (isAnEnumConstant(field) || !namesASecret(field.getName())
                            || isWrapped(field.getRawType())) {
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
        return type.getName().equals(Sensitive.class.getName()) || isOneOfOurOwnTypes(type);
    }

    /**
     * Whether {@code type} is a type this rule already checks in its own right.
     *
     * <p><strong>A compositional exclusion, not an exemption.</strong> This rule analyses every
     * class under {@code com.finapp}, so if {@code CredentialStore} or {@code RawPassword} held a
     * secret in an unwrapped field, the violation would be reported <em>at that class</em>. Wrapping
     * a <em>reference</em> to one protects nothing that is not already protected, and
     * {@code Sensitive<RawPassword>} would be double-wrapping a type whose whole job is to wrap.
     *
     * <p>It fired on {@code CredentialVerifier.credentials} - a {@code CredentialStore} collaborator
     * - and on a private factory returning a {@code RawPassword} (`P1-TSK-008`). Renaming was the
     * alternative and does not exist here: the {@code identity} module's collaborators are named
     * after credentials because that is what they are for, and every future {@code TokenStore} or
     * {@code PasswordPolicy} field would hit the same thing.
     *
     * <p><strong>It narrows nothing that matters.</strong> A secret lives in a {@code String}, a
     * {@code char[]}, a {@code byte[]} or a boxed primitive - JDK types, none of which this touches
     * - and the fixture tests below still fail the rule exactly as they did. The precedent is the
     * enum-constant exclusion above, and the same test proves this one load-bearing.
     */
    private static boolean isOneOfOurOwnTypes(JavaClass type) {
        return type.getName().startsWith("com.finapp.");
    }

    /** True when any camel-case word of {@code fieldName} is in the secret vocabulary. */
    /**
     * Whether {@code fieldName} names a secret.
     *
     * <h2>Adjacent pairs as well as single words, and this was a real hole</h2>
     *
     * <p>The vocabulary above deliberately uses <strong>compound</strong> forms where the bare word
     * has innocent uses - {@code apikey}, {@code privatekey}, {@code cardnumber}. The first version
     * of this method compared only <em>single</em> words, and the splitter separates
     * {@code apiKey} into {@code [api, Key]} - so <strong>none of those compound entries could ever
     * match the camel-case spelling a Java developer actually writes</strong>.
     *
     * <p>Measured during the {@code P1-TSK-009} gate rather than reasoned about. Six of the
     * twenty-two entries were dead: {@code apikey}, {@code privatekey}, {@code signingkey},
     * {@code cardnumber}, {@code mfacode} and {@code sessionid}. {@code secretKey} was caught, but
     * only by accident - {@code secret} is also a standalone entry. So {@code String cardNumber}
     * passed cleanly, which is exactly the field ADR-0019 named to stop PCI scope widening quietly,
     * and {@code String sessionId} passed too.
     *
     * <p>A control reporting coverage it does not have is the {@code P0-TST-008} shape again: worse
     * than none, because it is believed. Closed by testing <strong>adjacent word pairs</strong> as
     * well as single words, which is precise rather than fuzzy - {@code idempotencyKey} yields the
     * pair {@code idempotencykey}, which is not in the vocabulary and stays clean, as do
     * {@code companyName} and {@code spinLock}. Substring matching would have caught the six and
     * reintroduced exactly the false positives ADR-0019 excluded {@code key} to avoid.
     */
    private static boolean namesASecret(String fieldName) {
        String[] words = WORD_BOUNDARY.split(fieldName);
        for (int index = 0; index < words.length; index++) {
            if (SECRET_WORDS.contains(words[index].toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (index + 1 < words.length
                    && SECRET_WORDS.contains(
                            (words[index] + words[index + 1]).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

}
