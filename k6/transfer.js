// 비관적 락 vs 낙관적 락 이체 부하 테스트
//
// 환경변수
//   SCENARIO  hot | spread           (기본 hot)
//             hot    : K6H000000000 한 계좌에서 받는 계좌 100개 중 하나로 이체 (같은 행에 충돌 집중)
//             spread : K6S 계좌 1,000개 중 서로 다른 두 계좌를 무작위로 골라 이체 (충돌 거의 없음)
//   LOCK      PESSIMISTIC | OPTIMISTIC (기본 PESSIMISTIC)
//   BASE_URL  기본 http://localhost:8080
//   VUS       동시 가상 사용자 수 (기본 50)
//   DURATION  실행 시간 (기본 30s)
//   SUMMARY_FILE  결과 JSON 저장 경로 (기본 k6/results/<SCENARIO>-<LOCK>.json)
//
// 계좌는 k6/reset.sql로 미리 만들어 둔다.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const SCENARIO = __ENV.SCENARIO || 'hot';
const LOCK = __ENV.LOCK || 'PESSIMISTIC';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '30s';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || `k6/results/${SCENARIO}-${LOCK}.json`;

const AMOUNT = 1000;
const HOT_SENDER = 'K6H000000000';
const HOT_RECEIVER_COUNT = 100;   // reset.sql과 맞춰야 함
const SPREAD_ACCOUNT_COUNT = 1000; // reset.sql과 맞춰야 함

if (!['hot', 'spread'].includes(SCENARIO)) throw new Error(`SCENARIO must be hot|spread: ${SCENARIO}`);
if (!['PESSIMISTIC', 'OPTIMISTIC'].includes(LOCK)) throw new Error(`LOCK must be PESSIMISTIC|OPTIMISTIC: ${LOCK}`);

const transferSuccess = new Counter('transfer_success');
const transferConflict = new Counter('transfer_conflict');   // 409 TRANSFER_CONFLICT (낙관적 락 재시도 소진)
const transferOtherFail = new Counter('transfer_other_fail'); // 그 밖의 모든 실패 (있으면 안 됨)
const transferFailed = new Rate('transfer_failed');
const transferDuration = new Trend('transfer_duration', true);

export const options = {
    scenarios: {
        transfer: { executor: 'constant-vus', vus: VUS, duration: DURATION },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const accountNumber = (prefix, n) => prefix + String(n).padStart(9, '0');
const randomInt = (min, max) => min + Math.floor(Math.random() * (max - min + 1));

function pickAccounts() {
    if (SCENARIO === 'hot') {
        return [HOT_SENDER, accountNumber('K6H', randomInt(1, HOT_RECEIVER_COUNT))];
    }
    const from = randomInt(1, SPREAD_ACCOUNT_COUNT);
    let to;
    do {
        to = randomInt(1, SPREAD_ACCOUNT_COUNT);
    } while (to === from);
    return [accountNumber('K6S', from), accountNumber('K6S', to)];
}

export function setup() {
    const probe = SCENARIO === 'hot' ? HOT_SENDER : accountNumber('K6S', 1);
    const res = http.get(`${BASE_URL}/api/accounts/${probe}`);
    if (res.status !== 200) {
        fail(`계좌 ${probe} 조회 실패 (status ${res.status}). 서버가 떠 있는지, k6/reset.sql을 실행했는지 확인하세요.`);
    }
}

export default function () {
    const [from, to] = pickAccounts();
    const res = http.post(
        `${BASE_URL}/api/transfers?lockStrategy=${LOCK}`,
        JSON.stringify({ fromAccountNumber: from, toAccountNumber: to, amount: AMOUNT }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'transfer' } },
    );

    const ok = res.status === 200;
    transferDuration.add(res.timings.duration);
    transferFailed.add(!ok);

    if (ok) {
        transferSuccess.add(1);
    } else if (res.status === 409 && res.json('code') === 'TRANSFER_CONFLICT') {
        transferConflict.add(1);
    } else {
        transferOtherFail.add(1);
    }
    check(res, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    const m = data.metrics;
    const count = (name) => (m[name] ? m[name].values.count : 0);
    const durationSec = data.state.testRunDurationMs / 1000;
    const result = {
        scenario: SCENARIO,
        lock: LOCK,
        vus: VUS,
        duration: DURATION,
        testRunDurationSec: Number(durationSec.toFixed(2)),
        requests: count('transfer_success') + count('transfer_conflict') + count('transfer_other_fail'),
        success: count('transfer_success'),
        conflict: count('transfer_conflict'),
        otherFail: count('transfer_other_fail'),
        successTps: Number((count('transfer_success') / durationSec).toFixed(1)),
        totalTps: Number(((count('transfer_success') + count('transfer_conflict') + count('transfer_other_fail'))
            / durationSec).toFixed(1)),
        failRate: m.transfer_failed ? m.transfer_failed.values.rate : 0,
        p95Ms: Number(m.transfer_duration.values['p(95)'].toFixed(1)),
        p99Ms: Number(m.transfer_duration.values['p(99)'].toFixed(1)),
        medMs: Number(m.transfer_duration.values.med.toFixed(1)),
    };

    const text = [
        '',
        `===== k6 결과 [${SCENARIO} / ${LOCK}] VUs ${VUS}, ${DURATION} =====`,
        `요청 ${result.requests}건 / 성공 ${result.success} / 충돌 실패 ${result.conflict} / 기타 실패 ${result.otherFail}`,
        `성공 TPS ${result.successTps} (전체 TPS ${result.totalTps}) / 실패율 ${(result.failRate * 100).toFixed(2)}%`,
        `응답 시간 med ${result.medMs}ms / p95 ${result.p95Ms}ms / p99 ${result.p99Ms}ms`,
        '',
    ].join('\n');

    return {
        stdout: text,
        [SUMMARY_FILE]: JSON.stringify(result, null, 2),
    };
}
