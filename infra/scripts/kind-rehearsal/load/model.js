// The open model (P16-TSK-006, ADR-0126 decision 2). Every operation of a load profile becomes one scenario of this shape: iterations
// start at the profile's arrival rate whatever the platform answers, so a slow answer queues work as real clients would and its waiting
// is counted, instead of slowing the generator down until the latency it reports looks fine (coordinated omission). The pool of
// virtual users is only where in-flight iterations wait: preAllocatedVUs at start, maxVUs at most. An iteration that finds no free one
// is dropped, and kind-rehearsal/load.sh records a run with any dropped iteration as invalid.
//
// This is the one place the executor is chosen. The harness reads what k6 will actually run (`k6 inspect`) before any load and refuses a
// scenario whose executor is not an arrival-rate one; the probe that replaces this file with a closed model is refused there.
//
// The profiles allocate every virtual user before the load (preAllocatedVUs equal to maxVUs): a user initialised during the run costs its
// init code at the moment it is needed, and k6 drops an iteration that finds none ready (the sixth run dropped 111 that way, at the
// load's start and in a five-second stall). The bound is a minute of arrivals for a money flow, so a platform that slows is measured
// slowing rather than the generator giving up.
//
// STAGES (P16-TSK-007, PHASE_16_PLAN.md section 5.1). A profile with stages (normal, peak, sustained, stress, isolation) names each
// operation at the proposed production target's peak rate and scales it per stage: each stage is [seconds, factor], or [seconds, factor,
// operation] when only that operation runs in it (the others at zero, the isolation profile's way of reading one operation's cost). The
// executor is then ramping-arrival-rate, still an open model: each step is a one-second ramp to the stage's rate, then that rate held, so
// the arrivals are the stage's rate times its seconds to well within the judge's 2%. k6 takes whole iterations per time unit, so a staged
// profile counts its rates per 1000 s, three significant figures.

// The stage's target for this operation, in iterations per the operation's time unit.
function stageTarget(operation, stage) {
    if (stage.length > 2 && stage[2] !== operation.name) {
        return 0;
    }
    return Math.round(operation.rate * stage[1]);
}

export function openModel(operation, duration, gracefulStop, stages) {
    const common = {
        exec: operation.exec,
        preAllocatedVUs: operation.preAllocatedVUs,
        maxVUs: operation.maxVUs,
        // An iteration under way when the duration ends finishes its flow within the bound a movement is resolved in (scenarios.js), so
        // no payment is left unknown by the generator.
        gracefulStop,
        tags: { op: operation.name },
    };
    if (!stages) {
        return {
            executor: 'constant-arrival-rate',
            rate: operation.rate,
            timeUnit: operation.per,
            duration,
            ...common,
        };
    }
    const steps = [];
    stages.forEach((stage, index) => {
        const target = stageTarget(operation, stage);
        if (index === 0) {
            steps.push({ target, duration: `${stage[0]}s` });
        } else {
            steps.push({ target, duration: '1s' });
            steps.push({ target, duration: `${stage[0] - 1}s` });
        }
    });
    return {
        executor: 'ramping-arrival-rate',
        startRate: stageTarget(operation, stages[0]),
        timeUnit: operation.per,
        stages: steps,
        ...common,
    };
}
