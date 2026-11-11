/**
 * 과부하 보호 전후 — 한계를 넘는 편집자 수에서 "받아들인 요청"의 응답 시간과 초당 성공 건수(goodput), 거절(429) 수를 단계별로.
 * buffered.js 와 부하 모양이 같다. 다른 것은 집계뿐: 200 만의 응답 시간(memo_ok_duration)과 200 · 429 · 그 외를 따로 센다.
 * 429 를 받은 VU 는 앱처럼 0.5초 뒤 다시 보낸다(원래 간격과 같다).
 *
 *   STAGES="20s:640,40s:640,..." DATA_DIR=. BASE_URL=http://<앱 서버>:8080 k6 run --compatibility-mode=base shed.js
 */
import http from 'k6/http';
import {sleep} from 'k6';
import {Trend, Counter} from 'k6/metrics';
import {assignment, baseUrl, currentStage, headers, memoText, nextSeq, rampStages} from './lib.js';

// 앱 서버 여러 대에 VU 를 고르게 나눈다(로드밸런서 없이). URLS="http://a:8080,http://b:8080"
const urls = (__ENV.URLS || baseUrl).split(',');

const okDuration = new Trend('memo_ok_duration', true);
const shedDuration = new Trend('memo_shed_duration', true);
const ok = new Counter('memo_ok');
const shed = new Counter('memo_shed');
const other = new Counter('memo_other');

function thresholds() {
  const result = {};
  rampStages().forEach((stage, i) => {
    const tag = `{stage:${i + 1}:${stage.target}}`;
    result[`memo_ok_duration${tag}`] = ['p(95)>=0'];
    result[`memo_shed_duration${tag}`] = ['p(95)>=0'];
    result[`memo_ok${tag}`] = ['count>=0'];
    result[`memo_shed${tag}`] = ['count>=0'];
    result[`memo_other${tag}`] = ['count>=0'];
  });
  return result;
}

export const options = {
  scenarios: {
    editors: {executor: 'ramping-vus', exec: 'editor', startVUs: 0, stages: rampStages(), gracefulRampDown: '5s'},
  },
  thresholds: thresholds(),
};

const seqState = {};

export function editor() {
  const {course, memberId, token} = assignment(__VU);
  const storeId = course.storeIds[__VU % course.storeIds.length];
  const key = `${storeId}:${memberId}`;
  const seq = nextSeq(seqState, key);
  const response = http.put(
    `${urls[__VU % urls.length]}/courses/${course.courseId}/stores/${storeId}/memo-draft`,
    JSON.stringify({content: memoText(`d${memberId}`), seq}),
    {headers: headers(token), tags: {name: 'PUT memo-draft'}},
  );
  const stage = currentStage();
  if (response.status === 200) {
    ok.add(1, {stage});
    okDuration.add(response.timings.duration, {stage});
  } else if (response.status === 429) {
    shed.add(1, {stage});
    shedDuration.add(response.timings.duration, {stage});
    // 거절당한 클라이언트가 쉬는 시간 — 지금 앱은 0.5초 고정. BACKOFF_429_MS 로 더 길게(±50% 지터) 쉬는 앱을 흉내 낸다
    const backoff = Number(__ENV.BACKOFF_429_MS || 0);
    if (backoff > 0) {
      sleep((backoff * (0.5 + Math.random())) / 1000);
      return;
    }
  } else {
    other.add(1, {stage});
  }
  sleep(Number(__ENV.WRITE_INTERVAL_MS || 500) / 1000);
}
