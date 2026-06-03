#!/usr/bin/env python3
"""동(region_3rd) 가게 read-only 스냅샷 export.

운영DB(soboro)에서 region_3rd=<동> 가게만 SELECT 하여 CSV/JSON 스냅샷으로 저장한다.
- READ-ONLY 세션. 운영DB에 쓰기 일절 없음.
- 이후 블로그 점수 파이프라인(별도 로컬 PostgreSQL)이 이 스냅샷을 store_snapshot으로 적재한다.

사용:
    .venv/bin/python -m blog_score.export_mangwon_snapshot [--region 공덕동]
"""
import argparse
import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import psycopg2

# 기존 스크립트의 DSN 파서 재사용
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from blog_score.regions import slug_for  # noqa: E402
from scan_placeid_mismatch import db_config  # noqa: E402

OUT_DIR = Path(__file__).resolve().parent / "snapshots"

QUERY = """
    SELECT
        id,
        name,
        address,
        region_1st,
        region_2nd,
        region_3rd,
        ST_Y(location::geometry) AS lat,
        ST_X(location::geometry) AS lng,
        kakao_place_id,
        naver_place_id,
        status,
        source
    FROM store
    WHERE region_3rd = %s
      AND status = 'ACTIVE'
    ORDER BY id
"""

COLUMNS = [
    "store_id", "name", "address", "region_1st", "region_2nd", "region_3rd",
    "lat", "lng", "kakao_place_id", "naver_place_id", "status", "source",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--region", default="망원동", help="region_3rd 동명")
    args = ap.parse_args()
    region = args.region
    slug = slug_for(region)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    conn = psycopg2.connect(**db_config(), connect_timeout=20)
    conn.set_session(readonly=True, autocommit=True)  # 운영DB 무손상 보장
    try:
        cur = conn.cursor()
        cur.execute(QUERY, (region,))
        rows = cur.fetchall()
    finally:
        conn.close()

    snapshot_at = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    records = [dict(zip(COLUMNS, r)) for r in rows]

    csv_path = OUT_DIR / f"{slug}_stores_{snapshot_at}.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(records)

    json_path = OUT_DIR / f"{slug}_stores_{snapshot_at}.json"
    with json_path.open("w", encoding="utf-8") as f:
        json.dump(
            {"region_3rd": region, "snapshot_at": snapshot_at,
             "count": len(records), "stores": records},
            f, ensure_ascii=False, indent=2, default=str,
        )

    with_geo = sum(1 for r in records if r["lat"] is not None)
    with_naver = sum(1 for r in records if r["naver_place_id"])
    print(f"export 완료: {len(records)}곳")
    print(f"  좌표 보유: {with_geo} / naver_place_id 보유: {with_naver}")
    print(f"  CSV : {csv_path}")
    print(f"  JSON: {json_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
