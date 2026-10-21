# -*- coding: utf-8 -*-
"""재동기화 3단계 — 변경분 PENDING 을 가족별 규칙으로 승인/거절/보류한다 (2026-09-07 결정).

규칙:
  menu.<id>.description  새 설명 6자 미만 → 거절 / 운영 설명이 비어 있으면 승인 / 변경(기존 설명 있음)은 새 설명 15자↑만 승인, 6~14자 변경은 보류
  menu.<id>.price        승인 (재판정이 네이버 가격만 diff 로 냄, 배민 제외)
  store.business_hours   승인 (JSON 배열)
  store.name             정규화(공백·기호 제거, 소문자, 까페→카페) 후 옛 이름 ⊂ 새 이름 또는 새 이름 ⊂ 옛 이름이면 승인, 나머지 보류(표본 검수)
  store.phone            승인 (전화 형식 게이트)
  store.address          좌표 거리 1km 미만이고 서버 위치 게이트(GET /api/proposals/{id}/location-gate)를 통과하면 승인, 아니면 보류.
                         지역은 제안 JSON 이 아니라 region_resolution 근거에서 온다 (v3 P4-4 — scripts/region_resolver.py 로 먼저 해소)
  store.instagram_handle 승인 (핸들 문자 게이트)
  menu.<id>.removed      보류 (소멸 검수 정책 별도)
  store.category         BE StoreCategory 코드 집합(생성표 store_category_map.CODES)에 있으면 승인, 아니면 보류 — store_category_propose.py 가 만든 것만 존재
  store.region           서버 위치 게이트를 통과해야 승인 — 현재 운영 좌표의 법정동 근거와 제안 지역이 같아야 한다 (v3 P4-4)
승인 경로 그대로(PATCH approve/reject → apply_log 선기록 → ServingWriter). 재실행하면 남은 PENDING 만 이어서.

사용:
  python scripts/approve_stage3_pending.py --dry-run --report <tsv>
  python scripts/approve_stage3_pending.py --server http://localhost:8091 [--families price,hours] [--max N]
"""
import argparse
import csv
import json
import math
import os
import re
import sys
import time
from collections import Counter

import psycopg2
import requests
from dataops_server import server_headers

FAMILIES = ["description", "price", "hours", "name", "phone", "address", "instagram", "region", "category"]
try:
    from store_category_map import CODES as CATEGORY_CODES  # BE StoreCategory.java 의 생성표 (store_category_sync.py)
except ImportError:  # 생성표가 없으면 category 는 전부 보류 — 조용히 승인하지 않는다
    CATEGORY_CODES = ()
PHONE_RE = re.compile(r'^"(0[0-9-]{8,13}|1[0-9]{3}-[0-9]{4})"$')
INSTA_RE = re.compile(r'^"[A-Za-z0-9._]{1,30}"$')
PUNCT_RE = re.compile(r"[^0-9A-Za-z가-힣]+")


def norm_name(s):
    return PUNCT_RE.sub("", (s or "").replace("까페", "카페")).lower()


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def decide(target, old_raw, new_raw):
    """→ (family, decision, note)  decision ∈ approve/reject/hold"""
    old_empty = old_raw is None or old_raw in ("null", '""', "[]")
    if re.match(r"^menu\.\d+\.description$", target):
        new_text = json.loads(new_raw) if new_raw else ""
        n = len((new_text or "").strip())
        if n < 6:
            return "description", "reject", "재동기화 검수 — 설명이 아닌 짧은 값(6자 미만)"
        if old_empty:
            return "description", "approve", ""
        return ("description", "approve", "") if n >= 15 else ("description", "hold", "변경 6~14자 보류")
    if re.match(r"^menu\.\d+\.price$", target):
        return "price", "approve", ""
    if re.match(r"^menu\.\d+\.removed$", target):
        return "removed", "hold", "소멸 정책 별도"
    if target == "store.business_hours":
        try:
            ok = isinstance(json.loads(new_raw), list) and len(json.loads(new_raw)) > 0
        except Exception:  # noqa: BLE001
            ok = False
        return ("hours", "approve", "") if ok else ("hours", "hold", "형식 이상")
    if target == "store.name":
        o, n = norm_name(json.loads(old_raw) if old_raw else ""), norm_name(json.loads(new_raw) if new_raw else "")
        if o and n and (o in n or n in o):
            return "name", "approve", ""
        return "name", "hold", "포함 관계 아님 — 표본 검수"
    if target == "store.phone":
        return ("phone", "approve", "") if PHONE_RE.match(new_raw or "") else ("phone", "hold", "전화 형식 이상")
    if target == "store.instagram_handle":
        return ("instagram", "approve", "") if INSTA_RE.match(new_raw or "") else ("instagram", "hold", "핸들 형식 이상")
    if target == "store.region":
        # 근거는 서버의 region_resolution 에 있다 — 여기서는 후보로만 올리고 apply_location_gate 가 가른다
        return "region", "approve", ""

    if target == "store.category":
        # 네이버 카테고리 → BE 코드. 집합 밖이면 보류 — 생성표가 BE enum 보다 낡았거나 손으로 고친 것
        v = (new_raw or "").strip().strip('"')
        return ("category", "approve", "") if v in CATEGORY_CODES else ("category", "hold", f"코드 아님: {v[:20]}")
    if target == "store.address":
        try:
            o, n = json.loads(old_raw), json.loads(new_raw)
            d = haversine_m(float(o["lat"]), float(o["lon"]), float(n["lat"]), float(n["lon"]))
        except Exception:  # noqa: BLE001
            return "address", "hold", "좌표 없음 — 눈검수"
        # 지역 근거는 서버 게이트가 본다(apply_location_gate). 여기서는 이동 거리 검수 규칙만 둔다
        return ("address", "approve", f"{d:.0f}m") if d < 1000 else ("address", "hold", f"{d / 1000:.1f}km 이동 — 눈검수")
    return "other", "hold", "규칙 없음"


