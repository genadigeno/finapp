package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The glossary defines every canonical term, and contrasts every distinction (`P0-DOC-011`).
 *
 * <p>{@code CLAUDE.md} §Domain Distinctions forbids collapsing eight groups of concepts, and
 * {@code DOMAIN_MODEL.md} names 55 canonical terms. Neither rule enforces itself: a term nobody
 * defined and a distinction nobody contrasted both look exactly like ones that were handled.
 *
 * <p><strong>What the acceptance criterion actually requires.</strong> "Every term in
 * {@code DOMAIN_MODEL.md} defined, with the 'do not collapse' pairs explicitly contrasted" is two
 * claims about two documents, so it is checkable — and checking it found that the two lists
 * disagree: seven terms are forbidden from being collapsed that the canonical list never names.
 * The glossary is therefore the union of both, and this holds it to both.
 *
 * <p><strong>What it cannot check</strong> is whether a definition is correct. What it protects is
 * that no term is silently undefined, no definition omits the contrast that makes it useful, and
 * no forbidden collapse is left uncontrasted.
 */
@DisplayName("Domain glossary (P0-DOC-011)")
class DomainGlossaryTest {

    private static final String GLOSSARY = "docs/domain/GLOSSARY.md";
    private static final String DOMAIN_MODEL = "docs/domain/DOMAIN_MODEL.md";
    private static final String INSTRUCTIONS = "CLAUDE.md";
    private static final String MODULE_ARCHITECTURE = "docs/architecture/MODULE_ARCHITECTURE.md";
    private static final String INVARIANTS = "docs/domain/FINANCIAL_INVARIANTS.md";

    /** A glossary entry heading. */
    private static final Pattern ENTRY = Pattern.compile("^### (.+)$");

    /** The owning-module line of an entry. */
    // MULTILINE, or `^` anchors to the start of the whole body and matches nothing at all — the
    // defect the P0-TSK-033 review found in a register parser, reproduced here and caught
    // immediately only because this check asserts PRESENCE rather than absence. A guard that
    // looked for a forbidden line would have passed silently over every entry.
    private static final Pattern OWNED_BY =
            Pattern.compile("^\\*\\*Owned by:\\*\\* `([a-z]+)`", Pattern.MULTILINE);

    /** A module declared by the register, e.g. "### `ledger` — Phase 3". */
    private static final Pattern DECLARED_MODULE = Pattern.compile("^### `([a-z]+)` —");

    /** A party we deliberately do not model as our own state. */
    private static final String EXTERNAL = "external";

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every canonical term is defined")
    void everyCanonicalTermIsDefined() {
        assertThat(glossaryEntries().keySet())
                .as(
                        "%s must define every term %s names as a canonical concept",
                        GLOSSARY, DOMAIN_MODEL)
                .containsAll(canonicalTerms());
    }

    @Test
    @DisplayName("every term the distinctions name is defined")
    void everyDistinguishedTermIsDefined() {
        // The two lists do not agree, and that is the point: CLAUDE.md forbids collapsing seven
        // terms DOMAIN_MODEL.md never names - Authentication, Transaction, Operational Account,
        // Underwriting, Customer Payment, Merchant Settlement and KYC. A glossary covering only
        // the canonical list would leave exactly the terms the rule is about undefined.
        assertThat(glossaryEntries().keySet())
                .as("%s must define every term %s §Domain Distinctions names", GLOSSARY, INSTRUCTIONS)
                .containsAll(distinguishedTerms());
    }

    @Test
    @DisplayName("the glossary defines nothing the two lists do not name")
    void theGlossaryIsExactlyTheUnion() {
        // The other direction. Without it the glossary becomes a second home for vocabulary its
        // owning document should define - `Debit` belongs in LEDGER_MODEL.md, `Tolerance` in
        // RECONCILIATION_MODEL.md - and a second unguarded copy drifts while looking
        // authoritative. Same rule that keeps ERROR_CONTRACT.md the only list of error codes.
        Set<String> union = new TreeSet<>(canonicalTerms());
        union.addAll(distinguishedTerms());

        assertThat(glossaryEntries().keySet())
                .as(
                        "%s defines a term neither %s nor %s names. Add it to a list, or define it"
                                + " in its owning domain document.",
                        GLOSSARY, DOMAIN_MODEL, INSTRUCTIONS)
                .isSubsetOf(union);
    }

    @Test
    @DisplayName("every entry says what the term is not")
    void everyEntrySaysWhatTheTermIsNot() {
        Map<String, String> missing = new TreeMap<>();
        glossaryEntries()
                .forEach(
                        (term, body) -> {
                            if (!body.contains("**Not:**")) {
                                missing.put(term, "has no **Not:** statement");
                            }
                        });

        assertThat(missing)
                .as(
                        """
                        Every entry in %s must state what the term is NOT.

                        A definition alone does not stop a collapse: two definitions can each be \
                        correct and still be applied to the same thing by two people. Naming the \
                        concept a term is confused with is what makes a violation visible in \
                        review, which is the whole reason this document exists.""",
                        GLOSSARY)
                .isEmpty();
    }

