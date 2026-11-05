#!/usr/bin/env python3
"""네이버 역방향 탐색 — 지역별 '베이커리' 검색으로 우리 DB 에 없는 빵집을 찾는다.

왜 (2026-09-20)
- 지금 유입은 인허가에서 시작한다: LOCALDATA → 네이버 검증 → 등록. 그런데 휴게음식점은 상호 키워드로만 수집했고,
  키워드 없는 카페형 빵집(실사례: 오그레이)은 영영 안 들어온다.
- 표본 200곳 실측(09-20): 우리가 모르는 카페형 인허가 95,486곳 중 메뉴까지 보면 빵집다운 곳이 3.5~10%다
  (엄격 기준 약 3,300곳 · 느슨 기준 약 9,500곳). 인허가 전수 검증은 한 곳 11초라 290시간이 들어 불가능하다.
- 그래서 방향을 뒤집는다. 네이버 지도에서 지역 × '베이커리' 로 검색해 나온 가게 중 우리 DB 에 없는 place id 를 찾는다.
  카테고리가 '카페,디저트' 인 빵집도 검색어로는 걸린다.

--deep: 결과 목록의 페이지 버튼을 눌러 21번째 이후도 받는다 (2026-09-23 추가)
- 1쪽은 allSearch 응답(20개), 2쪽부터는 목록이 pcmap-api.place.naver.com/graphql 로 받아오므로 그것도 가로챈다.
- 왜 필요한가: 09-21 스윕은 검색의 80.9% 가 20개 한도에 꽉 차 있었다. 연남동 '베이커리' 는 totalCount 가 380인데
  20곳만 봤다. 페이지 2~5 를 더 보니 230곳이 더 나왔고, 그중 **48곳은 11,259번 스윕 전체가 한 번도 못 본 가게**였다
  (허밍벨라·콘하스 연남점·앤트러사이트 서교점 …). 뒷페이지는 대체로 이웃 동이지만, 그 동의 1쪽도 잘려 있어서
  합집합에 구멍이 남는다.
- API 를 직접 부르지 않는다. allSearch 토큰은 1회용이라 재요청하면 ncaptcha(CE_TOKEN_REUSE)로 막힌다 —
  사람이 하듯 목록의 페이지 버튼을 누르고 그 응답을 읽는다.

한계
- --deep 없이는 검색 결과가 상위 20개로 잘린다. 큰 동네는 다 못 본다 — 그래서 쿼리를 여러 개(--queries) 돌린다.
- 찾은 가게를 등록하려면 인허가(mgmt_no)가 필요하다. 이 스크립트는 명단까지만 만든다 — 인허가 대조는 뒤 단계.

출력: matching/naver_bakery_sweep_{today}.jsonl (검색 결과 원본 + 우리 DB 존재 여부)
      재개 가능 — 이미 끝난 (지역, 쿼리) 는 건너뛴다.

사용
    python scripts/naver_bakery_sweep.py --max-regions 5            # 시험
    python scripts/naver_bakery_sweep.py                            # 전체 (읍면동 × 쿼리)
    python scripts/naver_bakery_sweep.py --queries 베이커리 빵집 디저트
"""
import argparse
import datetime as dt
import io
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from match_worker import CONSECUTIVE_FAILURE_LIMIT, search_places  # noqa: E402
from cloud_runtime import serving_csv as prod_csv  # noqa: E402
from bakery_gate import category_pass, leaf_of  # noqa: E402

from playwright.sync_api import sync_playwright  # noqa: E402

from cloud_runtime import matching_dir

ROOT = Path(__file__).resolve().parent.parent
MATCHING = matching_dir()
DEFAULT_OUT = MATCHING / f"naver_bakery_sweep_{dt.date.today():%Y%m%d}.jsonl"
DEFAULT_QUERIES = ["베이커리"]
# 지역 목록과 우리 place id 는 운영에서 한 번만 읽고 캐시한다 — 터널이 끊겨도(이동·재부팅) 이어서 돌 수 있게
CACHE = MATCHING / "naver_sweep_cache.json"


