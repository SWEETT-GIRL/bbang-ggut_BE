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
	"얼그레이", "루이보스", "페퍼민트", "캐모마일",
	"주스", "에이드", "스무디", "쉐이크",
	"맥주", "와인", "샴페인", "하이볼",
	"우유", "두유", "오트",
	"초코라떼", "말차라떼", "차이라떼",
	"리얼 초콜릿",
]

# 이름 **끝**에서만 음료로 보는 짧은 말. 부분일치로 두면 한 글자가 빵을 통째로 삼킨다 —
# '티'가 티라미수를, '차이'가 차이나식 이름을 음료로 만들었다. '밀크티'·'유자차'는 끝에 온다.
DRINK_SUFFIXES = ["티", "차", "차이"]

# 음료 키워드가 재료·향으로 들어간 빵·디저트를 되살리는 표식.
# '커피번'·'우유식빵'·'얼그레이 스콘'·'오트밀쿠키'가 전부 음료로 걸러져 사라지고 있었다.
NOT_DRINK_MARKERS = [
	"빵", "케이크", "쿠키", "스콘", "휘낭시에", "마들렌", "타르트", "파이", "번", "브레드",
	"크럼블", "티라미수", "롤", "도넛", "크루아상", "크로와상", "크로아상", "베이글",
	"브라우니", "푸딩", "샌드위치", "토스트", "머핀", "까눌레", "카눌레", "몽블랑",
	"바스크", "구움", "와플", "크로플", "산도", "포카치아", "프레즐", "슈크림",
]

# 룰에 안 걸리면 카테고리를 비워 보낸다(None) — 백엔드가 NULL 로 두고 AI 스케줄러가 분류한다.
# 그전엔 "빵" 폴백이라 음료·수프·선물세트까지 빵이 됐다 (2026-09-08 신규 가게 11곳 172건 중 98건).

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
# '본사'는 회사/사무실일 수 있어 자동으로 떼지 않는다.
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
	s = re.sub(r"[^0-9A-Za-z가-힣]", "", s)
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


def _naver_address_values(naver: dict) -> list[str]:
	"""네이버 후보 주소들을 roadAddress 우선순위로 반환.

	allSearch 는 address(지번)와 roadAddress(도로명)를 둘 다 주는 경우가 있다. 도로명 비교는
	반드시 roadAddress 를 먼저 봐야 한다. 저장 후보/수동 후보처럼 address 하나만 남은 경우도
	호환한다.
	"""
	values: list[str] = []
	for key in ("roadAddress", "roadAddressName", "road_address_name", "address"):
		value = naver.get(key)
		if not value:
			continue
		value = str(value).strip()
		if value and value not in values:
			values.append(value)
	return values


def _naver_road_and_number(naver: dict) -> tuple[str, str]:
	for addr in _naver_address_values(naver):
		road, num = extract_road_and_number(addr)
		if road and num:
			return road, num
	return "", ""


def _naver_district_tokens(naver: dict) -> set[str]:
	tokens: set[str] = set()
	for addr in _naver_address_values(naver):
		tokens |= extract_district_tokens(addr)
	return tokens


_REGION_HEAD_SUFFIX_RE = re.compile(r"(?:특별자치도|특별자치시|특별시|광역시|자치도|도|시)$")


def _region_head(address: Optional[str]) -> str:
	if not address:
		return ""
	tokens = re.split(r"\s+", address.strip())
	if not tokens:
		return ""
	return _REGION_HEAD_SUFFIX_RE.sub("", tokens[0])


def _naver_region_heads(naver: dict) -> set[str]:
	return {head for addr in _naver_address_values(naver) if (head := _region_head(addr))}


def _same_region_head(kakao: dict, naver: dict) -> bool:
	k_head = _region_head(kakao.get("address"))
	n_heads = _naver_region_heads(naver)
	if not k_head or not n_heads:
		return True
	return k_head in n_heads


# ─── 메뉴 카테고리 ────────────────────────────────────────────────────────────

def is_drink(name: str) -> bool:
	compact = name.replace(" ", "")
	if any(marker in compact for marker in NOT_DRINK_MARKERS):
		return False
	if any(kw in compact for kw in DRINK_KEYWORDS):
		return True
	return any(compact.endswith(suffix) for suffix in DRINK_SUFFIXES)


