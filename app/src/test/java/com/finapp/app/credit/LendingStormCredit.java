package com.finapp.app.credit;

import com.finapp.credit.CreditBureau;
import com.finapp.credit.FinancialDataProvider;
import com.finapp.sharedkernel.money.CountryCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lending storm's bridge to credit's simulators (`P11-TST-001`; the {@link CreditFixtures} shape - a bridge only, the
 * simulators themselves stay package-private where they live): one simulated bureau and one simulated financial-data
 * provider ({@link SimulatedBureauEngine}) shared by both of the storm's application instances, the real
 * {@link SimulatedBureauAdapter} and {@link SimulatedFinancialDataAdapter} over them, and each storm party's identifying
 * facts chosen so the simulators' own derivation gives it the profile the storm needs.
 */
public final class LendingStormCredit implements AutoCloseable {

    /** What the simulators' figures make of a party. */
    public enum Profile {
        /** A clean, affordable file with a bureau balance of 2,000.00-6,000.00: approved for both products. */
        GOOD,
        /** As GOOD, its bureau balance 10,000.00-12,000.00: one of a loan and a line fits the line's limit, not both. */
        RACER
    }

    private static final byte[] KEY = "a-lending-storm-provider-key-32b".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final SimulatedBureauEngine bureau;
    private final SimulatedBureauEngine findata;
    private final Map<String, CreditDataSubject> facts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final CreditDataSubjectResolver subjects = reference -> Optional.ofNullable(facts.get(reference));

    private LendingStormCredit(SimulatedBureauEngine bureau, SimulatedBureauEngine findata) {
        this.bureau = bureau;
        this.findata = findata;
    }

    /** Both simulators started, every answer carrying {@code needle} in a field no adapter reads. */
    public static LendingStormCredit start(String needle) {
        try {
            SimulatedBureauEngine bureau = SimulatedBureauEngine.start();
            SimulatedBureauEngine findata = SimulatedBureauEngine.startFinancialData();
            bureau.note(needle);
            findata.note(needle);
            bureau.slowness(Duration.ofMillis(1_500));
            findata.slowness(Duration.ofMillis(1_500));
            return new LendingStormCredit(bureau, findata);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Every later answer states it was retrieved at {@code instant} - recent on the database's clock. */
    public void retrievedAt(Instant instant) {
        bureau.retrievedAt(instant);
        findata.retrievedAt(instant);
    }

    /** The real bureau adapter over the simulated bureau - one per instance. */
    public CreditBureau bureau(Duration timeout) {
        return new SimulatedBureauAdapter(bureau.baseUrl(), timeout, KEY, subjects);
    }

    /** The real financial-data adapter over the simulated provider - one per instance. */
    public FinancialDataProvider financialData(Duration timeout) {
        return new SimulatedFinancialDataAdapter(findata.baseUrl(), timeout, KEY, subjects);
    }

    /** {@code party} given identifying facts the simulators turn into {@code profile}'s figures. */
    public void register(UUID party, Profile profile) {
        while (true) {
            StringBuilder name = new StringBuilder("Storm ");
            for (int i = 0; i < 10; i++) {
                name.append((char) ('a' + RANDOMNESS.nextInt(26)));
            }
            LocalDate born = LocalDate.of(1960 + RANDOMNESS.nextInt(40), 1 + RANDOMNESS.nextInt(12),
                    1 + RANDOMNESS.nextInt(28));
            String subject = name + "|" + born + "|DE";
            SimulatedBureauEngine.Figures figures = SimulatedBureauEngine.Figures.of(subject);
            SimulatedBureauEngine.Summary summary = SimulatedBureauEngine.Summary.of(subject);
            boolean affordable = summary.incomeMinor() >= 450_000 && summary.expenditureMinor() <= 150_000
                    && figures.obligationsMinor() <= 60_000;
            boolean clean = !figures.insolvency() && figures.defaults() == 0 && figures.accounts() >= 1
                    && figures.accounts() <= 5 && figures.delinquencies() == 0 && figures.score() >= 650;
            boolean fits = clean && affordable && switch (profile) {
                case GOOD -> figures.balanceMinor() >= 200_000 && figures.balanceMinor() <= 600_000;
                case RACER -> figures.balanceMinor() >= 1_000_000 && figures.balanceMinor() <= 1_200_000;
            };
            if (fits) {
                facts.put(party.toString(), new CreditDataSubject(name.toString(), born, CountryCode.of("DE")));
                balances.put(party, figures.balanceMinor());
                return;
            }
        }
    }

    /** The bureau balance the simulators report for {@code party}, in minor units. */
    public long bureauBalance(UUID party) {
        Long balance = balances.get(party);
        if (balance == null) {
            throw new IllegalArgumentException("not a storm party");
        }
        return balance;
    }

    /** Arms the bureau's next novel pull with a transient fault (a retry's worth: a fault costs a retry, nothing more). */
    public void armTransientFault(boolean onTheBureau) {
        SimulatedBureauEngine.Fault[] transientFaults = {SimulatedBureauEngine.Fault.UNAVAILABLE,
                SimulatedBureauEngine.Fault.SLOW, SimulatedBureauEngine.Fault.LOSE_RESPONSE};
        (onTheBureau ? bureau : findata).arm(transientFaults[RANDOMNESS.nextInt(transientFaults.length)]);
    }

    /** Reports produced by the bureau and the provider - one per reference each. */
    public int[] pulls() {
        return new int[] {bureau.pulls(), findata.pulls()};
    }

    /** The references each simulator produced a report under. */
    public List<Set<String>> producedReferences() {
        return List.of(bureau.producedReferences(), findata.producedReferences());
    }

    /** The distinct references each simulator was asked under. */
    public List<Set<String>> askedReferences() {
        return List.of(new HashSet<>(bureau.idempotencyKeys()), new HashSet<>(findata.idempotencyKeys()));
    }

    @Override
    public void close() {
        bureau.close();
        findata.close();
    }
}
