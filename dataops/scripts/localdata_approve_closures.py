#!/usr/bin/env python3
"""폐업 proposal 승인 러너 — store.status PENDING 을 서버 승인 경로로 실행.

서버 approve = apply_log 선기록 → ServingWriter.softDeleteStore → provenance.
이 스크립트는 로컬 dataops DB 에서 대상 id 만 읽고, 실행은 전부 서버 API 가 한다.

사용: python scripts/localdata_approve_closures.py [--server http://localhost:8090] [--max N]
"""
import argparse
import sys
import time

import psycopg2
import requests
from dataops_server import server_headers


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://localhost:8090")
    parser.add_argument("--max", type=int, default=None, dest="max_items")
    args = parser.parse_args()

    conn = psycopg2.connect(host="localhost", port=15433, dbname="dataops",
                            user="dataops", password="dataops")
    cur = conn.cursor()
    cur.execute("SELECT id, store_id FROM change_proposal "
                "WHERE target = 'store.status' AND status = 'PENDING' ORDER BY id")
    targets = cur.fetchall()
    conn.close()
    if args.max_items:
        targets = targets[:args.max_items]
    print(f"[approve] 대상 {len(targets)}", flush=True)

    approved = failed = 0
    failed_ids = []
    for i, (proposal_id, store_id) in enumerate(targets, 1):
        try:
            r = requests.patch(f"{args.server}/api/proposals/{proposal_id}/approve", headers=server_headers(), timeout=30)
            if r.ok:
                approved += 1
            else:
                failed += 1
                failed_ids.append(proposal_id)
                print(f"[approve] 실패 proposal {proposal_id} (store {store_id}) — "
                      f"{r.status_code} {r.text[:120]}", flush=True)
        except requests.RequestException as e:
            failed += 1
            failed_ids.append(proposal_id)
            print(f"[approve] 예외 proposal {proposal_id}: {e}", flush=True)
        if i % 100 == 0:
            print(f"[approve] 진행 {i}/{len(targets)} — 승인 {approved}, 실패 {failed}", flush=True)
        time.sleep(0.05)

    print(f"[approve] 종료 — 승인 {approved} / 실패 {failed} {failed_ids[:10]}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
