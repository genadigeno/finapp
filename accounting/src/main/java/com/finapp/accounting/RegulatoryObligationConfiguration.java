package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a regulatory obligation version says, as data (`P14-TSK-018`; ADR-0118 decision 1; {@code PHASE_14_PLAN.md} section
 * 13.7): the report definitions it draws from, the line mapping (form line from run figure) and the validation rules. Frozen
 * from the proposal; stored as its canonical text with its hash, because the bytes are hashed and {@code jsonb} would re-order
 * them ({@code P14-TSK-008}'s finding).
 *
 * <p><strong>Nothing here can run.</strong> A figure is a {@link FigurePath} in a closed grammar, a rule a
 * {@link RegulatoryRuleType} over line codes; there is no SQL, script, expression or template to evaluate, and every string is
 * from a bounded plain alphabet, so an obligation's row can only ever be read.
 *
 * @param sources each report definition and version it draws from, plain parameters only
 * @param lines the form's lines
 * @param rules at least one: a form nothing checks is a form nobody validated
 */
public record RegulatoryObligationConfiguration(List<Source> sources, List<Line> lines, List<Rule> rules) {

    public static final int MAX_SOURCES = 8;
    public static final int MAX_LINES = 500;
    public static final int MAX_RULES = 200;
    /** A rule's most lines on one side. */
    public static final int MAX_RULE_LINES = 100;
    /** The canonical text's bound - {@code accounting V010}'s {@code regulatory_obligation_permits_only_machine_edges}. */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Pattern DEFINITION = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ,.()/&+:_-]{0,199}");

    /** The configuration is not coherent. Names what, never a figure. */
    public static final class Invalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Invalid(String detail) {
            super(detail);
        }
    }

    /** A report definition and the version of it the form was mapped against. */
    public record Source(String definition, int version) {}

    /** One line of the form: its code and label, the definition it draws from, the figure's path. */
    public record Line(String code, String label, String source, String figure) {}

    /** One validation rule. */
    public record Rule(String code, RegulatoryRuleType type, List<String> left, List<String> right) {

        public Rule {
            left = left == null ? List.of() : copied(left, "rule's lines");
            right = right == null ? List.of() : copied(right, "rule's lines");
        }
    }

    public RegulatoryObligationConfiguration {
        sources = copied(sources, "sources");
        lines = copied(lines, "lines");
        rules = copied(rules, "rules");
        if (sources.isEmpty() || sources.size() > MAX_SOURCES) {
            throw new Invalid("an obligation draws from 1 to " + MAX_SOURCES + " report definitions");
        }
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw new Invalid("a form has 1 to " + MAX_LINES + " lines");
        }
        if (rules.isEmpty() || rules.size() > MAX_RULES) {
            throw new Invalid("a form has 1 to " + MAX_RULES + " validation rules");
        }
        Set<String> definitions = new HashSet<>();
        for (Source source : sources) {
            if (source.definition() == null || !DEFINITION.matcher(source.definition()).matches() || source.version() < 1 || source.version() > 9999) {
                throw new Invalid("a source is a report definition's code and a version of 1 to 9999");
            }
            if (!definitions.add(source.definition())) {
                throw new Invalid("a source is named once: " + source.definition());
            }
        }
        Set<String> codes = new HashSet<>();
        for (Line line : lines) {
            if (line.code() == null || !CODE.matcher(line.code()).matches()) {
                throw new Invalid("a form line's code is an upper-case code");
            }
            if (!codes.add(line.code())) {
                throw new Invalid("a form line's code is held once: " + line.code());
            }
            if (line.label() == null || !LABEL.matcher(line.label()).matches() || InstrumentShapes.holdsAny(line.label())) {
                throw new Invalid("line " + line.code() + ": a label is 1 to 200 plain characters");
            }
            if (line.source() == null || !definitions.contains(line.source())) {
                throw new Invalid("line " + line.code() + " draws from a definition the obligation does not name");
            }
            try {
                FigurePath.of(line.figure());
            } catch (FigurePath.Malformed | NullPointerException malformed) {
                throw new Invalid("line " + line.code() + ": the figure path is not of the grammar");
            }
        }
        Set<String> ruleCodes = new HashSet<>();
        for (Rule rule : rules) {
            if (rule.code() == null || !CODE.matcher(rule.code()).matches() || !ruleCodes.add(rule.code())) {
                throw new Invalid("a rule's code is an upper-case code, held once");
            }
            if (rule.type() == null) {
                throw new Invalid("rule " + rule.code() + " names a type of the closed vocabulary");
            }
            if (rule.left().isEmpty() || rule.left().size() > MAX_RULE_LINES || rule.right().size() > MAX_RULE_LINES) {
                throw new Invalid("rule " + rule.code() + " names 1 to " + MAX_RULE_LINES + " lines on its left");
            }
            if ((rule.type() == RegulatoryRuleType.NON_NEGATIVE) != rule.right().isEmpty()) {
                throw new Invalid("rule " + rule.code() + ": NON_NEGATIVE names no right side, every other type names one");
            }
            for (List<String> side : List.of(rule.left(), rule.right())) {
                for (String named : side) {
                    if (!codes.contains(named)) {
                        throw new Invalid("rule " + rule.code() + " names a line the form does not hold");
                    }
                }
            }
        }
        if (canonical(sources, lines, rules).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new Invalid("the configuration is larger than " + MAX_BYTES + " bytes");
        }
    }

    private static <T> List<T> copied(List<T> given, String what) {
        if (given == null || given.stream().anyMatch(Objects::isNull)) {
            throw new Invalid("the " + what + " are required, and hold no null");
        }
        return List.copyOf(given);
    }

    /** The canonical text: what is stored, hashed and attested. */
    public String canonical() {
        return canonical(sources, lines, rules);
    }

    /** The source named {@code definition}. */
    public Source source(String definition) {
        return sources.stream().filter(source -> source.definition().equals(definition)).findFirst()
                .orElseThrow(() -> new IllegalStateException("a line draws from a named source"));
    }

    private static String canonical(List<Source> sources, List<Line> lines, List<Rule> rules) {
        CanonicalJson.Arr sourced = CanonicalJson.array();
        for (Source source : sources) {
            sourced.add(CanonicalJson.object().put("definition", source.definition()).put("version", source.version()));
        }
        CanonicalJson.Arr lined = CanonicalJson.array();
        for (Line line : lines) {
            lined.add(CanonicalJson.object().put("code", line.code()).put("figure", line.figure()).put("label", line.label()).put("source", line.source()));
        }
        CanonicalJson.Arr ruled = CanonicalJson.array();
        for (Rule rule : rules) {
            ruled.add(CanonicalJson.object().put("code", rule.code()).put("left", codes(rule.left())).put("right", codes(rule.right())).put("type", rule.type().name()));
        }
        return CanonicalJson.text(CanonicalJson.object().put("lines", lined).put("rules", ruled).put("sources", sourced));
    }

    private static CanonicalJson.Arr codes(List<String> codes) {
        CanonicalJson.Arr array = CanonicalJson.array();
        codes.forEach(array::add);
        return array;
    }

    /**
     * The configuration a row stores.
     *
     * @throws Invalid when the text is not the canonical form of a coherent configuration
     */
    public static RegulatoryObligationConfiguration parse(String text) {
        CanonicalJson.Obj root;
        try {
            root = RunContentParser.object(text);
        } catch (RunContentParser.NotCanonical notCanonical) {
            throw new Invalid("an obligation's configuration is canonical JSON");
        }
        List<Source> sources = new ArrayList<>();
        for (CanonicalJson.Obj source : objects(root, "sources")) {
            sources.add(new Source(text(source, "definition"), whole(source, "version")));
        }
        List<Line> lines = new ArrayList<>();
        for (CanonicalJson.Obj line : objects(root, "lines")) {
            lines.add(new Line(text(line, "code"), text(line, "label"), text(line, "source"), text(line, "figure")));
        }
        List<Rule> rules = new ArrayList<>();
        for (CanonicalJson.Obj rule : objects(root, "rules")) {
            RegulatoryRuleType type;
            try {
                type = RegulatoryRuleType.valueOf(text(rule, "type"));
            } catch (IllegalArgumentException unknown) {
                throw new Invalid("a rule names a type of the closed vocabulary");
            }
            rules.add(new Rule(text(rule, "code"), type, texts(rule, "left"), texts(rule, "right")));
        }
        return new RegulatoryObligationConfiguration(sources, lines, rules);
    }

    private static List<CanonicalJson.Obj> objects(CanonicalJson.Obj parent, String member) {
        if (!(parent.members().get(member) instanceof CanonicalJson.Arr array)) {
            throw new Invalid("the configuration holds " + member);
        }
        List<CanonicalJson.Obj> objects = new ArrayList<>();
        for (CanonicalJson.Value element : array.elements()) {
            if (!(element instanceof CanonicalJson.Obj object)) {
                throw new Invalid("the configuration's " + member + " are objects");
            }
            objects.add(object);
        }
        return objects;
    }

    private static List<String> texts(CanonicalJson.Obj parent, String member) {
        if (!(parent.members().get(member) instanceof CanonicalJson.Arr array)) {
            throw new Invalid("the configuration holds " + member);
        }
        List<String> texts = new ArrayList<>();
        for (CanonicalJson.Value element : array.elements()) {
            if (!(element instanceof CanonicalJson.Text text)) {
                throw new Invalid("the configuration's " + member + " are codes");
            }
            texts.add(text.value());
        }
        return texts;
    }

    private static String text(CanonicalJson.Obj parent, String member) {
        if (!(parent.members().get(member) instanceof CanonicalJson.Text text)) {
            throw new Invalid("the configuration holds " + member);
        }
        return text.value();
    }

    private static int whole(CanonicalJson.Obj parent, String member) {
        try {
            return Integer.parseInt(text(parent, member));
        } catch (NumberFormatException malformed) {
            throw new Invalid("the configuration's " + member + " is a whole number");
        }
    }
}
