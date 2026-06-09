#!/usr/bin/env python3
"""
네이버 플레이스 메뉴 크롤러 — 빵긋 백엔드 큐 연동

흐름:
  1. GET /api/admin/stores/naver-enrich-queue 로 naverPlaceId 미매칭 가게 fetch
  2. 가게마다 네이버 검색 → 후보 중 **신뢰 매칭** 통과한 1건만 선택
       - 이름 정규화 일치 AND (주소 구/동 겹침 OR 100m 이내)
       - 통과 안 하면 mark_naver_enrich_failed (7일 throttle)
  3. 매칭 성공:
       - PUT /api/admin/stores/{id}/naver-place-id 로 ID 저장
       - existingMenuCount == 0 이면 메뉴 크롤링 + 등록 + 이미지 업로드
       - existingMenuCount > 0 이면 메뉴는 건드리지 않음 (ID만 채움)

사용법:
  # 기본 — 큐 한 배치 처리
  python naver_menu_scraper.py --server http://localhost:8080

  # 큐가 빌 때까지 반복
  python naver_menu_scraper.py --loop

  # dry-run — 매칭만 시뮬레이션, 실제 저장 X
  python naver_menu_scraper.py --dry-run

  # 매칭 디버깅 — 신뢰 매칭 실패한 케이스의 후보들도 출력
  python naver_menu_scraper.py --verbose --dry-run

의존성:
  pip install requests playwright
  python -m playwright install chromium
"""

import argparse
import json
import random
import re
import sys
import time
import urllib.parse
from typing import Optional

try:
	import requests
except ImportError:
	print("requests 패키지가 필요합니다: pip install requests")
	sys.exit(1)

try:
	from playwright.sync_api import sync_playwright, Page
except ImportError:
	print("playwright 패키지가 필요합니다: pip install playwright && python -m playwright install chromium")
	sys.exit(1)

from naver_match import (
	classify_menu_texts,
	extract_best_district,
	extract_road_and_number,
	extract_road_name,
	guess_category,
	is_drink,
	normalize_name,
	select_match,
	strip_branch_suffix,
	strip_category_suffix,
	strip_company_suffix,
	strip_generic_branch_suffix,
)


# ─── 설정 ─────────────────────────────────────────────────────────────────────

DEFAULT_SERVER = "http://localhost:8080"
QUEUE_BATCH_SIZE = 50
STORE_PHOTO_LIMIT = 5
# 가게 간 기본 딜레이 + 랜덤 지터(아래) — 너무 규칙적/빠른 접속은 네이버 rate-limit 유발하므로
# 봇처럼 안 보이게 간격을 두고 흔든다.
INTER_STORE_DELAY_SEC = 1.5
INTER_STORE_JITTER_SEC = 1.5  # 0~이 값 사이 랜덤 추가 → 실제 1.5~3.0s
INTER_BATCH_DELAY_SEC = 8.0
# 네이버 차단 의심 — 큐 모드에서 N건 연속 검색 결과 0이면 abort (대량 false-fail 방지).
# 정상 baseline 누락률은 낮으므로 5건 연속은 차단 시그널로 충분.
NO_CANDIDATE_ABORT_THRESHOLD = 5
NAVER_RESTRICTED_TEXTS = (
	"서비스 이용이 제한되었습니다",
	"과도한 접근 요청",
	"잠시 후 다시 시도",
)

NAVER_UA = (
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
	"AppleWebKit/537.36 (KHTML, like Gecko) "
	"Chrome/124.0.0.0 Safari/537.36"
)

_SEARCH_FACILITY_SUFFIX_RE = re.compile(
	r"\s+(?:롯데백화점|현대백화점|신세계백화점|갤러리아백화점|백화점|더현대|스타필드"
	r"|타임스퀘어|월드몰|아울렛|몰|마트)$"
)


class NaverAccessRestricted(RuntimeError):
	pass


def canonicalize_pstatic(src: str) -> str:
	"""네이버 pstatic 이미지 URL 을 원본(풀해상도) canonical 형태로 정규화.

	네이버 그리드/라이트박스는 search.pstatic.net/common?...&type=w278&src=ENCODED 리사이즈
	프록시 래퍼로 썸네일(278px 등)을 렌더링한다. 이 래퍼를 그대로 저장하면 (1) 저화질이고
	(2) 같은 사진이 크롤마다 다른 type/파라미터로 와서 멱등성 키가 흔들려 중복 insert 된다.

	→ 래퍼면 안쪽 src 를 디코딩해 origin+path 만 사용(쿼리 제거 = 원본 풀해상도), 직접 pstatic
	URL 도 origin+path 로 잘라 ?type= 변형을 무시한다. 같은 사진은 항상 동일 canonical URL 로
	수렴하므로 화질·dedup·멱등성이 동시에 해결된다. pstatic 이 아니면 원본 그대로 반환.
	"""
	if not src:
		return src
	try:
		u = urllib.parse.urlparse(src)
		inner = urllib.parse.parse_qs(u.query).get("src")
		if inner:
			iu = urllib.parse.urlparse(inner[0])
			if iu.scheme and iu.netloc:
				return f"{iu.scheme}://{iu.netloc}{iu.path}"
		if "pstatic" in u.netloc:
			return f"{u.scheme}://{u.netloc}{u.path}"
		return src
	except Exception:
		return src


def _has_naver_restriction_text(scope) -> bool:
	try:
		text = scope.evaluate("document.body ? document.body.innerText : ''")
	except Exception:
		return False
	return any(marker in (text or "") for marker in NAVER_RESTRICTED_TEXTS)


def _raise_if_naver_restricted(scope, where: str) -> None:
	if _has_naver_restriction_text(scope):
		print(f"  [diag] 네이버 접근 제한 감지 — {where}")
		raise NaverAccessRestricted(where)


def _wait_entry_frame(page, timeout_ms: int = 8000):
	"""entryIframe 이 실제로 로드(url 확보)될 때까지 폴링한다.

	page.frame("entryIframe") 단발 호출은 진입 직후 타이밍에 따라 None 또는 url 빈 프레임을
	반환해, 메뉴/사진이 통째로 누락(0건)되는 flakiness 가 있었다(예: 오디티 재크롤 시 메뉴 0개).
	→ url 이 잡힐 때까지 재시도.
	"""
	waited = 0
	while waited < timeout_ms:
		fr = page.frame("entryIframe")
		if fr and fr.url and fr.url != "about:blank":
			return fr
		page.wait_for_timeout(400)
		waited += 400
	return page.frame("entryIframe")


# ─── 백엔드 API 클라이언트 ────────────────────────────────────────────────────

