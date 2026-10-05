import http from 'k6/http';
import {check, sleep} from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
// 쿼리의 한글은 반드시 인코딩한다 — 날것으로 두면 톰캣이 URI 단계에서 400 으로 거부하고,
// 앱까지 가지 않으므로 "빠르다"는 잘못된 측정이 나온다.
const defaultReadOnlyPaths = [
  `/stores/search?query=${encodeURIComponent('소금빵')}`,
  `/stores/autocomplete?query=${encodeURIComponent('소금')}`,
  '/stores/nearby?lat=37.5665&lng=126.9780',
  '/stores/popular?lat=37.5665&lng=126.9780',
  '/trend/nearby?lat=37.5665&lng=126.9780',
  '/trend/select?region=서울',
  '/menu-categories',
  '/banners',
];
const paths = (__ENV.API_PATHS || defaultReadOnlyPaths.join(','))
  .split(',')
  .map((path) => path.trim())
  .filter(Boolean);
const token = __ENV.AUTH_TOKEN || '';

if (baseUrl.includes('bbang-ggut.cloud') && __ENV.ALLOW_PROD !== 'true') {
  throw new Error('k6 is blocked for production. Set ALLOW_PROD=true only for an approved read-only smoke check.');
}

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
  },
};

export default function () {
  const path = paths[(__ITER + __VU) % paths.length];
  const headers = token ? {Authorization: `Bearer ${token}`} : {};
  const response = http.get(`${baseUrl}${path}`, {headers});

  check(response, {
    'status is 2xx or 3xx': (res) => res.status >= 200 && res.status < 400,
  });

  sleep(Number(__ENV.SLEEP_SECONDS || 1));
}