MAX_PAGE = 5            # 목록에 페이지 버튼이 5개까지 보인다. '다음페이지' 는 클릭이 안 먹어 쓰지 않는다
PAGE_WAIT_MS = 3500     # 페이지 전환 뒤 graphql 응답을 기다리는 시간


def deep_items(page, first_items):
    """결과 목록의 페이지 버튼을 눌러 2쪽부터 모은다 — 1쪽(first_items)에 이어 붙인다.

    2쪽부터는 pcmap-api.place.naver.com/graphql 이 목록을 준다. 응답 모양이 allSearch 와 달라서
    필요한 필드만 같은 이름으로 맞춘다(id·name·category·roadAddress·address).
    """
    buf = []

    def on_resp(resp):
        if "pcmap-api.place.naver.com/graphql" not in resp.url or resp.request.method != "POST":
            return
        try:
            data = resp.json()
        except Exception:
            return
        for one in (data if isinstance(data, list) else [data]):
            for _, val in ((one or {}).get("data") or {}).items():
                if isinstance(val, dict):
                    for x in ((val.get("businesses") or {}).get("items") or []):
                        buf.append({"id": x.get("id"), "name": x.get("name"), "category": x.get("category"),
                                    "roadAddress": x.get("roadAddress"), "address": x.get("commonAddress") or x.get("address")})

    frames = [f for f in page.frames if "searchIframe" in (f.name or "")]
    if not frames:
        return first_items
    frame = frames[0]
    page.on("response", on_resp)
    out = list(first_items)
    try:
        for want in range(2, MAX_PAGE + 1):
            loc = frame.locator("a.mBN2s").filter(has_text=re.compile(rf"^{want}$"))
            if not loc.count():
                break
            buf.clear()
            try:
                loc.first.click(timeout=8000)
            except Exception:
                break
            page.wait_for_timeout(PAGE_WAIT_MS)
            if not buf:
                break
            out.extend(buf)
    finally:
        page.remove_listener("response", on_resp)
    return out


def load_cache(env_path, refresh):
    """지역 목록 · 우리 place id — 캐시가 있으면 그걸 쓴다(운영 터널 불필요)"""
    if CACHE.exists() and not refresh:
        data = json.loads(CACHE.read_text(encoding="utf-8"))
        print(f"[sweep] 캐시 사용 — 지역 {len(data['regions']):,} · place id {len(data['known']):,} "
              f"({data.get('cached_at', '?')})", flush=True)
        return data["regions"], set(data["known"])
    regions = load_regions(env_path)
    known = sorted(load_known_place_ids(env_path))
    CACHE.write_text(json.dumps({"cached_at": dt.datetime.now().isoformat(timespec="seconds"),
                                 "regions": regions, "known": known}, ensure_ascii=False), encoding="utf-8")
    print(f"[sweep] 운영에서 읽어 캐시 저장 — 지역 {len(regions):,} · place id {len(known):,}", flush=True)
    return regions, set(known)


def load_regions(env_path):
    """운영 store 의 region 3분류 — 우리 가게가 한 곳이라도 있는 지역을 돈다(빵집이 아예 없는 면·리는 건너뛴다)"""
    rows = prod_csv(
        "SELECT region_1st, region_2nd, region_3rd, count(*) AS n FROM store WHERE status = 'ACTIVE' "
        "AND region_2nd <> '' GROUP BY 1, 2, 3 ORDER BY 4 DESC;", env_path)
    regions = []
    for r in rows:
        name = " ".join(x for x in (r["region_1st"], r["region_2nd"], r["region_3rd"]) if x).strip()
        if name:
            regions.append({"region": name, "stores": int(r["n"])})
    return regions


def load_known_place_ids(env_path):
    rows = prod_csv("SELECT naver_place_id FROM store WHERE naver_place_id IS NOT NULL AND naver_place_id <> '';", env_path)
    return {r["naver_place_id"] for r in rows}


