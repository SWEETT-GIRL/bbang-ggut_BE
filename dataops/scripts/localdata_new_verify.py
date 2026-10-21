#!/usr/bin/env python3
"""신규 가게 후보 네이버 검증 워커 — 인허가 신규 후보의 빵집 여부를 카테고리로 판별.

파일럿 결론(플랜 §3.6): 상호 키워드는 판별기가 못 된다 — 빵집 여부 최종 판별은
네이버 검색 결과 카테고리(베이커리·디저트·카페)로 한다. 이 워커는
localdata_new_candidates.tsv 의 후보를 네이버 지도에서 검색해:

  EXIST      네이버에 존재 (거리≤50m ∧ 유사도≥0.6, 또는 전화 일치) → 카테고리 힌트 기록
  REVIEW     약한 후보만 존재 — 사람/LLM 후속 판단
  NOT_FOUND  검색 부재 — 갓 오픈 미등록 가능성, 후속 주기 재시도 대상

- 기본 대상: 임포트(2026-05) 이후 인허가 = 진짜 신규 오픈 (--all 로 전체 확장)
- 검색·차단 대응은 match_worker 재사용: allSearch XHR 가로채기, 후보 0건 보류 후
  성공 응답 확인 시에만 기록(차단 구간 가짜 NOT_FOUND 오염 방지), 쿨다운 브라우저 재기동
- 운영 DB 무접촉 — 결과는 matching/new_verify_results.jsonl (mgmtNo 기준 재개 가능)

사용:
    python scripts/localdata_new_verify.py                # 신규 오픈만 (기본)
    python scripts/localdata_new_verify.py --max 5        # 시운전
    python scripts/localdata_new_verify.py --all          # 커버리지 갭 포함 전체
    python scripts/localdata_new_verify.py --retry        # 재시도 풀 재크롤
                                                          # (NOT_FOUND + 검수 RETRY — 완료건 재판정)
"""
import argparse
import csv
import io
import json
import os
import re
import sys
import time
from collections import deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from match_worker import (
    CONSECUTIVE_FAILURE_LIMIT, haversine_m, name_similarity, normalize_phone,
    region_tokens, search_places,
)
from localdata_match import DATASET_LABEL
from localdata_fetch import NAME_KEYWORDS
from bakery_gate import category_pass, leaf_of

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
CANDIDATES = ROOT / "matching" / "localdata_new_candidates.tsv"
RAW_DIR = ROOT / "matching" / "localdata_raw"
OUTPUT_PATH = ROOT / "matching" / "new_verify_results.jsonl"
# --candidates·--out 으로 갈아끼운다 (표본 측정처럼 본 후보표·결과에 섞으면 안 되는 실험용)

IMPORT_CUTOFF = "20260501"
EXIST_MAX_DISTANCE_M = 50
EXIST_MIN_NAME_SIMILARITY = 0.6
REVIEW_MAX_DISTANCE_M = 150
REVIEW_MIN_NAME_SIMILARITY = 0.75

CAFE_PAT = re.compile(r"카페|커피|찻집|브런치")

LABEL_TO_DS = {v: k for k, v in DATASET_LABEL.items()}


def ymd(s: str) -> str:
    return re.sub(r"\D", "", s or "")[:8]