def guess_category(name: str) -> Optional[str]:
	for keywords, kind in CATEGORY_RULES:
		if any(kw in name for kw in keywords):
			return kind
	return None


# ─── 메뉴 텍스트 분류기 (item 4) ──────────────────────────────────────────────

# 케이스 무시 exact 일치 — 순수 라벨이라 항상 제거 (메뉴를 구분하지 않음).
PURE_LABEL_TOKENS = {
	"대표", "BEST", "NEW", "추천", "신메뉴", "인기",
}
_PURE_LABELS_UPPER = {s.upper() for s in PURE_LABEL_TOKENS}

# 사이즈 토큰 — 한 항목에 여러 개면 '한 메뉴의 사이즈 옵션'이라 생략하지만, 딱 1개만 있으면
# '사이즈 변종 구분자'라 이름에 붙인다(소/대 별도 메뉴를 떼다가 같은 이름으로 충돌·소실 방지).
SIZE_TOKENS = {"소", "중", "대", "S", "M", "L"}
_SIZE_TOKENS_UPPER = {s.upper() for s in SIZE_TOKENS}

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
	size_tokens: list[str] = []

	for raw in uniq:
		t = (raw or "").strip()
		if not t:
			continue
		if t.upper() in _PURE_LABELS_UPPER:
			continue
		if t.upper() in _SIZE_TOKENS_UPPER:
			size_tokens.append(t)
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

	# 사이즈 토큰이 딱 1종이면 사이즈 변종이므로 이름에 붙여 구분 유지(예: '아메리카노 대').
	# 여러 종(소·대 등)이면 한 메뉴의 사이즈 옵션이라 이름에 안 붙임.
	if name and len(set(size_tokens)) == 1:
		name = f"{name} {size_tokens[0]}"

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


# ─── 지점명 충돌 / 일반어 동일성 ──────────────────────────────────────────────

def branch_token(name: str) -> str:
	"""이름 끝의 분점 토큰(○○점·N호점·매장 등)을 반환. 없으면 빈 문자열.

	'씨엘비베이커리 광주2호점' → '광주2호점', '이성당' → '' (신관은 점 접미사 아님 → '').
	normalize_name 이 떼어내는 그 토큰을 '떼기 전' 원본에서 추출한다.
	"""
	if not name:
		return ""
	s = re.sub(r"<[^>]+>", "", name)
	s = re.sub(r"\(.*?\)|\[.*?\]", "", s)
	s = strip_company_suffix(s.strip())
	m = _BRANCH_SUFFIX_RE.search(s)
	return m.group(0).strip() if m else ""


# 분점 토큰 — 이름 '어디에 있든' 매칭. 네이버 가게명이 SEO 키워드로 도배된 경우
# ('씨엘비베이커리 광주점 디저트맛집...') 끝 토큰만 보면 못 잡으므로 finditer 로 전부 수집.
_BRANCH_TOKEN_RE = re.compile(r"[가-힣A-Za-z0-9]*(?:\d+호점|점)")


def branch_tokens(name: str) -> set[str]:
	if not name:
		return set()
	s = re.sub(r"<[^>]+>", "", name)
	s = re.sub(r"\(.*?\)|\[.*?\]", "", s)
	s = strip_company_suffix(s.strip())
	return {m.group(0) for m in _BRANCH_TOKEN_RE.finditer(s) if len(m.group(0)) >= 2}


def _canonical_branch_token(token: str) -> str:
	"""지점 토큰 표기차 정규화.

	'신사역점' ↔ '신사점', '홍대지점' ↔ '홍대점', 'GS직영점' ↔ 'GS점' 같은
	동일 지점 표기차를 충돌로 보지 않기 위한 보조 정규화다.
	"""
	if not token:
		return ""
	t = token.strip()
	t = re.sub(r"(?:지점|직영점|본점|매장)$", "점", t)
	t = re.sub(r"역점$", "점", t)
	return t


_NUMBERED_BRANCH_RE = re.compile(r"^(.+?)(\d+)호점$")


def _numbered_branch_signatures(name: str) -> set[tuple[str, str]]:
	signatures: set[tuple[str, str]] = set()
	for token in branch_tokens(name):
		m = _NUMBERED_BRANCH_RE.match(_canonical_branch_token(token))
		if m:
			signatures.add((m.group(1), m.group(2)))
	return signatures


