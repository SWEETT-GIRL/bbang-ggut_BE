#!/usr/bin/env python3
"""전국 IDF용 — 카테고리 키워드별 '전국 블로그 total'을 오픈API로 받아 글로벌 캐시.

IDF의 document frequency를 동(local) 코퍼스가 아니라 전국으로 고정하기 위한 소스.
지역 앵커 없이 키워드만 검색("케이크" 등) → 네이버 블로그 검색 total = 전국 등장량.
동 무관 글로벌 캐시이므로 한 번 받아 모든 동이 재사용한다(주기적 갱신만).

결과: cache/keyword_total_nat.json {정규화키워드: 전국 total}
사용: .venv/bin/python -m blog_score.fetch_keyword_totals
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from blog_score import naver_scrape as ns  # noqa: E402
from blog_score.dictionaries import CATEGORY_KEYWORDS  # noqa: E402

CACHE = Path(__file__).resolve().parent / "cache" / "keyword_total_nat.json"


def main() -> int:
	# 원표기로 검색하되 캐시 키는 공백 제거 정규화(score_v3.build_idf 조회 형식과 일치)
	seen: dict[str, str] = {}
	for kws in CATEGORY_KEYWORDS.values():
		for kw in kws:
			seen.setdefault(kw.replace(" ", ""), kw)
	CACHE.parent.mkdir(parents=True, exist_ok=True)
	out = {}
	for i, (norm, raw) in enumerate(sorted(seen.items()), 1):
		out[norm] = ns.fetch_blog_total(raw) or 0
		if i % 20 == 0 or i == len(seen):
			print(f"  {i}/{len(seen)}")
	CACHE.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
	vals = sorted(out.values(), reverse=True)
	print(f"완료: {len(out)}개 키워드 · 최대 {vals[0]:,} / 중앙값 {vals[len(vals)//2]:,} / 최소 {vals[-1]:,}")
	print(f"  → {CACHE}")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
