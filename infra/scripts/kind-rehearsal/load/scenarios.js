// The load harness's scenarios (P16-TSK-006, ADR-0126, owner decision C5): the operations of a load profile (LOAD_PROFILE, for example
// demonstration.json) driven through the platform's real public edge as an open model (model.js), from a container on the kind network.
//
// WHAT IT HOLDS. Only the run's generated credentials, from the fixture Rehearsal.java load-fixture wrote into the run's temporary
// directory (LOAD_FIXTURE): the run's password, its customers' sessions and the merchant's key. TLS is verified against the run's own
// edge CA (SSL_CERT_FILE); nothing is skipped.
//
// WHAT IT DECIDES. Nothing about money. It sends, counts what came back and writes a report; kind-rehearsal/load.sh judges the run and
// the census judges the money. Every money-moving iteration is resolved by its key: an answer that is not definitive (no answer, a 5xx,
// a 409 while another request holds the key) is asked again with the same key and body until it is, never with a new key, and a payment
// still in progress is read until it concludes, both within LOAD_RESOLVE_SECONDS (kind-rehearsal/load.sh reads it from the code: twice the
// payment sweep's dispatched age and five minutes, the time the platform takes to conclude a payment whose provider answer was lost), so
// the census can count exactly one effect per key the generator was told succeeded.
//
// THE PROFILES (P16-TSK-007). The demonstration runs every operation at a constant rate; normal, peak, sustained, stress and isolation
// run them in stages (model.js), each a factor of the target's peak. BNPL, lending repayments and loan credit decisions are not driven on
// kind, each profile saying why: every one rests on a credit approval, which no kind deployment can give.
//
// SAME-KEY RETRIES (PHASE_16_PLAN.md section 5.1). One money-moving iteration in twenty (retryShare 0.05) sends each of its commands
// twice under the same key: half of them the second copy beside the first, before either is answered, half of them after the first
// answer. Every answer of a key must name the same transfer, payment or checkout; the report counts the keys retried and the keys
// whose answers agreed.
//
// SHEDDING (P16-TSK-016, ADR-0137; OPERATIONS_RUNBOOK.md section 11.52). A platform that cannot finish a request answers 503
// api.Overloaded before its handler, with a Retry-After: nothing was done, and the same request under the same key is a first attempt.
// Every such answer is counted per operation (shed_<op>), and an iteration retrying after one waits the Retry-After it was given, as a
// client honouring the contract does, rather than its usual pause. A profile with "shedOnceShare" sends that share of its keyed
// commands ONCE: an iteration chosen so (never one of the retried ones) that is shed does not retry, logs "SHED-ONCE <op> <key>
// <reference>" and concludes SHED - known to have asked for nothing, which kind-rehearsal/load.sh proves against the database: no
// idempotency claim under any of those keys, and no transfer under any of their references. An admitted one is resolved as usual.

import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { openModel } from './model.js';

const profile = JSON.parse(open(__ENV.LOAD_PROFILE));
const fixture = JSON.parse(open(__ENV.LOAD_FIXTURE));
const RUN = __ENV.LOAD_RUN || 'run';
const DURATION = __ENV.LOAD_DURATION || profile.duration;
const BASE = fixture.publicBase;
const RETRY_EVERY = Math.round(1 / profile.retryShare);
// The shed-once probes (P16-TSK-016): none unless the profile asks for them.
const ONCE_EVERY = profile.shedOnceShare ? Math.round(1 / profile.shedOnceShare) : 0;
const RESOLVE_SECONDS = Number(__ENV.LOAD_RESOLVE_SECONDS);
if (!(RESOLVE_SECONDS > 0)) {
    throw new Error('LOAD_RESOLVE_SECONDS is required: the bound within which every movement is resolved by its key');
}
const RESOLVE_MS = RESOLVE_SECONDS * 1000;

const driven = profile.operations.filter((operation) => operation.generator === 'k6');

// An iteration under way when the load's duration ends is let finish within the bound, so no movement is left unknown by the generator.
const GRACEFUL_STOP = `${RESOLVE_SECONDS + 120}s`;

