package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

/**
 * An amount never reaches a log line through production code (`P15-TSK-023`; the lending privacy residue A5-08, {@code Money.toString};
 * ADR-0019, ADR-0072, {@code INV-AUD-02}).
 *
 * <h2>Why a guard and not a redacted {@code Money.toString}</h2>
 *
 * <p>{@code Money}'s string form round-trips the exact value, and a value type's string form is read across every module: a redaction
 * there is a change nobody can measure without every suite of the fleet, in a phase that changes no financial output. So the property is
 * held at the call: <strong>no production instruction hands a logger or a formatter</strong> a value whose static type is {@code Money},
 * or one whose string form prints one - a record with an amount-bearing component and the generated form, or a class whose own
 * {@code toString} turns an amount into text - directly or as an element of the {@code Object[]} a logger's or formatter's varargs is.
 * A span's attributes leave the platform through the {@code Spans} port alone, which admits a UUID and nothing else.
 *
 * <h2>The residue it holds and does not clear</h2>
 *
 * <p>Read with the same analyser, thirteen classes turn an amount into text by concatenation or {@code String.valueOf}: every one of
 * them in an exception's message for a state that must not happen (an overflow, bounds misconfigured, a fee above its gross) or in a
 * Lombok builder's generated {@code toString}. Such a message reaches no error body (`ApiErrorHandlerTest`) and no exported span
 * (the collector drops an exception's message and a span's status message), but an unhandled one reaches the error log's stack trace:
 * the collector scrubs {@code Money}'s string form from the shipped record, and the pod's console keeps it. {@link #theTextResidueIsHeld}
 * holds that set so it cannot grow unnoticed; clearing it is {@code THREAT_MODEL.md} section 6 item 37 (c).
 *
 * <p><strong>Not seen</strong>: a collection's element type ({@code List<DueItem>} is a {@code List} here), a value cast to
 * {@code Object} before it is handed on, a method reference, and an amount already a plain {@code BigDecimal} or {@code long}.
 */
@DisplayName("no production code hands an amount to a logger or a formatter; the text residue is held (P15-TSK-023)")
class MoneyReachesNoTextSinkTest {

    static final String MONEY = "com/finapp/sharedkernel/money/Money";

    /**
     * Each instruction that turns an amount into text in an exception's message or a builder's form, as {@code class.method: what}
     * (one line per instruction, so a second in the same method is a second line): section 6 item 37 (c).
     */
    static final List<String> TEXT_RESIDUE = valueOfs(
            "com/finapp/bnpl/BnplTerms$BnplTermsBuilder.toString", 3,
            "com/finapp/fx/ConversionPlan.compute", 8,
            "com/finapp/fx/ConversionPlan.requireExact", 1,
            "com/finapp/fx/NotionalBounds.<init>", 4,
            "com/finapp/lending/LoanTerms$LoanTermsBuilder.toString", 2,
            "com/finapp/merchant/FeeAssessment.<init>", 3,
            "com/finapp/merchant/FeeCalculation.refundedAfter", 2,
            "com/finapp/merchant/FeeScheduleVersion.<init>", 1,
            "com/finapp/merchant/FeeScheduleVersion.toString", 1,
            "com/finapp/merchant/FeeSchedules.addVersion", 1,
            "com/finapp/merchant/MerchantSettlement.pin", 2,
            "com/finapp/merchant/MerchantSettlement.refundTerms", 1,
            "com/finapp/merchant/MerchantSettlement.settle", 2,
            "com/finapp/merchant/PaymentFeePin.<init>", 1,
            "com/finapp/payments/RefundReservation.<init>", 2,
            "com/finapp/payments/RefundSettlement.<init>", 2,
            "com/finapp/sharedkernel/money/Money.negated", 1,
            "com/finapp/sharedkernel/money/Money.times", 1);

    /** {@code method, count, ...}: that many instructions of each method handing a {@code Money} to {@code String.valueOf}. */
    private static List<String> valueOfs(Object... methodsAndCounts) {
        List<String> findings = new ArrayList<>();
        for (int i = 0; i < methodsAndCounts.length; i += 2) {
            for (int n = 0; n < (Integer) methodsAndCounts[i + 1]; n++) {
                findings.add(methodsAndCounts[i] + ": hands a com.finapp.sharedkernel.money.Money to java/lang/String.valueOf");
            }
        }
        return List.copyOf(findings);
    }

