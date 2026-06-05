#!/usr/bin/env python3
"""멀티쿼리 블로그 크롤 + 본문 캐시.

가게당 여러 쿼리(합집합)로 첫페이지 글을 모아 커버리지를 키우고, 본문을 JSONL 캐시에
저장한다. 캐시가 있으면 점수/귀속 튜닝을 재크롤 없이 반복할 수 있다.

사용:
    .venv/bin/python -m blog_score.crawl_multi [--limit N] [--max-posts 40]
출력:
    blog_score/cache/mangwon_bodies_<run>.jsonl   (라인당 {store_id,url,date,title,body})
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blog_score import naver_scrape as ns  # noqa: E402
from blog_score.regions import slug_for  # noqa: E402

BASE = Path(__file__).resolve().parent
SNAP_DIR = BASE / "snapshots"
CACHE_DIR = BASE / "cache"


def queries_for(name: str, region: str) -> list[str]:
	"""지역 앵커 + 변형 — 서로 다른 글을 끌어와 합집합 커버리지를 키움."""
	short = region[:-1] if region.endswith("동") else region  # 망원동→망원, 공덕동→공덕
	return [f"{region} {name}", f"{short} {name}", f"{name} 빵집"]


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--region", default="망원동", help="region_3rd 동명")
	ap.add_argument("--limit", type=int, default=None)
	ap.add_argument("--max-posts", type=int, default=40, help="가게당 본문 수집 상한")
	args = ap.parse_args()
	region = args.region
	slug = slug_for(region)

	today = datetime.now().date()
	snaps = sorted(SNAP_DIR.glob(f"{slug}_stores_*.json"))
	if not snaps:
		sys.exit("스냅샷 없음")
	snap = json.loads(snaps[-1].read_text(encoding="utf-8"))
	stores = snap["stores"][: args.limit] if args.limit else snap["stores"]

	CACHE_DIR.mkdir(parents=True, exist_ok=True)
	run = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
	out = CACHE_DIR / f"{slug}_bodies_{run}.jsonl"
	print(f"멀티쿼리 크롤 {len(stores)}곳 → {out.name}")

	total = 0
	with out.open("w", encoding="utf-8") as fh:
		for i, s in enumerate(stores, 1):
			sid, name = s["store_id"], s["name"]
			collected: dict[tuple, dict] = {}
			for q in queries_for(name, region):
				for p in ns.search_posts(q, today=today, max_posts=30):
					collected[(p["blog_id"], p["log_no"])] = p
				if len(collected) >= args.max_posts:
					break
			items = list(collected.values())[: args.max_posts]
			n = 0
			for p in items:
				body = ns.fetch_body(p["url"])
				if not body:
					continue
				fh.write(json.dumps({
					"store_id": sid, "url": p["url"], "date": p.get("date"),
					"title": p.get("title", ""), "body": body,
				}, ensure_ascii=False) + "\n")
				n += 1
			fh.flush()
			total += n
			print(f"  [{i}/{len(stores)}] {name}: 후보 {len(items)} → 본문 {n}")

	print(f"\n크롤 완료: 글 {total} → {out}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
