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

    /** Class-file major version for Java 21 (JVMS 4.1). */
    private static final int JAVA_21_CLASS_FILE_MAJOR = 65;

    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    @Test
    @DisplayName("compiled bytecode targets Java 21, as pinned by the Gradle toolchain")
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
                    .isEqualTo(JAVA_21_CLASS_FILE_MAJOR);
        }
    }

    @Test
    @DisplayName("tests execute on the pinned toolchain JVM")
    void testsRunOnPinnedJavaVersion() {
        assertThat(Runtime.version().feature())
                .as("test JVM feature version")
                .isEqualTo(21);
    }

    private static InputStream requireStream(InputStream in, String resource) {
        if (in == null) {
            throw new IllegalStateException("Could not read class resource: " + resource);
        }
        return in;
    }
}