// A staged profile (P16-TSK-007, model.js): its stages as written, or, when LOAD_DURATION shortens the run, every stage shortened in
// proportion (at least two seconds each), which kind-rehearsal/load.sh computes the same way and records as not the profile's workload.
function seconds(text) {
    const value = Number(String(text).slice(0, -1));
    return String(text).endsWith('h') ? value * 3600 : String(text).endsWith('m') ? value * 60 : value;
}
const STAGES = profile.stages ? (() => {
    const total = profile.stages.reduce((sum, stage) => sum + stage[0], 0);
    const scale = __ENV.LOAD_DURATION ? seconds(__ENV.LOAD_DURATION) / total : 1;
    return profile.stages.map((stage) => [Math.max(2, Math.round(stage[0] * scale)), ...stage.slice(1)]);
})() : null;

export const options = {
    scenarios: Object.fromEntries(driven.map((operation) => [operation.name, openModel(operation, DURATION, GRACEFUL_STOP, STAGES)])),
    summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg', 'count'],
    insecureSkipTLSVerify: false,
    userAgent: `finapp-load/${profile.name}`,
};

// One set of series per operation, so the summary holds each operation's own percentiles and counts.
const metrics = {};
for (const operation of driven) {
    const name = operation.name;
    metrics[name] = {
        latency: new Trend(`latency_${name}`, true),
        flow: new Trend(`flow_${name}`, true),
        iterations: new Counter(`iterations_${name}`),
        attempts: new Counter(`attempts_${name}`),
        ok: new Counter(`ok_${name}`),
        refused: new Counter(`refused_${name}`),
        failed: new Counter(`failed_attempts_${name}`),
        conflicts: new Counter(`conflicts_${name}`),
        unresolved: new Counter(`unresolved_${name}`),
        abandoned: new Counter(`abandoned_${name}`),
        retried: new Counter(`retried_${name}`),
        agreed: new Counter(`retry_agreed_${name}`),
        shed: new Counter(`shed_${name}`),
        shedOnce: new Counter(`shed_once_${name}`),
    };
}

function headers(bearer, key, body) {
    const out = {};
    if (body !== null) {
        out['Content-Type'] = 'application/json';
    }
    if (bearer) {
        out.Authorization = `Bearer ${bearer}`;
    }
    if (key) {
        out['Idempotency-Key'] = key;
    }
    return out;
}

function request(op, method, path, body, bearer, key, name) {
    return [method, BASE + path, body === null ? null : JSON.stringify(body),
        { headers: headers(bearer, key, body), tags: { op, name }, timeout: '30s' }];
}

// The latency the client saw: waiting for a connection, connecting and the handshake included, not only the server's time.
function seen(response) {
    const t = response.timings;
    return t.blocked + t.connecting + t.tls_handshaking + t.duration;
}

// A shed answer (P16-TSK-016): the platform refused before the handler, so nothing was done under the key.
function shedAnswer(response) {
    return response.status === 503 && json(response, 'code') === 'api.Overloaded';
}

function record(op, response, primary) {
    const m = metrics[op];
    m.attempts.add(1);
    if (shedAnswer(response)) {
        m.shed.add(1);
    }
    if (primary) {
        m.latency.add(seen(response));
    }
    if (response.status === 409) {
        m.conflicts.add(1);
    } else if (response.status === 0 || response.status >= 400) {
        m.failed.add(1);
    }
}

function send(op, req, primary) {
    const response = http.request(...req);
    record(op, response, primary);
    return response;
}

function sendBoth(op, req, primary) {
    const responses = http.batch([req, req]);
    responses.forEach((response) => record(op, response, primary));
    return responses;
}

function json(response, field) {
    try {
        return response.json(field);
    } catch (unreadable) {
        return undefined;
    }
}

// Not definitive: the platform may or may not have acted, so the same request goes again under the same key.
function retryable(response) {
    return response.status === 0 || response.status === 408 || response.status === 409 || response.status === 429 || response.status >= 500;
}

// RESOLVED: the accepted answer. REFUSED: a definitive refusal (a 4xx other than those above), which moved nothing. UNRESOLVED: no
// definitive answer within the bound, which the census must then be able to explain and the harness fails the run for.
const REFUSED = 'refused';
const UNRESOLVED = 'unresolved';
// ABANDONED (P16-TSK-007): a checkout whose every answer under its key was a replay. The session token is in the creating answer and in
// no other (CheckoutService.create), so a client that lost that answer can never pay the session: it stays OPEN and expires, nothing moved,
// and the census counts no payment for it. Found on a compressed stress run: the session committed, then the second transaction that reads
// it back waited past the pool's 5 s bound and the creating answer was a 500. Recorded per operation and stage, never a pass of anything:
// the outcome is known (no payment), unlike UNRESOLVED's.
const ABANDONED = 'abandoned';
// SHED (P16-TSK-016): a shed-once probe the platform shed, so it asked for nothing; load.sh proves no claim and no row exist for its key.
const SHED = 'shed';

