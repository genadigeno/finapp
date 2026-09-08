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
import com.tngtech.archunit.junit.ArchTag;
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
// NOT a duplicate of the line above. ArchUnit runs @ArchTest fields under its OWN
// JUnit Platform engine, and that engine's descriptors read `ArchTag` - they cannot
// see JUnit's `Tag` at all. Without this every rule below carried no tag, so
// `unitTest` (which selects by EXCLUSION) took them and `architectureTest` (which
// selects by INCLUSION) got none. P1-TSK-025; TestTaxonomyTest now requires the pair.
@ArchTag("architecture")
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
    /**
     * A primitive cannot hold a secret, so a primitive field is not one.
     *
     * <p>Added by {@code P1-TSK-017}, where {@code SECRET_BYTES} - the <em>length</em> of a
     * generated secret, an {@code int} - was flagged. A secret is a value with content; an
     * {@code int} has 32 bits of it and no way to be a base32 string, a derivation or a token.
     *
     * <p>The same shape as {@link #isAnEnumConstant} and the {@code P0-TSK-041} precedent it cites:
     * <strong>structurally incapable</strong> of being the thing the rule looks for, rather than a
     * judgement about a particular name. It narrows nothing else - a {@code String}, an array or any
     * reference type keeps being checked, and the fixtures below still fail the rule.
     *
     * <p>ADR-0019's principle applies directly: a rule with false positives is a rule somebody turns
     * off, and {@code secretBytes} / {@code tokenLength} / {@code otpDigits} are all names a
     * reasonable person writes for a number.
     */
    /**
     * Fields that must be serialised despite naming a secret.
     *
     * <p><strong>The rule's first exemption, and it is deliberately one entry.</strong> ADR-0019
     * built this rule with no exemption set at all, on the principle that a rule with escape hatches
     * is a rule that grows them. This is added because a case arrived that the rule cannot express
     * rather than one it merely inconveniences.
     *
     * <p>{@code ElevatedSession.sessionToken} carries the session a step-up produced. Elevation
     * <strong>rotates</strong> the identifier ({@code P1-TSK-015}), so a response that withheld the
     * replacement would log the customer out at the moment they proved a second factor — and
     * wrapping is not available either, because the platform's serialiser renders {@code Sensitive}
     * as a mask ({@code P0-TSK-030}) and the client would receive «redacted».
     *
     * <p><strong>The exemption permits serialisation and nothing else.</strong> The other harm the
     * rule guards — a record's generated {@code toString} printing a live token into a log — is
     * closed by an override, and {@code ElevatedSessionTest} asserts it. An exemption that permitted
     * both would be a hole rather than a decision.
     *
     * <p>Renaming the field to slip past the vocabulary was the alternative and was refused: it is
     * the option {@code P1-TSK-017} declined for {@code sharedSecret}, and it is no more honest for
     * being easier.
     */
    private static final Set<String> PERMITTED_FIELDS =
            Set.of(
                    "com.finapp.app.mfa.ElevatedSession.sessionToken",
                    // `P1-TSK-027`, and the same claim rather than a new one: a session token
                    // exists to be transmitted, and this platform's serialiser renders a wrapped
                    // value as a mask - so wrapping would hand the client «redacted» for the one
                    // field the response is for.
                    //
                    // The second exemption on this rule, and the reason it is not a slope: it is
                    // the SAME case as the first, arriving at a second endpoint because
                    // authentication now issues a session as well. A third entry for anything that
                    // is not a session token would be a new decision and should be argued as one.
                    //
                    // The toString harm is closed by an override, and AuthenticatedSessionTest
                    // asserts it - written WITH this exemption rather than after somebody noticed
                    // it was unbacked, which is what happened to the first one (P1-TSK-018's gate).
                    "com.finapp.app.authentication.AuthenticatedSession.sessionToken");

    /** The accessors of {@link #PERMITTED_FIELDS}, for the same reason and no other. */
    private static final Set<String> PERMITTED_ACCESSORS =
            Set.of(
                    "com.finapp.app.mfa.ElevatedSession.sessionToken()",
                    "com.finapp.app.authentication.AuthenticatedSession.sessionToken()");

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("every exemption still names a field the rule would otherwise flag")
    void theExemptionsAreNotStale() {
        // An exemption naming a field that has been renamed or deleted is one nobody can evaluate,
        // and it silently stops applying to anything - the P1-TSK-015 rule, and P0-TSK-041's
        // requirement that an exemption be proven load-bearing rather than assumed.
        //
        // It asserts the field is one the rule WOULD flag, not merely that it exists: an entry for a
        // field the vocabulary no longer matches is dead weight that reads as a live decision.
        java.util.List<String> wouldBeFlagged = new java.util.ArrayList<>();
        JavaClasses production =
                new ClassFileImporter()
                        .withImportOption(
                                com.tngtech.archunit.core.importer.ImportOption.Predefined
                                        .DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.finapp");
        for (JavaClass javaClass : production) {
            for (JavaField field : javaClass.getFields()) {
                if (!isAnEnumConstant(field)
                        && !isAPrimitive(field)
                        && !isAnOptionalOfOurOwnType(field)
                        && namesASecret(field.getName())
                        && !isWrapped(field.getRawType())) {
                    wouldBeFlagged.add(field.getFullName());
                }
            }
        }

        assertThat(wouldBeFlagged)
                .as("an exemption that names nothing the rule objects to has stopped applying")
                .containsAll(PERMITTED_FIELDS);
    }

    private static boolean isPermitted(JavaField field) {
        return PERMITTED_FIELDS.contains(field.getFullName());
    }

    private static boolean isAPrimitive(JavaField field) {
        return field.getRawType().isPrimitive() || isAnAmountOfTime(field.getRawType());
    }

    /**
     * A duration or an instant cannot hold a secret, so a field of one is not one.
     *
     * <p>Added by {@code P1-TSK-023}, where {@code RecoveryService.TOKEN_LIFETIME} — a
     * {@link java.time.Duration} naming how long a recovery token lives — was flagged. The same
     * structural argument as {@link #isAPrimitive}'s own: a {@code Duration} is a count of seconds
     * and nanoseconds, with no way to be a base64 string, a derivation or a token.
     *
     * <p>And the names that hit it are the ones anybody would write: {@code TOKEN_LIFETIME},
     * {@code SESSION_IDLE_TIMEOUT}, {@code CREDENTIAL_MAX_AGE}. ADR-0019's principle applies
     * directly — a rule with false positives is a rule somebody turns off.
     */
    private static boolean isAnAmountOfTime(JavaClass type) {
        return type.getName().startsWith("java.time.");
    }

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
                    if (isAnEnumConstant(field)
                            || isAPrimitive(field)
                            || isAnOptionalOfOurOwnType(field)
                            || isPermitted(field)
                            || !namesASecret(field.getName())
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
                    // The accessor is exempt for the same reason the field is: it exists so a
                    // serialiser can read it. Exempting the field alone would be incoherent - the
                    // serialiser reads THIS, so a field-only exemption would permit the storage
                    // while forbidding the purpose.
                    if (PERMITTED_ACCESSORS.contains(method.getFullName())
                            || !namesASecret(method.getName())
                            || isAnAmountOfTime(method.getRawReturnType())
                            || returnsAnOptionalOfOurOwnType(method)
                            || isWrapped(method.getRawReturnType())) {
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

    /**
     * Whether a field's declared type is an {@code Optional} of one of our own types.
     *
     * <p>A gap in {@link #isOneOfOurOwnTypes} that new code found: it reads the <strong>raw</strong>
     * type, and the raw type of {@code Optional<CredentialId>} is {@code java.util.Optional}, which
     * is not ours — so {@code RecoveryRequest.credentialId} was flagged although {@code CredentialId}
     * is checked at its own declaration exactly as an unwrapped one would be.
     *
     * <p><strong>Deliberately only one level, and only for our types.</strong>
     * {@code Optional<String> password} still fails, because {@code String} is where a secret
     * actually lives — which is the same boundary {@link #isOneOfOurOwnTypes} draws and the reason
     * that exclusion narrows nothing that matters.
     */
    /**
     * The accessor half of {@link #isAnOptionalOfOurOwnType}.
     *
     * <p>Both halves are needed, and the rule's own javadoc says why: a record produces an accessor
     * of the same name, so the field and the accessor are checked separately and an exclusion
     * applied to one alone would be incoherent. {@code RecoveryRequest.credentialId} passed as a
     * field and failed as an accessor until this existed — the guard being precise about which of
     * the two it was objecting to.
     */
    private static boolean returnsAnOptionalOfOurOwnType(JavaMethod method) {
        return method.getRawReturnType().getName().equals("java.util.Optional")
                && method.getReturnType()
                                instanceof
                                com.tngtech.archunit.core.domain.JavaParameterizedType parameterized
                && parameterized.getActualTypeArguments().size() == 1
                && parameterized
                        .getActualTypeArguments()
                        .get(0)
                        .toErasure()
                        .getName()
                        .startsWith("com.finapp.");
    }

    private static boolean isAnOptionalOfOurOwnType(JavaField field) {
        if (!field.getRawType().getName().equals("java.util.Optional")) {
            return false;
        }
        return field.getType().toErasure().getName().equals("java.util.Optional")
                && field.getType() instanceof com.tngtech.archunit.core.domain.JavaParameterizedType parameterized
                && parameterized.getActualTypeArguments().size() == 1
                && parameterized.getActualTypeArguments().get(0).toErasure().getName()
                        .startsWith("com.finapp.");
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
