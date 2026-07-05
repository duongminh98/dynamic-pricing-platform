// k6 per-pod concurrency curve — direct-hit, NO gateway, NO auth.
// Targets a single pricing pod IP directly (http://POD_IP:8000/pricing/quote).
// Hitting the pod directly bypasses Kong => no JWT => optional_subject() is None
// => customer_id="internal". The router's internal path requires a `profile` in
// the body (which BODY supplies), so the quote runs the full engine WITHOUT a
// token. This isolates ONE pod's service time as a function of concurrency,
// with zero LB / TLS / Keycloak / Kong in the path.
//
// Rungs step concurrency C = 1,2,4,8,16 (constant-vus), each held HOLD seconds
// with a GAP between, so each C yields its own clean latency cluster. Compare
// this curve against the 2.58-core baseline: if the 3-core pod flattens the
// C>=4 climb, cpu request IS the lever.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const POD_IP = __ENV.POD_IP;                 // e.g. 10.105.0.67
const URL = `http://${POD_IP}:8000/pricing/quote`;

// Same riskier-than-baseline health profile the staircase used: trips 5
// monotonicity-guard fields, exercising the exact guarded path.
const BODY = JSON.stringify({
  product_id: 'HEALTH_BASIC',
  profile: {
    age: 30, smoker: true, chronic_disease: true, diabetes: true,
    blood_pressure_problem: true, hospitalized_last_12m: true,
    province: 'Ha Noi', gender: 'male', weight_kg: 65, height_cm: 170,
    // Internal path skips quote-ready enrichment, so demographics must be supplied inline.
    region: 'north', urban_tier: 'tier1', occupation: 'office',
    income_level: 'medium', marital_status: 'single',
  },
});

const RUNGS = [
  { name: 'c01', vus: 1 },
  { name: 'c02', vus: 2 },
  { name: 'c04', vus: 4 },
  { name: 'c08', vus: 8 },
  { name: 'c16', vus: 16 },
];
const HOLD = 40; // seconds each concurrency level serves load
const GAP = 10;  // seconds quiet between levels
const WARMUP = 30; // prime the 4 workers before measuring

const rungTrend = {};
const rung200 = {};
const rungBad = {};
RUNGS.forEach((r) => {
  rungTrend[r.name] = new Trend('quote_ms_' + r.name, true);
  rung200[r.name] = new Counter('ok_' + r.name);
  rungBad[r.name] = new Counter('bad_' + r.name);
});

const OFFSET = WARMUP + GAP;
const scenarios = {
  warmup: {
    executor: 'constant-vus', vus: 4, duration: WARMUP + 's',
    startTime: '0s', exec: 'runWarmup', tags: { rung: 'warmup' },
  },
};
RUNGS.forEach((r, i) => {
  scenarios[r.name] = {
    executor: 'constant-vus', vus: r.vus, duration: HOLD + 's',
    startTime: OFFSET + i * (HOLD + GAP) + 's',
    exec: 'runRung', env: { RUNG: r.name }, tags: { rung: r.name },
  };
});

export const options = {
  scenarios,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function runWarmup() {
  http.post(URL, BODY, { headers: { 'Content-Type': 'application/json' }, tags: { op: 'warmup' } });
}

export function runRung() {
  const rung = __ENV.RUNG;
  const res = http.post(URL, BODY, { headers: { 'Content-Type': 'application/json' }, tags: { op: 'quote', rung } });
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200) {
    rungTrend[rung].add(res.timings.duration);
    rung200[rung].add(1);
  } else {
    rungBad[rung].add(1);
  }
}

export function handleSummary(data) {
  const pick = (name, stat) => {
    const m = data.metrics[name];
    return m && m.values && m.values[stat] !== undefined ? m.values[stat] : null;
  };
  const cnt = (name) => {
    const m = data.metrics[name];
    return m && m.values && m.values.count !== undefined ? m.values.count : 0;
  };
  const rows = RUNGS.map((r) => {
    const t = 'quote_ms_' + r.name;
    return {
      C: r.vus, ok: cnt('ok_' + r.name), bad: cnt('bad_' + r.name),
      avg_ms: pick(t, 'avg'), p50_ms: pick(t, 'med'),
      p95_ms: pick(t, 'p(95)'), p99_ms: pick(t, 'p(99)'), max_ms: pick(t, 'max'),
    };
  });
  const fmt = (v) => (v === null ? '   -  ' : v.toFixed(1).padStart(9));
  let table = `\n=== PER-POD CONCURRENCY CURVE (direct-hit ${POD_IP}, no gateway/auth) — latency of SUCCESSFUL quotes (ms) ===\n`;
  table += '   C      ok     bad       avg       p50       p95       p99       max\n';
  rows.forEach((r) => {
    table += `${String(r.C).padStart(4)}  ${String(r.ok).padStart(6)}  ${String(r.bad).padStart(6)}  ${fmt(r.avg_ms)} ${fmt(r.p50_ms)} ${fmt(r.p95_ms)} ${fmt(r.p99_ms)} ${fmt(r.max_ms)}\n`;
  });
  const json = JSON.stringify({ pod: POD_IP, rungs: rows, generated_at: new Date().toISOString() }, null, 2);
  return {
    stdout: table + '\n===JSON_START===\n' + json + '\n===JSON_END===\n',
    '/tmp/summary.json': json,
  };
}
