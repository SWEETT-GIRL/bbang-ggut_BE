"""naver_match 단위 테스트.

실행: cd scripts && python -m pytest tests/ -v
"""

import sys
from pathlib import Path

# scripts/ 를 import path 에 추가 (pytest 가 scripts/tests 에서 돌 때)
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from naver_match import (  # noqa: E402
	classify_menu_texts,
	extract_road_and_number,
	is_chain_like,
	is_closed,
	name_match_kind,
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


# ─── is_closed ────────────────────────────────────────────────────────────────

class TestClosed:
	def test_closed_in_category(self):
		assert is_closed({"name": "X", "category": "베이커리 폐업"})

	def test_closed_in_name(self):
		assert is_closed({"name": "테라로사 (폐업)", "category": ""})

	def test_not_closed(self):
		assert not is_closed({"name": "테라로사", "category": "베이커리"})


# ─── score_candidate / select_match ──────────────────────────────────────────

def _kakao(name="테라로사", lat=37.5, lng=127.0, address="서울 강남구 테헤란로 100"):
	return {"name": name, "lat": lat, "lng": lng, "address": address}


def _naver(name="테라로사", y=37.5, x=127.0, address="서울 강남구 테헤란로 100", category="베이커리"):
	return {"name": name, "y": str(y), "x": str(x), "address": address, "category": category, "id": "123"}


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

	def test_chain_beyond_30m_rejected(self):
		k = _kakao(name="스타벅스 강남점")
		# 50m 떨어짐
		n = _naver(name="스타벅스 강남점", y=37.50045)
		chosen, _, why = select_match(k, [n])
		assert chosen is None
		assert "체인" in why

	def test_non_chain_60m_accepted(self):
		# 60m 이내 — 이름 exact, road 다름
		n = _naver(y=37.50054, address="서울 강남구 봉은사로 50")
		chosen, _, _ = select_match(_kakao(), [n])
		assert chosen is not None

	def test_non_chain_75m_with_exact_and_district_accepted(self):
		# 75m — 80m 완화 룰 (exact + 동 일치)
		k = _kakao(address="서울 강남구 역삼동 테헤란로 100")
		n = _naver(y=37.50068, address="서울 강남구 역삼동 봉은사로 50")
		chosen, _, why = select_match(k, [n])
		assert chosen is not None, why

	def test_non_chain_120m_rejected(self):
		n = _naver(y=37.50108)
		chosen, _, why = select_match(_kakao(), [n])
		assert chosen is None
		assert "거리" in why

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
