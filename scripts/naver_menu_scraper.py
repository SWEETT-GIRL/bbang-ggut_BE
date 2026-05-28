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
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
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


# ─── 설정 ─────────────────────────────────────────────────────────────────────

DEFAULT_SERVER = "http://localhost:8080"
DEFAULT_CATEGORY = "빵"  # MenuKind 한글 enum 의 fallback
QUEUE_BATCH_SIZE = 50
STORE_PHOTO_LIMIT = 5
INTER_STORE_DELAY_SEC = 1.0
MAX_LOCATION_DISTANCE_M = 100  # 매칭 신뢰 거리 임계
# 네이버 차단 의심 — 큐 모드에서 N건 연속 검색 결과 0이면 abort (대량 false-fail 방지).
# 정상 baseline 누락률은 낮으므로 5건 연속은 차단 시그널로 충분.
NO_CANDIDATE_ABORT_THRESHOLD = 5

NAVER_UA = (
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
	"AppleWebKit/537.36 (KHTML, like Gecko) "
	"Chrome/124.0.0.0 Safari/537.36"
)

MIME_MAP = {
	".jpg": "image/jpeg", ".jpeg": "image/jpeg",
	".png": "image/png", ".webp": "image/webp",
}

# 음료·주류 — 베이커리 도메인이라 제외
DRINK_KEYWORDS = [
	"아메리카노", "에스프레소", "라떼", "카푸치노", "플랫화이트",
	"콜드브루", "드립커피", "커피",
	"얼그레이", "루이보스", "페퍼민트", "캐모마일", "티", "차이",
	"주스", "에이드", "스무디", "쉐이크",
	"맥주", "와인", "샴페인", "하이볼",
	"우유", "두유", "오트",
	"초코라떼", "말차라떼", "차이라떼",
	"리얼 초콜릿",
]

# 메뉴 이름 → MenuKind 한글 enum 매핑 (앞에서부터 첫 매칭). MenuKind 에 없는 값은 사용 금지.
CATEGORY_RULES: list[tuple[list[str], str]] = [
	(["소금빵"], "소금빵"),
	(["크루아상", "크로와상", "크로아상"], "크루아상"),
	(["퀸아망", "쿠인아망"], "퀸아망"),
	(["바게트", "바겟"], "바게트"),
	(["식빵"], "식빵"),
	(["베이글"], "베이글"),
	(["샌드위치", "샌드"], "샌드위치"),
	(["스콘"], "스콘"),
	(["쿠키"], "쿠키"),
	(["케이크"], "케이크"),
	(["타르트"], "타르트"),
	(["마카롱"], "마카롱"),
	(["도넛", "도너츠"], "도넛"),
	(["브라우니"], "브라우니"),
	(["휘낭시에"], "휘낭시에"),
	(["마들렌"], "마들렌"),
	(["까눌레"], "까눌레"),
	(["프레첼"], "프레첼"),
	(["사워도우"], "사워도우"),
	(["깜빠뉴"], "깜빠뉴"),
	(["치아바타"], "치아바타"),
	(["포카치아"], "포카치아"),
	(["브리오슈"], "브리오슈"),
	(["페이스트리"], "페이스트리"),
	(["에그타르트"], "에그타르트"),
	(["마늘빵", "마늘"], "마늘빵"),
	(["소보로"], "소보로"),
	(["앙버터"], "앙버터"),
	(["크로플"], "크로플"),
	(["크로크무슈"], "크로크무슈"),
	(["고구마빵", "고구마"], "고구마빵"),
	(["밤빵"], "밤빵"),
	(["소세지빵", "소시지빵"], "소세지빵"),
	(["버터떡"], "버터떡"),
	(["떡"], "떡"),
	(["번"], "번"),
	(["롤"], "롤"),
	(["파이"], "파이"),
	(["푸딩"], "푸딩"),
	(["러스크"], "러스크"),
	(["에끌레어"], "에끌레어"),
	(["슈"], "슈"),
	(["몽블랑"], "몽블랑"),
	(["고로케"], "고로케"),
	(["버터바"], "버터바"),
	(["파운드"], "파운드"),
	(["티라미수"], "티라미수"),
	(["빙수"], "빙수"),
]


# ─── 매칭 신뢰도 검증 ─────────────────────────────────────────────────────────

