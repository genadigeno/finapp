package com.finapp.app.accounting;

import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import com.finapp.creditmath.testing.SecondJvm;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The accounting battery's second JVM (`P14-TST-002`; the backlog's "regeneration in two JVMs byte-identical"): the same seeded
 * journal generated, posted through the real posting service and reported by the production folds in a process with another
 * default charset, locale and time zone and other identity hash codes ({@link SecondJvm#CHILD_DEFAULTS}) - the journal's
 * fingerprint, every report's content hash (thirty-six: three reports in twelve months) and the digest of every fact.
 * {@code AccountingBatteryHermeticTest} computes the same lines in its own JVM and requires them equal, every line.
 */
final class AccountingBatteryInAnotherJvm {

    private AccountingBatteryInAnotherJvm() {}

    /** {@code arguments}: the output file, the seed, the entries. */
    public static void main(String[] arguments) throws IOException {
        SecondJvm.write(Path.of(arguments[0]), lines(Long.parseLong(arguments[1]), Integer.parseInt(arguments[2])));
    }

    /** The lines both JVMs write for {@code (seed, entries)}. */
    static List<String> lines(long seed, int entries) {
        List<Entry> journal = AccountingBatteryWorld.generate(seed, entries);
        AccountingBatteryBooks.Books books = AccountingBatteryBooks.books(AccountingBatteryBooks.post(journal, seed), Chart.BASE,
                Optional.empty());
        return AccountingBatteryBooks.lines(AccountingBatteryWorld.fingerprint(journal),
                AccountingBatteryBooks.report(books, month -> AccountingBatteryWorld.rates(seed, month)));
    }
}