def _hard_numbered_branch_conflict(a: str, b: str) -> bool:
	"""'동탄2호점' ↔ '동탄3호점'처럼 양쪽이 다른 번호 지점을 명시하면 True."""
	left = _numbered_branch_signatures(a)
	right = _numbered_branch_signatures(b)
	for left_core, left_no in left:
		for right_core, right_no in right:
			if left_core == right_core and left_no != right_no:
				return True
	return False


def branch_conflict(a: str, b: str) -> bool:
	"""양쪽 다 분점 토큰이 있는데 공통이 하나도 없으면 True (다른 지점에 오매칭 방지).

	'씨엘비 광주2호점' vs '씨엘비 광주점 디저트맛집' → True (광주2호점 ∩ 광주점 = ∅).
	'류재은베이커리 류하우스점' vs '류재은베이커리' → False (한쪽만 있음 = 지점명 생략, 같은 가게).
	"""
	ta, tb = branch_tokens(a), branch_tokens(b)
	if not ta or not tb:
		return False
	for left in ta:
		for right in tb:
			c_left = _canonical_branch_token(left)
			c_right = _canonical_branch_token(right)
			if left == right or c_left == c_right:
				return False
			# 카카오는 '롯데백화점강남점', 네이버는 '강남점'처럼 입점 시설명이
			# 분점 토큰 앞에 붙거나 떨어지는 경우가 많다. suffix containment 는 같은
			# 지점 표기차로 본다. ('광주2호점' vs '광주점'은 containment 가 아니므로 충돌 유지)
			if len(c_left) >= 3 and len(c_right) >= 3 and (
					c_left.endswith(c_right) or c_right.endswith(c_left)):
				return False
	return True


def headquarters_conflict(a: str, b: str) -> bool:
	"""카카오명이 '본사'인데 네이버 후보가 본사로 등록돼 있지 않으면 자동 매칭하지 않는다."""
	if not a or not b:
		return False
	left = re.sub(r"<[^>]+>", "", a)
	right = re.sub(r"<[^>]+>", "", b)
	return "본사" in left and "본사" not in right


# 카테고리성 일반어 — 이름에서 빼도 가게 정체성이 유지되는 단어. mid 매칭이 '머리(브랜드)
# 잘림'인지 '일반어 차이'인지 가르는 데 쓴다. 긴 단어 먼저(제과점 → 제과 순서 보존).
_GENERIC_NAME_WORDS = (
	"주문제작", "레터링", "베이킹", "로스터스", "로스터리", "플래그쉽", "플래그십",
	"베이커리", "제과점", "제과", "카페", "커피", "케이크", "디저트",
	"요거트아이스크림", "아이스크림", "베이크샵", "베이크",
	"베이글", "브레드", "도넛", "분식", "스토어", "스튜디오", "전문점", "비건",
	"찹쌀", "식빵", "빵집",
	"쿠킹클래스", "글루텐프리", "케이크샵", "에그타르트", "생과일",
	"24시", "무인", "샵",
	"롯데백화점", "현대백화점", "신세계백화점", "백화점", "더현대",
	"스타필드", "타임스퀘어", "월드몰", "센터필드", "아울렛", "호텔",
	"coffee", "cafe", "bakery", "bread", "cakes", "cake", "dessert",
	"bagels", "bagel", "roastery", "roasters", "roaster", "studio", "shop", "store", "vegan",
)


def _strip_generic_words(normalized: str) -> str:
	s = normalized
	for w in _GENERIC_NAME_WORDS:
		s = s.replace(w, "")
	return s


def names_same_ignoring_generic(a: str, b: str) -> bool:
	"""일반어(카페/제과 등)만 빼면 동일한 이름인가.

	'카페메리고라운드'↔'메리고라운드' → True, '베지경주제과'↔'경주제과' → False(베지 머리 잘림).
	"""
	ga = _strip_generic_words(_apply_name_aliases(normalize_name(a)))
	gb = _strip_generic_words(_apply_name_aliases(normalize_name(b)))
	if len(ga) < 2 or len(gb) < 2:
		return False
	if ga == gb:
		return True
	ka = re.sub(r"[^가-힣]", "", ga)
	kb = re.sub(r"[^가-힣]", "", gb)
	return len(ka) >= 2 and len(kb) >= 2 and ka == kb


_NAME_ALIASES = {
	"atto": "아토",
	"acu": "아커",
	"donothing": "두낫띵",
	"hideout": "하이드아웃",
	"poulsbo": "폴스보",
	"tea": "티",
	"쁘송": "쁘숑",
	"브래드": "브레드",
	"핫쵸메": "핫초메",
}