# 매장 접미사 제거 — 공백 있는 상태에서 적용 (공백을 어휘 경계로 사용).
# 예: '테라로사 본점' → '테라로사', '카페드파리 성남본점' → '카페드파리',
#      '올선데이 광안직영점' → '올선데이', '포스톤즈 커피로스터스 삼청점' → '포스톤즈 커피로스터스',
#      '만 1호점' → '만'.
# 공백 없이 붙어 있는 케이스 (예: '올유캔잇본점')는 strip 안 되지만, 뒤의 substring 비교가 대응.
_BRANCH_SUFFIX_RE = re.compile(r"\s+(?:\d+호점|[가-힣a-zA-Z0-9]+점|매장|플래그십|플래그쉽)$")

# 검색어용 — 위치 정보 없는 일반 접미사만 제거. '강남점/광교앨리웨이점' 같이 지역 힌트 포함된 ○○점은 보존
# (지점명이 들어가야 체인점에서 네이버 결과 1페이지에 진짜 매장이 떠오름).
# 공백 없이 붙은 케이스도 strip — '서초본점' → '서초' (지역 힌트는 유지), '광안직영점' → '광안'.
# '1호점/2호점' 도 위치 정보 없는 일반 접미사로 취급 (호점 번호만으로는 지역 추정 불가).
# 공백 + 단독 '점' (예: '갤러리아west 점') 도 떼어냄.
_GENERIC_BRANCH_SUFFIX_RE = re.compile(r"(?:\s+점|\s*(?:본점|지점|매장|플래그십|플래그쉽|직영점|\d+호점))$")

# 회사명/사업자명 노이즈 — 카카오 데이터에 사업자등록명이 같이 붙어있는 케이스.
# 예: '브래드무어 주식회사더크리스마스컴퍼니' → '브래드무어'.
# 공백 있는 분리 + 공백 없이 붙은 두 케이스 모두 처리. 어휘 앞쪽 (브랜드명) 만 보존.
_COMPANY_SUFFIX_RE = re.compile(
	r"\s*(?:주식회사|\(주\)|㈜|\(유\)|유한회사|\(합\)|합자회사).*$"
)


def strip_company_suffix(name: str) -> str:
	"""사업자명 꼬리 제거. 브랜드 이름이 회사명 앞에 와있는 케이스 전제."""
	if not name:
		return name
	return _COMPANY_SUFFIX_RE.sub("", name).strip()


# 검색어에서 떼어내볼 카테고리 단어 — 1차 검색 실패 시 strip 후 재시도용.
# '유어사이드 커피로스터리' → '유어사이드', '비파티세리 신세계센트럴시티점' → '비파티세리'.
# 매칭 검증(name_matches)은 원본 이름과 비교하므로 strip 결과는 검색 쿼리에만 영향.
_CATEGORY_SUFFIX_RE = re.compile(
	r"\s+(?:커피로스터리|커피로스터스|로스터리|로스터스|베이커리|빵집|카페|커피"
	r"|레스토랑|파티세리|파티쎄리|부띠끄|부티크|케이크부티크|케이크부띠끄|디저트"
	r"|키친|하우스|스튜디오)$"
)


def strip_category_suffix(name: str) -> str:
	"""'유어사이드 커피로스터리' 같이 카테고리 단어가 꼬리에 붙은 경우 검색용으로 떼어냄."""
	if not name:
		return name
	return _CATEGORY_SUFFIX_RE.sub("", name).strip()


def strip_branch_suffix(name: str) -> str:
	"""이름 끝의 매장 접미사 1회 제거 (공백으로 어휘 경계 확보)."""
	if not name:
		return name
	return _BRANCH_SUFFIX_RE.sub("", name)


def strip_generic_branch_suffix(name: str) -> str:
	"""검색어용: '본점/지점/매장' 등 위치 정보 없는 접미사만 제거. '강남점' 같은 지역 힌트는 유지."""
	if not name:
		return name
	return _GENERIC_BRANCH_SUFFIX_RE.sub("", name)