def fetch_queue(
		server: str,
		size: int,
		path: str = "naver-enrich-queue",
		partition: Optional[str] = None) -> list[dict]:
	url = f"{server}/api/admin/stores/{path}"
	params = {"page": 0, "size": size}
	if partition:
		params["partition"] = partition
	resp = requests.get(url, params=params, timeout=15)
	resp.raise_for_status()
	return resp.json().get("data", {}).get("content", []) or []


def fetch_single_store(server: str, store_id: int) -> Optional[dict]:
	"""단일 가게를 큐 아이템과 동일한 shape 으로 받아옴."""
	url = f"{server}/api/admin/stores/{store_id}/naver-enrich-item"
	try:
		resp = requests.get(url, timeout=10)
		resp.raise_for_status()
		return resp.json().get("data")
	except Exception as e:
		print(f"[단일 가게 fetch 실패] id={store_id}: {e}")
		return None


def save_naver_place_id(server: str, store_id: int, naver_place_id: str) -> tuple[bool, Optional[str]]:
	"""placeId 를 BE 에 저장.
	반환: (성공 여부, 실패 사유 코드).
	  - (True, None): 성공
	  - (False, "STORE409_4"): 다른 store 가 이미 같은 placeId 사용 중 (BE 가 409 로 거절)
	  - (False, 그 외 code/문자열): 기타 실패 (네트워크 / 5xx 등)
	실패 시 호출자는 메뉴/사진 크롤을 진행하면 안 된다 — 잘못된 placeId 로 데이터 오염 위험.
	"""
	url = f"{server}/api/admin/stores/{store_id}/naver-place-id"
	try:
		resp = requests.put(url, json={"naverPlaceId": naver_place_id}, timeout=10)
		if resp.status_code == 409:
			code, msg = "CONFLICT", ""
			try:
				body = resp.json()
				code = body.get("code") or code
				msg = body.get("message") or ""
			except Exception:
				pass
			print(f"  [naverPlaceId 저장 실패 409] store_id={store_id} code={code} msg={msg}")
			return False, code
		resp.raise_for_status()
		return True, None
	except Exception as e:
		print(f"  [naverPlaceId 저장 실패] store_id={store_id}: {e}")
		return False, "ERROR"


def mark_failed(server: str, store_id: int, no_candidates: bool = False) -> None:
	# no_candidates=True (검색 후보 0건=진짜 무결과)면 백엔드 파킹 카운터가 +1 — 임계 누적 시 큐 제외.
	# MISMATCH(후보 있음)는 False 로 호출해 카운트 안 함(수동 후보선택 대상).
	url = f"{server}/api/admin/stores/{store_id}/naver-enrich-failed"
	try:
		requests.post(url, params={"noCandidates": str(no_candidates).lower()}, timeout=10)
	except Exception as e:
		print(f"  [실패 마킹 실패] store_id={store_id}: {e}")


def mark_backfill_attempted(server: str, store_id: int, mode: str) -> None:
	"""백필 큐 재진입 방지용 시도 마킹.

	메뉴/사진이 네이버에 없으면 DB 데이터가 변하지 않아 같은 store 가 page=0 큐에 계속 남는다.
	현재 백필 실행의 처리 완료 id 를 백엔드 메모리에 기록해 큐에서 제외한다.
	"""
	url = f"{server}/api/admin/stores/{store_id}/naver-backfill-attempted"
	try:
		resp = requests.post(url, params={"mode": mode}, timeout=10)
		resp.raise_for_status()
	except Exception as e:
		print(f"  [백필 시도 마킹 실패] store_id={store_id}: {e}")


def save_candidates(server: str, store_id: int, candidates: list[dict]) -> None:
	"""매칭 실패한 후보들을 BE 에 저장 (어드민 '후보 보기' UI 용).
	기존 후보는 BE 가 전부 지우고 교체. 빈 리스트면 호출 안 함.
	"""
	if not candidates:
		return
	url = f"{server}/api/admin/stores/{store_id}/naver-enrich-candidates"
	payload = []
	for c in candidates[:20]:  # 너무 많으면 어드민이 압도됨 — 상위 20개만
		try:
			lat = float(c.get("y") or 0) or None
			lng = float(c.get("x") or 0) or None
		except (TypeError, ValueError):
			lat = lng = None
		naver_id = c.get("id")
		name = c.get("name")
		if not naver_id or not name:
			continue
		payload.append({
			"naverPlaceId": str(naver_id),
			"name": re.sub(r"<[^>]+>", "", name),  # <b> 태그 제거
			# 실패 후보 저장 시에도 도로명주소를 우선 보관해야 나중에 수동/재검증에서 road_match 가 살아난다.
			"address": c.get("roadAddress") or c.get("address"),
			"lat": lat,
			"lng": lng,
		})
	if not payload:
		return
	try:
		resp = requests.post(url, json=payload, timeout=10)
		resp.raise_for_status()
	except Exception as e:
		print(f"  [후보 저장 실패] store_id={store_id}: {e}")


def get_existing_menus(server: str, store_id: int) -> dict[str, int]:
	"""DB의 기존 메뉴 {이름: menu_id}. soft-delete 포함된다는 점 유의."""
	try:
		resp = requests.get(f"{server}/api/admin/stores/{store_id}/menus", timeout=10)
		resp.raise_for_status()
		menus = resp.json().get("data", []) or []
		return {m["name"]: m["id"] for m in menus if m.get("name") and m.get("id")}
	except Exception:
		return {}


def create_menu(server: str, store_id: int, name: str, price: Optional[int],
				category: str, desc: Optional[str]) -> Optional[int]:
	url = f"{server}/api/admin/stores/{store_id}/menus"
	body = {
		"name": name,
		"price": f"{price}원" if price else None,
		"desc": desc or None,
		"category": category,
		"status": "active",
	}
	try:
		resp = requests.post(url, json=body, timeout=10)
		resp.raise_for_status()
		return resp.json().get("data", {}).get("id")
	except Exception as e:
		print(f"  [메뉴 등록 실패] {name}: {e}")
		return None


def upload_images_from_urls(server: str, domain: str, target_id: int, urls: list[str]) -> int:
	"""백엔드가 download → S3 → DB 를 한 번에 처리.
	per-URL 실패 결과를 담은 응답을 받아 성공 개수 반환. S3 orphan 위험 없음.
	"""
	if not urls:
		return 0
	endpoint = f"{server}/admin/images/{domain}/{target_id}/from-url"
	payload = [{"url": u} for u in urls]
	try:
		# 다운로드 15s * N + S3 PUT 시간 — 넉넉히 잡음.
		resp = requests.post(endpoint, json=payload, timeout=180)
		resp.raise_for_status()
		data = resp.json().get("data") or {}
		results = data.get("results") or []
		for item in results:
			if item.get("status") != "SUCCESS":
				print(f"  [업로드 실패] {domain}/{target_id} {item.get('status')}: {item.get('reason')} — {item.get('url')}")
		return int(data.get("successCount", 0))
	except Exception as e:
		print(f"  [업로드 호출 실패] {domain}/{target_id} ({len(urls)}장): {e}")
		return 0


