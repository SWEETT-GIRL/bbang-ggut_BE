#!/usr/bin/env python3
"""Apply confident stored Naver candidates for throttled stores.

This is a faster variant of apply_failed_candidate_matches.py:
- reads throttled stores and saved candidates in one DB query
- reuses the same candidate selection rules
- writes changes only through the existing admin API
"""

import argparse
import json
import re
import time
from collections import OrderedDict
from pathlib import Path
from urllib.parse import urlparse

import psycopg2
import requests

from apply_failed_candidate_matches import choose_candidate, update_naver_place_id
from naver_menu_scraper import DEFAULT_SERVER


DEFAULT_DB_PROPS = Path("src/main/resources/application-local.properties")


def read_props(path: Path) -> dict[str, str]:
	props: dict[str, str] = {}
	for line in path.read_text(encoding="utf-8").splitlines():
		line = line.strip()
		if not line or line.startswith("#") or "=" not in line:
			continue
		key, value = line.split("=", 1)
		props[key.strip()] = value.strip()
	return props


def parse_jdbc_url(url: str) -> dict[str, object]:
	if not url.startswith("jdbc:postgresql://"):
		raise ValueError(f"unsupported datasource url: {url}")
	parsed = urlparse(url.replace("jdbc:", "", 1))
	return {
		"host": parsed.hostname,
		"port": parsed.port or 5432,
		"dbname": parsed.path.lstrip("/"),
	}


def db_config(props_path: Path) -> dict[str, object]:
	props = read_props(props_path)
	cfg = parse_jdbc_url(props["spring.datasource.url"])
	cfg["user"] = props["spring.datasource.username"]
	cfg["password"] = props["spring.datasource.password"]
	return cfg


def fetch_candidate_groups(conn, limit: int) -> list[dict]:
	limit_sql = "LIMIT %s" if limit else ""
	params = [limit] if limit else []
	sql = f"""
		WITH throttled AS (
			SELECT
				s.id,
				s.name,
				s.address,
				CASE WHEN s.location IS NULL THEN NULL ELSE ST_Y(s.location::geometry) END AS lat,
				CASE WHEN s.location IS NULL THEN NULL ELSE ST_X(s.location::geometry) END AS lng,
				s.naver_matched_at
			FROM store s
			WHERE s.status = 'ACTIVE'
				AND s.naver_place_id IS NULL
				AND s.naver_matched_at IS NOT NULL
				AND s.naver_matched_at >= NOW() - INTERVAL '7 days'
				AND EXISTS (
					SELECT 1
					FROM naver_enrich_candidate c
					WHERE c.store_id = s.id
				)
			ORDER BY s.naver_matched_at DESC
			{limit_sql}
		)
		SELECT
			t.id AS store_id,
			t.name AS store_name,
			t.address AS store_address,
			t.lat AS store_lat,
			t.lng AS store_lng,
			c.naver_place_id,
			c.name AS candidate_name,
			c.address AS candidate_address,
			c.lat AS candidate_lat,
			c.lng AS candidate_lng,
			c.display_order
		FROM throttled t
		JOIN naver_enrich_candidate c ON c.store_id = t.id
		ORDER BY t.naver_matched_at DESC, t.id DESC, c.display_order ASC
	"""
	with conn.cursor() as cur:
		cur.execute(sql, params)
		rows = cur.fetchall()

	groups: OrderedDict[int, dict] = OrderedDict()
	for row in rows:
		(
			store_id,
			store_name,
			store_address,
			store_lat,
			store_lng,
			naver_place_id,
			candidate_name,
			candidate_address,
			candidate_lat,
			candidate_lng,
			display_order,
		) = row
		store = groups.setdefault(
			int(store_id),
			{
				"id": int(store_id),
				"name": store_name,
				"address": store_address,
				"lat": float(store_lat) if store_lat is not None else None,
				"lng": float(store_lng) if store_lng is not None else None,
				"candidates": [],
			},
		)
		store["candidates"].append(
			{
				"naverPlaceId": str(naver_place_id),
				"name": candidate_name,
				"address": candidate_address,
				"lat": float(candidate_lat) if candidate_lat is not None else None,
				"lng": float(candidate_lng) if candidate_lng is not None else None,
				"displayOrder": int(display_order),
			}
		)
	return list(groups.values())


