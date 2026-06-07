#!/usr/bin/env python3
"""저장된 네이버 실패 후보 중 확실한 매칭을 바로 확정하고 enrich 한다.

검색을 다시 돌리지 않고 NaverEnrichCandidate 를 직접 재평가한다. 이름/주소가 같은데
기존 게이트가 보수적으로 실패시킨 케이스를 운영자가 후보 UI 에서 수동 선택하지 않도록 줄이는 용도.
"""

import argparse
import contextlib
import json
import re
import time
from pathlib import Path
from typing import Iterator

import requests
from playwright.sync_api import sync_playwright

from naver_match import (
	branch_conflict,
	extract_road_and_number,
	is_closed,
	name_match_kind,
	names_related_for_same_address,
	names_same_ignoring_generic,
	normalize_name,
	select_match,
)
from naver_menu_scraper import (
	DEFAULT_SERVER,
	INTER_STORE_DELAY_SEC,
	NAVER_UA,
	fetch_single_store,
	process_store,
)
from rerun_naver_throttled import extract_started_store_ids


def fetch_throttled(server: str, size: int) -> list[dict]:
	url = f"{server}/api/admin/stores/naver-throttled"
	resp = requests.get(url, params={"page": 0, "size": size}, timeout=30)
	resp.raise_for_status()
	return resp.json().get("data", {}).get("content", []) or []


def fetch_candidates(server: str, store_id: int) -> list[dict]:
	url = f"{server}/api/admin/stores/{store_id}/naver-enrich-candidates"
	resp = requests.get(url, timeout=15)
	resp.raise_for_status()
	return resp.json().get("data") or []


def update_naver_place_id(server: str, store_id: int, cand: dict) -> tuple[bool, str]:
	url = f"{server}/api/admin/stores/{store_id}/naver-place-id"
	payload = {
		"naverPlaceId": str(cand["id"]),
		"replaceMenus": False,
		"address": cand.get("address"),
	}
	if cand.get("y") is not None and cand.get("x") is not None:
		payload["coord"] = {"latitude": cand["y"], "longitude": cand["x"]}
	resp = requests.put(url, json=payload, timeout=15)
	if resp.status_code == 409:
		return False, "DUPLICATE"
	if not resp.ok:
		return False, f"HTTP_{resp.status_code}"
	return True, "OK"


def candidate_to_naver(raw: dict) -> dict:
	return {
		"id": str(raw.get("naverPlaceId") or ""),
		"name": raw.get("name") or "",
		"address": raw.get("address") or "",
		"roadAddress": raw.get("address") or "",
		"y": raw.get("lat"),
		"x": raw.get("lng"),
		"displayOrder": raw.get("displayOrder"),
	}


def same_road_number(store: dict, cand: dict) -> bool:
	return extract_road_and_number(store.get("address")) == extract_road_and_number(cand.get("address"))


def same_region_prefix(store: dict, cand: dict) -> bool:
	store_tokens = (store.get("address") or "").split()
	cand_tokens = (cand.get("address") or "").split()
	if len(store_tokens) < 2 or len(cand_tokens) < 2:
		return False
	return store_tokens[0] == cand_tokens[0] and store_tokens[1] == cand_tokens[1]


def confident_same_address(store: dict, cand: dict) -> tuple[bool, str]:
	store_name = store.get("name") or ""
	cand_name = cand.get("name") or ""
	if is_closed(cand):
		return False, "closed"
	if not same_road_number(store, cand):
		return False, "road_mismatch"
	if not same_region_prefix(store, cand):
		return False, "region_mismatch"

	kind = name_match_kind(normalize_name(store_name), normalize_name(cand_name))
	related = names_related_for_same_address(store_name, cand_name)
	generic_same = names_same_ignoring_generic(store_name, cand_name)
	if kind == "exact":
		return True, "same_road_name_exact"
	if generic_same:
		return True, "same_road_generic_same"
	if related:
		return True, f"same_road_name_related:{kind}"
	if branch_conflict(store_name, cand_name):
		return False, "branch_conflict"
	return False, f"name_unrelated:{kind}"


