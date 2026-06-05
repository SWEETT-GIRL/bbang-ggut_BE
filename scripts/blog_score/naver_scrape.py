#!/usr/bin/env python3
"""네이버 블로그 스크래퍼 (오픈API 키 없음 → 전부 스크래핑).

- search_posts(query): 모바일 통합검색 블로그탭에서 글 목록(url/제목/날짜/스니펫) 추출
- fetch_body(url): 모바일 블로그 본문 전문 추출
- fetch_review_count(naver_place_id): 플레이스 방문자/블로그 리뷰 수 추출

차단 대비: User-Agent, 요청 간 슬립, 재시도.
"""
import json
import os
import random
import re
import time
from datetime import date, timedelta
from pathlib import Path

import requests

_SECRETS = Path(__file__).resolve().parent / ".secrets.json"


def _naver_api_keys() -> tuple:
	"""검색 오픈API 키 — 환경변수 우선, 없으면 .secrets.json."""
	cid = os.getenv("NAVER_CLIENT_ID")
	sec = os.getenv("NAVER_CLIENT_SECRET")
	if cid and sec:
		return cid, sec
	if _SECRETS.exists():
		d = json.loads(_SECRETS.read_text(encoding="utf-8"))
		return d.get("naver_client_id"), d.get("naver_client_secret")
	return None, None


def fetch_blog_total(query: str, *, sleep: float = 0.12) -> int | None:
	"""네이버 블로그 검색 오픈API — 해당 검색어의 블로그 글 총 개수(total). 다이닝코드식 언급량."""
	cid, sec = _naver_api_keys()
	if not cid:
		return None
	try:
		r = requests.get(
			"https://openapi.naver.com/v1/search/blog.json",
			headers={"X-Naver-Client-Id": cid, "X-Naver-Client-Secret": sec},
			params={"query": query, "display": 1}, timeout=12)
		time.sleep(sleep)
		if r.status_code == 200:
			return int(r.json().get("total", 0))
	except (requests.RequestException, ValueError):
		pass
	return None

UA_POOL = [
	"Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Safari/604.1",
	"Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
	"Mozilla/5.0 (Linux; Android 13; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
]

_POST_RE = re.compile(r"(?:m\.)?blog\.naver\.com/([^/?#]+)/(\d{6,})")
_ABS_DATE = re.compile(r"(20\d{2})\.\s*(\d{1,2})\.\s*(\d{1,2})")
_REL_DATE = re.compile(r"(\d+)\s*(분|시간|일|주|개월|달|년)\s*전|(어제|그제|그저께)")
_BOILER = re.compile(r"Keep에 저장|Keep에 바로가기|블로그 카테고리|이 블로그.*?카테고리 글")


def _headers() -> dict:
	return {"User-Agent": random.choice(UA_POOL),
			"Accept-Language": "ko-KR,ko;q=0.9"}


def _get(url: str, *, timeout: int = 15, retries: int = 3) -> requests.Response | None:
	for attempt in range(retries):
		try:
			r = requests.get(url, headers=_headers(), timeout=timeout)
			if r.status_code == 200:
				return r
		except requests.RequestException:
			pass
		time.sleep(0.8 * (attempt + 1))
	return None


def _parse_rel_date(token: str, *, today: date) -> str | None:
	m = _ABS_DATE.search(token)
	if m:
		y, mo, d = (int(x) for x in m.groups())
		try:
			return date(y, mo, d).isoformat()
		except ValueError:
			return None
	m = _REL_DATE.search(token)
	if not m:
		return None
	if m.group(3):  # 어제/그제
		return (today - timedelta(days=1 if m.group(3) == "어제" else 2)).isoformat()
	n, unit = int(m.group(1)), m.group(2)
	days = {"분": 0, "시간": 0, "일": 1, "주": 7, "개월": 30, "달": 30, "년": 365}[unit] * n
	return (today - timedelta(days=days)).isoformat()