# ─── 네이버 크롤링 (Playwright) ──────────────────────────────────────────────

def _naver_search_once(page: Page, query: str) -> tuple[list[dict], bool]:
	"""네이버 지도 한 번 검색 → (후보 places, 차단의심 여부) 반환.

	차단의심(blocked)은 후보가 0건일 때만 의미 있음:
	  · True  — allSearch XHR 미발사 OR non-200 응답 → IP/UA 차단·페이지 로드 실패 의심.
	  · False — allSearch 200 응답 + 빈 list → 네이버에 진짜 없는 가게(폐업/오등록 등).
	0건일 때만 진단 로그 출력 — 정상 케이스는 노이즈 X.
	"""
	captured: list[dict] = []
	statuses: list[tuple[int, str]] = []  # (status, content-type) — allSearch 응답만
	parse_errors: list[str] = []
	allsearch_seen = False
	blocked = False

	def handle_response(response):
		nonlocal allsearch_seen
		if "allSearch" not in response.url:
			return
		allsearch_seen = True
		ct = response.headers.get("content-type", "")
		statuses.append((response.status, ct[:40]))
		if "json" in ct:
			try:
				data = response.json()
				# 네이버는 "결과 없음" 을 result.place=null 로 시그널 — `.get(..., {})` 디폴트는
				# 키가 없을 때만 적용되므로 `or {}` 로 명시적 null 도 빈 dict 처리.
				result = data.get("result") or {}
				place = result.get("place") or {}
				places = place.get("list") or []
				if places:
					captured.extend(places)
			except Exception as e:
				parse_errors.append(str(e)[:80])

	page.on("response", handle_response)
	try:
		page.goto(
			f"https://map.naver.com/p/search/{urllib.parse.quote(query)}",
			wait_until="domcontentloaded", timeout=15000)
		page.wait_for_timeout(3000)
		if _has_naver_restriction_text(page):
			print("  [diag] 네이버 접근 제한 페이지 감지")
			blocked = True
	except Exception as e:
		print(f"  [페이지 로드 실패] {query}: {e}")
	page.remove_listener("response", handle_response)

	if not captured:
		non_200 = [(s, c) for s, c in statuses if s != 200]
		if not allsearch_seen:
			print(f"  [diag] allSearch XHR 미발사 — 페이지 로드 실패 or 차단 의심")
			blocked = True
		else:
			if non_200:
				print(f"  [diag] allSearch non-200: {non_200[:3]}")
				blocked = True
			if parse_errors:
				print(f"  [diag] JSON 파싱 에러: {parse_errors[:2]}")
			if not non_200 and not parse_errors:
				print(f"  [diag] allSearch 200 응답이나 places 비어있음 — 실제 무결과")

	return captured, blocked


def _strip_search_facility_suffix(name: str) -> str:
	if not name:
		return name
	return _SEARCH_FACILITY_SUFFIX_RE.sub("", name).strip()


def _unique_texts(values: list[str]) -> list[str]:
	seen: set[str] = set()
	out: list[str] = []
	for raw in values:
		value = (raw or "").strip()
		if not value or value in seen:
			continue
		seen.add(value)
		out.append(value)
	return out


def _relaxed_search_names(cleaned: str, base: str, category_stripped: str) -> list[str]:
	"""정확 검색이 전부 실패했을 때만 쓸 완화 이름 후보.

	지점명/입점시설 꼬리가 네이버 등록명에는 없는 throttle 실패가 많았다. 완화 이름은
	후보 0건일 때만 사용하고, 실제 매칭은 select_match 의 주소·거리 게이트가 계속 막는다.
	"""
	candidates = [base, category_stripped]
	for name in (cleaned, base, category_stripped):
		generic_stripped = strip_generic_branch_suffix(name) or name
		branch_stripped = strip_branch_suffix(generic_stripped)
		category_then_branch = strip_branch_suffix(strip_category_suffix(generic_stripped))
		for value in (branch_stripped, category_then_branch):
			candidates.append(value)
			candidates.append(_strip_search_facility_suffix(value))
	return _unique_texts(candidates)


def build_naver_search_query_groups(store_name: str, store_address: Optional[str] = None) -> tuple[list[str], list[str]]:
	"""네이버 검색 쿼리를 primary/fallback 으로 나눈다.

	primary 는 기존처럼 지역·도로명 힌트를 보존해 정확도를 우선한다. fallback 은 primary 결과가
	0건일 때만 쓰는 느슨한 검색어라 지점명/입점시설 꼬리를 일부 제거한다.
	"""
	cleaned = strip_company_suffix(store_name) or store_name
	base = strip_generic_branch_suffix(cleaned) or cleaned
	category_stripped = strip_category_suffix(base)
	district = extract_best_district(store_address or "")
	road, road_num = extract_road_and_number(store_address)
	road_name = road or extract_road_name(store_address)
	road_hint = f"{road} {road_num}".strip() if road and road_num else road_name

	primary: list[str] = []
	name_len = len(normalize_name(category_stripped or base))
	road_fallback = bool(road_hint and name_len <= 5)

	if road_fallback and name_len <= 3:
		primary.append(f"{base} {road_hint}")
		if category_stripped != base and category_stripped:
			primary.append(f"{category_stripped} {road_hint}")

	if district:
		primary.append(f"{base} {district}")
		if category_stripped != base and category_stripped:
			primary.append(f"{category_stripped} {district}")

	if road_hint:
		if district:
			primary.append(f"{base} {district} {road_hint}")
			if category_stripped != base and category_stripped:
				primary.append(f"{category_stripped} {district} {road_hint}")
		if road_fallback or road_num:
			primary.append(f"{base} {road_hint}")
			if category_stripped != base and category_stripped:
				primary.append(f"{category_stripped} {road_hint}")

	if road_fallback and name_len > 3 and road_name and road_name != road_hint:
		primary.append(f"{base} {road_name}")
		if category_stripped != base and category_stripped:
			primary.append(f"{category_stripped} {road_name}")

	fallback: list[str] = []
	relaxed_names = _relaxed_search_names(cleaned, base, category_stripped)
	for name in relaxed_names:
		if name not in (base, category_stripped):
			if district:
				fallback.append(f"{name} {district}")
			if road_hint:
				fallback.append(f"{name} {road_hint}")
		fallback.append(name)

	for name in relaxed_names:
		if "카페" in name:
			continue
		for variant in (f"카페 {name}", f"{name} 카페"):
			if district:
				fallback.append(f"{variant} {district}")
			fallback.append(variant)

	primary = _unique_texts(primary)
	fallback = [q for q in _unique_texts(fallback) if q not in set(primary)]
	return primary, fallback