// Between two asks: the Retry-After a shed answer gave (P16-TSK-016), or a second through the first minute, then five, as a client backs
// off while the platform concludes.
function pause(since, response) {
    const after = response && shedAnswer(response) ? Number(response.headers['Retry-After']) : NaN;
    if (after >= 1) {
        sleep(after);
        return;
    }
    sleep(Date.now() - since < 60000 ? 1 : 5);
}

function resolve(op, req, accept, first, primary) {
    const since = Date.now();
    const until = since + RESOLVE_MS;
    let response = first || send(op, req, primary);
    for (;;) {
        if (accept(response)) {
            return response;
        }
        if (!retryable(response)) {
            return REFUSED;
        }
        if (Date.now() >= until) {
            return UNRESOLVED;
        }
        pause(since, response);
        response = send(op, req, primary);
    }
}

// The command once, or twice under one key when this iteration is one of the retried ones; every resolved answer is returned. The
// keyed command of the iteration (keyed true) is what the retried and the agreed counts are of: one key each. A confirmation sent twice
// rides on the same iteration, under its intent's or its session's machine, and is not a second key.
function command(op, it, req, accept, primary, keyed) {
    const m = metrics[op];
    // A shed-once probe (P16-TSK-016): sent once; shed, it is never retried and its key goes to the shed census; admitted, it resolves.
    if (keyed && ONCE_EVERY > 0 && it % ONCE_EVERY === ONCE_EVERY - 2 && it % RETRY_EVERY !== RETRY_EVERY - 1) {
        const once = send(op, req, primary);
        if (shedAnswer(once)) {
            m.shedOnce.add(1);
            const body = req[2] === null ? {} : JSON.parse(req[2]);
            console.log(`SHED-ONCE ${op} ${req[3].headers['Idempotency-Key']} ${body.reference || '-'}`);
            return [SHED];
        }
        return [resolve(op, req, accept, once, primary)];
    }
    if (it % RETRY_EVERY !== RETRY_EVERY - 1) {
        return [resolve(op, req, accept, null, primary)];
    }
    if (keyed) {
        m.retried.add(1);
    }
    if (Math.floor(it / RETRY_EVERY) % 2 === 0) {
        const [a, b] = sendBoth(op, req, primary);
        return [resolve(op, req, accept, a, primary), resolve(op, req, accept, b, primary)];
    }
    const first = resolve(op, req, accept, null, primary);
    return [first, resolve(op, req, accept, null, primary)];
}

function failedOutcome(answers) {
    if (answers.includes(SHED)) {
        return SHED;
    }
    if (answers.includes(UNRESOLVED)) {
        return UNRESOLVED;
    }
    return answers.includes(REFUSED) ? REFUSED : null;
}

function conclude(op, outcome, started) {
    const m = metrics[op];
    m.flow.add(Date.now() - started);
    if (outcome === SHED) {
        // Counted by command() as shed_once; neither a movement nor a refusal.
    } else if (outcome === UNRESOLVED) {
        m.unresolved.add(1);
    } else if (outcome === ABANDONED) {
        m.abandoned.add(1);
    } else if (outcome === REFUSED) {
        m.refused.add(1);
    } else {
        m.ok.add(1);
    }
}

function agreed(op, answers, field) {
    if (answers.length === 2) {
        const [a, b] = answers.map((answer) => json(answer, field));
        if (a !== undefined && a !== null && a === b) {
            metrics[op].agreed.add(1);
        }
    }
}

function customer(it, stride) {
    const all = fixture.customers;
    return all[(it * stride) % all.length];
}

// ------------------------------------------------------------------------------------------------ the money movements

export function transfer() {
    const op = 'transfer';
    const it = exec.scenario.iterationInTest;
    const started = Date.now();
    metrics[op].iterations.add(1);
    // The concurrent-financial profile (P16-TSK-008, transferPair): every transfer between the same two customers, one way and back by
    // turns, so the pair's two accounts are the hot rows and two opposite transfers race for both. Every other profile spreads them.
    const all = profile.transferPair ? fixture.customers.slice(0, 2) : fixture.customers;
    const from = all[it % all.length];
    const to = all[(it + 1) % all.length];
    const body = {
        sourceAccountId: from.account, destinationAccountId: to.account, amount: profile.transferAmount, currency: 'EUR',
        reference: `wl-load-${RUN}-${it}`,
    };
    const req = request(op, 'POST', '/v1/transfers', body, from.token, `load-${RUN}-transfer-${it}`, 'transfer');
    const answers = command(op, it, req, (r) => (r.status === 200 || r.status === 201) && json(r, 'status') === 'COMPLETED', true, true);
    agreed(op, answers, 'id');
    conclude(op, failedOutcome(answers), started);
}

