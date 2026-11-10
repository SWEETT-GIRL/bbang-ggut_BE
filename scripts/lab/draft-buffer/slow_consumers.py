#!/usr/bin/env python3
"""W3 — 느린 소비자. 소켓 N개를 열어 STOMP CONNECT·SUBSCRIBE 만 하고 다시는 읽지 않는다(수신 버퍼가 차면 서버 쪽 전송이 막힌다).
   python3 slow_consumers.py <host:port> <N> <seconds> <tokens.csv> <courses.csv>   — 코스 0..N-1 의 첫 멤버로 하나씩 구독."""
import base64, os, socket, sys, time, csv
host, port = sys.argv[1].split(':'); N = int(sys.argv[2]); SEC = int(sys.argv[3])
tokens = {int(r[0]): r[1] for r in csv.reader(open(sys.argv[4])) if r}
courses = [(int(r[0]), r[2].split('|')) for r in csv.reader(open(sys.argv[5])) if r]
def frame(cmd, h): return (cmd + '\n' + '\n'.join(f'{k}:{v}' for k, v in h.items()) + '\n\n\0').encode()
def ws_text(payload):  # 클라이언트 → 서버는 마스킹 필수
    mask = os.urandom(4); data = bytes(b ^ mask[i % 4] for i, b in enumerate(payload)); n = len(payload)
    head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, 'big'))
    return head + mask + data
socks = []
for i in range(N):
    cid, members = courses[i % len(courses)]; tok = tokens[int(members[0])]
    s = socket.create_connection((host, int(port)), timeout=5); s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(f'GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
    resp = s.recv(1024)
    if b' 101 ' not in resp: print('handshake failed', resp[:60]); continue
    s.sendall(ws_text(frame('CONNECT', {'accept-version': '1.2', 'heart-beat': '0,0', 'Authorization': f'Bearer {tok}'})))
    time.sleep(0.05); s.recv(2048)  # CONNECTED 한 번만 읽고
    s.sendall(ws_text(frame('SUBSCRIBE', {'id': 'sub-0', 'destination': f'/user/queue/courses/{cid}'})))
    socks.append(s)  # 이후 절대 읽지 않는다
print(f'slow consumers open: {len(socks)} (courses 0..{N-1}), holding {SEC}s — 읽지 않음', flush=True)
time.sleep(SEC)
closed = 0
for s in socks:
    try: s.close(); closed += 1
    except Exception: pass
print('closed', closed)