def _candidate_detail_for_log(cand: dict) -> str:
	parts: list[str] = []
	place_id = cand.get("id")
	if place_id:
		parts.append(f"id={place_id}")
	category = cand.get("category") or cand.get("categoryName") or cand.get("category_name")
	if category:
		parts.append(f"cat={category}")
	for key in ("roadAddress", "roadAddressName", "road_address_name", "address"):
		value = cand.get(key)
		if value:
			parts.append(f"addr={str(value).strip()}")
			break
	return " ".join(parts)


def search_naver_candidates(
		page: Page,
		store_name: str,
		store_address: Optional[str] = None,
		diag: Optional[dict] = None) -> list[dict]:
	"""네이버 지도 검색 → 후보 places 반환.

	diag 가 주어지면 후보 0건일 때 diag["blocked"] 에 차단의심 여부를 기록한다 —
	시도한 쿼리 중 하나라도 차단 신호(allSearch 미발사/non-200)면 True, 전부 200 빈
	응답이면 False(진짜 없는 가게). 호출부(main 루프)가 이걸로 abort 여부를 가른다.

	검색 전략 (지역/도로명 후보는 dedup 해서 모은 뒤 반환):
	  1) 짧은 이름이면 "{이름} {도로명+건물번호}" 우선 — 흔한 2~5자 이름의 엉뚱한 후보 방지.
	  2) "{이름} {행정구역}" — 체인점의 진짜 매장이 1페이지에 떠오르게.
	     일반 접미사(본점/지점/호점)는 떼고 '○○점' 같은 지역 힌트는 유지.
	  3) "{카테고리 strip된 이름} {행정구역}" — '유어사이드 커피로스터리' 같이 카테고리 단어 꼬리
	     붙은 케이스에서 핵심 브랜드명으로 좁혀 재시도.
	  4) "{이름} {행정구역} {도로명+건물번호}" / "{이름} {도로명+건물번호}" —
	     행정구역 결과가 엉뚱한 짧은 이름('올'/'오리' 등) 대응.
	  5) 이름만 — 지역 인덱스 없는 케이스 fallback.
	잘못된 후보는 뒤의 select_match 가 점수+게이트로 걸러냄.
	"""
	primary_queries, fallback_queries = build_naver_search_query_groups(store_name, store_address)

	tried: set[str] = set()
	seen: set[str] = set()
	collected: list[dict] = []
	blocked_any = False

	def candidate_key(c: dict) -> str:
		return str(c.get("id") or (
			f"{c.get('name')}|{c.get('address') or c.get('roadAddress')}|"
			f"{c.get('x')}|{c.get('y')}"))

	def add_results(results: list[dict]) -> None:
		for cand in results:
			key = candidate_key(cand)
			if key in seen:
				continue
			seen.add(key)
			collected.append(cand)

	def try_query(q: str) -> list[dict]:
		nonlocal blocked_any
		q = q.strip()
		if not q or q in tried:
			return []
		tried.add(q)
		print(f"  검색: '{q}'")
		results, blocked = _naver_search_once(page, q)
		if not results and blocked:
			blocked_any = True
		if results:
			add_results(results)
		return results

	for q in primary_queries:
		try_query(q)

	if collected:
		return collected

	for q in fallback_queries:
		results = try_query(q)
		if results:
			return collected
	if diag is not None:
		diag["blocked"] = blocked_any
	return collected


def fetch_menu_data(page: Page, naver_place_id: str) -> dict[str, dict]:
	"""네이버 플레이스 메뉴 탭 DOM → {메뉴명: {"src": imageUrl, "desc": 설명, "price": int|None}}.

	JS 는 li 별 raw 텍스트 list 만 뽑아 반환. 라벨(대표/BEST/소/L 등)·가격·시가/문의 분류는
	Python 의 classify_menu_texts 가 담당 (tests/test_naver_match.py 가 검증).
	"""
	def menu_item_count() -> int:
		try:
			return int(frame.evaluate("""() => {
				const itemLinks = document.querySelectorAll('a.xPf1B').length;
				return itemLinks || document.querySelectorAll('li').length;
			}"""))
		except Exception:
			return 0

	def click_menu_expand() -> bool:
		"""메뉴 리스트 전용 '펼쳐서 더보기'만 클릭한다.

		상단 사진 영역에도 '더보기'가 있어 generic 텍스트 클릭은 photo 탭으로 이탈한다.
		따라서 정확히 '펼쳐서 더보기' 텍스트인 interactive element 만 허용한다.
		"""
		try:
			return bool(frame.evaluate("""() => {
				function norm(text) {
					return (text || '').replace(/\\s+/g, ' ').trim();
				}
				const candidates = Array.from(document.querySelectorAll('a, button, [role="button"]'));
				const button = candidates.find(el => {
					if (norm(el.textContent) !== '펼쳐서 더보기') return false;
					const href = el.getAttribute('href') || '';
					const klass = typeof el.className === 'string' ? el.className : '';
					return !/photo|place_thumb|QX0J7/.test(`${href} ${klass}`);
				});
				if (!button) return false;
				button.scrollIntoView({block: 'center', inline: 'center'});
				button.click();
				return true;
			}"""))
		except Exception:
			return False

	try:
		page.goto(
			f"https://map.naver.com/p/entry/place/{naver_place_id}",
			wait_until="domcontentloaded", timeout=15000)
		_raise_if_naver_restricted(page, "entry")
		frame = _wait_entry_frame(page)
		if not frame:
			return {}
		_raise_if_naver_restricted(frame, "entryIframe")
		m = re.search(r"pcmap\.place\.naver\.com/(\w+)/\d+", frame.url)
		place_type = m.group(1) if m else "restaurant"
		frame.goto(
			f"https://pcmap.place.naver.com/{place_type}/{naver_place_id}/menu/list",
			wait_until="domcontentloaded", timeout=10000)
		_raise_if_naver_restricted(frame, "menu/list")
		try:
			frame.wait_for_selector("li", timeout=5000)
		except Exception:
			pass
		_raise_if_naver_restricted(frame, "menu/list")
		# lazy-load 메뉴까지 스크롤로 모두 로드. 메뉴 리스트 전용 '펼쳐서 더보기'는 반복 클릭하되,
		# 상단 사진 '더보기'는 절대 클릭하지 않는다.
		initial_count = menu_item_count()
		prev = -1
		stale_rounds = 0
		for _ in range(12):
			before = menu_item_count()
			expanded = click_menu_expand()
			if expanded:
				page.wait_for_timeout(800)
			frame.evaluate("window.scrollTo(0, document.body.scrollHeight)")
			page.wait_for_timeout(700)
			count = menu_item_count()
			if count <= before and count == prev and not expanded:
				stale_rounds += 1
			else:
				stale_rounds = 0
			if stale_rounds >= 2:
				break
			prev = count
		final_count = menu_item_count()
		if final_count > initial_count:
			print(f"  [diag] 메뉴 더보기 확장(id={naver_place_id}) — {initial_count}→{final_count}")
		# 두 가지 차단:
		#  (1) 메뉴 탭 없는 가게: goto 가 SPA fallback(/home 등) 되어 리뷰 위젯의 프사·리뷰사진이
		#      메뉴로 잘못 들어가는 사례.
		#  (2) carryover: 직전 가게 iframe 이 갈아끼워지지 않아 '옆 가게 메뉴 페이지'를 읽는 사례.
		#      frame.url 에 이 place_id 가 있는지 확인해 다른 가게 메뉴 혼입을 막는다.
		if "/menu/" not in frame.url or str(naver_place_id) not in frame.url:
			print(f"  [diag] 메뉴 페이지 불일치(id={naver_place_id}) — frame URL: {frame.url[:100]}")
			return {}
		items = frame.evaluate("""() => {
			const out = [];
			document.querySelectorAll('li').forEach(li => {
				const img = li.querySelector('img[src*="pstatic"]');
				const src = img ? (img.getAttribute('src') || '') : '';
				const texts = [];
				li.querySelectorAll('span, em, strong, p, div').forEach(el => {
					if (el.childNodes.length === 1 && el.childNodes[0].nodeType === 3) {
						const t = el.textContent.trim();
						if (t) texts.push(t);
					}
				});
				const uniq = [...new Set(texts)];
				// 메뉴 li 시그널 — 가격성 텍스트 또는 pstatic 이미지 필요. 없으면 네비/탭/UI chrome.
				// 가격은 '6,000원' 뿐 아니라 '6,000'/'7500' 처럼 '원' 없이 숫자만 별도 노드로 오는
				// 레이아웃도 있어(예: 카페 디저트), 콤마 숫자·3~6자리 숫자도 가격성으로 인정.
				const hasPriceLike = uniq.some(t => /\\d.*원|시가|변동|문의/.test(t)
					|| /^\\d{1,3}(,\\d{3})+$/.test(t.trim()) || /^\\d{3,6}$/.test(t.trim()));
				if (uniq.length && (hasPriceLike || src)) out.push({ uniq, src });
			});
			return out;
		}""")
		menu_map: dict[str, dict] = {}
		for it in items:
			parsed = classify_menu_texts(it.get("uniq") or [])
			if not parsed["name"]:
				continue
			menu_map[parsed["name"]] = {
				"src": canonicalize_pstatic(it.get("src", "")),
				"desc": parsed["desc"],
				"price": parsed["price"],
			}
		return menu_map
	except NaverAccessRestricted:
		raise
	except Exception:
		return {}


