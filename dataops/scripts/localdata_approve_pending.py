#!/usr/bin/env python3
"""신규 가게발 PENDING 일괄 승인 — 검수 게이트 통과 후 menu.add·instagram 승인.

전제(2026-08-25 검수): 재판정으로 현행 필터 반영(용량 649→0) → 자동 스캔 정크 230 거절
(음료 91·0원 71·옵션 26·기타) → 표본 15곳 눈검수 구성 정상. menu.removed 는 소멸 검수
정책이 별도라 승인 대상에서 제외한다.

사용: python scripts/localdata_approve_pending.py [--server http://localhost:8091] [--max N]
"""
import argparse
import sys
import time

import psycopg2
import requests
from dataops_server import server_headers


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://localhost:8091")
    parser.add_argument("--max", type=int, default=None, dest="max_items")
    args = parser.parse_args()

    conn = psycopg2.connect(host="localhost", port=15433, dbname="dataops",
                            user="dataops", password="dataops")
    cur = conn.cursor()
    cur.execute("""SELECT id FROM change_proposal WHERE status = 'PENDING'
                   AND (target LIKE 'menu.add.%' OR target = 'store.instagram_handle')
                   ORDER BY id""")
    ids = [r[0] for r in cur.fetchall()]
    conn.close()
    if args.max_items:
        ids = ids[:args.max_items]
    print(f"[approve] 대상 {len(ids)}", flush=True)

    approved = failed = 0
    for i, pid in enumerate(ids, 1):
        try:
            r = requests.patch(f"{args.server}/api/proposals/{pid}/approve", headers=server_headers(), timeout=30)
            if r.ok:
                approved += 1
            else:
                failed += 1
                if failed <= 20:
                    print(f"[approve] 실패 {pid} — {r.status_code} {r.text[:100]}", flush=True)
        except requests.RequestException as e:
            failed += 1
            print(f"[approve] 예외 {pid}: {e}", flush=True)
        if i % 2000 == 0:
            print(f"[approve] 진행 {i}/{len(ids)} — 승인 {approved}, 실패 {failed}", flush=True)
        time.sleep(0.02)

    print(f"[approve] 종료 — 승인 {approved} / 실패 {failed}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
