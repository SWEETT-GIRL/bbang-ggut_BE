/**
 * 일행 화면 반영 지연 — 쓰는 쪽 요청 시각 → 받는 쪽이 새 내용을 손에 넣은 시각.
 *
 * 쓰는 VU 하나가 2초마다 "probe <보낸 ms>" 를 쓴다. 받는 VU 하나가 그 내용을 처음 보는 순간을 잰다.
 *   MODE=poll  (전): 기존 PATCH 로 쓰고, 받는 쪽은 8초 폴링 + If-None-Match(ETag) 로 상세를 받는다.
 *   MODE=push  (후): PUT memo-draft 로 쓰고, 받는 쪽은 /ws 에 STOMP 로 붙어 DRAFT 신호를 받는다.
 * 지연 = 받은 시각 - 내용에 박힌 보낸 ms (같은 머신이라 시계가 같다). p95 가 대표 숫자 3.
 *
 *   MODE=poll k6 run --summary-export results/<날짜>-latency-poll/summary.json scripts/lab/draft-buffer/latency-probe.js
 *   MODE=push k6 run --summary-export results/<날짜>-latency-push/summary.json scripts/lab/draft-buffer/latency-probe.js
 */
import http from 'k6/http';
import ws from 'k6/ws';
import {sleep} from 'k6';
import {Trend} from 'k6/metrics';
import {baseUrl, courses, headers, nextSeq} from './lib.js';
import {SharedArray} from 'k6/data';

const mode = __ENV.MODE || 'push';
const duration = __ENV.DURATION || '120s';
const latency = new Trend('companion_visible_latency_ms', true);

// k6 의 open() 은 이 스크립트 파일 위치 기준이다 — 데이터 파일은 같은 폴더에 둔다
const dir = __ENV.DATA_DIR || '.';
const tokens = new SharedArray('probe-tokens', () =>
  open(`${dir}/tokens.csv`).trim().split('\n').filter(Boolean).map((line) => {
    const [memberId, token] = line.split(',');
    return {memberId: Number(memberId), token};
  }),
);
function tokenOf(memberId) {
  return tokens.find((row) => row.memberId === memberId).token;
}

const course = courses[0];
const writer = course.memberIds[0];
const reader = course.memberIds[1];
const storeId = course.storeIds[0];

export const options = {
  scenarios: {
    writer: {executor: 'constant-vus', exec: 'write', vus: 1, duration},
    reader: {executor: 'constant-vus', exec: mode === 'push' ? 'readPush' : 'readPoll', vus: 1, duration},
  },
};

const seqState = {};

export function write() {
  const content = `probe ${Date.now()}`;
  if (mode === 'push') {
    const seq = nextSeq(seqState, 'probe');
    http.put(`${baseUrl}/courses/${course.courseId}/stores/${storeId}/memo-draft`,
      JSON.stringify({content, seq}), {headers: headers(tokenOf(writer))});
  } else {
    http.patch(`${baseUrl}/courses/${course.courseId}/stores/${storeId}`,
      JSON.stringify({memo: content}), {headers: headers(tokenOf(writer))});
  }
  sleep(Number(__ENV.WRITE_INTERVAL_SECONDS || 2));
}

let lastSeen = null;
let etag = null;

export function readPoll() {
  const requestHeaders = headers(tokenOf(reader));
  if (etag) {
    requestHeaders['If-None-Match'] = etag;
  }
  const response = http.get(`${baseUrl}/courses/${course.courseId}`, {headers: requestHeaders});
  if (response.status === 200) {
    etag = response.headers['Etag'] || response.headers['ETag'];
    const stop = response.json('data.courseStores').find((row) => row.storeId === storeId);
    const memo = stop && stop.memos.find((row) => row.memberId === writer);
    observe(memo && memo.content);
  }
  sleep(Number(__ENV.POLL_INTERVAL_SECONDS || 8));
}

export function readPush() {
  const wsUrl = baseUrl.replace(/^http/, 'ws') + '/ws';
  ws.connect(wsUrl, {}, (socket) => {
    socket.on('open', () => {
      socket.send(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '0,0', Authorization: `Bearer ${tokenOf(reader)}`}));
    });
    socket.on('message', (raw) => {
      for (const message of raw.split('\u0000').filter((part) => part.trim().length > 0)) {
        if (message.startsWith('CONNECTED')) {
          socket.send(frame('SUBSCRIBE', {id: 'sub-0', destination: `/user/queue/courses/${course.courseId}`}));
        } else if (message.startsWith('MESSAGE')) {
          const body = JSON.parse(message.slice(message.indexOf('\n\n') + 2));
          if (body.type === 'DRAFT' && body.memberId === writer) {
            observe(body.content);
          }
        } else if (message.startsWith('ERROR')) {
          console.error(message);
          socket.close();
        }
      }
    });
    socket.setTimeout(() => socket.close(), toMillis(duration));
  });
}

function observe(content) {
  if (!content || content === lastSeen || !content.startsWith('probe ')) {
    return;
  }
  lastSeen = content;
  latency.add(Date.now() - Number(content.slice('probe '.length)));
}

function frame(command, frameHeaders) {
  const lines = Object.entries(frameHeaders).map(([key, value]) => `${key}:${value}`);
  return `${command}\n${lines.join('\n')}\n\n\u0000`;
}

function toMillis(spec) {
  const match = /^(\d+)(s|m)$/.exec(spec);
  return Number(match[1]) * (match[2] === 'm' ? 60_000 : 1_000);
}
