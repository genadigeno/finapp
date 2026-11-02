package com.finapp.accounting.testing;

import com.finapp.accounting.reporting.CanonicalJson;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * A suite's way round a report body (`P14-TSK-017`): the values as written, read back by name - so an assertion names what a
 * reader of the issued content would. Test code: it ships in no jar.
 */
public final class ReportBodies {

    private ReportBodies() {}

    /** The object member at {@code path}. */
    public static CanonicalJson.Obj object(CanonicalJson.Obj from, String... path) {
        CanonicalJson.Obj at = from;
        for (String member : path) {
            at = (CanonicalJson.Obj) value(at, member);
        }
        return at;
    }

    /** The member {@code name}, which must be present. */
    public static CanonicalJson.Value value(CanonicalJson.Obj from, String name) {
        CanonicalJson.Value found = from.members().get(name);
        if (found == null) {
            throw new AssertionError("no member " + name + " in " + CanonicalJson.text(from));
        }
        return found;
    }

    /** A string member - a whole number reads as its decimal string. */
    public static String text(CanonicalJson.Obj from, String name) {
        return ((CanonicalJson.Text) value(from, name)).value();
    }

    /** A boolean member. */
    public static boolean flag(CanonicalJson.Obj from, String name) {
        return value(from, name) == CanonicalJson.Flag.TRUE;
    }

    /** True when the member is written as {@code null}. */
    public static boolean isNull(CanonicalJson.Obj from, String name) {
        return value(from, name) == CanonicalJson.Null.INSTANCE;
    }

    /** An array member's object elements. */
    public static List<CanonicalJson.Obj> objects(CanonicalJson.Obj from, String name) {
        return ((CanonicalJson.Arr) value(from, name)).elements().stream()
                .map(CanonicalJson.Obj.class::cast)
                .toList();
    }

    /** An amount member's minor units - {@code {"currency":...,"minor":...,"scale":...}}. */
    public static BigInteger minor(CanonicalJson.Obj from, String name) {
        return new BigInteger(text(object(from, name), "minor"));
    }

    /** An amount member's scale. */
    public static int scale(CanonicalJson.Obj from, String name) {
        return Integer.parseInt(text(object(from, name), "scale"));
    }

    /** The named section of a purpose report's body. */
    public static CanonicalJson.Obj section(CanonicalJson.Obj body, String name) {
        return objects(body, "sections").stream().filter(section -> text(section, "section").equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no section " + name));
    }

    /** One purpose report's row, when it has one: its purpose and currency, in any section. */
    public static Optional<CanonicalJson.Obj> row(CanonicalJson.Obj body, String purpose, String currency) {
        return objects(body, "sections").stream()
                .flatMap(section -> objects(section, "rows").stream())
                .filter(row -> text(row, "purpose").equals(purpose) && text(row, "currency").equals(currency))
                .findFirst();
    }

    /** One counterparty's row of a purpose report, when it has one. */
    public static Optional<CanonicalJson.Obj> row(CanonicalJson.Obj body, String purpose, String counterparty, String currency) {
        return objects(body, "sections").stream()
                .flatMap(section -> objects(section, "rows").stream())
                .filter(row -> text(row, "purpose").equals(purpose) && text(row, "currency").equals(currency)
                        && !isNull(row, "counterparty") && text(row, "counterparty").equals(counterparty))
                .findFirst();
    }
}
