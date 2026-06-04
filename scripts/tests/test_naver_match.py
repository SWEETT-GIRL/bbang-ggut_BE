"""naver_match 단위 테스트.

실행: cd scripts && python -m pytest tests/ -v
"""

import sys
from pathlib import Path

# scripts/ 를 import path 에 추가 (pytest 가 scripts/tests 에서 돌 때)
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from naver_match import (  # noqa: E402
	branch_conflict,
	branch_token,
	classify_menu_texts,
	classify_store,
	extract_road_and_number,
	headquarters_conflict,
	is_chain_like,
	is_closed,
	name_match_kind,
	names_related_for_near_match,
	names_related_for_same_address,
	names_same_ignoring_generic,
	normalize_name,
	score_candidate,
	select_match,
)


# ─── normalize_name / name_match_kind ────────────────────────────────────────

class TestNormalize:
	def test_strip_branch_suffix(self):
		assert normalize_name("테라로사 본점") == "테라로사"

	def test_html_tags_removed(self):
		assert normalize_name("<b>소금</b>빵집") == "소금빵집"

	def test_company_suffix_removed(self):
		assert normalize_name("브래드무어 주식회사더크리스마스컴퍼니") == "브래드무어"

	def test_ampersand_korean_equivalent(self):
		assert normalize_name("브레드 & 버터") == normalize_name("브레드앤버터")

	def test_underscore_removed(self):
		assert normalize_name("브레드_히어_") == normalize_name("브레드히어")


class TestNameMatchKind:
	def test_exact(self):
		assert name_match_kind("테라로사", "테라로사") == "exact"

	def test_prefix(self):
		# '베통' vs '베통제과점' — len ratio 4/5 = 0.8 ≥ 0.4
		assert name_match_kind("베통", "베통제과점") == "prefix"

	def test_prefix_too_short_fails(self):
		# 1자는 무조건 none
		assert name_match_kind("브", "브래드") == "none"

	def test_mid_substring(self):
		# 짧은쪽 ≥ 3, ratio ≥ 0.6 (4/6 = 0.67)
		assert name_match_kind("커피마마", "달콤커피마마") == "mid"

	def test_unrelated(self):
		assert name_match_kind("테라로사", "스타벅스") == "none"


# ─── extract_road_and_number ─────────────────────────────────────────────────

class TestRoadAndNumber:
	def test_basic(self):
		assert extract_road_and_number("서울 관악구 남부순환로 1850") == ("남부순환로", "1850")

	def test_with_dash(self):
		assert extract_road_and_number("경기 용인시 기흥구 공세로 73-1") == ("공세로", "73-1")

	def test_no_number(self):
		assert extract_road_and_number("서울 강남구 테헤란로") == ("", "")

	def test_empty(self):
		assert extract_road_and_number("") == ("", "")
		assert extract_road_and_number(None) == ("", "")


# ─── is_chain_like ────────────────────────────────────────────────────────────

class TestChainLike:
	def test_franchise_prefix(self):
		assert is_chain_like("스타벅스 강남점")
		assert is_chain_like("이디야커피")

	def test_branch_pattern(self):
		assert is_chain_like("우리동네 빵집 본점")
		assert is_chain_like("로컬 1호점")

	def test_non_chain(self):
		assert not is_chain_like("테라로사")
		assert not is_chain_like("브래드무어")


