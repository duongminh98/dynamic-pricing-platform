// k6 load test — quote flow staircase (concurrent-users model), POOL-TOKEN variant.
// Five constant-VU scenarios run back-to-back (10 -> 50 -> 100 -> 200 -> 400),
// each holding 2 minutes with a 30s gap between, so every rung yields its own
// clean p50/p95/p99 cluster. Runs INSIDE the cluster (Job), hitting Kong the
// same way a browser client does: POST /pricing/quote with a Bearer token.
//
// WHY POOL-TOKEN: the previous variant had every VU fetch its own token from
// Keycloak (password-grant, bcrypt) during load. At 50-400 concurrent VUs that
// SATURATED Keycloak, tokens failed, and quotes went out as "Bearer null" ->
// 99.8% fast-fail. That measured Keycloak login throughput, not the quote flow.
//
// Fix: setup() pre-mints a small pool of tokens ONCE (sequentially, so Keycloak
// is never hammered). VUs reuse a token by index and NEVER log in during load,
// so the measurement isolates the quote path. The realm's accessTokenLifespan is
// already 1800s (30m) > the ~12.5m run, so the pool stays valid start to finish
// with no realm change needed.
//
// PUBLIC PATH: quotes go to https://api.dpp-pricing.dev so the measured latency
// includes the GKE Ingress LB + TLS the way a real internet client sees it (k6
// runs in-cluster, so this traffic hairpins out to the LB and back). Tokens are
// still minted from the internal keycloak with X-Forwarded-* — a proven path that
// stamps the public https issuer Kong expects — since minting happens in setup(),
// off the measured path.
//
// WARM-UP: 3 replicas x 4 uvicorn workers = 12 cold workers, each of which lazily
// loads the model + builds the SHAP explainer cache on its first quote (~1.8s vs
// ~0.3s warm). A warm-up scenario runs first to prime all workers so the s010 rung
// measures steady-state latency, not one-time worker warm-up.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const AUTH = 'http://keycloak.dpp.svc.cluster.local:8080';
const API = 'https://api.dpp-pricing.dev'; // public Ingress + TLS (hairpin from in-cluster)
const REALM = 'dynamic-pricing';
const POOL_SIZE = 30; // tokens minted once in setup(), rotated across all VUs
const WARMUP_VUS = 20; // primes all 12 cold workers before the staircase
const WARMUP = 45;     // seconds of warm-up load

// A riskier-than-baseline health profile: trips 5 monotonicity-guard fields, so
// this is the exact path the guard fix optimizes (81 -> 33 build_features).
const BODY = JSON.stringify({
  product_id: 'HEALTH_BASIC',
  profile: {
    age: 30, smoker: true, chronic_disease: true, diabetes: true,
    blood_pressure_problem: true, hospitalized_last_12m: true,
    province: 'Ha Noi', gender: 'male', weight_kg: 65, height_cm: 170,
  },
});

const RUNGS = [
  { name: 's010', vus: 10 },
  { name: 's050', vus: 50 },
  { name: 's100', vus: 100 },
  { name: 's200', vus: 200 },
  { name: 's400', vus: 400 },
];
const HOLD = 120; // seconds each rung serves load
const GAP = 30;   // seconds of quiet between rungs (separates the clusters)

// Per-rung latency (only 200s are recorded, so percentiles reflect real quote
// timing, not fast-fails) + per-rung success/failure counts.
const rungTrend = {};
const rung200 = {};
const rungBad = {};
RUNGS.forEach((r) => {
  rungTrend[r.name] = new Trend('quote_ms_' + r.name, true);
  rung200[r.name] = new Counter('ok_' + r.name);
  rungBad[r.name] = new Counter('bad_' + r.name);
});

// Warm-up runs first (startTime 0); the staircase rungs start after it finishes
// plus a gap, so all 12 workers are primed before s010 measures anything.
const OFFSET = WARMUP + GAP;
const scenarios = {
  warmup: {
    executor: 'constant-vus',
    vus: WARMUP_VUS,
    duration: WARMUP + 's',
    startTime: '0s',
    exec: 'runWarmup',
    tags: { rung: 'warmup' },
  },
};
RUNGS.forEach((r, i) => {
  scenarios[r.name] = {
    executor: 'constant-vus',
    vus: r.vus,
    duration: HOLD + 's',
    startTime: OFFSET + i * (HOLD + GAP) + 's',
    exec: 'runRung',
    env: { RUNG: r.name },
    tags: { rung: r.name },
  };
});