    @Test
    @DisplayName("every entry names an owning module that exists")
    void everyEntryNamesAKnownOwner() {
        Set<String> declared = new TreeSet<>(declaredModules());
        declared.add(EXTERNAL);

        Map<String, String> problems = new TreeMap<>();
        glossaryEntries()
                .forEach(
                        (term, body) -> {
                            Matcher owner = OWNED_BY.matcher(body);
                            if (!owner.find()) {
                                problems.put(term, "has no **Owned by:** line");
                            } else if (!declared.contains(owner.group(1))) {
                                problems.put(term, "names module '" + owner.group(1) + "'");
                            }
                        });

        assertThat(problems)
                .as(
                        "every owner must be a module %s declares, or '%s' for a party we"
                                + " deliberately do not model. Declared: %s",
                        MODULE_ARCHITECTURE, EXTERNAL, declared)
                .isEmpty();
    }

    @Test
    @DisplayName("no owner contradicts the module register")
    void noOwnerContradictsTheRegister() {
        // The register is the authority on ownership (ADR-0012), and P0-TSK-006 verified single
        // ownership by script. Where it names a concept in a module's `Owns:` line, the glossary
        // must agree; where it does not, the attribution is this document's judgement and only the
        // module's existence is checked.
        //
        // Found by review, and it had found a real one: the register lists `Risk Score` under
        // `credit`, and the glossary had attributed it to `risk`. A glossary that quietly
        // disagrees with the document owning the decision is worse than one that stays silent.
        Map<String, String> ownedByRegister = conceptsClaimedByModules();

        Map<String, String> contradictions = new TreeMap<>();
        glossaryEntries()
                .forEach(
                        (term, body) -> {
                            String registerOwner = ownedByRegister.get(term);
                            if (registerOwner == null) {
                                return; // the register does not name it; §10 records the judgement
                            }
                            Matcher owner = OWNED_BY.matcher(body);
                            if (owner.find() && !owner.group(1).equals(registerOwner)) {
                                contradictions.put(
                                        term,
                                        "glossary says `"
                                                + owner.group(1)
                                                + "`, register says `"
                                                + registerOwner
                                                + "`");
                            }
                        });

        assertThat(contradictions)
                .as("%s §4 is the authority on ownership; %s must not disagree with it",
                        MODULE_ARCHITECTURE, GLOSSARY)
                .isEmpty();
    }

    @Test
    @DisplayName("every invariant the glossary cites exists")
    void everyCitedInvariantExists() {
        // A glossary that cites INV-BAL-07 is a glossary somebody stops trusting. Twenty-three
        // citations, none of which any other check would notice going stale - the invariant
        // catalogue is renumbered by no rule, but an invariant can be superseded.
        Set<String> cited = new TreeSet<>();
        Matcher citation =
                Pattern.compile("INV-[A-Z]+-\\d+").matcher(readRepositoryFile(GLOSSARY));
        while (citation.find()) {
            cited.add(citation.group());
        }

        Set<String> defined = new TreeSet<>();
        Matcher heading =
                Pattern.compile("(?m)^### (INV-[A-Z]+-\\d+)")
                        .matcher(readRepositoryFile(INVARIANTS));
        while (heading.find()) {
            defined.add(heading.group(1));
        }

        assertThat(cited)
                .as("%s cites an invariant %s does not define", GLOSSARY, INVARIANTS)
                .isNotEmpty()
                .isSubsetOf(defined);
    }

    @Test
    @DisplayName("every distinction group is contrasted, exactly as CLAUDE.md words it")
    void everyDistinctionGroupIsContrasted() {
        // Matched against the group text verbatim rather than against its members, so that
        // reordering or renaming a group in CLAUDE.md fails the build. A group added there is then
        // undefined here until somebody writes the contrast - which is the failure this prevents,
        // since an uncontrasted group looks exactly like a handled one.
        Set<String> headings = new TreeSet<>(glossaryEntries().keySet());
        headings.addAll(sectionHeadings());

        assertThat(headings)
                .as(
                        "%s §2 must contrast every group in %s §Domain Distinctions, under a"
                                + " heading repeating the group exactly",
                        GLOSSARY, INSTRUCTIONS)
                .containsAll(distinctionGroups());
    }

    @Test
    @DisplayName("every document is actually parsed")
    void everythingIsActuallyRead() {
        // Without this, a reformatted heading would empty every set above and the containsAll
        // assertions would pass over nothing at all - the vacuity P0-TST-007 found in a privilege
        // check and the P0-TSK-033 review found in a register parser. Named entries rather than
        // counts, because a count is satisfied by parsing the wrong list.
        assertThat(canonicalTerms())
                .hasSizeGreaterThan(50)
                .contains("Party", "Journal Line", "BNPL Agreement", "Instalment");
        assertThat(distinctionGroups()).hasSize(8).contains("Consent / Authentication / Authorization");
        assertThat(distinguishedTerms()).contains("Underwriting", "Merchant Settlement", "Transaction");
        assertThat(glossaryEntries().keySet())
                .hasSizeGreaterThan(50)
                .contains("Chargeback", "Suspense Account", "Operational Account");
        assertThat(declaredModules()).hasSizeGreaterThan(15).contains("ledger", "payments", "fx");
    }