def fetch_store_photos(page: Page, naver_place_id: str, limit: int = 5) -> list[str]:
	"""네이버 플레이스 사진 탭의 '업체' 필터에서 최대 N장의 이미지 URL 수집.

	entryIframe 의 photo URL 에 ?filterType=업체 쿼리로 직접 진입하여 업체 제공 사진만 노출.
	그 안에서 img[src*="pstatic"] 를 canonical path 로 그룹핑해 각 사진의 최대 사이즈 variant
	만 선택. (라이트박스/그리드/캐러셀이 같은 사진을 다른 ?type=fWxH 로 렌더링하므로 사이즈
	무시 dedup 필요). 업체 사진이 없는 가게는 빈 리스트 반환 — 방문자 사진 fallback 금지.
	"""
	try:
		page.goto(
			f"https://map.naver.com/p/entry/place/{naver_place_id}",
			wait_until="domcontentloaded", timeout=15000)
		_raise_if_naver_restricted(page, "entry")
		frame = _wait_entry_frame(page)
		if not frame:
			return []
		_raise_if_naver_restricted(frame, "entryIframe")
		m = re.search(r"pcmap\.place\.naver\.com/(\w+)/\d+", frame.url)
		place_type = m.group(1) if m else "restaurant"
		# filterType=업체 쿼리로 직접 진입 — DOM 클릭보다 안정적. 업체 사진 없는 가게는 빈 그리드.
		frame.goto(
			f"https://pcmap.place.naver.com/{place_type}/{naver_place_id}/photo?filterType=%EC%97%85%EC%B2%B4",
			wait_until="domcontentloaded", timeout=10000)
		_raise_if_naver_restricted(frame, "photo")

		# carryover 방지: 직전 가게 iframe 이 갈아끼워지지 않아 옆 가게 사진을 읽는 것 차단.
		if str(naver_place_id) not in frame.url:
			print(f"  [diag] 사진 페이지 불일치(id={naver_place_id}) — frame URL: {frame.url[:100]}")
			return []

		# pstatic 이미지 등장까지 대기 (고정 wait 대신 selector wait — 빠르고 안정적).
		# 업체 사진 0장이면 timeout 빈 그리드로 정상 종료.
		try:
			frame.wait_for_selector('img[src*="pstatic"]', timeout=5000)
		except Exception:
			pass

		# 업체 필터가 URL 만으론 안 먹은 경우 — '업체' 탭/링크 클릭 fallback.
		# 이미 활성화돼 있으면 클릭해도 무해.
		try:
			tab = frame.query_selector(
				'a:has-text("업체"), button:has-text("업체"), [role="tab"]:has-text("업체")')
			if tab:
				tab.click(timeout=2000)
				try:
					frame.wait_for_selector('img[src*="pstatic"]', timeout=3000)
				except Exception:
					pass
		except Exception:
			pass

		# 스크롤로 lazy-load 사진 추가 로딩. limit 이상 모일 때까지 최대 3회.
		for _ in range(3):
			count = frame.evaluate('document.querySelectorAll(\'img[src*="pstatic"]\').length')
			if count >= limit * 2:  # canonical dedup 후 limit 확보 위해 여유분
				break
			frame.evaluate("window.scrollBy(0, document.body.scrollHeight)")
			page.wait_for_timeout(700)

		urls = frame.evaluate("""(limit) => {
			// search.pstatic.net/common?src=ENCODED 래퍼는 내부 src 디코딩, 직접 pstatic 은 origin+pathname 사용.
			function canonical(src) {
				try {
					const u = new URL(src);
					const inner = u.searchParams.get('src');
					if (inner) {
						try {
							const iu = new URL(inner);
							return iu.origin + iu.pathname;
						} catch (e) { return inner; }
					}
					return u.origin + u.pathname;
				} catch (e) { return src; }
			}
			const seen = new Set();
			const out = [];
			document.querySelectorAll('img[src*="pstatic"]').forEach(img => {
				const src = img.getAttribute('src') || '';
				if (!src) return;
				// canonical(원본 origin+path)을 반환 — 래퍼(278px 썸네일) 대신 풀해상도 원본을
				// 저장하고, 같은 사진이 항상 동일 키로 수렴해 dedup·멱등성도 안정.
				const key = canonical(src);
				if (key && !seen.has(key)) {
					seen.add(key);
					out.push(key);
				}
			});
			return out.slice(0, limit);
		}""", limit)
		return urls or []
	except NaverAccessRestricted:
		raise
	except Exception:
		return []