    /** The thirteen classes those instructions are in. */
    static final Set<String> TEXT_CLASSES = Set.of(
            "com/finapp/sharedkernel/money/Money",
            "com/finapp/fx/NotionalBounds",
            "com/finapp/fx/ConversionPlan",
            "com/finapp/merchant/FeeScheduleVersion",
            "com/finapp/merchant/FeeSchedules",
            "com/finapp/merchant/MerchantSettlement",
            "com/finapp/merchant/FeeCalculation",
            "com/finapp/merchant/PaymentFeePin",
            "com/finapp/merchant/FeeAssessment",
            "com/finapp/payments/RefundSettlement",
            "com/finapp/payments/RefundReservation",
            "com/finapp/lending/LoanTerms$LoanTermsBuilder",
            "com/finapp/bnpl/BnplTerms$BnplTermsBuilder");

    private static Map<String, ClassNode> production;
    private static Set<String> bearing;

    @BeforeAll
    static void read() {
        production = new HashMap<>();
        for (Path entry : ProductionModules.mainOutputEntries().keySet()) {
            classesOf(entry).forEach(node -> production.put(node.name, node));
        }
        bearing = bearing(production);
    }

    @Test
    @DisplayName("every production class was read; Money is amount-bearing and the lending records that printed it no longer are")
    void theScopeWasRead() {
        assertThat(production).as("every production class on the classpath").hasSizeGreaterThan(5_000);
        assertThat(bearing).contains(MONEY)
                .as("a class whose own form prints an amount is one").contains("com/finapp/merchant/FeeScheduleVersion")
                .as("a record that redacts its own form is not")
                .doesNotContain("com/finapp/creditmath/DueState$DueItem", "com/finapp/lending/LoanAmendments$Accepted",
                        "com/finapp/lending/LoanAmendments$Plan", "com/finapp/lending/JdbcRepaymentStore$Head");
    }

    @Test
    @DisplayName("no production instruction hands an amount, or what prints one, to a logger, a formatter or their Object[]")
    void noProductionCodeHandsAnAmountToALogger() {
        List<String> findings = new ArrayList<>();
        for (ClassNode node : production.values()) {
            findings.addAll(sinks(node, bearing, true));
        }
        assertThat(findings).as("each is an amount on its way to a log line: hand it an identifier, or redact the form").isEmpty();
    }

    @Test
    @DisplayName("the classes that turn an amount into text by concatenation are the known residue, each in an exception's message or a builder's form")
    void theTextResidueIsHeld() {
        List<String> rendering = new ArrayList<>();
        for (ClassNode node : production.values()) {
            rendering.addAll(sinks(node, bearing, false));
        }
        rendering.sort(null);
        assertThat(rendering).as("the residue was read, so this holds something").isNotEmpty();
        assertThat(rendering.stream().map(finding -> finding.substring(0, finding.indexOf('.'))).distinct())
                .as("the residue's classes").isSubsetOf(TEXT_CLASSES);
        // Per method and per instruction, not per class: a new concatenation inside a listed class, or a second one inside a
        // listed method, is a change to this list in the same review. One cleared is one taken off.
        assertThat(rendering).as("a finding new to this list turns an amount into text: name the rule, not the figure")
                .isEqualTo(TEXT_RESIDUE.stream().sorted().toList());
    }

    @Test
    @DisplayName("the guard sees each shape: a logger's argument and varargs, a formatter, a concatenation, valueOf, append and toString")
    void theGuardSeesEachShape() {
        Map<String, ClassNode> planted = new HashMap<>(production);
        for (Class<?> type : List.of(PlantedShapes.class, PlantedShapes.Printing.class, PlantedShapes.Redacting.class)) {
            ClassNode node = classOf(type);
            planted.put(node.name, node);
        }
        Set<String> withFixture = bearing(planted);
        assertThat(withFixture).contains(Type.getInternalName(PlantedShapes.Printing.class))
                .doesNotContain(Type.getInternalName(PlantedShapes.Redacting.class));
        ClassNode fixture = planted.get(Type.getInternalName(PlantedShapes.class));
        List<String> logged = sinks(fixture, withFixture, true);
        assertThat(logged).anyMatch(finding -> finding.contains("loggerArgument"))
                .anyMatch(finding -> finding.contains("loggerVarargs"))
                .anyMatch(finding -> finding.contains("fluentArgument"))
                .anyMatch(finding -> finding.contains("fluentKeyValue"))
                .anyMatch(finding -> finding.contains("formatted"))
                .anyMatch(finding -> finding.contains("printingRecord"))
                .noneMatch(finding -> finding.contains("redactingRecord"))
                .noneMatch(finding -> finding.contains("identifierOnly"));
        List<String> text = sinks(fixture, withFixture, false);
        assertThat(text).anyMatch(finding -> finding.contains("concatenated"))
                .anyMatch(finding -> finding.contains("valueOf"))
                .anyMatch(finding -> finding.contains("appended"))
                .anyMatch(finding -> finding.contains("explicit"));
    }

