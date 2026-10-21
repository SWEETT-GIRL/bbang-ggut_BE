#!/usr/bin/env python3
"""상태 델타 → 폐업 전환 감지 — 운영 주기(설계 §3.6)의 폐업 루프.

입력: matching/localdata_raw/delta/{ds}_upd.jsonl (localdata_fetch --delta 산출)
로직:
  1. 관리번호→운영 가게 매핑 구성 (파일럿 매핑 AUTO·검수 INCLUDE + candidate REGISTERED)
  2. 델타에서 "매핑된 관리번호가 폐업으로 전환"된 레코드 추출
  3. 운영에서 해당 가게가 아직 ACTIVE 인 것만 남김
  4. 다중 신호 원칙 — 네이버 플레이스 생존 교차: 페이지 부재/사망 → 수납 후보,
     생존 → 신호 충돌 보류 (인허가 단독으로 폐업 처리 금지)
출력: matching/status_delta_closures.tsv (+ --enqueue 시 change_proposal 수납, 승인은 별도)

사용: python scripts/localdata_status_delta.py [--enqueue]
"""
import argparse
import csv
import json
import sys
import time
from pathlib import Path

import psycopg2
from psycopg2.extras import Json

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_db
from localdata_match import DATASET_LABEL
from sync_worker import fetch_place
from localdata_register_worker import place_base

ROOT = Path(__file__).resolve().parent.parent
DELTA_DIR = ROOT / "matching" / "localdata_raw" / "delta"
OUT = ROOT / "matching" / "status_delta_closures.tsv"


def load_mgmt_to_store() -> dict:
    """관리번호 → store_id — 파일럿 매핑(AUTO + 검수 INCLUDE)과 등록분(REGISTERED)의 합."""
    mapping = {}
    with open(ROOT / "matching" / "localdata_mapping.tsv", encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if r["judgment"] == "AUTO" and r["mgmt_no"]:
                mapping[r["mgmt_no"]] = int(r["store_id"])
    include = set()
    with open(ROOT / "matching" / "new_review_judgments.jsonl", encoding="utf-8") as f:
        for line in f:
            j = json.loads(line)
            if j.get("tier") == "INCLUDE":
                include.add(j["mgmtNo"])
    with open(ROOT / "matching" / "localdata_mapping.tsv", encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if r["judgment"] == "REVIEW" and r["mgmt_no"] in include:
                mapping[r["mgmt_no"]] = int(r["store_id"])
    lconn = local_db.connect()
    lc = lconn.cursor()
    lc.execute("SELECT mgmt_no, registered_store_id FROM new_store_candidate "
               "WHERE status='REGISTERED' AND registered_store_id IS NOT NULL")
    for m, sid in lc.fetchall():
        mapping[m] = sid
    lconn.close()
    return mapping


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enqueue", action="store_true")
    parser.add_argument("--server", default="http://localhost:8091")
    args = parser.parse_args()

    mapping = load_mgmt_to_store()
    print(f"매핑 관리번호 {len(mapping):,}")

    # 델타에서 폐업 전환 레코드 (관리번호당 최신 레코드 기준)
    latest = {}
    for ds in DATASET_LABEL:
        p = DELTA_DIR / f"{ds}_upd.jsonl"
        if not p.exists():
            continue
        for line in open(p, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            m = r.get("MNG_NO") or ""
            if m in mapping:
                latest[m] = r
    closed = {m: r for m, r in latest.items()
              if "폐업" in ((r.get("DTL_SALS_STTS_NM") or r.get("SALS_STTS_NM") or ""))}
    print(f"델타 내 매핑 관리번호 {len(latest)} — 폐업 전환 {len(closed)}")

    if not closed:
        print("폐업 전환 없음")
        return 0

    # 운영에서 아직 ACTIVE 인 가게만
    import re
    env = dict(re.findall(r"^([A-Z_]+)=(.*)$", (ROOT / ".env").read_text(encoding="utf-8"), re.M))
    pconn = psycopg2.connect(host="localhost", port=15432, dbname="soboro",
                             user=env["PROD_DB_USERNAME"], password=env["PROD_DB_PASSWORD"],
                             connect_timeout=8, sslmode="require")
    pc = pconn.cursor()
    sids = [mapping[m] for m in closed]
    pc.execute("SELECT id, name, naver_place_id FROM store WHERE id = ANY(%s) AND status='ACTIVE'", (sids,))
    active = {r[0]: (r[1], r[2]) for r in pc.fetchall()}
    pconn.close()
    targets = [(m, r, mapping[m]) for m, r in closed.items() if mapping[m] in active]
    print(f"운영 ACTIVE 교집합 {len(targets)}")

    # 네이버 생존 교차
    rows = []
    for m, r, sid in targets:
        name, place_id = active[sid]
        alive = None
        if place_id:
            apollo, blocked = fetch_place(place_id)
            base = place_base(apollo) if apollo else None
            alive = bool(base and base.get("name"))
            time.sleep(1.5)
        verdict = "CONFLICT_HOLD" if alive else "ENQUEUE"
        rows.append({
            "store_id": sid, "store_name": name, "mgmt_no": m,
            "licensed_name": (r.get("BPLC_NM") or "").strip(),
            "closed_ymd": (r.get("CLSBIZ_YMD") or "").strip(),
            "naver_alive": alive, "verdict": verdict,
        })
        print(f"  {name} ({sid}) 폐업 {rows[-1]['closed_ymd']} — 네이버 {'생존→보류' if alive else '부재→수납'}")

    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    print(f"→ {OUT.name} ({len(rows)}건)")

    if args.enqueue:
        lconn = local_db.connect()
        lc = lconn.cursor()
        n = 0
        for row in rows:
            if row["verdict"] != "ENQUEUE":
                continue
            lc.execute("""
                INSERT INTO change_proposal (store_id, target, old_value, new_value, source, snapshot_id, status,
                                         producer, target_family, policy_version)
                SELECT %s, 'store.status', %s, %s, 'LOCALDATA', NULL, 'PENDING',
                       'LOCALDATA_STATUS', 'STORE_STATUS', 'localdata_status_delta-2026-09-26'
                WHERE NOT EXISTS (SELECT 1 FROM change_proposal
                    WHERE store_id = %s AND target = 'store.status' AND status IN ('PENDING','APPLYING','APPROVED'))""",
                (row["store_id"], Json("ACTIVE"),
                 Json({"status": "DELETED", "evidence": "상태델타+네이버부재",
                       "mgmtNo": row["mgmt_no"], "closedYmd": row["closed_ymd"] or None}),
                 row["store_id"]))
            n += lc.rowcount
        lconn.commit()
        lconn.close()
        print(f"수납 {n}건 (승인은 별도 실행)")
    return 0


if __name__ == "__main__":
    main()
