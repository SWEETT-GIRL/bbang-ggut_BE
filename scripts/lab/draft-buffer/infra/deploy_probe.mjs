// 배포(ASG 인스턴스 교체) 중 소켓·초안이 어떻게 되는지 잰다. 두 계정(사람 2357 · 봇 114)을 운영 코스 1047 에 넣고,
// A 가 1초마다 초안을 치고 B 가 STOMP 로 받는다. 소켓 끊김·재연결·수신 공백·PUT 실패·DB 반영을 시간순으로 남긴다.
//   node deploy_probe.mjs <scratch dir with jwt_secret.txt> [분, 기본 8]
import fs from 'node:fs'; import crypto from 'node:crypto';
const S = process.argv[2], MIN = Number(process.argv[3] || 8);
const BASE = 'https://v2.bbang-ggut.cloud', WS = 'wss://v2.bbang-ggut.cloud/ws', COURSE = 1047, ME = 2357, BOT = 114;
const secret = Buffer.from(fs.readFileSync(`${S}/jwt_secret.txt`, 'utf8').trim(), 'base64');
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const token = (sub) => { const h = b64({alg: 'HS256'}), p = b64({sub: String(sub), jti: crypto.randomUUID(), iat: (Date.now()/1000|0), exp: (Date.now()/1000|0) + 3600});
  return `${h}.${p}.${crypto.createHmac('sha256', secret).update(`${h}.${p}`).digest('base64url')}`; };
const T = {[ME]: token(ME), [BOT]: token(BOT)};
const t0 = Date.now(); const ts = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(6);
const events = []; const log = (k, v = '') => { const line = `${ts()}s ${k} ${typeof v === 'string' ? v : JSON.stringify(v)}`; events.push(line); console.log(line); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function api(who, method, path, body) { try { const r = await fetch(BASE + path, {method, headers: {Authorization: `Bearer ${T[who]}`, 'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5000)});
  let j = {}; try { j = await r.json(); } catch {} return {status: r.status, ...j}; } catch (e) { return {status: 0, error: e.name}; } }

// --- 받는 쪽(B): 앱과 같은 재연결(0~3초 지터), 신호 시각 기록
const received = []; let sockState = 'init', disconnects = 0, reconnects = 0, lastSignalAt = null;
function connect(who, onSignal) {
  const ws = new WebSocket(WS); const frame = (c, h) => `${c}\n${Object.entries(h).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n\0`;
  ws.addEventListener('open', () => ws.send(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '10000,10000', Authorization: `Bearer ${T[who]}`})));
  ws.addEventListener('message', (ev) => { for (const m of String(ev.data).split('\0').filter((p) => p.trim())) {
    if (m.startsWith('CONNECTED')) { ws.send(frame('SUBSCRIBE', {id: 'sub-0', destination: `/user/queue/courses/${COURSE}`})); if (sockState === 'down') { reconnects++; log('SOCK reconnected'); } sockState = 'up'; }
    else if (m.startsWith('MESSAGE')) onSignal(JSON.parse(m.slice(m.indexOf('\n\n') + 2)), Date.now());
    else if (m.startsWith('ERROR')) log('SOCK error', m.split('\n')[1]); } });
  ws.addEventListener('close', (ev) => { if (sockState !== 'stopped') { sockState = 'down'; disconnects++; log('SOCK closed', `code=${ev.code}`); setTimeout(() => connect(who, onSignal), 500 + Math.random() * 2500); } });
  ws.addEventListener('error', () => {});
  return ws;
}
(async () => {
  log('members', (await api(ME, 'GET', `/courses/${COURSE}`)).data?.members?.map((m) => m.memberId));
  const inv = await api(ME, 'POST', `/courses/${COURSE}/invites`); const acc = await api(BOT, 'POST', `/courses/invites/${inv.data?.token}/accept`, {}); log('bot joined', acc.data);
  const d = await api(ME, 'GET', `/courses/${COURSE}`); const storeId = d.data.courseStores[0].storeId; log('draftEnabled', d.data.draftEnabled);
  connect(BOT, (sig, at) => { if (sig.type === 'DRAFT' && sig.memberId === ME && sig.content?.startsWith('probe ')) { const sent = Number(sig.content.slice(6)); received.push({seq: sig.seq, sent, at}); lastSignalAt = at; } else if (sig.type === 'VERSION') log('VERSION signal'); });
  await sleep(1500);
  // --- 쓰는 쪽(A): 1초마다 PUT, 실패·지연 기록
  const sent = []; let seq = Date.now(); const end = t0 + MIN * 60_000; let lastOkAt = Date.now(), outage = null;
  while (Date.now() < end) {
    seq += 1; const content = `probe ${Date.now()}`; const t = Date.now(); const r = await api(ME, 'PUT', `/courses/${COURSE}/stores/${storeId}/memo-draft`, {content, seq});
    const took = Date.now() - t; sent.push({seq, status: r.status, took});
    if (r.status === 200) { if (outage) { log('PUT recovered', `after ${((Date.now() - outage) / 1000).toFixed(1)}s`); outage = null; } lastOkAt = Date.now(); }
    else { if (!outage) { outage = Date.now(); log('PUT failed', `status=${r.status} ${r.code || r.error || ''}`); } }
    if (lastSignalAt && Date.now() - lastSignalAt > 5000 && sockState === 'up') { /* 수신 공백은 끝에 계산 */ }
    await sleep(1000);
  }
  await sleep(8000); sockState = 'stopped';
  // --- 집계
  const ok = sent.filter((s) => s.status === 200); const recvSeq = new Set(received.map((r) => r.seq));
  const okNotReceived = ok.filter((s) => !recvSeq.has(s.seq)).length;
  const lat = received.map((r) => r.at - r.sent).sort((a, b) => a - b); const q = (p) => lat[Math.min(lat.length - 1, Math.floor(p * lat.length))];
  let maxGap = 0; for (let i = 1; i < received.length; i++) maxGap = Math.max(maxGap, received[i].at - received[i - 1].at);
  const detail = await api(ME, 'GET', `/courses/${COURSE}`); const memo = detail.data.courseStores.find((s) => s.storeId === storeId)?.memos?.find((m) => m.memberId === ME);
  const lastOk = ok[ok.length - 1];
  const summary = {minutes: MIN, put_total: sent.length, put_ok: ok.length, put_failed: sent.length - ok.length, put_p95_ms: sent.map((s) => s.took).sort((a, b) => a - b)[Math.floor(sent.length * 0.95)],
    socket_disconnects: disconnects, socket_reconnects: reconnects, signals_received: received.length, ok_sent_but_not_received: okNotReceived, signal_latency_p50_ms: q(0.5), signal_latency_p95_ms: q(0.95), max_receive_gap_s: +(maxGap / 1000).toFixed(1),
    db_last_seq_matches_last_ok_put: memo?.seq === lastOk?.seq, db_seq: memo?.seq, last_ok_seq: lastOk?.seq};
  log('SUMMARY', summary);
  // --- 정리
  await api(ME, 'PUT', `/courses/${COURSE}/stores/${storeId}/memo-draft`, {content: '', seq: seq + 1}); await sleep(6000);
  log('bot leave', (await api(BOT, 'DELETE', `/courses/${COURSE}/members/me`)).status);
  log('final', {members: (await api(ME, 'GET', `/courses/${COURSE}`)).data?.members?.map((m) => m.memberId)});
  fs.writeFileSync(`${S}/deploy_probe_out.json`, JSON.stringify({summary, events, sent, received}, null, 1));
  process.exit(0);
})().catch((e) => { console.error('실패', e); process.exit(1); });