    // ------------------------------------------------------------------------------------------------- the reading

    /**
     * Money; every record with an amount-bearing component whose string form is the generated one; and every class whose own
     * {@code toString} turns an amount into text - to a fixed point.
     */
    static Set<String> bearing(Map<String, ClassNode> classes) {
        Set<String> found = new TreeSet<>();
        found.add(MONEY);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ClassNode node : classes.values()) {
                if (found.contains(node.name)) {
                    continue;
                }
                if (generatedRecordForm(node) && holdsOneOf(node, found)) {
                    found.add(node.name);
                    grew = true;
                } else if (ownFormRenders(node, found)) {
                    found.add(node.name);
                    grew = true;
                }
            }
        }
        return found;
    }

    private static boolean holdsOneOf(ClassNode node, Set<String> types) {
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 && field.desc.startsWith("L")
                    && types.contains(Type.getType(field.desc).getInternalName())) {
                return true;
            }
        }
        return false;
    }

    /** A record whose {@code toString} is the one {@code ObjectMethods} generates: it prints every component. */
    private static boolean generatedRecordForm(ClassNode node) {
        if (!"java/lang/Record".equals(node.superName)) {
            return false;
        }
        for (MethodNode method : node.methods) {
            if ("toString".equals(method.name) && "()Ljava/lang/String;".equals(method.desc)) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof InvokeDynamicInsnNode dynamic
                            && "java/lang/runtime/ObjectMethods".equals(dynamic.bsm.getOwner())) {
                        return true;
                    }
                }
                return false;
            }
        }
        return true;
    }

    /** A class whose own {@code toString} turns one of {@code types} into text. */
    private static boolean ownFormRenders(ClassNode node, Set<String> types) {
        for (MethodNode method : node.methods) {
            if ("toString".equals(method.name) && "()Ljava/lang/String;".equals(method.desc)) {
                if (!mentions(method, types)) {
                    return false;
                }
                ClassNode only = new ClassNode();
                only.name = node.name;
                only.methods = List.of(method);
                return !sinks(only, types, false).isEmpty() || !sinks(only, types, true).isEmpty();
            }
        }
        return false;
    }

    /**
     * Every instruction of {@code node} that hands an amount-bearing value to text: a logger, a formatter or an {@code Object[]} element
     * when {@code logging}; a concatenation, {@code String.valueOf}, {@code append}, {@code Objects.toString} or an explicit
     * {@code toString} otherwise.
     */
    static List<String> sinks(ClassNode node, Set<String> bearing, boolean logging) {
        List<String> findings = new ArrayList<>();
        for (MethodNode method : node.methods) {
            // A value of a type reaches the stack only through something that names the type: a parameter, a field, a call's return,
            // a cast. A method that names none of them cannot hand one on, and is not analysed.
            if (!mayRender(method) || !mentions(method, bearing)) {
                continue;
            }
            Frame<BasicValue>[] frames;
            try {
                frames = new Analyzer<>(new SimpleVerifier()).analyze(node.name, method);
            } catch (AnalyzerException unanalysable) {
                findings.add(node.name + "." + method.name + ": could not be analysed (" + unanalysable.getMessage() + ")");
                continue;
            }
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                Frame<BasicValue> frame = frames[i];
                if (frame == null) {
                    continue; // unreachable
                }
                String where = node.name + "." + method.name;
                AbstractInsnNode instruction = instructions[i];
                if (instruction instanceof InvokeDynamicInsnNode dynamic && "makeConcatWithConstants".equals(dynamic.name)) {
                    if (!logging) {
                        for (Type argument : Type.getArgumentTypes(dynamic.desc)) {
                            if (argument.getSort() == Type.OBJECT && bearing.contains(argument.getInternalName())) {
                                findings.add(where + ": concatenates a " + argument.getClassName());
                            }
                        }
                    }
                } else if (instruction instanceof MethodInsnNode call) {
                    boolean explicit = "toString".equals(call.name) && "()Ljava/lang/String;".equals(call.desc) && bearing.contains(call.owner);
                    if (!logging && explicit) {
                        findings.add(where + ": calls " + call.owner + ".toString() explicitly");
                    } else if (logging ? isLoggingSink(call) : isTextSink(call)) {
                        Type[] arguments = Type.getArgumentTypes(call.desc);
                        for (int a = 0; a < arguments.length; a++) {
                            BasicValue value = frame.getStack(frame.getStackSize() - arguments.length + a);
                            if (bearingValue(value, bearing)) {
                                findings.add(where + ": hands a " + value.getType().getClassName() + " to " + call.owner + "." + call.name);
                            }
                        }
                    }
                } else if (logging && instruction.getOpcode() == Opcodes.AASTORE) {
                    BasicValue array = frame.getStack(frame.getStackSize() - 3);
                    BasicValue value = frame.getStack(frame.getStackSize() - 1);
                    if (array.getType() != null && "[Ljava/lang/Object;".equals(array.getType().getDescriptor())
                            && bearingValue(value, bearing)) {
                        findings.add(where + ": puts a " + value.getType().getClassName() + " in an Object[] (a logger's or formatter's arguments)");
                    }
                }
            }
        }
        return findings;
    }

    private static boolean bearingValue(BasicValue value, Set<String> bearing) {
        return value != null && value.getType() != null && value.getType().getSort() == Type.OBJECT
                && bearing.contains(value.getType().getInternalName());
    }

    /** Whether any instruction of {@code method} names one of {@code types}: a method that names none cannot render one. */
    private static boolean mentions(MethodNode method, Set<String> types) {
        for (String type : types) {
            if (method.desc.contains(type)) {
                return true;
            }
        }
        for (AbstractInsnNode instruction : method.instructions) {
            String text = switch (instruction) {
                case MethodInsnNode call -> call.owner + call.desc;
                case org.objectweb.asm.tree.FieldInsnNode field -> field.desc;
                case InvokeDynamicInsnNode dynamic -> dynamic.desc;
                case org.objectweb.asm.tree.TypeInsnNode typed -> typed.desc;
                default -> "";
            };
            for (String type : types) {
                if (text.contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Only a method holding a call or an array store can hand anything to text; the rest are not analysed. */
    private static boolean mayRender(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode || instruction instanceof InvokeDynamicInsnNode
                    || instruction.getOpcode() == Opcodes.AASTORE) {
                return true;
            }
        }
        return false;
    }

    /** A call that writes its {@code Object} arguments to a log line or formats them into one. */
    static boolean isLoggingSink(MethodInsnNode call) {
        boolean takesObject = call.desc.contains("Ljava/lang/Object;");
        return switch (call.owner) {
            case "org/slf4j/Logger" -> takesObject && Set.of("trace", "debug", "info", "warn", "error").contains(call.name);
            // SLF4J 2's fluent form: an argument, a key's value, or the log call's own arguments.
            case "org/slf4j/spi/LoggingEventBuilder" -> takesObject && Set.of("addArgument", "addKeyValue", "log").contains(call.name);
            case "java/lang/String" -> takesObject && Set.of("format", "formatted").contains(call.name);
            case "java/text/MessageFormat" -> "format".equals(call.name);
            case "java/io/PrintStream", "java/io/PrintWriter" -> takesObject && call.name.startsWith("print");
            default -> false;
        };
    }

    /** A call that turns an {@code Object} argument into text, wherever the text goes. */
    static boolean isTextSink(MethodInsnNode call) {
        boolean takesObject = call.desc.contains("Ljava/lang/Object;");
        return switch (call.owner) {
            case "java/lang/String" -> takesObject && "valueOf".equals(call.name);
            case "java/lang/StringBuilder", "java/lang/StringBuffer" -> takesObject && "append".equals(call.name);
            case "java/util/Objects" -> takesObject && "toString".equals(call.name);
            default -> false;
        };
    }

    // ------------------------------------------------------------------------------------------------- the files

    private static List<ClassNode> classesOf(Path entry) {
        List<ClassNode> nodes = new ArrayList<>();
        try {
            if (Files.isDirectory(entry)) {
                try (Stream<Path> files = Files.walk(entry)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                        try (InputStream in = Files.newInputStream(file)) {
                            nodes.add(node(in));
                        }
                    }
                }
            } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(entry.toFile())) {
                    for (JarEntry file : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                        try (InputStream in = jar.getInputStream(file)) {
                            nodes.add(node(in));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + entry, e);
        }
        return nodes;
    }

    private static ClassNode classOf(Class<?> type) {
        String binary = type.getName();
        try (InputStream in = type.getResourceAsStream(binary.substring(binary.lastIndexOf('.') + 1) + ".class")) {
            return node(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ClassNode node(InputStream in) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }
}
