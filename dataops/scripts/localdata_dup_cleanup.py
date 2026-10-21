#!/usr/bin/env python3
"""중복 등재 정리 — 같은 정규화 상호 + 100m 이내 ACTIVE 쌍을 찾아 빈 쪽을 소프트삭제.

배경(2026-08-25 실측 5쌍): 관리자 수동 등록(placeId 없음)과 LOCALDATA 등록 배치가
하루 시간차로 경합 — placeId 선점 확인은 placeId 없는 행을 볼 수 없다.

안전 가드: 삭제 대상은 반드시 ①쌍 중 placeId 없는 쪽 ②메뉴·사진·영업시간·구독 전부 0
③상대편은 placeId 보유. 하나라도 어긋나면 사람행으로 보류(출력만).
삭제는 서버 승인 경로(change_proposal store.status → approve) — apply_log 자동 기록.

사용: python scripts/localdata_dup_cleanup.py [--server http://localhost:8091] [--execute]
"""
import argparse
import sys

import psycopg2
from psycopg2.extras import Json
import requests
from dataops_server import server_headers


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://localhost:8091")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()

    import re
    from pathlib import Path
    env = dict(re.findall(r"^([A-Z_]+)=(.*)$",
               (Path(__file__).resolve().parent.parent / ".env").read_text(encoding="utf-8"), re.M))
    prod = psycopg2.connect(host="localhost", port=15432, dbname="soboro",
                            user=env["PROD_DB_USERNAME"], password=env["PROD_DB_PASSWORD"],
                            connect_timeout=8, sslmode="require")
    pc = prod.cursor()
    pc.execute("""
        SELECT a.id, a.naver_place_id, b.id, b.naver_place_id, a.name
        FROM store a JOIN store b ON a.id < b.id
          AND a.name_normalized = b.name_normalized
          AND a.status = 'ACTIVE' AND b.status = 'ACTIVE'
          AND ST_DWithin(a.location, b.location, 100)""")
    pairs = pc.fetchall()
    print(f"중복 쌍 {len(pairs)}")

    def content(sid):
        pc.execute("""SELECT
            (SELECT count(*) FROM menu m WHERE m.store_id = %s AND m.deleted_at IS NULL),
            (SELECT count(*) FROM store_image_file f WHERE f.store_id = %s),
            (SELECT count(*) FROM store_business_hour h WHERE h.store_id = %s),
            (SELECT count(*) FROM subscribed_store ss WHERE ss.store_id = %s)""", (sid,) * 4)
        return pc.fetchone()

    local = psycopg2.connect(host="localhost", port=15433, dbname="dataops",
                             user="dataops", password="dataops")
    lc = local.cursor()

    deleted = held = 0
    for a_id, a_pid, b_id, b_pid, name in pairs:
        # 삭제 후보 = placeId 없는 쪽 (정확히 한쪽만 없어야 함)
        if bool(a_pid) == bool(b_pid):
            held += 1
            print(f"  보류(사람행) {name}: placeId 구도 모호 ({a_id}/{b_id})")
            continue
        victim, keeper = (a_id, b_id) if not a_pid else (b_id, a_id)
        c = content(victim)
        if any(c):
            held += 1
            print(f"  보류(사람행) {name}: 삭제 후보 {victim} 에 콘텐츠 있음 {c}")
            continue
        print(f"  정리 {name}: {victim} 삭제(빈 행) → {keeper} 유지", "[dry-run]" if not args.execute else "")
        if not args.execute:
            deleted += 1
            continue
        lc.execute("""
            INSERT INTO change_proposal (store_id, target, old_value, new_value, source, snapshot_id, status,
                                         producer, target_family, policy_version)
            SELECT %s, 'store.status', %s, %s, 'LOCALDATA', NULL, 'PENDING',
                       'LOCALDATA_STATUS', 'STORE_STATUS', 'localdata_dup_cleanup-2026-09-26'
            WHERE NOT EXISTS (SELECT 1 FROM change_proposal
                WHERE store_id = %s AND target = 'store.status' AND status IN ('PENDING','APPLYING'))""",
            (victim, Json("ACTIVE"),
             Json({"status": "DELETED", "reason": f"중복 등재 정리 — {keeper} 유지(관리자 수동 등록 경합)"}),
             victim))
        local.commit()
        lc.execute("SELECT id FROM change_proposal WHERE store_id = %s AND target='store.status' "
                   "AND status='PENDING' ORDER BY id DESC LIMIT 1", (victim,))
        row = lc.fetchone()
        if row:
            r = requests.patch(f"{args.server}/api/proposals/{row[0]}/approve", headers=server_headers(), timeout=30)
            if r.ok:
                deleted += 1
            else:
                held += 1
                print(f"  승인 실패 {victim}: {r.status_code}")
    print(f"{'정리' if args.execute else '정리 예정'} {deleted} / 보류 {held}")
    prod.close()
    local.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