def search_posts(query: str, *, today: date, max_posts: int = 30,
				 sleep: float = 0.4) -> list[dict]:
	"""모바일 블로그 검색 → [{url, blog_id, log_no, title, date, snippet}]"""
	from bs4 import BeautifulSoup

	url = ("https://m.search.naver.com/search.naver?where=m_blog&query="
		   + requests.utils.quote(query))
	r = _get(url)
	if r is None:
		return []
	soup = BeautifulSoup(r.text, "lxml")
	out: dict[tuple, dict] = {}
	for a in soup.find_all("a", href=True):
		m = _POST_RE.search(a["href"])
		if not m:
			continue
		key = (m.group(1), m.group(2))
		if key in out:
			continue
		item = a
		for _ in range(8):
			item = item.parent
			if item is None:
				break
			cls = item.get("class") or []
			if any("single-intention-item" in c for c in cls):
				break
		raw = item.get_text(" ", strip=True) if item is not None else a.get_text(" ", strip=True)
		raw = re.sub(r"\s+", " ", raw)
		clean = _BOILER.sub(" ", raw)
		out[key] = {
			"url": f"https://blog.naver.com/{key[0]}/{key[1]}",
			"blog_id": key[0], "log_no": key[1],
			"title": a.get_text(strip=True)[:120],
			"date": _parse_rel_date(raw, today=today),
			"snippet": re.sub(r"\s+", " ", clean)[:200],
		}
		if len(out) >= max_posts:
			break
	time.sleep(sleep)
	return list(out.values())


def fetch_body(url: str, *, sleep: float = 0.4) -> str:
	"""모바일 블로그 본문 전문 (실패 시 빈 문자열)."""
	from bs4 import BeautifulSoup

	m = _POST_RE.search(url)
	if not m:
		return ""
	murl = f"https://m.blog.naver.com/{m.group(1)}/{m.group(2)}"
	r = _get(murl)
	time.sleep(sleep)
	if r is None:
		return ""
	soup = BeautifulSoup(r.text, "lxml")
	cont = (soup.select_one("div.se-main-container")
			or soup.select_one("div.post_ct")
			or soup.select_one("#viewTypeSelector"))
	text = cont.get_text(" ", strip=True) if cont else ""
	return re.sub(r"\s+", " ", text)


def _review_from_html(text: str) -> int | None:
	nums = [int(x.replace(",", "")) for x in re.findall(r'"reviewCount":\s*"?(\d[\d,]*)"?', text)]
	if not nums:
		nums = [int(x.replace(",", "")) for x in re.findall(r'[Rr]eviewCount["\s:]+(\d[\d,]*)', text)]
	return max(nums) if nums else None


def _rating_from_html(text: str) -> float | None:
	m = re.search(r'"avgRating"\s*:\s*([\d.]+)', text)
	return float(m.group(1)) if m else None


def fetch_place_stats(naver_place_id: str, *, sleep: float = 0.3) -> tuple:
	"""(리뷰수, 평점). 가게 타입 다중 시도. 실패 시 (None, None)."""
	if not naver_place_id:
		return (None, None)
	for typ in ("restaurant", "cafe", "place"):
		r = _get(f"https://m.place.naver.com/{typ}/{naver_place_id}/home")
		if r is not None:
			rv = _review_from_html(r.text)
			if rv:
				time.sleep(sleep)
				return (rv, _rating_from_html(r.text))
	time.sleep(sleep)
	return (None, None)


def fetch_review_count(naver_place_id: str, *, sleep: float = 0.3) -> int | None:
	"""플레이스 리뷰 수만. (호환용)"""
	return fetch_place_stats(naver_place_id, sleep=sleep)[0]


def resolve_place_id(name: str, *, region: str = "망원동", sleep: float = 0.3) -> str | None:
	"""place_id 없는 가게용 — 이름검색으로 추정. 오매칭 위험 있어 호출측에서 검증 권장."""
	url = ("https://m.search.naver.com/search.naver?where=m&query="
		   + requests.utils.quote(f"{region} {name}"))
	r = _get(url)
	time.sleep(sleep)
	if r is None:
		return None
	m = re.search(r'place/(\d{6,})', r.text)
	return m.group(1) if m else None