def _apply_name_aliases(normalized: str) -> str:
	s = normalized
	for src, dst in _NAME_ALIASES.items():
		s = s.replace(src, dst)
	return s


def names_related_for_near_match(a: str, b: str) -> bool:
	"""30m 이내 확정 후보에서만 쓰는 넓은 이름 관련성 판정.

	일반어/영문 카테고리 제거 후 동일하거나, 후보명 앞에 지역/시설 prefix 가 붙고 핵심명이
	그대로 남는 경우를 허용한다. 짧은 1~2자 이름은 prefix/suffix 완화를 하지 않아 '오라',
	'수' 같은 동명이점 오매칭을 막는다.
	"""
	if names_same_ignoring_generic(a, b):
		return True
	ga = _strip_generic_words(_apply_name_aliases(normalize_name(a)))
	gb = _strip_generic_words(_apply_name_aliases(normalize_name(b)))
	if len(ga) < 3 or len(gb) < 3:
		return False
	shorter, longer = (ga, gb) if len(ga) <= len(gb) else (gb, ga)
	if len(shorter) < 3:
		return False
	if longer.endswith(shorter) and len(longer) - len(shorter) <= 4:
		return True
	if longer.startswith(shorter) and len(longer) - len(shorter) <= 4:
		return True
	return False


def names_related_for_same_address(a: str, b: str) -> bool:
	"""도로명+건물번호가 같은 후보에서 쓰는 넓은 이름 관련성 판정.

	같은 주소와 근접 좌표가 이미 강한 증거이므로, 지역 prefix/suffix·시설명·SEO 키워드가
	붙은 이름을 더 넓게 허용한다. 1~2자 단일어는 우연 포함을 막기 위해 완화하지 않는다.
	"""
	if names_related_for_near_match(a, b):
		return True

	na = _apply_name_aliases(normalize_name(a))
	nb = _apply_name_aliases(normalize_name(b))
	if len(na) < 3 or len(nb) < 3:
		return False
	shorter, longer = (na, nb) if len(na) <= len(nb) else (nb, na)
	if len(shorter) < 3:
		return False
	if longer.startswith(shorter) and len(longer) - len(shorter) <= 20:
		return True
	if longer.endswith(shorter) and len(longer) - len(shorter) <= 8:
		return True
	if len(shorter) >= 4 and shorter in longer and len(longer) - len(shorter) <= 16:
		return True
	return False


# ─── 폐업 감지 ────────────────────────────────────────────────────────────────

_CLOSED_TOKENS = ("폐업", "(폐업)", "영업종료", "영업 종료", "CLOSED", "OUT_OF_BUSINESS")
_CLOSED_SIGNAL_KEYS = (
	"name", "category", "businessStatus", "bizStatus", "status", "statusName",
	"business_status", "status_name", "description",
)


def is_closed(naver: dict) -> bool:
	values: list[str] = []
	for key in _CLOSED_SIGNAL_KEYS:
		value = naver.get(key)
		if value:
			values.append(str(value))
	hay = " ".join(values).upper()
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
	n_road, n_num = _naver_road_and_number(naver)
	road_pts = 15 if (k_road and n_road and k_road == n_road and k_num == n_num) else 0

	# 행정동 겹침
	k_dist = extract_district_tokens(kakao.get("address", ""))
	n_dist = _naver_district_tokens(naver)
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

# exact 이름 + 행정동/리 일치 + 도로명 충돌 없음일 때 허용 거리.
#  · 양쪽 도로명+번지 비교 가능: 80m (좌표 오차 흡수, 도시 동명이점 오매칭 방지).
#  · 한쪽이 지번이라 도로명 비교 불가: 110m — 시골 지번 가게는 지오코딩 오차가 커서
#    84m('하얀오골계 베이커리카페') 같은 동일 가게가 80m 컷에 걸리던 문제 구제.
EXACT_DONG_RADIUS_M = 80
EXACT_DONG_JIBUN_RADIUS_M = 110
CHAIN_EXACT_DONG_RADIUS_M = 90
LARGE_FACILITY_EXACT_ROAD_RADIUS_M = 600
# 카카오/네이버 좌표는 같은 건물·상가 안에서도 출입구/건물 중심/지오코딩 기준점 차이로
# 100m 이상 흔들릴 수 있다. 도로명+건물번호가 같고 이름도 관련 있으면 주소 증거를 더 신뢰한다.
SAME_ADDRESS_RELATED_RADIUS_M = 300

