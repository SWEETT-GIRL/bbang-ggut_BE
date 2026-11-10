/** 2번 — 회원당 초당 10회 제한이 서버 2대에서 어떻게 되나. 한 회원이 두 서버에 번갈아 20회/초 PUT. 429 비율을 센다.
 *   URLS="http://a:8080,http://b:8082" DURATION=20s DATA_DIR=. BASE_URL=http://a:8080 k6 run ratelimit.js */
import http from 'k6/http';
import {sleep} from 'k6';
import {Counter} from 'k6/metrics';
import {assignment, headers, nextSeq} from './lib.js';
const ok = new Counter('rl_ok'), limited = new Counter('rl_429'), other = new Counter('rl_other');
const urls = (__ENV.URLS || __ENV.BASE_URL).split(',');
export const options = {scenarios: {one: {executor: 'constant-arrival-rate', exec: 'put', rate: Number(__ENV.RATE || 20), timeUnit: '1s', duration: __ENV.DURATION || '20s', preAllocatedVUs: 10, maxVUs: 40}}};
const state = {}; let n = 0;
export function put() {
  const {course, memberId, token} = assignment(1); // 항상 같은 회원
  const storeId = course.storeIds[0]; const base = urls[(n++) % urls.length];
  const r = http.put(`${base}/courses/${course.courseId}/stores/${storeId}/memo-draft`, JSON.stringify({content: `rl ${Date.now()}`, seq: nextSeq(state, 'rl')}), {headers: headers(token)});
  if (r.status === 200) ok.add(1); else if (r.status === 429) limited.add(1); else other.add(1);
}
