#!/usr/bin/env python3
"""대분류별 '지역 특화도' 계산·캐시 (오픈API).

특화도 = 전국 대비 <동> 블로그 언급 비중 = Σtotal("<동> kw") / Σtotal("kw").
케이크처럼 어디나 흔한 카테고리는 낮고, 베이글·크림빵처럼 그 동네 쏠림이 큰 건 높음.
결과: cache/distinctiveness_<slug>.json {대분류: {mw, nat, ratio_permille}}

사용: .venv/bin/python -m blog_score.fetch_distinctiveness [--region 공덕동]
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from blog_score import naver_scrape as ns  # noqa: E402
from blog_score.regions import slug_for  # noqa: E402

CACHE_DIR = Path(__file__).resolve().parent / "cache"

# 대분류별 대표 키워드(전국 비교용 — generic 회피, 그룹 핵심만)
DISTINCT_KW = {
	"베이글": ["베이글", "샌드베이글"],
	"케이크": ["케이크", "티라미수"],
	"소금빵": ["소금빵"],
	"크루아상·페이스트리": ["크루아상", "퀸아망"],
	"단과자·크림빵": ["크림빵", "맘모스", "앙버터"],
	"식사빵·하드빵": ["바게트", "치아바타", "사워도우"],
	"타르트·파이": ["에그타르트", "타르트"],
	"구움과자": ["휘낭시에", "까눌레", "스콘"],
	"도넛·디저트": ["도넛", "마카롱"],
	"조리빵·샌드위치": ["고로케", "크로크무슈"],
}


def main() -> int:
	ap = argparse.ArgumentParser()
	ap.add_argument("--region", default="망원동", help="region_3rd 동명")
	args = ap.parse_args()
	region = args.region
	cache = CACHE_DIR / f"distinctiveness_{slug_for(region)}.json"

	cache.parent.mkdir(parents=True, exist_ok=True)
	out = {}
	for grp, kws in DISTINCT_KW.items():
		mw = sum(ns.fetch_blog_total(f"{region} {k}") or 0 for k in kws)
		nat = sum(ns.fetch_blog_total(k) or 0 for k in kws)
		ratio = mw / nat * 1000 if nat else 0.0
		out[grp] = {"mw": mw, "nat": nat, "ratio_permille": round(ratio, 3)}
		print(f"  {grp:<14} {region} {mw:>7,} / 전국 {nat:>10,} → {ratio:.2f}‰")
	cache.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
	print(f"\n→ {cache}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
