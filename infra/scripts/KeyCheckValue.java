import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A field-encryption key's check value (P15-TSK-020, THREAT_MODEL.md section 6 item 32 (a); OPERATIONS_RUNBOOK.md sections 11.7 and
 * 11.29): what a person publishes in a ring's {@code ring.check-values} beside the version that value stands for, so that a replica
 * whose stored value for that version differs refuses to start.
 *
 * <p>Usage: {@code ... | java --source 21 infra/scripts/KeyCheckValue.java}. The key arrives base64 on standard input, exactly as the
 * store holds it, and never as an argument, where a process list or a shell's history would keep it. Prints the check value alone:
 * HMAC-SHA256 of the fixed label under the key, its first eight bytes in lower-case hexadecimal. {@code KeyCheckValueToolTest}
 * holds this file to {@code com.finapp.platform.security.KeyRing#checkValueOf}, the computation every replica makes; the two must
 * never differ, or every replica of a rotation refuses to start.
 *
 * <p>Exit status: 0 printed; 1 the input is not a base64 key of exactly 32 bytes (nothing of it is echoed).
 */
public final class KeyCheckValue {

    static final String LABEL = "finapp key ring check value v1";
    static final int BYTES = 8;

    private KeyCheckValue() {}

    public static void main(String[] args) throws Exception {
        String read = new String(System.in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        byte[] key;
        try {
            key = Base64.getDecoder().decode(read);
        } catch (IllegalArgumentException notBase64) {
            System.err.println("the input is not base64");
            System.exit(1);
            return;
        }
        if (key.length != 32) {
            System.err.println("the input is not a 32-byte key");
            System.exit(1);
            return;
        }
        System.out.println(checkValueOf(key));
    }

    static String checkValueOf(byte[] key) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(LABEL.getBytes(StandardCharsets.US_ASCII)), 0, BYTES);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IOException("HmacSHA256 is not available", unavailable);
        }
    }
}
