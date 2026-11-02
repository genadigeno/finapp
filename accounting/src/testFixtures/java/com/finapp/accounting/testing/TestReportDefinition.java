package com.finapp.accounting.testing;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.ReportStatement;
import java.sql.Connection;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A test-only report definition (`P14-TSK-008`: "prove the machinery with a test-only definition" - the definitions'
 * content is `P14-TSK-009`, `-016` and `-017`'s): a code and version, the parameters it takes, and a body function the
 * suite supplies - a pure echo of the inputs for the hermetic suites, a read of the snapshot for the database suite, a
 * write for the read-only proof, a throw for the failure path. Test code: it ships in no jar.
 */
public final class TestReportDefinition implements ReportDefinition {

    /** The probe's code - no {@code ReportKind}, so it can never be mistaken for a production report. */
    public static final String CODE = "TEST_PROBE";

    private static final ReportStatement STATEMENT = new ReportStatement("the suite's own body function",
            "whatever the suite supplies", "the suite's own dates", "the suite's own currencies",
            "generated per the machinery under test");

    private final String code;
    private final int version;
    private final Set<String> parameterNames;
    private final boolean needsValuationRateSet;
    private final boolean takesValuationRateSet;
    private final BiFunction<Connection, ReportInputs, CanonicalJson.Obj> body;

    public TestReportDefinition(String code, int version, Set<String> parameterNames, boolean needsValuationRateSet,
            BiFunction<Connection, ReportInputs, CanonicalJson.Obj> body) {
        this(code, version, parameterNames, needsValuationRateSet, needsValuationRateSet, body);
    }

    private TestReportDefinition(String code, int version, Set<String> parameterNames, boolean needsValuationRateSet,
            boolean takesValuationRateSet, BiFunction<Connection, ReportInputs, CanonicalJson.Obj> body) {
        this.code = Objects.requireNonNull(code, "code");
        this.version = version;
        this.parameterNames = Set.copyOf(parameterNames);
        this.needsValuationRateSet = needsValuationRateSet;
        this.takesValuationRateSet = takesValuationRateSet;
        this.body = Objects.requireNonNull(body, "body");
    }

    /** This definition, a run of which may name a valuation rate set (`P14-TSK-015`). */
    public TestReportDefinition takingARateSet() {
        return new TestReportDefinition(code, version, parameterNames, needsValuationRateSet, true, body);
    }

    /** {@link #CODE} version 1, taking {@code probe} and {@code mode}, its body an echo of the inputs. */
    public static TestReportDefinition echo() {
        return new TestReportDefinition(CODE, 1, Set.of("probe", "mode"), false, TestReportDefinition::echoOf);
    }

    /** {@link #CODE} version {@code version} with {@code body}, taking {@code probe} and {@code mode}. */
    public static TestReportDefinition of(int version, BiFunction<Connection, ReportInputs, CanonicalJson.Obj> body) {
        return new TestReportDefinition(CODE, version, Set.of("probe", "mode"), false, body);
    }

    /** The echo: the period's range and the parameters - a pure function of the inputs. */
    public static CanonicalJson.Obj echoOf(Connection snapshot, ReportInputs inputs) {
        CanonicalJson.Obj values = CanonicalJson.object();
        inputs.parameters().values().forEach(values::put);
        return CanonicalJson.object()
                .put("from", inputs.start().toString())
                .put("through", inputs.end().toString())
                .put("echo", values)
                .putMoney("total", 0, "EUR", 2);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public ReportStatement statement() {
        return STATEMENT;
    }

    @Override
    public Set<String> parameterNames() {
        return parameterNames;
    }

    @Override
    public boolean needsValuationRateSet() {
        return needsValuationRateSet;
    }

    @Override
    public boolean takesValuationRateSet() {
        return takesValuationRateSet;
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        return body.apply(snapshot, inputs);
    }
}