// A payment read until it concludes, within the bound: one whose provider answer was lost concludes only when the platform's sweep has
// asked the provider.
function pollPayment(op, id, bearer) {
    const since = Date.now();
    let status;
    for (;;) {
        const response = send(op, request(op, 'GET', `/v1/payments/${id}`, null, bearer, null, 'payment-read'), false);
        status = response.status === 200 ? json(response, 'status') : undefined;
        if (status === 'SUCCEEDED' || status === 'FAILED' || Date.now() - since >= RESOLVE_MS) {
            return status;
        }
        pause(since, response);
    }
}

export function topup() {
    const op = 'topup';
    const it = exec.scenario.iterationInTest;
    const started = Date.now();
    metrics[op].iterations.add(1);
    const who = customer(it, 7);
    const create = request(op, 'POST', '/v1/payments',
        { amount: profile.topUpAmount, currency: 'EUR', paymentMethodId: who.card }, who.token, `load-${RUN}-topup-${it}`, 'payment-create');
    const created = command(op, it, create, (r) => (r.status === 200 || r.status === 201) && json(r, 'id'), false, true);
    const failed = failedOutcome(created);
    if (failed) {
        conclude(op, failed, started);
        return;
    }
    const id = json(created[0], 'id');
    if (created.length === 2 && json(created[1], 'id') !== id) {
        conclude(op, UNRESOLVED, started);
        return;
    }
    // The confirmation moves the money: it is the operation's latency, and the retried iterations send it twice too. A 409 here is the
    // intent already past confirmation, which the read below settles.
    const confirm = request(op, 'POST', `/v1/payments/${id}/confirmation`, null, who.token, null, 'payment-confirm');
    const confirmed = command(op, it, confirm, (r) => r.status === 200 || r.status === 409, true, false);
    const unconfirmed = failedOutcome(confirmed);
    if (unconfirmed) {
        conclude(op, unconfirmed, started);
        return;
    }
    const status = pollPayment(op, id, who.token);
    if (created.length === 2) {
        agreed(op, created, 'id');
    }
    conclude(op, status === 'SUCCEEDED' ? null : status === 'FAILED' ? REFUSED : UNRESOLVED, started);
}

function pollCheckout(op, checkoutId) {
    const since = Date.now();
    let status;
    for (;;) {
        const response = send(op, request(op, 'GET', `/v1/checkout/sessions/${checkoutId}`, null, fixture.merchantKey, null, 'checkout-read'), false);
        status = response.status === 200 ? json(response, 'status') : undefined;
        if (status === 'COMPLETED' || status === 'FAILED' || status === 'EXPIRED' || status === 'ABANDONED' || Date.now() - since >= RESOLVE_MS) {
            return status;
        }
        pause(since, response);
    }
}

export function checkout() {
    const op = 'checkout';
    const it = exec.scenario.iterationInTest;
    const started = Date.now();
    metrics[op].iterations.add(1);
    const who = customer(it, 3);
    const create = request(op, 'POST', '/v1/checkout/sessions',
        { amountMinor: profile.checkoutMinor, currency: 'EUR', lineSummary: 'The load harness order' }, fixture.merchantKey,
        `load-${RUN}-checkout-${it}`, 'checkout-create');
    const created = command(op, it, create, (r) => (r.status === 200 || r.status === 201) && json(r, 'checkoutId'), false, true);
    const failed = failedOutcome(created);
    if (failed) {
        conclude(op, failed, started);
        return;
    }
    // The token appears once, in the creating answer; a replay names the same session and carries none.
    const checkoutId = json(created[0], 'checkoutId');
    const token = created.map((answer) => json(answer, 'sessionToken')).find((value) => value);
    // Two sessions under one key would be the key's second effect: never explained away.
    if (created.length === 2 && json(created[1], 'checkoutId') !== checkoutId) {
        conclude(op, UNRESOLVED, started);
        return;
    }
    if (!token) {
        if (created.length === 2) {
            agreed(op, created, 'checkoutId');
        }
        conclude(op, ABANDONED, started);
        return;
    }
    const confirm = request(op, 'POST', '/v1/checkout/sessions/confirmation', { sessionToken: token, paymentMethodId: who.card },
        who.token, null, 'checkout-confirm');
    const confirmed = command(op, it, confirm, (r) => r.status === 200 || r.status === 409, true, false);
    const unconfirmed = failedOutcome(confirmed);
    if (unconfirmed) {
        conclude(op, unconfirmed, started);
        return;
    }
    let status = confirmed.map((answer) => json(answer, 'status')).find((value) => value === 'COMPLETED');
    if (!status) {
        status = pollCheckout(op, checkoutId);
    }
    if (created.length === 2) {
        agreed(op, created, 'checkoutId');
    }
    conclude(op, status === 'COMPLETED' ? null : status === 'FAILED' ? REFUSED : UNRESOLVED, started);
}

