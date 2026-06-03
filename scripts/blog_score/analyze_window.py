#!/usr/bin/env python3
"""근접 기반(windowed co-occurrence) 글 분석 — LLM 없이 귀속 정확도 향상.

핵심: 글에서 **가게명 근처(±WINDOW자)** 에 나온 카테고리/감성 키워드만 그 가게에 귀속.
  "투떰즈업 베이글 맛있다" → 투떰즈업 BAGEL 귀속 O
  "투떰즈업 갔다가 옆집 브릭베이글" → 투떰즈업엔 옆집 베이글 귀속 X
이로써 빵지순례 묶음글의 옆가게 오염을 근본 차단한다.
"""
import re

from blog_score.dictionaries import (
	AD_MARKERS, CATEGORY_KEYWORDS, NON_AD_MARKERS, SENTIMENT_NEG, SENTIMENT_POS,
)

WINDOW = 120   # 가게명 기준 좌우 문자 윈도우


def name_aliases(name: str) -> list[str]:
	"""가게명 매칭 별칭 — 전체명, 지점 뗀 첫토큰. 길이 2+만."""
	al = {name.replace(" ", "")}
	toks = name.split()
	if toks:
		al.add(toks[0])
	return [a for a in al if len(a) >= 2]


def _spans(text: str, aliases: list[str]) -> list[tuple[int, int]]:
	"""본문 내 가게명 출현 위치 [(start,end)]."""
	spans = []
	for a in aliases:
		for m in re.finditer(re.escape(a), text):
			spans.append((m.start(), m.end()))
	return spans


def _windows(text: str, spans: list[tuple[int, int]], window: int) -> str:
	"""가게명 주변 ±window 구간만 이어붙인 텍스트."""
	if not spans:
		return ""
	parts = []
	for s, e in spans:
		parts.append(text[max(0, s - window): min(len(text), e + window)])
	return " ".join(parts)


def detect_ad(text: str) -> tuple[bool, str]:
	"""광고/협찬 판별 — 글 전체 기준(보통 맺음말에 고지)."""
	n = text.replace(" ", "")
	hits = [w for w in AD_MARKERS if w.replace(" ", "") in n]
	non = [w for w in NON_AD_MARKERS if w.replace(" ", "") in n]
	if not hits:
		return False, ""
	if non and len(hits) < 2:
		return False, f"비광고신호({','.join(non)})"
	return True, ",".join(hits[:4])


def build_name_regex(aliases) -> "re.Pattern":
	"""전체 가게명(별칭) 매칭용 단일 정규식 — 긴 별칭 우선."""
	uniq = sorted({a for a in aliases if len(a) >= 2}, key=len, reverse=True)
	return re.compile("|".join(re.escape(a) for a in uniq)) if uniq else re.compile(r"(?!x)x")


def build_cat_regexes() -> dict:
	"""카테고리별 키워드 단일 정규식."""
	out = {}
	for c, kws in CATEGORY_KEYWORDS.items():
		uniq = sorted({kw for kw in kws}, key=len, reverse=True)
		out[c] = re.compile("|".join(re.escape(k) for k in uniq))
	return out


def attribute_nearest(full: str, target_set: set, name_re, cat_res: dict,
					  *, window: int = WINDOW) -> dict:
	"""최근접 가게명 귀속 — 카테고리 키워드를 '가장 가까운 가게명'에만 귀속.
	묶음글 옆가게 오염 차단(어글리 옆 투떰즈업 '샌드' → 투떰즈업으로). target_set=타깃 별칭 집합."""
	is_ad, reason = detect_ad(full)
	matches = [(m.start(), m.group()) for m in name_re.finditer(full)]
	tgt = [p for p, t in matches if t in target_set]
	if not tgt:
		return {"relevant": False, "ad": is_ad, "ad_reason": reason,
				"sentiment": "NEU", "senti_mult": 1.0, "cats": {}, "name_hits": 0}
	oth = [p for p, t in matches if t not in target_set]

	def nearest(pos, arr):
		return min((abs(pos - x) for x in arr), default=10**9)

	cats = {}
	for c, rgx in cat_res.items():
		h = sum(1 for m in rgx.finditer(full)
				if nearest(m.start(), tgt) <= window and nearest(m.start(), tgt) <= nearest(m.start(), oth))
		if h:
			cats[c] = h
	wn = " ".join(full[max(0, p - window): p + window] for p in tgt).replace(" ", "")
	pos = sum(wn.count(w.replace(" ", "")) for w in SENTIMENT_POS)
	neg = sum(wn.count(w.replace(" ", "")) for w in SENTIMENT_NEG)
	senti, mult = ("NEG", 0.5) if neg > pos else ("POS", 1.5) if pos > neg else ("NEU", 1.0)
	return {"relevant": True, "ad": is_ad, "ad_reason": reason,
			"sentiment": senti, "senti_mult": mult, "cats": cats, "name_hits": len(tgt)}


def analyze_post(body: str, title: str, aliases: list[str], *, window: int = WINDOW) -> dict:
	"""반환: {relevant, ad, ad_reason, sentiment, senti_mult, cats:{c:hits}, name_hits}
	cats/sentiment는 '가게명 근처' 텍스트에서만 계산."""
	full = (title + " " + body)
	spans = _spans(full, aliases)
	is_ad, reason = detect_ad(full)
	if not spans:
		return {"relevant": False, "ad": is_ad, "ad_reason": reason,
				"sentiment": "NEU", "senti_mult": 1.0, "cats": {}, "name_hits": 0}

	win = _windows(full, spans, window)
	wn = win.replace(" ", "")
	# 카테고리 — 윈도우 내 키워드 적중
	cats = {}
	for c, kws in CATEGORY_KEYWORDS.items():
		h = sum(wn.count(kw.replace(" ", "")) for kw in kws)
		if h:
			cats[c] = h
	# 감성 — 윈도우 내
	pos = sum(wn.count(w.replace(" ", "")) for w in SENTIMENT_POS)
	neg = sum(wn.count(w.replace(" ", "")) for w in SENTIMENT_NEG)
	if neg > pos:
		senti, mult = "NEG", 0.5
	elif pos > neg:
		senti, mult = "POS", 1.5
	else:
		senti, mult = "NEU", 1.0
	return {"relevant": True, "ad": is_ad, "ad_reason": reason,
			"sentiment": senti, "senti_mult": mult, "cats": cats, "name_hits": len(spans)}
