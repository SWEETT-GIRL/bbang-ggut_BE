/**
 * W2 — 재연결 폭풍. 소켓 N개를 유지하다 서버가 재시작되면(밖에서 run-app.sh) 모두 끊긴다.
 * 각 VU 는 앱과 같은 규칙으로 재연결한다: JITTER_MS(기본 0~3000) 만큼 기다렸다가 다시 CONNECT. 끊김 시각·재연결 완료 시각을 남긴다.
 *   VUS=1000 DURATION=240s JITTER_MS=3000 DATA_DIR=. BASE_URL=http://<앱 서버>:8080 k6 run storm.js   (JITTER_MS=0 이 대조군)
 */
import ws from 'k6/ws';
import {sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';
import {assignment, baseUrl} from './lib.js';

const connected = new Counter('stomp_connected');
const dropped = new Counter('stomp_dropped');
const reconnectMs = new Trend('stomp_reconnect_ms', true);   // 끊김 → 다시 CONNECTED
const jitter = Number(__ENV.JITTER_MS || 3000);
const duration = __ENV.DURATION || '240s';

export const options = {scenarios: {s: {executor: 'constant-vus', exec: 'hold', vus: Number(__ENV.VUS || 1000), duration, gracefulStop: '5s'}}};
const frame = (c, h) => `${c}\n${Object.entries(h).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n\u0000`;

export function hold() {
  const {course, token} = assignment(__VU);
  const url = baseUrl.replace(/^http/, 'ws') + '/ws';
  let droppedAt = null;
  const end = Date.now() + toMs(duration) - 6000;
  while (Date.now() < end) {
    let gotConnected = false;
    const res = ws.connect(url, {}, (sock) => {
      sock.on('open', () => sock.send(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '10000,10000', Authorization: `Bearer ${token}`})));
      sock.on('message', (raw) => {
        if (raw.startsWith('CONNECTED')) {
          gotConnected = true; connected.add(1);
          if (droppedAt !== null) { reconnectMs.add(Date.now() - droppedAt); droppedAt = null; }
          sock.send(frame('SUBSCRIBE', {id: 'sub-0', destination: `/user/queue/courses/${course.courseId}`}));
          sock.setInterval(() => sock.send('\n'), 10000);
        }
      });
      sock.on('close', () => { if (gotConnected && Date.now() < end) { dropped.add(1); droppedAt = Date.now(); } });
      sock.on('error', () => {});
      sock.setTimeout(() => sock.close(), end - Date.now());
    });
    if (!gotConnected && droppedAt === null) droppedAt = Date.now(); // 처음부터 못 붙었으면 재시도 대기
    if (Date.now() >= end) break;
    sleep((jitter > 0 ? Math.random() * jitter : 0) / 1000 + 0.2);
  }
}
function toMs(spec) { const m = /^(\d+)(s|m)$/.exec(spec); return Number(m[1]) * (m[2] === 'm' ? 60000 : 1000); }
