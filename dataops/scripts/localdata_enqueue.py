#!/usr/bin/env python3
"""LOCALDATA 확정 리스트 수납 — 로컬 dataops DB에 proposal·candidate 적재.

운영 DB 무접촉: 쓰는 곳은 로컬 dataops DB(localhost:15433)뿐이다.
실제 운영 반영은 이후 승인 단계(서버 approve → apply_log → ServingWriter)가 수행.

  폐업 593  → change_proposal(target='store.status', source=LOCALDATA, PENDING)
              승인 시 ServingWriter.softDeleteStore 실행 (서버 라우팅 구현돼 있음)
  신규 7,140 → new_store_candidate(status='MATCHED', naver_place_id 포함)
              — 설계 §3.6 상태 어휘(WAITING_NAVER|MATCHED|REGISTERED|NOT_FOUND|REJECTED).
              REGISTERED 전환(운영 store INSERT)은 4단계 구현 대상

멱등: 같은 (store_id, store.status) PENDING/APPROVED 존재 시 스킵,
candidate 는 mgmt_no UNIQUE ON CONFLICT DO NOTHING.

사용: python scripts/localdata_enqueue.py           # dry-run (건수만)
      python scripts/localdata_enqueue.py --execute
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import psycopg2
from psycopg2.extras import Json

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_db  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
M = ROOT / "matching"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()

    closure = list(csv.DictReader(open(M / "localdata_closure_confirmed.tsv", encoding="utf-8"), delimiter="\t"))
    new_rows = list(csv.DictReader(open(M / "localdata_new_confirmed.tsv", encoding="utf-8"), delimiter="\t"))
    overlap = {r["mgmt_no"] for r in csv.DictReader(open(M / "new_confirmed_overlap.tsv", encoding="utf-8"), delimiter="\t")}
    new_rows = [r for r in new_rows if r["mgmt_no"] not in overlap]
    print(f"수납 대상: 폐업 proposal {len(closure)} / 신규 candidate {len(new_rows)} (중복 {len(overlap)} 제외)")
    if not args.execute:
        print("dry-run — --execute 로 실제 수납")
        return

    conn = local_db.connect()
    cur = conn.cursor()

    n_prop = n_skip = 0
    for r in closure:
        cur.execute("""
            INSERT INTO change_proposal (store_id, target, old_value, new_value, source, snapshot_id, status,
                                         producer, target_family, policy_version)
            SELECT %s, 'store.status', %s, %s, 'LOCALDATA', NULL, 'PENDING',
                       'LOCALDATA_STATUS', 'STORE_STATUS', 'localdata_enqueue-2026-09-26'
            WHERE NOT EXISTS (
                SELECT 1 FROM change_proposal
                WHERE store_id = %s AND target = 'store.status' AND status IN ('PENDING', 'APPLYING', 'APPROVED')
            )""", (
            int(r["store_id"]), Json("ACTIVE"),
            Json({"status": "DELETED", "evidence": f"{r['path']}:{r['evidence']}",
                  "mgmtNo": r["mgmt_no"] or None, "closedYmd": r["closed_ymd"] or None,
                  "confidence": r["confidence"]}),
            int(r["store_id"]),
        ))
        n_prop += cur.rowcount
        n_skip += 1 - cur.rowcount

    n_cand = 0
    for r in new_rows:
        licensed = r["licensed_ymd"]
        licensed_at = f"{licensed[:4]}-{licensed[4:6]}-{licensed[6:8]}" if len(licensed) == 8 else None
        cur.execute("""
            INSERT INTO new_store_candidate (mgmt_no, name, road_address, category_code,
                                             licensed_at, status, naver_place_id)
            VALUES (%s, %s, %s, %s, %s, 'MATCHED', %s)
            ON CONFLICT (mgmt_no) DO NOTHING""", (
            r["mgmt_no"], r["lic_name"][:100], r["road_address"][:200] or None,
            r["dataset"][:40], licensed_at, r["naver_place_id"],
        ))
        n_cand += cur.rowcount

    conn.commit()
    cur.execute("select count(*) from change_proposal where target='store.status' and status='PENDING'")
    pending = cur.fetchone()[0]
    cur.execute("select status, count(*) from new_store_candidate group by 1")
    cand_dist = cur.fetchall()
    conn.close()
    print(f"proposal 신규 수납 {n_prop} (기존 존재 스킵 {n_skip}) → store.status PENDING 총 {pending}")
    print(f"candidate 수납 {n_cand} → 상태 분포 {cand_dist}")


if __name__ == "__main__":
    main()
