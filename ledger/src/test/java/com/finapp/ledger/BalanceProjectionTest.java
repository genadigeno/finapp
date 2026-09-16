package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structural half of {@code INV-BAL-05} (`P3-TSK-009`, ADR-0041): <strong>no decision can
 * read the projection, because no read exists.</strong> The port declares exactly one method
 * and it returns nothing, so the projection's numbers cannot leave the write path through
 * Java at all. A reader is a decision somebody must come and take — the verification job's
 * comparison (`P3-TSK-010`) and the display query (`P3-TSK-018`), each saying what kind of
 * number it returns — and until then this test is what fails the build on a read added in a
 * hurry.
 */
@DisplayName("the balance projection exposes no read (INV-BAL-05, P3-TSK-009)")
class BalanceProjectionTest {

    @Test
    @DisplayName("the port declares exactly one method, and it returns nothing")
    void thePortDeclaresExactlyOneVoidMethod() {
        Method[] declared = BalanceProjection.class.getDeclaredMethods();
        assertThat(declared)
                .as("a second method on the projection port is a read until proven otherwise")
                .hasSize(1);
        assertThat(declared[0].getName()).isEqualTo("apply");
        assertThat(declared[0].getReturnType())
                .as("the one method carries nothing out")
                .isEqualTo(void.class);
    }

    @Test
    @DisplayName("the JDBC updater carries nothing out either")
    void theUpdaterExposesNoRead() {
        for (Method method : JdbcBalanceProjection.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertThat(method.getReturnType())
                        .as("public method %s must not carry a projection value out",
                                method.getName())
                        .isEqualTo(void.class);
            }
        }
    }
}
