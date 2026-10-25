package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.testing.InMemoryRiskAssessmentStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

/**
 * Every {@link RiskSignalIntake.Source} has exactly one handler bean (`P13-TSK-005`): a source declared in the enum
 * and not composed would be an event the platform publishes and risk silently never hears - the gap this holds
 * closed without a Spring context, by invoking the configuration's bean methods with the composition's own
 * collaborators.
 */
@DisplayName("RiskBeans composes one intake per source (P13-TSK-005)")
class RiskBeansTest {

    @Test
    @DisplayName("one InboxEventHandler bean per source, each the source it names, none twice")
    void oneHandlerBeanPerSource() throws Exception {
        RiskBeans beans = new RiskBeans();
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
        IdGenerator ids = new IdGenerator(clock, new SecureRandom());
        RiskSignals signals = new RiskSignals(new RiskSignalIntakeTest.RecordingStore(),
                new RiskSignalIntakeTest.RecordingOutbox(), ids, clock);
        // Each bean's collaborators by type - the attributed sources (P13-TSK-014) take risk's assessment store too.
        Map<Class<?>, Object> collaborators = Map.of(RiskSignals.class, signals, MonitoringHints.class,
                MonitoringHints.NONE, IdGenerator.class, ids, RiskFlowScope.class, RiskFlowScope.NONE,
                RiskAssessmentStore.class, new InMemoryRiskAssessmentStore());
        List<RiskSignalIntake.Source> composed = new ArrayList<>();
        for (Method method : RiskBeans.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class) && InboxEventHandler.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                Object[] arguments = Arrays.stream(method.getParameterTypes())
                        .map(type -> java.util.Objects.requireNonNull(collaborators.get(type), type.getName()))
                        .toArray();
                RiskSignalIntake intake = (RiskSignalIntake) method.invoke(beans, arguments);
                composed.add(intake.source());
                assertThat(method.getParameterCount()).as("%s takes the assessment store only if attributed",
                        intake.source()).isEqualTo(intake.source().isAttributed() ? 5 : 4);
            }
        }
        assertThat(composed).containsExactlyInAnyOrder(RiskSignalIntake.Source.values());
        assertThat(Arrays.stream(RiskSignalIntake.Source.values()).map(RiskSignalIntake.Source::eventType).distinct())
                .as("one event type per source")
                .hasSize(RiskSignalIntake.Source.values().length);
    }
}