def sanitize_reason(reason: str) -> str:
	return re.sub(r"\s+", " ", reason or "").strip()


def main() -> None:
	parser = argparse.ArgumentParser(description="Fast apply of confident failed Naver candidates")
	parser.add_argument("--server", default=DEFAULT_SERVER)
	parser.add_argument("--db-props", type=Path, default=DEFAULT_DB_PROPS)
	parser.add_argument("--limit", type=int, default=0, help="candidate-bearing throttled stores to scan")
	parser.add_argument("--dry-run", action="store_true")
	parser.add_argument("--sleep", type=float, default=0.0, help="seconds between successful updates")
	parser.add_argument("--progress-every", type=int, default=50)
	parser.add_argument("--decision-log", default="/tmp/naver-apply-failed-candidates-fast.jsonl")
	args = parser.parse_args()

	log_path = Path(args.decision_log)
	log_path.parent.mkdir(parents=True, exist_ok=True)
	log_path.write_text("", encoding="utf-8")

	with psycopg2.connect(**db_config(args.db_props)) as conn:
		stores = fetch_candidate_groups(conn, args.limit)

	print(f"server={args.server}")
	print(f"candidate-bearing throttled stores={len(stores)}")
	print(f"decision_log={log_path}")

	total = {
		"applied": 0,
		"dry_run_applied": 0,
		"skipped": 0,
		"duplicate": 0,
		"update_failed": 0,
	}

	session = requests.Session()
	with log_path.open("a", encoding="utf-8") as log:
		for idx, store in enumerate(stores, 1):
			chosen, reason = choose_candidate(store, store["candidates"])
			record = {
				"idx": idx,
				"total": len(stores),
				"store_id": store["id"],
				"store_name": store["name"],
				"store_address": store["address"],
				"decision": "skip",
				"reason": sanitize_reason(reason),
			}
			if not chosen:
				total["skipped"] += 1
				log.write(json.dumps(record, ensure_ascii=False) + "\n")
				if args.progress_every and idx % args.progress_every == 0:
					print(f"[{idx}/{len(stores)}] applied={total['applied']} skipped={total['skipped']}")
				continue

			record.update(
				{
					"decision": "apply",
					"naver_place_id": chosen.get("id"),
					"candidate_name": chosen.get("name"),
					"candidate_address": chosen.get("address"),
				}
			)
			if args.dry_run:
				total["dry_run_applied"] += 1
				log.write(json.dumps(record, ensure_ascii=False) + "\n")
				if args.progress_every and idx % args.progress_every == 0:
					print(
						f"[{idx}/{len(stores)}] dry_apply={total['dry_run_applied']} "
						f"skipped={total['skipped']}"
					)
				continue

			ok, status = update_naver_place_id(args.server, store["id"], chosen)
			if ok:
				total["applied"] += 1
				record["update_status"] = "OK"
				if args.sleep:
					time.sleep(args.sleep)
			elif status == "DUPLICATE":
				total["duplicate"] += 1
				record["decision"] = "duplicate"
				record["update_status"] = status
			else:
				total["update_failed"] += 1
				record["decision"] = "update_failed"
				record["update_status"] = status

			log.write(json.dumps(record, ensure_ascii=False) + "\n")
			if args.progress_every and idx % args.progress_every == 0:
				print(
					f"[{idx}/{len(stores)}] applied={total['applied']} "
					f"skipped={total['skipped']} duplicate={total['duplicate']} "
					f"update_failed={total['update_failed']}"
				)

	print(json.dumps(total, ensure_ascii=False, indent=2))


if __name__ == "__main__":
	main()
