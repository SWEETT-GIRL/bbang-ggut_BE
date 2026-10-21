"""빵집 관문 G1 — 메뉴 증거의 경계 (네트워크·DB 없음)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bakery_gate as g  # noqa: E402

CAFE = "카페,디저트,카페"


class DrinkNameTest(unittest.TestCase):
    """2026-09-26 카페 잎 표본 — 음료 이름 한 줄이 제품 빵 1개·몫 100% 로 자동 통과를 만들었다"""

    def test_빵_단어가_붙은_음료는_제품_빵이_아니다(self):
        for name in ("도넛크림라떼", "파사노 브륄레 라떼", "쿠키치즈폼프레치노", "도넛모카라떼(only hot)",
                     "쿠키 쉐이크 ICE", "마카롱 스무디", "케이크 프라페"):
            product, _, food = g.bread_evidence([name])
            self.assertEqual(product, [], name)
            self.assertEqual(food, 0, name)

    def test_음료_이름_하나로는_카페가_통과하지_않는다(self):
        for name in ("도넛크림라떼", "파사노 브륄레 라떼", "쿠키치즈폼프레치노"):
            self.assertNotEqual(g.gate(CAFE, "카페", [name])[0], "PASS", name)

    def test_빵이_머리인_메뉴는_그대로_빵이다(self):
        # 음료 어휘가 앞에 붙은 빵 — 끝말을 보므로 빠지지 않는다
        for name in ("말차 쿠키", "우유 식빵", "커피번", "모카번 소금빵", "밀크티 스콘", "라떼 마들렌", "에그타르트"):
            product, _, food = g.bread_evidence([name])
            self.assertEqual(product, [name], name)
            self.assertEqual(food, 1, name)
        self.assertTrue(g.is_drink_name("아이스 아메리카노"))
        self.assertFalse(g.is_drink_name("커피번"))

    def test_빵과_음료를_묶은_세트는_빵이_들어_있다(self):
        for name in ("소금빵+아메리카노", "스콘&라떼", "(모닝세트) 크로플 SET"):
            product, _, _ = g.bread_evidence([name])
            self.assertEqual(product, [name], name)


class DuplicateMenuTest(unittest.TestCase):
    def test_같은_메뉴는_한_번만_센다(self):
        product, _, food = g.bread_evidence(["소금빵", "소금빵", "소금 빵", "소금빵"])
        self.assertEqual((len(product), food), (1, 1))

    def test_괄호_안이_다른_메뉴는_다른_제품이다(self):
        # 맛·구성이 괄호에 있다 — 묶으면 음식 수가 줄어 샌드위치 가게로 잘못 잡혔다(표본 아이보리타이거)
        _, _, food = g.bread_evidence(["하겐다즈 샌드위치 (리치카라멜)", "하겐다즈 샌드위치 (스트로베리)",
                                       "하겐다즈 미니컵 (바닐라)", "하겐다즈 미니컵 (그린티)"])
        self.assertEqual(food, 4)

    def test_샌드위치_가게_판정도_같은_메뉴는_한_번만_센다(self):
        menus = ["햄 샌드위치", "햄 샌드위치", "파스타", "리조또", "샐러드"]
        _, _, food = g.bread_evidence(menus)
        self.assertFalse(g.is_sandwich_shop("카페", menus, food))

    def test_중복으로_부풀린_메뉴는_몫_조건을_넘지_못한다(self):
        # 같은 빵 세 번 + 음식 7 — 중복을 세면 3/10=30% 로 통과했지만, 한 번만 세면 1/8
        menus = ["소금빵"] * 3 + [f"파스타{i}" for i in range(7)]
        self.assertNotEqual(g.gate(CAFE, "카페", menus)[0], "PASS")


class ExistingBehaviourTest(unittest.TestCase):
    """이번 수정이 기존 판정을 바꾸지 않는 경계"""

    def test_빵집_잎은_메뉴_없이_통과한다(self):
        self.assertEqual(g.gate("카페,디저트,베이커리", "빵집", None)[0], "PASS")

    def test_길거리_간식과_요리는_제품_빵이_아니다(self):
        product, street, _ = g.bread_evidence(["슈크림 붕어빵", "빵빵김밥", "찹쌀꽈배기"])
        self.assertEqual(product, [])
        self.assertEqual(len(street), 2)

    def test_빵_메뉴가_몫을_채운_카페는_통과한다(self):
        menus = ["아메리카노", "카페라떼", "소금빵", "크루아상", "스콘", "휘낭시에"]
        self.assertEqual(g.gate(CAFE, "카페", menus)[0], "PASS")


if __name__ == "__main__":
    unittest.main()
