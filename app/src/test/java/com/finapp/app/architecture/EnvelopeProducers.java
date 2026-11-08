package com.finapp.app.architecture;

import com.finapp.sharedkernel.event.EventEnvelope;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Every event the production code can produce, read from the compiled code (`P15-TSK-026`).
 *
 * <h2>What it reads, and why from bytecode</h2>
 *
 * <p>An event exists because some method constructs an {@link EventEnvelope}, and that envelope's
 * {@code producer} names the topic it is published to ({@code KafkaEventPublisher.topicFor}). So
 * the set of produced topics is a property of the code, and the topic catalogue must be checked
 * against the code - never against a second list somebody keeps beside it, which would agree with
 * the catalogue by construction.
 *
 * <p>Every {@code new EventEnvelope(...)} in production output is found, jars included
 * ({@link MonitorInstructions#forEachProductionClass}), and its {@code producer} and
 * {@code eventType} arguments are resolved by a small simulation of the operand stack: a string
 * constant ({@code LDC}, which is what javac emits for a {@code static final String} constant
 * too) is tracked by value, everything else as unknown, and the stack is re-read from the class
 * file's own frames at every branch target. A producer that is not a compile-time constant at the
 * construction site comes back {@code null} - and the catalogue's test refuses it, because a
 * producer the build cannot read is a topic the catalogue cannot be checked against.
 *
 * <h2>What it does not see</h2>
 *
 * <p>An envelope built by reflection or deserialised from bytes. Production code does neither:
 * the outbox stores the envelope's fields and the relay re-reads them as a {@code PendingEvent},
 * whose producer is the one written here.
 */
final class EnvelopeProducers {

    /** One construction site and what it produces; a component is {@code null} when computed. */
    record Site(String module, String owner, String method, String producer, String eventType) {}

    private static final String ENVELOPE = Type.getInternalName(EventEnvelope.class);
    private static final Object UNKNOWN = new Object();

    /** The canonical constructor's descriptor: the record's components, in order. */
    private static final String CANONICAL;

    /** The argument positions of {@code producer} and {@code eventType}, from the record itself. */
    private static final int PRODUCER_ARGUMENT;

    private static final int EVENT_TYPE_ARGUMENT;

    static {
        RecordComponent[] components = EventEnvelope.class.getRecordComponents();
        Type[] types = new Type[components.length];
        int producer = -1;
        int eventType = -1;
        for (int i = 0; i < components.length; i++) {
            types[i] = Type.getType(components[i].getType());
            if (components[i].getName().equals("producer")) {
                producer = i;
            }
            if (components[i].getName().equals("eventType")) {
                eventType = i;
            }
        }
        if (producer < 0 || eventType < 0) {
            throw new IllegalStateException("EventEnvelope no longer has a producer and an eventType");
        }
        CANONICAL = Type.getMethodDescriptor(Type.VOID_TYPE, types);
        PRODUCER_ARGUMENT = producer;
        EVENT_TYPE_ARGUMENT = eventType;
    }

    private EnvelopeProducers() {}

    /** Every construction site in production output. */
    static List<Site> inProductionCode() {
        List<Site> sites = new ArrayList<>();
        MonitorInstructions.forEachProductionClass((module, bytes) -> sites.addAll(in(module, bytes)));
        return sites;
    }

    /** Every construction site in one class file's bytes - the seam the scanner's own tests use. */
    static List<Site> in(String module, byte[] bytes) {
        List<Site> sites = new ArrayList<>();
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
                                owner = name.replace('/', '.');
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                return new Simulation(module, owner, name, sites);
                            }
                        },
                        ClassReader.EXPAND_FRAMES);
        return sites;
    }

    /**
     * The operand stack of one method, one slot per entry: a long or a double takes two, as on the
     * JVM, so the {@code DUP2} family needs no special case. A slot holds the string constant that
     * produced it, or {@link #UNKNOWN}.
     */
    private static final class Simulation extends MethodVisitor {
        private final String module;
        private final String owner;
        private final String method;
        private final List<Site> sites;
        private final List<Object> stack = new ArrayList<>();

        /** False after a jump, a return or a throw, until the next frame says what follows. */
        private boolean reachable = true;

        Simulation(String module, String owner, String method, List<Site> sites) {
            super(Opcodes.ASM9);
            this.module = module;
            this.owner = owner;
            this.method = method;
            this.sites = sites;
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] frameStack) {
            // EXPAND_FRAMES: every frame is complete. A branch target's stack is the frame's, not
            // what the instructions above it left: re-read it, losing any constant (a constant
            // that arrives by two paths is not one the build can name).
            stack.clear();
            for (int i = 0; i < numStack; i++) {
                push(UNKNOWN);
                if (frameStack[i] == Opcodes.LONG || frameStack[i] == Opcodes.DOUBLE) {
                    push(UNKNOWN);
                }
            }
            reachable = true;
        }

        @Override
        public void visitInsn(int opcode) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.NOP -> {}
                case Opcodes.ACONST_NULL,
                        Opcodes.ICONST_M1,
                        Opcodes.ICONST_0,
                        Opcodes.ICONST_1,
                        Opcodes.ICONST_2,
                        Opcodes.ICONST_3,
                        Opcodes.ICONST_4,
                        Opcodes.ICONST_5,
                        Opcodes.FCONST_0,
                        Opcodes.FCONST_1,
                        Opcodes.FCONST_2 -> unknown(0, 1);
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 ->
                        unknown(0, 2);
                case Opcodes.IALOAD,
                        Opcodes.FALOAD,
                        Opcodes.AALOAD,
                        Opcodes.BALOAD,
                        Opcodes.CALOAD,
                        Opcodes.SALOAD -> unknown(2, 1);
                case Opcodes.LALOAD, Opcodes.DALOAD -> unknown(2, 2);
                case Opcodes.IASTORE,
                        Opcodes.FASTORE,
                        Opcodes.AASTORE,
                        Opcodes.BASTORE,
                        Opcodes.CASTORE,
                        Opcodes.SASTORE -> unknown(3, 0);
                case Opcodes.LASTORE, Opcodes.DASTORE -> unknown(4, 0);
                case Opcodes.POP, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> unknown(1, 0);
                case Opcodes.POP2 -> unknown(2, 0);
                case Opcodes.DUP -> {
                    Object a = pop();
                    pushAll(a, a);
                }
                case Opcodes.DUP_X1 -> {
                    Object a = pop();
                    Object b = pop();
                    pushAll(a, b, a);
                }
                case Opcodes.DUP_X2 -> {
                    Object a = pop();
                    Object b = pop();
                    Object c = pop();
                    pushAll(a, c, b, a);
                }
                case Opcodes.DUP2 -> {
                    Object a = pop();
                    Object b = pop();
                    pushAll(b, a, b, a);
                }
                case Opcodes.DUP2_X1 -> {
                    Object a = pop();
                    Object b = pop();
                    Object c = pop();
                    pushAll(b, a, c, b, a);
                }
                case Opcodes.DUP2_X2 -> {
                    Object a = pop();
                    Object b = pop();
                    Object c = pop();
                    Object d = pop();
                    pushAll(b, a, d, c, b, a);
                }
                case Opcodes.SWAP -> {
                    Object a = pop();
                    Object b = pop();
                    pushAll(a, b);
                }
                case Opcodes.IADD,
                        Opcodes.ISUB,
                        Opcodes.IMUL,
                        Opcodes.IDIV,
                        Opcodes.IREM,
                        Opcodes.IAND,
                        Opcodes.IOR,
                        Opcodes.IXOR,
                        Opcodes.ISHL,
                        Opcodes.ISHR,
                        Opcodes.IUSHR,
                        Opcodes.FADD,
                        Opcodes.FSUB,
                        Opcodes.FMUL,
                        Opcodes.FDIV,
                        Opcodes.FREM,
                        Opcodes.FCMPL,
                        Opcodes.FCMPG,
                        Opcodes.L2I,
                        Opcodes.L2F,
                        Opcodes.D2I,
                        Opcodes.D2F -> unknown(2, 1);
                case Opcodes.LADD,
                        Opcodes.LSUB,
                        Opcodes.LMUL,
                        Opcodes.LDIV,
                        Opcodes.LREM,
                        Opcodes.LAND,
                        Opcodes.LOR,
                        Opcodes.LXOR,
                        Opcodes.DADD,
                        Opcodes.DSUB,
                        Opcodes.DMUL,
                        Opcodes.DDIV,
                        Opcodes.DREM -> unknown(4, 2);
                case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> unknown(3, 2);
                case Opcodes.INEG,
                        Opcodes.FNEG,
                        Opcodes.I2F,
                        Opcodes.F2I,
                        Opcodes.I2B,
                        Opcodes.I2C,
                        Opcodes.I2S,
                        Opcodes.ARRAYLENGTH -> unknown(1, 1);
                case Opcodes.LNEG, Opcodes.DNEG, Opcodes.L2D, Opcodes.D2L -> unknown(2, 2);
                case Opcodes.I2L, Opcodes.I2D, Opcodes.F2L, Opcodes.F2D -> unknown(1, 2);
                case Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> unknown(4, 1);
                case Opcodes.IRETURN,
                        Opcodes.LRETURN,
                        Opcodes.FRETURN,
                        Opcodes.DRETURN,
                        Opcodes.ARETURN,
                        Opcodes.RETURN,
                        Opcodes.ATHROW -> reachable = false;
                default -> throw lost("opcode " + opcode);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!reachable) {
                return;
            }
            if (opcode == Opcodes.NEWARRAY) {
                unknown(1, 1);
            } else {
                unknown(0, 1); // BIPUSH, SIPUSH
            }
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> unknown(0, 1);
                case Opcodes.LLOAD, Opcodes.DLOAD -> unknown(0, 2);
                case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> unknown(1, 0);
                case Opcodes.LSTORE, Opcodes.DSTORE -> unknown(2, 0);
                default -> throw lost("variable opcode " + opcode);
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.NEW -> unknown(0, 1);
                case Opcodes.CHECKCAST -> {
                    // A cast leaves the value it casts; a constant survives it.
                    Object value = pop();
                    push(value);
                }
                default -> unknown(1, 1); // ANEWARRAY, INSTANCEOF
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
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
        public void visitMethodInsn(
                int opcode, String methodOwner, String name, String descriptor, boolean isInterface) {
            if (!reachable) {
                return;
            }
            int sizes = Type.getArgumentsAndReturnSizes(descriptor);
            int argumentSlots = (sizes >> 2) - (opcode == Opcodes.INVOKESTATIC ? 1 : 0);
            if (methodOwner.equals(ENVELOPE) && name.equals("<init>")) {
                record(descriptor);
            }
            unknown(argumentSlots, sizes & 0x3);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            if (!reachable) {
                return;
            }
            int sizes = Type.getArgumentsAndReturnSizes(descriptor);
            unknown((sizes >> 2) - 1, sizes & 0x3);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.GOTO -> reachable = false;
                case Opcodes.IF_ICMPEQ,
                        Opcodes.IF_ICMPNE,
                        Opcodes.IF_ICMPLT,
                        Opcodes.IF_ICMPGE,
                        Opcodes.IF_ICMPGT,
                        Opcodes.IF_ICMPLE,
                        Opcodes.IF_ACMPEQ,
                        Opcodes.IF_ACMPNE -> unknown(2, 0);
                case Opcodes.JSR -> throw lost("JSR, which no class file of this version holds");
                default -> unknown(1, 0); // IFEQ ... IFLE, IFNULL, IFNONNULL
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!reachable) {
                return;
            }
            if (value instanceof String constant) {
                push(constant);
            } else if (value instanceof Long || value instanceof Double) {
                unknown(0, 2);
            } else {
                unknown(0, 1);
            }
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            if (reachable) {
                unknown(1, 0);
                reachable = false;
            }
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            if (reachable) {
                unknown(1, 0);
                reachable = false;
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            if (reachable) {
                unknown(numDimensions, 1);
            }
        }

        private void record(String descriptor) {
            if (!descriptor.equals(CANONICAL)) {
                throw lost("an EventEnvelope constructor other than the canonical one: " + descriptor);
            }
            // Every component is one slot wide (references and ints), so argument i of n sits at
            // depth n - i from the top.
            int arguments = Type.getArgumentTypes(descriptor).length;
            Object producer = stack.get(stack.size() - (arguments - PRODUCER_ARGUMENT));
            Object eventType = stack.get(stack.size() - (arguments - EVENT_TYPE_ARGUMENT));
            sites.add(
                    new Site(
                            module,
                            owner,
                            method,
                            producer instanceof String p ? p : null,
                            eventType instanceof String t ? t : null));
        }

        private void unknown(int popped, int pushed) {
            for (int i = 0; i < popped; i++) {
                pop();
            }
            for (int i = 0; i < pushed; i++) {
                push(UNKNOWN);
            }
        }

        private void push(Object value) {
            stack.add(Objects.requireNonNull(value));
        }

        private void pushAll(Object... values) {
            for (Object value : values) {
                push(value);
            }
        }

        private Object pop() {
            if (stack.isEmpty()) {
                throw lost("an empty operand stack");
            }
            return stack.remove(stack.size() - 1);
        }

        private IllegalStateException lost(String what) {
            // Loud, never silent: a simulation that lost track would otherwise report a wrong
            // producer, or none, and the catalogue check would rest on it.
            return new IllegalStateException(
                    "EnvelopeProducers lost track of " + owner + "#" + method + ": " + what);
        }
    }
}