def choose_candidate(store: dict, candidates: list[dict]) -> tuple[dict | None, str]:
	naver_candidates = [candidate_to_naver(c) for c in candidates]
	chosen, _, reason = select_match(store, naver_candidates)
	if chosen is not None:
		return chosen, f"select_match:{reason}"

	for cand in sorted(naver_candidates, key=lambda c: c.get("displayOrder") or 0):
		ok, why = confident_same_address(store, cand)
		if ok:
			return cand, why
	return None, reason


@contextlib.contextmanager
def redirected_stdout(log_path: Path) -> Iterator[None]:
	log_path.parent.mkdir(parents=True, exist_ok=True)
	with log_path.open("a", encoding="utf-8") as log:
		with contextlib.redirect_stdout(log):
			yield


def main() -> None:
	parser = argparse.ArgumentParser(description="저장 실패 후보 중 확실한 매칭 확정")
	parser.add_argument("--server", default=DEFAULT_SERVER)
	parser.add_argument("--size", type=int, default=10000)
	parser.add_argument("--limit", type=int, default=0)
	parser.add_argument("--dry-run", action="store_true")
	parser.add_argument("--no-enrich", action="store_true", help="placeId 만 확정하고 메뉴/사진 보강은 건너뜀")
	parser.add_argument("--log-file", default="/tmp/naver-apply-failed-candidates.log")
	parser.add_argument("--exclude-log", action="append", default=[])
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
	print(f"저장 후보 검수 대상: {len(items)}건")
	print(f"상세 로그: {log_path}")

	total = {
		"applied": 0,
		"enriched": 0,
		"skipped": 0,
		"duplicate": 0,
		"fetch_failed": 0,
		"candidate_failed": 0,
		"update_failed": 0,
	}

	with sync_playwright() as p:
		browser = p.chromium.launch(
			headless=True,
			args=["--disable-blink-features=AutomationControlled"],
		)
		context = browser.new_context(user_agent=NAVER_UA, viewport={"width": 1280, "height": 800})
		context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})")
		page = context.new_page()

		try:
			for idx, item in enumerate(items, 1):
				store_id = int(item["id"])
				store = fetch_single_store(args.server, store_id)
				if not store:
					total["fetch_failed"] += 1
					print(f"[{idx}/{len(items)}] FETCH_FAIL id={store_id}")
					continue
				if store.get("naverPlaceId"):
					total["skipped"] += 1
					print(f"[{idx}/{len(items)}] SKIP_MATCHED id={store_id} {store.get('name')}")
					continue
				try:
					candidates = fetch_candidates(args.server, store_id)
				except Exception as e:
					total["candidate_failed"] += 1
					print(f"[{idx}/{len(items)}] CAND_FAIL id={store_id}: {e}")
					continue
				chosen, why = choose_candidate(store, candidates)
				if not chosen:
					total["skipped"] += 1
					print(f"[{idx}/{len(items)}] SKIP id={store_id} {store.get('name')} reason={why}")
					continue

				print(
					f"[{idx}/{len(items)}] APPLY id={store_id} {store.get('name')} -> "
					f"{chosen.get('id')} {chosen.get('name')} ({why})",
					flush=True,
				)
				if args.dry_run:
					total["applied"] += 1
					continue
				ok, status = update_naver_place_id(args.server, store_id, chosen)
				if not ok:
					if status == "DUPLICATE":
						total["duplicate"] += 1
					else:
						total["update_failed"] += 1
					print(f"  UPDATE_FAIL {status}")
					continue

				total["applied"] += 1
				if args.no_enrich:
					continue
				store_after = fetch_single_store(args.server, store_id)
				if store_after:
					with redirected_stdout(log_path):
						process_store(page, store_after, args.server, False, False, crawl_menus=True)
					total["enriched"] += 1
				time.sleep(INTER_STORE_DELAY_SEC)
		finally:
			browser.close()

	print(json.dumps(total, ensure_ascii=False, indent=2))


if __name__ == "__main__":
	main()
