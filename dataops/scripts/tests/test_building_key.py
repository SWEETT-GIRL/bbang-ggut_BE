"""인허가 연결용 건물 키 — 시군구·부번까지 본다 (네트워크·DB 없음)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from localdata_match import BuildingKey, building_key, road_key, same_city  # noqa: E402


class BuildingKeyTest(unittest.TestCase):
    def test_시도_시군구_도로명_본번_부번을_읽는다(self):
        self.assertEqual(building_key("인천광역시 연수구 해돋이로171번길 9-4 121호 (송도동, 아리스타)"),
                         BuildingKey("인천", "연수구", "해돋이로171번길", "9", "4"))
        self.assertEqual(building_key("서울 노원구 상계로1길 14-29 2층 201호"),
                         BuildingKey("서울", "노원구", "상계로1길", "14", "29"))

    def test_부번_앞뒤_공백도_부번으로_읽는다(self):
        self.assertEqual(building_key("경상남도 창원시 진해구 덕산로46번길 31 -1, 1층").sub, "1")
        self.assertEqual(building_key("경상남도 창원시 진해구 덕산로46번길 31-1, 1층").index(),
                         building_key("경상남도 창원시 진해구 덕산로46번길 31 -1, 1층").index())

    def test_부번이_다르면_다른_건물이다(self):
        # 느슨한 키는 같은 건물로 모았다
        self.assertEqual(road_key("서울 노원구 상계로1길 14-29"), road_key("서울 노원구 상계로1길 14-3"))
        self.assertNotEqual(building_key("서울 노원구 상계로1길 14-29").index(),
                            building_key("서울 노원구 상계로1길 14-3").index())
        self.assertNotEqual(building_key("서울 노원구 상계로1길 14").index(),
                            building_key("서울 노원구 상계로1길 14-3").index())

    def test_다른_시의_같은_도로명_본번은_다른_건물이다(self):
        a, b = building_key("경기도 수원시 팔달구 중앙로 12"), building_key("경기도 안양시 만안구 중앙로 12")
        self.assertEqual(road_key("경기도 수원시 팔달구 중앙로 12"), road_key("경기도 안양시 만안구 중앙로 12"))
        self.assertEqual(a.index(), b.index())   # 색인은 같아도
        self.assertFalse(same_city(a, b))        # 시군구에서 갈린다
        self.assertFalse(same_city(building_key("부산 중구 중앙대로 10"), building_key("대구 중구 중앙대로 10")))

    def test_시_아래_일반구_표기는_달라도_같은_시다(self):
        a = building_key("경기도 수원시 영통구 중부대로 274")
        b = building_key("경기 수원시 중부대로 274")
        self.assertTrue(same_city(a, b))
        self.assertEqual(a.index(), b.index())

    def test_시도_시군구가_빠진_주소는_비워_둔다(self):
        # 검색 지역으로 채우지 않는다 — 이웃 구·이웃 시도 검색에서 나온 가게가 많았다
        self.assertEqual(building_key("정실동길 26 1층"), BuildingKey(None, None, "정실동길", "26", ""))

    def test_전남광주통합특별시는_시군구로_갈라_옛_이름과_같다(self):
        self.assertEqual(building_key("전남광주통합특별시 광산구 장신로 98").sido, "광주")
        self.assertEqual(building_key("전남광주 순천시 중앙로 1").sido, "전남")
        self.assertTrue(same_city(building_key("광주광역시 광산구 장신로 98"), building_key("전남광주 광산구 장신로 98")))

    def test_인천_개편_옛_구와_새_구는_같은_시군구로_본다(self):
        self.assertTrue(same_city(building_key("인천광역시 서구 서곶로 120"), building_key("인천 서해구 서곶로 120")))
        self.assertTrue(same_city(building_key("인천광역시 동구 송림로 1"), building_key("인천 제물포구 송림로 1")))
        self.assertFalse(same_city(building_key("인천광역시 동구 송림로 1"), building_key("인천 영종구 송림로 1")))

    def test_세종은_시군구가_없다(self):
        self.assertEqual(building_key("세종특별자치시 한누리대로 2130"), BuildingKey("세종", "", "한누리대로", "2130", ""))


def lic(addr, name, mgmt="m"):
    from localdata_match import norm_name
    return {"mgmt_no": mgmt, "name": name, "nname": norm_name(name), "addr": addr, "bkey": building_key(addr),
            "licensed": "", "dataset": "rest_cafes"}


def index(*licenses):
    out = {}
    for x in licenses:
        out.setdefault(x["bkey"].index(), []).append(x)
    return out


def by_main(*licenses):
    out = {}
    for x in licenses:
        out.setdefault((x["bkey"].road, x["bkey"].main), []).append(x)
    return out


class FindLicenseTest(unittest.TestCase):
    """역검색 가게 → 인허가 연결 규칙 (naver_sweep_match.find_license)"""

    def setUp(self):
        import naver_sweep_match as m
        from localdata_match import norm_name
        self.find = lambda name, addr, idx, main=None: m.find_license(norm_name(name), building_key(addr), idx, main)

    def test_도의_다른_시군_같은_도로명_번호에는_붙이지_않는다(self):
        idx = index(lic("강원특별자치도 화천군 화천읍 중앙로 20", "뚜레쥬르 경남하동점"))
        found, _, how = self.find("뚜레쥬르 경남하동점", "경상남도 하동군 하동읍 중앙로 20", idx)
        self.assertIsNone(found)
        idx = index(lic("충청남도 논산시 중앙로 223", "빵집"), lic("충청남도 천안시 동남구 중앙로 223", "빵집", "m2"))
        found, _, how = self.find("빵집", "충청남도 서산시 중앙로 223", idx)
        self.assertIsNone(found)
        self.assertEqual(how, "NO_LICENSE_IN_CITY")

    def test_광역시는_구가_달라도_같은_건물로_본다(self):
        # 서울역 — 네이버 중구, 인허가 용산구
        idx = index(lic("서울특별시 용산구 한강대로 405, 서울역 3층", "뚜레쥬르 서울역사점"))
        found, _, how = self.find("뚜레쥬르 서울역사점", "서울특별시 중구 한강대로 405", idx)
        self.assertEqual((found["mgmt_no"], how), ("m", "UNIQUE_IN_SIDO"))

    def test_같은_시군구가_있으면_그쪽을_먼저_쓴다(self):
        idx = index(lic("서울특별시 송파구 새말로 97", "새말빵집", "songpa"), lic("서울특별시 구로구 새말로 97", "새말빵집", "guro"))
        found, _, how = self.find("새말빵집", "서울특별시 구로구 새말로 97", idx)
        self.assertEqual((found["mgmt_no"], how), ("guro", "SAME_CITY"))

    def test_시도가_없는_주소는_이름_맞는_인허가가_한_시군구에만_있을_때_붙인다(self):
        idx = index(lic("경기도 시흥시 오이도어시장로 5, 1층", "파리바게뜨 오이도등대점"))
        found, _, how = self.find("파리바게뜨 오이도등대점", "오이도어시장로 5 1층", idx)
        self.assertEqual(how, "UNIQUE_NO_SIDO")
        idx = index(lic("충청남도 논산시 중앙로 223", "빵집"), lic("충청남도 천안시 동남구 중앙로 223", "빵집", "m2"))
        found, _, how = self.find("빵집", "중앙로 223", idx)
        self.assertIsNone(found)
        self.assertEqual(how, "AMBIGUOUS_CITY")

    def test_개편_옛_구_인허가는_새_구_주소와_같은_시군구다(self):
        idx = index(lic("인천광역시 서구 서곶로 120, 지하3층", "당신의케이크"))
        found, _, how = self.find("당신의케이크", "인천 서해구 서곶로 120 지하3층", idx)
        self.assertEqual(how, "SAME_CITY")

    def test_부번만_다르면_상호가_완전히_같을_때만_붙인다(self):
        same = lic("강원특별자치도 강릉시 해안로 32, 1층 101호", "곤트란쉐리에강릉점")
        found, _, how = self.find("곤트란쉐리에 강릉점", "강원특별자치도 강릉시 해안로 32-8 1층", index(same), by_main(same))
        self.assertEqual(how, "SAME_MAIN_EXACT_NAME")
        other = lic("경상북도 경주시 보문로 424-11 (신평동)", "신라명가한식당")
        found, _, how = self.find("신라명가", "경상북도 경주시 보문로 424-33", index(other), by_main(other))
        self.assertIsNone(found)
        # 다른 시군의 같은 본번은 상호가 같아도 붙이지 않는다
        far = lic("충청남도 논산시 중앙로 223-1", "빵집")
        found, _, _ = self.find("빵집", "충청남도 서산시 중앙로 223", index(far), by_main(far))
        self.assertIsNone(found)


if __name__ == "__main__":
    unittest.main()
