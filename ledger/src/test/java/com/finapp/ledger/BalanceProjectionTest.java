package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structural half of {@code INV-BAL-05} (`P3-TSK-009`, ADR-0041): <strong>no reader of
 * the projection hands a balance out.</strong> The write seam declares exactly one method and
 * it returns nothing; the first reader arrived with `P3-TSK-010` and said what it returns —
 * {@code ProjectionVerification} yields <strong>verdicts and counts, never {@link Money} or a
 * {@link DerivedBalance}</strong>, so nothing read from the projection can become a
 * decision's input. `P3-TSK-015`'s hold adjustment joined as a second <em>write</em> - the
 * availability decision derives from postings and hold rows before it runs, never from the
 * row it maintains. The display query arrived with `P3-TSK-013` (`JdbcBalanceDisplay` -
 * its own SQL over the table, never through this port; this doc had named it `P3-TSK-018`,
 * the recorded one-task plan drift), and `P3-TSK-018`'s statement reads postings, never the
 * projection - so the port still exposes no read, and this test is what fails the build on
 * one added in a hurry.
 */
@DisplayName("the balance projection hands no balance out (INV-BAL-05, P3-TSK-009/-010)")
class BalanceProjectionTest {

    @Test
    @DisplayName("the port declares exactly the two write methods, and both return nothing")
    void thePortDeclaresOnlyVoidWrites() {
        // P3-TSK-015 added the hold adjustment - a second WRITE, said so here as the class
        // doc demands: still no read exists, and a third method is a read until proven
        // otherwise.
        Method[] declared = BalanceProjection.class.getDeclaredMethods();
        assertThat(declared)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("apply", "adjustHolds");
        for (Method method : declared) {
            assertThat(method.getReturnType())
                    .as("port method %s carries nothing out", method.getName())
                    .isEqualTo(void.class);
        }
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

    @Test
    @DisplayName("the verifier - the first reader - returns verdicts and counts, never money")
    void theVerifierHandsNoBalanceOut() {
        // P3-TSK-010's read arrived and said what kind of number it returns. What it must
        // never return is the thing a decision could consume: a settled amount. Verdicts and
        // tallies cannot back a hold; a Money could.
        for (Method method : ProjectionVerification.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertThat(method.getReturnType())
                        .as("public method %s must not hand a balance out", method.getName())
                        .isNotIn(Money.class, DerivedBalance.class);
            }
        }
    }
}
