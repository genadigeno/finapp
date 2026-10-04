package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.kyc.CallbackKey;
import com.finapp.app.kyc.DocumentKey;
import com.finapp.app.merchant.PayoutEvidenceKey;
import com.finapp.app.merchant.PayoutProviderKey;
import com.finapp.app.mfa.MfaKey;
import com.finapp.app.payments.DisputeEvidenceKey;
import com.finapp.app.payments.InstantSchemeKey;
import com.finapp.app.payments.InstantWebhookKey;
import com.finapp.app.payments.PaymentEvidenceKey;
import com.finapp.app.payments.PaymentWebhookKey;
import com.finapp.app.payments.ProviderApiKey;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Every confined credential's refusal names a variable the configuration actually reads
 * (`P6-DOC-001`).
 *
 * <p>A {@link KeySpec} names its environment variable so that "the fix needs no
 * documentation" - the refusal tells an operator what to set. The Phase 6 review found five of
 * the eight naming a variable nothing binds: the payout keys said {@code FINAPP_PAYOUT_*} where
 * the configuration reads {@code finapp.merchant.payout.*}, and the payment keys
 * {@code FINAPP_PAYMENT_*} where it reads {@code finapp.payments.*}. Setting the named variable
 * changed nothing, so a deployment off loopback refused to start and its own message pointed at
 * the wrong fix. It failed closed, which is why nothing noticed.
 *
 * <p>The pairing is pinned here and checked both ways: each spec's variable is the property's
 * relaxed-binding form, the property is really read by an {@code @Value}, and every class that
 * declares a spec is in the table - so a ninth credential cannot arrive unpaired.
 */
@DisplayName("every confined credential names the variable its configuration reads")
class ConfinedCredentialVariablesTest {

    /** The property each credential's bean reads, by the class that declares its spec. */
    private static final Map<Class<?>, String> PROPERTY_READ =
            // Map.ofEntries since the eleventh credential (P7-TSK-014): Map.of stops at ten.
            Map.ofEntries(
                    Map.entry(MfaKey.class, "finapp.mfa.key"),
                    Map.entry(DocumentKey.class, "finapp.doc.key"),
                    Map.entry(CallbackKey.class, "finapp.kyc.callback.key"),
                    Map.entry(PaymentEvidenceKey.class, "finapp.payments.evidence.key"),
                    Map.entry(PaymentWebhookKey.class, "finapp.payments.webhook.key"),
                    Map.entry(ProviderApiKey.class, "finapp.payments.provider.key"),
                    Map.entry(InstantSchemeKey.class, "finapp.payments.instant.key"),
                    Map.entry(InstantWebhookKey.class, "finapp.payments.instant.webhook.key"),
                    Map.entry(PayoutEvidenceKey.class, "finapp.merchant.payout.evidence.key"),
                    Map.entry(PayoutProviderKey.class, "finapp.merchant.payout.provider.key"),
                    // P7-TSK-014: dispute evidence, its own key (one key per concern).
                    Map.entry(DisputeEvidenceKey.class, "finapp.payments.dispute.evidence.key"),
                    // The twelfth (P8-TSK-002): the settlement-file key, one key per concern.
                    Map.entry(
                            com.finapp.app.settlement.SettlementFileKey.class,
                            "finapp.settlement.file.key"),
                    // The thirteenth to sixteenth (P8-TSK-021): one pull credential per
                    // source - report access is not the money-moving API.
                    Map.entry(
                            com.finapp.app.settlement.PspReportKey.class,
                            "finapp.settlement.psp.report.key"),
                    Map.entry(
                            com.finapp.app.settlement.SchemeReportKey.class,
                            "finapp.settlement.scheme.report.key"),
                    Map.entry(
                            com.finapp.app.settlement.PayoutReportKey.class,
                            "finapp.settlement.payout.report.key"),
                    Map.entry(
                            com.finapp.app.settlement.BankStatementKey.class,
                            "finapp.settlement.bank.statement.key"),
                    // The seventeenth (P9-TSK-005): the independent reference rate - a
                    // different party than the FX provider, so never that provider's key.
                    Map.entry(
                            com.finapp.app.fx.ReferenceRateKey.class,
                            "finapp.fx.reference.key"),
                    // The eighteenth and nineteenth (P9-TSK-006): the FX provider's money-moving
                    // API key, and its evidence encryption key - one key per concern.
                    Map.entry(com.finapp.app.fx.FxProviderKey.class, "finapp.fx.provider.key"),
                    Map.entry(com.finapp.app.fx.FxEvidenceKey.class, "finapp.fx.evidence.key"));

