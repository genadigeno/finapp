package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical JSON writer's bytes, pinned by fixed vectors (`P14-TSK-008`; ADR-0115 decision 2; {@code INV-ACC-04},
 * {@code INV-MON-01}): the text and the SHA-256 below were computed once, outside this code, and are constants - so the
 * same content hashes the same on every JVM, locale and time zone this suite ever runs on, and a change to the form fails
 * here before it can make a FINAL run regenerate {@code DIVERGED}. Every vector's hash is also the one
 * {@code accounting V004}'s {@code report_run_content_hashed} recomputes ({@code encode(sha256(convert_to(content,
 * 'UTF8')), 'hex')} - the UTF-8 bytes' SHA-256 in lowercase hex).
 */
@DisplayName("the canonical JSON writer (P14-TSK-008)")
class CanonicalJsonTest {

    /** Vector 1 - every value kind, inserted out of order. */
    private static final String V1 = "{\"Beta\":\"upper\",\"alpha\":\"first\",\"amount\":{\"currency\":\"EUR\","
            + "\"minor\":\"-12345\",\"scale\":\"2\"},\"count\":\"42\",\"flag\":true,\"list\":[\"b\",\"a\",\"7\",null,false],"
            + "\"nested\":{\"x\":\"1\",\"y\":\"2\"},\"none\":null,\"zeta\":\"last\"}";
    private static final String V1_SHA256 = "bb14ad5818d7971d08fe1ad7ac7dfce9586c8004d9265b88e53bc65f7785732d";

    /** Vector 2 - escapes, a control character, a solidus left alone, non-ASCII and a supplementary character. */
    private static final String V2 = "{\"text\":\"quote\\\" backslash\\\\ nl\\n tab\\t ctl\\u0001 slash/ euro€ emoji"
            + "😀\",\"é\":\"accent key\"}";
    private static final String V2_SHA256 = "3882f9bc8e729ce7fc16c81eb14f809223e065f0d63384cd56e05c26d0131f4e";

    /** Vector 3 - keys in UTF-16 code-unit order (RFC 8785): a surrogate pair sorts before U+FFFD. */
    private static final String V3 = "{\"😀\":\"emoji\",\"�\":\"replacement\"}";
    private static final String V3_SHA256 = "abee91c75c6e64cb855f0aec3e76e49f2980b4188c647c48536db583de636fae";

    /** Vector 4 - the empty object; its hash is SHA-256's well-known digest of {@code {}}. */
    private static final String V4_SHA256 = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    @Test
    @DisplayName("vector 1: keys sorted whatever the insertion order, every scalar a string or a literal, the amount as"
            + " minor units in a string beside its currency and scale - the bytes and the hash fixed")
    void vectorOneEveryKind() {
        CanonicalJson.Obj value = CanonicalJson.object()
                .put("zeta", "last")
                .put("alpha", "first")
                .put("Beta", "upper")
                .put("count", 42L)
                .put("flag", true)
                .putNull("none")
                .putMoney("amount", -12345, "EUR", 2)
                .put("list", CanonicalJson.array().add("b").add("a").add(7).addNull().add(false))
                .put("nested", CanonicalJson.object().put("y", "2").put("x", "1"));
        assertThat(CanonicalJson.text(value)).isEqualTo(V1);
        assertThat(CanonicalJson.bytes(value)).hasSize(199).isEqualTo(V1.getBytes(StandardCharsets.UTF_8));
        assertThat(CanonicalJson.sha256(CanonicalJson.text(value))).isEqualTo(V1_SHA256);
    }

    @Test
    @DisplayName("vector 2: quote, backslash, the one-letter escapes and a control character escaped; the solidus,"
            + " non-ASCII and a supplementary character literal")
    void vectorTwoEscapes() {
        CanonicalJson.Obj value = CanonicalJson.object()
                .put("é", "accent key")
                .put("text", "quote\" backslash\\ nl\n tab\t ctl\u0001 slash/ euro€ emoji😀");
        assertThat(CanonicalJson.text(value)).isEqualTo(V2);
        assertThat(CanonicalJson.bytes(value)).hasSize(94);
        assertThat(CanonicalJson.sha256(CanonicalJson.text(value))).isEqualTo(V2_SHA256);
    }

    @Test
    @DisplayName("vector 3: keys compared by UTF-16 code units - the form RFC 8785 fixes - not by code point")
    void vectorThreeKeyOrder() {
        CanonicalJson.Obj value = CanonicalJson.object().put("�", "replacement").put("😀", "emoji");
        assertThat(CanonicalJson.text(value)).isEqualTo(V3);
        assertThat(CanonicalJson.sha256(CanonicalJson.text(value))).isEqualTo(V3_SHA256);
    }

