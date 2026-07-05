// k6 focused load test around the ~100-concurrent commit target.
// Runs against WARM pods (minReplicas pinned) so the measurement reflects
// steady-state capacity, NOT HPA scale-out lag. Rungs bracket 100 VU
// (50 -> 75 -> 100 -> 125) to pinpoint where fail-rate leaves ~0%.
// Same pool-token + public-Ingress path as quote_staircase.js.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const AUTH = 'http://keycloak.dpp.svc.cluster.local:8080';
const API = 'https://api.dpp-pricing.dev';
const REALM = 'dynamic-pricing';
const POOL_SIZE = 30;
const WARMUP_VUS = 20;
const WARMUP = 45;

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
  { name: 's020', vus: 20 },
  { name: 's050', vus: 50 },
  { name: 's075', vus: 75 },
  { name: 's100', vus: 100 },
];
const HOLD = 120;
const GAP = 30;

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
    executor: 'constant-vus', vus: WARMUP_VUS, duration: WARMUP + 's',
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
  setupTimeout: '120s',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { 'http_req_failed': ['rate<0.05'] },
};

export function setup() {
  const tokens = [];
  for (let i = 0; i < POOL_SIZE; i++) {
    const res = http.post(
      `${AUTH}/realms/${REALM}/protocol/openid-connect/token`,
      { grant_type: 'password', client_id: 'mini-app', username: 'demo.customer', password: 'demo_customer_dev_only' },
      { headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Forwarded-Proto': 'https', 'X-Forwarded-Host': 'auth.dpp-pricing.dev', 'X-Forwarded-Port': '443',
        }, tags: { op: 'token_setup' } }
    );
    if (res.status !== 200) throw new Error(`setup token #${i} failed: HTTP ${res.status}`);
    tokens.push(res.json().access_token);
  }
  console.log(`setup: minted ${tokens.length} tokens`);
  return { tokens };
}

export function runWarmup(data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  http.post(`${API}/pricing/quote`, BODY, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, tags: { op: 'warmup' },
  });
}

export function runRung(data) {
  const rung = __ENV.RUNG;
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.post(`${API}/pricing/quote`, BODY, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, tags: { op: 'quote', rung },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200) { rungTrend[rung].add(res.timings.duration); rung200[rung].add(1); }
  else { rungBad[rung].add(1); }
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
    const ok = cnt('ok_' + r.name), bad = cnt('bad_' + r.name);
    return {
      rung: r.name, vus: r.vus, ok, bad,
      fail_rate: (ok + bad) ? bad / (ok + bad) : 0,
      avg_ms: pick(t, 'avg'), p50_ms: pick(t, 'med'),
      p95_ms: pick(t, 'p(95)'), p99_ms: pick(t, 'p(99)'), max_ms: pick(t, 'max'),
    };
  });
  const fmt = (v) => (v === null ? '   -  ' : v.toFixed(1).padStart(9));
  let table = '\n=== FOCUS ~100 CONCURRENT (warm pods, guard-opt) — latency of SUCCESSFUL quotes (ms) ===\n';
  table += 'rung   VUs      ok     bad  fail%       avg       p50       p95       p99       max\n';
  rows.forEach((r) => {
    table += `${r.rung}  ${String(r.vus).padStart(4)}  ${String(r.ok).padStart(6)}  ${String(r.bad).padStart(6)}  ${(r.fail_rate*100).toFixed(1).padStart(5)}  ${fmt(r.avg_ms)} ${fmt(r.p50_ms)} ${fmt(r.p95_ms)} ${fmt(r.p99_ms)} ${fmt(r.max_ms)}\n`;
  });
  const json = JSON.stringify({ rungs: rows, generated_at: new Date().toISOString() }, null, 2);
  return { stdout: table + '\n===JSON_START===\n' + json + '\n===JSON_END===\n', '/tmp/summary.json': json };
}