class TestBranchAndGeneric:
	def test_branch_token(self):
		assert branch_token("씨엘비베이커리 광주2호점") == "광주2호점"
		assert branch_token("류재은베이커리 류하우스점") == "류하우스점"
		assert branch_token("이성당") == ""
		assert branch_token("이성당 신관") == ""  # '신관'은 점 접미사 아님

	def test_branch_conflict(self):
		assert branch_conflict("씨엘비 광주2호점", "씨엘비 광주점")
		assert not branch_conflict("류재은베이커리 류하우스점", "류재은베이커리")
		assert not branch_conflict("이성당 신관", "이성당")
		assert not branch_conflict("몽슈슈 현대백화점무역센터점", "몽슈슈 무역센터점")
		assert not branch_conflict("밤부베이커리 롯데백화점강남점", "밤부베이커리 강남점")
		assert not branch_conflict("써브웨이 신사점", "써브웨이 신사역점")
		assert not branch_conflict("미쉐 홍대점", "미쉐 홍대지점")
		assert not branch_conflict("에그드랍 역삼GS점", "에그드랍 역삼GS직영점")
		assert not branch_conflict("플레어비 도곡본점", "플레어비 도곡점")
		assert branch_conflict("브레드데이 동탄2호점", "브레드데이 동탄3호점")

	def test_branch_conflict_seo_stuffed_name(self):
		# 네이버 SEO 도배명에서도 지점 충돌 감지 — 끝 토큰이 키워드여도 finditer 로 잡음.
		assert branch_conflict("씨엘비베이커리 광주2호점", "씨엘비베이커리 광주점 디저트맛집 빵집")
		assert branch_conflict("베지경주제과 황리단길점", "경주제과 본점 베이커리 카페 디저트")
		# 같은 지점이면 충돌 아님
		assert not branch_conflict("세라젬웰파크 청량리점", "세라젬 웰파크 청량리점 건강관리")

	def test_headquarters_conflict(self):
		assert headquarters_conflict("빈스로드 본사", "빈스로드 상봉본점")
		assert not headquarters_conflict("빈스로드 본사", "빈스로드 본사")
		assert not headquarters_conflict("빈스로드 상봉점", "빈스로드 상봉본점")

	def test_names_same_ignoring_generic(self):
		assert names_same_ignoring_generic("카페메리고라운드", "메리고라운드")
		assert names_same_ignoring_generic("케이크오라", "오라")
		assert names_same_ignoring_generic("VB커피", "VB COFFEE")
		assert names_same_ignoring_generic("보라곰케이크", "보라곰 주문제작&레터링케이크")
		assert names_same_ignoring_generic("스윗바이비", "비건제과 스윗바이비")
		assert not names_same_ignoring_generic("베지경주제과", "경주제과")
		assert not names_same_ignoring_generic("오해피데이", "해피데이")
		assert not names_same_ignoring_generic("미운오리", "주남팥집")

	def test_names_related_for_near_match(self):
		assert names_related_for_near_match("시트로넬", "시트로넬 베이킹스튜디오")
		assert names_related_for_near_match("캐리베이커리", "캐리 베이커리 CARRIE BAKERY")
		assert names_related_for_near_match("크림데이", "크림데이 글루텐프리 케이크샵")
		assert names_related_for_near_match("SF베이글", "SF bagels")
		assert names_related_for_near_match("황제찹쌀꽈배기 논현영동시장점", "영동황제꽈배기 논현영동시장점")
		assert not names_related_for_near_match("오라", "오라하우스")

	def test_names_related_for_same_address(self):
		assert names_related_for_same_address("하트카페", "역삼 하트카페")
		assert names_related_for_same_address("아토커피 역삼점", "ATTO커피 본점")
		assert names_related_for_same_address("하이드아웃", "Hideout")
		assert names_related_for_same_address("지유가오카핫쵸메 7호점", "지유가오카 핫초메 논현점")
		assert names_related_for_same_address("플레플레", "플레플레 케이크 주문케이크 떡케이크")
		assert names_related_for_same_address("뚜쥬루 갤러리아점", "뚜쥬루갤러리아백화점센터시티점")
		assert not names_related_for_same_address("오리", "푸른목장")


# ─── is_closed ────────────────────────────────────────────────────────────────

class TestClosed:
	def test_closed_in_category(self):
		assert is_closed({"name": "X", "category": "베이커리 폐업"})

	def test_closed_in_name(self):
		assert is_closed({"name": "테라로사 (폐업)", "category": ""})

	def test_not_closed(self):
		assert not is_closed({"name": "테라로사", "category": "베이커리"})

	def test_closed_in_status(self):
		assert is_closed({"name": "테라로사", "status": "OUT_OF_BUSINESS"})