_LARGE_FACILITY_HINTS = (
	"롯데백화점", "현대백화점", "신세계백화점", "갤러리아백화점", "백화점",
	"더현대", "스타필드", "타임스퀘어", "월드몰", "센터필드", "아울렛",
	"쇼핑몰", "몰", "마트", "호텔",
)


def _has_large_facility_hint(*names: str) -> bool:
	return any(hint in (name or "") for name in names for hint in _LARGE_FACILITY_HINTS)


def _road_conflict(kakao: dict, naver: dict) -> bool:
	k_road, k_num = extract_road_and_number(kakao.get("address", ""))
	n_road, n_num = _naver_road_and_number(naver)
	return bool(k_road and n_road and (k_road != n_road or k_num != n_num))


def _exact_dong_confirm(kakao: dict, naver: dict, score: dict) -> bool:
	"""exact 이름 + 행정동/리 일치 + 도로명 충돌 없음 + 비체인 + 거리 통과 = 동일 가게 확정.

	도로명을 양쪽 다 비교 가능하면 EXACT_DONG_RADIUS_M, 한쪽이 지번이라 비교 불가하면
	EXACT_DONG_JIBUN_RADIUS_M 까지 허용. 게이트 통과 + 점수컷(60) 면제 양쪽에서 공용.
	"""
	if score["name_kind"] != "exact" or score["breakdown"]["district"] <= 0:
		return False
	dist_m = score["distance_m"]
	if dist_m is None:
		return False
	if is_chain_like(kakao.get("name", "")):
		return False
	if _road_conflict(kakao, naver):
		return False  # 도로명 충돌 = 옆 지점이므로 제외
	k_road, _ = extract_road_and_number(kakao.get("address", ""))
	n_road, _ = _naver_road_and_number(naver)
	limit = EXACT_DONG_RADIUS_M if (k_road and n_road) else EXACT_DONG_JIBUN_RADIUS_M
	return dist_m <= limit


def _chain_exact_dong_confirm(kakao: dict, naver: dict, score: dict) -> bool:
	"""체인/분점 이름 exact 이고 같은 행정구역이면 지번 주소 좌표 오차를 일부 흡수.

	체인명만 exact 인 오매칭을 피하려고, 유명 체인 힌트로 잡힌 가게는 지점 토큰이 한쪽에라도
	있을 때만 완화한다. 도로명+번지가 양쪽 모두 있는데 다르면 다른 지점으로 본다.
	"""
	if score["name_kind"] != "exact" or score["breakdown"]["district"] <= 0:
		return False
	dist_m = score["distance_m"]
	if dist_m is None or dist_m > CHAIN_EXACT_DONG_RADIUS_M:
		return False
	if _road_conflict(kakao, naver):
		return False
	k_name = kakao.get("name", "")
	n_name = naver.get("name", "")
	if any(k_name.startswith(h) for h in CHAIN_HINTS) and not (branch_tokens(k_name) or branch_tokens(n_name)):
		return False
	return True