def normalize_name(name: str) -> str:
	"""이름 정규화: HTML/괄호 제거 → 사업자명/매장 접미사 제거 → 공백/특수문자 제거 → &↔앤 → 소문자."""
	if not name:
		return ""
	s = re.sub(r"<[^>]+>", "", name)             # HTML 태그 (네이버 응답 일부에 있음)
	s = re.sub(r"\(.*?\)|\[.*?\]", "", s)         # 괄호 내용
	s = s.strip()
	s = strip_company_suffix(s)                   # '주식회사X' 노이즈 제거 (카카오 데이터 특성)
	s = strip_branch_suffix(s)                    # 공백 있는 상태에서 매장 접미사 제거
	s = re.sub(r"\s+", "", s)                     # 모든 공백
	s = re.sub(r"[^\w가-힣]", "", s)               # 특수문자 ('&' 등 제거)
	s = s.replace("앤", "")                       # '앤'(한글 '&' 표기) 제거 — Kakao '&' ↔ Naver '앤' 등가 처리
	return s.lower()


def name_matches(a: str, b: str) -> bool:
	"""정규화된 이름 비교.
	- exact 일치 → 통과
	- prefix 관계(브랜드명 + 수식어): 짧은 쪽 ≥ 2자, 길이비 ≥ 40%
	- mid-string substring: 짧은 쪽 ≥ 3자, 길이비 ≥ 60% (mid 는 오매칭 위험 커서 그대로 엄격)
	2자 한글 브랜드(베통/공차 등) 및 '브랜드 + 길게 붙는 수식어' 케이스 대응. 오매칭은
	뒤의 is_reliable_match 의 주소/거리 게이트가 받음.
	"""
	if not a or not b:
		return False
	if a == b:
		return True
	shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
	if len(shorter) < 2:
		return False
	ratio = len(shorter) / len(longer)
	if longer.startswith(shorter):
		return ratio >= 0.4
	return len(shorter) >= 3 and shorter in longer and ratio >= 0.6


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
	"""두 위경도 간 거리(미터)."""
	r = 6371000.0
	phi1, phi2 = math.radians(lat1), math.radians(lat2)
	dphi = math.radians(lat2 - lat1)
	dlam = math.radians(lng2 - lng1)
	a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
	return 2 * r * math.asin(math.sqrt(a))


_DIST_SUFFIXES = ("구", "동", "읍", "면", "리", "시", "군")

# 검색어에 부착할 행정 토큰 우선순위 — 좁은 단위가 먼저 (구정면이 강릉시보다 정확함).
# endswith 매칭이므로 '구정면'은 '면'에 매칭되어 '구' 보다 먼저 시도됨.
_DIST_PRIORITY = ("동", "읍", "면", "리", "구", "시", "군")


def extract_district_tokens(address: str) -> set[str]:
	"""주소 문자열에서 행정구역 토큰만 추출 (광역시/구/동/...). 비교용 set."""
	if not address:
		return set()
	tokens = re.split(r"\s+", address.strip())
	return {t for t in tokens if t and t.endswith(_DIST_SUFFIXES)}


def extract_best_district(address: str) -> str:
	"""주소에서 가장 구체적인 행정 토큰 한 개 반환 (없으면 빈 문자열).
	검색어에 부착해 네이버 결과 1페이지에 진짜 매장이 떠오르도록 좁히는 용도.
	"""
	if not address:
		return ""
	tokens = re.split(r"\s+", address.strip())
	for suffix in _DIST_PRIORITY:
		for t in tokens:
			# len > 1 — '구'/'시' 한 글자 토큰 (도로명 일부) 배제
			if t.endswith(suffix) and len(t) > 1:
				return t
	return ""


def is_reliable_match(kakao: dict, naver: dict) -> tuple[bool, str]:
	"""
	카카오 store ↔ 네이버 place 매칭 신뢰도.
	  통과 조건: 이름 정규화 일치 AND (주소 구/동 겹침 OR 좌표 100m 이내)
	반환: (신뢰 여부, 사유 문자열)
	"""
	k_name = normalize_name(kakao.get("name", ""))
	n_name = normalize_name(naver.get("name", ""))
	if not k_name or not n_name:
		return False, "이름 비어있음"
	if not name_matches(k_name, n_name):
		return False, f"이름 불일치 '{kakao.get('name')}' ≠ '{naver.get('name')}'"

	# 주소 구/동 겹침
	k_dist = extract_district_tokens(kakao.get("address", ""))
	n_addr = naver.get("address") or naver.get("roadAddress") or ""
	n_dist = extract_district_tokens(n_addr)
	addr_match = bool(k_dist & n_dist)

	# 좌표 100m 이내
	loc_match = False
	dist_m = None
	try:
		k_lat = kakao.get("lat")
		k_lng = kakao.get("lng")
		n_lat = float(naver.get("y") or 0)
		n_lng = float(naver.get("x") or 0)
		if k_lat and k_lng and n_lat and n_lng:
			dist_m = haversine_m(k_lat, k_lng, n_lat, n_lng)
			loc_match = dist_m < MAX_LOCATION_DISTANCE_M
	except (TypeError, ValueError):
		pass

	if addr_match or loc_match:
		why = []
		if addr_match:
			why.append(f"동 겹침({','.join(k_dist & n_dist)})")
		if loc_match:
			why.append(f"거리 {dist_m:.0f}m")
		return True, " + ".join(why)
	return False, f"주소·좌표 모두 불일치 (dist={dist_m}m if known)"


