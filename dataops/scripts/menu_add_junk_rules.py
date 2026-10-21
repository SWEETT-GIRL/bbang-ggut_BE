# -*- coding: utf-8 -*-
"""PENDING menu.add 정크 판정 규칙 (2026-09-06, 재동기화 2단계 게이트) — 8월 게이트(음료·0원·옵션·기타)와 같은 축.

judge(name, price) → (rule, review_note) 또는 None(승인). reconcile 의 BeverageFilter 를 이미 통과한 잔여분이 대상이라
음료 판정은 강한 음료어(라떼·아메리카노·에이드·블렌디드·HOT/ICE 표기 …)만 쓰고, 약한 단어(우유·얼그레이·차·티)는 이름 끝에서만 본다.
v1→v3 에서 표본으로 걸러낸 오탐: 우유크림 카스테라·얼그레이 생크림(약한 음료어), 비스코티(티 끝), 예약필수 홀케이크(예약), 썸머그린 푸딩(머그),
답례품(스티커 무료), 풍선케이크(풍선), [에그마요 소금빵] 신메뉴(대괄호 제거 뒤 비메뉴 오판).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from menu_classifier import classify  # noqa: E402  (2026-09-19 menu_reclassify 에서 옮겨 옴 — 반환이 (종류, 끝 위치, 걸린 단어))

ROUND = re.compile(r"\([^()]*\)")
ANY_PAREN = re.compile(r"\([^()]*\)|\[[^\[\]]*\]")
STRONG_BEV = ["라떼", "라테", "아메리카노", "커피", "에스프레소", "콜드브루", "카푸치노", "마끼아또", "마키아토", "마끼야또", "마키아또", "플랫화이트",
              "아인슈페너", "아인슈패너", "아이스초코", "릴렉서",
              "에이드", "주스", "쥬스", "스무디", "쉐이크", "셰이크", "프라페", "프라푸치노", "밀크티", "아이스티", "뱅쇼", "코코아", "핫초코",
              "미숫가루", "아샷추", "맥주", "와인", "하이볼", "칵테일", "사이다", "탄산수", "레모네이드", "블렌디드", "핸드드립", "더치", "티라떼",
              "콤부차", "버블티", "식혜", "수정과", "쌍화탕", "원액", "보틀", "슬러시"]
STRONG_BEV_EN = re.compile(r"(?<![a-z])(tea|coffee|latte|americano|ade|smoothie|juice|shake|frappe|espresso|brew|mocha|cappuccino|milk\s?tea)(?![a-z])", re.I)
WEAK_BEV_END = ["차", "티", "우유", "밀크", "얼그레이", "페퍼민트", "루이보스", "카모마일", "캐모마일", "히비스커스", "쟈스민", "자스민", "말차",
                "핫", "아이스", "hot", "ice", "h/i", "i/h", "벤티", "그란데", "톨"]
WEAK_END_EXCEPT = ["비스코티", "스파게티", "파티", "파나코티", "판나코티", "로스티", "치아바티", "티그레", "스콘티", "바게티", "페이스트리", "페스츄리", "패스츄리"]
SIZE_MARK = re.compile(r"\((hot|ice|h/i|i/h|핫|아이스|벤티|그란데|톨|xl|tall|venti|grande)\)|(hot|ice)$|\b(hot|ice)\b", re.I)
# 온도 전용 표기 — 음식에는 붙지 않는다. 분류기가 이름 안의 글자로 빵 종류를 잘못 잡아도(브라운"슈"가 → 슈) 음료다
# (2026-09-27: '브라운슈가보바밀크 ICE ONLY' · '아인슈패너(ICE)' 가 이 조건 없이 통과했다)
TEMP_ONLY = re.compile(r"\((ice|hot|아이스|핫)\)|\b(ice|hot)\s*only\b|\bonly\s*(ice|hot)\b|\((only\s*(ice|hot))\)", re.I)
OPTION_RE = re.compile(r"(추가|옵션|사이즈업|샷추가|휘핑|택배비|택배|배송비|배송료|배송|퀵비|포장비|쇼핑백|종이백|종이가방|봉투|아이스팩|보냉백|보냉팩|보냉|캐리어|용기|박스만|초만|접시)$"
                       r"|^(추가|옵션|택배|배송|퀵|포장|사이즈|샷|휘핑|시럽|토핑추가|초|쇼핑백|종이백|보냉)(\s|$|\()"
                       r"|(변경시|추가시|추가금|추가요금|배달비)")
NONMENU_NAMES = {"음식·음료", "음식", "음료", "음료류", "커피류", "내부", "외부", "외관", "메뉴판", "매장", "매장사진", "인테리어", "전체", "기타", "메뉴",
                 "사진", "상세", "대표메뉴", "세트메뉴", "추천메뉴", "신메뉴", "시그니처", "베스트", "가격표", "안내", "공지", "이벤트", "할인", "서비스", "기타메뉴"}
INQUIRY_RE = re.compile(r"(문의|예약금|상담|클래스|원데이|수업|강좌|레슨|체험|견적|대관|촬영|예약석|견학)")
INQUIRY_ONLY = re.compile(r"^(예약|예약주문|주문예약|예약상품|사전예약|단체예약|단체주문|주문제작|맞춤주문|픽업)$")
GOODS_RE = re.compile(r"(상품권|기프티콘|기프트카드|모바일쿠폰|텀블러|머그컵|머그잔|에코백|캔들|비누|굿즈|앞치마|티셔츠|키링|디퓨저|모양초|케익초|케이크초|숫자초|생일초|초&데코|파티소품|파티용품|고깔|폭죽)")
MEAL_RE = re.compile(r"(파스타|스파게티|리조또|리소토|떡볶이|김밥|라면|국밥|찌개|덮밥|볶음밥|비빔밥|우동|짜장|짬뽕|탕수육|족발|보쌈|삼겹|갈비|초밥|스시|카레라이스|돈까스|돈가스|함박|오므라이스|쌀국수|냉면|칼국수|수제비|필라프|포케|주먹밥|분식세트|다이닝)")
LETTERS_RE = re.compile(r"[가-힣A-Za-z]")


def is_beverage(n, kind_end):
    low = n.lower()
    best = -1
    for w in STRONG_BEV:
        i = low.rfind(w)
        if i >= 0:
            best = max(best, i + len(w))
    for m in STRONG_BEV_EN.finditer(low):
        best = max(best, m.end())
    core = ANY_PAREN.sub(" ", low).strip()
    if not any(core.endswith(x) for x in WEAK_END_EXCEPT):
        for w in WEAK_BEV_END:
            if core.endswith(w):
                best = max(best, len(core))
    if kind_end < 0 and SIZE_MARK.search(low):
        return True
    if TEMP_ONLY.search(low):
        return True
    return best >= 0 and best >= kind_end


def judge(name, price):
    n = (name or "").strip()
    if not LETTERS_RE.search(n) or len(n) <= 1:
        return "nonmenu", "재동기화 검수 — 이름 아님"
    core_round = ROUND.sub(" ", n).replace(" ", "")
    if core_round in NONMENU_NAMES:
        return "nonmenu", "재동기화 검수 — 비메뉴 이름(사진 탭·분류명)"
    if price == 0:
        return "zero", "재동기화 검수 — 0원 비매품/광고"
    kind, kind_end, _ = classify(n)
    if is_beverage(n, kind_end):
        return "beverage", "재동기화 검수 — 음료"
    if OPTION_RE.search(n) or OPTION_RE.search(ANY_PAREN.sub(" ", n).strip()):
        return "option", "재동기화 검수 — 옵션/부자재"
    if INQUIRY_RE.search(n) or INQUIRY_ONLY.match(ANY_PAREN.sub("", n).replace(" ", "")):
        return "inquiry", "재동기화 검수 — 문의·예약·클래스"
    if GOODS_RE.search(n):
        return "goods", "재동기화 검수 — 굿즈·상품권"
    if MEAL_RE.search(n) and kind is None:
        return "meal", "재동기화 검수 — 식사류(빵 아님)"
    return None
