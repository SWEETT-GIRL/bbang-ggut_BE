#!/usr/bin/env python3
"""대량 STOMP 소켓. asyncio 로 N개를 단계적으로 열어 유지한다(앱과 같은 하트비트 25초). k6 는 VU 당 0.67MB 라 수만 개에 못 쓴다.
   python3 mass_sockets.py <host:port> <steps e.g. 5000,10000,20000,30000> <hold_s per step> <tokens.csv> <courses.csv>
   단계마다 한 줄: 목표, 열린 수, 실패, CONNECTED 까지 p50/p95/max(ms), 끊긴 수."""
import asyncio, base64, csv, os, sys, time
host, port = sys.argv[1].split(':'); port = int(port)
steps = [int(x) for x in sys.argv[2].split(',')]; HOLD = int(sys.argv[3])
tokens = {int(r[0]): r[1] for r in csv.reader(open(sys.argv[4])) if r}
courses = [(int(r[0]), [int(m) for m in r[2].split('|')]) for r in csv.reader(open(sys.argv[5])) if r]
pairs = [(cid, m) for cid, ms in courses for m in ms]            # (코스, 회원) 12,000쌍을 돌려 쓴다
def frame(cmd, h): return (cmd + '\n' + '\n'.join(f'{k}:{v}' for k, v in h.items()) + '\n\n\0').encode()
def ws_text(payload):
    mask = os.urandom(4); n = len(payload); data = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
    head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, 'big'))
    return head + mask + data
HB = ws_text(b'\n')
stats = {'open': 0, 'failed': 0, 'dropped': 0, 'msgs': 0}; lat = []
async def one(i, sem):
    cid, member = pairs[i % len(pairs)]; t0 = time.time()
    try:
        async with sem:
            r, w = await asyncio.wait_for(asyncio.open_connection(host, port), 15)
            key = base64.b64encode(os.urandom(16)).decode()
            w.write(f'GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
            head = await asyncio.wait_for(r.readuntil(b'\r\n\r\n'), 15)
            if b' 101 ' not in head: raise RuntimeError('no 101')
            w.write(ws_text(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '25000,25000', 'Authorization': f'Bearer {tokens[member]}'})))
            data = await asyncio.wait_for(r.read(4096), 15)
            if b'CONNECTED' not in data: raise RuntimeError('no CONNECTED')
            w.write(ws_text(frame('SUBSCRIBE', {'id': 'sub-0', 'destination': f'/user/queue/courses/{cid}'}))); await w.drain()
        lat.append((time.time() - t0) * 1000); stats['open'] += 1
    except Exception:
        stats['failed'] += 1; return
    last = time.time()
    try:
        while True:
            try:
                d = await asyncio.wait_for(r.read(4096), 20)
                if not d: break
                stats['msgs'] += 1
            except asyncio.TimeoutError:
                pass
            if time.time() - last > 20: w.write(HB); last = time.time()
    except Exception:
        pass
    stats['open'] -= 1; stats['dropped'] += 1
async def main():
    sem = asyncio.Semaphore(400); tasks = []; n = 0
    for target in steps:
        t = time.time(); lat.clear(); f0 = stats['failed']
        while n < target: tasks.append(asyncio.create_task(one(n, sem))); n += 1
        while stats['open'] + (stats['failed'] - f0) + stats['dropped'] < target and time.time() - t < 120: await asyncio.sleep(0.5)
        ramp = time.time() - t; l = sorted(lat); q = lambda p: round(l[min(len(l) - 1, int(p * len(l)))]) if l else None
        print(f'target={target} open={stats["open"]} failed={stats["failed"]} dropped={stats["dropped"]} ramp_s={ramp:.1f} connect_ms p50={q(0.5)} p95={q(0.95)} max={q(1.0)}', flush=True)
        await asyncio.sleep(HOLD)
        print(f'  hold {HOLD}s → open={stats["open"]} dropped={stats["dropped"]} server_frames={stats["msgs"]}', flush=True)
    for t in tasks: t.cancel()
asyncio.run(main())