# ─── 백엔드 API 클라이언트 ────────────────────────────────────────────────────

def fetch_queue(server: str, size: int, photos_only: bool = False) -> list[dict]:
	path = "naver-photos-backfill-queue" if photos_only else "naver-enrich-queue"
	url = f"{server}/api/admin/stores/{path}?page=0&size={size}"
	resp = requests.get(url, timeout=15)
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


def save_naver_place_id(server: str, store_id: int, naver_place_id: str) -> bool:
	url = f"{server}/api/admin/stores/{store_id}/naver-place-id"
	try:
		resp = requests.put(url, json={"naverPlaceId": naver_place_id}, timeout=10)
		resp.raise_for_status()
		return True
	except Exception as e:
		print(f"  [naverPlaceId 저장 실패] store_id={store_id}: {e}")
		return False


def mark_failed(server: str, store_id: int) -> None:
	url = f"{server}/api/admin/stores/{store_id}/naver-enrich-failed"
	try:
		requests.post(url, timeout=10)
	except Exception as e:
		print(f"  [실패 마킹 실패] store_id={store_id}: {e}")


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
			"address": c.get("address") or c.get("roadAddress"),
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


def get_presigned_url(server: str, domain: str, target_id: int, mime: str) -> dict:
	url = f"{server}/admin/images/presigned-url"
	resp = requests.post(url, json={"domain": domain, "targetId": target_id, "mimeType": mime}, timeout=10)
	resp.raise_for_status()
	return resp.json()


def upload_to_s3(upload_url: str, data: bytes, mime: str) -> None:
	resp = requests.put(upload_url, data=data, headers={"Content-Type": mime}, timeout=30)
	resp.raise_for_status()


def download_bytes(url: str) -> bytes:
	req = urllib.request.Request(url, headers={"User-Agent": NAVER_UA})
	with urllib.request.urlopen(req, timeout=15) as r:
		return r.read()


def save_image_metadata(server: str, domain: str, target_id: int, file_name: str,
						aws_key: str, mime: str) -> None:
	"""백엔드가 displayOrder 자동 부여 — 페이로드에서 생략 가능."""
	url = f"{server}/admin/images/{domain}/{target_id}"
	resp = requests.post(url, json=[{
		"fileName": file_name,
		"awsKey": aws_key,
		"mimeType": mime,
	}], timeout=10)
	resp.raise_for_status()


def detect_mime(url: str) -> str:
	qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
	inner = qs.get("src", [""])[0]
	target = inner if inner else url
	ext = os.path.splitext(target.split("?")[0])[-1].lower()
	return MIME_MAP.get(ext, "image/jpeg")


def upload_menu_image(server: str, menu_id: int, image_url: str) -> bool:
	mime = detect_mime(image_url)
	try:
		presigned = get_presigned_url(server, "menus", menu_id, mime)
		data = download_bytes(image_url)
		upload_to_s3(presigned["uploadUrl"], data, mime)
		save_image_metadata(server, "menus", menu_id, presigned["fileName"], presigned["awsKey"], mime)
		return True
	except Exception as e:
		print(f"  [이미지 업로드 실패] menu_id={menu_id}: {e}")
		return False


# ─── 네이버 크롤링 (Playwright) ──────────────────────────────────────────────

