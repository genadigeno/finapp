package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every judgement a rail produces is WRITTEN in one of three appliers (`P7-TSK-015`) — the property
 * {@code finapp.payments.rail.outcome}'s completeness rests on.
 *
 * <p>The appliers ({@code PaymentOutcomes}, {@code WithdrawalOutcomes},
 * {@code DisputeResponseOutcomes}) report each acting judgement to their {@code RailOutcomeObserver},
 * which is what makes the count complete by construction: every door and every sweep is only an
 * arrival of a judgement one of them writes. That holds exactly as long as nothing ELSE writes a
 * judgement — a new resolver calling {@code attempts.capture(...)} itself would move money the
 * meters never see, the "count a new door silently loses" defect the seam exists to end (Phase 7's
 * doors lost five). So the writers are pinned to their appliers: a store method that commits a
 * judgement — and the book attempt's birth, which IS one — may be called from its applier and
 * nowhere else in production code.
 *
 * <p>Method CALLS, read from bytecode (a mention in prose cannot satisfy it, the
 * {@code P1-TSK-021} lesson); calls through the port or its JDBC class alike. Births that judge
 * nothing ({@code *_DISPATCHED}, {@code AWAITING_PAYER}) are not writers here.
 */
@Tag("architecture")
@DisplayName("judgement writers are confined to their appliers (P7-TSK-015)")
class JudgementWritersAreConfinedTest {

    /** A writer family: the store (port and JDBC class), its judging methods, their one applier. */
    private record Writers(Set<String> owners, Set<String> methods, String applier) {}

    private static final List<Writers> WRITERS =
            List.of(
                    new Writers(
                            Set.of(
                                    "com.finapp.payments.PaymentAttemptStore",
                                    "com.finapp.payments.JdbcPaymentAttemptStore"),
                            Set.of(
                                    "authorize", "markAuthUnknown", "capture",
                                    "markCaptureUnknown", "voided", "markVoidUnknown", "fail",
                                    "execute", "failHandleless", "openInitiation"),
                            "com.finapp.payments.PaymentOutcomes"),
                    new Writers(
                            Set.of(
                                    "com.finapp.payments.RefundStore",
                                    "com.finapp.payments.JdbcRefundStore"),
                            Set.of("complete", "fail", "markUnknown"),
                            "com.finapp.payments.PaymentOutcomes"),
                    // The book attempt is BORN executed (P7-TSK-011): its birth is its judgement.
                    new Writers(
                            Set.of("com.finapp.payments.PaymentAttempt"),
                            Set.of("createBook"),
                            "com.finapp.payments.PaymentOutcomes"),
                    new Writers(
                            Set.of(
                                    "com.finapp.payments.WithdrawalStore",
                                    "com.finapp.payments.JdbcWithdrawalStore"),
                            Set.of("transition"),
                            "com.finapp.payments.WithdrawalOutcomes"),
                    new Writers(
                            Set.of(
                                    "com.finapp.payments.DisputeResponseStore",
                                    "com.finapp.payments.JdbcDisputeResponseStore"),
                            Set.of("transition"),
                            "com.finapp.payments.DisputeResponseOutcomes"));

    @Test
    @DisplayName("every call that commits a judgement originates in its applier - the observer sees"
            + " every judgement because no other code can write one")
    void everyJudgementWriterCallIsItsAppliers() {
        Set<String> strays = new TreeSet<>();
        for (Map.Entry<String, String> call : writerCalls().entrySet()) {
            String origin = call.getKey().substring(0, call.getKey().indexOf(" -> "));
            String applier = call.getValue();
            if (!origin.startsWith(applier + ".") && !origin.startsWith(applier + "$")) {
                strays.add(call.getKey() + " (only " + applier + " may write it)");
            }
        }
        assertThat(strays)
                .as("a judgement written outside its applier is a judgement the rail meters never"
                        + " count - route it through the applier, whose acting branch reports it")
                .isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: every writer family is found called from its applier")
    void theGuardHasTeeth() {
        Map<String, String> calls = writerCalls();
        for (Writers family : WRITERS) {
            assertThat(calls.entrySet())
                    .as("the detector must find %s's own calls to %s", family.applier(),
                            family.methods())
                    .anySatisfy(
                            call ->
                                    assertThat(call.getKey()).startsWith(family.applier() + "."));
        }
    }

    // -----------------------------------------------------------------

    /** Every production call to a writer, as {@code origin.method -> owner.method} to its applier. */
    private static Map<String, String> writerCalls() {
        Map<String, String> calls = new java.util.TreeMap<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                for (Writers family : WRITERS) {
                    // A store calling itself is not a writer: the generic port's bridge methods
                    // (authorize(Object, ...) -> authorize(Connection, ...)) delegate inside the
                    // JDBC class, and a store implementing its own method is the write itself.
                    if (family.owners().contains(call.getOriginOwner().getName())) {
                        continue;
                    }
                    if (family.owners().contains(call.getTargetOwner().getName())
                            && family.methods().contains(call.getName())) {
                        calls.put(
                                call.getOriginOwner().getName()
                                        + "."
                                        + call.getOrigin().getName()
                                        + " -> "
                                        + call.getTargetOwner().getSimpleName()
                                        + "."
                                        + call.getName(),
                                family.applier());
                    }
                }
            }
        }
        return calls;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
