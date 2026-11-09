package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CallbackSignature;
import com.finapp.payments.WebhookSignature;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * The five callback doors' rotation windows (`P15-TSK-019`, {@code THREAT_MODEL.md} T8): each bean really passes its door's
 * window, read through the bean method a replica runs, and both keys are compared before either answers.
 */
@DisplayName("each callback door's bean passes its own window, and both keys are always compared (P15-TSK-019)")
class CallbackDoorsHoldTheirWindowTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] CURRENT = fill(0x31);
    private static final byte[] PREVIOUS = fill(0x32);
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private static byte[] fill(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }

    private static Object signatureFrom(Class<?> beans, String method, MockEnvironment environment) throws Exception {
        for (Method candidate : beans.getDeclaredMethods()) {
            if (candidate.getName().equals(method)) {
                candidate.setAccessible(true);
                Constructor<?> constructor = beans.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object[] arguments = new Object[candidate.getParameterCount()];
                for (int i = 0; i < arguments.length; i++) {
                    Class<?> type = candidate.getParameterTypes()[i];
                    if (type == String.class) {
                        arguments[i] = Base64.getEncoder().encodeToString(CURRENT);
                    } else if (type == Duration.class) {
                        arguments[i] = Duration.ofMinutes(5);
                    } else if (type == Environment.class) {
                        arguments[i] = environment;
                    } else if (type == Clock.class) {
                        arguments[i] = CLOCK;
                    } else {
                        throw new AssertionError("no argument for " + type);
                    }
                }
                return candidate.invoke(constructor.newInstance(), arguments);
            }
        }
        throw new AssertionError("no bean method " + method + " on " + beans);
    }

    private static boolean verifiesThePreviousKey(Object signature) {
        if (signature instanceof WebhookSignature webhook) {
            String timestamp = Long.toString(NOW.getEpochSecond());
            return webhook.matches(timestamp, BODY, new WebhookSignature(PREVIOUS, Duration.ofMinutes(5), CLOCK).sign(timestamp, BODY));
        }
        return ((CallbackSignature) signature).matches(BODY, new CallbackSignature(PREVIOUS).sign(BODY));
    }

    private static MockEnvironment doorEnvironment(String stem, Instant until) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/finapp")
                .withProperty(stem + ".previous.key", Base64.getEncoder().encodeToString(PREVIOUS));
        if (until != null) {
            environment.setProperty(stem + ".previous.valid-until", until.toString());
        }
        return environment;
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "com.finapp.app.kyc.KycBeans, callbackSignature, finapp.kyc.callback",
        "com.finapp.app.payments.PaymentBeans, webhookSignature, finapp.payments.webhook",
        "com.finapp.app.payments.PaymentBeans, instantWebhookSignature, finapp.payments.instant.webhook",
        "com.finapp.app.fx.FxCoverBeans, fxWebhookSignature, finapp.fx.webhook",
        "com.finapp.app.crossborder.CrossBorderOutcomeBeans, corridorWebhookSignature, finapp.corridor.webhook",
    })
    @DisplayName("the door's bean accepts its previous key inside its window, and not without one")
    void eachDoorPassesItsWindow(String beans, String method, String stem) throws Exception {
        Class<?> configuration = Class.forName(beans);

        Object open = signatureFrom(configuration, method, doorEnvironment(stem, NOW.plusSeconds(3600)));
        assertThat(verifiesThePreviousKey(open)).as("%s accepts the previous key inside the window", method).isTrue();

        Object noWindow = signatureFrom(configuration, method, doorEnvironment(stem, null));
        assertThat(verifiesThePreviousKey(noWindow)).as("%s has no window without an instant", method).isFalse();
    }

    @Test
    @DisplayName("both comparisons always run: the two answers are OR-ed, no branch between them, in both signature classes")
    void bothKeysAreAlwaysCompared() throws IOException {
        assertBitwiseCombine("com/finapp/payments/WebhookSignature.class");
        assertBitwiseCombine("com/finapp/kyc/CallbackSignature.class");
    }

    /** A {@code ||} compiles to a branch and no {@code ior}; an early return to none at all. So the {@code ior} is the pin. */
    private static void assertBitwiseCombine(String classFile) throws IOException {
        List<Integer> opcodes = new ArrayList<>();
        try (InputStream in = CallbackDoorsHoldTheirWindowTest.class.getClassLoader().getResourceAsStream(classFile)) {
            assertThat(in).as("%s is on the classpath", classFile).isNotNull();
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return !name.equals("matches") ? null : new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int opcode) {
                            opcodes.add(opcode);
                        }
                    };
                }
            }, 0);
        }
        assertThat(opcodes).as("%s combines the two comparisons with |", classFile).contains(Opcodes.IOR);
    }
}