def _naver_search_once(page: Page, query: str) -> list[dict]:
	"""네이버 지도 한 번 검색 → allSearch 응답 가로채서 후보 places 반환.

	0건일 때만 진단 로그(응답 상태/파싱 오류/allSearch 미발사) 출력 — 정상 케이스는 노이즈 X.
	"""
	captured: list[dict] = []
	statuses: list[tuple[int, str]] = []  # (status, content-type) — allSearch 응답만
	parse_errors: list[str] = []
	allsearch_seen = False

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
	except Exception as e:
		print(f"  [페이지 로드 실패] {query}: {e}")
	page.remove_listener("response", handle_response)

	if not captured:
		if not allsearch_seen:
			print(f"  [diag] allSearch XHR 미발사 — 페이지 로드 실패 or 차단 의심")
		else:
			non_200 = [(s, c) for s, c in statuses if s != 200]
			if non_200:
				print(f"  [diag] allSearch non-200: {non_200[:3]}")
			if parse_errors:
				print(f"  [diag] JSON 파싱 에러: {parse_errors[:2]}")
			if not non_200 and not parse_errors:
				print(f"  [diag] allSearch 200 응답이나 places 비어있음 — 실제 무결과")

	return captured


def _extract_road_name(address: Optional[str]) -> str:
	"""주소에서 도로명 토큰 (○○로/○○길) 한 개 반환. 짧은 가게명 검색 보강용.
	'서울 관악구 남부순환로 1850' → '남부순환로', '경기 용인시 기흥구 공세로 73' → '공세로'.
	"""
	if not address:
		return ""
	for tok in re.split(r"\s+", address.strip()):
		if tok.endswith(("로", "길")) and len(tok) >= 3:
			return tok
	return ""


def search_naver_candidates(page: Page, store_name: str, store_address: Optional[str] = None) -> list[dict]:
	"""네이버 지도 검색 → 후보 places 반환.

	검색 전략 (각 단계에서 결과 나오면 즉시 반환):
	  1) "{이름} {행정구역}" — 체인점의 진짜 매장이 1페이지에 떠오르게.
	     일반 접미사(본점/지점/호점)는 떼고 '○○점' 같은 지역 힌트는 유지.
	  2) "{카테고리 strip된 이름} {행정구역}" — '유어사이드 커피로스터리' 같이 카테고리 단어 꼬리
	     붙은 케이스에서 핵심 브랜드명으로 좁혀 재시도.
	  3) "{이름} {도로명}" — 행정구역만으로 좁혀지지 않는 짧은 이름('올'/'오리' 등) 대응.
	  4) 이름만 — 지역 인덱스 없는 케이스 fallback.
	잘못된 후보는 뒤의 is_reliable_match 가 걸러냄.
	"""
	# 사업자명 노이즈 먼저 제거 (브랜드명만 남김)
	cleaned = strip_company_suffix(store_name) or store_name
	base = strip_generic_branch_suffix(cleaned) or cleaned
	category_stripped = strip_category_suffix(base)
	district = extract_best_district(store_address or "")
	road = _extract_road_name(store_address)

	tried: set[str] = set()

	def try_query(q: str) -> Optional[list[dict]]:
		q = q.strip()
		if not q or q in tried:
			return None
		tried.add(q)
		print(f"  검색: '{q}'")
		results = _naver_search_once(page, q)
		return results if results else None

	if district:
		results = try_query(f"{base} {district}")
		if results:
			return results
		# 카테고리 단어 strip 결과가 다르면 한 번 더 시도
		if category_stripped != base and category_stripped:
			results = try_query(f"{category_stripped} {district}")
			if results:
				return results

	# 짧은 이름은 도로명으로 좁혀야 1페이지에 진짜 매장이 떠오름
	if road and len(base) <= 3:
		results = try_query(f"{base} {road}")
		if results:
			return results

	# 최종 fallback — 이름만
	results = try_query(base)
	if results:
		return results
	if category_stripped != base and category_stripped:
		results = try_query(category_stripped)
		if results:
			return results
	return []