    @Test
    @DisplayName("the variable a refusal names is the relaxed-binding form of the property read")
    void theNamedVariableIsTheOneThatBinds() throws Exception {
        Map<String, String> mismatched = new TreeMap<>();
        for (Map.Entry<Class<?>, String> credential : PROPERTY_READ.entrySet()) {
            String named = specOf(credential.getKey()).environmentVariable();
            String binds = environmentFormOf(credential.getValue());
            if (!named.equals(binds)) {
                mismatched.put(
                        credential.getKey().getSimpleName(), named + " (binds: " + binds + ")");
            }
        }
        assertThat(mismatched)
                .as("setting the variable a refusal names must change what the application reads")
                .isEmpty();
    }

    @Test
    @DisplayName("each pinned property is really read by an @Value in the application")
    void eachPinnedPropertyIsRead() {
        List<String> expressions = valueExpressions();
        assertThat(expressions).as("the sweep saw the application's @Value sites").isNotEmpty();
        TreeSet<String> unread = new TreeSet<>();
        for (String property : PROPERTY_READ.values()) {
            if (expressions.stream()
                    .noneMatch(
                            expression ->
                                    expression.startsWith("${" + property + ":")
                                            || expression.equals("${" + property + "}"))) {
                unread.add(property);
            }
        }
        assertThat(unread)
                .as("a pinned property nothing reads would make the pairing above vacuous")
                .isEmpty();
    }

    @Test
    @DisplayName("every class that declares a KeySpec is in the table")
    void everyCredentialIsPaired() {
        TreeSet<String> declaring = new TreeSet<>();
        for (Class<?> type : applicationClasses()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == KeySpec.class) {
                    declaring.add(type.getName());
                }
            }
        }
        TreeSet<String> pinned = new TreeSet<>();
        PROPERTY_READ.keySet().forEach(type -> pinned.add(type.getName()));
        assertThat(declaring).as("a new credential is paired here before it ships").isEqualTo(pinned);
    }

    // -----------------------------------------------------------------

    private static KeySpec specOf(Class<?> type) throws ReflectiveOperationException {
        Field field = type.getDeclaredField("SPEC");
        field.setAccessible(true);
        return (KeySpec) field.get(null);
    }

    /** Spring Boot's canonical mapping: dots to underscores, dashes dropped, upper case. */
    private static String environmentFormOf(String property) {
        return property.replace("-", "").replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /** Every {@code @Value} expression on a field, constructor or method parameter in {@code app}. */
    private static List<String> valueExpressions() {
        List<String> expressions = new ArrayList<>();
        for (Class<?> type : applicationClasses()) {
            for (Field field : type.getDeclaredFields()) {
                Value value = field.getAnnotation(Value.class);
                if (value != null) {
                    expressions.add(value.value());
                }
            }
            List<Executable> executables = new ArrayList<>(List.of(type.getDeclaredMethods()));
            executables.addAll(List.of(type.getDeclaredConstructors()));
            for (Executable executable : executables) {
                for (Annotation[] parameter : executable.getParameterAnnotations()) {
                    for (Annotation annotation : parameter) {
                        if (annotation instanceof Value value) {
                            expressions.add(value.value());
                        }
                    }
                }
            }
        }
        return expressions;
    }

    /**
     * The application's PRODUCTION classes - those sharing {@link ConfinedCredential}'s code
     * source, so a test's own example spec is not mistaken for a credential - loaded and never
     * initialised, because a test class's static initialiser may start a container.
     */
    private static List<Class<?>> applicationClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        Object production =
                ConfinedCredential.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader loader = ConfinedCredentialVariablesTest.class.getClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.finapp.app")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName(), false, loader);
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException(missing);
            }
            if (production.equals(type.getProtectionDomain().getCodeSource().getLocation())) {
                classes.add(type);
            }
        }
        return classes;
    }
}