# ─── 메뉴 데이터 파싱 ────────────────────────────────────────────────────────

def parse_menu_info(menu_info_str: str) -> list[dict]:
	"""네이버 menuInfo ('메뉴명 가격 | ...') 파싱. 음료 제외, MenuKind 매핑 포함."""
	if not menu_info_str:
		return []
	menus = []
	for item in menu_info_str.split("|"):
		item = item.strip()
		if not item:
			continue
		m = re.match(r"^(.+?)\s+([\d,]+)$", item)
		if m:
			name = m.group(1).strip()
			price = int(m.group(2).replace(",", ""))
		else:
			name = item
			price = None
		if is_drink(name):
			continue
		menus.append({"name": name, "price": price, "category": guess_category(name)})
	return menus


# ─── 메인 처리 ────────────────────────────────────────────────────────────────

def process_store(page: Page, store: dict, server: str, dry_run: bool, verbose: bool,
		crawl_menus: bool = True) -> dict:
	"""
	큐의 한 store 처리.
	  - placeId 이미 있음: 검색/매칭 스킵하고 바로 메뉴+사진 크롤 (어드민 수동 매칭 직후 경로)
	  - placeId 없음: 네이버 검색 → 신뢰 매칭 1건 선택 → placeId 저장 → 메뉴+사진 크롤
	  - 매칭 실패: mark_failed
	"""
	stats = {"matched": 0, "menus_created": 0, "images_uploaded": 0, "skipped_menu_crawl": 0,
			"photos_uploaded": 0, "skipped_photo_crawl": 0,
			"failed": 0, "no_candidates": False, "blocked": False, "closed_candidate": False}

	store_id = store["id"]
	store_name = store["name"]
	existing_menu_count = store.get("existingMenuCount", 0) or 0
	existing_photo_count = store.get("existingPhotoCount", 0) or 0
	existing_place_id = store.get("naverPlaceId") or None

	print(f"\n[가게] id={store_id} | {store_name}  (메뉴 {existing_menu_count}개, 사진 {existing_photo_count}장)")

	# 어드민이 placeId 를 수동 저장한 직후 경로: 검색/매칭 스킵하고 그 placeId 로 바로 크롤
	if existing_place_id:
		print(f"  ✓ placeId 이미 있음 ({existing_place_id}) — 검색 스킵하고 크롤로 점프")
		stats["matched"] = 1
		if dry_run:
			print("  [dry-run] 크롤 생략")
			return stats
		return _enrich_with_place_id(page, store_id, existing_place_id,
			existing_menu_count, existing_photo_count, None, server, stats, crawl_menus)

	search_diag: dict = {}
	candidates = search_naver_candidates(page, store_name, store.get("address"), diag=search_diag)
	if not candidates:
		blocked = search_diag.get("blocked", False)
		print("  → 네이버 검색 결과 없음" + ("  (차단 의심)" if blocked else "  (진짜 무결과)"))
		if not dry_run:
			# 차단 의심이면 파킹 카운트 제외(우리 쪽 문제) — 진짜 무결과만 +1.
			mark_failed(server, store_id, no_candidates=not blocked)
		stats["failed"] = 1
		stats["no_candidates"] = True
		stats["blocked"] = blocked
		return stats

	# 점수 + 게이트로 후보 평가 → top1 선택. select_match 가 (체인성, 거리, 도로명, 점수컷,
	# 1-2위 격차) 다 체크.
	matched_place, scored, reason = select_match(store, candidates)

	if not matched_place:
		print(f"  → 매칭 실패: {reason} (후보 {len(candidates)}개)")
		if scored and scored[0][1].get("closed"):
			stats["closed_candidate"] = True
			print("  → 네이버 폐업 후보 감지 — 자동 매칭/삭제 없이 후보로 보관")
		if verbose:
			for cand, sc in scored[:5]:
				bk = sc["breakdown"]
				dist = f"{sc['distance_m']:.0f}m" if sc["distance_m"] is not None else "-"
				detail = _candidate_detail_for_log(cand)
				detail_suffix = f" ({detail})" if detail else ""
				print(f"     - {sc['total']}점 [{sc['name_kind']}/{dist}/road{bk['road']}] {cand.get('name')}{detail_suffix}")
		if not dry_run:
			# 어드민이 직접 고를 수 있게 후보 저장 — '후보 보기' UI 가 읽음
			save_candidates(server, store_id, candidates)
			mark_failed(server, store_id)
		stats["failed"] = 1
		return stats

	naver_id = matched_place.get("id", "")
	top_score = scored[0][1]["total"]
	print(f"  ✓ 매칭: naverPlaceId={naver_id}  '{matched_place.get('name')}' ({top_score}점, {reason})")
	stats["matched"] = 1

	if dry_run:
		print("  [dry-run] 저장/등록 생략")
		return stats

	# naverPlaceId 저장 — 실패 시 크롤 중단.
	# 다른 store 가 같은 placeId 를 점유 중이면 (STORE409_4) 매칭 자체가 틀렸을 가능성이 큼 →
	# 메뉴/사진을 붙이지 말고 후보 저장 후 mark_failed 로 운영자에게 넘긴다.
	saved, err = save_naver_place_id(server, store_id, naver_id)
	if not saved:
		print(f"  → placeId 저장 실패({err}) — 메뉴/사진 크롤 중단")
		if err == "STORE409_4":
			# 다른 store 와 충돌 — 어드민이 후보 중 다른 걸 고를 수 있게 후보 보존
			save_candidates(server, store_id, candidates)
		mark_failed(server, store_id)
		stats["matched"] = 0
		stats["failed"] = 1
		return stats

	return _enrich_with_place_id(
		page, store_id, naver_id,
		existing_menu_count, existing_photo_count,
		matched_place.get("menuInfo") or "",
		server, stats, crawl_menus)


