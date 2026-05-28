"""네이버 매칭/메뉴 파서 — 순수 함수 모듈.

I/O 없음. naver_menu_scraper.py 가 imports 해서 사용. tests/test_naver_match.py 가 검증.
"""

import math
import re
from typing import Optional


# ─── 상수 ─────────────────────────────────────────────────────────────────────

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

DEFAULT_CATEGORY = "빵"

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


# ─── 이름 정규화 ──────────────────────────────────────────────────────────────

# 매장 접미사 제거 — 공백 있는 상태에서 적용 (공백을 어휘 경계로 사용).
# 예: '테라로사 본점' → '테라로사', '카페드파리 성남본점' → '카페드파리'.
_BRANCH_SUFFIX_RE = re.compile(r"\s+(?:\d+호점|[가-힣a-zA-Z0-9]+점|매장|플래그십|플래그쉽)$")

# 검색어용 — 위치 정보 없는 일반 접미사만 제거. '강남점' 같은 지역 힌트 포함된 ○○점은 보존.
_GENERIC_BRANCH_SUFFIX_RE = re.compile(r"(?:\s+점|\s*(?:본점|지점|매장|플래그십|플래그쉽|직영점|\d+호점))$")

# 사업자명 노이즈 — 카카오 데이터에 사업자등록명이 같이 붙어있는 케이스.
_COMPANY_SUFFIX_RE = re.compile(
	r"\s*(?:주식회사|\(주\)|㈜|\(유\)|유한회사|\(합\)|합자회사).*$"
)

# 검색어에서 떼어내볼 카테고리 단어 — 1차 검색 실패 시 strip 후 재시도용.
_CATEGORY_SUFFIX_RE = re.compile(
	r"\s+(?:커피로스터리|커피로스터스|로스터리|로스터스|베이커리|빵집|카페|커피"
	r"|레스토랑|파티세리|파티쎄리|부띠끄|부티크|케이크부티크|케이크부띠끄|디저트"
	r"|키친|하우스|스튜디오)$"
)


def strip_company_suffix(name: str) -> str:
	if not name:
		return name
	return _COMPANY_SUFFIX_RE.sub("", name).strip()


def strip_category_suffix(name: str) -> str:
	if not name:
		return name
	return _CATEGORY_SUFFIX_RE.sub("", name).strip()


def strip_branch_suffix(name: str) -> str:
	if not name:
		return name
	return _BRANCH_SUFFIX_RE.sub("", name)


def strip_generic_branch_suffix(name: str) -> str:
	if not name:
		return name
	return _GENERIC_BRANCH_SUFFIX_RE.sub("", name)


def normalize_name(name: str) -> str:
	"""HTML/괄호 제거 → 사업자명/매장 접미사 제거 → 공백/특수문자 제거 → 앤 제거 → 소문자."""
	if not name:
		return ""
	s = re.sub(r"<[^>]+>", "", name)
	s = re.sub(r"\(.*?\)|\[.*?\]", "", s)
	s = s.strip()
	s = strip_company_suffix(s)
	s = strip_branch_suffix(s)
	s = re.sub(r"\s+", "", s)
	s = re.sub(r"[^\w가-힣]", "", s)
	s = s.replace("앤", "")
	return s.lower()


def name_match_kind(a: str, b: str) -> str:
	"""정규화된 이름 비교. 'exact' | 'prefix' | 'mid' | 'none' 중 하나."""
	if not a or not b:
		return "none"
	if a == b:
		return "exact"
	shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
	if len(shorter) < 2:
		return "none"
	ratio = len(shorter) / len(longer)
	if longer.startswith(shorter):
		return "prefix" if ratio >= 0.4 else "none"
	if len(shorter) >= 3 and shorter in longer and ratio >= 0.6:
		return "mid"
	return "none"


def name_matches(a: str, b: str) -> bool:
	"""호환용 — 기존 호출부에서 사용."""
	return name_match_kind(a, b) != "none"