def load_candidates(only_recent: bool) -> list[dict]:
    rows = []
    with open(CANDIDATES, encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            if only_recent and ymd(r["licensed_ymd"]) < IMPORT_CUTOFF:
                continue
            rows.append(r)
    # 주소·전화는 후보 TSV에 없다 — 원본에서 관리번호로 조인
    needed = {r["mgmt_no"] for r in rows}
    extra = {}
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
                if m in needed and m not in extra:
                    extra[m] = {
                        "address": (rec.get("ROAD_NM_ADDR") or rec.get("LOTNO_ADDR") or "").strip(),
                        "phone": normalize_phone(rec.get("TELNO") or ""),
                    }
    out = []
    for r in rows:
        e = extra.get(r["mgmt_no"], {"address": "", "phone": ""})
        out.append({
            "mgmt_no": r["mgmt_no"], "dataset": r["dataset"], "category": r["category"],
            "name": r["name"], "licensed_ymd": ymd(r["licensed_ymd"]),
            "lat": float(r["lat"]) if r["lat"] else None,
            "lon": float(r["lon"]) if r["lon"] else None,
            "address": e["address"], "phone": e["phone"],
        })
    # 상호에 빵집 키워드가 있는 후보부터 — 키워드는 판별기가 아니지만(최종 판정은 네이버 카테고리) 순서는 정한다.
    # 한 세션을 다 못 돌려도 빵집이 앞쪽에서 나온다 (설계 §3.6 "키워드 보유분은 우선순위만 상향")
    out.sort(key=lambda r: (has_bakery_keyword(r["name"]), r["licensed_ymd"]), reverse=True)
    return out


def has_bakery_keyword(name: str) -> bool:
    flat = (name or "").replace(" ", "")
    return any(kw in flat for kw in NAME_KEYWORDS)


def load_retry_set() -> set:
    """재시도 풀 — 최신 검증이 NOT_FOUND 이거나 검수에서 RETRY(다른 지점) 판정된 후보.
    재크롤 시점에 신규점이 네이버에 등록돼 있으면 EXIST 로 전환된다."""
    latest = {}
    if OUTPUT_PATH.exists():
        for line in io.open(OUTPUT_PATH, encoding="utf-8"):
            try:
                r = json.loads(line)
                latest[r["mgmtNo"]] = r["judgment"]
            except (json.JSONDecodeError, KeyError):
                continue
    retry = {m for m, j in latest.items() if j == "NOT_FOUND"}
    judgments = ROOT / "matching" / "new_review_judgments.jsonl"
    if judgments.exists():
        for line in io.open(judgments, encoding="utf-8"):
            j = json.loads(line)
            if j.get("tier") == "RETRY":
                retry.add(j["mgmtNo"])
            elif j.get("tier") in ("NONTARGET", "FRANCHISE", "INCLUDE"):
                retry.discard(j["mgmtNo"])
    return retry


def load_done() -> set:
    done = set()
    if OUTPUT_PATH.exists():
        for line in io.open(OUTPUT_PATH, encoding="utf-8"):
            try:
                done.add(json.loads(line)["mgmtNo"])
            except (json.JSONDecodeError, KeyError):
                continue
    return done


def bakery_hint(category: str) -> str:
    """BAKERY = 카테고리만으로 G1 통과(bakery_gate.category_pass). 09-20 전에는 경로 전체 부분일치(BAKERY_PAT)라
    '카페,디저트' 대분류의 '디저트'·호떡의 '떡'·찐빵의 '빵'이 다 BAKERY 였다. 최종 판정은 localdata_new_confirmed 가 G1 로 한다"""
    if not category:
        return "UNKNOWN"
    if category_pass(leaf_of(category)):
        return "BAKERY"
    if CAFE_PAT.search(category or ""):
        return "CAFE"
    return "OTHER" if category else "UNKNOWN"


def judge(cand: dict, places: list) -> dict:
    best = None
    for place in places:
        try:
            lon, lat = float(place.get("x")), float(place.get("y"))
        except (TypeError, ValueError):
            lon = lat = None
        distance = None
        if lon is not None and cand["lat"] is not None:
            distance = haversine_m(cand["lon"], cand["lat"], lon, lat)
        similarity = name_similarity(cand["name"], place.get("name") or "")
        phone_matched = bool(cand["phone"]) and cand["phone"] == normalize_phone(place.get("tel") or "")
        raw_category = place.get("category")
        if isinstance(raw_category, list):
            raw_category = ",".join(str(c) for c in raw_category)
        score = (0 if phone_matched else 1,
                 (distance if distance is not None else 99999) - similarity * 30)
        entry = {
            "placeId": place.get("id"), "name": place.get("name"), "tel": place.get("tel"),
            "roadAddress": place.get("roadAddress") or place.get("address"),
            "category": raw_category,
            "distanceM": round(distance) if distance is not None else None,
            "nameSimilarity": round(similarity, 3), "phoneMatched": phone_matched,
        }
        if best is None or score < best[0]:
            best = (score, entry)

    if best is None:
        return {"judgment": "NOT_FOUND", "candidate": None, "bakeryHint": None}
    entry = best[1]
    near = entry["distanceM"] is not None and entry["distanceM"] <= EXIST_MAX_DISTANCE_M
    # 상호 강일치는 거리 완화 — 몰·지하상가 인허가 좌표 노이즈 실측(P1-4) 반영
    strong_name = (entry["nameSimilarity"] >= 0.9
                   and entry["distanceM"] is not None and entry["distanceM"] <= 300)
    if entry["phoneMatched"] or strong_name \
            or (near and entry["nameSimilarity"] >= EXIST_MIN_NAME_SIMILARITY):
        return {"judgment": "EXIST", "candidate": entry, "bakeryHint": bakery_hint(entry["category"])}
    if (entry["distanceM"] is not None and entry["distanceM"] <= REVIEW_MAX_DISTANCE_M) \
            or entry["nameSimilarity"] >= REVIEW_MIN_NAME_SIMILARITY:
        return {"judgment": "REVIEW", "candidate": entry, "bakeryHint": bakery_hint(entry["category"])}
    return {"judgment": "NOT_FOUND", "candidate": entry, "bakeryHint": None}


def result_line(cand: dict, verdict: dict, candidate_count: int) -> str:
    return json.dumps({
        "mgmtNo": cand["mgmt_no"], "name": cand["name"], "dataset": cand["dataset"],
        "licCategory": cand["category"], "licensedYmd": cand["licensed_ymd"],
        "address": cand["address"], "judgment": verdict["judgment"],
        "bakeryHint": verdict["bakeryHint"], "candidate": verdict["candidate"],
        "candidateCount": candidate_count,
    }, ensure_ascii=False) + "\n"


def run(only_recent: bool, max_items: int | None, interval: float,
        cooldown_minutes: int, max_cooldowns: int,
        failure_limit: int = CONSECUTIVE_FAILURE_LIMIT, retry: bool = False) -> int:
    cands = load_candidates(only_recent)
    if retry:
        retry_set = load_retry_set()
        queue = deque(c for c in cands if c["mgmt_no"] in retry_set)
    else:
        done = load_done()
        queue = deque(c for c in cands if c["mgmt_no"] not in done)
    print(f"[verify] 대상 {len(queue)}곳 (후보 {len(cands)}{', 재시도 모드' if retry else ''})", flush=True)

    counts = {"EXIST": 0, "REVIEW": 0, "NOT_FOUND": 0}
    hints = {"BAKERY": 0, "CAFE": 0, "OTHER": 0, "UNKNOWN": 0}
    pending_empty = []
    consecutive_failures = 0
    cooldowns_used = 0
    written = 0
    attempts = 0

    with sync_playwright() as playwright_api, \
            io.open(OUTPUT_PATH, "a", encoding="utf-8") as output:

        def launch():
            browser = playwright_api.chromium.launch(headless=True)
            context = browser.new_context(locale="ko-KR")
            context.route(re.compile(r"\.(png|jpg|jpeg|gif|webp|woff2?|mp4|svg)"),
                          lambda route: route.abort())
            return browser, context.new_page()

        browser, page = launch()

        while queue:
            if max_items is not None and attempts >= max_items:
                break
            cand = queue.popleft()
            attempts += 1

            places, captcha = search_places(page, f"{cand['name']} {region_tokens(cand['address'])}")
            if not places and not captcha:
                secondary, captcha = search_places(page, cand["name"])
                places = places or secondary

            blocked = captcha or (not places and consecutive_failures + 1 >= failure_limit)
            if blocked:
                queue.append(cand)
                for pending_cand, _ in pending_empty:
                    queue.append(pending_cand)
                pending_empty.clear()
                consecutive_failures = 0
                cooldowns_used += 1
                if cooldowns_used > max_cooldowns:
                    print(f"[verify] 쿨다운 {max_cooldowns}회 소진 — 종료. 기록 {written}", flush=True)
                    return 3
                print(f"[verify] 차단 의심({'캡차' if captcha else '연속 무응답'}) — "
                      f"{cooldown_minutes}분 쿨다운 ({cooldowns_used}/{max_cooldowns}) 후 재개", flush=True)
                browser.close()
                time.sleep(cooldown_minutes * 60)
                browser, page = launch()
                continue

            verdict = judge(cand, places)
            line = result_line(cand, verdict, len(places))
            if places:
                consecutive_failures = 0
                for _, pending_line in pending_empty:
                    output.write(pending_line)
                    counts["NOT_FOUND"] += 1
                    written += 1
                pending_empty.clear()
                output.write(line)
                counts[verdict["judgment"]] += 1
                if verdict["bakeryHint"]:
                    hints[verdict["bakeryHint"]] += 1
                written += 1
                output.flush()
            else:
                consecutive_failures += 1
                pending_empty.append((cand, line))

            if attempts % 25 == 0:
                print(f"[verify] 시도 {attempts} / 기록 {written} — EXIST {counts['EXIST']} "
                      f"(빵집힌트 {hints['BAKERY']}·카페 {hints['CAFE']}) / REVIEW {counts['REVIEW']} / "
                      f"NOT_FOUND {counts['NOT_FOUND']}", flush=True)
            time.sleep(interval)

        for _, pending_line in pending_empty:
            output.write(pending_line)
            counts["NOT_FOUND"] += 1
            written += 1
        browser.close()

    print(f"[verify] 세션 종료 — 기록 {written}, EXIST {counts['EXIST']} "
          f"(BAKERY {hints['BAKERY']} / CAFE {hints['CAFE']} / OTHER {hints['OTHER']} / "
          f"UNKNOWN {hints['UNKNOWN']}), REVIEW {counts['REVIEW']}, NOT_FOUND {counts['NOT_FOUND']}", flush=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="신규 후보 네이버 검증 워커")
    parser.add_argument("--all", action="store_true", help="커버리지 갭 포함 전체 (기본: 신규 오픈만)")
    parser.add_argument("--retry", action="store_true", help="재시도 풀 재크롤(NOT_FOUND+RETRY)")
    parser.add_argument("--max", type=int, default=None, dest="max_items")
    parser.add_argument("--interval", type=float, default=2.5)
    parser.add_argument("--cooldown-minutes", type=int, default=45)
    parser.add_argument("--max-cooldowns", type=int, default=8)
    # 큐 끝자락에 진짜 미등록이 몰리면 연속 무응답 한도가 차단으로 오탐해 쿨다운 루프에
    # 빠진다(08-17 실측: 잔여 41건 전부 무응답 → 쿨다운 3연속). 캡차 가드는 별도로 살아있다.
    parser.add_argument("--failure-limit", type=int, default=CONSECUTIVE_FAILURE_LIMIT)
    parser.add_argument("--candidates", help="후보 TSV 경로 (기본: matching/localdata_new_candidates.tsv)")
    parser.add_argument("--out", help="결과 JSONL 경로 (기본: matching/new_verify_results.jsonl)")
    args = parser.parse_args()
    global CANDIDATES, OUTPUT_PATH
    if args.candidates:
        CANDIDATES = Path(args.candidates)
    if args.out:
        OUTPUT_PATH = Path(args.out)
    return run(not (args.all or args.retry), args.max_items, args.interval,
               args.cooldown_minutes, args.max_cooldowns, args.failure_limit,
               retry=args.retry)


if __name__ == "__main__":
    sys.exit(main())
