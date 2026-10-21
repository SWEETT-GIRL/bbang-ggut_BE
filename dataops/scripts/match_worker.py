"""네이버 place_id 매칭 워커 (파일럿) — 미매칭 가게를 네이버 지도에서 검색해 후보를 수집한다.

- 검색은 Playwright(브라우저) 로 map.naver.com 을 열고 allSearch XHR 응답을 가로챈다
  (allSearch 직접 requests 호출은 캡차 토큰 부재로 빈 결과 — 2026-08-02 실측 확인)
- 판정 신호: 전화번호 일치 > 좌표 거리 > 이름 유사도 (매칭 트랙 설계 초안 §3)
- 운영 DB 에 아무것도 쓰지 않는다 — 결과는 로컬 JSONL 로만 남기고 검토 후 반영한다
- 재실행 시 이미 처리한 가게는 건너뛴다 (resume)

차단 대응 (실측: 연속 요청 ~5,300회/1,840회에서 소프트 차단):
- "후보 0건" 결과는 즉시 기록하지 않고 보류했다가 **다음 성공 응답이 확인된 뒤에만** 기록한다
  — 차단 구간의 가짜 NOT_FOUND 가 결과를 오염시키지 않게 하기 위함
- 차단 의심(연속 무응답·캡차) 시 종료하지 않고 브라우저를 재기동해 쿨다운 후 이어간다.
  쿨다운을 다 써도 차단이 풀리지 않으면 exit 3.

사용:
    python scripts/match_worker.py --input matching/unmatched_stores.tsv
    python scripts/match_worker.py --input matching/unmatched_stores.tsv --max 100 --interval 2.5
"""

import argparse
import difflib
import io
import json
import math
import os
import re
import sys
import time
import urllib.parse
from collections import deque

from playwright.sync_api import sync_playwright

OUTPUT_PATH = os.path.join("matching", "match_results.jsonl")
NAV_TIMEOUT_MS = 15000
SEARCH_WAIT_MS = 3000
CONSECUTIVE_FAILURE_LIMIT = 8

AUTO_MAX_DISTANCE_M = 50
AUTO_MIN_NAME_SIMILARITY = 0.6
REVIEW_MAX_DISTANCE_M = 150
REVIEW_MIN_NAME_SIMILARITY = 0.75


def normalize_name(name: str) -> str:
    return re.sub(r"[\s\W_]+", "", name or "").lower()


def name_similarity(a: str, b: str) -> float:
    na, nb = normalize_name(a), normalize_name(b)
    if not na or not nb:
        return 0.0
    if na in nb or nb in na:
        return max(0.8, difflib.SequenceMatcher(None, na, nb).ratio())
    return difflib.SequenceMatcher(None, na, nb).ratio()


def normalize_phone(phone: str) -> str:
    return re.sub(r"\D", "", phone or "")