def fetch_menu_data(page: Page, naver_place_id: str) -> dict[str, dict]:
	"""네이버 플레이스 메뉴 탭 DOM → {메뉴명: {"src": imageUrl, "desc": 설명, "price": int|None}}.

	어드민이 placeId 를 수동 매칭한 경로에서는 menuInfo (검색결과 문자열) 가 없어서
	DOM 에서 직접 가격도 같이 긁어와야 함. priceRe 매칭 텍스트가 1개면 가격으로 채택.
	"""
	try:
		page.goto(
			f"https://map.naver.com/p/entry/place/{naver_place_id}",
			wait_until="domcontentloaded", timeout=15000)
		page.wait_for_timeout(2500)
		frame = page.frame("entryIframe")
		if not frame:
			return {}
		m = re.search(r"pcmap\.place\.naver\.com/(\w+)/\d+", frame.url)
		place_type = m.group(1) if m else "restaurant"
		frame.goto(
			f"https://pcmap.place.naver.com/{place_type}/{naver_place_id}/menu/list",
			wait_until="domcontentloaded", timeout=10000)
		page.wait_for_timeout(2000)
		# 메뉴 탭 없는 가게: 위 goto 가 SPA fallback (/home 등) 되어 리뷰 위젯의
		# 사용자 프사·리뷰 사진이 메뉴 이미지로 잘못 들어가는 사례 차단.
		# /menu/ path 가 아니면 abort + 진단 로그.
		if "/menu/" not in frame.url:
			print(f"  [diag] 메뉴 탭 없음 — frame fallback URL: {frame.url[:100]}")
			return {}
		items = frame.evaluate("""() => {
			const priceRe = /^[\\d,]+원?$/;
			const results = [];
			document.querySelectorAll('li').forEach(li => {
				const img = li.querySelector('img[src*="pstatic"]');
				const src = img ? (img.getAttribute('src') || '') : '';
				const texts = [];
				li.querySelectorAll('span, em, strong, p, div').forEach(el => {
					if (el.childNodes.length === 1 && el.childNodes[0].nodeType === 3) {
						const t = el.textContent.trim();
						if (t && t.length > 1) texts.push(t);
					}
				});
				const uniq = [...new Set(texts)].filter(t => t !== '대표');
				const priceTexts = uniq.filter(t => priceRe.test(t));
				const nonPrice = uniq.filter(t => !priceRe.test(t));
				const name = nonPrice[0] || '';
				const desc = nonPrice[1] || '';
				const price = priceTexts[0] || '';
				// 메뉴 li 최소 시그널 — 가격(○○원) 이나 pstatic 이미지 없으면
				// 네비/탭/UI chrome 이라 메뉴로 인식하면 안 됨.
				if (name && (price || src)) results.push({ name, src, desc, price });
			});
			return results;
		}""")
		def parse_price(p: str) -> Optional[int]:
			if not p:
				return None
			digits = p.replace(",", "").replace("원", "").strip()
			return int(digits) if digits.isdigit() else None
		return {
			it["name"]: {
				"src": it.get("src", ""),
				"desc": it.get("desc", ""),
				"price": parse_price(it.get("price", "")),
			}
			for it in items if it.get("name")
		}
	except Exception:
		return {}


def fetch_store_photos(page: Page, naver_place_id: str, limit: int = 5) -> list[str]:
	"""네이버 플레이스 사진 탭에서 최대 N장의 이미지 URL 수집.

	메뉴 탭과 동일한 entryIframe 경로 (pcmap.place.naver.com/{type}/{id}/photo) 를 따라간 후
	img[src*="pstatic"] 의 src 를 순서대로 수집. limit 이상이면 잘라냄.
	"""
	try:
		page.goto(
			f"https://map.naver.com/p/entry/place/{naver_place_id}",
			wait_until="domcontentloaded", timeout=15000)
		page.wait_for_timeout(2500)
		frame = page.frame("entryIframe")
		if not frame:
			return []
		m = re.search(r"pcmap\.place\.naver\.com/(\w+)/\d+", frame.url)
		place_type = m.group(1) if m else "restaurant"
		frame.goto(
			f"https://pcmap.place.naver.com/{place_type}/{naver_place_id}/photo",
			wait_until="domcontentloaded", timeout=10000)
		page.wait_for_timeout(2000)
		urls = frame.evaluate("""(limit) => {
			const seen = new Set();
			const out = [];
			document.querySelectorAll('img[src*="pstatic"]').forEach(img => {
				const src = img.getAttribute('src') || '';
				if (!src || seen.has(src)) return;
				seen.add(src);
				if (out.length < limit) out.push(src);
			});
			return out;
		}""", limit)
		return urls or []
	except Exception:
		return []


