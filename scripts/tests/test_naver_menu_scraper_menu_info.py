"""menuInfo 파싱 — 가격부가 이름에 붙지 않는지."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from naver_match import guess_category  # noqa: E402
from naver_menu_scraper import parse_menu_info, split_menu_info_item  # noqa: E402


def test_숫자_가격은_이름과_분리된다():
	assert split_menu_info_item("소금빵 3,500") == ("소금빵", 3500)
	assert split_menu_info_item("휘낭시에 3500원") == ("휘낭시에", 3500)


def test_변동가격_범위_시가는_이름에서_떼고_가격은_없다():
	assert split_menu_info_item("아카스키 복숭아 산도 변동가격(업주문의)") == ("아카스키 복숭아 산도", None)
	assert split_menu_info_item("초콜라또 칼도 4,300~6,200") == ("초콜라또 칼도", None)
	assert split_menu_info_item("2호 케이크 62,000~82,000원") == ("2호 케이크", None)
	assert split_menu_info_item("한치 시가") == ("한치", None)


def test_가격부가_없으면_그대로_이름이다():
	assert split_menu_info_item("플레인소금빵2") == ("플레인소금빵2", None)
	assert split_menu_info_item("오늘의 후르츠산도") == ("오늘의 후르츠산도", None)


def test_parse_menu_info_는_깨끗한_이름을_낸다():
	menus = parse_menu_info("아카스키 복숭아 산도 변동가격(업주문의) | 소금빵 3,500 | 초콜라또 칼도 4,300~6,200")
	assert [m["name"] for m in menus] == ["아카스키 복숭아 산도", "소금빵", "초콜라또 칼도"]
	assert [m["price"] for m in menus] == [None, 3500, None]
	assert [m["category"] for m in menus] == [None, "소금빵", None]


def test_룰에_없는_이름은_카테고리를_비운다():
	assert guess_category("먹물방망이") is None
	assert guess_category("명란 바게트") == "바게트"
