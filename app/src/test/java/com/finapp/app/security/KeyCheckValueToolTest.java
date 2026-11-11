package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.KeyRing;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tool a person computes a check value with, {@code infra/scripts/KeyCheckValue.java} (`P15-TSK-020`), held to the
 * computation every replica makes at its start, {@link KeyRing#checkValueOf}. If the two ever differed, every replica of a
 * rotation would refuse to start on a check value computed correctly by the published procedure, or, worse, a procedure fixed
 * to match would stop matching the code. The rehearsal uses the same file, so a cluster run is a second witness.
 */
@DisplayName("the check-value tool computes exactly what a replica computes, and reads the key from standard input alone (P15-TSK-020)")
class KeyCheckValueToolTest {

    private static final Path TOOL = RepositoryPaths.locate("settings.gradle.kts").getParent().resolve("infra/scripts/KeyCheckValue.java");

    private record Run(int status, String out, String err) {}

    private static Run run(String stdin) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "--source", "21", TOOL.toString()).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.US_ASCII));
        }
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the tool finished").isTrue();
        return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip(),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip());
    }

    @Test
    @DisplayName("for three keys, the tool's answer is the ring's own, byte for byte; and a non-key is refused without being echoed")
    void theToolIsTheRingsComputation() throws Exception {
        for (int seed : new int[] {0, 1, 0x5a}) {
            byte[] key = new byte[KeyRing.KEY_BYTES];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (seed == 0 ? i : seed * (i + 1));
            }
            Run answered = run(Base64.getEncoder().encodeToString(key) + "\n");
            assertThat(answered.status()).as("seed %s: %s", seed, answered.err()).isZero();
            assertThat(answered.out()).as("seed %s", seed).isEqualTo(KeyRing.checkValueOf(key));
        }
        Run refused = run("bm90LWEta2V5\n");
        assertThat(refused.status()).isEqualTo(1);
        assertThat(refused.out() + refused.err()).doesNotContain("bm90LWEta2V5").doesNotContain("not-a-key");
    }
}
