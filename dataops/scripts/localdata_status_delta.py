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

from cloud_runtime import matching_dir

ROOT = Path(__file__).resolve().parent.parent
MATCHING = matching_dir()
DELTA_DIR = MATCHING / "localdata_raw" / "delta"
OUT = MATCHING / "status_delta_closures.tsv"


def load_identity_links():
    """이전 연결의 가게 이름/플레이스 ID도 보관하여 운영의 현재 식별자와 대조한다."""
    links = {}
    include = set()
    with (MATCHING / "new_review_judgments.jsonl").open() as stream:
        for line in stream:
            row = json.loads(line)
            if row.get("tier") == "INCLUDE": include.add(row["mgmtNo"])
    with (MATCHING / "localdata_mapping.tsv").open() as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            if row["mgmt_no"] and (row["judgment"] == "AUTO" or row["mgmt_no"] in include):
                links[row["mgmt_no"]] = (int(row["store_id"]), row["store_name"], row.get("naver_place_id", ""))
    source = MATCHING / "registered_links.tsv"
    if source.exists():
        with source.open() as stream:
            for row in csv.DictReader(stream, delimiter="\t"):
                links.setdefault(row["mgmt_no"], (int(row["registered_store_id"]), "", row["naver_place_id"]))
    conn = local_db.connect()
    try:
        with conn.cursor() as cursor:
            cursor.execute("SELECT mgmt_no,registered_store_id,naver_place_id FROM new_store_candidate "
                           "WHERE status='REGISTERED' AND registered_store_id IS NOT NULL")
            for mgmt, sid, place_id in cursor: links[mgmt] = (sid, "", place_id or "")
    finally:
        conn.close()
    return links


def identity_matches(expected, name, place_id):
    _, expected_name, expected_place = expected
    if expected_place:
        return str(expected_place) == str(place_id or "")
    return bool(expected_name) and expected_name == name


def closure_verdict(result):
    """수집 장애와 가게 부재를 구분한다. 부재도 운영 삭제가 아닌 검토 제안이다."""
    if result.blocked:
        return "UNKNOWN_HOLD", "BLOCKED"
    if result.kind == "HTTP_ERROR" and result.http_status in (404, 410):
        return "ENQUEUE", "HTTP_ABSENT"
    if result.kind != "SUCCESS" or not result.payload:
        return "UNKNOWN_HOLD", result.kind
    base = place_base(result.payload)
    if base and base.get("name"):
        return "CONFLICT_HOLD", "NAVER_PRESENT"
    return "UNKNOWN_HOLD", "PLACE_IDENTITY_UNRESOLVED"


def enqueue_closures(conn, rows):
    inserted = 0
    with conn, conn.cursor() as cursor:
        for row in rows:
            if row["verdict"] != "ENQUEUE": continue
            # 같은 실행기의 재전달·동시 처리를 트랜잭션 안에서 직렬화한다.
            cursor.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (f"dataops:closure:{row['store_id']}",))
            cursor.execute("""
                INSERT INTO change_proposal (store_id,target,old_value,new_value,source,snapshot_id,status,
                                             producer,target_family,policy_version)
                SELECT %s,'store.status',%s,%s,'LOCALDATA',NULL,'PENDING',
                       'LOCALDATA_STATUS','STORE_STATUS','cloud-status-v1'
                WHERE NOT EXISTS (SELECT 1 FROM change_proposal
                  WHERE store_id=%s AND target='store.status' AND status IN ('PENDING','APPLYING','APPROVED'))
                """, (row["store_id"], Json("ACTIVE"), Json({"status": "DELETED", "evidence": row["reason"],
                          "mgmtNo": row["mgmt_no"], "closedYmd": row["closed_ymd"], "observedAt": row["observed_at"]}), row["store_id"]))
            inserted += cursor.rowcount
    return inserted


def main() -> int:
    from batch_execution import atomic_json, utc_now
    from cloud_runtime import serving_connect
    from cloud_public_pull import digest
    import requests

    parser = argparse.ArgumentParser()
    parser.add_argument("--enqueue", action="store_true")
    parser.add_argument("--server", default="http://localhost:8091", help=argparse.SUPPRESS)  # 기존 호출 호환(미사용)
    parser.add_argument("--max", type=int, default=100, dest="max_items")
    args = parser.parse_args()
    if args.max_items < 1: parser.error("max must be positive")
    identities = load_identity_links()
    mapping = {key: value[0] for key, value in identities.items()}
    latest = {}
    for dataset in DATASET_LABEL:
        file = DELTA_DIR / f"{dataset}_upd.jsonl"
        if not file.exists(): continue
        for line in file.read_text().splitlines():
            row = json.loads(line)
            if row.get("MNG_NO") in mapping: latest[row["MNG_NO"]] = row
    closed = {key: row for key, row in latest.items()
              if "폐업" in str(row.get("DTL_SALS_STTS_NM") or row.get("SALS_STTS_NM") or "")}
    conn = serving_connect()
    try:
        with conn.cursor() as cursor:
            cursor.execute("SELECT id, name, naver_place_id FROM store WHERE id=ANY(%s) AND status='ACTIVE'",
                           ([mapping[key] for key in closed],))
            active = {r[0]: (r[1], r[2]) for r in cursor.fetchall()}
    finally:
        conn.close()
    path = MATCHING / "closure_observations.json"
    observations = json.loads(path.read_text()) if path.exists() else {}
    rows, attempts, deferred = [], 0, 0
    for key, source in sorted(closed.items()):
        sid = mapping[key]
        if sid not in active: continue
        name, place_id = active[sid]
        identity = digest([key, sid, place_id, source])
        row = observations.get(identity)
        if not row or row["verdict"] == "UNKNOWN_HOLD":
            if attempts >= args.max_items:
                deferred += 1
                continue
            attempts += 1
            verdict, reason = "UNKNOWN_HOLD", "NO_NAVER_PLACE_ID"
            if not identity_matches(identities[key], name, place_id):
                reason = "STORE_IDENTITY_CHANGED"
            elif place_id:
                try:
                    verdict, reason = closure_verdict(fetch_place(place_id))
                except requests.RequestException:
                    verdict, reason = "UNKNOWN_HOLD", "FETCH_ERROR"
                time.sleep(1.5)
            row = {"store_id": sid, "store_name": name, "mgmt_no": key,
                   "licensed_name": source.get("BPLC_NM", ""), "closed_ymd": source.get("CLSBIZ_YMD", ""),
                   "verdict": verdict, "reason": reason, "observed_at": utc_now()}
            observations[identity] = row
            atomic_json(path, observations)
        rows.append(row)
    with OUT.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["store_id", "store_name", "mgmt_no", "licensed_name",
                                                   "closed_ymd", "verdict", "reason", "observed_at"], delimiter="\t")
        writer.writeheader()
        writer.writerows(rows)
    inserted = 0
    if args.enqueue:
        conn = local_db.connect()
        try:
            inserted = enqueue_closures(conn, rows)
        finally:
            conn.close()
    unknown = sum(row["verdict"] == "UNKNOWN_HOLD" for row in rows)
    atomic_json(MATCHING / "closure-summary.json", {"matched_changes": len(closed), "observed": len(rows),
                "attempted": attempts, "proposed": inserted, "held": sum(row["verdict"] != "ENQUEUE" for row in rows),
                "unknown": unknown, "deferred": deferred})
    print(f"폐업 확인 {len(rows)} / 보류 {unknown} / 다음 실행 대상 {deferred} / 신규 제안 {inserted}")
    return 4 if unknown or deferred else 0


if __name__ == "__main__":
    sys.exit(main())
