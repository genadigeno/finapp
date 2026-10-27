package com.finapp.app.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.IdentityActions;
import com.finapp.risk.MerchantActions;
import com.finapp.risk.RegulatoryFilingProvider;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * The risk storm's adversaries (`P13-TST-001`; the BNPL storm's {@code BnplStormFaults}), each either an owner-rank
 * lever confined to a quiesced scene or a seam the instances' own beans are wrapped at:
 *
 * <ul>
 *   <li>a <strong>crash point</strong> ({@link #refuse}): a row-conditioned {@code BEFORE} trigger that raises (a forced
 *       rollback) or terminates its own backend mid-transaction (a killed backend) - only the rows the condition names;
 *   <li>an instance's <strong>backends terminated</strong> by its pool's application name ({@link #terminateBackends});
 *   <li>the <strong>owning domains</strong> an action reaches ({@link IdentityActions}, {@link MerchantActions},
 *       {@link HeldWithdrawalActions}, {@link HeldPaymentActions}) and the <strong>filing authority</strong>
 *       ({@link RegulatoryFilingProvider}) wrapped on both instances ({@link #wrapper}): every call counted by target and
 *       key, and a fault armed per target - the domain down ({@link Fault#FAIL_BEFORE}: nothing reached it), or the
 *       domain's answer lost after it committed ({@link Fault#LOSE_RESPONSE}: the timeout that leaves the caller not
 *       knowing);
 *   <li>the <strong>tipping-off counter</strong> ({@link RiskReads}): each instance's {@link DataSource} wrapped so every
 *       statement naming a {@code risk.} relation is counted by the request's principal kind and by whether it ran inside
 *       the judgement (T-eval or T-advise - {@code RiskEvaluation} on the stack) - the runtime counterpart of
 *       {@code NoCustomerSurfaceReadsRiskTest} (`INV-RSK-08`).
 * </ul>
 */
final class RiskStormFaults {

    private RiskStormFaults() {}

    // ================================================================== crash points

    /**
     * A fault beneath {@code table}'s {@code operation} where {@code condition} holds: the statement refused (a forced
     * rollback) or, {@code kill}, the backend terminated mid-transaction. Closing lifts it.
     */
    static AutoCloseable refuse(String table, String operation, String condition, boolean kill) throws SQLException {
        String name = "storm_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        String act = kill ? "PERFORM pg_terminate_backend(pg_backend_pid()); PERFORM pg_sleep(5);"
                : "RAISE EXCEPTION 'the risk storm''s injected fault';";
        String row = operation.contains("DELETE") ? "OLD" : "NEW";
        try (Connection owner = DatabaseRoles.bootstrap(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN " + act + " END IF; RETURN " + row + "; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + schema + "." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.bootstrap(); Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON " + table);
                ddl.execute("DROP FUNCTION " + schema + "." + name + "()");
            }
        };
    }

    /**
     * A write of {@code table} matching {@code condition} held for {@code seconds} under its row lock before it proceeds -
     * a slow commit, never a refusal: the window between a lock taken and its transaction's commit widened so that what
     * queues behind it does, every time.
     */
    static AutoCloseable delay(String table, String operation, String condition, double seconds) throws SQLException {
        String name = "storm_delay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        try (Connection owner = DatabaseRoles.bootstrap(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN PERFORM pg_sleep(" + seconds + "); END IF; RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + schema + "." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.bootstrap(); Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON " + table);
                ddl.execute("DROP FUNCTION " + schema + "." + name + "()");
            }
        };
    }

    /** Every backend of the pool named {@code applicationName} terminated - an instance's crash, as the database sees it. */
    static int terminateBackends(String applicationName) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap();
                PreparedStatement terminate = owner.prepareStatement("SELECT count(*) FILTER (WHERE"
                        + " pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = ?")) {
            terminate.setString(1, applicationName);
            try (var row = terminate.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    /** One backend terminated - a crash in the middle of whatever it waits on. */
    static boolean terminate(int pid) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap();
                PreparedStatement terminate = owner.prepareStatement("SELECT pg_terminate_backend(?)")) {
            terminate.setInt(1, pid);
            try (var row = terminate.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    // ================================================================== the owning domains and the authority

    /** A fault armed for a target: how many calls it applies to. */
    enum Fault {
        /** The domain down: the call throws before reaching it. */
        FAIL_BEFORE,
        /** The domain's answer lost: the call reaches it, commits, and then throws - the caller cannot know. */
        LOSE_RESPONSE
    }

    /** A domain call seen: the instance, the method, the target, the key. */
    record Call(String instance, String method, String target, String key) {}

    /** target (uuid text, or a filing reference) -> the fault and how many more calls it applies to. */
    static final Map<String, Object[]> ARMED = new ConcurrentHashMap<>();
    /** method + target -> calls that reached the domain (after FAIL_BEFORE). */
    static final Map<String, AtomicInteger> REACHED = new ConcurrentHashMap<>();
    /** method + target -> calls refused by an armed fault before the domain. */
    static final Map<String, AtomicInteger> REFUSED = new ConcurrentHashMap<>();
    /** method + target -> answers lost after the domain committed. */
    static final Map<String, AtomicInteger> LOST = new ConcurrentHashMap<>();

    static void arm(String target, Fault fault, int times) {
        ARMED.put(target, new Object[] {fault, new AtomicInteger(times)});
    }

    static int reached(String method, String target) {
        AtomicInteger found = REACHED.get(method + ":" + target);
        return found == null ? 0 : found.get();
    }

    static int lost(String method, String target) {
        AtomicInteger found = LOST.get(method + ":" + target);
        return found == null ? 0 : found.get();
    }

    static int refusedBefore(String method, String target) {
        AtomicInteger found = REFUSED.get(method + ":" + target);
        return found == null ? 0 : found.get();
    }

    /** The calls reaching any domain for {@code method} - every target. */
    static int reached(String method) {
        return REACHED.entrySet().stream().filter(e -> e.getKey().startsWith(method + ":"))
                .mapToInt(e -> e.getValue().get()).sum();
    }

    private static final Set<Class<?>> WRAPPED = Set.of(IdentityActions.class, MerchantActions.class,
            HeldWithdrawalActions.class, HeldPaymentActions.class, RegulatoryFilingProvider.class);

    /**
     * The post-processor each instance registers: the owning domains' ports and the filing authority wrapped for
     * counting and faults, the {@link DataSource} wrapped for the tipping-off counter.
     */
    static BeanPostProcessor wrapper(String instance) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource dataSource && "dataSource".equals(beanName)) {
                    return RiskReads.wrap(dataSource);
                }
                for (Class<?> port : WRAPPED) {
                    if (port.isInstance(bean) && !Proxy.isProxyClass(bean.getClass())) {
                        return Proxy.newProxyInstance(RiskStormFaults.class.getClassLoader(), new Class<?>[] {port},
                                new Faulted(instance, bean));
                    }
                }
                return bean;
            }
        };
    }

    /** One wrapped port: every call counted by method and target, an armed fault applied. */
    private record Faulted(String instance, Object target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "storm(" + target + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(target, args);
                };
            }
            String subject = subjectOf(method, args);
            String counted = method.getName() + ":" + subject;
            Object[] armed = subject == null ? null : ARMED.get(subject);
            Fault fault = null;
            if (armed != null && ((AtomicInteger) armed[1]).getAndDecrement() > 0) {
                fault = (Fault) armed[0];
            }
            if (fault == Fault.FAIL_BEFORE) {
                REFUSED.computeIfAbsent(counted, k -> new AtomicInteger()).incrementAndGet();
                throw new IllegalStateException("the risk storm's domain is down");
            }
            Object answer;
            try {
                answer = method.invoke(target, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            } finally {
                REACHED.computeIfAbsent(counted, k -> new AtomicInteger()).incrementAndGet();
            }
            if (fault == Fault.LOSE_RESPONSE) {
                LOST.computeIfAbsent(counted, k -> new AtomicInteger()).incrementAndGet();
                throw new IllegalStateException("the risk storm lost the domain's answer (a timeout)");
            }
            return answer;
        }

        /** The call's target: the first UUID argument, or a filing's report reference. */
        private static String subjectOf(Method method, Object[] args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (arg instanceof UUID id) {
                    return id.toString();
                }
                if (arg instanceof RegulatoryFilingProvider.Request request) {
                    return request.report().value().toString();
                }
            }
            if (method.getName().equals("inquire") && args.length == 1 && args[0] instanceof String reference) {
                return reference;
            }
            return null;
        }
    }

    // ================================================================== the tipping-off counter

    /**
     * Every statement naming a {@code risk.} relation, counted by the principal kind of the thread that ran it and by
     * whether {@code com.finapp.risk.RiskEvaluation} (T-eval, T-advise) was on its stack - the judgement, which reads risk
     * inside a customer's own command by design and answers only the owner's outcome.
     */
    static final class RiskReads {

        /** "CUSTOMER judgement", "CUSTOMER other", "EMPLOYEE other", "NONE other", ... -> statements. */
        static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
        /** The first few statements a subject principal ran outside the judgement - the census names them. */
        static final Map<String, String> OFFENDERS = new ConcurrentHashMap<>();

        private RiskReads() {}

        static long of(String key) {
            AtomicLong found = COUNTS.get(key);
            return found == null ? 0 : found.get();
        }

        static DataSource wrap(DataSource delegate) {
            return (DataSource) Proxy.newProxyInstance(RiskStormFaults.class.getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object answer = invokeOn(delegate, method, args);
                        if (answer instanceof Connection connection) {
                            return connection(connection);
                        }
                        return answer;
                    });
        }

        private static Connection connection(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(RiskStormFaults.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if ((name.equals("prepareStatement") || name.equals("prepareCall")) && args != null
                                && args[0] instanceof String sql) {
                            observe(sql);
                        }
                        if (name.equals("unwrap") && args != null && args[0] == Connection.class) {
                            return proxy;
                        }
                        Object answer = invokeOn(delegate, method, args);
                        if (name.equals("createStatement") && answer instanceof Statement statement) {
                            return statement(statement);
                        }
                        return answer;
                    });
        }

        private static Statement statement(Statement delegate) {
            return (Statement) Proxy.newProxyInstance(RiskStormFaults.class.getClassLoader(),
                    new Class<?>[] {Statement.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("execute") || method.getName().equals("addBatch")) {
                            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                                observe(sql);
                            }
                        }
                        return invokeOn(delegate, method, args);
                    });
        }

        private static Object invokeOn(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        }

        /** OPERATOR, MERCHANT, CUSTOMER (a request on a subject's surface), or the actor's type with no request. */
        private static String surface() {
            String actor = SecurityContext.current().map(Actor::type).map(Enum::name).orElse("NONE");
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    instanceof org.springframework.web.context.request.ServletRequestAttributes request) {
                String path = request.getRequest().getRequestURI();
                if (path.startsWith("/v1/operator")) {
                    return "OPERATOR";
                }
                return "MERCHANT".equals(actor) ? "MERCHANT" : "CUSTOMER";
            }
            return actor.equals("NONE") ? "PLATFORM" : "PLATFORM-" + actor;
        }

        private static void observe(String sql) {
            if (!sql.toLowerCase(Locale.ROOT).contains("risk.")) {
                return;
            }
            // The principal kind is the surface the request came through: a person holding an operator role is a
            // CUSTOMER actor too (the platform's identities are people), so the operator's desks are told apart by their
            // route (/v1/operator/...), a merchant by its key's actor; no request at all is the platform's own work.
            String principal = surface();
            // The judgement: T-eval and T-advise (RiskEvaluation), and the seam adapters the owner's command consults in
            // its own transaction - a second seam answered from the first's seal reads the seal through the store.
            boolean judgement = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> {
                String name = frame.getClassName();
                return name.equals("com.finapp.risk.RiskEvaluation") || (name.startsWith("com.finapp.app.risk.Risk")
                        && (name.endsWith("RiskDecision") || name.endsWith("LimitCheck") || name.endsWith("StepUpAdvice")))
                        || (name.startsWith("com.finapp.app.risk.") && name.endsWith("RiskJudgement"));
            }));
            String key = principal + (judgement ? " judgement" : " other");
            COUNTS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            if (!judgement && ("CUSTOMER".equals(principal) || "MERCHANT".equals(principal)) && OFFENDERS.size() < 10) {
                String caller = StackWalker.getInstance().walk(frames -> frames
                        .map(frame -> frame.getClassName().replace("com.finapp.", "") + "." + frame.getMethodName())
                        .filter(name -> !name.contains("RiskStorm") && !name.startsWith("jdk") && !name.startsWith("java")
                                && !name.startsWith("org.") && !name.startsWith("com.zaxxer") && !name.startsWith("sun"))
                        .limit(8).collect(java.util.stream.Collectors.joining(" < ")));
                OFFENDERS.putIfAbsent(caller + " " + sql.substring(0, Math.min(120, sql.length())), principal);
            }
        }
    }

    /** The principal kind of the current thread, for a fault's diagnostics. */
    static Optional<String> principal() {
        return SecurityContext.current().map(actor -> actor.type().name());
    }
}