def load_pending():
    # 포트를 박지 않는다 — 이 Mac 에서 15433 은 개발 DB 터널이라, 박아 두면 개발 DB 의 제안 id 로 승인을 보낸다
    import local_db
    conn = local_db.connect()
    cur = conn.cursor()
    cur.execute("SELECT id, store_id, target, old_value::text, new_value::text FROM change_proposal WHERE status = 'PENDING' ORDER BY target, id")
    rows = cur.fetchall()
    conn.close()
    out = []
    for pid, sid, target, old_raw, new_raw in rows:
        fam, dec, note = decide(target, old_raw, new_raw)
        out.append({"id": pid, "store_id": sid, "target": target, "family": fam, "decision": dec, "note": note,
                    "old": (old_raw or "")[:80], "new": (new_raw or "")[:80]})
    return out


LOCATION_TARGETS = ("store.address", "store.region")


def apply_location_gate(server, items, log):
    """위치 제안은 서버 게이트 미리보기로 가른다 — 스크립트가 근거를 따로 판정하면 서버와 두 벌로 갈라진다.

    서버에 닿지 못하면 승인 후보로 두지 않는다(보류). 읽기 전용 호출이라 운영에 쓰지 않는다.
    """
    checked = passed = 0
    for it in items:
        if it["target"] not in LOCATION_TARGETS or it["decision"] != "approve":
            continue
        checked += 1
        try:
            r = requests.get(f"{server}/api/proposals/{it['id']}/location-gate", headers=server_headers(), timeout=20)
            data = r.json().get("data") if r.ok else None
        except requests.RequestException as e:
            data = None
            it["note"] = f"게이트 미리보기 실패: {e}"[:120]
        if not data:
            it["decision"], it["note"] = "hold", it["note"] or "게이트 미리보기 불가 — 서버 확인"
        elif not data["passed"]:
            it["decision"], it["note"] = "hold", f"{it['note']} · {data['holdReason']}"[:200]
        else:
            ev = data["evidence"]
            it["note"] = f"{it['note']} · 근거 #{ev['resolutionId']} {ev['region1st']} {ev['region2nd']} {ev['region3rd']}"
            passed += 1
    if checked:
        log(f"[stage3] 위치 게이트 미리보기 {checked:,}건 — 통과 {passed:,} · 보류 {checked - passed:,}")


def call(server, it):
    if it["decision"] == "approve":
        return requests.patch(f"{server}/api/proposals/{it['id']}/approve", headers=server_headers(), timeout=90)
    return requests.patch(f"{server}/api/proposals/{it['id']}/reject", headers=server_headers(), json={"note": it["note"]}, timeout=60)


def run(server, items, sleep, log):
    ok = failed = 0
    t0 = time.time()
    for i, it in enumerate(items, 1):
        try:
            r = call(server, it)
            if r.ok:
                ok += 1
            else:
                failed += 1
                if failed <= 30:
                    log(f"[stage3] 실패 {it['id']} {it['target']} — {r.status_code} {r.text[:160]}")
                if failed >= 50 and ok == 0:
                    raise SystemExit("[stage3] 초반 50건 연속 실패 — 서버/writer 설정 확인 후 재실행")
        except requests.RequestException as e:
            failed += 1
            log(f"[stage3] 예외 {it['id']}: {e}")
        if i % 1000 == 0:
            rate = i / (time.time() - t0)
            log(f"[stage3] {i}/{len(items)} — 성공 {ok} 실패 {failed} · {rate:.0f}/s · 남은 ≈{(len(items) - i) / rate / 60:.0f}분")
        time.sleep(sleep)
    log(f"[stage3] 종료 — 성공 {ok} / 실패 {failed} · {(time.time() - t0) / 60:.1f}분")
    return failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://localhost:8091")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report", help="결정 TSV 경로")
    ap.add_argument("--families", default=",".join(FAMILIES), help="처리할 가족 (쉼표)")
    ap.add_argument("--max", type=int, default=0, dest="max_items")
    ap.add_argument("--sleep", type=float, default=0.02)
    args = ap.parse_args()
    fams = [f for f in args.families.split(",") if f]

    def log(s):
        print(s, flush=True)

    items = load_pending()
    apply_location_gate(args.server, items, log)
    cnt = Counter((it["family"], it["decision"]) for it in items)
    for fam in FAMILIES + ["removed", "other"]:
        a, r, h = cnt[(fam, "approve")], cnt[(fam, "reject")], cnt[(fam, "hold")]
        if a or r or h:
            log(f"[stage3] {fam:12s} 승인 {a:>6,} · 거절 {r:>5,} · 보류 {h:>6,}")
    if args.report:
        with open(args.report, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["id", "store_id", "target", "family", "decision", "note", "old", "new"], delimiter="\t")
            w.writeheader()
            w.writerows(items)
        log(f"[stage3] 결정 TSV: {args.report}")
    if args.dry_run:
        return 0
    health = requests.get(f"{args.server}/actuator/health", headers=server_headers(), timeout=5).json().get("status")
    if health != "UP":
        raise SystemExit(f"서버 상태 {health} — 중단")
    todo = [it for it in items if it["family"] in fams and it["decision"] in ("approve", "reject")]
    if args.max_items:
        todo = todo[: args.max_items]
    log(f"[stage3] 실행 대상 {len(todo):,}건 (가족 {','.join(fams)})")
    return 1 if run(args.server, todo, args.sleep, log) else 0


if __name__ == "__main__":
    sys.exit(main())