def done_keys(out_path):
    done = set()
    if out_path.exists():
        for line in io.open(out_path, encoding="utf-8"):
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            done.add((r["region"], r["query"]))
    return done


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", default=str(ROOT / ".env"))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--queries", nargs="+", default=DEFAULT_QUERIES)
    ap.add_argument("--max-regions", type=int, default=None, help="시험용 — 가게 많은 지역부터 N 개만")
    ap.add_argument("--refresh-cache", action="store_true", help="지역·place id 캐시를 운영에서 다시 읽는다")
    ap.add_argument("--deep", action="store_true", help="목록의 페이지 버튼을 눌러 21번째 이후도 받는다 (검색당 5배 느리다)")
    ap.add_argument("--interval", type=float, default=2.5)
    ap.add_argument("--cooldown-minutes", type=int, default=45)
    ap.add_argument("--max-cooldowns", type=int, default=6)
    args = ap.parse_args()

    out_path = Path(args.out)
    regions, known = load_cache(args.env, args.refresh_cache)
    if args.max_regions:
        regions = regions[:args.max_regions]
    done = done_keys(out_path)
    todo = [(r, q) for r in regions for q in args.queries if (r["region"], q) not in done]
    print(f"[sweep] 지역 {len(regions):,} × 쿼리 {len(args.queries)} → 남은 검색 {len(todo):,} "
          f"(완료 {len(done):,}) · 우리 place id {len(known):,}", flush=True)

    found = new_places = 0
    unresolved = 0
    consecutive_failures = cooldowns = 0
    with sync_playwright() as pw, io.open(out_path, "a", encoding="utf-8") as out:
        def launch():
            browser = pw.chromium.launch(headless=True)
            context = browser.new_context(locale="ko-KR")
            context.route(re.compile(r"\.(png|jpg|jpeg|gif|webp|woff2?|mp4|svg)"), lambda route: route.abort())
            return browser, context.new_page()

        browser, page = launch()
        for i, (region, query) in enumerate(todo, 1):
            places, captcha = search_places(page, f"{region['region']} {query}")
            # 1쪽이 꽉 찼을 때만 더 본다 — 20개 미만이면 뒤에 아무것도 없다
            if args.deep and not captcha and len(places) >= 20:
                places = deep_items(page, places)
            if captcha or (not places and consecutive_failures + 1 >= CONSECUTIVE_FAILURE_LIMIT):
                cooldowns += 1
                if cooldowns > args.max_cooldowns:
                    print(f"[sweep] 쿨다운 {args.max_cooldowns}회 소진 — 종료 (검색 {i - 1})", flush=True)
                    return 3
                print(f"[sweep] 차단 의심({'캡차' if captcha else '연속 무응답'}) — {args.cooldown_minutes}분 쉼", flush=True)
                browser.close()
                time.sleep(args.cooldown_minutes * 60)
                browser, page = launch()
                consecutive_failures = 0
                unresolved += 1
                continue
            consecutive_failures = 0 if places else consecutive_failures + 1
            items = []
            seen_ids = set()
            for p in places:
                category = p.get("category")
                if isinstance(category, list):
                    category = ",".join(str(c) for c in category)
                pid = str(p.get("id") or "")
                if not pid or pid in seen_ids:
                    continue
                seen_ids.add(pid)
                items.append({"placeId": pid, "name": p.get("name"), "category": category,
                              "address": p.get("roadAddress") or p.get("address"),
                              "known": pid in known, "categoryPass": category_pass(leaf_of(category))})
            out.write(json.dumps({"region": region["region"], "query": query, "storesHere": region["stores"],
                                  "count": len(items), "items": items}, ensure_ascii=False) + "\n")
            out.flush()
            found += len(items)
            new_places += sum(1 for x in items if not x["known"])
            if i % 25 == 0 or i == len(todo):
                print(f"[sweep] {i}/{len(todo)} — 결과 {found:,} · 우리 DB 에 없는 곳 {new_places:,}", flush=True)
            time.sleep(args.interval)
        browser.close()
    print(f"[sweep] 끝 — 검색 {len(todo):,} · 결과 {found:,} · 우리 DB 에 없는 곳 {new_places:,} → {out_path}", flush=True)
    return 4 if unresolved else 0


if __name__ == "__main__":
    sys.exit(main())
