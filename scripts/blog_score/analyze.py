#!/usr/bin/env python3
"""글 단위 분석 — 협찬 판별 / 감성 / 카테고리 키워드 매칭 (룰 기반)."""
import re

from blog_score.dictionaries import (
	AD_MARKERS, CATEGORY_KEYWORDS, NON_AD_MARKERS, SENTIMENT_NEG, SENTIMENT_POS,
)


def _norm(text: str) -> str:
	"""공백 제거 + 소문자 — 부분일치 매칭용."""
	return re.sub(r"\s+", "", text).lower()


def detect_ad(text: str) -> tuple[bool, str]:
	"""(광고여부, 근거). 협찬 마커 매칭 → 광고. 단 내돈내산 등 강한 비광고 신호가
	있고 협찬 마커가 약하면 비광고로 본다(보수적: 마커 2개 이상이면 그래도 광고)."""
	n = _norm(text)
	hits = [w for w in AD_MARKERS if _norm(w) in n]
	non = [w for w in NON_AD_MARKERS if _norm(w) in n]
	if not hits:
		return False, ""
	if non and len(hits) < 2:
		return False, f"비광고신호({','.join(non)}) 우선"
	return True, ",".join(hits[:4])


def sentiment(text: str) -> tuple[str, float]:
	"""(라벨, 승수). 긍/부 키워드 빈도 비교. NEG 0.5 / NEU 1.0 / POS 1.5."""
	n = _norm(text)
	pos = sum(n.count(_norm(w)) for w in SENTIMENT_POS)
	neg = sum(n.count(_norm(w)) for w in SENTIMENT_NEG)
	if pos == 0 and neg == 0:
		return "NEU", 1.0
	if neg > pos:
		return "NEG", 0.5
	if pos > neg:
		return "POS", 1.5
	return "NEU", 1.0


def category_hits(text: str) -> dict[str, int]:
	"""카테고리별 키워드 적중 횟수 (0인 카테고리는 생략)."""
	n = _norm(text)
	out: dict[str, int] = {}
	for cat, kws in CATEGORY_KEYWORDS.items():
		c = sum(n.count(_norm(kw)) for kw in kws)
		if c:
			out[cat] = c
	return out
