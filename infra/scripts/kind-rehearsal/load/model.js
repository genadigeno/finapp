// The open model (P16-TSK-006, ADR-0126 decision 2). Every operation of a load profile becomes one scenario of this shape: iterations
// start at the profile's arrival rate whatever the platform answers, so a slow answer queues work as real clients would and its waiting
// is counted, instead of slowing the generator down until the latency it reports looks fine (coordinated omission). The pool of
// virtual users is only where in-flight iterations wait: preAllocatedVUs at start, maxVUs at most. An iteration that finds no free one
// is dropped, and kind-rehearsal/load.sh records a run with any dropped iteration as invalid.
//
// This is the one place the executor is chosen. The harness reads what k6 will actually run (`k6 inspect`) before any load and refuses a
// scenario whose executor is not an arrival-rate one; the probe that replaces this file with a closed model is refused there.

export function openModel(operation, duration) {
    return {
        executor: 'constant-arrival-rate',
        exec: operation.exec,
        rate: operation.rate,
        timeUnit: operation.per,
        duration,
        preAllocatedVUs: operation.preAllocatedVUs,
        maxVUs: operation.maxVUs,
        // An iteration under way when the duration ends finishes its flow, so no payment is left half-confirmed by the generator.
        gracefulStop: '120s',
        tags: { op: operation.name },
    };
}
