// Phase 4 checkpoint: proves 429s kick in at the configured limit, and measures the cached read.
// Hammers GET /api/v1/accounts/{id} (a cheap, cached read) with ONE api key from 10 VUs for 10s:
//   ACCOUNT=<account id> k6 run load/ratelimit_smoke.js
//
// Policy is 20 req/s burst and 100 req/min sustained per key. The sustained bucket is the binding
// limit: it starts full (100 tokens) and refills at 100/60 per second, so over 10s at most
// 100 + 10 * 100/60 = ~116 requests may pass; everything else must be 429 with a Retry-After header. A second key in parallel shows limits are per key, not global.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const allowed = new Counter('allowed');
const limited = new Counter('limited');
const cachedRead = new Trend('cached_read_ms', true);

export const options = {
    scenarios: {
        hot_key: { executor: 'constant-vus', vus: 10, duration: '10s', env: { KEY: 'smoke-hot' } },
        other_key: { executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: '10s',
                     preAllocatedVUs: 2, env: { KEY: 'smoke-other' } },
    },
    thresholds: {
        // Capacity + refill over the run (~116), plus a little slack for scenario start-up timing.
        'allowed{scenario:hot_key}': ['count<=120'],
        'limited{scenario:hot_key}': ['count>0'],
        // A well-behaved key at 5 req/s must never be throttled by the hot key's traffic.
        'limited{scenario:other_key}': ['count==0'],
    },
};

export default function () {
    const res = http.get(`${BASE}/api/v1/accounts/${__ENV.ACCOUNT}`, { headers: { 'X-Api-Key': __ENV.KEY } });
    if (res.status === 429) {
        limited.add(1);
        check(res, { '429 carries Retry-After': (r) => r.headers['Retry-After'] !== undefined });
    } else {
        allowed.add(1);
        cachedRead.add(res.timings.duration);
        check(res, { 'status 200': (r) => r.status === 200 });
    }
}