def upload_store_image(server: str, store_id: int, image_url: str) -> bool:
	"""가게 사진 1장 업로드 — 메뉴 이미지 흐름과 동일하지만 domain=stores."""
	mime = detect_mime(image_url)
	try:
		presigned = get_presigned_url(server, "stores", store_id, mime)
		data = download_bytes(image_url)
		upload_to_s3(presigned["uploadUrl"], data, mime)
		save_image_metadata(server, "stores", store_id, presigned["fileName"], presigned["awsKey"], mime)
		return True
	except Exception as e:
		print(f"  [가게 이미지 업로드 실패] store_id={store_id}: {e}")
		return False


# ─── 메뉴 데이터 파싱 ────────────────────────────────────────────────────────

def is_drink(name: str) -> bool:
	return any(kw in name for kw in DRINK_KEYWORDS)


def guess_category(name: str) -> str:
	for keywords, kind in CATEGORY_RULES:
		if any(kw in name for kw in keywords):
			return kind
	return DEFAULT_CATEGORY


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

def process_store(page: Page, store: dict, server: str, dry_run: bool, verbose: bool) -> dict:
	"""
	큐의 한 store 처리.
	  - placeId 이미 있음: 검색/매칭 스킵하고 바로 메뉴+사진 크롤 (어드민 수동 매칭 직후 경로)
	  - placeId 없음: 네이버 검색 → 신뢰 매칭 1건 선택 → placeId 저장 → 메뉴+사진 크롤
	  - 매칭 실패: mark_failed
	"""
	stats = {"matched": 0, "menus_created": 0, "images_uploaded": 0, "skipped_menu_crawl": 0,
			"photos_uploaded": 0, "skipped_photo_crawl": 0,
			"failed": 0, "no_candidates": False}

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
			existing_menu_count, existing_photo_count, None, server, stats)

	candidates = search_naver_candidates(page, store_name, store.get("address"))
	if not candidates:
		print("  → 네이버 검색 결과 없음")
		if not dry_run:
			mark_failed(server, store_id)
		stats["failed"] = 1
		stats["no_candidates"] = True
		return stats

	# 신뢰 매칭 통과한 1순위 선택
	matched_place = None
	reasons = []
	for cand in candidates:
		ok, why = is_reliable_match(store, cand)
		reasons.append((cand.get("name"), ok, why))
		if ok:
			matched_place = cand
			break

	if not matched_place:
		print(f"  → 신뢰 매칭 실패 (후보 {len(candidates)}개)")
		if verbose:
			for name, ok, why in reasons[:5]:
				print(f"     - [{'O' if ok else 'X'}] {name}  ({why})")
		if not dry_run:
			# 어드민이 직접 고를 수 있게 후보 저장 — '후보 보기' UI 가 읽음
			save_candidates(server, store_id, candidates)
			mark_failed(server, store_id)
		stats["failed"] = 1
		return stats

	naver_id = matched_place.get("id", "")
	print(f"  ✓ 매칭: naverPlaceId={naver_id}  '{matched_place.get('name')}'")
	stats["matched"] = 1

	if dry_run:
		print("  [dry-run] 저장/등록 생략")
		return stats

	# naverPlaceId 저장
	save_naver_place_id(server, store_id, naver_id)

	return _enrich_with_place_id(
		page, store_id, naver_id,
		existing_menu_count, existing_photo_count,
		matched_place.get("menuInfo") or "",
		server, stats)