def _gate_pass(kakao: dict, naver: dict, score: dict) -> tuple[bool, str]:
	"""점수 외 추가 게이트. (통과 여부, 사유)."""
	kind = score["name_kind"]
	dist_m = score["distance_m"]
	bk = score["breakdown"]
	chain = is_chain_like(kakao.get("name", ""))

	if score["closed"]:
		return False, "폐업"

	k_name = kakao.get("name", "")
	n_name = naver.get("name", "")
	related_name = kind != "none" or names_related_for_near_match(k_name, n_name)
	same_road_near = (
		bk["road"] > 0
		and dist_m is not None
		and dist_m <= 30
		and names_related_for_same_address(k_name, n_name)
	)
	same_road_related = (
		bk["road"] > 0
		and dist_m is not None
		and dist_m <= SAME_ADDRESS_RELATED_RADIUS_M
		and names_related_for_same_address(k_name, n_name)
	)

	# 지점명 충돌 — 양쪽 다 분점 토큰이 있고 다르면 다른 지점이므로 거부 (체인·비체인 공통).
	# 예: '씨엘비 광주2호점' ↔ '씨엘비 광주점'. 한쪽만 있으면(지점명 생략) 통과.
	# 단, 도로명+건물번호와 좌표가 같은 건물 수준으로 일치하면 지점명 표기차로 본다.
	# '의정부점'↔'의정부1호점', '성산직영점'↔'성산 직영점' 같은 케이스 구제.
	if branch_conflict(k_name, n_name) and not (
			same_road_related and not _hard_numbered_branch_conflict(k_name, n_name)):
		return False, "지점명 충돌"
	if headquarters_conflict(k_name, n_name):
		return False, "본사 후보 수동 확인 필요"

	# mid(중간/꼬리 부분일치)는 '머리(브랜드) 잘림'일 위험 — 일반어(카페/제과) 차이나
	# 같은 도로명+건물번호·근접 좌표가 있는 경우만 인정. '카페X'↔'X'는 통과하지만,
	# 주소 증거 없는 '베지경주제과'↔'경주제과' 같은 머리 잘림은 계속 거부.
	if kind == "mid" and not (
			names_same_ignoring_generic(k_name, n_name)
			or (dist_m is not None and dist_m <= 30 and names_related_for_near_match(k_name, n_name))
			or same_road_related):
		return False, "이름 부분일치(머리 다름)"

	if chain:
		# A: 체인성 — 기본은 거리 ≤ 30m AND (도로명번호 일치 OR 이름 exact).
		# 단, 백화점/아울렛/대형몰 입점 매장은 카카오/네이버 좌표가 건물 양끝으로 50~80m
		# 흔들리는 경우가 많다. 이름 exact + 도로명번호 일치면 같은 입점 매장으로 보고 80m 까지 허용.
		if dist_m is None:
			if bk["road"] > 0:
				return True, "체인 좌표 없음, 도로명번호 일치"
			return False, f"체인 거리 미달 (dist={dist_m})"
		if kind == "exact" and bk["road"] > 0 and dist_m <= 80:
			return True, f"체인 도로명+거리 통과 ({dist_m:.0f}m)"
		if same_road_related:
			return True, f"체인 도로명+이름+거리 통과 ({dist_m:.0f}m)"
		if (
				kind == "exact"
				and bk["road"] > 0
				and dist_m <= LARGE_FACILITY_EXACT_ROAD_RADIUS_M
				and _has_large_facility_hint(k_name, n_name)):
			return True, f"체인 대형시설 도로명+이름+거리 통과 ({dist_m:.0f}m)"
		if _chain_exact_dong_confirm(kakao, naver, score):
			return True, f"체인 이름 일치+동 일치, 거리 통과 ({dist_m:.0f}m)"
		if dist_m > 30:
			return False, f"체인 거리 미달 (dist={dist_m})"
		if not related_name:
			return False, "체인 근접하지만 이름 무관"
		return True, "체인 게이트 통과"

	if dist_m is not None:
		# B: 비-체인, 좌표 있음.
		# 같은 브랜드 근접 지점을 옆 지점 placeId 에 잘못 매칭하던 문제 방지 —
		#   · 도로명+번지 일치: 같은 주소 확정 → 거리 ≤ 80m 까지 허용(좌표 오차 흡수).
		#   · 도로명 불일치: 다른 지점일 수 있으므로 같은 건물 수준(≤ 20m)일 때만 허용.
		if same_road_related:
			return True, f"도로명+이름+거리 통과 ({dist_m:.0f}m)"
		# 이름 exact + 행정동/리 일치면 같은 가게라는 강한 증거 — 단, 양쪽 도로명+번지가 모두
		# 있는데 서로 다를 때(테헤란로 vs 봉은사로)는 옆지점 충돌이므로 제외. 한쪽이 지번이라
		# 비교 불가하거나(코티지송송·하얀오골계) 도로명이 일치하면 좌표 오차를 흡수.
		# 비-exact 는 아래 30m 컷 유지 → 브랜드 옆지점 오매칭 방지는 그대로.
		if _exact_dong_confirm(kakao, naver, score):
			return True, f"이름 일치+동 일치, 거리 통과 ({dist_m:.0f}m)"
		if dist_m <= 30 and related_name:
			return True, f"근접 통과 ({dist_m:.0f}m)"
		if bk["road"] > 0:
			return False, "도로명번호 일치하지만 이름 무관"
		return False, f"도로명 불일치 + 거리 미달 ({dist_m:.0f}m)"

	# C: 좌표 없음 — 도로명+건물번호 필수
	if bk["road"] > 0:
		return True, "좌표 없음, 도로명번호 일치"
	return False, "좌표 없음 + 도로명번호 불일치"


