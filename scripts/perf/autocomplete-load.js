// 자동완성 부하 — 운영에서 사람들이 실제로 친 검색어를 그 순서대로 초당 RATE 건으로 재생한다.
//
// 캐시 적중률은 같은 검색어가 얼마나 반복되느냐로 정해진다. 무작위로 뽑으면 요청이 많을수록 드문 검색어까지
// 반복돼 적중률이 부풀려진다. 그래서 실제 순서를 빨리 감는다. 한 바퀴만 돈다 — 두 바퀴째는 전부 적중이다.
//
//   BASE_URL=http://<앱 사설 IP>:8080 RATE=100 k6 run autocomplete-load.js
//
// 입력: build-autocomplete-queries.py 가 만든 autocomplete-queries.json
import http from 'k6/http';
import exec from 'k6/execution';
import {check} from 'k6';
import {SharedArray} from 'k6/data';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const rate = Number(__ENV.RATE || 50);

if (baseUrl.includes('bbang-ggut.cloud')) {
  throw new Error('운영은 부하 대상이 아닙니다.');
}

const queries = new SharedArray('queries', () => JSON.parse(open(__ENV.QUERIES || './autocomplete-queries.json')));

export const options = {
  scenarios: {
    replay: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      // 검색어를 한 번씩만 — 다 쓰면 끝난다
      duration: `${Math.ceil(queries.length / rate)}s`,
      preAllocatedVUs: Math.min(rate * 2, 500),
      maxVUs: Math.min(rate * 10, 2000),
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // 측정이 목적이라 실패로 끊지 않는다 — 다만 요약에 기준선을 남긴다
    http_req_failed: [{threshold: 'rate<0.01', abortOnFail: false}],
  },
};

function url(query) {
  // 한글은 반드시 인코딩한다 — 날것이면 톰캣이 URI 단계에서 400 으로 거부해 "빠르다"는 잘못된 측정이 나온다
  let path = `/stores/autocomplete?query=${encodeURIComponent(query.q)}`;
  if (query.size) {
    path += `&size=${query.size}`;
  }
  if (query.types) {
    path += `&types=${encodeURIComponent(query.types)}`;
  }
  return `${baseUrl}${path}`;
}

export default function () {
  const index = exec.scenario.iterationInTest;
  if (index >= queries.length) {
    return;
  }
  const response = http.get(url(queries[index]), {tags: {name: 'autocomplete'}});
  check(response, {'200': (res) => res.status === 200});
}