# ─── 거리/주소 유틸 ───────────────────────────────────────────────────────────

def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
	r = 6371000.0
	phi1, phi2 = math.radians(lat1), math.radians(lat2)
	dphi = math.radians(lat2 - lat1)
	dlam = math.radians(lng2 - lng1)
	a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
	return 2 * r * math.asin(math.sqrt(a))


_DIST_SUFFIXES = ("구", "동", "읍", "면", "리", "시", "군")
_DIST_PRIORITY = ("동", "읍", "면", "리", "구", "시", "군")


def extract_district_tokens(address: str) -> set[str]:
	if not address:
		return set()
	tokens = re.split(r"\s+", address.strip())
	return {t for t in tokens if t and t.endswith(_DIST_SUFFIXES)}


def extract_best_district(address: str) -> str:
	if not address:
		return ""
	tokens = re.split(r"\s+", address.strip())
	for suffix in _DIST_PRIORITY:
		for t in tokens:
			if t.endswith(suffix) and len(t) > 1:
				return t
	return ""


def extract_road_name(address: Optional[str]) -> str:
	if not address:
		return ""
	for tok in re.split(r"\s+", address.strip()):
		if tok.endswith(("로", "길")) and len(tok) >= 3:
			return tok
	return ""


_NUMBER_TOKEN_RE = re.compile(r"^\d+(?:-\d+)?$")


def extract_road_and_number(address: Optional[str]) -> tuple[str, str]:
	"""주소에서 (도로명, 건물번호) 추출. 못 찾으면 빈 문자열.

	'서울 관악구 남부순환로 1850' → ('남부순환로', '1850')
	'경기 용인시 기흥구 공세로 73-1' → ('공세로', '73-1')
	도로명 토큰 바로 다음에 숫자/숫자-숫자 토큰이 와야 한다.
	"""
	if not address:
		return "", ""
	tokens = re.split(r"\s+", address.strip())
	for i, tok in enumerate(tokens):
		if tok.endswith(("로", "길")) and len(tok) >= 3:
			if i + 1 < len(tokens) and _NUMBER_TOKEN_RE.match(tokens[i + 1]):
				return tok, tokens[i + 1]
	return "", ""


# ─── 메뉴 카테고리 ────────────────────────────────────────────────────────────

def is_drink(name: str) -> bool:
	return any(kw in name for kw in DRINK_KEYWORDS)


def guess_category(name: str) -> str:
	for keywords, kind in CATEGORY_RULES:
		if any(kw in name for kw in keywords):
			return kind
	return DEFAULT_CATEGORY


# ─── 메뉴 텍스트 분류기 (item 4) ──────────────────────────────────────────────

# 케이스 무시 exact 일치 — 라벨이라 메뉴 데이터에서 제거
LABEL_TOKENS = {
	"대표", "BEST", "NEW", "추천", "신메뉴", "인기",
	"소", "중", "대", "S", "M", "L",
}
_LABEL_TOKENS_UPPER = {s.upper() for s in LABEL_TOKENS}

# price-like 지만 DB price 는 None 으로. desc 에서도 제외.
PRICE_LIKE_NULL = {"시가", "변동", "문의"}
PRICE_LIKE_NULL_PREFIXES = ("시가",)  # '시가/계절', '시가 (변동)' 등

# 가격 매치: '3,500원', '3,500원~', '3500원 (1인분)', '3500'.
# 첫 캡처가 콤마 포함 숫자 문자열.
_PRICE_NUMERIC_RE = re.compile(r"^([\d,]+)\s*원?(?:\s*[~\(].*)?$")

DESC_MAX_LEN = 255

