package com.finapp.app.architecture;

import com.finapp.sharedkernel.event.EventEnvelope;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * What every event carries, read from the compiled code (`P15-TSK-027`).
 *
 * <h2>Why from the bytecode, and why across methods</h2>
 *
 * <p>The event catalogue must say what the code writes, and a list somebody keeps beside the code
 * would agree with the catalogue by construction. {@link EnvelopeProducers} already reads each
 * {@code new EventEnvelope(...)}, but the facts a contract needs are rarely all in that one method:
 * two thirds of the producers build the envelope in a private helper that takes the event type and
 * the payload as parameters ({@code publish(unitOfWork, type, payload)}), and the payload itself is
 * assembled by the caller, a ternary or a loop.
 *
 * <p>So this reader runs a small abstract interpretation over every production class - operand
 * stack and locals, joined at branch targets - and keeps, per method, what it did with an {@code
 * EventPayload} and what it passed to whom. A value is a set of possibilities: a string constant,
 * an int constant, the method's own parameter, a payload allocation, a call's result. A value that
 * depends on a parameter is resolved at each call site, up the call chain, so each write to the
 * outbox ends up as one or more closed {@link Flow}s: producer, event type, version and fields.
 *
 * <h2>What a field records</h2>
 *
 * <p>Its name, a {@code kind} read from how the value was made, and whether every path to the write
 * sets it. The kinds are {@code uuid}, {@code integer}, {@code decimal}, {@code boolean}, {@code
 * date}, {@code instant}, {@code currency}, {@code enum(<class>)}, {@code literal(<a|b>)} and {@code
 * identifier}, the last being any other string: the top of the lattice {@link EventCatalogue}
 * compares with. A numbered field written in a loop ({@code "reasonCode" + (i + 1)}) is catalogued
 * as {@code reasonCode#}, and is never required.
 *
 * <h2>What it refuses, and what it cannot see</h2>
 *
 * <p>Anything it cannot read comes back in {@link Result#unreadable()} and the catalogue's test
 * fails: a guess would be a wrong contract, and silence would be a hole. It does not see an event
 * written by reflection, nor a payload built other than through {@code EventPayload}; the second is
 * refused as unreadable. A field that keeps its name and kind but starts to carry a different
 * quantity is invisible to any reading of the code: that is review, and a new {@code eventVersion}.
 */
final class EventFlows {

    /** One closed write: a producer, a type, a version and the fields that path sets. */
    record Flow(
            String module,
            String owner,
            String method,
            String producer,
            String eventType,
            int version,
            String versionDeclaredFor,
            Map<String, Field> fields) {}

    /** A field as one path writes it. */
    record Field(String kind, boolean required) {}

    /** Every closed write, and everything that could not be read. */
    record Result(List<Flow> flows, List<String> unreadable) {}

    /** Resolves a per-type version declaration, {@code <owner>.of(<eventType>)}. */
    @FunctionalInterface
    interface VersionLookup {
        int versionOf(String declaringClass, String eventType);
    }

    private static final String ENVELOPE = Type.getInternalName(EventEnvelope.class);
    private static final String PAYLOAD = "com/finapp/platform/outbox/EventPayload";
    private static final String OUTBOX_WRITER = "com/finapp/platform/outbox/OutboxWriter";
    private static final String CURRENCY = "com/finapp/sharedkernel/money/CurrencyCode";
    private static final String STRING = "()Ljava/lang/String;";

    /** Above this a value is unknown: a real joined set is two or three constants. */
    private static final int MOST_POSSIBILITIES = 32;

    /** How far up the call chain a parameter is followed. */
    private static final int DEEPEST_CHAIN = 8;

    private static final int PRODUCER_ARGUMENT;
    private static final int EVENT_TYPE_ARGUMENT;
    private static final int EVENT_VERSION_ARGUMENT;

    static {
        var components = EventEnvelope.class.getRecordComponents();
        int producer = -1;
        int type = -1;
        int version = -1;
        for (int i = 0; i < components.length; i++) {
            switch (components[i].getName()) {
                case "producer" -> producer = i;
                case "eventType" -> type = i;
                case "eventVersion" -> version = i;
                default -> {}
            }
        }
        if (producer < 0 || type < 0 || version < 0) {
            throw new IllegalStateException("EventEnvelope changed shape: producer, eventType, eventVersion");
        }
        PRODUCER_ARGUMENT = producer;
        EVENT_TYPE_ARGUMENT = type;
        EVENT_VERSION_ARGUMENT = version;
    }

    // -----------------------------------------------------------------
    // The abstract values
    // -----------------------------------------------------------------

    /** The set of things a value may be; {@link #TOP} when the build cannot say. */
    private record Val(Set<Object> atoms) {
        static final Val TOP = new Val(null);

        static Val of(Object atom) {
            return new Val(Set.of(atom));
        }

        boolean isTop() {
            return atoms == null;
        }

        boolean has(Object atom) {
            return atoms != null && atoms.contains(atom);
        }

        Val join(Val other) {
            if (isTop() || other.isTop()) {
                return TOP;
            }
            if (atoms.equals(other.atoms)) {
                return this;
            }
            Set<Object> all = new HashSet<>(atoms);
            all.addAll(other.atoms);
            return all.size() > MOST_POSSIBILITIES ? TOP : new Val(Set.copyOf(all));
        }
    }

    private record Str(String value) {}

    private record Int(int value) {}

    private record Param(int index) {}

    private record Alloc(int id) {}

    private record Ret(int call) {}

    private record Bytes(Val payload) {}

    private record Env(int id) {}

    private record Kind(String kind) {}

    private record Concat(String recipe, List<Val> arguments) {}

    private record VersionOf(String declaringClass, Val eventType) {}

    // -----------------------------------------------------------------
    // What each method did
    // -----------------------------------------------------------------

    private record MethodKey(String owner, String name, String descriptor) {}

    private record With(Val target, Val name, Val value, int position) {}

    private record Write(Val envelope, Val payload, int position) {}

    private record Envelope(Val type, Val version, Val producer) {}

    private record Call(int id, MethodKey callee, List<Val> arguments, Facts caller, int position) {}

    private record Use(Val value, int position) {}

    private static final class Facts {
        final String module;
        final MethodKey key;
        final List<With> withs = new ArrayList<>();
        final List<Write> writes = new ArrayList<>();
        final Map<Integer, Envelope> envelopes = new LinkedHashMap<>();
        final List<Call> calls = new ArrayList<>();
        final List<Use> uses = new ArrayList<>();
        final Map<Integer, List<Integer>> successors = new HashMap<>();
        int nextAllocation;
        Val returned;
        boolean rebindsAConstantInALoop;

        Facts(String module, MethodKey key) {
            this.module = module;
            this.key = key;
        }

        void edge(int from, int to) {
            successors.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
        }

        /** Whether some path from the method's entry reaches a target without passing {@code position}. */
        boolean avoidable(int position, Set<Integer> targets) {
            if (targets.isEmpty() || position == 0) {
                return false;
            }
            Deque<Integer> queue = new ArrayDeque<>(List.of(0));
            Set<Integer> seen = new HashSet<>(queue);
            while (!queue.isEmpty()) {
                int at = queue.poll();
                if (targets.contains(at)) {
                    return true;
                }
                for (int next : successors.getOrDefault(at, List.of())) {
                    if (next != position && seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return false;
        }

        String where() {
            return key.owner().replace('/', '.') + "#" + key.name();
        }
    }

    // -----------------------------------------------------------------
    // Reading the classes
    // -----------------------------------------------------------------

    private final Map<MethodKey, Facts> facts = new LinkedHashMap<>();
    private final Map<MethodKey, List<Call>> callers = new HashMap<>();
    private final Map<String, Set<String>> supertypes = new HashMap<>();
    private final VersionLookup versions;

    private EventFlows(VersionLookup versions) {
        this.versions = Objects.requireNonNull(versions, "versions");
    }

    /** Every production class, through the walk the monitor sweep and the topic catalogue share. */
    static Result inProductionCode(VersionLookup versions) {
        EventFlows flows = new EventFlows(versions);
        MonitorInstructions.forEachProductionClass(flows::read);
        return flows.resolve();
    }

    /** One class file's bytes at a time - the seam the reader's own tests use. */
    static Result in(VersionLookup versions, List<byte[]> classFiles) {
        EventFlows flows = new EventFlows(versions);
        classFiles.forEach(bytes -> flows.read("probe", bytes));
        return flows.resolve();
    }

    private void read(String module, byte[] bytes) {
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            private String owner = "";

                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                owner = name;
                                Set<String> parents = new HashSet<>(Arrays.asList(interfaces));
                                if (superName != null) {
                                    parents.add(superName);
                                }
                                supertypes.put(name, parents);
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                MethodKey key = new MethodKey(owner, name, descriptor);
                                Facts method = new Facts(module, key);
                                facts.put(key, method);
                                return new Reader(method, (access & Opcodes.ACC_STATIC) != 0);
                            }
                        },
                        ClassReader.EXPAND_FRAMES);
    }

    /** The operand stack and the locals of one method. A long or a double takes two slots, as on the JVM. */
    private final class Reader extends MethodVisitor {
        private final Facts method;
        private List<Val> stack = new ArrayList<>();
        private Map<Integer, Val> locals = new HashMap<>();
        private final Map<Integer, Val> entryLocals;
        private final Map<Label, State> pending = new HashMap<>();
        private final Map<Label, State> visited = new HashMap<>();
        private final Map<Label, Integer> positions = new HashMap<>();
        private final List<Object[]> jumps = new ArrayList<>();
        private boolean reachable = true;
        private int position;
        private int fallsFrom = -1;

        Reader(Facts method, boolean isStatic) {
            super(Opcodes.ASM9);
            this.method = method;
            int slot = isStatic ? 0 : 1;
            Type[] parameters = Type.getArgumentTypes(method.key.descriptor());
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].getSize() == 1) {
                    locals.put(slot, Val.of(new Param(i)));
                }
                slot += parameters[i].getSize();
            }
            entryLocals = Map.copyOf(locals);
        }

        private record State(List<Val> stack, Map<Integer, Val> locals) {}

        private int step() {
            int at = position++;
            if (fallsFrom >= 0) {
                method.edge(fallsFrom, at);
            }
            fallsFrom = at;
            return at;
        }

        private void terminate() {
            fallsFrom = -1;
            reachable = false;
        }

        private void flowTo(Label label, int from) {
            jumps.add(new Object[] {from, label});
            State arrived = visited.get(label);
            if (arrived != null) {
                noticeRebinding(arrived);
            }
            State here = new State(new ArrayList<>(stack), new HashMap<>(locals));
            pending.merge(label, here, Reader::joined);
        }

        /**
         * A backward jump carries a value this single pass will not join into the loop's head. That
         * loses nothing for a payload, which is one object however often it is rebound, and nothing
         * for a counter; a string constant rebound in a loop could be a type this pass never sees.
         */
        private void noticeRebinding(State arrived) {
            arrived.locals.forEach(
                    (slot, value) -> {
                        boolean constant =
                                !value.isTop() && value.atoms().stream().anyMatch(Str.class::isInstance);
                        if (constant && !value.equals(locals.getOrDefault(slot, Val.TOP))) {
                            method.rebindsAConstantInALoop = true;
                        }
                    });
        }

        private static State joined(State a, State b) {
            if (a.stack.size() != b.stack.size()) {
                throw new IllegalStateException("operand stacks of different depth at one branch target");
            }
            List<Val> stack = new ArrayList<>(a.stack.size());
            for (int i = 0; i < a.stack.size(); i++) {
                stack.add(a.stack.get(i).join(b.stack.get(i)));
            }
            Map<Integer, Val> locals = new HashMap<>();
            a.locals.forEach(
                    (slot, value) -> {
                        Val other = b.locals.get(slot);
                        locals.put(slot, other == null ? Val.TOP : value.join(other));
                    });
            b.locals.keySet().forEach(slot -> locals.putIfAbsent(slot, Val.TOP));
            return new State(stack, locals);
        }

        @Override
        public void visitLabel(Label label) {
            positions.put(label, position);
            State arriving = pending.get(label);
            if (arriving != null) {
                State merged = reachable ? joined(new State(stack, locals), arriving) : arriving;
                stack = new ArrayList<>(merged.stack);
                locals = new HashMap<>(merged.locals);
                reachable = true;
            }
            visited.put(label, new State(List.copyOf(stack), Map.copyOf(locals)));
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] frameStack) {
            if (reachable) {
                return; // the join above already holds this state, and more precisely
            }
            // Reached by no jump this pass has seen: an exception handler. The caught exception is
            // on the stack and a parameter is still the parameter.
            stack = new ArrayList<>();
            for (int i = 0; i < numStack; i++) {
                push(Val.TOP);
                if (frameStack[i] == Opcodes.LONG || frameStack[i] == Opcodes.DOUBLE) {
                    push(Val.TOP);
                }
            }
            locals = new HashMap<>(entryLocals);
            reachable = true;
        }

        @Override
        public void visitInsn(int opcode) {
            int at = step();
            if (!reachable) {
                return;
            }
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                push(Val.of(new Int(opcode - Opcodes.ICONST_0)));
                return;
            }
            switch (opcode) {
                case Opcodes.NOP -> {}
                case Opcodes.ACONST_NULL, Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> unknown(0, 1);
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> unknown(0, 2);
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD ->
                        unknown(2, 1);
                case Opcodes.LALOAD, Opcodes.DALOAD -> unknown(2, 2);
                case Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE ->
                        unknown(3, 0);
                case Opcodes.LASTORE, Opcodes.DASTORE -> unknown(4, 0);
                case Opcodes.POP, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> unknown(1, 0);
                case Opcodes.POP2 -> unknown(2, 0);
                case Opcodes.DUP -> {
                    Val a = pop();
                    pushAll(a, a);
                }
                case Opcodes.DUP_X1 -> {
                    Val a = pop();
                    Val b = pop();
                    pushAll(a, b, a);
                }
                case Opcodes.DUP_X2 -> {
                    Val a = pop();
                    Val b = pop();
                    Val c = pop();
                    pushAll(a, c, b, a);
                }
                case Opcodes.DUP2 -> {
                    Val a = pop();
                    Val b = pop();
                    pushAll(b, a, b, a);
                }
                case Opcodes.DUP2_X1 -> {
                    Val a = pop();
                    Val b = pop();
                    Val c = pop();
                    pushAll(b, a, c, b, a);
                }
                case Opcodes.DUP2_X2 -> {
                    Val a = pop();
                    Val b = pop();
                    Val c = pop();
                    Val d = pop();
                    pushAll(b, a, d, c, b, a);
                }
                case Opcodes.SWAP -> {
                    Val a = pop();
                    Val b = pop();
                    pushAll(a, b);
                }
                case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR,
                        Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL,
                        Opcodes.FDIV, Opcodes.FREM, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.L2I, Opcodes.L2F, Opcodes.D2I,
                        Opcodes.D2F -> unknown(2, 1);
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LAND, Opcodes.LOR,
                        Opcodes.LXOR, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> unknown(4, 2);
                case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> unknown(3, 2);
                case Opcodes.INEG, Opcodes.FNEG, Opcodes.I2F, Opcodes.F2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                        Opcodes.ARRAYLENGTH -> unknown(1, 1);
                case Opcodes.LNEG, Opcodes.DNEG, Opcodes.L2D, Opcodes.D2L -> unknown(2, 2);
                case Opcodes.I2L, Opcodes.I2D, Opcodes.F2L, Opcodes.F2D -> unknown(1, 2);
                case Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> unknown(4, 1);
                case Opcodes.ARETURN -> {
                    Val value = pop();
                    method.returned = method.returned == null ? value : method.returned.join(value);
                    method.uses.add(new Use(value, at));
                    terminate();
                }
                case Opcodes.IRETURN -> {
                    Val value = pop();
                    method.returned = method.returned == null ? value : method.returned.join(value);
                    terminate();
                }
                case Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.RETURN, Opcodes.ATHROW -> terminate();
                default -> throw lost("opcode " + opcode);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            step();
            if (!reachable) {
                return;
            }
            if (opcode == Opcodes.NEWARRAY) {
                unknown(1, 1);
            } else {
                push(Val.of(new Int(operand)));
            }
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            step();
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> push(locals.getOrDefault(varIndex, Val.TOP));
                case Opcodes.LLOAD, Opcodes.DLOAD -> unknown(0, 2);
                case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> locals.put(varIndex, pop());
                case Opcodes.LSTORE, Opcodes.DSTORE -> {
                    unknown(2, 0);
                    locals.remove(varIndex);
                }
                default -> throw lost("variable opcode " + opcode);
            }
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            step();
            if (reachable) {
                locals.put(varIndex, Val.TOP);
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            step();
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.NEW -> {
                    if (type.equals(ENVELOPE)) {
                        int id = method.nextAllocation++;
                        push(Val.of(new Env(id)));
                    } else {
                        unknown(0, 1);
                    }
                }
                case Opcodes.CHECKCAST -> {}
                default -> unknown(1, 1); // ANEWARRAY, INSTANCEOF
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
            step();
            if (!reachable) {
                return;
            }
            int size = Type.getType(descriptor).getSize();
            switch (opcode) {
                case Opcodes.GETSTATIC -> unknown(0, size);
                case Opcodes.PUTSTATIC -> unknown(size, 0);
                case Opcodes.GETFIELD -> unknown(1, size);
                default -> unknown(1 + size, 0); // PUTFIELD
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            int at = step();
            if (!reachable) {
                return;
            }
            Type[] parameters = Type.getArgumentTypes(descriptor);
            List<Val> arguments = popArguments(parameters);
            Val receiver = opcode == Opcodes.INVOKESTATIC ? null : pop();
            Type returned = Type.getReturnType(descriptor);
            Val result = invoke(owner, name, descriptor, receiver, arguments, returned, at);
            if (returned.getSize() == 1) {
                push(result);
            } else if (returned.getSize() == 2) {
                unknown(0, 2);
            }
        }

        private List<Val> popArguments(Type[] parameters) {
            Val[] arguments = new Val[parameters.length];
            for (int i = parameters.length - 1; i >= 0; i--) {
                if (parameters[i].getSize() == 2) {
                    pop();
                    arguments[i] = pop();
                } else {
                    arguments[i] = pop();
                }
            }
            return Arrays.asList(arguments);
        }

        private Val invoke(
                String owner,
                String name,
                String descriptor,
                Val receiver,
                List<Val> arguments,
                Type returned,
                int at) {
            if (owner.equals(PAYLOAD)) {
                switch (name) {
                    case "of" -> {
                        return Val.of(new Alloc(method.nextAllocation++));
                    }
                    case "with" -> {
                        method.withs.add(new With(receiver, arguments.get(0), arguments.get(1), at));
                        return receiver;
                    }
                    case "toBytes" -> {
                        method.uses.add(new Use(receiver, at));
                        return Val.of(new Bytes(receiver));
                    }
                    default -> {
                        return Val.TOP;
                    }
                }
            }
            if (owner.equals(ENVELOPE) && name.equals("<init>")) {
                if (receiver.isTop() || receiver.atoms().size() != 1) {
                    throw lost("an envelope whose allocation was lost");
                }
                int id = ((Env) receiver.atoms().iterator().next()).id();
                method.envelopes.put(
                        id,
                        new Envelope(
                                arguments.get(EVENT_TYPE_ARGUMENT),
                                arguments.get(EVENT_VERSION_ARGUMENT),
                                arguments.get(PRODUCER_ARGUMENT)));
                return Val.TOP;
            }
            if (name.equals("write")
                    && descriptor.startsWith("(Ljava/lang/Object;L" + ENVELOPE + ";[B")
                    && isOutboxWriter(owner)) {
                method.writes.add(new Write(arguments.get(1), arguments.get(2), at));
                method.uses.add(new Use(arguments.get(2), at));
                return Val.TOP;
            }
            if (name.equals("of") && owner.endsWith("EventVersions") && returned.getSort() == Type.INT) {
                return Val.of(new VersionOf(owner.replace('/', '.'), arguments.get(0)));
            }
            String kind = kindOf(owner, name, descriptor);
            if (kind != null) {
                return Val.of(new Kind(kind));
            }
            Call call = new Call(method.calls.size(), new MethodKey(owner, name, descriptor), arguments, method, at);
            method.calls.add(call);
            callers.computeIfAbsent(call.callee(), ignored -> new ArrayList<>()).add(call);
            arguments.forEach(argument -> method.uses.add(new Use(argument, at)));
            int sort = returned.getSort();
            return sort == Type.OBJECT || sort == Type.ARRAY || sort == Type.INT ? Val.of(new Ret(call.id())) : Val.TOP;
        }

        private boolean isOutboxWriter(String owner) {
            return owner.equals(OUTBOX_WRITER) || owner.endsWith("OutboxWriter");
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            int at = step();
            if (!reachable) {
                return;
            }
            Type[] captured = Type.getArgumentTypes(descriptor);
            List<Val> arguments = popArguments(captured);
            Type returned = Type.getReturnType(descriptor);
            String bootstrap = bootstrapMethodHandle.getName();
            Val result = Val.TOP;
            if (bootstrap.equals("makeConcatWithConstants")) {
                boolean floating =
                        Arrays.stream(captured).anyMatch(type -> type == Type.DOUBLE_TYPE || type == Type.FLOAT_TYPE);
                result = floating
                        ? Val.of(new Kind("decimal"))
                        : Val.of(new Concat((String) bootstrapMethodArguments[0], List.copyOf(nonNull(arguments))));
            } else if (bootstrap.equals("metafactory") || bootstrap.equals("altMetafactory")) {
                captureInto((Handle) bootstrapMethodArguments[1], arguments, at);
            }
            if (returned.getSize() == 1) {
                push(result);
            } else if (returned.getSize() == 2) {
                unknown(0, 2);
            }
        }

        private List<Val> nonNull(List<Val> values) {
            return values.stream().map(value -> value == null ? Val.TOP : value).toList();
        }

        /** A lambda's body is a call whose arguments are what it captured, then what it is later given. */
        private void captureInto(Handle implementation, List<Val> capturedValues, int at) {
            boolean instance = implementation.getTag() != Opcodes.H_INVOKESTATIC;
            List<Val> arguments =
                    instance && !capturedValues.isEmpty()
                            ? capturedValues.subList(1, capturedValues.size())
                            : capturedValues;
            MethodKey callee = new MethodKey(implementation.getOwner(), implementation.getName(), implementation.getDesc());
            Call call = new Call(method.calls.size(), callee, List.copyOf(nonNull(arguments)), method, at);
            method.calls.add(call);
            callers.computeIfAbsent(callee, ignored -> new ArrayList<>()).add(call);
            arguments.forEach(argument -> method.uses.add(new Use(argument, at)));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            int at = step();
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.GOTO -> {}
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                        Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> unknown(2, 0);
                case Opcodes.JSR -> throw lost("JSR, which no class file of this version holds");
                default -> unknown(1, 0); // IFEQ ... IFLE, IFNULL, IFNONNULL
            }
            flowTo(label, at);
            if (opcode == Opcodes.GOTO) {
                terminate();
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            step();
            if (!reachable) {
                return;
            }
            if (value instanceof String constant) {
                push(Val.of(new Str(constant)));
            } else if (value instanceof Integer constant) {
                push(Val.of(new Int(constant)));
            } else if (value instanceof Long || value instanceof Double) {
                unknown(0, 2);
            } else {
                unknown(0, 1);
            }
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            switched(dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            switched(dflt, labels);
        }

        private void switched(Label dflt, Label[] labels) {
            int at = step();
            if (!reachable) {
                return;
            }
            unknown(1, 0);
            flowTo(dflt, at);
            for (Label label : labels) {
                flowTo(label, at);
            }
            terminate();
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            step();
            if (reachable) {
                unknown(numDimensions, 1);
            }
        }

        @Override
        public void visitEnd() {
            for (Object[] jump : jumps) {
                Integer target = positions.get((Label) jump[1]);
                if (target != null) {
                    method.edge((Integer) jump[0], target);
                }
            }
        }

        private void unknown(int popped, int pushed) {
            for (int i = 0; i < popped; i++) {
                pop();
            }
            for (int i = 0; i < pushed; i++) {
                push(Val.TOP);
            }
        }

        private void push(Val value) {
            stack.add(Objects.requireNonNull(value));
        }

        private void pushAll(Val... values) {
            for (Val value : values) {
                push(value);
            }
        }

        private Val pop() {
            if (stack.isEmpty()) {
                throw lost("an empty operand stack");
            }
            return stack.remove(stack.size() - 1);
        }

        private IllegalStateException lost(String what) {
            return new IllegalStateException("EventFlows lost track of " + method.where() + ": " + what);
        }
    }

    /** The kind a call's result has, or {@code null} when the call says nothing about it. */
    private static String kindOf(String owner, String name, String descriptor) {
        switch (owner) {
            case "java/lang/String" -> {
                if (name.equals("valueOf")) {
                    return switch (descriptor) {
                        case "(J)Ljava/lang/String;", "(I)Ljava/lang/String;" -> "integer";
                        case "(D)Ljava/lang/String;", "(F)Ljava/lang/String;" -> "decimal";
                        case "(Z)Ljava/lang/String;" -> "boolean";
                        default -> null;
                    };
                }
                return null;
            }
            case "java/lang/Long", "java/lang/Integer", "java/lang/Short", "java/lang/Byte", "java/math/BigInteger" -> {
                return name.equals("toString") ? "integer" : null;
            }
            case "java/lang/Double", "java/lang/Float" -> {
                return name.equals("toString") ? "decimal" : null;
            }
            case "java/math/BigDecimal" -> {
                return name.equals("toString") || name.equals("toPlainString") || name.equals("toEngineeringString")
                        ? "decimal"
                        : null;
            }
            case "java/lang/Boolean" -> {
                return name.equals("toString") ? "boolean" : null;
            }
            case "java/util/UUID" -> {
                return name.equals("toString") ? "uuid" : null;
            }
            case "java/time/LocalDate" -> {
                return name.equals("toString") ? "date" : null;
            }
            case "java/time/Instant" -> {
                return name.equals("toString") ? "instant" : null;
            }
            case CURRENCY -> {
                return name.equals("code") ? "currency" : null;
            }
            default -> {
                return name.equals("name") && descriptor.equals(STRING) && isEnum(owner)
                        ? "enum(" + owner.replace('/', '.') + ")"
                        : null;
            }
        }
    }

    private static boolean isEnum(String owner) {
        try {
            return Class.forName(owner.replace('/', '.'), false, EventFlows.class.getClassLoader()).isEnum();
        } catch (ClassNotFoundException | LinkageError unknown) {
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Resolving a write up its call chain
    // -----------------------------------------------------------------

    /** A method in a call chain: its caller's frame and the call that entered it, once known. */
    private static final class Frame {
        final Facts facts;
        Frame parent;
        Call via;

        Frame(Facts facts) {
            this.facts = facts;
        }

        Frame(Facts facts, Frame parent, Call via) {
            this.facts = facts;
            this.parent = parent;
            this.via = via;
        }

        Frame root() {
            Frame frame = this;
            while (frame.parent != null) {
                frame = frame.parent;
            }
            return frame;
        }

        int depth() {
            int depth = 0;
            for (Frame frame = this; frame.parent != null; frame = frame.parent) {
                depth++;
            }
            return depth;
        }
    }

    /** Thrown when a value depends on a parameter of the outermost method: a caller must say. */
    private static final class OpenParameter extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        OpenParameter() {
            super(null, null, false, false);
        }
    }

    private Result resolve() {
        List<Flow> flows = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Facts method : facts.values()) {
            if (method.rebindsAConstantInALoop && !method.writes.isEmpty()) {
                unreadable.add(method.where() + ": a string constant is rebound in a loop, which this reader does not follow");
            }
            for (Write write : method.writes) {
                Frame frame = new Frame(method);
                expand(frame, write, frame, flows, unreadable);
            }
        }
        return new Result(List.copyOf(flows), List.copyOf(new TreeSet<>(unreadable)));
    }

    private void expand(Frame frame, Write write, Frame start, List<Flow> flows, List<String> unreadable) {
        try {
            close(frame, write, flows, unreadable);
        } catch (OpenParameter open) {
            Frame root = frame.root();
            List<Call> calls = callsTo(root.facts.key);
            if (calls.isEmpty() || frame.depth() >= DEEPEST_CHAIN) {
                unreadable.add(start.facts.where() + ": a value depends on a parameter no caller in production names");
                return;
            }
            for (Call call : calls) {
                if (inChain(frame, call.caller())) {
                    continue; // recursion adds no value the chain does not already hold
                }
                root.parent = new Frame(call.caller());
                root.via = call;
                expand(frame, write, start, flows, unreadable);
                root.parent = null;
                root.via = null;
            }
        }
    }

    private static boolean inChain(Frame frame, Facts method) {
        for (Frame at = frame; at != null; at = at.parent) {
            if (at.facts == method) {
                return true;
            }
        }
        return false;
    }

    private List<Call> callsTo(MethodKey key) {
        List<Call> calls = new ArrayList<>(callers.getOrDefault(key, List.of()));
        for (String ancestor : ancestorsOf(key.owner())) {
            calls.addAll(callers.getOrDefault(new MethodKey(ancestor, key.name(), key.descriptor()), List.of()));
        }
        return calls;
    }

    private Set<String> ancestorsOf(String type) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(supertypes.getOrDefault(type, Set.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(supertypes.getOrDefault(next, Set.of()));
            }
        }
        return seen;
    }

    /** The method a call lands in, looking up the class hierarchy for an inherited one. */
    private Facts target(MethodKey key) {
        Facts direct = facts.get(key);
        if (direct != null) {
            return direct;
        }
        for (String ancestor : ancestorsOf(key.owner())) {
            Facts inherited = facts.get(new MethodKey(ancestor, key.name(), key.descriptor()));
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private void close(Frame frame, Write write, List<Flow> flows, List<String> unreadable) {
        List<EnvelopeAt> envelopes = new ArrayList<>();
        collectEnvelopes(write.envelope(), frame, envelopes, new HashSet<>(), unreadable);
        if (envelopes.isEmpty()) {
            return;
        }
        for (EnvelopeAt at : envelopes) {
            Envelope envelope = at.envelope();
            Set<String> types = strings(envelope.type(), at.frame());
            Set<String> producers = strings(envelope.producer(), at.frame());
            if (types == null || producers == null || producers.size() != 1) {
                unreadable.add(at.frame().root().facts.where() + ": an event type or producer the build cannot read");
                continue;
            }
            String producer = producers.iterator().next();
            Map<String, Observed> observed = new TreeMap<>();
            if (!collectBytes(write.payload(), frame, observed, unreadable)) {
                continue;
            }
            for (String type : types) {
                VersionRead version = version(envelope.version(), at.frame(), type);
                if (version == null) {
                    unreadable.add(at.frame().root().facts.where() + ": the version of " + type + " is not a constant");
                    continue;
                }
                Map<String, Field> fields = new TreeMap<>();
                observed.forEach((name, seen) -> fields.put(name, new Field(mergedKind(seen.kinds), seen.required)));
                flows.add(
                        new Flow(
                                at.frame().facts.module,
                                at.frame().facts.key.owner().replace('/', '.'),
                                at.frame().facts.key.name(),
                                producer,
                                type,
                                version.version,
                                version.declaredFor,
                                Map.copyOf(fields)));
            }
        }
    }

    private record EnvelopeAt(Envelope envelope, Frame frame) {}

    private record VersionRead(int version, String declaredFor) {}

    private VersionRead version(Val value, Frame frame, String eventType) {
        if (value.isTop()) {
            return null;
        }
        VersionRead read = null;
        for (Object atom : value.atoms()) {
            VersionRead one;
            if (atom instanceof Int constant) {
                one = new VersionRead(constant.value(), null);
            } else if (atom instanceof VersionOf declared) {
                Set<String> arguments = strings(declared.eventType(), frame);
                if (arguments == null || arguments.isEmpty()) {
                    return null;
                }
                String declaredFor = arguments.contains(eventType) ? eventType : arguments.iterator().next();
                one = new VersionRead(versions.versionOf(declared.declaringClass(), declaredFor), declaredFor);
            } else if (atom instanceof Param parameter) {
                if (frame.parent == null) {
                    throw new OpenParameter();
                }
                one = version(argument(frame, parameter.index()), frame.parent, eventType);
            } else if (atom instanceof Ret call) {
                Call site = frame.facts.calls.get(call.call());
                Facts callee = target(site.callee());
                if (callee == null || callee.returned == null) {
                    return null;
                }
                one = version(callee.returned, new Frame(callee, frame, site), eventType);
            } else {
                return null;
            }
            if (one == null || (read != null && !read.equals(one))) {
                return null;
            }
            read = one;
        }
        return read;
    }

    private static Val argument(Frame frame, int index) {
        return index < frame.via.arguments().size() ? frame.via.arguments().get(index) : Val.TOP;
    }

    /** Every constant a value may be, or {@code null} when one possibility is not a constant. */
    private Set<String> strings(Val value, Frame frame) {
        if (value.isTop()) {
            return null;
        }
        Set<String> found = new TreeSet<>();
        for (Object atom : value.atoms()) {
            Set<String> more;
            if (atom instanceof Str constant) {
                more = Set.of(constant.value());
            } else if (atom instanceof Param parameter) {
                if (frame.parent == null) {
                    throw new OpenParameter();
                }
                more = strings(argument(frame, parameter.index()), frame.parent);
            } else if (atom instanceof Ret call) {
                Call site = frame.facts.calls.get(call.call());
                Facts callee = target(site.callee());
                if (callee == null || callee.returned == null) {
                    return null;
                }
                more = strings(callee.returned, new Frame(callee, frame, site));
            } else {
                return null;
            }
            if (more == null) {
                return null;
            }
            found.addAll(more);
        }
        return found;
    }

    private void collectEnvelopes(
            Val value, Frame frame, List<EnvelopeAt> out, Set<Object> seen, List<String> unreadable) {
        if (value.isTop()) {
            unreadable.add(frame.facts.where() + ": an envelope written that this reader did not see built");
            return;
        }
        for (Object atom : value.atoms()) {
            if (atom instanceof Env env) {
                out.add(new EnvelopeAt(frame.facts.envelopes.get(env.id()), frame));
            } else if (atom instanceof Param parameter) {
                if (frame.parent == null) {
                    throw new OpenParameter();
                }
                collectEnvelopes(argument(frame, parameter.index()), frame.parent, out, seen, unreadable);
            } else if (atom instanceof Ret call) {
                Call site = frame.facts.calls.get(call.call());
                Facts callee = target(site.callee());
                if (callee == null || callee.returned == null) {
                    unreadable.add(frame.facts.where() + ": an envelope returned by a call this reader cannot follow");
                    return;
                }
                collectEnvelopes(callee.returned, new Frame(callee, frame, site), out, seen, unreadable);
            } else {
                unreadable.add(frame.facts.where() + ": an envelope written that this reader did not see built");
                return;
            }
        }
    }

    private boolean collectBytes(Val value, Frame frame, Map<String, Observed> out, List<String> unreadable) {
        if (value.isTop()) {
            unreadable.add(frame.facts.where() + ": a payload written that EventPayload did not build");
            return false;
        }
        boolean read = true;
        for (Object atom : value.atoms()) {
            if (atom instanceof Bytes bytes) {
                read &= collectPayload(bytes.payload(), frame, out, unreadable);
            } else if (atom instanceof Param parameter) {
                if (frame.parent == null) {
                    throw new OpenParameter();
                }
                read &= collectBytes(argument(frame, parameter.index()), frame.parent, out, unreadable);
            } else if (atom instanceof Ret call) {
                Call site = frame.facts.calls.get(call.call());
                Facts callee = target(site.callee());
                if (callee == null || callee.returned == null) {
                    unreadable.add(frame.facts.where() + ": a payload from a call this reader cannot follow");
                    return false;
                }
                read &= collectBytes(callee.returned, new Frame(callee, frame, site), out, unreadable);
            } else {
                unreadable.add(frame.facts.where() + ": a payload written that EventPayload did not build");
                return false;
            }
        }
        return read;
    }

    private boolean collectPayload(Val value, Frame frame, Map<String, Observed> out, List<String> unreadable) {
        Fields fields = new Fields(unreadable);
        boolean read = fields.collect(value, frame, false);
        fields.observed.forEach(
                (name, seen) ->
                        out.merge(name, seen, (a, b) -> a.join(b)));
        return read;
    }

    /** A field's sightings across the paths of one payload. */
    private static final class Observed {
        final Set<String> kinds = new TreeSet<>();
        boolean required;

        Observed(String kind, boolean required) {
            this.kinds.add(kind);
            this.required = required;
        }

        Observed join(Observed other) {
            // Seen twice on one payload's graph: through a helper and its caller, for instance.
            kinds.addAll(other.kinds);
            required |= other.required;
            return this;
        }
    }

    private final class Fields {
        final Map<String, Observed> observed = new TreeMap<>();
        final Set<List<Object>> visited = new HashSet<>();
        final List<String> unreadable;

        Fields(List<String> unreadable) {
            this.unreadable = unreadable;
        }

        boolean collect(Val value, Frame frame, boolean conditional) {
            if (value.isTop()) {
                unreadable.add(frame.facts.where() + ": a payload this reader lost track of");
                return false;
            }
            boolean read = true;
            for (Object atom : value.atoms()) {
                if (!visited.add(List.of(System.identityHashCode(frame), atom))) {
                    continue;
                }
                read &= collectLocal(atom, frame, conditional);
                if (atom instanceof Param parameter) {
                    if (frame.parent == null) {
                        throw new OpenParameter();
                    }
                    read &= collect(argument(frame, parameter.index()), frame.parent, conditional);
                } else if (atom instanceof Ret call) {
                    Call site = frame.facts.calls.get(call.call());
                    Facts callee = target(site.callee());
                    if (callee == null || callee.returned == null) {
                        unreadable.add(frame.facts.where() + ": a payload from a call this reader cannot follow");
                        return false;
                    }
                    read &= collect(callee.returned, new Frame(callee, frame, site), conditional);
                } else if (!(atom instanceof Alloc)) {
                    unreadable.add(frame.facts.where() + ": a payload this reader lost track of");
                    return false;
                }
            }
            return read;
        }

        /** Whatever this method does to the object: its own {@code with}s, and helpers it hands the object to. */
        private boolean collectLocal(Object atom, Frame frame, boolean conditional) {
            boolean read = true;
            Set<Integer> consumed = consumedAt(atom, frame.facts);
            for (With with : frame.facts.withs) {
                if (with.target().has(atom)) {
                    boolean optional = conditional || frame.facts.avoidable(with.position(), consumed);
                    read &= record(with, frame, optional);
                }
            }
            for (Call call : frame.facts.calls) {
                for (int i = 0; i < call.arguments().size(); i++) {
                    if (!call.arguments().get(i).has(atom)) {
                        continue;
                    }
                    Facts callee = target(call.callee());
                    if (callee == null) {
                        if (call.callee().owner().startsWith("com/finapp/")) {
                            unreadable.add(frame.facts.where() + ": a payload handed to a method this reader cannot follow");
                            read = false;
                        }
                        continue; // the JDK adds no field to an EventPayload
                    }
                    Set<Integer> others = new HashSet<>(consumed);
                    others.remove(call.position());
                    boolean optional = conditional || frame.facts.avoidable(call.position(), others);
                    read &= collectLocalOnly(new Param(i), new Frame(callee, frame, call), optional);
                }
            }
            return read;
        }

        private boolean collectLocalOnly(Object atom, Frame frame, boolean conditional) {
            if (!visited.add(List.of(System.identityHashCode(frame), atom))) {
                return true;
            }
            return collectLocal(atom, frame, conditional);
        }

        private Set<Integer> consumedAt(Object atom, Facts method) {
            Set<Integer> positions = new HashSet<>();
            for (Use use : method.uses) {
                if (use.value().has(atom)) {
                    positions.add(use.position());
                } else if (!use.value().isTop()) {
                    for (Object other : use.value().atoms()) {
                        if (other instanceof Bytes bytes && bytes.payload().has(atom)) {
                            positions.add(use.position());
                        }
                    }
                }
            }
            return positions;
        }

        private boolean record(With with, Frame frame, boolean optional) {
            String name = fieldName(with.name(), frame);
            if (name == null) {
                unreadable.add(frame.facts.where() + ": a payload field name the build cannot read");
                return false;
            }
            boolean numbered = name.endsWith("#");
            Observed seen = new Observed(fieldKind(with.value(), frame), !optional && !numbered);
            observed.merge(name, seen, Observed::join);
            return true;
        }
    }

    /** A constant name, or a constant stem with a computed number after it: {@code reasonCode#}. */
    private String fieldName(Val value, Frame frame) {
        if (value.isTop() || value.atoms().size() != 1) {
            return null;
        }
        Object atom = value.atoms().iterator().next();
        if (atom instanceof Str constant) {
            return constant.value();
        }
        if (atom instanceof Param parameter) {
            if (frame.parent == null) {
                throw new OpenParameter();
            }
            return fieldName(argument(frame, parameter.index()), frame.parent);
        }
        if (atom instanceof Concat concat) {
            String recipe = concat.recipe();
            int placeholder = recipe.indexOf('\u0001');
            if (placeholder > 0 && placeholder == recipe.length() - 1) {
                return recipe.substring(0, placeholder) + "#";
            }
            if (placeholder == 0 && concat.arguments().size() == 1) {
                String stem = fieldName(concat.arguments().get(0), frame);
                return stem == null ? null : stem + recipe.substring(1);
            }
        }
        return null;
    }

    private String fieldKind(Val value, Frame frame) {
        return mergedKind(fieldKinds(value, frame, 0));
    }

    private Set<String> fieldKinds(Val value, Frame frame, int depth) {
        if (value.isTop() || depth > DEEPEST_CHAIN) {
            return Set.of("identifier");
        }
        Set<String> kinds = new TreeSet<>();
        for (Object atom : value.atoms()) {
            if (atom instanceof Str constant) {
                kinds.add("literal(" + constant.value() + ")");
            } else if (atom instanceof Kind kind) {
                kinds.add(kind.kind());
            } else if (atom instanceof Param parameter && frame.parent != null) {
                kinds.addAll(fieldKinds(argument(frame, parameter.index()), frame.parent, depth + 1));
            } else if (atom instanceof Ret call) {
                Call site = frame.facts.calls.get(call.call());
                Facts callee = target(site.callee());
                if (callee == null || callee.returned == null) {
                    kinds.add("identifier");
                } else {
                    kinds.addAll(fieldKinds(callee.returned, new Frame(callee, frame, site), depth + 1));
                }
            } else {
                kinds.add("identifier");
            }
        }
        return kinds;
    }

    /**
     * One kind for a set of sightings: one sighting stands; literals join into one; a literal beside
     * a kind takes the kind when the kind can spell it; anything else is an {@code identifier}.
     */
    static String mergedKind(Set<String> kinds) {
        Set<String> literals = new TreeSet<>();
        Set<String> others = new TreeSet<>();
        for (String kind : kinds) {
            if (kind.startsWith("literal(")) {
                for (String value : kind.substring("literal(".length(), kind.length() - 1).split("\\|", -1)) {
                    literals.add(value);
                }
            } else {
                others.add(kind);
            }
        }
        if (others.isEmpty()) {
            return "literal(" + String.join("|", literals) + ")";
        }
        if (others.size() > 1) {
            return "identifier";
        }
        String only = others.iterator().next();
        if (literals.isEmpty() || only.startsWith("enum(")) {
            return only;
        }
        return only.equals("integer") && literals.stream().allMatch(value -> value.matches("-?[0-9]+"))
                ? only
                : "identifier";
    }

    /** For a caller that wants the construction sites this reader saw closed. */
    static void forEachSite(Result result, Consumer<String> sink) {
        result.flows().forEach(flow -> sink.accept(flow.owner() + "#" + flow.method()));
    }
}
