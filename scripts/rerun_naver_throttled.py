#!/usr/bin/env python3
"""현재 throttle 된 네이버 enrich 실패 건만 다시 실행한다.

일반 큐(--loop)와 달리 실행 시작 시점의 /naver-throttled 목록을 고정해 순회하므로,
새로 들어온 미시도 가게가 섞이지 않는다. 각 store 는 처리 직전에 retry API 로 throttle 을
해제한 뒤 --store-id 모드와 같은 단일 item shape 으로 처리한다.
"""

import argparse
import contextlib
import json
import random
import re
import sys
import time
from pathlib import Path
from typing import Iterator

import requests
from playwright.sync_api import sync_playwright

from naver_menu_scraper import (
	DEFAULT_SERVER,
	INTER_STORE_DELAY_SEC,
	INTER_STORE_JITTER_SEC,
	NAVER_UA,
	NO_CANDIDATE_ABORT_THRESHOLD,
	fetch_single_store,
	process_store,
)


def fetch_throttled(server: str, size: int) -> list[dict]:
	url = f"{server}/api/admin/stores/naver-throttled?page=0&size={size}"
	resp = requests.get(url, timeout=30)
	resp.raise_for_status()
	return resp.json().get("data", {}).get("content", []) or []


def retry_store(server: str, store_id: int) -> None:
	url = f"{server}/api/admin/stores/{store_id}/naver-enrich-retry"
	resp = requests.post(url, timeout=15)
	resp.raise_for_status()


def extract_started_store_ids(log_paths: list[str]) -> set[int]:
	if not log_paths:
		return set()
	pattern = re.compile(r"^=== \[\d+/\d+\] store_id=(\d+) ")
	store_ids: set[int] = set()
	for log_path in log_paths:
		store_ids |= _extract_started_store_ids_from_one_log(log_path, pattern)
	return store_ids


def _extract_started_store_ids_from_one_log(log_path: str, pattern: re.Pattern) -> set[int]:
	path = Path(log_path)
	if not path.exists():
		return set()
	store_ids: set[int] = set()
	for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
		m = pattern.match(line)
		if m:
			store_ids.add(int(m.group(1)))
	return store_ids


@contextlib.contextmanager
def redirected_stdout(log_path: Path) -> Iterator[None]:
	log_path.parent.mkdir(parents=True, exist_ok=True)
	with log_path.open("a", encoding="utf-8") as log:
		with contextlib.redirect_stdout(log):
			yield


def main() -> None:
	parser = argparse.ArgumentParser(description="현재 throttled 네이버 실패 건 재실행")
	parser.add_argument("--server", default=DEFAULT_SERVER)
	parser.add_argument("--size", type=int, default=1000, help="throttled 목록 fetch size")
	parser.add_argument("--limit", type=int, default=0, help="앞에서 N건만 처리(0=전체)")
	parser.add_argument("--dry-run", action="store_true", help="저장 없이 매칭만 시뮬레이션")
	parser.add_argument("--verbose", action="store_true", help="후보 상세 로그까지 남김")
	parser.add_argument("--no-reset-throttle", action="store_true", help="처리 전 retry API 호출 생략")
	parser.add_argument("--log-file", default="/tmp/naver-rerun-throttled.log")
	parser.add_argument(
		"--exclude-log",
		action="append",
		default=[],
		help="이미 시작된 store_id 를 제외할 이전 로그. 여러 번 지정 가능",
	)
	args = parser.parse_args()

	log_path = Path(args.log_file)
	log_path.write_text("", encoding="utf-8")

	items = fetch_throttled(args.server, args.size)
	excluded_ids = extract_started_store_ids(args.exclude_log)
	if excluded_ids:
		items = [item for item in items if int(item["id"]) not in excluded_ids]
	if args.limit:
		items = items[:args.limit]

	print(f"서버: {args.server}")
	print(f"throttled 재실행 대상: {len(items)}건")
	if excluded_ids:
		print(f"이전 로그 제외: {len(excluded_ids)}건 ({', '.join(args.exclude_log)})")
	print(f"상세 로그: {log_path}")
	if not items:
		return

	total = {
		"matched": 0,
		"menus_created": 0,
		"images_uploaded": 0,
		"skipped_menu_crawl": 0,
		"photos_uploaded": 0,
		"skipped_photo_crawl": 0,
		"failed": 0,
		"no_candidates": 0,
		"blocked": 0,
		"fetch_failed": 0,
		"retry_failed": 0,
	}

	with sync_playwright() as p:
		browser = p.chromium.launch(
			headless=True,
			args=["--disable-blink-features=AutomationControlled"],
		)
		context = browser.new_context(user_agent=NAVER_UA, viewport={"width": 1280, "height": 800})
		context.add_init_script(
			"Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
		)
		page = context.new_page()

		blocked_streak = 0
		try:
			for idx, item in enumerate(items, 1):
				store_id = int(item["id"])
				name = item.get("name") or ""

				if not args.dry_run and not args.no_reset_throttle:
					try:
						retry_store(args.server, store_id)
					except Exception as e:
						total["retry_failed"] += 1
						total["failed"] += 1
						print(f"[{idx:3d}/{len(items)}] RETRY_FAIL id={store_id} {name}: {e}")
						continue

				store = fetch_single_store(args.server, store_id)
				if not store:
					total["fetch_failed"] += 1
					total["failed"] += 1
					print(f"[{idx:3d}/{len(items)}] FETCH_FAIL id={store_id} {name}")
					continue

				with log_path.open("a", encoding="utf-8") as log:
					log.write(f"\n\n=== [{idx}/{len(items)}] store_id={store_id} {name} ===\n")
				with redirected_stdout(log_path):
					result = process_store(
						page,
						store,
						args.server,
						args.dry_run,
						args.verbose,
						crawl_menus=True,
					)

				for key in ("matched", "menus_created", "images_uploaded", "skipped_menu_crawl",
						"photos_uploaded", "skipped_photo_crawl", "failed"):
					total[key] += result.get(key, 0)
				if result.get("no_candidates"):
					total["no_candidates"] += 1
				if result.get("blocked"):
					total["blocked"] += 1
					blocked_streak += 1
				else:
					blocked_streak = 0

				if result.get("matched"):
					status = "MATCH"
				elif result.get("blocked"):
					status = "BLOCKED"
				elif result.get("no_candidates"):
					status = "NO_CAND"
				else:
					status = "FAIL"

				print(
					f"[{idx:3d}/{len(items)}] {status:8s} id={store_id} {name[:26]} "
					f"matched={total['matched']} failed={total['failed']} "
					f"menus={total['menus_created']} photos={total['photos_uploaded']}",
					flush=True,
				)

				if blocked_streak >= NO_CANDIDATE_ABORT_THRESHOLD:
					print(f"[ABORT] 차단 신호 {blocked_streak}건 연속 — 상세 로그 확인: {log_path}")
					break

				time.sleep(INTER_STORE_DELAY_SEC + random.uniform(0, INTER_STORE_JITTER_SEC))
		finally:
			browser.close()

	print("\n" + "=" * 60)
	print(json.dumps(total, ensure_ascii=False, indent=2))


if __name__ == "__main__":
	try:
		main()
	except KeyboardInterrupt:
		print("\n[중단] KeyboardInterrupt")
		sys.exit(130)