# 메뉴명 alias 결합 — 영문 라인 + 한글 라인이 짝지어 등장하면 둘을 합쳐 name 으로 사용.
# 'Pain au Chocolat' + '빵 오 쇼콜라' → 'Pain au Chocolat / 빵 오 쇼콜라'
# 둘 다 '짧은 이름형' (글자 수 + 토큰 수 모두 작아야) 일 때만 alias 로 인정 — 설명 문장 배제.
_ALIAS_MAX_LEN = 30
_ALIAS_MAX_TOKENS = 4
_LATIN_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9\s\-'.&()/]*$")
_KOREAN_NAME_RE = re.compile(r"^[가-힣][가-힣0-9\s\-.&()/]*$")


def _is_short_name(t: str) -> bool:
	return len(t) <= _ALIAS_MAX_LEN and len(t.split()) <= _ALIAS_MAX_TOKENS


def _alias_pair(a: str, b: str) -> bool:
	if not (_is_short_name(a) and _is_short_name(b)):
		return False
	if _LATIN_NAME_RE.match(a) and _KOREAN_NAME_RE.match(b):
		return True
	if _KOREAN_NAME_RE.match(a) and _LATIN_NAME_RE.match(b):
		return True
	return False


def classify_menu_texts(uniq: list[str]) -> dict:
	"""메뉴 li 내부 텍스트 list → {name, price, desc} dict.

	- 라벨 (대표/BEST/소/L 등) 은 제거
	- 가격 텍스트 매치되면 price (첫 매치만, int)
	- '시가'/'변동'/'문의' 는 price=None 유지 + desc 에서 제외
	- 남은 텍스트 첫 개 = name, 단 다음 텍스트가 영문↔한글 alias 짝이면 둘을 ' / ' 로 합쳐 name 으로 사용
	- 이후 텍스트는 desc 로 join, 255 자 cap
	"""
	price: Optional[int] = None
	name_candidates: list[str] = []

	for raw in uniq:
		t = (raw or "").strip()
		if not t:
			continue
		if t.upper() in _LABEL_TOKENS_UPPER:
			continue
		if t in PRICE_LIKE_NULL or any(t.startswith(p) for p in PRICE_LIKE_NULL_PREFIXES):
			continue
		m = _PRICE_NUMERIC_RE.match(t)
		if m:
			if price is None:
				digits = m.group(1).replace(",", "")
				if digits.isdigit():
					price = int(digits)
			continue
		name_candidates.append(t)

	if len(name_candidates) >= 2 and _alias_pair(name_candidates[0], name_candidates[1]):
		name = f"{name_candidates[0]} / {name_candidates[1]}"
		desc_parts = name_candidates[2:]
	elif name_candidates:
		name = name_candidates[0]
		desc_parts = name_candidates[1:]
	else:
		name = ""
		desc_parts = []

	desc = " · ".join(desc_parts).strip()
	if len(desc) > DESC_MAX_LEN:
		desc = desc[: DESC_MAX_LEN - 1] + "…"
	return {"name": name, "price": price, "desc": desc or None}


# ─── 체인성 판정 ──────────────────────────────────────────────────────────────