def _strong_address_evidence(kakao: dict, naver: dict, score: dict) -> Optional[str]:
	if score["closed"] or score["breakdown"]["road"] <= 0:
		return None
	if not _same_region_head(kakao, naver):
		return None
	dist_m = score["distance_m"]
	k_name = kakao.get("name", "")
	n_name = naver.get("name", "")
	if (
			dist_m is not None
			and dist_m <= 30
			and names_related_for_same_address(k_name, n_name)):
		return f"도로명+근접 ({dist_m:.0f}m)"
	if (
			dist_m is not None
			and dist_m <= SAME_ADDRESS_RELATED_RADIUS_M
			and names_related_for_same_address(k_name, n_name)):
		return f"도로명+이름 ({dist_m:.0f}m)"
	if (
			dist_m is not None
			and dist_m <= LARGE_FACILITY_EXACT_ROAD_RADIUS_M
			and score["name_kind"] == "exact"
			and _has_large_facility_hint(k_name, n_name)):
		return f"대형시설 도로명+이름 ({dist_m:.0f}m)"
	if dist_m is None and names_related_for_same_address(k_name, n_name):
		return "도로명+이름 (좌표 없음)"
	return None


def _select_strong_address_candidate(
		kakao: dict,
		scored: list[tuple[dict, dict]]) -> Optional[tuple[dict, dict, str]]:
	"""점수 1등보다 도로명+번지가 확실한 후보를 우선한다.

	네이버 검색 결과는 가까운 다른 도로/지점 후보가 점수 1등으로 올라오고, 정작 같은 주소
	후보가 2~3등으로 밀리는 경우가 있다. 저장 후보 수동확정 사례 기준으로, 같은 지역의
	도로명+건물번호가 일치하고 게이트도 통과하는 후보는 전역 점수 순위보다 주소 증거를 우선한다.
	"""
	strong: list[tuple[dict, dict, str, str]] = []
	for cand, score in scored:
		evidence = _strong_address_evidence(kakao, cand, score)
		if not evidence:
			continue
		ok, why = _gate_pass(kakao, cand, score)
		if ok:
			strong.append((cand, score, why, evidence))
	if not strong:
		return None
	strong.sort(key=lambda cs: cs[1]["total"], reverse=True)
	cand, score, why, evidence = strong[0]
	return cand, score, f"{why}; 주소후보 우선({evidence})"


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

	strong_choice = _select_strong_address_candidate(kakao, scored)
	if strong_choice is not None:
		chosen, _, why = strong_choice
		return chosen, scored, f"주소 확정 매칭 ({why})"

	top_cand, top_score = scored[0]
	ok, why = _gate_pass(kakao, top_cand, top_score)
	if not ok:
		return None, scored, f"게이트 실패: {why}"

	dist_m = top_score["distance_m"]
	kind = top_score["name_kind"]
	bk = top_score["breakdown"]

	def _gap_margin() -> Optional[int]:
		# 박빙 후보 2개면 모호 → 거부(동점 오매칭 차단).
		# 단, 2등이 게이트 자체를 통과 못 하는 입구/주차장/무관 후보면 모호 후보로 보지 않는다.
		for cand, cand_score in scored[1:]:
			ok, _ = _gate_pass(kakao, cand, cand_score)
			if ok:
				return top_score["total"] - cand_score["total"]
		return None

	def _gap_ok() -> bool:
		gap = _gap_margin()
		return gap is None or gap >= MIN_SCORE_GAP_AUTO_SELECT

	# 확정 매칭 — 점수컷 면제(이름 표기차로 점수 낮은 '같은 가게' 구제). 단 격차 컷은 유지.
	#  · 주소 확정: 도로명+번지 일치 + ≤30m. 이름이 전혀 달라도 동일 장소 확신(예: '카페폴리'↔'폴리 합정').
	#  · 좌표+이름 확정: 같은 건물(≤30m) + 이름 관련(비-none) + 동 일치 + 비-체인.
	#    이름 표기차(메리고라운드↔카페메리고라운드)뿐인 같은 가게를 구제하되, 우연 근접(이름 무관)·
	#    체인 옆지점은 제외.
	# 게이트에서 이미 '머리 다른 mid'·지점명 충돌은 걸러졌으므로, 여기선 이름 관련(비-none)이면
	# 같은 가게로 본다. exact/prefix/일반어차이-mid 만 여기 도달.
	addr_confirmed = bk["road"] > 0 and dist_m is not None and dist_m <= 30
	related_name = kind != "none" or names_related_for_near_match(kakao.get("name", ""), top_cand.get("name", ""))
	same_address_related = (
		bk["road"] > 0
		and dist_m is not None
		and dist_m <= SAME_ADDRESS_RELATED_RADIUS_M
		and names_related_for_same_address(kakao.get("name", ""), top_cand.get("name", ""))
	)
	facility_exact_same_address = (
		bk["road"] > 0
		and dist_m is not None
		and dist_m <= LARGE_FACILITY_EXACT_ROAD_RADIUS_M
		and kind == "exact"
		and _has_large_facility_hint(kakao.get("name", ""), top_cand.get("name", ""))
	)
	strong_same_address = (
		bk["road"] > 0
		and dist_m is not None
		and dist_m <= 10
		and names_related_for_same_address(kakao.get("name", ""), top_cand.get("name", ""))
	)
	chain = is_chain_like(kakao.get("name", ""))
	prox_confirmed = (
		dist_m is not None and dist_m <= 30
		and related_name
		and bk["district"] > 0
		and not chain
	)
	chain_prox_confirmed = (
		dist_m is not None and dist_m <= 30
		and related_name
		and chain
	)
	# exact + 동 일치 + 지번(도로명 비교 불가)이면 ≤110m 까지 동일 가게 확정 — 점수컷 면제.
	# 84m '하얀오골계'처럼 도로명 점수가 없어 60 미만이라도 구제(게이트와 동일 기준).
	exact_dong_confirmed = _exact_dong_confirm(kakao, top_cand, top_score)
	if (
			addr_confirmed or prox_confirmed or chain_prox_confirmed or exact_dong_confirmed
			or same_address_related or facility_exact_same_address):
		if not strong_same_address and not _gap_ok():
			gap = _gap_margin()
			return None, scored, f"top1-top2 격차 부족 ({gap} < {MIN_SCORE_GAP_AUTO_SELECT})"
		label = "주소 확정 매칭" if (addr_confirmed or same_address_related) else "좌표+이름 확정 매칭"
		return top_cand, scored, f"{label} ({why})"

	if top_score["total"] < MIN_SCORE_AUTO_SELECT:
		return None, scored, f"점수 미달 ({top_score['total']} < {MIN_SCORE_AUTO_SELECT})"

	if not _gap_ok():
		gap = _gap_margin()
		return None, scored, f"top1-top2 격차 부족 ({gap} < {MIN_SCORE_GAP_AUTO_SELECT})"

	return top_cand, scored, why


