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
INTER_STORE_DELAY_SEC = 1.0
MAX_LOCATION_DISTANCE_M = 100  # 매칭 신뢰 거리 임계

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
#      '올선데이 광안직영점' → '올선데이', '포스톤즈 커피로스터스 삼청점' → '포스톤즈 커피로스터스'.
# 공백 없이 붙어 있는 케이스 (예: '올유캔잇본점')는 strip 안 되지만, 뒤의 substring 비교가 대응.
_BRANCH_SUFFIX_RE = re.compile(r"\s+(?:[가-힣a-zA-Z0-9]+점|매장|플래그십|플래그쉽)$")


def strip_branch_suffix(name: str) -> str:
	"""이름 끝의 매장 접미사 1회 제거 (공백으로 어휘 경계 확보)."""
	if not name:
		return name
	return _BRANCH_SUFFIX_RE.sub("", name)


def normalize_name(name: str) -> str:
	"""이름 정규화: HTML/괄호 제거 → 매장 접미사 제거 → 공백/특수문자 제거 → 연결어(&↔앤) 정규화 → 소문자화."""
	if not name:
		return ""
	s = re.sub(r"<[^>]+>", "", name)             # HTML 태그 (네이버 응답 일부에 있음)
	s = re.sub(r"\(.*?\)|\[.*?\]", "", s)         # 괄호 내용
	s = s.strip()
	s = strip_branch_suffix(s)                    # 공백 있는 상태에서 매장 접미사 제거
	s = re.sub(r"\s+", "", s)                     # 모든 공백
	s = re.sub(r"[^\w가-힣]", "", s)               # 특수문자 ('&' 등 제거)
	s = s.replace("앤", "")                       # '앤'(한글 '&' 표기) 제거 — Kakao '&' ↔ Naver '앤' 등가 처리
	return s.lower()


def name_matches(a: str, b: str) -> bool:
	"""정규화된 이름 비교.
	- exact 일치 → 통과
	- prefix 관계(브랜드명 + 추가 수식어): 짧은 쪽 ≥ 3자, 길이비 ≥ 50%
	- mid-string substring: 짧은 쪽 ≥ 3자, 길이비 ≥ 60% (오매칭 위험이 더 커서 더 엄격)
	"""
	if not a or not b:
		return False
	if a == b:
		return True
	shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
	if len(shorter) < 3:
		return False
	ratio = len(shorter) / len(longer)
	if longer.startswith(shorter):
		return ratio >= 0.5
	return shorter in longer and ratio >= 0.6


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
	"""두 위경도 간 거리(미터)."""
	r = 6371000.0
	phi1, phi2 = math.radians(lat1), math.radians(lat2)
	dphi = math.radians(lat2 - lat1)
	dlam = math.radians(lng2 - lng1)
	a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
	return 2 * r * math.asin(math.sqrt(a))


_DIST_SUFFIXES = ("구", "동", "읍", "면", "리", "시", "군")


def extract_district_tokens(address: str) -> set[str]:
	"""주소 문자열에서 행정구역 토큰만 추출 (광역시/구/동/...). 비교용 set."""
	if not address:
		return set()
	tokens = re.split(r"\s+", address.strip())
	return {t for t in tokens if t and t.endswith(_DIST_SUFFIXES)}


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

def fetch_queue(server: str, size: int) -> list[dict]:
	url = f"{server}/api/admin/stores/naver-enrich-queue?page=0&size={size}"
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

def search_naver_candidates(page: Page, store_name: str) -> list[dict]:
	"""네이버 지도 검색 → allSearch 응답 가로채서 후보 places 전부 반환.

	검색어에서도 매장 접미사 제거 (네이버 인덱스는 '본점/지점' 없이 등록된 케이스가 많음 — 예: '카페비읍미음 본점'을
	그대로 검색하면 0건이지만 '카페비읍미음'으로 검색하면 찾힘). 잘못된 후보는 뒤의 이름·주소 비교가 걸러냄.
	"""
	search_term = strip_branch_suffix(store_name) or store_name
	captured: list[dict] = []

	def handle_response(response):
		if "allSearch" in response.url and "json" in response.headers.get("content-type", ""):
			try:
				data = response.json()
				places = data.get("result", {}).get("place", {}).get("list", [])
				if places:
					captured.extend(places)
			except Exception:
				pass

	page.on("response", handle_response)
	try:
		page.goto(
			f"https://map.naver.com/p/search/{urllib.parse.quote(search_term)}",
			wait_until="domcontentloaded", timeout=15000)
		page.wait_for_timeout(3000)
	except Exception as e:
		print(f"  [페이지 로드 실패] {search_term}: {e}")
	page.remove_listener("response", handle_response)
	return captured