// ------------------------------------------------------------------------------------------------ the rest of the mix

function single(op, req, okStatus) {
    const it = exec.scenario.iterationInTest;
    const started = Date.now();
    metrics[op].iterations.add(1);
    const response = send(op, req, true);
    conclude(op, response.status === okStatus ? null : retryable(response) ? UNRESOLVED : REFUSED, started);
    return it;
}

export function read() {
    const who = customer(exec.scenario.iterationInTest, 1);
    single('read', request('read', 'GET', `/v1/me/accounts/${who.account}/balance`, null, who.token, null, 'balance-read'), 200);
}

export function signin() {
    const who = customer(exec.scenario.iterationInTest, 5);
    single('signin', request('signin', 'POST', '/v1/authentications', { loginIdentifier: who.login, password: fixture.password }, null,
        null, 'sign-in'), 201);
}

export function merchant() {
    // The payable read folds the merchant payable account's whole history on every call (ADR-0133), so its cost grows with a run's age:
    // a profile that holds for hours (the soak, P16-TSK-008) sets merchantPayable false and reads the merchant's own record alone.
    const payable = profile.merchantPayable !== false && exec.scenario.iterationInTest % 2 === 1;
    const path = payable ? '/v1/merchant/payable' : '/v1/merchant/me';
    single('merchant', request('merchant', 'GET', path, null, fixture.merchantKey, null, path.substring(4)), 200);
}

// ------------------------------------------------------------------------------------------------ the report

// One row per operation, for the harness to judge: arrivals, outcomes and the client-side latency percentiles in milliseconds. The
// full summary and the time series beside it are the evidence.
export function handleSummary(data) {
    const value = (metric, stat) => {
        const found = data.metrics[metric];
        return found && found.values[stat] !== undefined ? found.values[stat] : 0;
    };
    const columns = ['op', 'per_second', 'iterations', 'ok', 'refused', 'unresolved', 'attempts', 'failed_attempts', 'conflicts',
        'retried', 'retry_agreed', 'p50_ms', 'p95_ms', 'p99_ms', 'max_ms', 'flow_p99_ms'];
    const rows = driven.map((operation) => {
        const n = operation.name;
        return [n, operation.perSecond, value(`iterations_${n}`, 'count'), value(`ok_${n}`, 'count'), value(`refused_${n}`, 'count'),
            value(`unresolved_${n}`, 'count'), value(`attempts_${n}`, 'count'), value(`failed_attempts_${n}`, 'count'),
            value(`conflicts_${n}`, 'count'), value(`retried_${n}`, 'count'), value(`retry_agreed_${n}`, 'count'),
            value(`latency_${n}`, 'med').toFixed(1), value(`latency_${n}`, 'p(95)').toFixed(1), value(`latency_${n}`, 'p(99)').toFixed(1),
            value(`latency_${n}`, 'max').toFixed(1), value(`flow_${n}`, 'p(99)').toFixed(1)].join('\t');
    });
    const generator = ['dropped_iterations', 'peak_vus', 'max_vus', 'http_reqs', 'duration_ms'].join('\t') + '\n'
        + [value('dropped_iterations', 'count'), value('vus', 'max'), value('vus_max', 'max'), value('http_reqs', 'count'),
            data.state.testRunDurationMs.toFixed(0)].join('\t') + '\n';
    return {
        '/out/summary.json': JSON.stringify(data, null, 1),
        '/out/report.tsv': `${columns.join('\t')}\n${rows.join('\n')}\n`,
        '/out/generator.tsv': generator,
    };
}
