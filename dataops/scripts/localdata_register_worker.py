#!/usr/bin/env python3
"""신규 후보 등록 워커 — MATCHED 후보를 서버 등록 API 로 운영 store 에 편입.

흐름 (후보당):
  1. 네이버 플레이스 fetch (sync_worker.fetch_place — Apollo 상태)
  2. PlaceDetailBase 에서 상호·도로명주소·좌표·전화 추출 (서버 NaverPlaceParser 와 동일 키)
  3. 그 좌표를 region_resolver 로 역산해 법정동 근거를 남긴다 (region_resolution: CANDIDATE, 관리번호,
     REGISTRATION). 같은 좌표·같은 버전의 근거가 있으면 다시 부르지 않는다 (v3 P4-4)
  4. 배치로 POST /api/candidates/register-batch — 지역은 싣지 않는다. 서버가 같은 좌표의 근거를 찾아 그 지역을
     쓰고, 근거가 없거나 해소되지 않았으면 신규 INSERT 를 보류한다. 선점 확인·apply_log·sync_state 도 서버가
  5. 등록된 가게는 같은 payload 로 POST /api/snapshots 제출 → 메뉴·영업시간·인스타가
     기존 reconcile 파이프라인으로 흘러 proposal 이 된다 (storeId 는 로컬 candidate 행에서 조회)

인허가 지번주소에서 3토큰을 잘라 지역으로 쓰던 방식은 없앴다 — 검증이 아니다 (시+구 도시에서 구를 동 자리에
넣는 오류도 있었다). 지번주소는 도로명주소가 없을 때의 주소 대체값으로만 쓴다.

재개: candidate.status 가 워크리스트 (MATCHED 만 집음). 차단 시 쿨다운 자동 재개.
운영 쓰기는 전부 서버(ServingWriter) 경유 — 이 스크립트는 운영 DB 직접 접속 없음.

사용:
    python scripts/localdata_register_worker.py --preview          # 판정만 (운영·후보 상태 쓰기 없음)
    python scripts/localdata_register_worker.py --preview --probe  # + 운영 INSERT 후 롤백 (dry-run)
    python scripts/localdata_register_worker.py --max 5            # 시운전
    python scripts/localdata_register_worker.py                    # 전량
    python scripts/localdata_register_worker.py --server http://localhost:8090
미리보기도 네이버 fetch·카카오 역산·근거 기록(로컬)은 한다 — 근거는 등록 결과와 무관한 관측이다.
"""
import argparse
import csv
import datetime as dt
import json
import sys
import time
import uuid
from dataclasses import asdict
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_db
from capture_spool import CaptureSpool
from sync_worker import FetchResult, fetch_place, submit_retained
from localdata_match import DATASET_LABEL
from region_resolver import PointResolver
from dataops_server import server_headers

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "matching" / "localdata_raw"

BATCH_SIZE = 20
COOLDOWN_MINUTES = 45
MAX_COOLDOWNS = 8


def load_matched(limit=None, only=None):
    """등록 대기 + 후속 미완 후보.

    NEEDS_RECOVERY·STORE_PRESENT 는 운영에 가게가 이미 있으나 sync_state 등 후속이 빠진 건이다 (v3 P1-3).
    다시 보내면 서버의 ensureRegistrationCompleted 가 빠진 것만 채운다 — 사람의 추가 호출에 의존하지 않는다.
    UNKNOWN 은 결과 불명이라 확인 전에는 보내지 않는다.
    only: 관리번호 목록 — 승인받은 후보만 정확히 돌릴 때. 대상 조건은 그대로 적용된다.
    """
    conn = local_db.connect()
    cur = conn.cursor()
    sql = ("SELECT mgmt_no, name, naver_place_id, registration_state FROM new_store_candidate "
           "WHERE ((status = 'MATCHED' AND COALESCE(registration_state, '') <> 'UNKNOWN') "
           "   OR registration_state IN ('NEEDS_RECOVERY', 'STORE_PRESENT')) "
           + ("AND mgmt_no = ANY(%s) " if only else "")
           + "ORDER BY licensed_at DESC")
    if limit:
        sql += f" LIMIT {int(limit)}"
    cur.execute(sql, (list(only),) if only else None)
    rows = cur.fetchall()
    conn.close()
    return rows


