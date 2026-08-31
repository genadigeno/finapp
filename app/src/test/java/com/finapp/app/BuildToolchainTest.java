package com.finapp.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the acceptance criterion of P0-TSK-001: the Java version is pinned by
 * the Gradle toolchain rather than by the ambient {@code JAVA_HOME}.
 *
 * <p>Asserting the runtime JVM alone would be weak — it would pass on a machine
 * that merely happens to run the right JDK. Reading the class-file major version
 * of an already-compiled class asserts what the compiler actually targeted, so
 * this fails if the toolchain declaration is removed and a different JDK is used.
 */
class BuildToolchainTest {

    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    /**
     * Class-file major versions start at 45 for Java 1.1 and increment by one per release,
     * so Java N compiles to major version N + 44 (JVMS 4.1).
     */
    private static final int CLASS_FILE_MAJOR_OFFSET = 44;

    /**
     * The pinned toolchain version, injected by the build from the version catalog. Read
     * rather than hardcoded so that the expected value has exactly one definition: a second
     * copy of "21" here would silently stop matching the day the toolchain is bumped.
     */
    private static int pinnedJavaVersion() {
        String pinned = System.getProperty("finapp.java.toolchain");
        assertThat(pinned)
                .as("system property finapp.java.toolchain, injected by finapp.java-conventions")
                .isNotBlank();
        return Integer.parseInt(pinned);
    }

    @Test
    @DisplayName("compiled bytecode targets the pinned toolchain version")
    void bytecodeTargetsPinnedJavaVersion() throws IOException {
        String resource = "/" + FinappApplication.class.getName().replace('.', '/') + ".class";

        try (InputStream in = FinappApplication.class.getResourceAsStream(resource);
                DataInputStream data = new DataInputStream(requireStream(in, resource))) {

            assertThat(data.readInt())
                    .as("class file magic number")
                    .isEqualTo(CLASS_FILE_MAGIC);

            data.readUnsignedShort(); // minor version, unused
            int major = data.readUnsignedShort();

            assertThat(major)
                    .as("class-file major version of compiled production code")
                    .isEqualTo(pinnedJavaVersion() + CLASS_FILE_MAJOR_OFFSET);
        }
    }

    @Test
    @DisplayName("tests execute on the pinned toolchain JVM")
    void testsRunOnPinnedJavaVersion() {
        assertThat(Runtime.version().feature())
                .as("test JVM feature version")
                .isEqualTo(pinnedJavaVersion());
    }

    private static InputStream requireStream(InputStream in, String resource) {
        if (in == null) {
            throw new IllegalStateException("Could not read class resource: " + resource);
        }
        return in;
    }
}
