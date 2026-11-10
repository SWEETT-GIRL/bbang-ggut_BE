import fs from 'node:fs'; import crypto from 'node:crypto';
const S = process.argv[2], BASE = 'https://v2.bbang-ggut.cloud', WS = 'wss://v2.bbang-ggut.cloud/ws';
const COURSE = 1047, ME = 2357, BOT = 114, N = Number(process.env.N || 20);
const secret = Buffer.from(fs.readFileSync(`${S}/jwt_secret.txt`, 'utf8').trim(), 'base64');
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const token = (sub) => { const h = b64({alg: 'HS256'}), p = b64({sub: String(sub), jti: crypto.randomUUID(), iat: (Date.now()/1000|0), exp: (Date.now()/1000|0) + 900});
  return `${h}.${p}.${crypto.createHmac('sha256', secret).update(`${h}.${p}`).digest('base64url')}`; };
const T = {[ME]: token(ME), [BOT]: token(BOT)};
const api = async (who, method, path, body) => { const r = await fetch(BASE + path, {method, headers: {Authorization: `Bearer ${T[who]}`, 'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
  let j = {}; try { j = await r.json(); } catch {} return {status: r.status, ...j}; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const out = {startedAt: new Date().toISOString(), steps: []}; const log = (k, v) => { out.steps.push({[k]: v}); console.log(k, JSON.stringify(v)); };

function stomp(who, onSignal) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS); const frame = (c, h) => `${c}\n${Object.entries(h).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n\0`;
    ws.addEventListener('open', () => ws.send(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '10000,10000', Authorization: `Bearer ${T[who]}`})));
    ws.addEventListener('message', (ev) => { for (const m of String(ev.data).split('\0').filter((p) => p.trim())) {
      if (m.startsWith('CONNECTED')) { ws.send(frame('SUBSCRIBE', {id: 'sub-0', destination: `/user/queue/courses/${COURSE}`})); resolve(ws); }
      else if (m.startsWith('MESSAGE')) onSignal(JSON.parse(m.slice(m.indexOf('\n\n') + 2)), Date.now());
      else if (m.startsWith('ERROR')) { console.error('STOMP ERROR', who, m.split('\n')[1]); reject(new Error(m)); } } });
    ws.addEventListener('error', () => reject(new Error('ws error')));
  });
}
async function measure(writer, reader, storeId, label) {
  const lat = [], versions = []; let seenSeq = 0;
  const sock = await stomp(reader, (sig, at) => { if (sig.type === 'DRAFT' && sig.memberId === writer && sig.content?.startsWith('probe ')) lat.push(at - Number(sig.content.slice(6))); if (sig.type === 'VERSION') versions.push(at); });
  await sleep(500); let seq = Date.now(), errors = 0;
  for (let i = 0; i < N; i++) { seq += 1; const r = await api(writer, 'PUT', `/courses/${COURSE}/stores/${storeId}/memo-draft`, {content: `probe ${Date.now()}`, seq}); if (r.status !== 200) errors++; seenSeq = r.data?.acceptedSeq; await sleep(1000); }
  await sleep(4000);
  const drafts = await api(reader, 'GET', `/courses/${COURSE}/drafts`);
  const detail = await api(reader, 'GET', `/courses/${COURSE}`);
  const stop = (detail.data?.courseStores || []).find((s) => s.storeId === storeId); const memo = stop?.memos?.find((m) => m.memberId === writer);
  sock.close(); lat.sort((a, b) => a - b); const q = (p) => lat[Math.min(lat.length - 1, Math.floor(p * lat.length))];
  log(label, {sent: N, errors, received: lat.length, p50: q(0.5), p95: q(0.95), max: lat[lat.length - 1], versionSignals: versions.length,
    readerDraftsSeesWriter: (drafts.data || []).some((d) => d.memberId === writer), dbMemoSeqMatchesLastSent: memo?.seq === seenSeq, dbMemoContent: memo?.content?.slice(0, 20)});
}
(async () => {
  log('members_before', (await api(ME, 'GET', `/courses/${COURSE}`)).data?.members?.map((m) => m.memberId));
  const inv = await api(ME, 'POST', `/courses/${COURSE}/invites`); log('invite', {status: inv.status, code: inv.code, hasToken: !!inv.data?.token});
  const acc = await api(BOT, 'POST', `/courses/invites/${inv.data?.token}/accept`, {}); log('accept_as_bot', {status: acc.status, code: acc.code, message: acc.message, data: acc.data});
  const d = await api(ME, 'GET', `/courses/${COURSE}`); const stores = d.data?.courseStores?.map((s) => s.storeId); log('members_after', {members: d.data?.members?.map((m) => m.memberId), draftEnabled: d.data?.draftEnabled, stores});
  if (!(d.data?.members || []).some((m) => m.memberId === BOT)) { console.error('봇이 멤버가 아님 — 중단'); fs.writeFileSync(`${S}/probe_out.json`, JSON.stringify(out, null, 1)); process.exit(2); }
  await measure(ME, BOT, stores[0], 'me_writes_bot_receives');
  await measure(BOT, ME, stores[1], 'bot_writes_me_receives');
  // 정리: 시험 메모 비우기 → 플러시 대기 → 봇 나가기
  const t = Date.now(); await api(ME, 'PUT', `/courses/${COURSE}/stores/${stores[0]}/memo-draft`, {content: '', seq: t}); await api(BOT, 'PUT', `/courses/${COURSE}/stores/${stores[1]}/memo-draft`, {content: '', seq: t});
  await sleep(5000); const left = await api(BOT, 'DELETE', `/courses/${COURSE}/members/me`); log('bot_leave', {status: left.status, code: left.code});
  const fin = await api(ME, 'GET', `/courses/${COURSE}`); log('final', {members: fin.data?.members?.map((m) => m.memberId), memosLeft: (fin.data?.courseStores || []).flatMap((s) => s.memos || []).length, redisDrafts: (await api(ME, 'GET', `/courses/${COURSE}/drafts`)).data?.length});
  fs.writeFileSync(`${S}/probe_out.json`, JSON.stringify(out, null, 1));
})().catch((e) => { console.error('실패', e.message); process.exit(1); });