def _enrich_with_place_id(
		page: Page,
		store_id: int,
		naver_place_id: str,
		existing_menu_count: int,
		existing_photo_count: int,
		menu_info_str: str,
		server: str,
		stats: dict,
		crawl_menus: bool = True) -> dict:
	"""placeId 가 확정된 가게에 대해 메뉴 + 가게 사진을 크롤하고 업로드.

	검색 매칭 경로와 수동 매칭 경로 둘 다에서 사용.
	  - menu_info_str 있음: 네이버 search 응답의 menuInfo 를 1차 소스로 사용 (가격 보장)
	  - menu_info_str 없음: 메뉴 탭 DOM 을 1차 소스로 사용 (수동 매칭 경로)
	  - crawl_menus=False (photos-only 백필): 메뉴는 건드리지 않고 가게 사진만 처리
	"""
	# photos-only 백필은 메뉴를 건드리지 않음(사진만 보충). enrich 경로는 기존 메뉴 수와 무관하게
	# 항상 match-and-enrich — 백엔드 createMenu 가 정규화 이름으로 기존 메뉴를 매칭해 누락된
	# price/desc/이미지를 보강하거나(중복 생성 X) 없으면 생성한다. 덕분에 LocalData 한글 메뉴의
	# 빈 가격·설명·사진이 채워지고, 재실행해도 메뉴가 중복되지 않는다.
	if not crawl_menus:
		stats["skipped_menu_crawl"] = 1
	else:
		try:
			# 메뉴 탭 DOM (수동 매칭 → 1차 소스, 검색 매칭 → 이미지/설명/fallback 가격)
			place_menu_map = fetch_menu_data(page, naver_place_id) if naver_place_id else {}
			# iframe 로드 타이밍 flakiness 로 비어 나오는 경우 1회 재시도 (메뉴 있는데 0개로 누락 방지).
			if not place_menu_map and naver_place_id:
				place_menu_map = fetch_menu_data(page, naver_place_id)
		except NaverAccessRestricted:
			print("  → 네이버 접근 제한 감지 — 백필 완료 마킹 없이 중단")
			stats["failed"] = 1
			stats["blocked"] = True
			return stats
		if menu_info_str:
			primary_menus = parse_menu_info(menu_info_str)
		else:
			# 수동 매칭 경로: 메뉴 탭 DOM 만으로 구성. 음료/카테고리 추정은 동일 규칙.
			primary_menus = [
				{"name": name, "price": data.get("price"), "category": guess_category(name)}
				for name, data in place_menu_map.items()
				if not is_drink(name)
			]

		if not primary_menus:
			print("  → 네이버에 메뉴 정보 없음")
		else:
			def match_menu_extra(menu_name: str) -> dict:
				if menu_name in place_menu_map:
					return place_menu_map[menu_name]
				n_norm = re.sub(r"\(.*?\)", "", menu_name).replace(" ", "")
				return next(
					(v for k, v in place_menu_map.items()
					 if re.sub(r"\(.*?\)", "", k).replace(" ", "") == n_norm),
					{},
				)

			dom_img_count = sum(1 for data in place_menu_map.values() if data.get("src"))
			matched_img_count = sum(
				1 for menu in primary_menus
				if match_menu_extra(menu["name"]).get("src")
			)
			if menu_info_str and (len(place_menu_map) < len(primary_menus)
					or matched_img_count < len(primary_menus)):
				print(
					"  [diag] 메뉴 DOM 매칭 — "
					f"search={len(primary_menus)}, dom={len(place_menu_map)}, "
					f"dom_img={dom_img_count}, matched_img={matched_img_count}"
				)

			for menu in primary_menus:
				extra = match_menu_extra(menu["name"])
				img_url = extra.get("src") or None
				desc = extra.get("desc") or None
				# 검색 menuInfo 에 가격 없으면 DOM 가격으로 보완
				price = menu.get("price") if menu.get("price") is not None else extra.get("price")

				# 기존 메뉴면 백엔드가 매칭해 보강 후 그 id 반환, 신규면 생성.
				menu_id = create_menu(server, store_id, menu["name"], price, menu["category"], desc)
				if not menu_id:
					continue
				stats["menus_created"] += 1
				price_str = f"{price}원" if price else "-"
				print(f"    + 메뉴 [{menu_id}] {menu['name']}  {price_str}")

				# 이미지는 source_url(canonical) 멱등성으로 중복 방지 — 기존 메뉴에 같은 사진이
				# 이미 있으면 백엔드가 SKIP, 없으면 추가(한글 메뉴 사진 보강).
				if img_url:
					if upload_images_from_urls(server, "menus", menu_id, [img_url]) > 0:
						stats["images_uploaded"] += 1
						print(f"      └ 이미지 업로드 완료")

				time.sleep(0.1)

	# 가게 사진 — 기존 사진 0장일 때만
	if existing_photo_count > 0:
		print(f"  → 가게 사진 {existing_photo_count}장 보유 — 사진 크롤링 생략")
		stats["skipped_photo_crawl"] = 1
	else:
		try:
			photo_urls = fetch_store_photos(page, naver_place_id, limit=STORE_PHOTO_LIMIT) if naver_place_id else []
			# iframe 로드 flakiness 로 비어 나오면 1회 재시도 (사진 있는데 0장으로 누락 방지).
			if not photo_urls and naver_place_id:
				photo_urls = fetch_store_photos(page, naver_place_id, limit=STORE_PHOTO_LIMIT)
		except NaverAccessRestricted:
			print("  → 네이버 접근 제한 감지 — 백필 완료 마킹 없이 중단")
			stats["failed"] = 1
			stats["blocked"] = True
			return stats
		if not photo_urls:
			print("  → 네이버 사진 탭에서 이미지 못 찾음")
		else:
			print(f"  → 가게 사진 {len(photo_urls)}장 업로드 시도")
			uploaded = upload_images_from_urls(server, "stores", store_id, photo_urls)
			stats["photos_uploaded"] += uploaded
			print(f"      └ 가게 사진 {uploaded}/{len(photo_urls)}장 업로드 완료")

	return stats


