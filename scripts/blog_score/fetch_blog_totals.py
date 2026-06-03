#!/usr/bin/env python3
"""네이버 검색 오픈API로 가게별 블로그 총 언급량(total)을 받아 캐시.

쿼리: "<동> {가게명}" (지역 앵커로 동명 오염 줄임). 결과: cache/blog_total_<slug>.json {store_id: total}
재실행 시 갱신. score_v3가 이 캐시를 볼륨(화제성) 신호로 사용.

사용: .venv/bin/python -m blog_score.fetch_blog_totals [--region 공덕동]
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from blog_score import naver_scrape as ns  # noqa: E402
from blog_score.regions import slug_for  # noqa: E402

BASE = Path(__file__).resolve().parent
SNAP_DIR = BASE / "snapshots"


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--region", default="망원동", help="region_3rd 동명")
	args = ap.parse_args()
	region = args.region
	slug = slug_for(region)
	cache = BASE / "cache" / f"blog_total_{slug}.json"

	snap = json.loads(sorted(SNAP_DIR.glob(f"{slug}_stores_*.json"))[-1].read_text(encoding="utf-8"))
	stores = snap["stores"]
	cache.parent.mkdir(parents=True, exist_ok=True)
	out = {}
	for i, s in enumerate(stores, 1):
		total = ns.fetch_blog_total(f"{region} {s['name']}")
		out[str(s["store_id"])] = total
		if i % 25 == 0 or i == len(stores):
			print(f"  {i}/{len(stores)}")
	cache.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
	vals = [v for v in out.values() if v]
	print(f"완료: {len(vals)}/{len(stores)} 보유 · "
		  f"최대 {max(vals):,} / 중앙값 {sorted(vals)[len(vals)//2]:,}")
	print(f"  → {cache}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