export const options = {
  scenarios,
  setupTimeout: '120s',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Advisory only (never abortOnFail) — annotate pass/fail per rung.
    'http_req_failed': ['rate<0.05'],
  },
};

// setup(): mint the token pool ONCE, sequentially. demo.customer's token is not
// bound to a VU, so any VU can use any token; a pool just avoids a single point.
// X-Forwarded-* forces Keycloak to mint the public https issuer Kong's JWT key
// expects (internal keycloak:8080 would otherwise stamp http://...:8080).
export function setup() {
  const tokens = [];
  for (let i = 0; i < POOL_SIZE; i++) {
    const res = http.post(
      `${AUTH}/realms/${REALM}/protocol/openid-connect/token`,
      { grant_type: 'password', client_id: 'mini-app', username: 'demo.customer', password: 'demo_customer_dev_only' },
      {
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Forwarded-Proto': 'https',
          'X-Forwarded-Host': 'auth.dpp-pricing.dev',
          'X-Forwarded-Port': '443',
        },
        tags: { op: 'token_setup' },
      }
    );
    if (res.status !== 200) {
      throw new Error(`setup token mint #${i} failed: HTTP ${res.status} ${res.body}`);
    }
    tokens.push(res.json().access_token);
  }
  console.log(`setup: minted ${tokens.length} tokens`);
  return { tokens };
}

// Warm-up: same quote call as the staircase but its latency is NOT recorded in
// any rung Trend. Its only job is to make every one of the 12 uvicorn workers
// serve at least one quote so the model + SHAP explainer cache are built before
// s010 starts measuring. Uses the token pool exactly like runRung.
export function runWarmup(data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  http.post(`${API}/pricing/quote`, BODY, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { op: 'warmup' },
  });
}

export function runRung(data) {
  const rung = __ENV.RUNG;
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.post(`${API}/pricing/quote`, BODY, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { op: 'quote', rung },
  });
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has final_premium': (r) => typeof r.body === 'string' && r.body.indexOf('final_premium_vnd') !== -1,
  });
  // Record latency ONLY for real successes so percentiles measure the quote
  // path, not the speed of a rejection.
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
      rung: r.name,
      vus: r.vus,
      ok: cnt('ok_' + r.name),
      bad: cnt('bad_' + r.name),
      avg_ms: pick(t, 'avg'),
      p50_ms: pick(t, 'med'),
      p95_ms: pick(t, 'p(95)'),
      p99_ms: pick(t, 'p(99)'),
      max_ms: pick(t, 'max'),
    };
  });
  const overall = {
    http_reqs: cnt('http_reqs'),
    http_req_failed_rate: pick('http_req_failed', 'rate'),
    iterations: cnt('iterations'),
  };

  const fmt = (v) => (v === null ? '   -  ' : v.toFixed(1).padStart(9));
  let table = '\n=== QUOTE STAIRCASE (pool-token, public Ingress+TLS) — per-rung latency of SUCCESSFUL quotes (ms) ===\n';
  table += 'rung   VUs      ok     bad       avg       p50       p95       p99       max\n';
  rows.forEach((r) => {
    table += `${r.rung}  ${String(r.vus).padStart(4)}  ${String(r.ok).padStart(6)}  ${String(r.bad).padStart(6)}  ${fmt(r.avg_ms)} ${fmt(r.p50_ms)} ${fmt(r.p95_ms)} ${fmt(r.p99_ms)} ${fmt(r.max_ms)}\n`;
  });
  table += `\ntotal http_reqs=${overall.http_reqs}  http_req_failed_rate=${overall.http_req_failed_rate}  iterations=${overall.iterations}\n`;

  const json = JSON.stringify({ rungs: rows, overall, generated_at: new Date().toISOString() }, null, 2);
  const wrapped = '\n===JSON_START===\n' + json + '\n===JSON_END===\n';

  return {
    stdout: table + wrapped,
    '/tmp/summary.json': json,
  };
}