def load_jibun(needed: set) -> dict:
    out = {}
    for ds in DATASET_LABEL:
        p = RAW_DIR / f"{ds}.jsonl"
        if not p.exists():
            continue
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                m = rec.get("MNG_NO") or ""
                if m in needed and m not in out:
                    out[m] = (rec.get("LOTNO_ADDR") or "").strip()
    return out


def place_base(apollo: dict):
    """PlaceDetailBase — 서버 NaverPlaceParser 와 동일 키 (name/roadAddress/phone/coordinate.x·y)."""
    for key, value in (apollo or {}).items():
        if key.startswith("PlaceDetailBase") and isinstance(value, dict):
            coord = value.get("coordinate") or {}
            try:
                lon, lat = float(coord.get("x")), float(coord.get("y"))
            except (TypeError, ValueError):
                lon = lat = None
            return {
                "name": (value.get("name") or "").strip(),
                "roadAddress": (value.get("roadAddress") or value.get("address") or "").strip(),
                "phone": (value.get("phone") or "").strip(),
                "lon": lon, "lat": lat,
            }
    return None


def registered_store_ids(mgmt_nos: list) -> dict:
    conn = local_db.connect()
    cur = conn.cursor()
    cur.execute("SELECT mgmt_no, registered_store_id FROM new_store_candidate "
                "WHERE mgmt_no = ANY(%s) AND registered_store_id IS NOT NULL", (mgmt_nos,))
    out = dict(cur.fetchall())
    conn.close()
    return out


def register_item(mgmt_no, base, jibun_addr, lic_name):
    """등록 요청 한 항목 — 지역을 싣지 않는다. 좌표는 법정동 근거를 찾는 키이기도 하다."""
    return {
        "mgmtNo": mgmt_no, "name": base["name"],
        "address": base["roadAddress"] or jibun_addr or lic_name,
        "longitude": base["lon"], "latitude": base["lat"],
        "phone": base["phone"] or None,
    }


PREVIEW_COLUMNS = ["mgmtNo", "name", "plan", "region", "bjd_code", "resolution_id", "be_key", "existing_store_id",
                   "probe_row_id", "probe_error", "reason", "address", "lon", "lat"]


def capture_fetch(place_id):
    """Freeze identity and observation time at fetch, before registration/evidence calls."""
    fetched = fetch_place(place_id)
    payload, blocked = fetched
    if not isinstance(fetched, FetchResult):
        fetched = FetchResult(payload, blocked, "BLOCKED" if blocked else "SUCCESS")
    metadata = asdict(fetched)
    metadata.pop("payload")
    return {"observationId": str(uuid.uuid4()), "externalId": str(place_id),
            "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "payload": payload, "fetch": metadata}, blocked


def retain_snapshot(spool, store_id, mgmt_no, capture):
    request = {key: capture[key] for key in ("observationId", "externalId", "observedAt", "payload")}
    request.update(storeId=store_id, source="NAVER")
    return spool.retain(capture["observationId"], request, {**capture["fetch"], "mgmt_no": mgmt_no})


def deliver_snapshot(server, spool, record, totals):
    """Only a matching capture receipt is delivery success; a lost ACK stays pending."""
    try:
        receipt = submit_retained(server, record)
    except (requests.RequestException, ValueError, KeyError):
        totals["snapshot_pending"] += 1
        print("[register] 스냅샷 제출 미확정 — 원본을 보존하고 다음 실행에서 재전송", flush=True)
        return False
    spool.acknowledge(record, receipt)
    totals["snapshot_" + receipt["assessment"].lower()] += 1
    if receipt.get("snapshotId") is not None:
        totals["snapshots"] += 1
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="신규 후보 등록 워커")
    parser.add_argument("--server", default="http://localhost:8090")
    parser.add_argument("--max", type=int, default=None, dest="max_items")
    parser.add_argument("--interval", type=float, default=2.5)
    parser.add_argument("--preview", action="store_true",
                        help="등록하지 않고 서버 판정만 받는다 (운영·후보 상태 쓰기 없음)")
    parser.add_argument("--probe", action="store_true",
                        help="--preview 와 함께 — INSERT 판정 항목을 운영에 INSERT 후 롤백한다")
    parser.add_argument("--only", default=None,
                        help="관리번호 쉼표 목록 — 이 후보만 처리한다 (승인 범위를 정확히 지킬 때)")
    parser.add_argument("--report", default=None, help="미리보기 TSV 경로 (기본 dataops-logs/register_preview_<시각>.tsv)")
    parser.add_argument("--capture-dir", default=str(ROOT / "dataops-logs" / "registration-captures"),
                        help="등록 후 스냅샷 원본/재전송 보관 경로")
    args = parser.parse_args()
    if args.probe and not args.preview:
        parser.error("--probe 는 --preview 와 함께만 쓴다")

    if args.preview:
        return run(args)
    spool = CaptureSpool(args.capture_dir)
    with spool.exclusive():
        return run(args, spool)


