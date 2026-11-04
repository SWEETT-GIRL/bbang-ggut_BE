/**
 * 기준선(전) — 기존 PATCH 로 0.5초마다 메모를 저장하면 어디서 깨지나.
 *
 * VU 하나 = 코스의 멤버 하나. 0.5초마다 자기 가게 하나의 메모를 PATCH 한다 (코스 행 락 → 메모 upsert → bumpVersion).
 * 램프업으로 "저장 p95 < 500ms 그리고 에러율 < 1%" 가 처음 깨지는 단계를 찾는다 — 그 직전 값이 대표 숫자 1(전).
 * 동시에 코스와 무관한 가게 검색을 저강도로 계속 불러 메모 부하가 다른 기능까지 끌어내리는지 본다.
 *
 *   k6 run --summary-export results/<날짜>-baseline/summary.json scripts/lab/draft-buffer/baseline.js
 *   환경: BASE_URL · STAGES · MEMBERS_PER_COURSE · WRITE_INTERVAL_MS(기본 500)
 */
import http from 'k6/http';
import {check, sleep} from 'k6';
import {Trend, Rate} from 'k6/metrics';
import {assignment, baseUrl, headers, memoText, rampStages} from './lib.js';

const saveDuration = new Trend('memo_save_duration', true);
const saveFailed = new Rate('memo_save_failed');
const searchDuration = new Trend('unrelated_search_duration', true);

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
  thresholds: {
    memo_save_duration: ['p(95)<500'],
    memo_save_failed: ['rate<0.01'],
  },
};

function totalDuration() {
  const seconds = rampStages().reduce((sum, stage) => sum + Number(stage.duration.replace('s', '')), 0);
  return `${seconds}s`;
}

export function editor() {
  const {course, memberId, token} = assignment(__VU);
  const storeId = course.storeIds[__VU % course.storeIds.length];
  const response = http.patch(
    `${baseUrl}/courses/${course.courseId}/stores/${storeId}`,
    JSON.stringify({memo: memoText(`m${memberId}`)}),
    {headers: headers(token), tags: {name: 'PATCH memo'}},
  );
  saveDuration.add(response.timings.duration);
  saveFailed.add(response.status !== 200);
  check(response, {'PATCH 200': (res) => res.status === 200});
  sleep(Number(__ENV.WRITE_INTERVAL_MS || 500) / 1000);
}

export function search() {
  const response = http.get(`${baseUrl}/stores/search?query=${encodeURIComponent('소금빵')}`, {
    headers: headers(assignment(1).token),
    tags: {name: 'GET search'},
  });
  searchDuration.add(response.timings.duration);
}