def main() -> None:
	parser = argparse.ArgumentParser(description="네이버 플레이스 메뉴 크롤러 (빵긋 큐 연동)")
	parser.add_argument("--server", default=DEFAULT_SERVER, help=f"백엔드 (기본: {DEFAULT_SERVER})")
	parser.add_argument("--batch", type=int, default=QUEUE_BATCH_SIZE, help=f"한 번에 fetch 할 큐 크기 (기본: {QUEUE_BATCH_SIZE})")
	parser.add_argument("--loop", action="store_true", help="큐가 빌 때까지 반복 (각 배치 사이 5초 대기)")
	parser.add_argument("--max-batches", type=int, default=0, help="최대 배치 수 (0 = 무제한)")
	parser.add_argument("--store-id", type=int, default=0,
		help="단일 가게 모드 — 지정한 store id 만 처리하고 종료 (어드민 'Enrich' 버튼용)")
	parser.add_argument("--dry-run", action="store_true", help="매칭만 시뮬레이션, 실제 저장 X")
	parser.add_argument("--verbose", action="store_true", help="신뢰 매칭 실패 후보까지 출력")
	parser.add_argument("--photos-only", action="store_true",
		help="매칭됨+사진 0장 큐만 처리 — 검색은 스킵하고 사진만 보충 (process_store 분기로 자동 처리)")
	parser.add_argument("--enrich-backfill", action="store_true",
		help="매칭됨(placeId 있음)인데 메뉴 OR 사진 빈 큐 처리 — 검색 스킵, 메뉴+사진 둘 다 풀 보충")
	parser.add_argument("--partition", choices=["P1", "P2", "P3"],
		help="작업 파티션(큐 모드 필수): P1/P2/P3 = store id 기준 균등 분배")
	parser.add_argument("--headless", action="store_true", default=True, help="브라우저 창 숨김 (기본 ON)")
	parser.add_argument("--show-browser", dest="headless", action="store_false", help="브라우저 창 표시")
	args = parser.parse_args()

	# 모드 결정 — 어느 큐를 어떤 깊이로 처리할지. photos-only(사진만) / enrich-backfill(메뉴+사진 풀 보충) /
	# enrich(검색+매칭+enrich). backfill 모드들은 placeId 가 이미 있어 process_store 가 검색을 스킵한다.
	if args.photos_only:
		mode, queue_path, crawl_menus = "photos-backfill", "naver-photos-backfill-queue", False
	elif args.enrich_backfill:
		mode, queue_path, crawl_menus = "enrich-backfill", "naver-enrich-backfill-queue", True
	else:
		mode, queue_path, crawl_menus = "enrich", "naver-enrich-queue", True

	print(f"서버: {args.server}")
	if args.store_id:
		print(f"단일 가게 모드: store_id={args.store_id}")
	else:
		print(f"큐 모드 [{mode}] — 배치 크기: {args.batch}, loop={args.loop}, dry-run={args.dry_run}")
		if args.partition:
			print(f"작업 파티션: {args.partition}")
		else:
			print("작업 파티션: 전체")

	total = {"matched": 0, "menus_created": 0, "images_uploaded": 0, "skipped_menu_crawl": 0,
			"photos_uploaded": 0, "skipped_photo_crawl": 0, "failed": 0,
			"no_candidates": 0, "mismatch": 0, "closed_suspects": 0, "blocked": 0}

	with sync_playwright() as p:
		browser = p.chromium.launch(
			headless=args.headless,
			args=["--disable-blink-features=AutomationControlled"],
		)
		context = browser.new_context(user_agent=NAVER_UA, viewport={"width": 1280, "height": 800})
		context.add_init_script(
			"Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
		)
		page = context.new_page()

		if args.store_id:
			# Option A: 단일 가게 모드 — 큐 무시하고 지정한 가게만 처리
			store = fetch_single_store(args.server, args.store_id)
			if not store:
				print(f"[종료] store_id={args.store_id} fetch 실패")
				browser.close()
				sys.exit(2)
			result = process_store(page, store, args.server, args.dry_run, args.verbose,
				crawl_menus=crawl_menus)
			if mode in ("photos-backfill", "enrich-backfill") and not args.dry_run and not result.get("blocked"):
				mark_backfill_attempted(args.server, store["id"], "photos" if mode == "photos-backfill" else mode)
			for k in ("matched", "menus_created", "images_uploaded", "skipped_menu_crawl",
					"photos_uploaded", "skipped_photo_crawl", "failed"):
				total[k] += result.get(k, 0)
			if result.get("no_candidates"):
				total["no_candidates"] += 1
			elif result.get("closed_candidate"):
				total["closed_suspects"] += 1
			elif result.get("failed"):
				total["mismatch"] += 1
			if result.get("blocked"):
				total["blocked"] += 1
		else:
			# 큐 모드 (Option B 또는 운영자 CLI)
			batches_done = 0
			blocked_streak = 0
			while True:
				queue = fetch_queue(args.server, args.batch, path=queue_path, partition=args.partition)
				if not queue:
					print("\n[큐 비어있음 — 종료]")
					break
				print(f"\n=== 배치 #{batches_done + 1} — {len(queue)}건 ===")
				for store in queue:
					result = process_store(page, store, args.server, args.dry_run, args.verbose,
						crawl_menus=crawl_menus)
					if mode in ("photos-backfill", "enrich-backfill") and not args.dry_run and not result.get("blocked"):
						mark_backfill_attempted(args.server, store["id"], "photos" if mode == "photos-backfill" else mode)
					for k in ("matched", "menus_created", "images_uploaded", "skipped_menu_crawl",
							"photos_uploaded", "skipped_photo_crawl", "failed"):
						total[k] += result.get(k, 0)
					if result.get("no_candidates"):
						total["no_candidates"] += 1
					elif result.get("closed_candidate"):
						total["closed_suspects"] += 1
					elif result.get("failed"):
						total["mismatch"] += 1
					if result.get("blocked"):
						total["blocked"] += 1
					# 네이버 차단 의심 — '차단 신호(allSearch 미발사/non-200)' 검색이 N건 연속이면
					# abort. 200 빈응답(진짜 없는 가게)은 abort 안 함 — 못 찾는 가게가 큐에 몰려도
					# 배치 전체를 죽이지 않고 mark_failed 후 계속 진행(대량 false-abort 방지).
					if result.get("blocked"):
						blocked_streak += 1
						if blocked_streak >= NO_CANDIDATE_ABORT_THRESHOLD:
							print(f"\n[ABORT] 차단 신호 {blocked_streak}건 연속 — 네이버 차단 의심.")
							print(f"        위의 [diag] 로그 확인. IP/UA/요청간격 점검 후 재시도하세요.")
							print(f"        실패 마킹된 가게는 /naver-enrich-retry 로 throttle 해제 가능.")
							browser.close()
							sys.exit(3)
					else:
						blocked_streak = 0
					time.sleep(INTER_STORE_DELAY_SEC + random.uniform(0, INTER_STORE_JITTER_SEC))
				batches_done += 1
				if not args.loop:
					break
				if args.max_batches and batches_done >= args.max_batches:
					print(f"\n[max-batches {args.max_batches} 도달 — 종료]")
					break
				print(f"\n--- 다음 배치 {INTER_BATCH_DELAY_SEC:.0f}초 대기 ---")
				time.sleep(INTER_BATCH_DELAY_SEC)

		browser.close()

	print(f"\n{'=' * 60}")
	print(json.dumps(total, ensure_ascii=False, indent=2))


if __name__ == "__main__":
	main()