# ─── score_candidate / select_match ──────────────────────────────────────────

def _kakao(name="테라로사", lat=37.5, lng=127.0, address="서울 강남구 테헤란로 100"):
	return {"name": name, "lat": lat, "lng": lng, "address": address}


def _naver(
		name="테라로사", y=37.5, x=127.0, address="서울 강남구 테헤란로 100",
		category="베이커리", road_address=None):
	naver = {"name": name, "y": str(y), "x": str(x), "address": address, "category": category, "id": "123"}
	if road_address is not None:
		naver["roadAddress"] = road_address
	return naver


class TestScoreCandidate:
	def test_exact_close(self):
		s = score_candidate(_kakao(), _naver())
		# name=40, dist≈0m=30, road=15, district=5 → 90
		assert s["total"] == 90
		assert s["name_kind"] == "exact"
		assert s["distance_m"] < 1

	def test_exact_far_negative_distance(self):
		# 200m 떨어짐 — dist=-10
		s = score_candidate(_kakao(), _naver(y=37.5018))
		assert s["breakdown"]["distance"] == -10

	def test_closed_penalty(self):
		s = score_candidate(_kakao(), _naver(name="테라로사 (폐업)"))
		assert s["closed"] is True
		assert s["breakdown"]["closed"] == -100
		assert s["total"] < 0

	def test_no_coords(self):
		k = _kakao(lat=None, lng=None)
		n = _naver(y=0, x=0)
		s = score_candidate(k, n)
		# 거리 점수 없음. exact=40, road=15, district=5 → 60
		assert s["breakdown"]["distance"] == 0
		assert s["distance_m"] is None

	def test_road_address_preferred_over_jibun_address(self):
		k = _kakao(address="서울 강남구 테헤란로 100")
		n = _naver(address="서울 강남구 역삼동 1", road_address="서울 강남구 테헤란로 100")
		s = score_candidate(k, n)
		assert s["breakdown"]["road"] == 15