def fetch_menu_data(page: Page, naver_place_id: str) -> dict[str, dict]:
	"""네이버 플레이스 메뉴 탭 DOM → {메뉴명: {"src": imageUrl, "desc": 설명}}."""
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
				const nonPrice = [...new Set(texts)].filter(t => !priceRe.test(t) && t !== '대표');
				const name = nonPrice[0] || '';
				const desc = nonPrice[1] || '';
				if (name) results.push({ name, src, desc });
			});
			return results;
		}""")
		return {it["name"]: {"src": it["src"], "desc": it["desc"]} for it in items if it.get("name")}
	except Exception:
		return {}


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
	  - 네이버 검색 → 신뢰 매칭 1건 선택
	  - 매칭 성공: naverPlaceId 저장. existingMenuCount == 0 이면 메뉴 등록.
	  - 매칭 실패: mark_failed
	"""
	stats = {"matched": 0, "menus_created": 0, "images_uploaded": 0, "skipped_menu_crawl": 0, "failed": 0}

	store_id = store["id"]
	store_name = store["name"]
	existing_menu_count = store.get("existingMenuCount", 0) or 0

	print(f"\n[가게] id={store_id} | {store_name}  (메뉴 {existing_menu_count}개 보유)")

	candidates = search_naver_candidates(page, store_name)
	if not candidates:
		print("  → 네이버 검색 결과 없음")
		if not dry_run:
			mark_failed(server, store_id)
		stats["failed"] = 1
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

	# 메뉴 이미 있으면 ID 만 채우고 종료
	if existing_menu_count > 0:
		print(f"  → 메뉴 {existing_menu_count}개 보유 — 메뉴 크롤링 생략 (ID만 채움)")
		stats["skipped_menu_crawl"] = 1
		return stats

	# 메뉴 크롤링 & 등록
	menu_info_str = matched_place.get("menuInfo") or ""
	parsed_menus = parse_menu_info(menu_info_str)
	if not parsed_menus:
		print("  → 네이버에 메뉴 정보 없음 (menuInfo 비어있음)")
		return stats

	place_menu_map = fetch_menu_data(page, naver_id) if naver_id else {}

	def match_menu_img(menu_name: str) -> dict:
		if menu_name in place_menu_map:
			return place_menu_map[menu_name]
		n_norm = re.sub(r"\(.*?\)", "", menu_name).replace(" ", "")
		return next(
			(v for k, v in place_menu_map.items()
			 if re.sub(r"\(.*?\)", "", k).replace(" ", "") == n_norm),
			{},
		)

	for menu in parsed_menus:
		matched_img = match_menu_img(menu["name"])
		img_url = matched_img.get("src") or None
		desc = matched_img.get("desc") or None

		menu_id = create_menu(server, store_id, menu["name"], menu["price"], menu["category"], desc)
		if not menu_id:
			continue
		stats["menus_created"] += 1
		price_str = f"{menu['price']}원" if menu["price"] else "-"
		print(f"    + 등록 [{menu_id}] {menu['name']}  {price_str}")

		if img_url:
			if upload_menu_image(server, menu_id, img_url):
				stats["images_uploaded"] += 1
				print(f"      └ 이미지 업로드 완료")

		time.sleep(0.1)

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
	parser.add_argument("--headless", action="store_true", default=True, help="브라우저 창 숨김 (기본 ON)")
	parser.add_argument("--show-browser", dest="headless", action="store_false", help="브라우저 창 표시")
	args = parser.parse_args()

	print(f"서버: {args.server}")
	if args.store_id:
		print(f"단일 가게 모드: store_id={args.store_id}")
	else:
		print(f"큐 모드 — 배치 크기: {args.batch}, loop={args.loop}, dry-run={args.dry_run}")

	total = {"matched": 0, "menus_created": 0, "images_uploaded": 0, "skipped_menu_crawl": 0, "failed": 0}

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
			while True:
				queue = fetch_queue(args.server, args.batch)
				if not queue:
					print("\n[큐 비어있음 — 종료]")
					break
				print(f"\n=== 배치 #{batches_done + 1} — {len(queue)}건 ===")
				for store in queue:
					result = process_store(page, store, args.server, args.dry_run, args.verbose)
					for k in total:
						total[k] += result.get(k, 0)
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