def run(args, spool=None) -> int:
    only = [m.strip() for m in args.only.split(",") if m.strip()] if args.only else None
    totals = {"registered": 0, "dedup": 0, "recovered": 0, "held": 0, "unknown": 0,
              "skipped": 0, "failed": 0, "fetch_fail": 0, "snapshots": 0,
              "snapshot_ready": 0, "snapshot_partial": 0, "snapshot_held": 0, "snapshot_pending": 0,
              "evidence_new": 0, "evidence_reused": 0, "evidence_unresolved": 0}
    # Registered candidates may no longer be in the worklist; replay before loading it.
    if not args.preview:
        if spool is None:
            raise ValueError("registration requires a locked capture spool")
        for record in spool.pending():
            if only and record["fetch"].get("mgmt_no") not in only:
                continue
            if not deliver_snapshot(args.server, spool, record, totals):
                return 1
    matched = load_matched(args.max_items, only)
    if only and len(matched) != len(only):
        missing = sorted(set(only) - {m for m, _, _, _ in matched})
        print(f"[register] 지정 {len(only)}건 중 대상 조건에 맞지 않는 후보 {missing} — 중단", flush=True)
        return 2
    print(f"[register] 대상 {len(matched)} ({'미리보기' + (' + probe' if args.probe else '') if args.preview else '등록'})",
          flush=True)
    if not matched:
        if not args.preview:
            print(f"[register] 종료 — {totals}", flush=True)
        return 0
    jibun = load_jibun({m for m, _, _, _ in matched})
    evidence_conn = local_db.connect()
    resolver = PointResolver(evidence_conn)
    evidence_by_mgmt = {}

    cooldowns = 0
    batch, payloads, previews = [], {}, []

    def flush_preview():
        r = requests.post(f"{args.server}/api/candidates/register-preview", headers=server_headers(),
                          params={"probe": str(args.probe).lower()}, json={"candidates": batch}, timeout=120)
        r.raise_for_status()
        by_mgmt = {b["mgmtNo"]: b for b in batch}
        for p in r.json()["data"]:
            ev = p.get("evidence") or {}
            local = evidence_by_mgmt.get(p["mgmtNo"]) or {}
            item = by_mgmt[p["mgmtNo"]]
            previews.append({
                "mgmtNo": p["mgmtNo"], "name": p["name"], "plan": p["plan"],
                "region": " ".join(x for x in (ev.get("region1st"), ev.get("region2nd"), ev.get("region3rd")) if x),
                "bjd_code": ev.get("bjdCode") or "", "resolution_id": ev.get("resolutionId") or "",
                "be_key": local.get("be_region3rd_key") or "",
                "existing_store_id": p.get("existingStoreId") or "", "probe_row_id": p.get("probeRowId") or "",
                "probe_error": p.get("probeError") or "", "reason": p.get("reason") or "",
                "address": item["address"], "lon": item["longitude"], "lat": item["latitude"],
            })
        batch.clear()
        payloads.clear()

    def flush_batch():
        if not batch:
            return True
        if args.preview:
            flush_preview()
            return True
        r = requests.post(f"{args.server}/api/candidates/register-batch", headers=server_headers(),
                          json={"candidates": batch}, timeout=120)
        r.raise_for_status()
        data = r.json()["data"]
        totals["registered"] += data["registered"]
        totals["dedup"] += data["dedupResolved"]
        totals["recovered"] += data.get("recovered", 0)
        totals["held"] += data.get("held", 0)
        totals["unknown"] += data.get("unknown", 0)
        totals["skipped"] += data["skipped"]
        totals["failed"] += data["failed"]
        # 등록된 가게는 방금 그 payload 로 스냅샷 제출 → 메뉴·영업시간 파이프라인 진입
        ids = registered_store_ids([b["mgmtNo"] for b in batch])
        records = []
        for b in batch:
            store_id = ids.get(b["mgmtNo"])
            capture = payloads.get(b["mgmtNo"])
            if store_id and capture:
                records.append(retain_snapshot(spool, store_id, b["mgmtNo"], capture))
        # Persist every registered observation before sending any snapshot in this batch.
        for record in records:
            if not deliver_snapshot(args.server, spool, record, totals):
                return False
        print(f"[register] 배치 {len(batch)} — 누적 등록 {totals['registered']} / 중복귀속 {totals['dedup']} "
              f"/ 후속보완 {totals['recovered']} / 보류 {totals['held']} / 결과불명 {totals['unknown']} "
              f"/ 스킵 {totals['skipped']} / 실패 {totals['failed']} / 스냅샷 {totals['snapshots']} "
              f"/ 응답 READY {totals['snapshot_ready']} PARTIAL {totals['snapshot_partial']} "
              f"HELD {totals['snapshot_held']}", flush=True)
        batch.clear()
        payloads.clear()
        return True

    try:
        for i, (mgmt_no, lic_name, place_id, reg_state) in enumerate(matched, 1):
            capture, blocked = capture_fetch(place_id)
            if blocked:
                cooldowns += 1
                if cooldowns > MAX_COOLDOWNS:
                    print(f"[register] 쿨다운 소진 — 종료 (재실행으로 재개)", flush=True)
                    if not flush_batch():
                        return 1
                    return 3
                print(f"[register] 차단 감지 — {COOLDOWN_MINUTES}분 쿨다운 ({cooldowns}/{MAX_COOLDOWNS})", flush=True)
                if not flush_batch():
                    return 1
                time.sleep(COOLDOWN_MINUTES * 60)
                capture, blocked = capture_fetch(place_id)
            apollo = capture["payload"]
            base = place_base(apollo) if apollo else None
            if not base or base["lon"] is None or not base["name"]:
                totals["fetch_fail"] += 1  # 폐업·페이지 소멸 등 — MATCHED 로 남아 다음 회차 재시도
            else:
                # 운영에 이미 만든 후보(후속 보완)는 위치를 쓰지 않으므로 역산하지 않는다
                if reg_state not in ("NEEDS_RECOVERY", "STORE_PRESENT"):
                    ev = resolver.resolve("CANDIDATE", mgmt_no, "REGISTRATION", base["lon"], base["lat"])
                    evidence_by_mgmt[mgmt_no] = ev
                    totals["evidence_reused" if ev["reused"] else "evidence_new"] += 1
                    if ev["status"] != "RESOLVED":
                        totals["evidence_unresolved"] += 1
                batch.append(register_item(mgmt_no, base, jibun.get(mgmt_no, ""), lic_name))
                payloads[mgmt_no] = capture
            if len(batch) >= BATCH_SIZE:
                if not flush_batch():
                    return 1
            if i % 50 == 0:
                print(f"[register] 진행 {i}/{len(matched)}", flush=True)
            time.sleep(args.interval)

        if not flush_batch():
            return 1
    finally:
        evidence_conn.close()

    if args.preview:
        report = Path(args.report) if args.report else (
            ROOT / "dataops-logs" / f"register_preview_{dt.datetime.now():%Y%m%d_%H%M}.tsv")
        report.parent.mkdir(parents=True, exist_ok=True)
        with open(report, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=PREVIEW_COLUMNS, delimiter="\t")
            w.writeheader()
            w.writerows(previews)
        plans = {}
        for p in previews:
            plans[p["plan"]] = plans.get(p["plan"], 0) + 1
        print(f"[register] 미리보기 {len(previews)}건 {plans} — 근거 새로 {totals['evidence_new']} / 재사용 "
              f"{totals['evidence_reused']} / 미해소 {totals['evidence_unresolved']} / fetch 실패 {totals['fetch_fail']}",
              flush=True)
        print(f"[register] 보고서 {report}", flush=True)
        return 0
    print(f"[register] 종료 — {totals}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