class TestSelectMatch:
	def test_empty_candidates(self):
		chosen, scored, why = select_match(_kakao(), [])
		assert chosen is None
		assert "후보 없음" in why

	def test_exact_close_accepted(self):
		chosen, _, _ = select_match(_kakao(), [_naver()])
		assert chosen is not None

	def test_chain_within_30m_with_road_match(self):
		k = _kakao(name="스타벅스 강남점")
		n = _naver(name="스타벅스 강남점")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_chain_exact_road_match_60m_accepted(self):
		# 백화점/몰 입점 매장 좌표 오차 회귀: exact 이름 + 도로명번호 일치면 80m 까지 허용.
		k = _kakao(name="스타벅스 강남점")
		n = _naver(name="스타벅스 강남점", y=37.50054)
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_chain_beyond_150m_rejected(self):
		k = _kakao(name="스타벅스 강남점")
		# 170m 이상 떨어짐 — exact + road match 여도 체인 좌표 오차 허용 범위 밖.
		n = _naver(name="스타벅스 강남점", y=37.5016)
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "체인" in why

	def test_chain_same_road_related_name_130m_accepted(self):
		# 대형 건물/백화점 좌표는 100m 이상 흔들릴 수 있다. 도로명+건물번호와 이름이
		# 모두 같은 지점이면 150m 까지 흡수.
		k = _kakao(name="곤트란쉐리에 논현건설회관점", address="서울 강남구 언주로 711")
		n = _naver(name="곤트란쉐리에 논현건설회관점", y=37.50115, address="서울 강남구 언주로 711")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_chain_large_facility_exact_road_match_500m_accepted(self):
		# 실패 throttle 회귀: 백화점 입점 매장은 같은 도로명+번지·이름 exact 여도
		# 카카오/네이버 좌표가 백화점 건물 안팎 기준점 차이로 크게 흔들릴 수 있다.
		k = _kakao(
			name="솔아띠몽 롯데백화점 청량리점",
			address="서울 동대문구 왕산로 214",
		)
		n = _naver(
			name="솔아띠몽 롯데백화점 청량리점",
			y=37.5045,
			address="서울 동대문구 왕산로 214",
		)
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why
		assert "대형시설" in why

	def test_chain_exact_jibun_district_70m_accepted(self):
		# 이름은 exact 인데 네이버 후보가 지번만 남아 도로명 비교가 불가능한 백화점/몰 입점 매장.
		k = _kakao(
			name="성심당 옛맛솜씨 롯데백화점 대전점",
			address="대전 서구 계룡로 598")
		n = _naver(
			name="성심당 옛맛솜씨 롯데백화점 대전점",
			y=37.50063,
			address="대전 서구 괴정동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_chain_exact_jibun_district_beyond_90m_rejected(self):
		k = _kakao(
			name="성심당 옛맛솜씨 롯데백화점 대전점",
			address="대전 서구 계룡로 598")
		n = _naver(
			name="성심당 옛맛솜씨 롯데백화점 대전점",
			y=37.50095,
			address="대전 서구 괴정동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "체인" in why

	def test_chain_near_related_name_accepted(self):
		# 체인/분점도 30m 이내에서 이름 관련성이 충분하면 도로명번호가 없어도 통과.
		k = _kakao(name="탠저린 논현점", address="서울 강남구 강남대로132길 59")
		n = _naver(name="탠저린 논현", y=37.50002, address="서울 강남구 논현동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_chain_near_generic_name_gets_score_cut_exemption(self):
		# 게이트는 통과했는데 점수 35점이라 떨어지던 회귀:
		# 입점 시설/일반어만 다른 같은 이름은 체인/분점에서도 점수컷 면제.
		k = _kakao(
			name="몽슈슈 현대백화점무역센터점",
			address="서울 강남구 테헤란로 517")
		n = _naver(
			name="몽슈슈 현대백화점 무역센터점",
			y=37.50002,
			address="서울 강남구 삼성동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_chain_near_generic_prefix_name_gets_score_cut_exemption(self):
		k = _kakao(name="프루케 위례점", address="경기 성남시 수정구 위례광장로 5")
		n = _naver(name="프루케 생과일케이크 위례점", y=37.50002, address="경기 성남시 수정구 창곡동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_non_chain_60m_road_match_accepted(self):
		# 60m 이지만 도로명+번지 일치 → 같은 주소 확정이라 통과(좌표 오차 흡수).
		n = _naver(y=37.50054, address="서울 강남구 테헤란로 100")
		chosen, _, why = select_match(_kakao(address="서울 강남구 테헤란로 100"), [n])
		assert chosen is not None, why

	def test_non_chain_60m_road_mismatch_rejected(self):
		# 60m + 도로명 다름 → 옆 지점(브랜드)일 수 있어 거부(브랜드 지점 오매칭 방지).
		n = _naver(y=37.50054, address="서울 강남구 봉은사로 50")
		chosen, _, why = select_match(_kakao(address="서울 강남구 테헤란로 100"), [n])
		assert chosen is None
		assert "도로명" in why

	def test_non_chain_close_road_mismatch_accepted(self):
		# 30m 이내(같은 건물 수준) — 도로명 표기 차이여도 통과.
		n = _naver(y=37.50022, address="서울 강남구 봉은사로 50")
		chosen, _, why = select_match(_kakao(address="서울 강남구 테헤란로 100"), [n])
		assert chosen is not None, why

	def test_address_confirmed_name_mismatch_accepted(self):
		# 이름이 전혀 달라도(예: '카페폴리' ↔ '폴리 합정') 도로명+번지 일치 + 같은 자리(≤30m)면
		# 동일 장소로 확신 매칭 — 점수 미달이어도 통과.
		k = _kakao(name="카페폴리", address="서울 강남구 테헤란로 100")
		n = _naver(name="폴리 합정", y=37.50010, address="서울 강남구 테헤란로 100")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why
		assert "주소 확정" in why

	def test_non_chain_120m_rejected(self):
		n = _naver(y=37.50108)
		chosen, _, why = select_match(_kakao(), [n])
		assert chosen is None
		assert "거리" in why

	def test_exact_name_jibun_district_within_80m_accepted(self):
		# 코티지송송 회귀: 카카오 지번(도로명 없음) + 이름 exact + 같은 면/리 → 42m 통과.
		# 도로명 대조가 구조적으로 불가능하고 좌표 오차만 있는 시골 가게 구제.
		k = _kakao(name="코티지송송", address="충남 아산시 송악면 송학리 15-8")
		n = _naver(name="코티지송송", y=37.50038, address="충남 아산시 송악면 외암로 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_exact_name_no_district_beyond_30m_rejected(self):
		# exact 이지만 동/리 불일치 → 80m 완화 안 됨(다른 지역 동명이점 방지). 42m 거부.
		k = _kakao(name="코티지송송", address="충남 아산시 송악면 송학리 15-8")
		n = _naver(name="코티지송송", y=37.50038, address="서울 강남구 테헤란로 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "도로명" in why

	def test_exact_kakao_road_naver_jibun_within_80m_accepted(self):
		# exact + 동 일치 + 도로명 충돌 없음(네이버가 지번이라 비교 불가) → 60m 통과.
		# 백화점/지번 등록 차이로 도로명이 한쪽만 있는 경우 흡수.
		k = _kakao(name="가실마루", address="대구 남구 현충로 20")
		n = _naver(name="가실마루", y=37.50054, address="대구 남구 봉덕동 100-1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_exact_jibun_rural_84m_accepted(self):
		# 하얀오골계 회귀(id=2573): exact + 같은 면 + 도로명 비교 불가(네이버 지번) →
		# 시골 지번 가게는 좌표 오차가 커 84m도 동일 가게. 80m→110m 완화로 구제.
		k = _kakao(name="하얀오골계 베이커리카페",
			address="강원특별자치도 원주시 판부면 내남송길 98-11")
		n = _naver(name="하얀오골계 베이커리 카페", y=37.50076,
			address="강원특별자치도 원주시 판부면 서곡리 100")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_exact_road_comparable_beyond_80m_rejected(self):
		# 양쪽 도로명 비교 가능(둘 다 도로명+번지)하면 완화 안 됨 — 90m 거부.
		# 지번 완화는 '도로명 비교 불가'일 때만. 도시 동명이점 오매칭 방지.
		k = _kakao(name="하얀오골계", address="서울 강남구 테헤란로 100")
		n = _naver(name="하얀오골계", y=37.50081, address="서울 강남구 테헤란로 200")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "거리" in why or "도로명" in why

	def test_proximity_related_name_accepted(self):
		# 같은 가게 표기차(메리고라운드 ↔ 카페메리고라운드) + 같은 건물(≤30m) + 동 일치 → 매칭.
		# 이름 점수만으론 60 미만이지만 좌표+이름관련 확정으로 점수컷 면제.
		k = _kakao(name="메리고라운드")  # 강남구 테헤란로 100
		n = _naver(name="카페메리고라운드", y=37.5001, address="서울 강남구 봉은사로 50")  # ~11m, 도로명 다름
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why
		assert "확정" in why

	def test_proximity_unrelated_name_rejected(self):
		# 좌표만 우연히 겹치고 이름 무관(미운오리 ↔ 주남팥집) → 거부(우연 근접 오매칭 방지).
		k = _kakao(name="미운오리")
		n = _naver(name="주남팥집", y=37.5, address="서울 강남구 봉은사로 50")
		chosen, _, _ = select_match(k, [n])
		assert chosen is None

	def test_prox_head_truncated_brand_without_same_road_rejected(self):
		# 머리(브랜드) 잘림 — '베지경주제과' ↔ '경주제과'. 같은 주소 증거가 없으면 거부.
		k = _kakao(name="베지경주제과 황리단길점", address="경북 경주시 포석로 1047")
		n = _naver(name="경주제과", y=37.50002, address="경북 경주시 태종로 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "머리" in why or "부분일치" in why

	def test_same_road_near_head_truncated_brand_accepted(self):
		# 같은 도로명+건물번호와 근접 좌표가 있으면 지역/브랜드 prefix 표기 차이를 허용.
		k = _kakao(name="베지경주제과 황리단길점", address="경북 경주시 포석로 1047")
		n = _naver(name="경주제과", y=37.50002, address="경북 경주시 포석로 1047")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_prox_generic_word_diff_accepted(self):
		# 일반어(카페)만 다른 같은 가게 — '하얀파도' ↔ '카페 하얀파도'. 근접+동 → 매칭.
		k = _kakao(name="하얀파도")
		n = _naver(name="카페 하얀파도", y=37.50005, address="서울 강남구 봉은사로 50")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_prox_english_generic_word_diff_accepted(self):
		k = _kakao(name="VB커피", address="서울 강남구 논현로149길 8")
		n = _naver(name="VB COFFEE", y=37.50002, address="서울 강남구 논현동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_prox_long_generic_word_diff_accepted(self):
		k = _kakao(name="보라곰케이크", address="서울 마포구 신촌로2안길 12")
		n = _naver(name="보라곰 주문제작&레터링케이크", y=37.50002, address="서울 마포구 동교동 1")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_prox_short_generic_word_diff_accepted(self):
		# 짧은 이름도 일반어만 다르고 30m 이내 + 같은 구/동이면 같은 가게로 본다.
		# 예: '오라' ↔ '케이크오라', '콤마' ↔ '카페 콤마'.
		k = _kakao(name="오라", address="경기 고양시 덕양구 오부자로 14")
		n = _naver(name="케이크오라", y=37.50002, address="경기 고양시 덕양구 오부자로 14")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_branch_conflict_rejected(self):
		# 양쪽 다른 번호 지점 토큰 — 1m여도 거부(옆지점 오매칭 방지).
		k = _kakao(name="브레드데이 동탄2호점", address="경기 화성시 동탄순환대로18길 16")
		n = _naver(name="브레드데이 동탄3호점", y=37.50001, address="경기 화성시 동탄순환대로18길 16")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "지점" in why

	def test_branch_conflict_same_road_variant_accepted(self):
		# 같은 주소/좌표이면 본점·직영점·세부 지역명이 섞인 지점 표기차는 허용.
		k = _kakao(name="천안당호두과자 의정부점", address="경기 의정부시 서부로 685")
		n = _naver(name="천안당호두과자 의정부1호점", y=37.50001, address="경기 의정부시 서부로 685")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_branch_conflict_same_road_spelling_variant_accepted(self):
		k = _kakao(name="지유가오카핫쵸메 7호점", address="서울 강남구 강남대로118길 59")
		n = _naver(name="지유가오카 핫초메 논현점", y=37.50001, address="서울 강남구 강남대로118길 59")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_branch_one_sided_accepted(self):
		# 한쪽만 지점 토큰(생략) — '류재은베이커리 류하우스점' ↔ '류재은베이커리'. 같은 가게로 매칭.
		k = _kakao(name="류재은베이커리 류하우스점", address="경기 파주시 문산읍 반구정로 36")
		n = _naver(name="류재은베이커리", y=37.50002, address="경기 파주시 문산읍 반구정로 36")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_headquarters_store_candidate_rejected(self):
		# '본사'는 회사/사무실일 수 있어 같은 주소여도 본사로 등록된 후보가 아니면 수동 확인.
		k = _kakao(name="빈스로드 본사", address="서울 중랑구 봉화산로 111")
		n = _naver(name="빈스로드 상봉본점", y=37.50001, address="서울 중랑구 봉화산로 111")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "본사" in why

	def test_proximity_related_name_ambiguous_rejected(self):
		# 근접+이름관련이라도 비슷한 후보 2개가 점수 박빙이면 모호 → 거부(격차 컷 유지).
		k = _kakao(name="메리고라운드")
		n1 = _naver(name="카페메리고라운드", y=37.5001, address="서울 강남구 봉은사로 50")
		n2 = _naver(name="메리고라운드카페", y=37.5001, address="서울 강남구 봉은사로 52")
		chosen, _, _ = select_match(k, [n1, n2])
		assert chosen is None

	def test_no_coords_road_match_accepted(self):
		k = _kakao(lat=None, lng=None)
		n = _naver(y=0, x=0)
		chosen, _, _ = select_match(k, [n])
		assert chosen is not None

	def test_no_coords_road_mismatch_rejected(self):
		k = _kakao(lat=None, lng=None, address="서울 강남구 테헤란로 100")
		n = _naver(y=0, x=0, address="서울 강남구 봉은사로 50")
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "도로명" in why

	def test_close_tie_rejected(self):
		# top1=70, top2=60 — gap 10 < 15
		n1 = _naver(name="테라로사", y=37.50054, address="서울 강남구 봉은사로 50")  # 60m, road 다름 → exact40+dist20+동5=65
		n2 = _naver(name="테라로사커피", y=37.50054, address="서울 강남구 봉은사로 50")  # prefix25+dist20+동5=50
		chosen, scored, why = select_match(_kakao(), [n1, n2])
		# 두 후보 점수 차이 < 15 면 거절. 점수 자체가 60 미만이면 점수 컷에서 거절.
		# 어느 쪽이든 chosen None 이어야 함.
		assert chosen is None or scored[0][1]["total"] - scored[1][1]["total"] >= 15


# ─── classify_menu_texts (item 4) ────────────────────────────────────────────

class TestClassifyMenuTexts:
	def test_basic_with_label(self):
		r = classify_menu_texts(["대표", "소금빵", "3,500원"])
		assert r == {"name": "소금빵", "price": 3500, "desc": None}

	def test_new_label_with_desc(self):
		r = classify_menu_texts(["NEW", "소금빵", "3,500원~", "버터향"])
		assert r["name"] == "소금빵"
		assert r["price"] == 3500
		assert r["desc"] == "버터향"

	def test_sigaga_price_null(self):
		r = classify_menu_texts(["시가", "한치"])
		assert r == {"name": "한치", "price": None, "desc": None}

	def test_price_with_parens_and_multi_desc(self):
		r = classify_menu_texts(["BEST", "치즈케이크", "4500원 (1인분)", "바닐라빈", "진한 풍미"])
		assert r["name"] == "치즈케이크"
		assert r["price"] == 4500
		assert r["desc"] == "바닐라빈 · 진한 풍미"

	def test_size_labels_stripped(self):
		# 한 카드에 사이즈 옵션(소·대)이 함께 — 한 메뉴라 사이즈 생략.
		r = classify_menu_texts(["소", "대", "아메리카노", "3,000원"])
		assert r == {"name": "아메리카노", "price": 3000, "desc": None}

	def test_single_size_token_kept_as_variant(self):
		# 사이즈가 1종만 — 별도 사이즈 변종이라 이름에 붙여 구분 유지(충돌·소실 방지).
		assert classify_menu_texts(["아메리카노", "대", "4,000원"])["name"] == "아메리카노 대"
		assert classify_menu_texts(["카페라떼", "L", "5,500원"])["name"] == "카페라떼 L"

	def test_desc_truncated_to_255(self):
		long = "가" * 300
		r = classify_menu_texts(["빵", "5000원", long])
		assert r["desc"] is not None
		assert len(r["desc"]) == 255
		assert r["desc"].endswith("…")

	def test_empty(self):
		assert classify_menu_texts([]) == {"name": "", "price": None, "desc": None}

	def test_only_labels_and_pricelike(self):
		r = classify_menu_texts(["추천", "문의", "한정메뉴"])
		assert r == {"name": "한정메뉴", "price": None, "desc": None}

	def test_sigaga_prefix(self):
		r = classify_menu_texts(["시가/계절", "한치"])
		assert r["name"] == "한치"
		assert r["price"] is None

	def test_price_without_won_symbol(self):
		# 그냥 '3500' 도 매치되어야 함
		r = classify_menu_texts(["메뉴A", "3500"])
		assert r["price"] == 3500

	def test_english_korean_alias_combined(self):
		# 영문 이름 + 한글 이름 짝 → name 으로 합치고 desc 에는 안 들어감
		r = classify_menu_texts(["Pain au Chocolat", "빵 오 쇼콜라", "3,500원"])
		assert r["name"] == "Pain au Chocolat / 빵 오 쇼콜라"
		assert r["price"] == 3500
		assert r["desc"] is None

	def test_korean_english_alias_combined(self):
		# 순서가 한글→영문이어도 동일하게 합치고 source 순서 유지
		r = classify_menu_texts(["빵 오 쇼콜라", "Pain au Chocolat", "3,500원"])
		assert r["name"] == "빵 오 쇼콜라 / Pain au Chocolat"

	def test_alias_with_label_and_desc(self):
		# 라벨 통과 후에도 alias 결합 동작 + 뒤따르는 텍스트는 desc
		r = classify_menu_texts(["NEW", "Salt Bread", "소금빵", "5000원", "고소함"])
		assert r["name"] == "Salt Bread / 소금빵"
		assert r["price"] == 5000
		assert r["desc"] == "고소함"

	def test_no_alias_combine_when_both_korean(self):
		# 한글-한글은 합치지 않음 — 두 번째는 desc
		r = classify_menu_texts(["소금빵", "버터향", "3,500원"])
		assert r["name"] == "소금빵"
		assert r["desc"] == "버터향"

	def test_no_alias_combine_when_too_long(self):
		# 두 번째가 긴 문장(설명형)이면 alias 가 아니라 desc
		long_desc = "겉은 바삭하고 속은 촉촉한 프랑스식 초콜릿 페이스트리"
		r = classify_menu_texts(["Pain au Chocolat", long_desc, "3,500원"])
		assert r["name"] == "Pain au Chocolat"
		assert r["desc"] == long_desc


# ─── classify_store (진단 버킷) ──────────────────────────────────────────────

class TestClassifyStore:
	def test_search_empty(self):
		# 후보 0건 → NO_CAND
		chosen, scored, reason = select_match(_kakao(), [])
		r = classify_store([], chosen, reason, scored)
		assert r["bucket"] == "NO_CAND"
		assert r["num_candidates"] == 0
		assert r["top_cand_name"] is None
		assert r["top_score"] is None

	def test_would_match(self):
		# 지금 돌리면 매칭됨 → MATCH (과거 flaky 실패)
		cands = [_naver()]
		chosen, scored, reason = select_match(_kakao(), cands)
		assert chosen is not None
		r = classify_store(cands, chosen, reason, scored)
		assert r["bucket"] == "MATCH"
		assert r["num_candidates"] == 1
		assert r["top_cand_name"] == "테라로사"
		assert r["name_kind"] == "exact"
		assert r["road_match"] is True

	def test_gate_reject(self):
		# 후보는 떴으나 게이트 거부 (120m) → MISMATCH, reason 보존
		cands = [_naver(y=37.50108, address="서울 강남구 봉은사로 50")]
		chosen, scored, reason = select_match(_kakao(), cands)
		assert chosen is None
		r = classify_store(cands, chosen, reason, scored)
		assert r["bucket"] == "MISMATCH"
		assert r["num_candidates"] == 1
		assert r["reason"] == reason
		assert r["top_cand_name"] == "테라로사"
		assert r["distance_m"] is not None
		assert r["distance_m"] > 100

	def test_closed_candidate(self):
		cands = [_naver(name="테라로사 폐업")]
		chosen, scored, reason = select_match(_kakao(), cands)
		assert chosen is None
		r = classify_store(cands, chosen, reason, scored)
		assert r["bucket"] == "CLOSED"
