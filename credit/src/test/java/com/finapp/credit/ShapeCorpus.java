package com.finapp.credit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * A corpus for proving the PL/pgSQL twin of the instrument-shape screen agrees with the Java one
 * (the Phase 8 → 9 transition, SEC-03): fixed edge cases, and random texts built from the pieces
 * each verdict turns on - Luhn-valid and invalid digit strings grouped by spaces and dashes,
 * account identifiers contiguous, printed and corrupted, platform UUIDs dashed and dashless,
 * words, and the separators that join or break a run. Reconciliation's migration test carries the
 * same corpus for its own twin (no build edge between the modules, by design).
 */
final class ShapeCorpus {

    private static final List<String> IDENTIFIERS =
            List.of(
                    "GB82WEST12345698765432", "DE89370400440532013000",
                    "FR1420041010050500013M02606", "NL91ABNA0417164300", "BE68539007547034",
                    "NO9386011117947", "MT84MALT011000012345MTLCAST001S",
                    "GB29NWBK60161331926819");

    private static final List<String> SEPARATORS =
            List.of(" ", " ", "-", "", "  ", ", ", ":", "_", "\n", "#", "--");

    private ShapeCorpus() {}

    static List<String> fixed() {
        return List.of(
                "", " ", "-", "4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111",
                "4111 - 1111 - 1111 - 1111", "4111  1111  1111  1111", "PAN4111111111111111",
                "ref 994111111111111111 end", "4111 1111 1111 1111 2 times", "4111111 111111111",
                "3782 822463 10005", "501800000009", "5018 0000 0009", "123456789012",
                "1234567890123", "7b8f2ab5-3451-4013-9675-f6ad325b55dd",
                "7B8F2AB5-3451-4013-9675-F6AD325B55DD", "x7b8f2ab5-3451-4013-9675-f6ad325b55dd",
                "x-3451-4013-9675", "ae97ba94d0ed782f8f6d05584ef8aa38",
                "PUSH_WITHDRAWAL:ae97ba94d0ed782f8f6d05584ef8aa38",
                "ae91ba94d0ed182f8f6d05584ef8aa38", "4111111111111111-1111-1111-1111-111111111111",
                "GB82WEST12345698765432", "GB82 WEST 1234 5698 7654 32",
                "GB82-WEST-1234-5698-7654-32", "de89 3704 0044 0532 0130 00",
                "BE68 5390 0754 7034 done", "ref XX12 GB82 WEST 1234 5698 7654 32",
                "NO93 8601 1117 947", "FY26 plan 2027 will need more review",
                "moved to xGB82WESTABCDEFGHIJKLM today", "moved to GB82WESTABCDEFGHIJKLM today",
                "GB82 WEST 1234 5698 7654 33", "from=AMOUNT_MISMATCH, to=FEE_MISMATCH",
                "retired by version 3's activation: the fee terms moved",
                "file received 2026-10-02 12:30:45", "call +44 20 7946 0958",
                "é4111111111111111", "AB12 CDEF GHIJ KLMN OPQR STUV WXYZ 1234 5678");
    }

    static List<String> random(Random random, int size) {
        List<String> texts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            StringBuilder text = new StringBuilder();
            int parts = 1 + random.nextInt(5);
            for (int p = 0; p < parts; p++) {
                text.append(piece(random));
                text.append(SEPARATORS.get(random.nextInt(SEPARATORS.size())));
            }
            texts.add(text.toString());
        }
        return texts;
    }

    private static String piece(Random random) {
        return switch (random.nextInt(10)) {
            case 0 -> digits(random, 1 + random.nextInt(22));
            case 1 -> grouped(random, luhnValid(random, 12 + random.nextInt(8)));
            case 2 -> grouped(random, digits(random, 10 + random.nextInt(12)));
            case 3 -> words(random);
            case 4 -> {
                String uuid = new UUID(random.nextLong(), random.nextLong()).toString();
                yield random.nextBoolean() ? uuid : uuid.toUpperCase(Locale.ROOT);
            }
            case 5 -> {
                // A version-7, variant-2 UUID without dashes, lower case (ADR-0013's form).
                long high = (random.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000007000L;
                long low = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
                yield new UUID(high, low).toString().replace("-", "");
            }
            case 6 -> printed(random, IDENTIFIERS.get(random.nextInt(IDENTIFIERS.size())));
            case 7 -> printed(random, corrupted(random,
                    IDENTIFIERS.get(random.nextInt(IDENTIFIERS.size()))));
            case 8 -> {
                StringBuilder opening = new StringBuilder();
                opening.append((char) ('A' + random.nextInt(26)))
                        .append((char) ('a' + random.nextInt(26)))
                        .append(digits(random, 2));
                int groups = random.nextInt(8);
                for (int g = 0; g < groups; g++) {
                    opening.append(random.nextBoolean() ? ' ' : '-')
                            .append(alphanumerics(random, 1 + random.nextInt(5)));
                }
                yield opening.toString();
            }
            default -> alphanumerics(random, 1 + random.nextInt(34));
        };
    }

    private static String digits(Random random, int length) {
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            digits.append((char) ('0' + random.nextInt(10)));
        }
        return digits.toString();
    }

    private static String luhnValid(Random random, int length) {
        String body = digits(random, length - 1);
        for (int check = 0; check <= 9; check++) {
            String candidate = body + check;
            if (luhn(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("one check digit always completes a Luhn number");
    }

    private static boolean luhn(String digits) {
        int total = 0;
        for (int i = 1; i <= digits.length(); i++) {
            int digit = digits.charAt(digits.length() - i) - '0';
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }
        return total % 10 == 0;
    }

    /** Digits split into random groups by single spaces or dashes - or not at all. */
    private static String grouped(Random random, String digits) {
        if (random.nextInt(4) == 0) {
            return digits;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < digits.length()) {
            int size = 1 + random.nextInt(6);
            if (i > 0) {
                out.append(random.nextBoolean() ? ' ' : '-');
            }
            out.append(digits, i, Math.min(digits.length(), i + size));
            i += size;
        }
        return out.toString();
    }

    /** An identifier contiguous, or printed in groups of four, in either case. */
    private static String printed(Random random, String identifier) {
        String cased = random.nextBoolean() ? identifier : identifier.toLowerCase(Locale.ROOT);
        if (random.nextInt(3) == 0) {
            return cased;
        }
        char separator = random.nextBoolean() ? ' ' : '-';
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cased.length(); i += 4) {
            if (i > 0) {
                out.append(separator);
            }
            out.append(cased, i, Math.min(cased.length(), i + 4));
        }
        return out.toString();
    }

    private static String corrupted(Random random, String identifier) {
        int at = 4 + random.nextInt(identifier.length() - 4);
        char was = identifier.charAt(at);
        char now = Character.isDigit(was) ? (char) ('0' + (was - '0' + 1) % 10) : 'Q';
        return identifier.substring(0, at) + now + identifier.substring(at + 1);
    }

    private static String words(Random random) {
        String[] words = {"the", "plan", "fee", "card", "west", "batch", "FY26", "ref", "done",
            "Q3", "B-42", "ORD", "late", "PSP-REM"};
        StringBuilder out = new StringBuilder();
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(words[random.nextInt(words.length)]);
        }
        return out.toString();
    }

    private static String alphanumerics(Random random, int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }
}
