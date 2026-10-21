#!/usr/bin/env python3
"""역방향 탐색 후보 → 로컬 new_store_candidate 수납 (운영 DB 무접촉).

naver_sweep_match 가 만든 naver_sweep_new_candidates.tsv 를 후보표에 넣는다.
상태는 **MATCHED** 다 — 탐색이 이미 naver_place_id 를 알아냈으므로 네이버 검색 단계(WAITING_NAVER)를
건너뛴다. 여기서부터는 기존 파이프라인과 같은 길을 간다: localdata_register_worker 가 MATCHED 를 집어
플레이스를 fetch 하고 서버 등록 API 로 운영에 편입한다(운영 쓰기는 전부 ServingWriter 경유).

G1 관문을 여기서 다시 돌리지 않는 이유: 대조 대상이 카테고리 잎이 확실한 빵집(BAKERY_LEAVES)과
허용 잎(아이스크림·빙수)뿐이라 bakery_gate.category_pass 가 메뉴 없이 통과시키는 집합이다.
프랜차이즈도 naver_sweep_match 가 이미 뺐다.

멱등: mgmt_no UNIQUE ON CONFLICT DO NOTHING.

사용: python scripts/naver_sweep_enqueue.py            # dry-run (건수만)
      python scripts/naver_sweep_enqueue.py --execute
"""
import argparse
import csv
import io
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import local_db  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "matching" / "naver_sweep_new_candidates.tsv"


def ymd(value):
    """인허가일은 '2026-06-25' 로도 '20260625' 로도 온다 — date 로 넣을 문자열을 만든다"""
    digits = "".join(c for c in (value or "") if c.isdigit())[:8]
    return f"{digits[:4]}-{digits[4:6]}-{digits[6:8]}" if len(digits) == 8 else None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--execute", action="store_true")
    args = ap.parse_args()

    rows = list(csv.DictReader(io.open(SRC, encoding="utf-8"), delimiter="\t"))
    print(f"수납 대상 {len(rows):,}곳 ({SRC.name}) — 로컬 {local_db.describe()}")

    conn = local_db.connect()
    cur = conn.cursor()
    cur.execute("SELECT mgmt_no FROM new_store_candidate WHERE mgmt_no = ANY(%s)",
                (sorted({r["mgmt_no"] for r in rows}),))
    existing = {m for (m,) in cur.fetchall()}
    fresh = [r for r in rows if r["mgmt_no"] not in existing]
    print(f"  이미 후보표에 있는 인허가 {len(existing):,}개 → 새로 넣을 {len(fresh):,}곳")
    if not args.execute:
        print("dry-run — --execute 로 실제 수납")
        for r in fresh[:5]:
            print(f"    {r['mgmt_no']:<24} {r['licenseName'][:20]:<22} {ymd(r['licensed_ymd'])}  place {r['placeId']}")
        cur.close()
        conn.close()
        return 0

    n = 0
    for r in fresh:
        cur.execute("""
            INSERT INTO new_store_candidate (mgmt_no, name, road_address, category_code,
                                             licensed_at, status, naver_place_id)
            VALUES (%s, %s, %s, %s, %s, 'MATCHED', %s)
            ON CONFLICT (mgmt_no) DO NOTHING""",
                    (r["mgmt_no"], r["licenseName"][:100], (r["address"] or "")[:200] or None,
                     r["dataset"][:40], ymd(r["licensed_ymd"]), r["placeId"]))
        n += cur.rowcount
    conn.commit()
    cur.execute("SELECT status, count(*) FROM new_store_candidate GROUP BY 1 ORDER BY 2 DESC")
    print(f"수납 {n:,}곳 — 후보표 현황: " + " · ".join(f"{s} {c:,}" for s, c in cur.fetchall()))
    cur.close()
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
