package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every operator and merchant door of Phases 7 to 10 records its refused and failed acts (`P15-TSK-039`,
 * {@code THREAT_MODEL.md} T38).
 *
 * <p>The doors are read from the controllers, never listed here. A door is a handler that declares a permission or a
 * merchant key, in a package those four phases built, or under a permission of theirs that lives elsewhere. A new one
 * fails this until it declares {@link RecordsRefusals}, which is the only thing that switches the record on, so
 * forgetting it is not a door that quietly records nothing.
 *
 * <p>{@code RefusedActsTest} holds what the declaration does at each kind of refusal.
 */
@Tag("architecture")
@DisplayName("every operator and merchant door of Phases 7 to 10 records its refused and failed acts (P15-TSK-039)")
class RefusedActsAreRecordedTest {

    /** Payments and disputes (7), settlement and reconciliation (8), FX and cross-border (9), credit (10). */
    private static final Set<String> PHASE_PACKAGES = Set.of("com.finapp.app.payments", "com.finapp.app.settlement",
            "com.finapp.app.reconciliation", "com.finapp.app.fx", "com.finapp.app.crossborder", "com.finapp.app.credit");

    /** A door of those phases in a package an earlier phase built: the counterparty screening desk (Phase 9) in kyc. */
    private static final Set<PermissionName> PHASE_PERMISSIONS_ELSEWHERE =
            EnumSet.of(PermissionName.COUNTERPARTY_SCREENING_REVIEW);

    private static final int FEWEST_DOORS = 90;

    private static final List<Class<?>> CONTROLLERS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finapp.app")
            .stream()
            .filter(javaClass -> javaClass.isAnnotatedWith(RestController.class))
            .map(JavaClass::reflect)
            .collect(Collectors.toList());

    private record Door(Class<?> controller, Method handler) {

        boolean inScope() {
            if (PHASE_PACKAGES.contains(controller.getPackageName())) {
                return true;
            }
            RequiresPermission declared = declared(RequiresPermission.class);
            return declared != null && PHASE_PERMISSIONS_ELSEWHERE.contains(declared.value());
        }

        boolean recordsRefusals() {
            return declared(RecordsRefusals.class) != null;
        }

        String name() {
            return controller.getSimpleName() + "#" + handler.getName();
        }

        private <A extends java.lang.annotation.Annotation> A declared(Class<A> annotation) {
            A onMethod = handler.getAnnotation(annotation);
            return onMethod != null ? onMethod : controller.getAnnotation(annotation);
        }
    }

    private static List<Door> doorsOf(Collection<Class<?>> controllers) {
        List<Door> doors = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method handler : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(handler, RequestMapping.class)) {
                    continue;
                }
                Door door = new Door(controller, handler);
                if (door.declared(RequiresPermission.class) != null || door.declared(RequiresMerchantKey.class) != null) {
                    doors.add(door);
                }
            }
        }
        return doors;
    }

    private static List<Door> doorsInScope(Collection<Class<?>> controllers) {
        return doorsOf(controllers).stream().filter(Door::inScope).toList();
    }

    private static Set<String> undeclared(Collection<Class<?>> controllers) {
        return doorsInScope(controllers).stream()
                .filter(door -> !door.recordsRefusals())
                .map(Door::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("no such door is without the declaration")
    void everyDoorRecordsItsRefusals() {
        assertThat(undeclared(CONTROLLERS))
                .as("a door of Phases 7 to 10 whose refusals nothing records: declare @RecordsRefusals on it")
                .isEmpty();
    }

    @Test
    @DisplayName("the rule can fail: a planted door with no declaration is caught, one with it is not")
    void aPlantedDoorIsCaught() {
        assertThat(undeclared(List.of(Planted.class))).containsExactly("Planted#undeclared");
    }

    @Test
    @DisplayName("the rule reaches every phase's package, and more doors than a broken import could hide")
    void theRuleIsNotVacuous() {
        List<Door> doors = doorsInScope(CONTROLLERS);
        assertThat(doors.stream().map(door -> door.controller().getPackageName()).collect(Collectors.toSet()))
                .containsAll(PHASE_PACKAGES);
        assertThat(doors).as("%s", doors.size()).hasSizeGreaterThanOrEqualTo(FEWEST_DOORS);
    }

    @Test
    @DisplayName("no such door answers a refusal for itself: every one reaches the error contract, where the record is")
    void noDoorAnswersARefusalOfItsOwn() {
        List<String> ownAnswer = doorsInScope(CONTROLLERS).stream()
                .filter(door -> door.handler().getReturnType() == ResponseEntity.class
                        || Arrays.asList(door.handler().getParameterTypes()).contains(HttpServletResponse.class))
                .map(Door::name)
                .toList();

        assertThat(ownAnswer)
                .as("a handler that shapes its own status or writes its own response can answer a refusal the error"
                        + " contract never sees, and so nothing records")
                .isEmpty();
    }

    @RestController
    @SuppressWarnings("unused") // Read by reflection.
    static class Planted {

        @PostMapping("/planted/undeclared")
        @RequiresPermission(PermissionName.COUNTERPARTY_SCREENING_REVIEW)
        public void undeclared() {}

        @GetMapping("/planted/declared")
        @RequiresPermission(PermissionName.COUNTERPARTY_SCREENING_REVIEW)
        @RecordsRefusals
        public void declared() {}
    }
}
