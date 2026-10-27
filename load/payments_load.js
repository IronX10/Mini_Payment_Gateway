// Phase 6 load test. Run against a locally running app (docker compose up + ./mvnw spring-boot:run):
//
//   SRC=<funded account id> DEST=<account id> KEY=<any api key> k6 run --vus 50 --duration 60s load/payments_load.js
//
// Every request carries a fresh Idempotency-Key, so each one is a real (non-replayed) payment.
// With the rate limiter on, one API key is capped at 20 req/s, so 429s are an expected outcome for
// stage (c) and are counted separately rather than as errors. Record results in LOAD_TEST.md.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const rateLimited = new Counter('rate_limited');

export const options = {
    thresholds: {
        http_req_duration: ['p(95)<500'],
    },
};

export default function () {
    const res = http.post(`${BASE}/api/v1/payments`,
        JSON.stringify({ source: Number(__ENV.SRC), dest: Number(__ENV.DEST), amount: 1, currency: 'INR' }),
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Api-Key': __ENV.KEY || 'load-test',
                'Idempotency-Key': uuidv4(),
            },
        });
    if (res.status === 429) {
        rateLimited.add(1);
    }
    check(res, { 'status 200/201/202/429': (r) => [200, 201, 202, 429].includes(r.status) });
}
