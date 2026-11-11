#!/usr/bin/env python3
"""재접속 폭주. STOMP 소켓 N개를 붙여 두고, 끊기면 (BASE + 0~JITTER)초 뒤 다시 붙는다(실패하면 같은 규칙으로 계속).
   서버를 죽였다 살릴 때 수만 개가 한꺼번에 돌아오는 모양을 본다.
   python3 storm_sockets.py <host:port> <소켓 수> <초> <BASE ms> <JITTER ms> <tokens.csv> <courses.csv>
   2초마다 한 줄: 시각(epoch ms), 붙은 소켓 수, 그 2초의 접속 시도 · 성공 · 실패. 끝에 재접속 소요(끊긴 순간 → 다시 CONNECTED) 분포."""
import asyncio, base64, csv, os, random, sys, time
host, port = sys.argv[1].split(':'); port = int(port)
N, DURATION, BASE, JITTER = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]) / 1000, int(sys.argv[5]) / 1000
tokens = {int(r[0]): r[1] for r in csv.reader(open(sys.argv[6])) if r}
courses = [(int(r[0]), [int(m) for m in r[2].split('|')]) for r in csv.reader(open(sys.argv[7])) if r]
pairs = [(cid, m) for cid, ms in courses for m in ms]
def frame(cmd, h): return (cmd + '\n' + '\n'.join(f'{k}:{v}' for k, v in h.items()) + '\n\n\0').encode()
def ws_text(payload):
    mask = os.urandom(4); n = len(payload); data = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
    head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, 'big'))
    return head + mask + data
HB = ws_text(b'\n')
st = {'open': 0, 'try': 0, 'ok': 0, 'fail': 0}; recon = []; END = time.time() + DURATION
async def connect(i):
    cid, member = pairs[i % len(pairs)]
    r, w = await asyncio.wait_for(asyncio.open_connection(host, port), 10)
    try:
        key = base64.b64encode(os.urandom(16)).decode()
        w.write(f'GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
        head = await asyncio.wait_for(r.readuntil(b'\r\n\r\n'), 10)
        if b' 101 ' not in head: raise RuntimeError('no 101')
        w.write(ws_text(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '25000,25000', 'Authorization': f'Bearer {tokens[member]}'})))
        data = await asyncio.wait_for(r.read(4096), 10)
        if b'CONNECTED' not in data: raise RuntimeError('no CONNECTED')
        w.write(ws_text(frame('SUBSCRIBE', {'id': 'sub-0', 'destination': f'/user/queue/courses/{cid}'}))); await w.drain()
        return r, w
    except Exception:
        w.close(); raise
async def one(i, gate):
    dropped_at = None
    while time.time() < END:
        try:
            async with gate:
                st['try'] += 1; r, w = await connect(i)
        except Exception:
            st['fail'] += 1; await asyncio.sleep(BASE + random.random() * JITTER); continue
        st['ok'] += 1; st['open'] += 1
        if dropped_at is not None: recon.append(time.time() - dropped_at)
        last = time.time()
        try:
            while time.time() < END:
                try:
                    d = await asyncio.wait_for(r.read(4096), 10)
                    if not d: break
                except asyncio.TimeoutError:
                    pass
                if time.time() - last > 20: w.write(HB); await w.drain(); last = time.time()
        except Exception:
            pass
        st['open'] -= 1; dropped_at = time.time()
        try: w.close()
        except Exception: pass
        await asyncio.sleep(BASE + random.random() * JITTER)
async def main():
    gate = asyncio.Semaphore(int(os.environ.get('CONCURRENT_CONNECTS', '2000')))
    tasks = [asyncio.create_task(one(i, gate)) for i in range(N)]
    prev = dict(st)
    while time.time() < END:
        await asyncio.sleep(2)
        print(f'{int(time.time() * 1000)} open={st["open"]} try={st["try"] - prev["try"]} ok={st["ok"] - prev["ok"]} fail={st["fail"] - prev["fail"]}', flush=True); prev = dict(st)
    l = sorted(recon); q = lambda p: round(l[min(len(l) - 1, int(p * len(l)))], 1) if l else None
    print(f'RECONNECT n={len(l)} p50={q(0.5)} p95={q(0.95)} p99={q(0.99)} max={q(1.0)} (초, 끊긴 순간 → 다시 붙음)', flush=True)
    for t in tasks: t.cancel()
asyncio.run(main())