# FranchiseName.java 와 동기화 (2026-05-28 기준 ~108개).
# Kakao 임포트 시 FranchiseName.matches() 로 이미 1차 필터 — 이건 누락된 분점/로컬 체인 보강용.
CHAIN_HINTS = (
	"파리바게뜨", "뚜레쥬르", "파리크라상", "빵장수단팥빵", "명랑시대쌀핫도그",
	"던킨", "크리스피크림도넛", "폴바셋", "스타벅스", "투썸플레이스",
	"이디야", "메가MGC커피", "컴포즈커피", "빽다방", "더벤티", "커피빈",
	"할리스", "엔제리너스", "파스쿠찌", "탐앤탐스", "카페베네", "커피베이",
	"카페봄봄", "매머드커피", "매머드익스프레스", "커피에반하다", "커피나무",
	"텐퍼센트커피", "디저트39", "요거프레소", "공차", "팔공티", "아마스빈",
	"타이거슈가", "스무디킹", "쥬씨", "설빙", "바나프레소", "카페인중독",
	"커피랑도서관", "카페희다", "블루보틀", "아티제", "와플대학", "이삭토스트",
	"브레덴코", "테라커피", "크라상점", "호밀호두", "더카페", "메가커피",
	"크리스피크림", "하삼동커피", "더리터", "감성커피", "달콤커피", "드롭탑",
	"셀렉토커피", "커피스미스", "커피명가", "커피마마", "만랩커피", "토프레소",
	"그라찌에", "빈스빈스", "전광수커피", "더착한커피", "카페보니또", "나우커피",
	"포트캔커피", "커피사피엔스", "벌크커피", "커피온리", "더치앤빈", "백억커피",
	"카페게이트", "프롬하츠커피", "홍루이젠", "코코호도", "못난이꽈배기",
	"송사부수제쌀고로케", "로띠번", "츄러스1500", "스트릿츄러스", "망원동티라미수",
	"롤링핀", "심봉사도로케", "따삐오", "마리웨일237", "읍천리382",
	"커피니", "핫브레드", "경성꽈배기", "꽈백최선생", "떡보의하루",
	"요거트월드", "요거트아이스크림의정석", "쉬즈베이글커피",
	"파리바게트", "복호두", "브레댄코", "메고지고", "요거트퍼플",
	"대단한커피&탕후루",
)

# 분점 패턴: '○○ ○○점', '○○ 1호점', '○○ 직영점' 등 — 끝에 점/매장/호점.
_BRANCH_PATTERN = re.compile(r"\S+\s+\S*(?:\d+호점|점|매장|직영점)$")


def is_chain_like(name: str) -> bool:
	if not name:
		return False
	if any(name.startswith(h) for h in CHAIN_HINTS):
		return True
	return bool(_BRANCH_PATTERN.search(name))


# ─── 폐업 감지 ────────────────────────────────────────────────────────────────

_CLOSED_TOKENS = ("폐업", "(폐업)", "CLOSED")


def is_closed(naver: dict) -> bool:
	category = (naver.get("category") or "")
	name = (naver.get("name") or "")
	hay = f"{category} {name}".upper()
	return any(t.upper() in hay for t in _CLOSED_TOKENS)


# ─── 스코어러 (items 2 + 3) ──────────────────────────────────────────────────

_NAME_SCORES = {"exact": 40, "prefix": 25, "mid": 15, "none": 0}


def score_candidate(kakao: dict, naver: dict) -> dict:
	"""카카오 store ↔ 네이버 place 매칭 점수.

	반환: {
	  'total': int,
	  'breakdown': {'name': int, 'distance': int, 'road': int, 'district': int, 'closed': int},
	  'distance_m': Optional[float],
	  'name_kind': str,           # exact|prefix|mid|none
	  'closed': bool,
	}
	"""
	k_name = normalize_name(kakao.get("name", ""))
	n_name = normalize_name(naver.get("name", ""))
	kind = name_match_kind(k_name, n_name)
	name_pts = _NAME_SCORES[kind]

	# 거리
	dist_pts = 0
	dist_m: Optional[float] = None
	try:
		k_lat = kakao.get("lat")
		k_lng = kakao.get("lng")
		n_lat = float(naver.get("y") or 0) or None
		n_lng = float(naver.get("x") or 0) or None
		if k_lat and k_lng and n_lat and n_lng:
			dist_m = haversine_m(k_lat, k_lng, n_lat, n_lng)
			if dist_m <= 30:
				dist_pts = 30
			elif dist_m <= 60:
				dist_pts = 20
			elif dist_m <= 80:
				dist_pts = 15
			elif dist_m <= 100:
				dist_pts = 10
			else:
				dist_pts = -10
	except (TypeError, ValueError):
		pass

	# 도로명 + 건물번호 일치
	k_road, k_num = extract_road_and_number(kakao.get("address", ""))
	n_addr = naver.get("address") or naver.get("roadAddress") or ""
	n_road, n_num = extract_road_and_number(n_addr)
	road_pts = 15 if (k_road and n_road and k_road == n_road and k_num == n_num) else 0

	# 행정동 겹침
	k_dist = extract_district_tokens(kakao.get("address", ""))
	n_dist = extract_district_tokens(n_addr)
	district_pts = 5 if (k_dist & n_dist) else 0

	# 폐업
	closed = is_closed(naver)
	closed_pts = -100 if closed else 0

	total = name_pts + dist_pts + road_pts + district_pts + closed_pts
	return {
		"total": total,
		"breakdown": {
			"name": name_pts,
			"distance": dist_pts,
			"road": road_pts,
			"district": district_pts,
			"closed": closed_pts,
		},
		"distance_m": dist_m,
		"name_kind": kind,
		"closed": closed,
	}


