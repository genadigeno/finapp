package com.finapp.app.architecture;

import java.util.regex.Pattern;

/**
 * What a source-join census read port may do with a book's name, held by the one-poster guards that permit one
 * ({@code CashAtBankHasOnePosterTest}, {@code FxBooksHaveOnePosterTest}, {@code CounterpartyClearingIsNamedByDeclarationsTest};
 * a coordinator's repair, 2026-11-13).
 *
 * <p>`P15-TSK-035`'s census compares each journal entry with the record that caused it, and a module's read port states, as data,
 * the purposes an entry's lines must carry for a figure to compare: {@code Set.of("CASH_AT_BANK")} in a figure, never a resolved
 * account. That port names the book to READ it. A permit by file name alone would also admit a poster planted in the same file, so
 * a census reader is held to its form, and the form is everything a poster needs taken away:
 *
 * <ol>
 *   <li>the file is the census's port: its class {@code implements JournalSources};
 *   <li>the book is named only inside a string literal, never in code - no {@code AccountPurpose.CASH_AT_BANK}, no static import
 *       of it;
 *   <li>the code reaches no ledger type and turns no name into one: no {@code AccountPurpose}, no {@code valueOf}, no
 *       {@code forName}, no {@code com.finapp.ledger} - so no purpose, no chart, no {@code JournalLine}, no posting service;
 *   <li>no literal writes or names the ledger's schema: no {@code INSERT INTO}, {@code UPDATE ... SET}, {@code DELETE FROM},
 *       {@code MERGE INTO} or {@code TRUNCATE}, and no {@code ledger.} relation - the port reads its own module's tables with a
 *       {@code SELECT} (its schema is {@code SourceJoinCoverageTest}'s).
 * </ol>
 *
 * <p>Each guard still names its census readers one by one: a new port naming a book is refused until somebody writes its
 * sentence, and a permitted port that stops being in this form is refused as a second poster.
 */
final class CensusReaderForm {

    private CensusReaderForm() {}

    private static final Pattern CENSUS_PORT = Pattern.compile("\\bimplements\\s+(?:[\\w.]+\\.)?JournalSources\\b");

    private static final Pattern LEDGER_REACH =
            Pattern.compile("\\b(?:AccountPurpose|valueOf|forName)\\b|\\bcom\\s*\\.\\s*finapp\\s*\\.\\s*ledger\\b");

    private static final Pattern SQL_WRITE_OR_LEDGER = Pattern.compile(
            "(?i)\\b(?:insert\\s+into|update\\s+\\S+\\s+set|delete\\s+from|merge\\s+into|truncate)\\b|\\bledger\\s*\\.\\s*\\w");

    /** Whether {@code source} is a census read port in the read-only form, {@code books} the guard's tokens for its books. */
    static boolean holds(String source, Pattern books) {
        String code = scan(source, false);
        return CENSUS_PORT.matcher(code).find()
                && !books.matcher(code).find()
                && !LEDGER_REACH.matcher(code).find()
                && !SQL_WRITE_OR_LEDGER.matcher(scan(source, true)).find();
    }

    /**
     * {@code keepLiterals}: the string literals, one per line; otherwise the code with comments AND literals stripped - the
     * one-poster guards' scanner, character literals skipped so {@code '"'} opens nothing.
     */
    static String scan(String source, boolean keepLiterals) {
        StringBuilder kept = new StringBuilder();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end;
                if (keepLiterals) {
                    kept.append(source, i + 3, end).append('\n');
                }
                i = Math.min(length, end + 3);
            } else if (c == '"') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                if (keepLiterals) {
                    kept.append(source, i + 1, Math.min(j, length)).append('\n');
                }
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                if (!keepLiterals) {
                    kept.append(c);
                }
                i++;
            }
        }
        return kept.toString();
    }
}
