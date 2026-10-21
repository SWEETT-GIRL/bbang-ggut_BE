#!/usr/bin/env python3
"""운영 DB 대조 검증 (SELECT 전용) — 확정 리스트를 현재 DB 상태와 대조.

전제: 사람이 SSH 터널(localhost:15432 → RDS soboro)을 열어둔 상태.
계정은 dataops_reader(읽기 전용) — 이 스크립트는 어떤 쓰기도 하지 않는다.

검증 항목:
  1. 폐업 확정 593곳 — 현재도 ACTIVE인가? (다른 트랙이 이미 닫았으면 수납 불요)
  2. 신규 확정 7,378곳 — 파일럿 임포트(2026-05) 이후 DB에 추가된 가게와 중복인가?
     (상호 정규화 유사 + 좌표 근접, place_id 컬럼이 있으면 직접 조인도)

출력:
  matching/closure_db_status.tsv     폐업 리스트 × 현재 DB 상태
  matching/new_confirmed_overlap.tsv 신규 리스트 중 DB 기존/신규 가게와 중복 의심
"""
import csv
import math
import re
import sys
from pathlib import Path

import psycopg2

sys.path.insert(0, str(Path(__file__).resolve().parent))
from localdata_match import haversine_m, name_sim, norm_name

ROOT = Path(__file__).resolve().parent.parent


def load_env() -> dict:
    env = {}
    for line in open(ROOT / ".env", encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env


def main():
    env = load_env()
    conn = psycopg2.connect(host="localhost", port=15432, dbname="soboro",
                            user=env["PROD_DB_USERNAME"], password=env["PROD_DB_PASSWORD"],
                            connect_timeout=8, sslmode="require")  # RDS force_ssl
    conn.set_session(readonly=True)
    cur = conn.cursor()

    cur.execute("""select column_name from information_schema.columns
                   where table_schema='public' and table_name='store'""")
    cols = {r[0] for r in cur.fetchall()}
    print("store 컬럼:", sorted(cols))
    status_col = "status" if "status" in cols else None
    place_col = next((c for c in ("place_id", "naver_place_id") if c in cols), None)

    # 1) 폐업 확정 리스트 현재 상태
    closure = list(csv.DictReader(open(ROOT / "matching" / "localdata_closure_confirmed.tsv",
                                       encoding="utf-8"), delimiter="\t"))
    ids = [int(r["store_id"]) for r in closure]
    cur.execute(f"select id, name, {status_col} from store where id = any(%s)", (ids,))
    db = {r[0]: r for r in cur.fetchall()}
    from collections import Counter
    dist = Counter()
    with open(ROOT / "matching" / "closure_db_status.tsv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(["store_id", "store_name", "db_status", "closure_evidence"])
        for r in closure:
            row = db.get(int(r["store_id"]))
            st = row[2] if row else "(행 없음)"
            dist[st] += 1
            w.writerow([r["store_id"], r["store_name"], st, r["path"]])
    print("폐업 593 현재 상태:", dict(dist))

    # 2) 신규 확정 리스트 vs 현재 ACTIVE 가게 (임포트 이후 추가분 포함 전체와 대조)
    where = f"where {status_col}='ACTIVE'" if status_col else ""
    loc = ("ST_Y(location::geometry) as lat, ST_X(location::geometry) as lon"
           if "location" in cols else "null, null")
    cur.execute(f"select id, name, {loc}{', ' + place_col if place_col else ''} from store {where}")
    stores = cur.fetchall()
    print(f"현재 ACTIVE 가게: {len(stores):,}")

    GRID = 0.005
    cell = {}
    for s in stores:
        if s[2] is None:
            continue
        cell.setdefault((int(s[2] / GRID), int(s[3] / GRID)), []).append(s)
    place_ids = {str(s[4]) for s in stores if place_col and s[4]} if place_col else set()

    new = list(csv.DictReader(open(ROOT / "matching" / "localdata_new_confirmed.tsv",
                                   encoding="utf-8"), delimiter="\t"))
    overlaps = []
    for r in new:
        hit = None
        if place_ids and r["naver_place_id"] in place_ids:
            hit = ("placeId 일치", "", "")
        # 좌표가 확정 TSV에 없으므로 상호 정규화 일치로만 보조 스크린 (좌표는 후보 원본에 있음)
        overlaps.append((r, hit))

    # 좌표 포함 근접 대조: 후보 원본에서 좌표 조인
    coords = {}
    for row in csv.DictReader(open(ROOT / "matching" / "localdata_new_candidates.tsv",
                                   encoding="utf-8"), delimiter="\t"):
        if row["lat"]:
            coords[row["mgmt_no"]] = (float(row["lat"]), float(row["lon"]))
    n_place = n_near = 0
    with open(ROOT / "matching" / "new_confirmed_overlap.tsv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(["mgmt_no", "lic_name", "naver_place_id", "overlap_type", "db_store_id", "db_name"])
        for r, hit in overlaps:
            if hit:
                n_place += 1
                w.writerow([r["mgmt_no"], r["lic_name"], r["naver_place_id"], "placeId", "", ""])
                continue
            ll = coords.get(r["mgmt_no"])
            if not ll:
                continue
            ci, cj = int(ll[0] / GRID), int(ll[1] / GRID)
            nn = norm_name(r["naver_name"] or r["lic_name"])
            for di in (-1, 0, 1):
                for dj in (-1, 0, 1):
                    for s in cell.get((ci + di, cj + dj), []):
                        d = haversine_m(ll[0], ll[1], s[2], s[3])
                        if d <= 150 and name_sim(nn, norm_name(s[1])) >= 0.75:
                            n_near += 1
                            w.writerow([r["mgmt_no"], r["lic_name"], r["naver_place_id"],
                                        f"근접+상호({round(d)}m)", s[0], s[1]])
                            break
                    else:
                        continue
                    break
                else:
                    continue
                break
    print(f"신규 {len(new):,} 중 중복 의심 — placeId 일치 {n_place} / 근접+상호 {n_near}")
    conn.close()


if __name__ == "__main__":
    main()