def _enrich_with_place_id(
		page: Page,
		store_id: int,
		naver_place_id: str,
		existing_menu_count: int,
		existing_photo_count: int,
		menu_info_str: str,
		server: str,
		stats: dict) -> dict:
	"""placeId 가 확정된 가게에 대해 메뉴 + 가게 사진을 크롤하고 업로드.

	검색 매칭 경로와 수동 매칭 경로 둘 다에서 사용.
	  - menu_info_str 있음: 네이버 search 응답의 menuInfo 를 1차 소스로 사용 (가격 보장)
	  - menu_info_str 없음: 메뉴 탭 DOM 을 1차 소스로 사용 (수동 매칭 경로)
	"""
	# 메뉴 탭 DOM 은 두 경로 모두에서 필요 (수동 매칭 → 1차 소스, 검색 매칭 → 이미지/설명/fallback 가격)
	place_menu_map = fetch_menu_data(page, naver_place_id) if naver_place_id else {}

	if existing_menu_count > 0:
		print(f"  → 메뉴 {existing_menu_count}개 보유 — 메뉴 크롤링 생략")
		stats["skipped_menu_crawl"] = 1
	else:
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

			for menu in primary_menus:
				extra = match_menu_extra(menu["name"])
				img_url = extra.get("src") or None
				desc = extra.get("desc") or None
				# 검색 menuInfo 에 가격 없으면 DOM 가격으로 보완
				price = menu.get("price") if menu.get("price") is not None else extra.get("price")

				menu_id = create_menu(server, store_id, menu["name"], price, menu["category"], desc)
				if not menu_id:
					continue
				stats["menus_created"] += 1
				price_str = f"{price}원" if price else "-"
				print(f"    + 등록 [{menu_id}] {menu['name']}  {price_str}")

				if img_url:
					if upload_menu_image(server, menu_id, img_url):
						stats["images_uploaded"] += 1
						print(f"      └ 이미지 업로드 완료")

				time.sleep(0.1)

	# 가게 사진 — 기존 사진 0장일 때만
	if existing_photo_count > 0:
		print(f"  → 가게 사진 {existing_photo_count}장 보유 — 사진 크롤링 생략")
		stats["skipped_photo_crawl"] = 1
	else:
		photo_urls = fetch_store_photos(page, naver_place_id, limit=STORE_PHOTO_LIMIT) if naver_place_id else []
		if not photo_urls:
			print("  → 네이버 사진 탭에서 이미지 못 찾음")
		else:
			print(f"  → 가게 사진 {len(photo_urls)}장 업로드 시도")
			for url in photo_urls:
				if upload_store_image(server, store_id, url):
					stats["photos_uploaded"] += 1
				time.sleep(0.1)
			print(f"      └ 가게 사진 {stats['photos_uploaded']}/{len(photo_urls)}장 업로드 완료")

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
	parser.add_argument("--headless", action="store_true", default=True, help="브라우저 창 숨김 (기본 ON)")
	parser.add_argument("--show-browser", dest="headless", action="store_false", help="브라우저 창 표시")
	args = parser.parse_args()

	print(f"서버: {args.server}")
	if args.store_id:
		print(f"단일 가게 모드: store_id={args.store_id}")
	else:
		mode = "photos-backfill" if args.photos_only else "enrich"
		print(f"큐 모드 [{mode}] — 배치 크기: {args.batch}, loop={args.loop}, dry-run={args.dry_run}")

	total = {"matched": 0, "menus_created": 0, "images_uploaded": 0, "skipped_menu_crawl": 0,
			"photos_uploaded": 0, "skipped_photo_crawl": 0, "failed": 0}

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
			result = process_store(page, store, args.server, args.dry_run, args.verbose)
			for k in total:
				total[k] += result.get(k, 0)
		else:
			# 큐 모드 (Option B 또는 운영자 CLI)
			batches_done = 0
			no_candidate_streak = 0
			while True:
				queue = fetch_queue(args.server, args.batch, photos_only=args.photos_only)
				if not queue:
					print("\n[큐 비어있음 — 종료]")
					break
				print(f"\n=== 배치 #{batches_done + 1} — {len(queue)}건 ===")
				for store in queue:
					result = process_store(page, store, args.server, args.dry_run, args.verbose)
					for k in total:
						total[k] += result.get(k, 0)
					# 네이버 차단 의심 — N건 연속으로 검색 결과 0이면 abort (대량 false-fail 방지)
					if result.get("no_candidates"):
						no_candidate_streak += 1
						if no_candidate_streak >= NO_CANDIDATE_ABORT_THRESHOLD:
							print(f"\n[ABORT] {no_candidate_streak}건 연속 검색 결과 0 — 네이버 차단 의심.")
							print(f"        위의 [diag] 로그 확인. IP/UA/요청간격 점검 후 재시도하세요.")
							print(f"        실패 마킹된 가게는 /naver-enrich-retry 로 throttle 해제 가능.")
							browser.close()
							sys.exit(3)
					else:
						no_candidate_streak = 0
					time.sleep(INTER_STORE_DELAY_SEC)
				batches_done += 1
				if not args.loop:
					break
				if args.max_batches and batches_done >= args.max_batches:
					print(f"\n[max-batches {args.max_batches} 도달 — 종료]")
					break
				print(f"\n--- 다음 배치 5초 대기 ---")
				time.sleep(5)

		browser.close()

	print(f"\n{'=' * 60}")
	print(json.dumps(total, ensure_ascii=False, indent=2))


if __name__ == "__main__":
	main()