    // ------------------------------------------------------------------

    /**
     * The canonical concepts, which are the capitalised lines between the introduction and the
     * closing note.
     *
     * <p>Bounded to that block rather than matched across the document, because
     * {@code DOMAIN_MODEL.md} §Time also begins lines with capitals. Three times in
     * {@code P0-TSK-037} the defect was pattern-matching a whole file instead of the region.
     */
    private static Set<String> canonicalTerms() {
        Set<String> terms = new TreeSet<>();
        boolean inList = false;

        for (String line : readRepositoryFile(DOMAIN_MODEL).split("\\R")) {
            if (line.startsWith("Canonical concepts")) {
                inList = true;
                continue;
            }
            if (inList) {
                if (line.startsWith("Important:")) {
                    break;
                }
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && Character.isUpperCase(trimmed.charAt(0))) {
                    terms.add(trimmed);
                }
            }
        }
        return terms;
    }

    /** The eight groups, verbatim, from `CLAUDE.md` §Domain Distinctions. */
    private static List<String> distinctionGroups() {
        List<String> groups = new ArrayList<>();
        boolean inSection = false;

        for (String line : readRepositoryFile(INSTRUCTIONS).split("\\R")) {
            if (line.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                inSection = line.equals("## Domain Distinctions");
                continue;
            }
            if (inSection && line.startsWith("- ")) {
                groups.add(line.substring(2).strip());
            }
        }
        return groups;
    }

    /** Every individual term named by a distinction group. */
    private static Set<String> distinguishedTerms() {
        Set<String> terms = new TreeSet<>();
        for (String group : distinctionGroups()) {
            for (String member : group.split("/")) {
                terms.add(member.strip());
            }
        }
        return terms;
    }

    /** Glossary entries: heading to the body beneath it. */
    private static Map<String, String> glossaryEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : readRepositoryFile(GLOSSARY).split("\\R")) {
            if (line.strip().startsWith("```")) {
                // §1 shows the entry shape inside a fence, so a `### Term` there is an example
                // rather than a definition. Without this the example is read as a term the lists
                // do not name - which theGlossaryIsExactlyTheUnion reported, correctly.
                inCodeFence = !inCodeFence;
                if (current != null) {
                    body.append(line).append('\n');
                }
                continue;
            }
            if (inCodeFence) {
                if (current != null) {
                    body.append(line).append('\n');
                }
                continue;
            }
            Matcher heading = ENTRY.matcher(line);
            if (heading.matches()) {
                if (current != null) {
                    entries.put(current, body.toString());
                }
                current = heading.group(1).strip();
                body = new StringBuilder();
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null) {
            entries.put(current, body.toString());
        }

        // §2's headings are the distinction groups, not terms. They are returned by
        // sectionHeadings() for everyDistinctionGroupIsContrasted and removed here so that
        // theGlossaryIsExactlyTheUnion does not report them as undefined terms.
        entries.keySet().removeAll(sectionHeadings());
        return entries;
    }

    /** Headings that are distinction-group contrasts rather than term definitions. */
    private static Set<String> sectionHeadings() {
        Set<String> groups = new LinkedHashSet<>(distinctionGroups());
        Set<String> present = new LinkedHashSet<>();

        for (String line : readRepositoryFile(GLOSSARY).split("\\R")) {
            Matcher heading = ENTRY.matcher(line);
            if (heading.matches() && groups.contains(heading.group(1).strip())) {
                present.add(heading.group(1).strip());
            }
        }
        return present;
    }

    /**
     * Concepts the register explicitly places in a module, from its `Owns:` lines.
     *
     * <p>Matched on the comma-separated items so that "Risk Score" is found and "Credit Score" is
     * not mistaken for it — a substring search over the whole line would report both.
     */
    private static Map<String, String> conceptsClaimedByModules() {
        Map<String, String> owners = new LinkedHashMap<>();
        String module = null;

        for (String line : readRepositoryFile(MODULE_ARCHITECTURE).split("\\R")) {
            Matcher declared = DECLARED_MODULE.matcher(line);
            if (declared.find()) {
                module = declared.group(1);
                continue;
            }
            if (module == null || !line.startsWith("- **Owns:**")) {
                continue;
            }
            for (String item : line.substring("- **Owns:**".length()).split(",")) {
                String concept = item.strip().replaceAll("\\.$", "");
                if (!concept.isEmpty()) {
                    owners.putIfAbsent(concept, module);
                }
            }
        }
        return owners;
    }

    /** Modules the architecture register declares. */
    private static Set<String> declaredModules() {
        Set<String> modules = new TreeSet<>();
        for (String line : readRepositoryFile(MODULE_ARCHITECTURE).split("\\R")) {
            Matcher module = DECLARED_MODULE.matcher(line);
            if (module.find()) {
                modules.add(module.group(1));
            }
        }
        return modules;
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private static String readRepositoryFile(String relativePath) {
        Path path = repositoryRoot().resolve(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