MIN_SCORE_AUTO_SELECT = 60
MIN_SCORE_GAP_AUTO_SELECT = 15


def _gate_pass(kakao: dict, naver: dict, score: dict) -> tuple[bool, str]:
	"""점수 외 추가 게이트. (통과 여부, 사유)."""
	kind = score["name_kind"]
	dist_m = score["distance_m"]
	bk = score["breakdown"]
	chain = is_chain_like(kakao.get("name", ""))

	if score["closed"]:
		return False, "폐업"

	if chain:
		# A: 체인성 — 거리 ≤ 30m AND (도로명번호 일치 OR 이름 exact)
		if dist_m is None or dist_m > 30:
			return False, f"체인 거리 미달 (dist={dist_m})"
		if not (bk["road"] > 0 or kind == "exact"):
			return False, "체인 도로명번호 불일치 + 이름 비-exact"
		return True, "체인 게이트 통과"

	if dist_m is not None:
		# B: 비-체인, 좌표 있음 — 거리 ≤ 60m (이름 exact + 동 일치면 80m 까지 허용)
		if dist_m <= 60:
			return True, f"거리 통과 ({dist_m:.0f}m)"
		if dist_m <= 80 and kind == "exact" and bk["district"] > 0:
			return True, f"거리 완화 통과 ({dist_m:.0f}m, exact+동)"
		return False, f"거리 미달 ({dist_m:.0f}m)"

	# C: 좌표 없음 — 도로명+건물번호 필수
	if bk["road"] > 0:
		return True, "좌표 없음, 도로명번호 일치"
	return False, "좌표 없음 + 도로명번호 불일치"


def select_match(
	kakao: dict,
	candidates: list[dict],
) -> tuple[Optional[dict], list[tuple[dict, dict]], str]:
	"""후보 중 최선을 점수+게이트로 선정.

	반환: (chosen_or_None, scored_sorted, reason).
	  scored_sorted: [(cand, score_dict), ...] total 내림차순.
	  chosen 이 None 이면 reason 에 이유.
	"""
	if not candidates:
		return None, [], "후보 없음"

	scored: list[tuple[dict, dict]] = [(c, score_candidate(kakao, c)) for c in candidates]
	scored.sort(key=lambda cs: cs[1]["total"], reverse=True)

	top_cand, top_score = scored[0]
	ok, why = _gate_pass(kakao, top_cand, top_score)
	if not ok:
		return None, scored, f"게이트 실패: {why}"

	if top_score["total"] < MIN_SCORE_AUTO_SELECT:
		return None, scored, f"점수 미달 ({top_score['total']} < {MIN_SCORE_AUTO_SELECT})"

	if len(scored) >= 2:
		gap = top_score["total"] - scored[1][1]["total"]
		if gap < MIN_SCORE_GAP_AUTO_SELECT:
			return None, scored, f"top1-top2 격차 부족 ({gap} < {MIN_SCORE_GAP_AUTO_SELECT})"

	return top_cand, scored, why
