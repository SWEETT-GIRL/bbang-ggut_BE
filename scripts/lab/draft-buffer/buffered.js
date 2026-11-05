/**
 * 후 — 같은 부하·같은 램프업으로 PUT memo-draft (Redis 버퍼).
 *
 * baseline.js 와 VU 배정·간격·판정 기준(p95 < 500ms · 에러율 < 1%)이 같다. 다른 것은 호출하는 API 만이다.
 * 합치기 비율은 끝난 뒤 Prometheus 에서 draft_write_total / draft_flush_batch_size_sum 으로 읽는다(observe.sh).
 *
 *   k6 run --summary-export results/<날짜>-buffered/summary.json scripts/lab/draft-buffer/buffered.js
 */
import http from 'k6/http';
import {check, sleep} from 'k6';
import {Trend, Rate, Counter} from 'k6/metrics';
import {assignment, baseUrl, currentStage, headers, memoText, nextSeq, rampStages, stageThresholds} from './lib.js';

const saveDuration = new Trend('memo_save_duration', true);
const saveFailed = new Rate('memo_save_failed');
const searchDuration = new Trend('unrelated_search_duration', true);
const unavailable = new Counter('memo_draft_503');

export const options = {
  scenarios: {
    editors: {
      executor: 'ramping-vus',
      exec: 'editor',
      startVUs: 0,
      stages: rampStages(),
      gracefulRampDown: '5s',
    },
    unrelated: {
      executor: 'constant-arrival-rate',
      exec: 'search',
      rate: Number(__ENV.SEARCH_RATE || 5),
      timeUnit: '1s',
      duration: totalDuration(),
      preAllocatedVUs: 5,
    },
  },
  thresholds: stageThresholds(),
};

function totalDuration() {
  const seconds = rampStages().reduce((sum, stage) => sum + Number(stage.duration.replace('s', '')), 0);
  return `${seconds}s`;
}

const seqState = {};

export function editor() {
  const {course, memberId, token} = assignment(__VU);
  const storeId = course.storeIds[__VU % course.storeIds.length];
  const key = `${storeId}:${memberId}`;
  const seq = nextSeq(seqState, key);
  const response = http.put(
    `${baseUrl}/courses/${course.courseId}/stores/${storeId}/memo-draft`,
    JSON.stringify({content: memoText(`d${memberId}`), seq}),
    {headers: headers(token), tags: {name: 'PUT memo-draft'}},
  );
  const stage = currentStage();
  saveDuration.add(response.timings.duration, {stage});
  saveFailed.add(response.status !== 200, {stage});
  if (response.status === 503) {
    unavailable.add(1);
  }
  if (response.status === 200) {
    // 서버가 더 큰 seq 를 돌려주면(다른 기기) 시계를 그 위로 올린다
    const accepted = response.json('data.acceptedSeq');
    if (accepted > seq) {
      seqState[key] = accepted;
    }
  }
  check(response, {'PUT 200': (res) => res.status === 200});
  sleep(Number(__ENV.WRITE_INTERVAL_MS || 500) / 1000);
}

export function search() {
  const response = http.get(`${baseUrl}/stores/search?query=${encodeURIComponent('소금빵')}`, {
    headers: headers(assignment(1).token),
    tags: {name: 'GET search'},
  });
  searchDuration.add(response.timings.duration);
}