def haversine_m(lon1, lat1, lon2, lat2) -> float:
    radius = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def load_stores(input_path: str) -> list[dict]:
    stores = []
    for line in io.open(input_path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 6:
            stores.append({
                "store_id": int(parts[0]), "name": parts[1], "phone": parts[2],
                "address": parts[3], "lon": float(parts[4]), "lat": float(parts[5]),
            })
    return stores


def load_done_ids() -> set:
    done = set()
    if os.path.exists(OUTPUT_PATH):
        for line in io.open(OUTPUT_PATH, encoding="utf-8"):
            try:
                done.add(json.loads(line)["storeId"])
            except (json.JSONDecodeError, KeyError):
                continue
    return done


def search_places(page, query: str) -> tuple[list, bool]:
    """map.naver.com 검색 → allSearch 응답의 place.list. returns (places, captcha_detected)"""
    captured = {"places": None, "captcha": False}

    def handle_response(response):
        if "allSearch" not in response.url:
            return
        try:
            data = response.json()
        except Exception:
            return
        result = data.get("result") or {}
        meta = result.get("metaInfo") or {}
        if "ncaptcha" in str(meta.get("pageId", "")):
            captured["captcha"] = True
        place = result.get("place") or {}
        captured["places"] = place.get("list") or []

    page.on("response", handle_response)
    try:
        page.goto("https://map.naver.com/p/search/" + urllib.parse.quote(query),
                  wait_until="domcontentloaded", timeout=NAV_TIMEOUT_MS)
        page.wait_for_timeout(SEARCH_WAIT_MS)
    except Exception:
        pass
    finally:
        page.remove_listener("response", handle_response)
    return captured["places"] if captured["places"] is not None else [], captured["captcha"]


def judge(store: dict, places: list) -> dict:
    best = None
    store_phone = normalize_phone(store["phone"])
    for place in places:
        try:
            lon, lat = float(place.get("x")), float(place.get("y"))
        except (TypeError, ValueError):
            continue
        distance = haversine_m(store["lon"], store["lat"], lon, lat)
        similarity = name_similarity(store["name"], place.get("name") or "")
        phone_matched = bool(store_phone) and store_phone == normalize_phone(place.get("tel") or "")
        score = (0 if phone_matched else 1, distance - similarity * 30)
        candidate = {
            "placeId": place.get("id"), "name": place.get("name"), "tel": place.get("tel"),
            "roadAddress": place.get("roadAddress") or place.get("address"),
            "distanceM": round(distance), "nameSimilarity": round(similarity, 3),
            "phoneMatched": phone_matched,
        }
        if best is None or score < best[0]:
            best = (score, candidate)

    if best is None:
        return {"judgment": "NOT_FOUND", "candidate": None}
    candidate = best[1]
    if candidate["phoneMatched"] or (
            candidate["distanceM"] <= AUTO_MAX_DISTANCE_M
            and candidate["nameSimilarity"] >= AUTO_MIN_NAME_SIMILARITY):
        return {"judgment": "AUTO", "candidate": candidate}
    if candidate["distanceM"] <= REVIEW_MAX_DISTANCE_M \
            or candidate["nameSimilarity"] >= REVIEW_MIN_NAME_SIMILARITY:
        return {"judgment": "REVIEW", "candidate": candidate}
    return {"judgment": "NOT_FOUND", "candidate": candidate}


def region_tokens(address: str) -> str:
    return " ".join((address or "").split()[:2])


def result_line(store: dict, verdict: dict, candidate_count: int) -> str:
    return json.dumps({
        "storeId": store["store_id"], "name": store["name"], "address": store["address"],
        "phone": store["phone"], "judgment": verdict["judgment"],
        "candidate": verdict["candidate"], "candidateCount": candidate_count,
    }, ensure_ascii=False) + "\n"


def run(input_path: str, max_stores: int | None, interval: float,
        cooldown_minutes: int, max_cooldowns: int) -> int:
    stores = load_stores(input_path)
    done_ids = load_done_ids()
    queue = deque(s for s in stores if s["store_id"] not in done_ids)
    print(f"[match] 대상 {len(queue)}곳 (전체 {len(stores)}, 완료 {len(done_ids)})", flush=True)

    counts = {"AUTO": 0, "REVIEW": 0, "NOT_FOUND": 0}
    pending_empty = []  # (store, line) — 후보 0건 보류분: 다음 성공 응답 후에만 진짜 NOT_FOUND 로 기록
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
            if max_stores is not None and attempts >= max_stores:
                break
            store = queue.popleft()
            attempts += 1

            places, captcha = search_places(page, f"{store['name']} {region_tokens(store['address'])}")
            if not places and not captcha:
                secondary, captcha = search_places(page, store["name"])
                places = places or secondary

            blocked = captcha or (not places and consecutive_failures + 1 >= CONSECUTIVE_FAILURE_LIMIT)
            if blocked:
                # 보류분·현재 가게를 큐 뒤로 되돌리고 (기록 없음) 쿨다운 후 재개
                queue.append(store)
                for pending_store, _ in pending_empty:
                    queue.append(pending_store)
                pending_empty.clear()
                consecutive_failures = 0
                cooldowns_used += 1
                if cooldowns_used > max_cooldowns:
                    print(f"[match] 쿨다운 {max_cooldowns}회 소진 — 종료. 기록 {written}", flush=True)
                    return 3
                print(f"[match] 차단 의심({'캡차' if captcha else '연속 무응답'}) — "
                      f"{cooldown_minutes}분 쿨다운 ({cooldowns_used}/{max_cooldowns}) 후 재개", flush=True)
                browser.close()
                time.sleep(cooldown_minutes * 60)
                browser, page = launch()
                continue

            verdict = judge(store, places)
            line = result_line(store, verdict, len(places))
            if places:
                consecutive_failures = 0
                for _, pending_line in pending_empty:
                    output.write(pending_line)
                    counts["NOT_FOUND"] += 1
                    written += 1
                pending_empty.clear()
                output.write(line)
                counts[verdict["judgment"]] += 1
                written += 1
                output.flush()
            else:
                consecutive_failures += 1
                pending_empty.append((store, line))

            if attempts % 25 == 0:
                print(f"[match] 시도 {attempts} / 기록 {written} — AUTO {counts['AUTO']} / "
                      f"REVIEW {counts['REVIEW']} / NOT_FOUND {counts['NOT_FOUND']}", flush=True)
            time.sleep(interval)

        # 세션 정상 종료 시 보류분은 진짜 후보 0건으로 확정 기록
        for _, pending_line in pending_empty:
            output.write(pending_line)
            counts["NOT_FOUND"] += 1
            written += 1
        browser.close()

    print(f"[match] 세션 종료 — 기록 {written}, AUTO {counts['AUTO']}, "
          f"REVIEW {counts['REVIEW']}, NOT_FOUND {counts['NOT_FOUND']}", flush=True)
    return 0


def main() -> int:
    argument_parser = argparse.ArgumentParser(description="네이버 place_id 매칭 워커 (파일럿)")
    argument_parser.add_argument("--input", required=True, help="미매칭 가게 TSV (id,name,phone,address,lon,lat)")
    argument_parser.add_argument("--max", type=int, default=None, dest="max_stores")
    argument_parser.add_argument("--interval", type=float, default=2.5, help="가게 간 대기 초")
    argument_parser.add_argument("--cooldown-minutes", type=int, default=45)
    argument_parser.add_argument("--max-cooldowns", type=int, default=8)
    arguments = argument_parser.parse_args()
    os.makedirs("matching", exist_ok=True)
    return run(arguments.input, arguments.max_stores, arguments.interval,
               arguments.cooldown_minutes, arguments.max_cooldowns)


if __name__ == "__main__":
    sys.exit(main())
