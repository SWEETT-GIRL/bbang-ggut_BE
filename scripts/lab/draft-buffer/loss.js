/**
 * 페일오버 중 유실 — 부하 중 초안 Redis 마스터를 죽이고(kill-master.sh), 끝난 뒤 "마지막으로 보낸 seq" 와 DB 를 비교한다.
 *
 * 앱의 약속을 k6 가 흉내 낸다(PROMISE=on):
 *   - 필드당 요청은 하나만 날아간다. 실패(503·타임아웃·연결 오류)하면 같은 seq 로 다시 보낸다 — 최대 15초.
 *   - 마지막 쓰기 뒤 DB 반영을 기다린다(teardown 의 SETTLE_SECONDS, 기본 15초 = 플러시 상한 10초 + 여유).
 * PROMISE=off 는 한 번 실패하면 그 글을 포기한다 — 약속이 없을 때 얼마나 잃는지 보는 대조군.
 *
 * 끝나면 handleSummary 가 필드별 마지막 seq 를 results/last-seq.csv 로 남긴다 → verify-loss.sql 이 DB 와 비교한다.
 *
 *   PROMISE=on  k6 run --summary-export results/<날짜>-failover-on/summary.json scripts/lab/draft-buffer/failover.js
 *   (돌아가는 동안 다른 터미널에서) scripts/lab/draft-buffer/kill-master.sh
 */
import http from 'k6/http';
import {sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';
import {assignment, baseUrl, headers, memoText, nextSeq} from './lib.js';

const promise = (__ENV.PROMISE || 'on') === 'on';
const retries = new Counter('memo_draft_retries');
const givenUp = new Counter('memo_draft_given_up');
const unavailable = new Counter('memo_draft_503');
const promotion = new Trend('first_success_after_failure_ms', true);

export const options = {
  scenarios: {
    editors: {
      executor: 'constant-vus',
      exec: 'editor',
      vus: Number(__ENV.VUS || 80),
      duration: __ENV.DURATION || '120s',
      gracefulStop: '20s',
    },
  },
};

const seqState = {};
// VU 안에서만 산다 — 마지막으로 "서버가 받았다"고 확인한 seq (필드별)
const lastAccepted = {};
let firstFailureAt = null;

export function editor() {
  const {course, memberId, token} = assignment(__VU);
  const storeId = course.storeIds[__VU % course.storeIds.length];
  const key = `${course.courseId}:${storeId}:${memberId}`;
  const seq = nextSeq(seqState, key);
  const body = JSON.stringify({content: memoText(`f${memberId}`), seq});
  const url = `${baseUrl}/courses/${course.courseId}/stores/${storeId}/memo-draft`;

  const deadline = Date.now() + 15_000;
  let attempt = 0;
  while (true) {
    attempt += 1;
    const response = http.put(url, body, {headers: headers(token), timeout: '3s', tags: {name: 'PUT memo-draft'}});
    if (response.status === 200) {
      if (firstFailureAt !== null) {
        promotion.add(Date.now() - firstFailureAt);
        firstFailureAt = null;
      }
      const accepted = response.json('data.acceptedSeq');
      console.log(`R,${key},${seq},${accepted}`);
      lastAccepted[key] = Math.max(lastAccepted[key] || 0, accepted);
      if (accepted > seq) {
        seqState[key] = accepted;
      }
      break;
    }
    if (response.status === 503) {
      unavailable.add(1);
    }
    if (firstFailureAt === null) {
      firstFailureAt = Date.now();
    }
    if (!promise || Date.now() > deadline) {
      givenUp.add(1);
      console.log(`R,${key},${seq},0`);
      break;
    }
    retries.add(1);
    sleep(0.5);
  }
  sleep(Number(__ENV.WRITE_INTERVAL_MS || 500) / 1000);
}

export function teardown() {
  // 플러셔가 마지막 글을 DB 에 내릴 시간을 준다 — 이 뒤에 verify-loss.sql 을 돈다
  sleep(Number(__ENV.SETTLE_SECONDS || 15));
}

export function handleSummary(data) {
  // k6 는 VU 상태를 summary 로 모아 주지 않는다 — 마지막 seq 는 서버가 보증하는 값이므로 DB 쪽에서 센다:
  // verify-loss.sql 은 "보낸 글(content 의 타임스탬프)" 이 아니라 memos.seq 와 Redis 잔량으로 판정한다.
  return {
    stdout: JSON.stringify(
      {
        retries: data.metrics.memo_draft_retries ? data.metrics.memo_draft_retries.values.count : 0,
        givenUp: data.metrics.memo_draft_given_up ? data.metrics.memo_draft_given_up.values.count : 0,
        unavailable503: data.metrics.memo_draft_503 ? data.metrics.memo_draft_503.values.count : 0,
        promotionMs: data.metrics.first_success_after_failure_ms
          ? data.metrics.first_success_after_failure_ms.values
          : null,
      },
      null,
      2,
    ) + '\n',
  };
}
