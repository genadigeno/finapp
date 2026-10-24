package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CrossBorderMovements;
import com.finapp.app.crossborder.CrossBorderPaymentBeans;
import com.finapp.crossborder.CrossBorderLimitCheck;
import com.finapp.crossborder.CrossBorderRiskDecision;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

/**
 * The cross-border seams are one control, wired together (`P13-TSK-011`): their refusals are disjoint - crossborder
 * consults the risk seam first, which permits a limit-decided refusal for the limit seam to refuse - so wiring one
 * without the other would let a refusal through. Both are declared by {@link RiskCrossBorderBeans} and nowhere else in
 * the composition the authorization is built in; crossborder's permit-all pair is wired by no bean method.
 */
@DisplayName("the cross-border seams are wired together, by RiskCrossBorderBeans alone (P13-TSK-011)")
class RiskCrossBorderBeansTest {

    @Test
    @DisplayName("RiskCrossBorderBeans declares exactly one risk seam and one limit seam; CrossBorderPaymentBeans neither")
    void theSeamsAreDeclaredTogetherOnce() {
        assertThat(beansOf(RiskCrossBorderBeans.class, CrossBorderRiskDecision.class)).hasSize(1);
        assertThat(beansOf(RiskCrossBorderBeans.class, CrossBorderLimitCheck.class)).hasSize(1);
        assertThat(beansOf(CrossBorderPaymentBeans.class, CrossBorderRiskDecision.class))
                .as("the permit-all risk seam is wired nowhere").isEmpty();
        assertThat(beansOf(CrossBorderPaymentBeans.class, CrossBorderLimitCheck.class))
                .as("the permit-all limit seam is wired nowhere").isEmpty();
    }

    @Test
    @DisplayName("the declared seams are risk's adapters over one evaluation and the movement's facts")
    void theDeclaredSeamsAreRisks() {
        RiskEvaluationWorld world = new RiskEvaluationWorld();
        CrossBorderMovements movements = new CrossBorderMovements() {
            @Override
            public Movement locked(java.sql.Connection unitOfWork, com.finapp.crossborder.CrossBorderInstruction instruction) {
                throw new AssertionError("not judged here");
            }

            @Override
            public java.util.Optional<Movement> owned(java.sql.Connection unitOfWork, java.util.UUID party,
                    java.util.UUID quote) {
                throw new AssertionError("not advised here");
            }
        };
        RiskCrossBorderBeans beans = new RiskCrossBorderBeans();
        assertThat(beans.crossBorderRiskDecision(world.evaluation, movements)).isInstanceOf(RiskCrossBorderRiskDecision.class);
        assertThat(beans.crossBorderLimitCheck(world.evaluation, movements)).isInstanceOf(RiskCrossBorderLimitCheck.class);
        assertThat(beans.crossBorderStepUpAdvice(world.evaluation, world.assessmentStore))
                .isInstanceOf(RiskCrossBorderStepUpAdvice.class);
    }

    private static List<Method> beansOf(Class<?> configuration, Class<?> type) {
        return Arrays.stream(configuration.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> type.isAssignableFrom(method.getReturnType()))
                .toList();
    }
}
