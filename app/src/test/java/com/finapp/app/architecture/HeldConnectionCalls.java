package com.finapp.app.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Which code runs while a database connection is held, and which of it can reach the network (ADR-0046; ADR-0008).
 *
 * <p>Read from the compiled classes with ASM rather than ArchUnit: ArchUnit attributes a lambda's calls to the method that declares
 * it, so it cannot tell a transaction's body from the code around it - and around it is exactly where ADR-0046 puts the provider
 * call. In the class file every lambda is its own synthetic method, and that is the unit read here.
 *
 * <h2>Held</h2>
 *
 * A method runs while a connection is held when it is handed one ({@link #HANDLES}: every transaction body is, the modules'
 * {@code inTransaction(Function<Connection, R>)} and Spring's {@code TransactionStatus} callbacks alike, and so is every store method
 * that takes the caller's connection), when it is annotated {@code @Transactional}, or when it turns a connection's auto-commit off
 * itself. Whatever such a method calls runs held too.
 *
 * <h2>Calls</h2>
 *
 * The call graph is the class hierarchy's over production code: a virtual or interface call reaches every declaration above its
 * owner and every override below it, and also every lambda or method reference that implements a production interface there. Making
 * a lambda counts as calling it. A functional value from the JDK ({@code Supplier}, {@code Function}, {@code Runnable}, ...) is
 * followed to where it was made: a held method's functional parameter to the lambdas its callers pass (through their parameters in
 * turn), and a functional field to the lambdas stored in it. What cannot be followed so is {@linkplain #unfollowed() listed}.
 *
 * <h2>The network</h2>
 *
 * {@code HttpClient.send} and {@code sendAsync}: {@code OutboundExchange.send} is the one place that calls them, and
 * {@code OutboundCallsAreBoundedTest} holds that every provider exchange goes through it.
 */
final class HeldConnectionCalls {

    static final String HTTP_CLIENT = "java/net/http/HttpClient";

    static final Set<String> SENDS = Set.of("send", "sendAsync");

    /** A parameter of one of these types means the method runs while the transaction or the connection it names is held. */
    static final Set<String> HANDLES = Set.of(
            "org/springframework/transaction/TransactionStatus",
            "java/sql/Connection",
            "java/sql/Statement",
            "java/sql/PreparedStatement",
            "java/sql/CallableStatement",
            "java/sql/ResultSet");

    static final Set<String> TRANSACTIONAL = Set.of(
            "Lorg/springframework/transaction/annotation/Transactional;", "Ljakarta/transaction/Transactional;");

    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    /** One method, by its owner's internal name, its name and its descriptor. */
    record Method(String owner, String name, String descriptor) implements Comparable<Method> {

        @Override
        public String toString() {
            return owner.replace('/', '.') + "#" + name;
        }

        String full() {
            return owner.replace('/', '.') + "#" + name + descriptor;
        }

        @Override
        public int compareTo(Method other) {
            return full().compareTo(other.full());
        }
    }

    /** Why a method is read as running while a connection is held. */
    enum Hold {
        HANDED_THE_HANDLE,
        ANNOTATED_TRANSACTIONAL,
        OPENS_ITS_OWN
    }

    /**
     * Where a method is entered from: a call, or the making of the lambda whose body it is. Its parameter q is the site's argument
     * q + shift.
     */
    private record Site(Method caller, AbstractInsnNode insn, int shift) {}

    /** A lambda or method reference implementing a production interface, and how its parameters line up with the interface's. */
    private record LambdaImpl(Method body, int shift) {}

    private sealed interface Source permits Made, Param, Field, Unknown {}

    private record Made(Method body) implements Source {}

    private record Param(int index) implements Source {}

    private record Field(String owner, String name) implements Source {}

    private record Unknown(String what) implements Source {}

    static final Method NETWORK = new Method(HTTP_CLIENT, "send", "");

    private final Map<String, ClassNode> classes;
    private final Map<Method, MethodNode> methods = new HashMap<>();
    private final Map<String, Set<String>> directSubtypes = new HashMap<>();
    private final Map<String, Set<String>> allSubtypes = new HashMap<>();
    private final Map<String, List<LambdaImpl>> lambdaImpls = new HashMap<>();
    private final Map<Method, Set<Method>> callees = new HashMap<>();
    private final Map<Method, List<Site>> sites = new HashMap<>();
    private final Map<Method, Frame<SourceValue>[]> frames = new HashMap<>();
    private final Map<String, Trace> lambdaSets = new HashMap<>();
    private final Map<String, Trace> fieldValues = new HashMap<>();
    private final Map<Method, Hold> scopes = new TreeMap<>();
    private final Map<Method, Method> towardTheNetwork = new HashMap<>();
    private final Map<Method, Method> heldFrom = new HashMap<>();
    private final Set<String> unfollowed = new TreeSet<>();

    /**
     * Every production class, read with its code: each module's main output, its test fixtures left out - an in-memory store a test
     * wires is no code that runs - and the modules' coverage left to {@code ProductionModules}' own guard.
     */
    static Map<String, ClassNode> production() {
        Map<String, ClassNode> classes = new HashMap<>();
        for (Path entry : ProductionModules.mainOutputEntries().keySet()) {
            String normalised = entry.toString().replace('\\', '/');
            if (normalised.endsWith("-test-fixtures.jar") || normalised.contains("/classes/java/testFixtures")) {
                continue;
            }
            for (byte[] bytes : classFiles(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
                classes.put(node.name, node);
            }
        }
        return classes;
    }

    private static List<byte[]> classFiles(Path entry) {
        List<byte[]> files = new ArrayList<>();
        try {
            if (Files.isDirectory(entry)) {
                try (Stream<Path> walk = Files.walk(entry)) {
                    for (Path file : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                        files.add(Files.readAllBytes(file));
                    }
                }
            } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(entry.toFile())) {
                    for (JarEntry file : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                        try (InputStream in = jar.getInputStream(file)) {
                            files.add(in.readAllBytes());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + entry, e);
        }
        return files;
    }

    /** One class from the test classpath, read with its code: a probe planted beside production. */
    static ClassNode classOf(Class<?> type) {
        String binary = type.getName();
        try (InputStream in = type.getResourceAsStream(binary.substring(binary.lastIndexOf('.') + 1) + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    HeldConnectionCalls(Map<String, ClassNode> classes) {
        this.classes = classes;
        index();
        for (Map.Entry<Method, MethodNode> entry : methods.entrySet()) {
            Method caller = entry.getKey();
            for (AbstractInsnNode insn : entry.getValue().instructions) {
                link(caller, insn);
            }
        }
        findScopes();
        followFunctionalValues();
        walkBackFromTheNetwork();
        walkForwardFromTheScopes();
        findUnfollowed();
    }

    // ------------------------------------------------------------------------------------------------ the index

    private void index() {
        for (ClassNode node : classes.values()) {
            for (MethodNode method : node.methods) {
                methods.put(new Method(node.name, method.name, method.desc), method);
            }
            if (node.superName != null) {
                directSubtypes.computeIfAbsent(node.superName, k -> new HashSet<>()).add(node.name);
            }
            for (String implemented : node.interfaces) {
                directSubtypes.computeIfAbsent(implemented, k -> new HashSet<>()).add(node.name);
            }
        }
        for (ClassNode node : classes.values()) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (lambda(insn) instanceof InvokeDynamicInsnNode indy) {
                        String implemented = Type.getReturnType(indy.desc).getInternalName();
                        if (classes.containsKey(implemented)) {
                            Handle target = (Handle) indy.bsmArgs[1];
                            String sam = ((Type) indy.bsmArgs[0]).getDescriptor();
                            for (Method body : bodies(target)) {
                                lambdaImpls.computeIfAbsent(implemented + "." + indy.name + sam, k -> new ArrayList<>())
                                        .add(new LambdaImpl(body, dispatchShift(indy, target)));
                            }
                        }
                    }
                }
            }
        }
    }

    private static AbstractInsnNode lambda(AbstractInsnNode insn) {
        return insn instanceof InvokeDynamicInsnNode indy
                        && METAFACTORY.equals(indy.bsm.getOwner())
                        && indy.bsmArgs.length > 1
                        && indy.bsmArgs[1] instanceof Handle
                ? indy
                : null;
    }

    /** A bound receiver is the indy's first argument and no parameter of the body; an unbound one is the interface's first. */
    private static boolean boundReceiver(InvokeDynamicInsnNode indy, Handle target) {
        boolean instance = target.getTag() != Opcodes.H_INVOKESTATIC && target.getTag() != Opcodes.H_NEWINVOKESPECIAL;
        return instance && Type.getArgumentTypes(indy.desc).length > 0;
    }

    private static int creationShift(InvokeDynamicInsnNode indy, Handle target) {
        return boundReceiver(indy, target) ? 1 : 0;
    }

    private static int dispatchShift(InvokeDynamicInsnNode indy, Handle target) {
        int captured = Type.getArgumentTypes(indy.desc).length;
        boolean instance = target.getTag() != Opcodes.H_INVOKESTATIC && target.getTag() != Opcodes.H_NEWINVOKESPECIAL;
        if (instance && captured == 0) {
            return 1;
        }
        return -(boundReceiver(indy, target) ? captured - 1 : captured);
    }

    // ------------------------------------------------------------------------------------------------ the calls

    private void link(Method caller, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            if (HTTP_CLIENT.equals(call.owner) && SENDS.contains(call.name)) {
                edge(caller, NETWORK);
                return;
            }
            boolean virtual = call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE;
            for (Method target : virtual ? dispatch(call.owner, call.name, call.desc) : exact(call.owner, call.name, call.desc)) {
                edge(caller, target);
                sites.computeIfAbsent(target, k -> new ArrayList<>()).add(new Site(caller, insn, 0));
            }
            if (virtual) {
                for (LambdaImpl impl : implementingLambdas(call.owner, call.name, call.desc)) {
                    edge(caller, impl.body());
                    sites.computeIfAbsent(impl.body(), k -> new ArrayList<>()).add(new Site(caller, insn, impl.shift()));
                }
            }
        } else if (lambda(insn) instanceof InvokeDynamicInsnNode indy) {
            Handle target = (Handle) indy.bsmArgs[1];
            if (HTTP_CLIENT.equals(target.getOwner()) && SENDS.contains(target.getName())) {
                edge(caller, NETWORK);
                return;
            }
            for (Method body : bodies(target)) {
                edge(caller, body);
                sites.computeIfAbsent(body, k -> new ArrayList<>()).add(new Site(caller, insn, creationShift(indy, target)));
            }
        }
    }

    private void edge(Method from, Method to) {
        callees.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    private Collection<Method> bodies(Handle target) {
        return switch (target.getTag()) {
            case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE -> dispatch(target.getOwner(), target.getName(), target.getDesc());
            default -> exact(target.getOwner(), target.getName(), target.getDesc());
        };
    }

    /** A static, constructor, private or super call: the first declaration found walking up from the owner. */
    private Collection<Method> exact(String owner, String name, String descriptor) {
        for (String type : upward(owner)) {
            Method method = new Method(type, name, descriptor);
            if (methods.containsKey(method)) {
                return List.of(method);
            }
        }
        return List.of();
    }

    /** A virtual or interface call: every declaration the owner sees above it and every override below it (class hierarchy). */
    private Collection<Method> dispatch(String owner, String name, String descriptor) {
        if (!classes.containsKey(owner)) {
            return List.of();
        }
        List<Method> targets = new ArrayList<>();
        for (String type : hierarchy(owner)) {
            Method method = new Method(type, name, descriptor);
            if (methods.containsKey(method)) {
                targets.add(method);
            }
        }
        return targets;
    }

    private List<LambdaImpl> implementingLambdas(String owner, String name, String descriptor) {
        if (!classes.containsKey(owner)) {
            return List.of();
        }
        List<LambdaImpl> found = new ArrayList<>();
        for (String type : hierarchy(owner)) {
            found.addAll(lambdaImpls.getOrDefault(type + "." + name + descriptor, List.of()));
        }
        return found;
    }

    private Set<String> hierarchy(String owner) {
        Set<String> types = new LinkedHashSet<>(upward(owner));
        types.addAll(subtypes(owner));
        return types;
    }

    private List<String> upward(String owner) {
        List<String> seen = new ArrayList<>();
        Deque<String> work = new ArrayDeque<>(List.of(owner));
        while (!work.isEmpty()) {
            String type = work.poll();
            if (seen.contains(type) || !classes.containsKey(type)) {
                continue;
            }
            seen.add(type);
            ClassNode node = classes.get(type);
            if (node.superName != null) {
                work.add(node.superName);
            }
            work.addAll(node.interfaces);
        }
        return seen;
    }

    private Set<String> subtypes(String owner) {
        Set<String> known = allSubtypes.get(owner);
        if (known != null) {
            return known;
        }
        Set<String> found = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>(directSubtypes.getOrDefault(owner, Set.of()));
        while (!work.isEmpty()) {
            String type = work.poll();
            if (found.add(type)) {
                work.addAll(directSubtypes.getOrDefault(type, Set.of()));
            }
        }
        allSubtypes.put(owner, found);
        return found;
    }

    // ------------------------------------------------------------------------------------------------ the scopes

    private void findScopes() {
        for (Map.Entry<Method, MethodNode> entry : methods.entrySet()) {
            Method method = entry.getKey();
            MethodNode node = entry.getValue();
            if (handedTheHandle(method.descriptor())) {
                scopes.put(method, Hold.HANDED_THE_HANDLE);
            } else if (annotatedTransactional(classes.get(method.owner()), node)) {
                scopes.put(method, Hold.ANNOTATED_TRANSACTIONAL);
            } else if (opensItsOwn(node)) {
                scopes.put(method, Hold.OPENS_ITS_OWN);
            }
        }
    }

    static boolean handedTheHandle(String descriptor) {
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            if (argument.getSort() == Type.OBJECT && HANDLES.contains(argument.getInternalName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotatedTransactional(ClassNode owner, MethodNode method) {
        if (annotated(method.visibleAnnotations) || annotated(method.invisibleAnnotations)) {
            return true;
        }
        boolean instanceMethod = (method.access & Opcodes.ACC_STATIC) == 0 && !method.name.startsWith("<");
        return instanceMethod && (annotated(owner.visibleAnnotations) || annotated(owner.invisibleAnnotations));
    }

    private static boolean annotated(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> TRANSACTIONAL.contains(a.desc));
    }

    /** A method that turns a connection's auto-commit off itself: a unit of work it holds open. */
    private static boolean opensItsOwn(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && "java/sql/Connection".equals(call.owner) && "setAutoCommit".equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ functional values

    static boolean jdkFunctional(String internalName) {
        return internalName.startsWith("java/util/function/")
                || internalName.equals("java/lang/Runnable")
                || internalName.equals("java/util/concurrent/Callable");
    }

    private static boolean jdkFunctional(Type type) {
        return type.getSort() == Type.OBJECT && jdkFunctional(type.getInternalName());
    }

    /**
     * A held scope's functional parameters run held, whoever passed them; a functional field runs where it is read. Context-free
     * on purpose for the scopes - a scope is held in every context - and the reading of a field is where its value is used.
     */
    private void followFunctionalValues() {
        for (Method scope : scopes.keySet()) {
            Type[] parameters = Type.getArgumentTypes(scope.descriptor());
            for (int index = 0; index < parameters.length; index++) {
                if (jdkFunctional(parameters[index])) {
                    for (Method body : lambdaSet(scope, index).bodies()) {
                        edge(scope, body);
                    }
                }
            }
        }
        for (Map.Entry<Method, MethodNode> entry : methods.entrySet()) {
            for (AbstractInsnNode insn : entry.getValue().instructions) {
                if (functionalFieldRead(insn) instanceof FieldInsnNode read) {
                    for (Method body : fieldValues(read.owner, read.name).bodies()) {
                        edge(entry.getKey(), body);
                    }
                }
            }
        }
    }

    private static AbstractInsnNode functionalFieldRead(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode read
                        && (read.getOpcode() == Opcodes.GETFIELD || read.getOpcode() == Opcodes.GETSTATIC)
                        && jdkFunctional(Type.getType(read.desc))
                ? read
                : null;
    }

    /** The bodies a functional value can be, and what about it could not be followed. */
    private record Trace(Set<Method> bodies, Set<String> unfollowed) {

        Trace() {
            this(new TreeSet<>(), new TreeSet<>());
        }

        void add(Trace other) {
            bodies.addAll(other.bodies());
            unfollowed.addAll(other.unfollowed());
        }
    }

    /** The lambdas and method references passed to {@code method}'s parameter {@code index}, through its callers' parameters. */
    private Trace lambdaSet(Method method, int index) {
        String key = method.full() + "@" + index;
        Trace known = lambdaSets.get(key);
        if (known != null) {
            return known;
        }
        Trace found = new Trace();
        lambdaSets.put(key, found);
        boolean supplied = false;
        for (Site site : sites.getOrDefault(method, List.of())) {
            int argument = index + site.shift();
            int arguments = argumentCount(site.insn());
            if (argument < 0 || argument >= arguments) {
                continue;
            }
            supplied = true;
            Frame<SourceValue> frame = frame(site.caller(), site.insn());
            if (frame == null) {
                found.unfollowed().add(site.caller() + ": an unreadable call of " + method);
                continue;
            }
            SourceValue value = frame.getStack(frame.getStackSize() - arguments + argument);
            collect(site.caller(), value, found, "the argument " + index + " of " + method);
        }
        if (!supplied) {
            if (method.name().equals("<init>") || annotatedBean(methods.get(method))) {
                found.add(injected(Type.getArgumentTypes(method.descriptor())[index], method + " parameter " + index));
            } else {
                found.unfollowed().add(method + " parameter " + index + ": no call site in production code passes it");
            }
        }
        return found;
    }

    private static boolean annotatedBean(MethodNode method) {
        return method.visibleAnnotations != null
                && method.visibleAnnotations.stream().anyMatch(a -> "Lorg/springframework/context/annotation/Bean;".equals(a.desc));
    }

    /** A value Spring injects: whatever a {@code @Bean} method of that type returns. */
    private Trace injected(Type type, String what) {
        Trace found = new Trace();
        boolean declared = false;
        for (Map.Entry<Method, MethodNode> entry : methods.entrySet()) {
            Method bean = entry.getKey();
            if (annotatedBean(entry.getValue()) && Type.getReturnType(bean.descriptor()).equals(type)) {
                declared = true;
                for (AbstractInsnNode insn : entry.getValue().instructions) {
                    if (insn.getOpcode() == Opcodes.ARETURN) {
                        Frame<SourceValue> frame = frame(bean, insn);
                        if (frame != null && frame.getStackSize() > 0) {
                            collect(bean, frame.getStack(frame.getStackSize() - 1), found, "the bean " + bean);
                        }
                    }
                }
            }
        }
        if (!declared) {
            found.unfollowed().add(what + ": injected, and no @Bean method returns a " + type.getClassName());
        }
        return found;
    }

    private Trace fieldValues(String owner, String name) {
        String key = owner + "." + name;
        Trace known = fieldValues.get(key);
        if (known != null) {
            return known;
        }
        Trace found = new Trace();
        fieldValues.put(key, found);
        ClassNode node = classes.get(owner);
        if (node == null) {
            found.unfollowed().add(owner.replace('/', '.') + "." + name + ": a field outside production code");
            return found;
        }
        boolean stored = false;
        for (MethodNode method : node.methods) {
            Method writer = new Method(owner, method.name, method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode write
                        && (write.getOpcode() == Opcodes.PUTFIELD || write.getOpcode() == Opcodes.PUTSTATIC)
                        && write.owner.equals(owner)
                        && write.name.equals(name)) {
                    stored = true;
                    Frame<SourceValue> frame = frame(writer, insn);
                    if (frame != null) {
                        collect(writer, frame.getStack(frame.getStackSize() - 1), found, "the field " + key.replace('/', '.'));
                    }
                }
            }
        }
        if (!stored) {
            found.unfollowed().add(owner.replace('/', '.') + "." + name + ": never stored in its class");
        }
        return found;
    }

    private void collect(Method where, SourceValue value, Trace into, String what) {
        for (Source source : sources(where, value, 0)) {
            switch (source) {
                case Made made -> into.bodies().add(made.body());
                case Param param -> into.add(lambdaSet(where, param.index()));
                case Field field -> into.add(fieldValues(field.owner(), field.name()));
                case Unknown unknown -> into.unfollowed().add(where + ": " + what + " is " + unknown.what());
            }
        }
    }

    private Set<Source> sources(Method where, SourceValue value, int depth) {
        Set<Source> found = new LinkedHashSet<>();
        if (depth > 8) {
            found.add(new Unknown("too deep to trace"));
            return found;
        }
        for (AbstractInsnNode insn : value.insns) {
            if (lambda(insn) instanceof InvokeDynamicInsnNode indy) {
                bodies((Handle) indy.bsmArgs[1]).forEach(body -> found.add(new Made(body)));
            } else if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                Frame<SourceValue> frame = frame(where, insn);
                SourceValue local = frame == null ? null : frame.getLocal(load.var);
                if (local == null) {
                    found.add(new Unknown("unreadable"));
                } else if (local.insns.isEmpty()) {
                    int parameter = parameterAt(where, load.var);
                    found.add(parameter < 0 ? new Unknown("this") : new Param(parameter));
                } else {
                    for (AbstractInsnNode store : local.insns) {
                        Frame<SourceValue> before = frame(where, store);
                        if (before == null || before.getStackSize() == 0) {
                            found.add(new Unknown("an unreadable local"));
                        } else {
                            found.addAll(sources(where, before.getStack(before.getStackSize() - 1), depth + 1));
                        }
                    }
                }
            } else if (insn.getOpcode() == Opcodes.CHECKCAST || insn.getOpcode() == Opcodes.DUP) {
                Frame<SourceValue> frame = frame(where, insn);
                if (frame == null || frame.getStackSize() == 0) {
                    found.add(new Unknown("unreadable"));
                } else {
                    found.addAll(sources(where, frame.getStack(frame.getStackSize() - 1), depth + 1));
                }
            } else if (insn instanceof FieldInsnNode field) {
                found.add(new Field(field.owner, field.name));
            } else if (insn instanceof MethodInsnNode call) {
                found.addAll(returned(where, call, depth));
            } else if (insn instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW) {
                found.addAll(instanceOf(made.desc));
            } else if (insn.getOpcode() != Opcodes.ACONST_NULL) {
                found.add(new Unknown("made by opcode " + insn.getOpcode()));
            }
        }
        return found;
    }

    /** The JDK's own functions: made from no production code, so they run none. */
    private static final Set<String> JDK_MADE = Set.of(
            "java/util/function/Function.identity",
            "java/util/function/UnaryOperator.identity",
            "java/util/function/Predicate.isEqual",
            "java/util/regex/Pattern.asMatchPredicate",
            "java/util/regex/Pattern.asPredicate");

    /** The JDK's combinators: the result runs its receiver and its arguments. */
    private static final Set<String> COMBINATORS = Set.of("and", "or", "negate", "andThen", "compose", "not");

    /** What a call returns, as a functional value: traced through pass-throughs, combinators and production methods' returns. */
    private Set<Source> returned(Method where, MethodInsnNode call, int depth) {
        Set<Source> found = new LinkedHashSet<>();
        String name = call.owner + "." + call.name;
        Frame<SourceValue> frame = frame(where, call);
        int arguments = Type.getArgumentTypes(call.desc).length;
        boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
        if (JDK_MADE.contains(name)) {
            return found;
        }
        if (frame != null && "java/util/Objects".equals(call.owner) && call.name.startsWith("requireNonNull")) {
            found.addAll(sources(where, frame.getStack(frame.getStackSize() - arguments), depth + 1));
            return found;
        }
        if (frame != null && jdkFunctional(call.owner) && COMBINATORS.contains(call.name)) {
            int first = frame.getStackSize() - arguments - (instance ? 1 : 0);
            for (int index = first; index < frame.getStackSize(); index++) {
                found.addAll(sources(where, frame.getStack(index), depth + 1));
            }
            return found;
        }
        if (classes.containsKey(call.owner)) {
            boolean virtual = call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE;
            Collection<Method> targets = virtual ? dispatch(call.owner, call.name, call.desc) : exact(call.owner, call.name, call.desc);
            for (Method target : targets) {
                MethodNode node = methods.get(target);
                for (AbstractInsnNode insn : node.instructions) {
                    if (insn.getOpcode() == Opcodes.ARETURN) {
                        Frame<SourceValue> before = frame(target, insn);
                        if (before != null && before.getStackSize() > 0) {
                            for (Source source : sources(target, before.getStack(before.getStackSize() - 1), depth + 1)) {
                                if (source instanceof Param param && frame != null) {
                                    SourceValue argument = frame.getStack(frame.getStackSize() - arguments + param.index());
                                    found.addAll(sources(where, argument, depth + 1));
                                } else if (source instanceof Param) {
                                    found.add(new Unknown("an argument of " + target + " at an unreadable call"));
                                } else {
                                    found.add(source);
                                }
                            }
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                found.add(new Unknown("the result of " + call.owner.replace('/', '.') + "#" + call.name + ", which has no body"));
            }
            return found;
        }
        found.add(new Unknown("the result of " + call.owner.replace('/', '.') + "#" + call.name));
        return found;
    }

    /** An object made with {@code new} handed on as a functional value: any of its own methods may run. */
    private Set<Source> instanceOf(String type) {
        Set<Source> found = new LinkedHashSet<>();
        ClassNode node = classes.get(type);
        if (node == null) {
            found.add(new Unknown("an instance of " + type.replace('/', '.')));
            return found;
        }
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("<")) {
                found.add(new Made(new Method(type, method.name, method.desc)));
            }
        }
        return found;
    }

    private int parameterAt(Method method, int slot) {
        boolean instance = (methods.get(method).access & Opcodes.ACC_STATIC) == 0;
        int at = instance ? 1 : 0;
        Type[] parameters = Type.getArgumentTypes(method.descriptor());
        for (int index = 0; index < parameters.length; index++) {
            if (at == slot) {
                return index;
            }
            at += parameters[index].getSize();
        }
        return -1;
    }

    private static int argumentCount(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call
                ? Type.getArgumentTypes(call.desc).length
                : Type.getArgumentTypes(((InvokeDynamicInsnNode) insn).desc).length;
    }

    private Frame<SourceValue> frame(Method method, AbstractInsnNode insn) {
        Frame<SourceValue>[] all = frames.computeIfAbsent(method, this::analyse);
        if (all == null) {
            return null;
        }
        int index = methods.get(method).instructions.indexOf(insn);
        return index < all.length ? all[index] : null;
    }

    @SuppressWarnings("unchecked")
    private Frame<SourceValue>[] analyse(Method method) {
        try {
            return new Analyzer<>(new SourceInterpreter()).analyze(method.owner(), methods.get(method));
        } catch (AnalyzerException e) {
            unfollowed.add(method + ": its bytecode could not be analysed (" + e.getMessage() + ")");
            return (Frame<SourceValue>[]) new Frame<?>[0];
        }
    }

    // ------------------------------------------------------------------------------------------------ the walks

    private void walkBackFromTheNetwork() {
        Map<Method, Set<Method>> callers = new HashMap<>();
        callees.forEach((caller, targets) -> targets.forEach(t -> callers.computeIfAbsent(t, k -> new HashSet<>()).add(caller)));
        Deque<Method> work = new ArrayDeque<>();
        towardTheNetwork.put(NETWORK, NETWORK);
        work.add(NETWORK);
        while (!work.isEmpty()) {
            Method callee = work.poll();
            for (Method caller : callers.getOrDefault(callee, Set.of())) {
                if (!towardTheNetwork.containsKey(caller)) {
                    towardTheNetwork.put(caller, callee);
                    work.add(caller);
                }
            }
        }
    }

    private void walkForwardFromTheScopes() {
        Deque<Method> work = new ArrayDeque<>();
        for (Method scope : scopes.keySet()) {
            heldFrom.put(scope, scope);
            work.add(scope);
        }
        while (!work.isEmpty()) {
            Method method = work.poll();
            for (Method callee : callees.getOrDefault(method, Set.of())) {
                if (!heldFrom.containsKey(callee)) {
                    heldFrom.put(callee, method);
                    work.add(callee);
                }
            }
        }
    }

    /**
     * Every functional value held code runs, hands to the JDK, is handed as a scope or reads from a field, followed; what cannot be
     * followed is listed.
     */
    private void findUnfollowed() {
        for (Method held : heldFrom.keySet()) {
            MethodNode node = methods.get(held);
            if (node == null) {
                continue;
            }
            if (scopes.containsKey(held)) {
                Type[] parameters = Type.getArgumentTypes(held.descriptor());
                for (int index = 0; index < parameters.length; index++) {
                    if (jdkFunctional(parameters[index])) {
                        unfollowed.addAll(lambdaSet(held, index).unfollowed());
                    }
                }
            }
            for (AbstractInsnNode insn : node.instructions) {
                if (functionalFieldRead(insn) instanceof FieldInsnNode read) {
                    unfollowed.addAll(fieldValues(read.owner, read.name).unfollowed());
                }
                if (!(insn instanceof MethodInsnNode call) || classes.containsKey(call.owner)) {
                    continue;
                }
                Type[] parameters = Type.getArgumentTypes(call.desc);
                boolean runs = jdkFunctional(call.owner) && call.getOpcode() == Opcodes.INVOKEINTERFACE;
                boolean handsOn = false;
                for (Type parameter : parameters) {
                    handsOn |= jdkFunctional(parameter);
                }
                if (!runs && !handsOn) {
                    continue;
                }
                Frame<SourceValue> frame = frame(held, insn);
                if (frame == null) {
                    unfollowed.add(held + ": an unreadable call of " + call.owner.replace('/', '.') + "#" + call.name);
                    continue;
                }
                Trace trace = new Trace();
                int top = frame.getStackSize();
                if (runs) {
                    collect(held, frame.getStack(top - parameters.length - 1), trace, "the functional value it runs");
                }
                for (int index = 0; index < parameters.length; index++) {
                    if (jdkFunctional(parameters[index])) {
                        collect(held, frame.getStack(top - parameters.length + index), trace,
                                "the functional value it hands to " + call.owner.replace('/', '.') + "#" + call.name);
                    }
                }
                unfollowed.addAll(trace.unfollowed());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the answers

    Map<Method, Hold> heldScopes() {
        return scopes;
    }

    boolean reachesTheNetwork(Method method) {
        return towardTheNetwork.containsKey(method);
    }

    /**
     * ADR-0046's shape, as the code has it: the methods that make a transaction body and reach the network themselves, holding
     * nothing - the call made between two transactions, or beside one.
     */
    Set<Method> callingBesideATransaction() {
        Set<Method> found = new TreeSet<>();
        for (Method scope : scopes.keySet()) {
            if (!scope.name().startsWith("lambda$")) {
                continue;
            }
            for (Site site : sites.getOrDefault(scope, List.of())) {
                Method maker = site.caller();
                if (site.insn() instanceof InvokeDynamicInsnNode && !held(maker) && reachesTheNetwork(maker)) {
                    found.add(maker);
                }
            }
        }
        return found;
    }

    /** Whether a call to {@code owner.name} - any implementation of it, a lambda's among them - can reach the network. */
    boolean callReaches(String owner, String name, String descriptor) {
        return dispatch(owner, name, descriptor).stream().anyMatch(this::reachesTheNetwork)
                || implementingLambdas(owner, name, descriptor).stream().anyMatch(impl -> reachesTheNetwork(impl.body()));
    }

    boolean held(Method method) {
        return heldFrom.containsKey(method);
    }

    /** The calls from {@code start} to the network, or empty when it cannot reach it. */
    List<Method> pathToTheNetwork(Method start) {
        List<Method> path = new ArrayList<>();
        Method step = start;
        while (step != null && !step.equals(NETWORK) && path.size() < 64) {
            path.add(step);
            step = towardTheNetwork.get(step);
        }
        return step == null ? List.of() : path;
    }

    /** Every held scope that can reach the network, with its path. */
    Map<Method, List<Method>> violations() {
        Map<Method, List<Method>> found = new LinkedHashMap<>();
        scopes.keySet().stream().filter(this::reachesTheNetwork).forEach(scope -> found.put(scope, pathToTheNetwork(scope)));
        return found;
    }

    /** The functional values held code runs that could not be followed to where they were made. */
    Set<String> unfollowed() {
        return unfollowed;
    }

    Set<Method> methods() {
        return methods.keySet();
    }

    MethodNode node(Method method) {
        return methods.get(method);
    }

    long heldCount() {
        return heldFrom.size();
    }
}
