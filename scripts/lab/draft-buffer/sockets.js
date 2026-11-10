/**
 * W1 — 인스턴스당 STOMP 소켓 한계. VU 하나 = 소켓 하나(CONNECT → SUBSCRIBE 뒤 유지, 하트비트 10초).
 * 단계별로 소켓 수를 올리며(1k → 2k → 3k → 4k) 접속 성공률·CONNECTED 까지 시간을 재고, 서버 쪽은 sample.sh 가 메모리·CPU·소켓 수를 찍는다.
 *   STAGES="60s:1000,60s:1000,60s:2000,60s:2000,..." DATA_DIR=. BASE_URL=http://<앱 서버>:8080 k6 run sockets.js
 */
import ws from 'k6/ws';
import {Counter, Trend} from 'k6/metrics';
import {assignment, baseUrl, rampStages} from './lib.js';

const connected = new Counter('stomp_connected');
const failed = new Counter('stomp_failed');
const connectMs = new Trend('stomp_connect_ms', true);
const holdSeconds = Number(__ENV.HOLD_SECONDS || 55);

export const options = {
  scenarios: {sockets: {executor: 'ramping-vus', exec: 'socket', stages: rampStages(), gracefulRampDown: '5s', gracefulStop: '5s'}},
};

function frame(command, headers) {
  return `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n\u0000`;
}

export function socket() {
  const {course, token} = assignment(__VU);
  const url = baseUrl.replace(/^http/, 'ws') + '/ws';
  const started = Date.now();
  let ok = false;
  const response = ws.connect(url, {}, (sock) => {
    sock.on('open', () => sock.send(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '10000,10000', Authorization: `Bearer ${token}`})));
    sock.on('message', (raw) => {
      if (raw.startsWith('CONNECTED')) {
        ok = true; connected.add(1); connectMs.add(Date.now() - started);
        sock.send(frame('SUBSCRIBE', {id: 'sub-0', destination: `/user/queue/courses/${course.courseId}`}));
        sock.setInterval(() => sock.send('\n'), 10000); // 클라이언트 하트비트
      } else if (raw.startsWith('ERROR')) { failed.add(1); sock.close(); }
    });
    sock.on('error', () => { if (!ok) failed.add(1); });
    sock.setTimeout(() => sock.close(), holdSeconds * 1000);
  });
  if (!response || response.status !== 101) failed.add(1);
}
