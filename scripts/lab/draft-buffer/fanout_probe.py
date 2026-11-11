#!/usr/bin/env python3
"""신호 전달 지연. STOMP 소켓 N개로 코스를 구독해 두고, DRAFT 신호의 content("d<회원> <보낸 시각 ms> <난수>")에서 보낸 시각을 꺼내
   받은 시각과의 차를 잰다. 쓰는 쪽(k6)과 같은 서버에서 돌려 시계 차를 없앤다.
   python3 fanout_probe.py <host:port> <소켓 수> <시작 번호> <초> <tokens.csv> <courses.csv> [라벨]
   10초마다 한 줄: 시각, 열린 소켓, 받은 신호/초, 지연 p50/p95/p99/max(ms), 끊긴 수."""
import asyncio, base64, csv, os, re, sys, time
host, port = sys.argv[1].split(':'); port = int(port)
N, OFFSET, DURATION = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]); LABEL = sys.argv[7] if len(sys.argv) > 7 else 'probe'
tokens = {int(r[0]): r[1] for r in csv.reader(open(sys.argv[5])) if r}
courses = [(int(r[0]), [int(m) for m in r[2].split('|')]) for r in csv.reader(open(sys.argv[6])) if r]
pairs = [(cid, m) for cid, ms in courses for m in ms]
# HOT_COURSE=<courses.csv 의 몇 번째 줄(0부터)> 이면 소켓 전부가 그 코스 하나를 구독한다(인기 코스 쏠림)
if os.environ.get('HOT_COURSE'):
    hot = courses[int(os.environ['HOT_COURSE'])]; pairs = [(hot[0], m) for m in hot[1]]
SENT = re.compile(rb'"content":"d\d+ (\d{13}) ')
def frame(cmd, h): return (cmd + '\n' + '\n'.join(f'{k}:{v}' for k, v in h.items()) + '\n\n\0').encode()
def ws_text(payload):
    mask = os.urandom(4); n = len(payload); data = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
    head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, 'big'))
    return head + mask + data
HB = ws_text(b'\n')
stats = {'open': 0, 'failed': 0, 'dropped': 0}; lat = []
async def one(i, sem):
    cid, member = pairs[i % len(pairs)]
    try:
        async with sem:
            r, w = await asyncio.wait_for(asyncio.open_connection(host, port, limit=1 << 20), 20)
            key = base64.b64encode(os.urandom(16)).decode()
            w.write(f'GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
            head = await asyncio.wait_for(r.readuntil(b'\r\n\r\n'), 20)
            if b' 101 ' not in head: raise RuntimeError('no 101')
            w.write(ws_text(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '25000,25000', 'Authorization': f'Bearer {tokens[member]}'})))
            data = await asyncio.wait_for(r.read(4096), 20)
            if b'CONNECTED' not in data: raise RuntimeError('no CONNECTED')
            w.write(ws_text(frame('SUBSCRIBE', {'id': 'sub-0', 'destination': f'/user/queue/courses/{cid}'}))); await w.drain()
        stats['open'] += 1
    except Exception:
        stats['failed'] += 1; return
    last = time.time(); tail = b''
    try:
        while True:
            try:
                d = await asyncio.wait_for(r.read(65536), 20)
                if not d: break
                now = time.time() * 1000; buf = tail + d; end = 0
                for m in SENT.finditer(buf):
                    lat.append(now - int(m.group(1))); end = m.end()
                tail = buf[end:][-64:]
            except asyncio.TimeoutError:
                pass
            if time.time() - last > 20: w.write(HB); last = time.time()
    except Exception:
        pass
    stats['open'] -= 1; stats['dropped'] += 1
async def main():
    sem = asyncio.Semaphore(300); tasks = [asyncio.create_task(one(OFFSET + i, sem)) for i in range(N)]
    t0 = time.time()
    while stats['open'] + stats['failed'] + stats['dropped'] < N and time.time() - t0 < 120: await asyncio.sleep(0.5)
    print(f'{LABEL} READY open={stats["open"]} failed={stats["failed"]} in {time.time() - t0:.0f}s', flush=True)
    end = time.time() + DURATION
    while time.time() < end:
        await asyncio.sleep(10); l = sorted(lat); lat.clear()
        q = lambda p: round(l[min(len(l) - 1, int(p * len(l)))]) if l else -1
        print(f'{LABEL} {time.strftime("%H:%M:%S")} open={stats["open"]} recv/s={len(l) / 10:.0f} p50={q(0.5)} p95={q(0.95)} p99={q(0.99)} max={q(1.0)} dropped={stats["dropped"]}', flush=True)
    for t in tasks: t.cancel()
asyncio.run(main())
