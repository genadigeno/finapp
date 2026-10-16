/**
 * The pure, deterministic credit arithmetic two bounded contexts share (`P12-TSK-002`; ADR-0101 decision 5,
 * {@code MODULE_ARCHITECTURE.md} M12 and {@code creditmath}).
 *
 * <p><strong>What is here.</strong> Lending's three version-1 servicing engines, moved from {@code lending} unchanged -
 * every figure, refusal and detail the same, so every Phase 11 agreement replays {@code IDENTICAL}:
 * {@link com.finapp.creditmath.ScheduleEngineV1} (the level actual-day monthly annuity, over
 * {@link com.finapp.creditmath.ScheduleTerms}), {@link com.finapp.creditmath.AccrualEngineV1} (daily ACT/365F accrual,
 * one rounding per period, over {@link com.finapp.creditmath.AccrualTerms}) and
 * {@link com.finapp.creditmath.AllocationEngineV1} (oldest due first, the pinned component order, over
 * {@link com.finapp.creditmath.AllocationRules} and {@link com.finapp.creditmath.DueState}); the
 * {@link com.finapp.creditmath.Act365Fixed} arithmetic two of them share; their input and output records; the engine
 * conventions they name ({@link com.finapp.creditmath.DayCountConvention},
 * {@link com.finapp.creditmath.AllocationSequence}, {@link com.finapp.creditmath.AllocationComponent},
 * {@link com.finapp.creditmath.OverpaymentTreatment}); and the version identities every fact computed with them records
 * ({@link com.finapp.creditmath.EngineVersion} over {@link com.finapp.creditmath.LendingEngine}, every member's name and
 * number unchanged - lending's statement engine, which stays in {@code lending}, keeps its identity here beside the
 * others, one registry of the versions an agreement pins).
 *
 * <p><strong>What is not.</strong> No product vocabulary of either context - lending's {@code LoanTerms},
 * {@code LoanProduct}, {@code LoanKind}, {@code RateType} and {@code RepaymentFrequency} stay in {@code lending}, whose
 * {@code LoanEngines} maps its terms onto these records, and {@code bnpl}'s products and plans stay in {@code bnpl}. No
 * state, no I/O, no clock, no Spring, no floating point ({@code INV-MON-01}): the same inputs give the same figures on
 * every instance and in every JVM - the ten-instance answer is determinism, proven by the two-JVM comparisons. A
 * semantic change to an engine is a new {@code EngineVersion} member beside the old, never an edit ({@code INV-LND-05},
 * {@code INV-LND-09}).
 *
 * <p><strong>Edges.</strong> {@code creditmath -> sharedkernel} only; {@code lending -> creditmath} and
 * {@code bnpl -> creditmath} are its only dependants ({@code CreditmathModuleIsolationTest} and the sibling isolation
 * tests). It is a module without a bounded context, as {@code sharedkernel}'s {@code Money} is arithmetic every context
 * shares.
 */
package com.finapp.creditmath;
