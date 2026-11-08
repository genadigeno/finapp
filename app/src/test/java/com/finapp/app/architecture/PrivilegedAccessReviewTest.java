package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The privileged access review's registers are the code's (`P15-TSK-021`; {@code THREAT_MODEL.md} T3, T18).
 *
 * <p>{@code docs/architecture/PRIVILEGED_ACCESS_REVIEW.md} §3 lists every role and the permissions it grants; §4 lists
 * every act whose proposal and approval are two permissions, and the role holding each half - the pairs of roles one
 * identity must not hold together. A reviewer acts on those tables, so a role added, a permission moved or a new
 * propose/approve pair fails the build until the review says so. Read from {@link RoleName} and {@link PermissionName}
 * over {@code values()}: the pairs are derived from the names exactly as
 * {@code RoleNameTest#noRoleProposesAndApprovesTheSameAct} derives them.
 */
@Tag("architecture")
@DisplayName("the privileged access review's role register and propose/approve partition are the code's (P15-TSK-021)")
class PrivilegedAccessReviewTest {

    private static final String REVIEW = "docs/architecture/PRIVILEGED_ACCESS_REVIEW.md";
    private static final Pattern ROW = Pattern.compile("(?m)^\\|(.*)\\|\\s*$");
    private static final Pattern TOKEN = Pattern.compile("`([A-Z][A-Z0-9_]+)`");

    @Test
    @DisplayName("§3: every role, and exactly the permissions it grants")
    void theRoleRegisterIsTheCodes() {
        Map<String, Set<String>> documented = new TreeMap<>();
        for (List<String> cells : rows(section("## 3."))) {
            if (cells.size() == 2) {
                documented.put(single(cells.get(0)), tokens(cells.get(1)));
            }
        }
        Map<String, Set<String>> code = new TreeMap<>();
        for (RoleName role : RoleName.values()) {
            Set<String> permissions = new TreeSet<>();
            role.permissions().forEach(permission -> permissions.add(permission.name()));
            code.put(role.name(), permissions);
        }
        assertThat(documented).as("the review's role register").isEqualTo(code);
    }

    @Test
    @DisplayName("§4: every propose/approve pair the vocabulary names, and the role holding each half")
    void thePartitionIsTheCodes() {
        List<List<String>> documented = new ArrayList<>();
        for (List<String> cells : rows(section("## 4."))) {
            if (cells.size() == 5) {
                documented.add(List.of(single(cells.get(1)), single(cells.get(2)), single(cells.get(3)),
                        single(cells.get(4))));
            }
        }
        List<List<String>> code = new ArrayList<>();
        for (PermissionName proposal : PermissionName.values()) {
            String name = proposal.name();
            String stem = name.endsWith("_PROPOSE") ? name.substring(0, name.length() - "_PROPOSE".length())
                    : name.endsWith("_PREPARE") ? name.substring(0, name.length() - "_PREPARE".length())
                    : null;
            if (stem == null) {
                continue;
            }
            PermissionName approval = PermissionName.valueOf(stem + "_APPROVE");
            code.add(List.of(proposal.name(), holder(proposal), approval.name(), holder(approval)));
        }
        assertThat(code).as("the vocabulary's pairs").hasSize(6);
        assertThat(documented).as("the review's partition").containsExactlyInAnyOrderElementsOf(code);
    }

    /** The one role granting {@code permission}: grants are pairwise disjoint ({@code RoleNameTest}). */
    private static String holder(PermissionName permission) {
        Set<RoleName> holders = EnumSet.noneOf(RoleName.class);
        for (RoleName role : RoleName.values()) {
            if (role.permissions().contains(permission)) {
                holders.add(role);
            }
        }
        assertThat(holders).as("%s is granted by exactly one role", permission).hasSize(1);
        return holders.iterator().next().name();
    }

    private static String section(String heading) {
        String review = EvidenceCitations.read(EvidenceCitations.repositoryRoot().resolve(REVIEW));
        int start = review.indexOf("\n" + heading);
        assertThat(start).as("%s has a section %s", REVIEW, heading).isGreaterThanOrEqualTo(0);
        int end = review.indexOf("\n## ", start + 1);
        return review.substring(start, end < 0 ? review.length() : end);
    }

    private static List<List<String>> rows(String section) {
        List<List<String>> rows = new ArrayList<>();
        Matcher row = ROW.matcher(section);
        while (row.find()) {
            String inner = row.group(1);
            if (inner.matches("[-|\\s]+") || !inner.contains("`")) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : inner.split("\\|", -1)) {
                cells.add(cell.trim());
            }
            rows.add(cells);
        }
        assertThat(rows).as("the section's table was read").isNotEmpty();
        return rows;
    }

    private static Set<String> tokens(String cell) {
        Set<String> tokens = new TreeSet<>();
        Matcher token = TOKEN.matcher(cell);
        while (token.find()) {
            tokens.add(token.group(1));
        }
        return tokens;
    }

    private static String single(String cell) {
        Set<String> tokens = tokens(cell);
        assertThat(tokens).as("one name in the cell %s", cell).hasSize(1);
        return tokens.iterator().next();
    }
}
