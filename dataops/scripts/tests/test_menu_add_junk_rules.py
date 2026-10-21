"""메뉴 추가 정크 판정 — 음료가 새지 않고, 빵·디저트를 음료로 막지 않는다 (네트워크·DB 없음)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from menu_add_junk_rules import judge  # noqa: E402


def decision(name):
    j = judge(name, 5000)
    return j[0] if j else "approve"


class BeverageTest(unittest.TestCase):
    """2026-09-27 — 9/22 이후 등록분 30,273개 승인분에서 통과한 음료"""

    def test_온도_전용_표기는_빵_종류가_잘못_잡혀도_음료다(self):
        for name in ("브라운슈가보바밀크 ICE ONLY", "아인슈패너(ICE)", "바닐라 아포가토(only ice)", "레몬티(HOT)"):
            self.assertEqual(decision(name), "beverage", name)

    def test_빠졌던_음료_어휘(self):
        for name in ("카라멜 마키아또", "아이스초코", "캐모마일 릴렉서", "진저티청송사과릴렉서", "아인슈페너"):
            self.assertEqual(decision(name), "beverage", name)

    def test_기존_음료_판정은_그대로다(self):
        for name in ("딸기라떼", "아메리카노", "자몽에이드"):
            self.assertEqual(decision(name), "beverage", name)


class NotBeverageTest(unittest.TestCase):
    """음료 어휘가 수식어인 빵·디저트 — 음료로 막으면 메뉴가 빠진다"""

    def test_음료_어휘가_들어간_빵_디저트(self):
        for name in ("우유크림 카스테라", "얼그레이 생크림 케이크", "비스코티", "통밀비스코티", "흑당 버블 타르트",
                     "버블덕 mini cake", "팝핑보바 망고 요거트빙수", "말차라떼 크림빵", "커피번", "플레인 그릭요거트(300g)",
                     "리치팝핑보바(중)", "딸기 팝핑보바(50g)"):   # 요거트 아이스크림 토핑 — 음료 아님
            self.assertEqual(decision(name), "approve", name)


if __name__ == "__main__":
    unittest.main()
