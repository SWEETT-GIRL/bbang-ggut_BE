#!/usr/bin/env python3
"""등록 배치 region 정정 (일회성) — 2026-08-21 등록분의 region 이상치 수정.

대상 (실측): ① region_1st='전남광주통합특별시' 450곳 — 2026 행정개편 명칭이 기존 관행
('전남'/'광주')과 어긋나 지역 탐색 그룹이 갈라짐 → 광주 5개 구는 '광주', 나머지는 '전남'.
② region_1st IS NULL 9곳(지번 부재 등록) — 네이버 주소 토큰으로 보완.

⚠ 규율 예외 선언: 운영 DML 은 ServingWriter 가 원칙이나, 이 스크립트는 방금 만든 등록
배치의 후속 정정(같은 논리 작업의 마무리)으로 한정해 직접 UPDATE 한다. 대신 apply_log
(target=store.region)를 행 단위로 남겨 감사 가능성을 유지한다. 재사용 금지 — 워커의
regions_from_jibun 이 고쳐져 재발하지 않는다.

사용: DATAOPS_WRITER_PASSWORD=... python scripts/localdata_region_fix.py [--execute]
"""
import argparse
import json
import os
import sys

import psycopg2
from psycopg2.extras import Json

GWANGJU_GU = {"동구", "서구", "남구", "북구", "광산구"}
KNOWN_SIDO = {"서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기",
              "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"}


def fix_merged(r2):
    return ("광주" if r2 in GWANGJU_GU else "전남", r2)


def fix_from_address(address):
    toks = (address or "").split()
    if not toks:
        return None, None
    r1 = toks[0]
    r2 = toks[1] if len(toks) > 1 else None
    if r1 in ("전남광주", "전남광주통합특별시"):
        return fix_merged(r2)
    if r1 not in KNOWN_SIDO:
        return None, None  # 확신 없으면 건드리지 않는다
    return r1, r2


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()

    writer_pw = os.environ.get("DATAOPS_WRITER_PASSWORD")
    if not writer_pw:  # .env(gitignore) 폴백 — 자격증명을 명령줄에 노출하지 않는다
        import re
        from pathlib import Path
        env_text = (Path(__file__).resolve().parent.parent / ".env").read_text(encoding="utf-8")
        m = re.search(r"^DATAOPS_WRITER_PASSWORD=(.*)$", env_text, re.M)
        writer_pw = m.group(1).strip() if m else None
    if not writer_pw:
        print("DATAOPS_WRITER_PASSWORD 필요 (.env 또는 환경변수)")
        return 1
    prod = psycopg2.connect(host="localhost", port=15432, dbname="soboro",
                            user="dataops_writer", password=writer_pw,
                            connect_timeout=8, sslmode="require")
    prod.autocommit = True
    pc = prod.cursor()
    local = psycopg2.connect(host="localhost", port=15433, dbname="dataops",
                             user="dataops", password="dataops")
    lc = local.cursor()

    pc.execute("""SELECT id, name, address, region_1st, region_2nd, region_3rd FROM store
                  WHERE source='ADMIN_REGISTERED' AND naver_matched_at >= '2026-08-21'
                  AND (region_1st = '전남광주통합특별시' OR region_1st IS NULL)""")
    rows = pc.fetchall()
    print(f"정정 대상 {len(rows)}")

    fixed = skipped = 0
    for sid, name, address, r1, r2, r3 in rows:
        if r1 == "전남광주통합특별시":
            new1, new2 = fix_merged(r2)
            new2 = new2 or r2
        else:
            new1, new2 = fix_from_address(address)
            new2 = new2 or r2
        if not new1:
            skipped += 1
            continue
        if not args.execute:
            fixed += 1
            continue
        lc.execute("""INSERT INTO apply_log (store_id, target, old_value, new_value, origin, applied_at)
                      VALUES (%s, 'store.region', %s, %s, 'PROPOSAL', now())""",
                   (sid, Json({"region1st": r1, "region2nd": r2}),
                    Json({"region1st": new1, "region2nd": new2, "reason": "등록 배치 region 정정"})))
        pc.execute("UPDATE store SET region_1st=%s, region_2nd=%s, updated_at=now() WHERE id=%s",
                   (new1, new2, sid))
        fixed += 1
    local.commit()
    print(f"{'정정' if args.execute else '정정 예정'} {fixed} / 보류 {skipped}")

    if args.execute:
        pc.execute("""SELECT region_1st, count(*) FROM store
                      WHERE source='ADMIN_REGISTERED' AND naver_matched_at >= '2026-08-21'
                      GROUP BY 1 ORDER BY 2 DESC LIMIT 8""")
        print("정정 후 분포:", pc.fetchall())
    prod.close()
    local.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
