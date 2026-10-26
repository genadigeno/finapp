package com.finapp.risk;

import com.finapp.risk.RiskBatteryOracles.Expected;
import com.finapp.risk.RiskBatteryOracles.Line;
import com.finapp.risk.RiskBatteryOracles.Statement;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The policy battery's generator (`P13-TST-002`; {@code PHASE_13_PLAN.md} sections 12.2-12.7, 13.3): one subject per index -
 * a party with its identity, wallets, payee accounts, cross-border beneficiaries, devices, merchants and sessions - living on
 * a simulated calendar over 2027-2029: judged movements of the five operations, inbound credits, credential changes,
 * recoveries, authentication failures, BNPL applications, refunds, chargebacks, a KYC approval, a screening hit, a
 * {@code PROHIBITED} disposition, a merchant's suspension. Each judged movement's readings are computed from the state
 * <strong>before</strong> it and sealed as {@link EvaluationInputs} exactly as T-eval seals them; the battery's oracle
 * ({@link RiskBatteryOracles}, never the engine) decides whether it permits, and only a permitted movement counts - in the
 * velocity windows and in the first sightings (OQ-1, plan section 12.4). After every posted movement and every inbound
 * credit the {@code MONITORING} version judges the party, and its rating is the profile the next judged movement reads.
 *
 * <p><strong>The walk and the edges.</strong> A subject's cohort is its index modulo twelve - the generator's walk - each
 * shaping one family of the backlog's: amounts across every band's edge, velocity bursts at every window boundary, novelty,
 * account takeover, structuring, dormancy gaps, round amounts, corridors, hard decisions, abuse ratios and the fail-safe at
 * every band. <strong>The edges are the C8 v1 fixtures' figures</strong>: only the amount bands' thresholds and the
 * fail-safe bands' edges are read from the battery's own {@link Statement}; every other planned edge - the velocity counts
 * and sums (10, EUR 5,000, 5 a session, 100 and EUR 50,000 a month), the authentication failures, the lookbacks (29/30
 * days, 23/24 and 71/72 hours), the account age, structuring, dormancy, round amounts and the ratio figures - is written
 * here for C8's values. So the battery proves the C8 versions; another version needs its own statement, these edges, the
 * floors and its successor written for it before its fingerprint means anything ({@code OPERATIONS_RUNBOOK.md} section
 * 9.1 step 1). Every selection the battery makes of a subject - its perturbation, its version swap - is taken from a mix of
 * the index and never from this walk ({@code RiskBattery#mix}).
 *
 * <p><strong>Deterministic in any JVM.</strong> A subject's randomness is one {@link Random} seeded from its index (the JDK
 * specifies the sequence); no hash map is ever iterated into an output; the windows are tumbling buckets anchored at Monday
 * 1970-01-05 UTC by this class's own arithmetic ({@link #start}) - the battery holds it equal to {@code WindowStarts} on
 * every movement. Amounts are {@code long} minor units with an explicit currency ({@code INV-MON-01}).
 */
final class RiskBatteryWorld {

    /** The population's seed - moved only by a reviewed change, with the fingerprint. */
    static final long SEED = 13_302L;

    /** The first day a subject's calendar may start: a Monday. */
    static final Instant FIRST_DAY = Instant.parse("2027-01-04T00:00:00Z");

    /** A subject's calendar starts on one of this many days from {@link #FIRST_DAY}. */
    static final int START_DAYS = 730;

    /** The windows' anchor (plan section 12.4 as built by `-006`): Monday 1970-01-05 UTC. */
    static final Instant ANCHOR = Instant.parse("1970-01-05T00:00:00Z");

    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final CurrencyCode USD = CurrencyCode.of("USD");

    /** The corridors a beneficiary is drawn from: the three high-risk, then three benign (one high-risk in v2). */
    static final List<String> CORRIDORS = List.of("EUR-AED-AE", "EUR-GBP-KY", "EUR-USD-PA", "EUR-CHF-CH", "EUR-GBP-GB",
            "EUR-USD-US");

    /** The judged operations, in the fixtures' order. */
    static final List<RiskOperation> JUDGED = List.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL,
            RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER);

    private static final long HOUR = 3_600L;
    private static final long DAY = 86_400L;

    private RiskBatteryWorld() {}

    /** The shapes a subject's calendar takes - one per index modulo twelve (the walk). */
    enum Cohort {
        ORDINARY,
        BAND_EDGES,
        VELOCITY_BURST,
        NOVELTY,
        ATO,
        STRUCTURING,
        DORMANCY,
        ROUND,
        CORRIDOR,
        HARD,
        ABUSE,
        FAIL_SAFE
    }

    /**
     * One evaluation the world produced. {@code evaluated} is what the engine is given (every reading); {@code sealed} what
     * the seal carries - the same, or for a fail-safe the request's own readings (`RiskEvaluationRequest#judged`'s shape).
     * {@code mustRefuse} names the refusal the engine must answer {@code evaluated} with, when the fail-safe is the
     * engine's own refusal (another currency) rather than a planned unavailability, in which the engine never runs.
     */
    record Evaluation(int seq, Instant at, RiskOperation operation, EvaluationInputs evaluated, EvaluationInputs sealed,
            Optional<UnavailableCause> cause, Optional<String> mustRefuse, RiskAssessment.Subjects subjects, Money amount,
            Expected expected, List<String> tags) {

        boolean monitoring() {
            return operation == RiskOperation.MONITORING;
        }

        boolean failSafe() {
            return cause.isPresent();
        }
    }

    /** One subject: its cohort and its evaluations in calendar order. */
    record Generated(int index, Cohort cohort, List<Evaluation> evaluations) {}

    /** Subject {@code index}'s calendar. */
    static Generated generate(int index) {
        World world = new World(index);
        world.script();
        return new Generated(index, world.cohort, List.copyOf(world.evaluations));
    }

    /** The start of {@code window}'s tumbling bucket holding {@code at} - the battery's own arithmetic. */
    static Instant start(WindowKind window, Instant at) {
        long length = window.length().getSeconds();
        long offset = at.getEpochSecond() - ANCHOR.getEpochSecond();
        return ANCHOR.plusSeconds(Math.floorDiv(offset, length) * length);
    }

    // ------------------------------------------------------------------ the subject's state

    private static final class Account {
        final UUID id;
        final Instant opened;

        Account(UUID id, Instant opened) {
            this.id = id;
            this.opened = opened;
        }
    }

    /** A payee account - new until its first counted movement. */
    private static final class Payee {
        final UUID id;
        Instant firstCounted;

        Payee(UUID id, Instant firstCounted) {
            this.id = id;
            this.firstCounted = firstCounted;
        }
    }

    /** A cross-border beneficiary - its age is its registration's. */
    private static final class Beneficiary {
        final UUID id;
        final Instant registered;
        final String corridor;

        Beneficiary(UUID id, Instant registered, String corridor) {
            this.id = id;
            this.registered = registered;
            this.corridor = corridor;
        }
    }

    private static final class Device {
        final DeviceHash hash;
        Instant firstCounted;

        Device(DeviceHash hash, Instant firstCounted) {
            this.hash = hash;
            this.firstCounted = firstCounted;
        }
    }

    /**
     * A merchant: its suspension and its figures per thirty-day bucket - its other customers' beside this subject's counted
     * payments (profiles 0 to 2), or (profile 3) exactly at the C8 payment ratio rules' edges: both shares exact (EUR
     * figures 400 refunds and 20 chargebacks over 2,000 payments - 20 % and 1 %), both one refund or chargeback short of
     * the share (399, 19), both counts exactly at their floors with the share met (20 and 10 over 90), both one short of
     * the floor (19 and 9 over 90) - a variant per bucket.
     */
    private static final class Merchant {
        final UUID id;
        final long seed;
        int profile;
        Instant suspendedFrom;

        Merchant(UUID id, long seed, int profile, Instant suspendedFrom) {
            this.id = id;
            this.seed = seed;
            this.profile = profile;
            this.suspendedFrom = suspendedFrom;
        }

        /**
         * {@code count, refunds, chargebacks} in the bucket starting at {@code bucket} - the count with {@code own}, this
         * subject's payments to the merchant already counted there (the edge profile's figures are the merchant's whole).
         */
        long[] figures(Instant bucket, long own) {
            Random random = new Random(seed ^ (bucket.getEpochSecond() * 0x9E3779B97F4A7C15L));
            return switch (profile) {
                case 1 -> new long[] {100 + random.nextInt(5) + own, 18 + random.nextInt(5), random.nextInt(4)};
                case 2 -> new long[] {995 + random.nextInt(10) + own, random.nextInt(60), 9 + random.nextInt(3)};
                case 3 -> switch (random.nextInt(4)) {
                    case 0 -> new long[] {2_000, 400, 20};
                    case 1 -> new long[] {2_000, 399, 19};
                    case 2 -> new long[] {90, 20, 10};
                    default -> new long[] {90, 19, 9};
                };
                default -> {
                    long count = 200 + random.nextInt(400);
                    yield new long[] {count + own, random.nextInt((int) (count / 10)), random.nextInt(2)};
                }
            };
        }
    }

    private record Session(UUID id, RiskAssurance assurance) {}

    private record Monitored(Instant at, long minor, boolean inbound) {}

    // ------------------------------------------------------------------ the world

    private static final class World {

        final int index;
        final Cohort cohort;
        final Random rng;
        final UUID party;
        final UUID identity;
        final List<Account> accounts = new ArrayList<>();
        final List<Payee> payees = new ArrayList<>();
        final List<Beneficiary> beneficiaries = new ArrayList<>();
        final List<Device> devices = new ArrayList<>();
        final List<Merchant> merchants = new ArrayList<>();
        final Map<String, long[]> windows = new HashMap<>();
        final List<Monitored> monitored = new ArrayList<>();
        final List<Instant> credentialChanges = new ArrayList<>();
        final List<Instant> recoveries = new ArrayList<>();
        final List<Instant> authFailures = new ArrayList<>();
        final List<Instant> bnplApplications = new ArrayList<>();
        final List<Instant> refunds = new ArrayList<>();
        final List<Instant> chargebacks = new ArrayList<>();
        final List<Evaluation> evaluations = new ArrayList<>();
        final boolean authKnown;
        final boolean credentialKnown;
        final boolean recoveryKnown;
        final boolean kycKnown;
        final boolean screeningKnown;
        Instant kycFrom;
        Instant screeningFrom;
        Instant prohibitedFrom;
        RiskRating rating;
        Session session;
        Instant now;
        Instant lastJudged;
        int seq;

        World(int index) {
            this.index = index;
            this.cohort = Cohort.values()[Math.floorMod(index, Cohort.values().length)];
            this.rng = new Random(SEED * 0x2545F4914F6CDD1DL + index * 0x632BE59BD9B4E019L);
            this.party = uuid();
            this.identity = uuid();
            this.now = FIRST_DAY.plusSeconds(DAY * Math.floorMod(index * 7_919L, START_DAYS) + rng.nextInt((int) DAY));
            int accountCount = 1 + rng.nextInt(3);
            for (int i = 0; i < accountCount; i++) {
                long age = rng.nextInt(10) < 3 ? rng.nextInt(11) : 10 + rng.nextInt(890);
                accounts.add(new Account(uuid(), now.minusSeconds(age * DAY + rng.nextInt((int) DAY))));
            }
            int payeeCount = 2 + rng.nextInt(3);
            for (int i = 0; i < payeeCount; i++) {
                payees.add(new Payee(uuid(), rng.nextInt(10) < 4 ? now.minusSeconds((1 + rng.nextInt(120)) * DAY) : null));
            }
            int beneficiaryCount = 1 + rng.nextInt(3);
            for (int i = 0; i < beneficiaryCount; i++) {
                beneficiaries.add(new Beneficiary(uuid(), now.minusSeconds(rng.nextInt(91) * DAY + rng.nextInt((int) DAY)),
                        CORRIDORS.get(rng.nextInt(CORRIDORS.size()))));
            }
            int deviceCount = 1 + rng.nextInt(3);
            for (int i = 0; i < deviceCount; i++) {
                devices.add(new Device(hash(), rng.nextBoolean() ? now.minusSeconds((1 + rng.nextInt(200)) * DAY) : null));
            }
            int merchantCount = 2 + rng.nextInt(2);
            for (int i = 0; i < merchantCount; i++) {
                merchants.add(new Merchant(uuid(), rng.nextLong(), rng.nextInt(3),
                        rng.nextInt(100) < 8 ? now.plusSeconds(rng.nextInt(60) * DAY) : null));
            }
            authKnown = rng.nextInt(100) < 85;
            credentialKnown = rng.nextInt(100) < 90;
            recoveryKnown = rng.nextInt(100) < 90;
            kycKnown = rng.nextInt(100) < 95;
            screeningKnown = rng.nextInt(100) < 95;
            int kyc = rng.nextInt(100);
            kycFrom = kyc < 90 ? Instant.EPOCH : kyc < 95 ? now.plusSeconds(rng.nextInt(30) * DAY) : null;
            screeningFrom = rng.nextInt(100) < 4 ? now.plusSeconds(rng.nextInt(60) * DAY) : null;
            prohibitedFrom = rng.nextInt(100) < 2 ? now.plusSeconds(rng.nextInt(60) * DAY) : null;
            if (rng.nextInt(100) < 30) {
                credentialChanges.add(now.minusSeconds(rng.nextInt(2_000) * HOUR));
            }
            if (rng.nextInt(100) < 15) {
                recoveries.add(now.minusSeconds(rng.nextInt(2_000) * HOUR));
            }
            session = newSession();
        }

        /** One judged movement as it is being written. */
        private final class Move {
            final RiskOperation operation;
            final long minor;
            CurrencyCode currency = EUR;
            Account source;
            Payee payee;
            Beneficiary beneficiary;
            Device device;
            Merchant merchant;
            Session session;
            UnavailableCause cause;

            Move(RiskOperation operation, long minor) {
                this.operation = operation;
                this.minor = minor;
            }

            Move source(Account account) {
                source = account;
                return this;
            }

            Move payee(Payee to) {
                payee = to;
                return this;
            }

            Move beneficiary(Beneficiary to) {
                beneficiary = to;
                return this;
            }

            Move device(Device from) {
                device = from;
                return this;
            }

            Move merchant(Merchant paid) {
                merchant = paid;
                return this;
            }

            Move session(Session confirming) {
                session = confirming;
                return this;
            }

            Move cause(UnavailableCause unavailable) {
                cause = unavailable;
                return this;
            }

            Move currency(CurrencyCode code) {
                currency = code;
                return this;
            }

            void go() {
                judge(this);
            }
        }

        // -------------------------------------------------------------- the scripts

        void script() {
            switch (cohort) {
                case ORDINARY -> ordinary(8 + rng.nextInt(13));
                case BAND_EDGES -> bandEdges();
                case VELOCITY_BURST -> velocityBurst();
                case NOVELTY -> novelty();
                case ATO -> ato();
                case STRUCTURING -> structuring();
                case DORMANCY -> dormancy();
                case ROUND -> round();
                case CORRIDOR -> corridors();
                case HARD -> hard();
                case ABUSE -> abuse();
                case FAIL_SAFE -> failSafe();
            }
        }

        /** {@code count} everyday movements, facts sprinkled between them. */
        void ordinary(int count) {
            for (int i = 0; i < count; i++) {
                advance(gap());
                fact();
                advance(1 + rng.nextInt(300));
                RiskOperation operation = operation();
                Move move = move(operation, amount(operation));
                int sprinkle = rng.nextInt(100);
                if (sprinkle < 2) {
                    move.currency(USD);
                } else if (sprinkle < 4) {
                    move.cause(UnavailableCause.values()[rng.nextInt(UnavailableCause.values().length)]);
                }
                move.go();
            }
        }

        /** Every {@code AMOUNT_BAND} threshold of every judged version at t-1, t and t+1, and a zero amount. */
        void bandEdges() {
            ordinary(2);
            int first = Math.floorMod(index / 12, JUDGED.size());
            for (int k = 0; k < JUDGED.size(); k++) {
                RiskOperation operation = JUDGED.get((first + k) % JUDGED.size());
                TreeSet<Long> thresholds = new TreeSet<>();
                for (Line line : RiskBatteryOracles.v1(operation).lines()) {
                    if (line.kind() == RuleKind.AMOUNT_BAND) {
                        thresholds.add(line.number("threshold_minor"));
                    }
                }
                for (long threshold : thresholds) {
                    for (long delta = -1; delta <= 1; delta++) {
                        advance(HOUR + rng.nextInt((int) (6 * HOUR)));
                        move(operation, threshold + delta).session(rng.nextInt(10) < 7 ? multi() : single()).go();
                    }
                }
                advance(HOUR);
                move(operation, 0).go();
            }
        }

        /** A burst straddling a boundary of one window kind, the count and sum edges, the session edge, the D30 figures. */
        void velocityBurst() {
            ordinary(2);
            WindowKind window = WindowKind.values()[Math.floorMod(index / 12, WindowKind.values().length)];
            long step = switch (window) {
                case H1 -> 90L;
                case D1 -> 15 * 60L;
                case D7 -> 2 * HOUR;
                case D30 -> 8 * HOUR;
            };
            Instant boundary = start(window, now.plusSeconds(3 * step + HOUR)).plus(window.length());
            advanceTo(boundary.minusSeconds(3 * step));
            Account account = pick(accounts);
            Merchant merchant = pick(merchants);
            Session burst = multi();
            for (int i = 0; i < 6; i++) {
                RiskOperation operation = i % 2 == 0 ? RiskOperation.TRANSFER : RiskOperation.CARD_PAYMENT;
                Move move = move(operation, 500 + rng.nextInt(19_500)).session(burst);
                if (operation == RiskOperation.TRANSFER) {
                    move.source(account);
                } else {
                    move.merchant(merchant);
                }
                move.go();
                advance(step);
            }
            // The count edge: twelve movements from one account within a morning - the tenth sees 9, the eleventh 10 -
            // of the operations whose versions count the source account (a card payment carries none).
            morning(1);
            Payee seen = seenPayee();
            RiskOperation counted = List.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CROSS_BORDER,
                    RiskOperation.WALLET_PAYMENT).get(Math.floorMod(index / 48, 4));
            Beneficiary known = new Beneficiary(uuid(), now.minusSeconds(400 * DAY), CORRIDORS.get(5));
            for (int i = 0; i < 12; i++) {
                Move move = move(counted, 2_000 + rng.nextInt(7_000)).source(account).session(burst);
                if (counted == RiskOperation.TRANSFER) {
                    move.payee(seen);
                } else if (counted == RiskOperation.CROSS_BORDER) {
                    move.beneficiary(known);
                } else if (counted == RiskOperation.WALLET_PAYMENT) {
                    move.merchant(merchant);
                }
                move.go();
                advance(300 + rng.nextInt(300));
            }
            // The party's sum edge: 4,999.99 then exactly 5,000.00 before a movement, over several accounts.
            morning(1);
            long[] amounts = {1_000_00, 1_000_00, 1_000_00, 1_000_00, 999_99, 1, 50_00, 50_00};
            for (long minor : amounts) {
                move(RiskOperation.TRANSFER, minor).source(pick(accounts)).payee(seen).session(burst).go();
                advance(600);
            }
            // The session edge: seven checkouts in one session within the hour - the sixth sees 5.
            morning(1);
            advance(5 * 60);
            Session checkout = multi();
            for (int i = 0; i < 7; i++) {
                move(rng.nextBoolean() ? RiskOperation.CARD_PAYMENT : RiskOperation.WALLET_PAYMENT, 500 + rng.nextInt(2_500))
                        .session(checkout).merchant(merchant).go();
                advance(240 + rng.nextInt(60));
            }
            heavy(merchant);
            if (window == WindowKind.D30) {
                Instant bucket = start(WindowKind.D30, now).plus(WindowKind.D30.length());
                advanceTo(bucket.plusSeconds(DAY));
                int run = Math.floorMod(index / 48, 3);
                if (run == 2) {
                    partyRatios();
                } else if (run == 0) {
                    // A hundred and four small checkouts in one thirty-day bucket - the party's D30 count past 100.
                    for (int i = 0; i < 104; i++) {
                        if (i % 4 == 0) {
                            session = newSession();
                        }
                        move(RiskOperation.CARD_PAYMENT, 100 + rng.nextInt(900)).session(session)
                                .merchant(pick(merchants)).go();
                        advance(5 * HOUR);
                    }
                } else {
                    // Ten transfers of EUR 5,000 on ten days - the party's D30 sum exactly at EUR 50,000 after the tenth.
                    for (int i = 0; i < 10; i++) {
                        move(RiskOperation.TRANSFER, 5_000_00).source(pick(accounts)).payee(seen).session(burst).go();
                        advance(DAY);
                    }
                }
            }
            ordinary(2);
        }

        /**
         * The party's own shares at the C8 sweep's ratio edges (`MONITORING v1` rules 9 and 10): about two hundred small
         * checkouts in one thirty-day bucket, refunds and chargebacks placed against the party's counted movements - two
         * refunds while the count is at most 10 (one short of the floor of 3, the share met), the third once 12 are counted
         * (the floor exact; 20 % exactly at 15; one refund short of the share from 16 to 20); one chargeback while at most
         * 60 are counted (one short of the floor of 2), the second after (the floor exact; 1 % exactly at 200; one short
         * from 201).
         */
        void partyRatios() {
            refunds.add(now);
            refunds.add(now);
            chargebacks.add(now);
            boolean thirdRefund = false;
            boolean secondChargeback = false;
            for (int i = 0; i < 215; i++) {
                long counted = window(VelocityDimension.PARTY, party.toString(), WindowKind.D30, now)[0];
                if (!thirdRefund && counted >= 12) {
                    refunds.add(now);
                    thirdRefund = true;
                    advance(60);
                }
                if (!secondChargeback && counted >= 60) {
                    chargebacks.add(now);
                    secondChargeback = true;
                    advance(60);
                }
                if (i % 4 == 0) {
                    session = newSession();
                }
                move(RiskOperation.CARD_PAYMENT, 100 + rng.nextInt(900)).session(session).merchant(pick(merchants)).go();
                advance(3 * HOUR);
            }
        }

        /**
         * Every weight at once - the clamp: five checkouts of EUR 1,000 in one session, counted while they still permit,
         * then three authentication failures and a sixth from a never-seen device (the session's count, the party's sum,
         * the failures and the device: 1,200, clamped); then a withdrawal from a three-day-old account and an unseen device
         * on that day's sum - 800 exactly, or 1,100 after a recovery.
         */
        void heavy(Merchant merchant) {
            morning(1);
            Session checkout = multi();
            Device known = new Device(hash(), now.minusSeconds(400 * DAY));
            devices.add(known);
            for (int i = 0; i < 5; i++) {
                move(i % 2 == 0 ? RiskOperation.CARD_PAYMENT : RiskOperation.WALLET_PAYMENT, 1_000_00).session(checkout)
                        .merchant(merchant).device(known).go();
                advance(300);
            }
            for (int i = 0; i < 3; i++) {
                authFailures.add(now);
                advance(60);
            }
            Device unseen = new Device(hash(), null);
            devices.add(unseen);
            move(Math.floorMod(index / 48, 2) == 0 ? RiskOperation.CARD_PAYMENT : RiskOperation.WALLET_PAYMENT, 20_00)
                    .session(checkout).merchant(merchant).device(unseen).go();
            advance(600);
            Account young = new Account(uuid(), now.minusSeconds(3 * DAY));
            accounts.add(young);
            if (Math.floorMod(index / 96, 2) == 0) {
                recoveries.add(now);
                advance(60);
            }
            move(RiskOperation.WITHDRAWAL, 100_00).source(young).device(new Device(hash(), null)).session(multi()).go();
        }

        /** A payee, a device and a beneficiary at their lookback's edges: never seen, 29 days, 30 days. */
        void novelty() {
            ordinary(3);
            Payee fresh = new Payee(uuid(), null);
            payees.add(fresh);
            Device device = new Device(hash(), null);
            devices.add(device);
            morning(1);
            Instant first = now;
            Session confirming = multi();
            move(RiskOperation.TRANSFER, 4_000).payee(fresh).device(device).session(confirming).go();
            advance(HOUR);
            move(RiskOperation.CARD_PAYMENT, 3_000).device(device).session(confirming).go();
            // The account age's edge (WITHDRAWAL v1's "younger than 7 days"): a wallet opened now, withdrawn from at 6 and 7.
            Account opened = new Account(uuid(), now);
            accounts.add(opened);
            for (long days : new long[] {6, 7}) {
                advanceTo(opened.opened.plusSeconds(days * DAY + 3 * HOUR));
                move(RiskOperation.WITHDRAWAL, 5_000).source(opened).session(multi()).go();
            }
            Beneficiary beneficiary = new Beneficiary(uuid(), first.plusSeconds(HOUR), CORRIDORS.get(3 + rng.nextInt(3)));
            beneficiaries.add(beneficiary);
            for (long days : new long[] {29, 30, 31}) {
                advanceTo(first.plusSeconds(days * DAY + 2 * HOUR));
                move(RiskOperation.TRANSFER, 4_500).payee(fresh).device(device).session(confirming).go();
                advance(HOUR);
                move(RiskOperation.CARD_PAYMENT, 2_500).device(device).session(confirming).go();
                advanceTo(beneficiary.registered.plusSeconds(days * DAY + 4 * HOUR));
                move(RiskOperation.CROSS_BORDER, 20_000).beneficiary(beneficiary).session(confirming).go();
            }
            ordinary(3);
        }

        /** A credential change, a new payee, a withdrawal 23 or 24 hours later; a recovery and movements 71 or 72 hours on. */
        void ato() {
            ordinary(3);
            boolean inside = Math.floorMod(index / 12, 2) == 0;
            morning(1);
            Instant changed = now;
            credentialChanges.add(changed);
            advance(30 * 60);
            Payee fresh = new Payee(uuid(), null);
            payees.add(fresh);
            move(RiskOperation.TRANSFER, 5_000).payee(fresh).session(rng.nextBoolean() ? multi() : single()).go();
            advanceTo(changed.plusSeconds((inside ? 23 : 24) * HOUR + 600));
            move(RiskOperation.WITHDRAWAL, 30_000).session(multi()).go();
            advance(DAY * 3);
            Instant recovered = now;
            recoveries.add(recovered);
            advanceTo(recovered.plusSeconds((inside ? 71 : 72) * HOUR + 1_200));
            move(RiskOperation.TRANSFER, 6_000).session(multi()).go();
            advance(600);
            move(RiskOperation.WITHDRAWAL, 10_000).session(multi()).go();
            ordinary(3);
        }

        /** Inbound credits at the structuring edges in one day; then an inflow and 80 % (or a cent less) out again. */
        void structuring() {
            ordinary(2);
            int variant = Math.floorMod(index / 12, 4);
            long[][] days = {
                {900_00, 900_00, 700_00},
                {900_00, 900_00, 700_01},
                {999_99, 999_99, 1_000_00, 3},
                {800_00, 800_00, 800_00, 800_00}};
            morning(2);
            for (long minor : days[variant]) {
                inbound(minor);
                advance(20 * 60);
            }
            morning(1);
            inbound(1_000_00);
            advance(HOUR);
            move(RiskOperation.TRANSFER, variant % 2 == 0 ? 800_00 : 799_99).payee(seenPayee()).session(multi()).go();
            ordinary(3);
        }

        /** Movements, a gap of 89, 90 or 91 days to the instant, then EUR 1,000 (or a cent less) in one day. */
        void dormancy() {
            ordinary(3);
            long gap = 89 + Math.floorMod(index / 12, 3);
            // An inflow is monitored and never refused: the party's last movement before the gap is exactly this one.
            advance(HOUR);
            inbound(10_00 + rng.nextInt(100_00));
            advanceTo(now.plusSeconds(gap * DAY));
            long amount = Math.floorMod(index / 36, 2) == 0 ? 1_000_00 : 999_99;
            move(RiskOperation.TRANSFER, amount).payee(seenPayee()).session(multi()).go();
            ordinary(2);
        }

        /** Round amounts and their neighbours, out and in. */
        void round() {
            ordinary(2);
            long[] amounts = {1_000_00, 2_000_00, 5_000_00, 999_99, 1_000_01, 10_000_00, 3_000_00};
            for (long minor : amounts) {
                advance(2 * HOUR + rng.nextInt((int) HOUR));
                move(JUDGED.get(rng.nextInt(JUDGED.size())), minor).session(multi()).go();
            }
            advance(HOUR);
            inbound(3_000_00);
            advance(HOUR);
            inbound(2_999_99);
            ordinary(2);
        }

        /** One cross-border movement over every corridor, multi-factor; one more single-factor. */
        void corridors() {
            ordinary(2);
            int first = Math.floorMod(index / 12, CORRIDORS.size());
            for (int k = 0; k < CORRIDORS.size(); k++) {
                Beneficiary beneficiary = new Beneficiary(uuid(), now.minusSeconds((100 + rng.nextInt(100)) * DAY),
                        CORRIDORS.get((first + k) % CORRIDORS.size()));
                beneficiaries.add(beneficiary);
                advance(3 * HOUR + rng.nextInt((int) HOUR));
                move(RiskOperation.CROSS_BORDER, 5_000 + rng.nextInt(30_000)).beneficiary(beneficiary).session(multi()).go();
            }
            advance(HOUR);
            move(RiskOperation.CROSS_BORDER, 20_000).beneficiary(beneficiaries.get(beneficiaries.size() - 1))
                    .session(single()).go();
            ordinary(2);
        }

        /** A screening hit, a PROHIBITED disposition, KYC not yet approved, or a merchant suspended - before and after. */
        void hard() {
            ordinary(2);
            int variant = Math.floorMod(index / 12, 4);
            Instant from = now.plusSeconds(HOUR);
            switch (variant) {
                case 0 -> screeningFrom = from;
                case 1 -> prohibitedFrom = from;
                case 2 -> kycFrom = now.plusSeconds(5 * DAY);
                default -> merchants.get(0).suspendedFrom = from;
            }
            for (int round = 0; round < 2; round++) {
                for (RiskOperation operation : JUDGED) {
                    advance(2 * HOUR + rng.nextInt((int) HOUR));
                    Move move = move(operation, 1_000 + rng.nextInt(40_000)).session(multi());
                    if (operation == RiskOperation.CARD_PAYMENT || operation == RiskOperation.WALLET_PAYMENT) {
                        move.merchant(merchants.get(0));
                    }
                    move.go();
                }
                advance(6 * DAY);
            }
            if (variant == 0) {
                // A screened party's day of inflows below EUR 1,000 summing past EUR 2,500: 600 + 500 - the sweep's clamp.
                morning(1);
                for (long minor : new long[] {900_00, 900_00, 800_00}) {
                    inbound(minor);
                    advance(20 * 60);
                }
            }
            ordinary(2);
        }

        /**
         * Authentication failures, BNPL applications, the party's refunds and chargebacks; the first merchant exactly at the
         * payment ratio rules' edges (profile 3), the second near them - each paid by card and by wallet.
         */
        void abuse() {
            ordinary(2);
            int variant = Math.floorMod(index / 12, 3);
            merchants.get(0).profile = 3;
            merchants.get(1).profile = 1 + Math.floorMod(index / 36, 2);
            morning(1);
            advance(HOUR);
            for (int i = 0; i < 2 + variant; i++) {
                authFailures.add(now);
                advance(120);
            }
            move(RiskOperation.CARD_PAYMENT, 2_000 + rng.nextInt(3_000)).merchant(merchants.get(0)).session(multi()).go();
            Instant week = start(WindowKind.D7, now).plus(WindowKind.D7.length());
            advanceTo(week.plusSeconds(DAY));
            for (int i = 0; i < 2 + variant; i++) {
                bnplApplications.add(now);
                advance(HOUR);
            }
            move(RiskOperation.TRANSFER, 3_000).payee(seenPayee()).session(multi()).go();
            Instant month = start(WindowKind.D30, now).plus(WindowKind.D30.length());
            advanceTo(month.plusSeconds(DAY));
            for (int i = 0; i < 8; i++) {
                move(i % 2 == 0 ? RiskOperation.CARD_PAYMENT : RiskOperation.WALLET_PAYMENT, 1_000 + rng.nextInt(9_000))
                        .merchant(merchants.get((i / 2) % 2)).session(multi()).go();
                advance(10 * HOUR);
                if (i < 1 + variant) {
                    refunds.add(now);
                }
                if (i < variant) {
                    chargebacks.add(now);
                }
            }
            ordinary(3);
        }

        /** Every judged version's fail-safe at each band and its edge, under each cause in turn; amounts in USD. */
        void failSafe() {
            ordinary(2);
            int cause = Math.floorMod(index / 12, UnavailableCause.values().length);
            for (RiskOperation operation : JUDGED) {
                List<Long> amounts = new ArrayList<>();
                for (RiskBatteryOracles.Edge edge : RiskBatteryOracles.v1(operation).failSafe()) {
                    if (edge.fromMinor() > 0) {
                        amounts.add(edge.fromMinor() - 1);
                        amounts.add(edge.fromMinor());
                        amounts.add(edge.fromMinor() + 1);
                    }
                }
                amounts.add((long) rng.nextInt(50_000));
                amounts.add(100_000L + rng.nextInt(2_000_000));
                for (long minor : amounts) {
                    advance(HOUR + rng.nextInt((int) HOUR));
                    UnavailableCause unavailable = UnavailableCause.values()[cause];
                    cause = (cause + 1) % UnavailableCause.values().length;
                    move(operation, minor).cause(unavailable).go();
                }
                advance(HOUR);
                move(operation, 1_000 + rng.nextInt(400_000)).currency(USD).session(multi()).go();
            }
            ordinary(2);
        }

        // -------------------------------------------------------------- the draws

        long gap() {
            int r = rng.nextInt(100);
            if (r < 30) {
                return 60 + rng.nextInt(10_740);
            }
            if (r < 60) {
                return 3 * HOUR + rng.nextInt((int) (45 * HOUR));
            }
            if (r < 85) {
                return 2 * DAY + rng.nextInt((int) (18 * DAY));
            }
            return 20 * DAY + rng.nextInt((int) (100 * DAY));
        }

        RiskOperation operation() {
            int r = rng.nextInt(100);
            return r < 30 ? RiskOperation.TRANSFER : r < 50 ? RiskOperation.WITHDRAWAL
                    : r < 70 ? RiskOperation.CARD_PAYMENT : r < 85 ? RiskOperation.WALLET_PAYMENT : RiskOperation.CROSS_BORDER;
        }

        /** Mostly small; sometimes past every band; one in twenty at a threshold of the version, one either side. */
        long amount(RiskOperation operation) {
            int r = rng.nextInt(100);
            if (r < 45) {
                return 1 + rng.nextInt(49_999);
            }
            if (r < 65) {
                return 50_000 + rng.nextInt(50_000);
            }
            if (r < 80) {
                return 100_000 + rng.nextInt(150_000);
            }
            if (r < 90) {
                return 250_000 + rng.nextInt(750_000);
            }
            if (r < 95) {
                return 1_000_000 + rng.nextInt(4_000_000);
            }
            List<Long> edges = new ArrayList<>();
            Statement statement = RiskBatteryOracles.v1(operation);
            for (Line line : statement.lines()) {
                if (line.kind() == RuleKind.AMOUNT_BAND) {
                    edges.add(line.number("threshold_minor"));
                }
            }
            for (RiskBatteryOracles.Edge edge : statement.failSafe()) {
                if (edge.fromMinor() > 0) {
                    edges.add(edge.fromMinor());
                }
            }
            return Math.max(0, edges.get(rng.nextInt(edges.size())) + rng.nextInt(3) - 1);
        }

        /** A fact of the calendar before a movement, sometimes. */
        void fact() {
            int r = rng.nextInt(100);
            if (r < 6) {
                credentialChanges.add(now);
            } else if (r < 9) {
                recoveries.add(now);
            } else if (r < 15) {
                inbound(1 + rng.nextInt(300_000));
            } else if (r < 18) {
                int failures = 1 + rng.nextInt(4);
                for (int i = 0; i < failures; i++) {
                    authFailures.add(now);
                    advance(60 + rng.nextInt(120));
                }
            } else if (r < 20) {
                bnplApplications.add(now);
            } else if (r < 22) {
                refunds.add(now);
            } else if (r < 23) {
                chargebacks.add(now);
            }
        }

        /** A movement of {@code operation} with this subject's instruments drawn - the caller narrows what it must. */
        Move move(RiskOperation operation, long minor) {
            Move move = new Move(operation, minor);
            if (operation != RiskOperation.CARD_PAYMENT) {
                move.source(pick(accounts));
            }
            if (operation == RiskOperation.TRANSFER) {
                move.payee(pick(payees));
            }
            if (operation == RiskOperation.CROSS_BORDER) {
                move.beneficiary(pick(beneficiaries));
            }
            if (rng.nextInt(10) < 7) {
                move.device(pick(devices));
            }
            if (operation == RiskOperation.CARD_PAYMENT || operation == RiskOperation.WALLET_PAYMENT) {
                move.merchant(pick(merchants));
            }
            int s = rng.nextInt(20);
            if (s >= 3) {
                if (s < 8) {
                    session = newSession();
                }
                move.session(session);
            }
            return move;
        }

        Session newSession() {
            return new Session(uuid(), rng.nextInt(100) < 55 ? RiskAssurance.MULTI_FACTOR : RiskAssurance.SINGLE_FACTOR);
        }

        Session multi() {
            session = new Session(uuid(), RiskAssurance.MULTI_FACTOR);
            return session;
        }

        Session single() {
            session = new Session(uuid(), RiskAssurance.SINGLE_FACTOR);
            return session;
        }

        /** A payee already counted long ago - never new, never at an edge. */
        Payee seenPayee() {
            Payee seen = new Payee(uuid(), now.minusSeconds(400 * DAY));
            payees.add(seen);
            return seen;
        }

        /** 08:00 UTC of the {@code days}-th day from now - a fresh day bucket. */
        void morning(int days) {
            Instant day = start(WindowKind.D1, now).plusSeconds(days * DAY + 8 * HOUR);
            advanceTo(day.isAfter(now) ? day : day.plusSeconds(DAY));
        }

        void advance(long seconds) {
            now = now.plusSeconds(Math.max(1, seconds));
        }

        void advanceTo(Instant instant) {
            now = instant.isAfter(now) ? instant : now.plusSeconds(1);
        }

        <T> T pick(List<T> list) {
            return list.get(rng.nextInt(list.size()));
        }

        UUID uuid() {
            return new UUID(rng.nextLong(), rng.nextLong());
        }

        DeviceHash hash() {
            byte[] bytes = new byte[32];
            rng.nextBytes(bytes);
            return DeviceHash.parse(HexFormat.of().formatHex(bytes));
        }

        // -------------------------------------------------------------- judging and counting

        /** An inbound credit: posted, monitored, never judged. */
        void inbound(long minor) {
            monitored.add(new Monitored(now, minor, true));
            monitor(Money.ofMinorUnits(minor, EUR), Optional.empty());
        }

        void judge(Move move) {
            Instant at = now;
            Money amount = Money.ofMinorUnits(move.minor, move.currency);
            boolean payment = move.operation == RiskOperation.CARD_PAYMENT
                    || move.operation == RiskOperation.WALLET_PAYMENT;
            Optional<RiskEvaluationRequest.Destination> destination = Optional.empty();
            if (move.payee != null) {
                destination = Optional.of(new RiskEvaluationRequest.Destination(RiskEvaluationRequest.DestinationKind.ACCOUNT,
                        move.payee.id));
            } else if (move.beneficiary != null) {
                destination = Optional.of(new RiskEvaluationRequest.Destination(
                        RiskEvaluationRequest.DestinationKind.BENEFICIARY, move.beneficiary.id));
            }
            Optional<UUID> session = payment && move.session != null ? Optional.of(move.session.id()) : Optional.empty();
            RiskAssessment.Subjects subjects = new RiskAssessment.Subjects(party, Optional.of(identity),
                    Optional.ofNullable(move.source).map(account -> account.id), destination,
                    Optional.ofNullable(move.device).map(device -> device.hash),
                    Optional.ofNullable(move.merchant).map(merchant -> merchant.id), session);
            EvaluationInputs own = own(move, amount, subjects).build();
            List<String> tags = tags(at);
            Statement pinned = RiskBatteryOracles.v1(move.operation);
            Evaluation evaluation;
            if (move.cause != null) {
                Expected expected = RiskBatteryOracles.failSafe(
                        move.cause.unpinned() ? Optional.empty() : Optional.of(pinned), move.operation, move.cause, amount);
                evaluation = new Evaluation(seq++, at, move.operation, own, own, Optional.of(move.cause), Optional.empty(),
                        subjects, amount, expected, tags);
            } else {
                EvaluationInputs full = readings(move, amount, subjects, at);
                Expected judged = RiskBatteryOracles.judge(pinned, full);
                if (judged.isRefused()) {
                    Expected expected = RiskBatteryOracles.failSafe(Optional.of(pinned), move.operation,
                            UnavailableCause.ENGINE_REFUSED, amount);
                    evaluation = new Evaluation(seq++, at, move.operation, full, own,
                            Optional.of(UnavailableCause.ENGINE_REFUSED), judged.refusal(), subjects, amount, expected, tags);
                } else {
                    evaluation = new Evaluation(seq++, at, move.operation, full, full, Optional.empty(), Optional.empty(),
                            subjects, amount, judged, tags);
                }
            }
            evaluations.add(evaluation);
            lastJudged = at;
            if (evaluation.expected().permits()) {
                count(move, subjects, amount, at);
            }
        }

        /** Which window boundaries this movement sits just across from the subject's previous one. */
        List<String> tags(Instant at) {
            List<String> tags = new ArrayList<>();
            if (lastJudged != null) {
                for (WindowKind window : WindowKind.values()) {
                    Instant bucket = start(window, at);
                    if (start(window, lastJudged).equals(bucket.minus(window.length()))
                            && Duration.between(lastJudged, at).compareTo(window.length()) < 0) {
                        tags.add("boundary:" + window);
                    }
                }
            }
            return tags;
        }

        /** The request's own readings - what a fail-safe seals (`RiskEvaluationRequest#judged`'s shape). */
        EvaluationInputs.Builder own(Move move, Money amount, RiskAssessment.Subjects subjects) {
            EvaluationInputs.Builder in = EvaluationInputs.builder(move.operation)
                    .subject(SignalSubject.of(SubjectKind.PARTY, party))
                    .subject(SignalSubject.of(SubjectKind.IDENTITY, identity))
                    .amount(amount);
            subjects.sourceAccount().ifPresent(id -> in.subject(SignalSubject.of(SubjectKind.ACCOUNT, id)));
            if (move.beneficiary != null) {
                in.subject(SignalSubject.of(SubjectKind.BENEFICIARY, move.beneficiary.id));
                in.corridor(move.beneficiary.corridor);
            }
            subjects.device().ifPresent(hash -> in.subject(SignalSubject.device(hash)));
            subjects.merchant().ifPresent(id -> in.subject(SignalSubject.of(SubjectKind.MERCHANT, id)));
            subjects.session().ifPresent(id -> in.subject(SignalSubject.of(SubjectKind.SESSION, id)));
            return in;
        }

        /** Every reading T-eval would seal for {@code move} at {@code at}: the figures before it, the sightings, the facts. */
        EvaluationInputs readings(Move move, Money amount, RiskAssessment.Subjects subjects, Instant at) {
            EvaluationInputs.Builder in = own(move, amount, subjects);
            for (Map.Entry<VelocityDimension, String> carried : carried(subjects).entrySet()) {
                for (WindowKind window : WindowKind.values()) {
                    long[] figures = window(carried.getKey(), carried.getValue(), window, at);
                    in.velocity(carried.getKey(), window, figures[0], figures[1]);
                }
            }
            if (move.payee != null) {
                in.payeeFirstSeen(move.payee.firstCounted == null ? FirstSeen.never()
                        : FirstSeen.daysAgo(days(move.payee.firstCounted, at)));
            } else if (move.beneficiary != null) {
                in.payeeFirstSeen(FirstSeen.daysAgo(days(move.beneficiary.registered, at)));
            }
            if (move.device != null) {
                in.deviceFirstSeen(move.device.firstCounted == null ? FirstSeen.never()
                        : FirstSeen.daysAgo(days(move.device.firstCounted, at)));
            }
            if (move.session != null) {
                in.sessionAssurance(move.session.assurance());
            }
            if (authKnown) {
                for (WindowKind window : WindowKind.values()) {
                    in.authFailures(window, inBucket(authFailures, window, at));
                }
            }
            if (credentialKnown) {
                in.credentialChange(lastSeen(credentialChanges, at));
            }
            if (recoveryKnown) {
                in.recovery(lastSeen(recoveries, at));
            }
            if (move.source != null) {
                in.accountAgeDays(days(move.source.opened, at));
            }
            if (kycKnown) {
                in.kycApproved(kycFrom != null && !at.isBefore(kycFrom));
            }
            if (screeningKnown) {
                in.screeningHit(screeningFrom != null && !at.isBefore(screeningFrom));
            }
            if (prohibitedFrom != null && !at.isBefore(prohibitedFrom)) {
                in.profileRating(RiskRating.PROHIBITED);
            } else if (rating != null) {
                in.profileRating(rating);
            }
            if (move.merchant != null) {
                in.merchantSuspended(move.merchant.suspendedFrom != null && !at.isBefore(move.merchant.suspendedFrom));
                // The merchant's shares (`-014`): its other customers' figures, this subject's counted payments beside them.
                long[] figures = move.merchant.figures(start(WindowKind.D30, at),
                        window(VelocityDimension.MERCHANT, move.merchant.id.toString(), WindowKind.D30, at)[0]);
                in.monitoring(new MonitoringReadings(Reading.absent(), Map.of(), Map.of(),
                        Map.of(WindowKind.D30, new Ratio(figures[1], figures[0])),
                        Map.of(WindowKind.D30, new Ratio(figures[2], figures[0])), Map.of()));
            }
            return in.build();
        }

        /** The dimensions a movement counts in, by the reference each is keyed on - declaration order. */
        Map<VelocityDimension, String> carried(RiskAssessment.Subjects subjects) {
            Map<VelocityDimension, String> carried = new EnumMap<>(VelocityDimension.class);
            carried.put(VelocityDimension.PARTY, subjects.party().toString());
            subjects.sourceAccount().ifPresent(id -> carried.put(VelocityDimension.SOURCE_ACCOUNT, id.toString()));
            subjects.destination().ifPresent(to -> carried.put(VelocityDimension.DESTINATION, to.id().toString()));
            subjects.device().ifPresent(hash -> carried.put(VelocityDimension.DEVICE, hash.value()));
            subjects.merchant().ifPresent(id -> carried.put(VelocityDimension.MERCHANT, id.toString()));
            subjects.session().ifPresent(id -> carried.put(VelocityDimension.SESSION, id.toString()));
            return carried;
        }

        /** A permitted movement counted - every window of every dimension it carries, its first sightings - then posted. */
        void count(Move move, RiskAssessment.Subjects subjects, Money amount, Instant at) {
            for (Map.Entry<VelocityDimension, String> carried : carried(subjects).entrySet()) {
                for (WindowKind window : WindowKind.values()) {
                    long[] figures = windows.computeIfAbsent(key(carried.getKey(), carried.getValue(), window, at),
                            ignored -> new long[2]);
                    figures[0]++;
                    figures[1] += amount.minorUnits();
                }
            }
            if (move.payee != null && move.payee.firstCounted == null) {
                move.payee.firstCounted = at;
            }
            if (move.device != null && move.device.firstCounted == null) {
                move.device.firstCounted = at;
            }
            monitored.add(new Monitored(at, amount.minorUnits(), false));
            monitor(amount, move.beneficiary == null ? Optional.empty() : Optional.of(move.beneficiary.corridor));
        }

        /** The sweep's evaluation of the party after a monitored movement: the {@code MONITORING} version's rating. */
        void monitor(Money amount, Optional<String> corridor) {
            Instant at = now;
            EvaluationInputs.Builder in = EvaluationInputs.builder(RiskOperation.MONITORING)
                    .subject(SignalSubject.of(SubjectKind.PARTY, party))
                    .subject(SignalSubject.of(SubjectKind.IDENTITY, identity))
                    .amount(amount);
            corridor.ifPresent(in::corridor);
            Map<WindowKind, Flow> flows = new EnumMap<>(WindowKind.class);
            Map<WindowKind, Amounts> amounts = new EnumMap<>(WindowKind.class);
            Map<WindowKind, Ratio> refundShares = new EnumMap<>(WindowKind.class);
            Map<WindowKind, Ratio> chargebackShares = new EnumMap<>(WindowKind.class);
            Map<WindowKind, Long> bnpl = new EnumMap<>(WindowKind.class);
            for (WindowKind window : WindowKind.values()) {
                long[] figures = window(VelocityDimension.PARTY, party.toString(), window, at);
                in.velocity(VelocityDimension.PARTY, window, figures[0], figures[1]);
                Instant bucket = start(window, at);
                long inflow = 0;
                long outflow = 0;
                List<Long> minor = new ArrayList<>();
                for (Monitored entry : monitored) {
                    if (!entry.at().isBefore(bucket) && !entry.at().isAfter(at)) {
                        minor.add(entry.minor());
                        if (entry.inbound()) {
                            inflow += entry.minor();
                        } else {
                            outflow += entry.minor();
                        }
                    }
                }
                flows.put(window, new Flow(inflow, outflow));
                amounts.put(window, new Amounts(minor));
                if (figures[0] > 0) {
                    refundShares.put(window, new Ratio(inBucket(refunds, window, at), figures[0]));
                    chargebackShares.put(window, new Ratio(inBucket(chargebacks, window, at), figures[0]));
                }
                bnpl.put(window, inBucket(bnplApplications, window, at));
            }
            if (screeningKnown) {
                in.screeningHit(screeningFrom != null && !at.isBefore(screeningFrom));
            }
            if (recoveryKnown) {
                in.recovery(lastSeen(recoveries, at));
            }
            in.monitoring(new MonitoringReadings(dormancy(at), flows, amounts, refundShares, chargebackShares, bnpl));
            EvaluationInputs inputs = in.build();
            Expected expected = RiskBatteryOracles.judge(RiskBatteryOracles.v1(RiskOperation.MONITORING), inputs);
            evaluations.add(new Evaluation(seq++, at, RiskOperation.MONITORING, inputs, inputs, Optional.empty(),
                    Optional.empty(), new RiskAssessment.Subjects(party, Optional.of(identity), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty()),
                    amount, expected, List.of()));
            rating = expected.rating().orElseThrow();
        }

        /**
         * The whole days between the party's last monitored movement before today's bucket and the first one in it - absent
         * when nothing was monitored before today.
         */
        Reading<Integer> dormancy(Instant at) {
            Instant today = start(WindowKind.D1, at);
            Instant first = null;
            Instant previous = null;
            for (Monitored entry : monitored) {
                if (entry.at().isBefore(today)) {
                    previous = entry.at();
                } else if (first == null && !entry.at().isAfter(at)) {
                    first = entry.at();
                }
            }
            return previous == null || first == null ? Reading.absent() : Reading.of(days(previous, first));
        }

        long[] window(VelocityDimension dimension, String reference, WindowKind window, Instant at) {
            long[] figures = windows.get(key(dimension, reference, window, at));
            return figures == null ? new long[2] : figures.clone();
        }

        String key(VelocityDimension dimension, String reference, WindowKind window, Instant at) {
            return dimension + "|" + reference + "|" + window + "|" + start(window, at).getEpochSecond();
        }

        long inBucket(List<Instant> facts, WindowKind window, Instant at) {
            Instant bucket = start(window, at);
            long count = 0;
            for (Instant fact : facts) {
                if (!fact.isBefore(bucket) && !fact.isAfter(at)) {
                    count++;
                }
            }
            return count;
        }

        LastSeen lastSeen(List<Instant> facts, Instant at) {
            Instant last = null;
            for (Instant fact : facts) {
                if (!fact.isAfter(at) && (last == null || fact.isAfter(last))) {
                    last = fact;
                }
            }
            return last == null ? LastSeen.never()
                    : LastSeen.hoursAgo(Math.min(EvaluationInputs.MAX_HOURS, Duration.between(last, at).toHours()));
        }

        int days(Instant from, Instant at) {
            return from.isAfter(at) ? 0 : (int) Math.min(EvaluationInputs.MAX_DAYS, Duration.between(from, at).toDays());
        }
    }
}