    @Test
    @DisplayName("vector 4: the empty object, and SHA-256's well-known digest of it - the digest is the platform's")
    void vectorFourEmpty() {
        assertThat(CanonicalJson.text(CanonicalJson.object())).isEqualTo("{}");
        assertThat(CanonicalJson.sha256("{}")).isEqualTo(V4_SHA256);
    }

    @Test
    @DisplayName("the same content built twice in different orders is the same bytes - the regeneration's premise")
    void insertionOrderNeverShows() {
        CanonicalJson.Obj one = CanonicalJson.object().put("b", 2).put("a", 1).put("c", CanonicalJson.object()
                .put("z", "z").put("y", "y"));
        CanonicalJson.Obj two = CanonicalJson.object().put("c", CanonicalJson.object().put("y", "y").put("z", "z"))
                .put("a", 1).put("b", 2);
        assertThat(CanonicalJson.text(one)).isEqualTo(CanonicalJson.text(two)).isEqualTo(
                "{\"a\":\"1\",\"b\":\"2\",\"c\":{\"y\":\"y\",\"z\":\"z\"}}");
    }

    @Test
    @DisplayName("refused, not escaped: a key held twice, U+0000, an unpaired surrogate, a malformed amount")
    void refusals() {
        assertThatThrownBy(() -> CanonicalJson.object().put("a", "1").put("a", "2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("each key once");
        assertThatThrownBy(() -> CanonicalJson.object().put("a", "nul\u0000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("U+0000");
        assertThatThrownBy(() -> CanonicalJson.object().put("a", "\uD83D"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unpaired surrogate");
        assertThatThrownBy(() -> CanonicalJson.object().put("\uDE00", "low"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unpaired surrogate");
        assertThatThrownBy(() -> CanonicalJson.object().putMoney("a", 1, "eur", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.object().putMoney("a", 1, "EUR", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no floating point can enter: no writer method takes a float, a double or a BigDecimal (INV-MON-01)")
    void noFloatingPointWriter() {
        for (Class<?> type : new Class<?>[] {CanonicalJson.class, CanonicalJson.Obj.class, CanonicalJson.Arr.class}) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertThat(Arrays.stream(method.getParameterTypes())
                        .noneMatch(parameter -> parameter == double.class || parameter == float.class
                                || parameter == Double.class || parameter == Float.class
                                || parameter == java.math.BigDecimal.class))
                        .as("%s.%s takes no fraction", type.getSimpleName(), method.getName()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the parameters' canonical text and hash are fixed, whatever the order they were given in; the rate set"
            + " an explicit null when absent")
    void theParametersAreCanonical() {
        RunParameters given = RunParameters.of(Map.of("probe", "x", "mode", "a"), Optional.empty());
        assertThat(given.canonical()).isEqualTo("{\"valuationRateSet\":null,\"values\":{\"mode\":\"a\",\"probe\":\"x\"}}");
        assertThat(given.hash()).isEqualTo("c76ee9b66d132cdd552ac13c520cd519c4417058c5fdf73d37198a34bc0fb0b6");
        UUID set = UUID.fromString("0190f0c4-1d2e-7a00-8000-000000000001");
        assertThat(RunParameters.of(Map.of(), Optional.of(set)).canonical())
                .isEqualTo("{\"valuationRateSet\":\"" + set + "\",\"values\":{}}");
        assertThat(RunParameters.NONE.canonical()).isEqualTo("{\"valuationRateSet\":null,\"values\":{}}");
    }

    @Test
    @DisplayName("the parameters are bounded and screened: a malformed name, a value out of its alphabet, a card number,"
            + " too many")
    void theParametersAreScreened() {
        assertThatThrownBy(() -> RunParameters.of(Map.of("Probe", "x"), Optional.empty()))
                .isInstanceOf(RunParameters.Invalid.class);
        assertThatThrownBy(() -> RunParameters.of(Map.of("probe", "x\"y"), Optional.empty()))
                .isInstanceOf(RunParameters.Invalid.class);
        assertThatThrownBy(() -> RunParameters.of(Map.of("probe", "4111 1111 1111 1111"), Optional.empty()))
                .isInstanceOf(RunParameters.Invalid.class).hasMessageContaining("card-number");
        Map<String, String> many = new java.util.HashMap<>();
        for (int i = 0; i <= RunParameters.MAX_VALUES; i++) {
            many.put("p" + i, "v");
        }
        assertThatThrownBy(() -> RunParameters.of(many, Optional.empty())).isInstanceOf(RunParameters.Invalid.class);
        assertThat(RunParameters.of(Map.of("probe", UUID.randomUUID().toString()), Optional.empty()).values())
                .containsKey("probe");
    }
}
