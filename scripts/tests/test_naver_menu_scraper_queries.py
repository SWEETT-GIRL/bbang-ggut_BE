"""네이버 검색 쿼리 생성 단위 테스트."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from naver_menu_scraper import build_naver_search_query_groups  # noqa: E402


def _queries(name: str, address: str) -> tuple[list[str], list[str]]:
	return build_naver_search_query_groups(name, address)


def test_branch_suffix_relaxed_only_in_fallback():
	primary, fallback = _queries("쁘띠크로아상 신내우디안점", "서울 중랑구 신내역로1길 142")

	assert "쁘띠크로아상 신내우디안점 중랑구" in primary
	assert "쁘띠크로아상 중랑구" in fallback
	assert "쁘띠크로아상 신내역로1길 142" in fallback
	assert "쁘띠크로아상" in fallback


def test_headquarters_suffix_is_preserved_for_search():
	primary, fallback = _queries("빈스로드 본사", "서울 중랑구 봉화산로 111")

	assert "빈스로드 본사 중랑구" in primary
	assert "빈스로드 본사 봉화산로 111" in primary
	assert "빈스로드 중랑구" not in primary
	assert "빈스로드 중랑구" not in fallback


def test_department_store_suffix_relaxed_after_branch_suffix():
	primary, fallback = _queries("드림베티 롯데백화점 노원점", "서울 노원구 동일로 1414")

	assert "드림베티 롯데백화점 노원점 노원구" in primary
	assert "드림베티 롯데백화점 노원구" in fallback
	assert "드림베티 노원구" in fallback
	assert "드림베티 동일로 1414" in fallback
