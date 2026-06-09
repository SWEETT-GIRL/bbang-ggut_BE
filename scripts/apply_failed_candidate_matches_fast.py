#!/usr/bin/env python3
"""Fast review/apply for throttled Naver candidates.

Reads stores and saved candidates directly from the local DB config, evaluates them
with the same matcher used by the crawler, and optionally applies selected matches
through the backend API.
"""

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Optional

import psycopg2
import requests

from apply_failed_candidate_matches import confident_same_address
from naver_match import select_match
from naver_menu_scraper import DEFAULT_SERVER


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / "src/main/resources/application-local.properties"
DEFAULT_CSV = ROOT / "scripts/failed_candidate_fast_review.csv"


def load_props(path: Path) -> dict[str, str]:
	props: dict[str, str] = {}
	for raw in path.read_text(encoding="utf-8").splitlines():
		line = raw.strip()
		if not line or line.startswith("#") or "=" not in line:
			continue
		key, value = line.split("=", 1)
		props[key.strip()] = value.strip()
	return props


def db_connect(config_path: Path):
	props = load_props(config_path)
	url = props["spring.datasource.url"]
	rest = url.split("//", 1)[1]
	host_port, db_name = rest.split("/", 1)
	if "?" in db_name:
		db_name = db_name.split("?", 1)[0]
	if ":" in host_port:
		host, port = host_port.split(":", 1)
	else:
		host, port = host_port, "5432"
	return psycopg2.connect(
		host=host,
		port=int(port),
		dbname=db_name,
		user=props["spring.datasource.username"],
		password=props["spring.datasource.password"],
		connect_timeout=10,
	)


def fetch_rows(conn) -> tuple[list[dict], dict[int, list[dict]]]:
	with conn.cursor() as cur:
		cur.execute(
			"""
			SELECT
				id,
				name,
				address,
				ST_Y(location::geometry) AS lat,
				ST_X(location::geometry) AS lng
			FROM store
			WHERE status = 'ACTIVE'
			  AND naver_place_id IS NULL
			  AND naver_matched_at IS NOT NULL
			  AND naver_matched_at >= now() - interval '7 days'
			ORDER BY naver_matched_at DESC
			"""
		)
		stores = [
			{"id": row[0], "name": row[1], "address": row[2], "lat": row[3], "lng": row[4]}
			for row in cur.fetchall()
		]
		if not stores:
			return [], {}
		store_ids = [s["id"] for s in stores]
		cur.execute(
			"""
			SELECT store_id, naver_place_id, name, address, lat, lng, display_order
			FROM naver_enrich_candidate
			WHERE store_id = ANY(%s)
			ORDER BY store_id, display_order
			""",
			(store_ids,),
		)
		candidates: dict[int, list[dict]] = defaultdict(list)
		for row in cur.fetchall():
			candidates[row[0]].append(
				{
					"naverPlaceId": row[1],
					"name": row[2],
					"address": row[3],
					"lat": row[4],
					"lng": row[5],
					"displayOrder": row[6],
				}
			)
		return stores, candidates


def to_naver(raw: dict) -> dict:
	return {
		"id": str(raw.get("naverPlaceId") or ""),
		"name": raw.get("name") or "",
		"address": raw.get("address") or "",
		"roadAddress": raw.get("address") or "",
		"y": raw.get("lat"),
		"x": raw.get("lng"),
		"displayOrder": raw.get("displayOrder"),
	}


def choose(store: dict, raw_candidates: list[dict]) -> tuple[Optional[dict], str, str]:
	naver_candidates = [to_naver(c) for c in raw_candidates]
	chosen, _, reason = select_match(store, naver_candidates)
	if chosen is not None:
		return chosen, "select_match", reason
	for cand in sorted(naver_candidates, key=lambda c: c.get("displayOrder") or 0):
		ok, why = confident_same_address(store, cand)
		if ok:
			return cand, "fallback_same_address", why
	return None, "skip", reason


def apply_match(server: str, store_id: int, chosen: dict) -> tuple[bool, str]:
	payload = {
		"naverPlaceId": str(chosen["id"]),
		"replaceMenus": False,
		"address": chosen.get("address"),
	}
	if chosen.get("y") is not None and chosen.get("x") is not None:
		payload["coord"] = {"latitude": chosen["y"], "longitude": chosen["x"]}
	resp = requests.put(
		f"{server}/api/admin/stores/{store_id}/naver-place-id",
		json=payload,
		timeout=20,
	)
	if resp.status_code == 409:
		return False, "duplicate"
	if not resp.ok:
		return False, f"http_{resp.status_code}"
	return True, "ok"


def main() -> None:
	parser = argparse.ArgumentParser(description="Fast throttled candidate review/apply")
	parser.add_argument("--config", default=str(DEFAULT_CONFIG))
	parser.add_argument("--server", default=DEFAULT_SERVER)
	parser.add_argument("--limit", type=int, default=0)
	parser.add_argument("--apply", action="store_true")
	parser.add_argument("--include-fallback", action="store_true")
	parser.add_argument("--csv", default=str(DEFAULT_CSV))
	args = parser.parse_args()

	conn = db_connect(Path(args.config))
	try:
		stores, candidates_by_store = fetch_rows(conn)
	finally:
		conn.close()
	if args.limit:
		stores = stores[:args.limit]

	rows: list[dict] = []
	counts = Counter()
	for idx, store in enumerate(stores, 1):
		chosen, source, reason = choose(store, candidates_by_store.get(store["id"], []))
		if chosen is None:
			counts["skipped"] += 1
			row = {
				"idx": idx,
				"store_id": store["id"],
				"store_name": store["name"],
				"store_address": store.get("address") or "",
				"decision": "skip",
				"source": source,
				"reason": reason,
				"naver_place_id": "",
				"candidate_name": "",
				"candidate_address": "",
				"apply_status": "",
			}
			rows.append(row)
			continue
		if source == "fallback_same_address" and not args.include_fallback:
			counts["fallback_review"] += 1
			decision = "review"
		else:
			counts["selected"] += 1
			decision = "apply" if args.apply else "dry_apply"
		row = {
			"idx": idx,
			"store_id": store["id"],
			"store_name": store["name"],
			"store_address": store.get("address") or "",
			"decision": decision,
			"source": source,
			"reason": reason,
			"naver_place_id": chosen.get("id") or "",
			"candidate_name": chosen.get("name") or "",
			"candidate_address": chosen.get("address") or "",
			"apply_status": "",
		}
		if args.apply and decision == "apply":
			ok, status = apply_match(args.server, int(store["id"]), chosen)
			row["apply_status"] = status
			counts["applied" if ok else f"apply_{status}"] += 1
		rows.append(row)
		if idx % 100 == 0:
			print(f"processed={idx}/{len(stores)} counts={dict(counts)}", flush=True)

	csv_path = Path(args.csv)
	with csv_path.open("w", encoding="utf-8-sig", newline="") as f:
		fields = [
			"idx",
			"store_id",
			"store_name",
			"store_address",
			"decision",
			"source",
			"reason",
			"naver_place_id",
			"candidate_name",
			"candidate_address",
			"apply_status",
		]
		writer = csv.DictWriter(f, fieldnames=fields)
		writer.writeheader()
		writer.writerows(rows)

	print(json.dumps({
		"total": len(stores),
		"counts": dict(counts),
		"csv": str(csv_path),
	}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
	main()
