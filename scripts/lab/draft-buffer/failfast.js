/**
 * Redis 가 끊기거나 멈춘 동안 PUT 이 어떻게 실패하는가 — 상태별 응답 시간과 건수. 편집자 수는 고정.
 *   VUS=1280 DURATION=75s DATA_DIR=. BASE_URL=http://<앱 서버>:8080 k6 run --compatibility-mode=base failfast.js
 */
import http from 'k6/http';
import {sleep} from 'k6';
import {Trend, Counter} from 'k6/metrics';
import {assignment, baseUrl, headers, memoText, nextSeq} from './lib.js';

const okDuration = new Trend('put_200_duration', true);
const unavailableDuration = new Trend('put_503_duration', true);
const otherDuration = new Trend('put_other_duration', true);
const ok = new Counter('put_200');
const unavailable = new Counter('put_503');
const other = new Counter('put_other');

export const options = {
  scenarios: {editors: {executor: 'constant-vus', exec: 'editor', vus: Number(__ENV.VUS || 1280), duration: __ENV.DURATION || '75s'}},
};

const seqState = {};

export function editor() {
  const {course, memberId, token} = assignment(__VU);
  const storeId = course.storeIds[__VU % course.storeIds.length];
  const key = `${storeId}:${memberId}`;
  const response = http.put(
    `${baseUrl}/courses/${course.courseId}/stores/${storeId}/memo-draft`,
    JSON.stringify({content: memoText(`d${memberId}`), seq: nextSeq(seqState, key)}),
    {headers: headers(token), timeout: '10s'},
  );
  if (response.status === 200) {
    ok.add(1); okDuration.add(response.timings.duration);
  } else if (response.status === 503) {
    unavailable.add(1); unavailableDuration.add(response.timings.duration);
  } else {
    other.add(1); otherDuration.add(response.timings.duration);
  }
  sleep(0.5);
}