# ─── 실패 진단 분류 ───────────────────────────────────────────────────────────

def classify_store(
	candidates: list[dict],
	matched: Optional[dict],
	reason: str,
	scored: list[tuple[dict, dict]],
) -> dict:
	"""select_match 결과를 진단 버킷으로 분류 (순수 함수).

	버킷:
	  - NO_CAND: 네이버 검색 결과 0건
	  - MATCH: 지금 돌리면 매칭됨
	  - CLOSED: 네이버 폐업 후보가 top 후보
	  - MISMATCH: 후보는 떴으나 주소/지점명/점수 게이트에서 거부

	반환 dict: bucket, reason, num_candidates, top_cand_name, top_score,
	name_kind, distance_m, road_match.
	"""
	if not candidates:
		return {
			"bucket": "NO_CAND",
			"reason": reason,
			"num_candidates": 0,
			"top_cand_name": None,
			"top_score": None,
			"name_kind": None,
			"distance_m": None,
			"road_match": None,
		}

	bucket = "MATCH" if matched else "MISMATCH"
	top_cand, top_score = scored[0]
	if not matched and top_score.get("closed"):
		bucket = "CLOSED"
	return {
		"bucket": bucket,
		"reason": reason,
		"num_candidates": len(candidates),
		"top_cand_name": top_cand.get("name"),
		"top_score": top_score["total"],
		"name_kind": top_score["name_kind"],
		"distance_m": top_score["distance_m"],
		"road_match": top_score["breakdown"]["road"] > 0,
	}
