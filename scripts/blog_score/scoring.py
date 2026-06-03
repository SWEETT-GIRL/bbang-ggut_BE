#!/usr/bin/env python3
"""점수 공식 — 가게×카테고리 (docs/blog-score-feed-architecture.md §3)."""
import math
from datetime import date

HALFLIFE_DAYS = 180.0   # 최신성 반감기
GAMMA = 0.3             # 리뷰 prior 강도
SHRINK_M = 3.0          # 베이지안 수축 사전강도(유효글수 기준)


def recency_decay(post_date: str | None, *, today: date) -> float:
	"""0.5 ^ (age_days / HALFLIFE). 날짜 없으면 중립 0.5."""
	if not post_date:
		return 0.5
	try:
		y, m, d = (int(x) for x in post_date.split("-"))
		age = max(0, (today - date(y, m, d)).days)
	except (ValueError, AttributeError):
		return 0.5
	return 0.5 ** (age / HALFLIFE_DAYS)


def post_strength(total_hits: int) -> float:
	"""글이 '빵 얘기'를 얼마나 하는지 = 1 + ln(1 + 전체 카테고리 적중수)."""
	return 1.0 + math.log(1.0 + total_hits)


def quality(review_count: int | None) -> float:
	"""1 + γ·log10(1 + R)."""
	r = review_count or 0
	return 1.0 + GAMMA * math.log10(1.0 + r)


def raw_cat_score(posts: list[dict], cat: str, *, today: date) -> tuple[float, int]:
	"""해당 카테고리 RawCatScore와 유효 글 수 n_c.
	posts: 광고 제외된 글 [{date, senti_mult, cats:{cat:hits}, focus}]
	- focus  = 묶음글(여러 가게) 분배 가중. 없으면 1.0.
	- share  = 글 내 카테고리 분배 = hits_c / 글의 전체 카테고리 적중수.
	           → BAGEL 33·SAVORY 3 글이면 SAVORY엔 3/36만 귀속(약한 카테고리 과대평가 방지)."""
	total, n_c = 0.0, 0
	for p in posts:
		hits = p["cats"].get(cat, 0)
		focus = p.get("focus", 1.0)
		if not hits or focus <= 0:
			continue
		total_hits = sum(p["cats"].values())
		share = hits / total_hits if total_hits else 0.0
		total += (recency_decay(p["date"], today=today) * p["senti_mult"]
				  * post_strength(total_hits) * share * focus)
		n_c += 1
	return total, n_c


def final_cat_score(score_q: float, n_c: int, cat_mean: float) -> float:
	"""베이지안 수축: 글 적은 가게가 우연히 1등 방지."""
	if n_c == 0:
		return 0.0
	w = n_c / (n_c + SHRINK_M)
	return w * score_q + (1 - w) * cat_mean


def normalize(values: dict, scale: float = 100.0) -> dict:
	"""카테고리 내 min-max → 0~scale."""
	if not values:
		return {}
	vs = list(values.values())
	lo, hi = min(vs), max(vs)
	if hi <= lo:
		return {k: scale for k in values}
	return {k: round((v - lo) / (hi - lo) * scale, 1) for k, v in values.items()}


def normalize_log(values: dict, scale: float = 100.0, floor: float = 5.0) -> dict:
	"""로그 스케일 min-max → floor~scale.
	점수가 heavy-tail(소수 가게가 압도)일 때 1등만 100·나머지 깔리는 현상 방지.
	최저값도 floor(기본 5)로 띄워 '0점처럼 보이는' 문제 완화."""
	pos = {k: v for k, v in values.items() if v > 0}
	if not pos:
		return {}
	import math as _m
	ls = {k: _m.log(v) for k, v in pos.items()}
	lo, hi = min(ls.values()), max(ls.values())
	if hi <= lo:
		return {k: scale for k in pos}
	return {k: round(floor + (ls[k] - lo) / (hi - lo) * (scale - floor), 1) for k in pos}
