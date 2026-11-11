package com.finapp.app.architecture;

import com.finapp.sharedkernel.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each way an amount can be turned into text, planted, for {@link MoneyReachesNoTextSinkTest#theGuardSeesEachShape}: the negative
 * control that proves the guard can see what it says it refuses. Never called.
 */
final class PlantedShapes {

    private static final Logger log = LoggerFactory.getLogger(PlantedShapes.class);

    /** A record whose generated string form prints its amount. */
    record Printing(String label, Money amount) {}

    /** A record holding an amount whose string form says nothing of it. */
    record Redacting(String label, Money amount) {
        @Override
        public String toString() {
            return "Redacting[redacted]";
        }
    }

    private PlantedShapes() {}

    static void loggerArgument(Money amount) {
        log.info("an amount {}", amount);
    }

    static void loggerVarargs(String first, String second, Money amount) {
        log.info("{} {} {}", first, second, amount);
    }

    static void fluentArgument(Money amount) {
        log.atInfo().addArgument(amount).log("an amount {}");
    }

    static void fluentKeyValue(Money amount) {
        log.atWarn().addKeyValue("amount", amount).log("an amount");
    }

    static String concatenated(Money amount) {
        return "an amount " + amount;
    }

    static String valueOf(Money amount) {
        return String.valueOf(amount);
    }

    static String appended(Money amount) {
        return new StringBuilder().append(amount).toString();
    }

    static String formatted(Money amount) {
        return String.format("%s", amount);
    }

    static String explicit(Money amount) {
        return amount.toString();
    }

    static void printingRecord(Printing record) {
        log.info("{}", record);
    }

    static void redactingRecord(Redacting record) {
        log.info("{}", record);
    }

    static void identifierOnly(Money amount) {
        log.info("a currency {}", amount.currency());
    }
}
