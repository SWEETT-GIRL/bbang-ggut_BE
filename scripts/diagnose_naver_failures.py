"""네이버 매칭 실패 진단 dry-run.

실패로 큐에 쌓인 가게를 검색+매칭만 **시뮬레이션**해 어느 단계에서 왜 실패하는지
버킷으로 분류한다. 서버에 **아무것도 쓰지 않는다** (완전 read-only).

버킷:
  - SEARCH_EMPTY  : 네이버 검색 결과 0건 → 검색어 문제 (A)
  - WOULD_MATCH   : 지금 돌리면 매칭됨 → 과거 flaky 실패 (재시도 시 해결)
  - GATE_REJECT   : 후보는 떴으나 점수/게이트가 거부 → 매칭 로직 문제 (B)

실행 예:
  cd scripts && source .venv/bin/activate
  python diagnose_naver_failures.py                 # 큐에서 40건 진단
  python diagnose_naver_failures.py --limit 80
  python diagnose_naver_failures.py --ids 2916,2912,2911
  python diagnose_naver_failures.py --show-browser

결과: 콘솔 히스토그램 + scripts/naver_failure_diagnosis.csv
"""

import argparse
import csv
import random
import sys
import time
from collections import Counter
from typing import Optional

from playwright.sync_api import sync_playwright

from naver_match import classify_store, select_match
from naver_menu_scraper import (
	DEFAULT_SERVER,
	INTER_STORE_DELAY_SEC,
	INTER_STORE_JITTER_SEC,
	NAVER_UA,
	NO_CANDIDATE_ABORT_THRESHOLD,
	fetch_queue,
	fetch_single_store,
	search_naver_candidates,
)

CSV_PATH = "naver_failure_diagnosis.csv"
CSV_FIELDS = [
	"store_id", "store_name", "store_address", "bucket", "reason",
	"num_candidates", "top_cand_name", "top_score", "name_kind",
	"distance_m", "road_match",
]
DEFAULT_LIMIT = 40


def _load_stores(server: str, ids: Optional[list[int]], limit: int) -> list[dict]:
	"""진단 대상 store 리스트 확보. --ids 우선, 없으면 큐에서."""
	if ids:
		stores = []
		for sid in ids:
			s = fetch_single_store(server, sid)
			if s:
				stores.append(s)
		return stores[:limit]
	# 큐 = 현재 실패 가게들. limit 만큼만 fetch.
	return fetch_queue(server, limit)


def _diagnose_one(page, store: dict) -> dict:
	"""한 가게 검색+매칭 시뮬레이션 → classify_store 결과 + store 식별자."""
	candidates = search_naver_candidates(page, store["name"], store.get("address"))
	matched, scored, reason = select_match(store, candidates)
	result = classify_store(candidates, matched, reason, scored)
	result["store_id"] = store["id"]
	result["store_name"] = store["name"]
	result["store_address"] = store.get("address") or ""
	return result


def _print_line(r: dict) -> None:
	if r["bucket"] == "SEARCH_EMPTY":
		print(f"  [SEARCH_EMPTY] {r['store_name']} — 검색 결과 0건")
		return
	dist = f"{r['distance_m']:.0f}m" if r["distance_m"] is not None else "-"
	road = "road1" if r["road_match"] else "road0"
	print(
		f"  [{r['bucket']}] {r['store_name']} → top '{r['top_cand_name']}'"
		f" ({r['top_score']}점/{r['name_kind']}/{dist}/{road})"
		f"{'  · ' + r['reason'] if r['bucket'] == 'GATE_REJECT' else ''}"
	)


def _write_csv(rows: list[dict]) -> None:
	with open(CSV_PATH, "w", encoding="utf-8-sig", newline="") as f:
		writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
		writer.writeheader()
		for r in rows:
			writer.writerow({k: r.get(k) for k in CSV_FIELDS})


def _print_histogram(rows: list[dict]) -> None:
	buckets = Counter(r["bucket"] for r in rows)
	print(f"\n{'=' * 60}")
	print(f"진단 완료 — 총 {len(rows)}건")
	for bucket in ("SEARCH_EMPTY", "GATE_REJECT", "WOULD_MATCH"):
		n = buckets.get(bucket, 0)
		if n:
			print(f"  {bucket:14s}: {n}")
	# GATE_REJECT 는 사유별로 한 번 더 쪼갬
	gate_reasons = Counter(
		r["reason"] for r in rows if r["bucket"] == "GATE_REJECT"
	)
	if gate_reasons:
		print("\n  GATE_REJECT 사유별:")
		for reason, n in gate_reasons.most_common():
			print(f"    {n:3d}× {reason}")
	print(f"\n  → CSV: scripts/{CSV_PATH}")


def main() -> None:
	parser = argparse.ArgumentParser(description="네이버 매칭 실패 진단 (read-only)")
	parser.add_argument("--server", default=DEFAULT_SERVER, help=f"백엔드 (기본: {DEFAULT_SERVER})")
	parser.add_argument("--ids", default="", help="진단할 store id 목록 (콤마 구분). 없으면 큐에서 fetch")
	parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT,
		help=f"처리 상한 — 네이버 차단 방지 (기본 {DEFAULT_LIMIT})")
	parser.add_argument("--headless", action="store_true", default=True, help="브라우저 창 숨김 (기본 ON)")
	parser.add_argument("--show-browser", dest="headless", action="store_false", help="브라우저 창 표시")
	args = parser.parse_args()

	ids = [int(x) for x in args.ids.split(",") if x.strip()] if args.ids else None

	print(f"서버: {args.server}")
	stores = _load_stores(args.server, ids, args.limit)
	if not stores:
		print("[종료] 진단 대상 가게 없음 (큐 비었거나 fetch 실패)")
		sys.exit(2)
	print(f"진단 대상: {len(stores)}건 (read-only — 서버에 쓰지 않음)\n")

	rows: list[dict] = []
	empty_streak = 0
	with sync_playwright() as p:
		browser = p.chromium.launch(
			headless=args.headless,
			args=["--disable-blink-features=AutomationControlled"],
		)
		context = browser.new_context(user_agent=NAVER_UA, viewport={"width": 1280, "height": 800})
		context.add_init_script(
			"Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
		)
		page = context.new_page()

		for store in stores:
			print(f"\n[가게] id={store['id']} | {store['name']}")
			r = _diagnose_one(page, store)
			_print_line(r)
			rows.append(r)

			# 네이버 차단 의심 — 연속 검색 0건이 임계 넘으면 abort (남은 가게는 미진단)
			if r["bucket"] == "SEARCH_EMPTY":
				empty_streak += 1
				if empty_streak >= NO_CANDIDATE_ABORT_THRESHOLD:
					print(f"\n[ABORT] {empty_streak}건 연속 검색 결과 0 — 네이버 차단 의심. 부분 결과만 기록.")
					break
			else:
				empty_streak = 0
			time.sleep(INTER_STORE_DELAY_SEC + random.uniform(0, INTER_STORE_JITTER_SEC))

		browser.close()

	_write_csv(rows)
	_print_histogram(rows)


if __name__ == "__main__":
	main()
