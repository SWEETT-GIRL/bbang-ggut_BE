"""가게 프로필 초안 배치 — store_signal + 운영 분류 메뉴 × menu_kind_axis 로 축·타입·태그·대표 메뉴를 만들어 로컬 store_profile_draft 에 upsert.

설계 §5.1 "가게 프로필: 메뉴 → 축 평균(분류 메뉴 3개 미만이면 NULL), 네이버 스냅샷 → 태그·대표 메뉴" 의 dataops 초안.
- 축 = 운영 분류 메뉴 축(가중 1+like_count, is_bread 만) 와 리뷰 언급 메뉴 축(언급 수 가중) 의 결합
    · 둘 다 있으면 0.6·메뉴 + 0.4·리뷰 (메뉴가 3개 미만이면 리뷰만, 리뷰도 없으면 NULL)
    · 리뷰 언급 라벨 → 종류: 정확 일치 또는 끝단어 일치(긴 이름 우선), 비-빵 종류 제외
- 태그 = scripts/store_tags.py 의 규칙 (8개, 0~1 — 방문자 투표 태그는 수축 lift 의 백분위)
- 대표 메뉴 = recommend 메뉴 이름(정제 후 최대 3) + 리뷰 언급 상위 라벨 로 최대 5 — 음료·빵이 아닌 메뉴·세트 묶음 제외
- 대표 메뉴 종류(signature_kinds) = 대표 메뉴를 빵 종류로 푼 것 — BE 가 빵 기록 없는 회원의 "자주 찾는 빵 종류"를 추정하는 재료
- 타입 = 축 부호 조합 4글자 코드(BE TasteAxis.typeCode 와 같은 글자 — 맛 S/P · 식감 F/C · 속 R/L · 신상 O/N), |축| < 0.15 는 라벨에 '?' 표시
- 리뷰 수·별점 인원·별점·소개글 = store_signal 그대로 (review_fields, 별점 1 미만은 결측)

사용
    python scripts/store_profile_batch.py --env ../bbang-ggut_dataops_BE-localdata/.env              # 테스트셋 30곳
    python scripts/store_profile_batch.py --env ... --all                                            # store_signal 전체
"""
import argparse
import csv
import datetime as dt
import io
import json
import math
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from menu_classifier import GENERIC_KINDS, classify, doubt  # noqa: E402
from store_tags import TAGS, local_csv, prod_csv, score as tag_score, vote_scale  # noqa: E402
from kind_evidence import EvidencePolicy, canonical_kind  # noqa: E402
from kind_evidence_store import freeze_context, group_menus, load_store_evidence  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AXES = ("taste", "texture", "filling", "vibe")
POLE_LABEL = {"taste": ("달콤", "담백"), "texture": ("부드", "바삭"), "filling": ("풍부", "심플"), "vibe": ("고전", "신상")}
# 타입 코드 글자 — BE TasteAxis.typeCode() 와 같은 배정(#731). 자리마다 한 축, 여덟 글자가 전부 다르다.
#   1 맛 S달콤/P담백 · 2 식감 F부드/C바삭 · 3 속 R풍부/L심플 · 4 빵의 신상 O고전/N신상
# 2026-09-13 까지는 숫자 1~16(code = 1 + 8·맛 + 4·식감 + 2·속 + 1·신상)이었다. 표현만 바뀌었고 규칙은 같다 —
# n-1 의 비트가 곧 네 글자라 type_code_of_number 로 옮긴다(로컬 menu_kind_axis.bbti_type 은 아직 숫자).
POLE_CODE = {"taste": ("S", "P"), "texture": ("F", "C"), "filling": ("R", "L"), "vibe": ("O", "N")}
TYPE_NAMES = {"SFRO": "따뜻한 포옹", "SFRN": "화려한 몽상가", "SFLO": "조용한 감성파", "SFLN": "가벼운 구름",
              "SCRO": "완벽주의 미식가", "SCRN": "트렌드세터", "SCLO": "우아한 고집", "SCLN": "실험적 예술가",
              "PFRO": "든든한 현실주의자", "PFRN": "경계를 허무는 자", "PFLO": "수수한 일상", "PFLN": "미니멀리스트",
              "PCRO": "날카로운 감각", "PCRN": "세련된 반전", "PCLO": "고독한 철학자", "PCLN": "뉴웨이브 미니멀"}
TYPE_CODES = list(TYPE_NAMES)   # 옛 번호 순서(1~16) — 번호 n 의 코드는 TYPE_CODES[n-1]
NEUTRAL = 0.15
MIN_MENUS = 3
MIN_REVIEW_LABELS, MIN_REVIEW_MENTIONS = 3, 20   # 리뷰 언급 메뉴만으로 축을 만들 때 최소 조건 (라벨 1개로 타입이 서던 오탐 방지)
BAKERY_CATEGORIES = {"베이커리", "빵류제조", "제과,제빵", "제과점", "베이커리카페", "케이크전문", "베이글", "도넛", "샌드위치", "브런치카페", "카페,디저트",
                     "디저트카페", "디저트", "크레페", "와플", "타르트", "마카롱", "쿠키", "스콘", "구움과자"}
# 빵집이 아닌데 빵을 곁들여 파는 곳을 후보 풀에서 내린다 (삭제가 아니라 점수로).
# 떡·한과·공방을 2026-09-10 에 더했다 — 답례떡·이바지·주문제작 공방은 '맛집'이 아니라 제작소다.
# 카테고리 전수를 확인해 '떡' 이 걸리는 것은 떡,한과 1,583 · 떡류제조 277 · 떡볶이 15 · 전,빈대떡 4 뿐이고
# BAKERY_CATEGORIES 와 겹치지 않는다. 떡카페·호떡은 이미 '카페'·'호떡' 으로 걸리고 있었다.
NON_BAKERY_HINTS = ("분식", "치킨", "한식", "중식", "일식", "양식", "이탈리아", "피자", "햄버거", "호떡",
                    "아이스크림", "젤라또", "젤라토", "빙수", "술집", "주점", "카페",
                    "떡", "한과", "공방")
INGREDIENT_LABELS = {"치즈", "크림치즈", "과일", "딸기", "초코", "초콜릿", "버터", "크림", "생크림", "바닐라", "말차", "흑임자", "바질", "옥수수", "고구마",
                     "단호박", "견과", "아몬드", "피스타치오", "레몬", "망고", "블루베리", "무화과", "밤", "팥", "누룽지", "연어", "닭가슴살", "계란", "베이컨", "햄"}
DRINK_WORDS = ("라떼", "라테", "아메리카노", "커피", "에이드", "주스", "쥬스", "스무디", "밀크티", "아이스티", "드링크", "콜드브루", "에스프레소", "아인슈페너", "차", "티", "우유", "음료", "모히또", "프라페",
               "카푸치노", "모카", "마끼아또", "마키아토", "플랫화이트", "쉐이크", "셰이크", "핫초코", "코코아", "미숫가루",
               "뱅쇼", "하이볼", "맥주", "와인", "칵테일", "막걸리", "사이다", "콜라", "소다",
               "latte", "coffee", "americano", "tea", "ade")
# 대표 메뉴 정제 (dataops #11, 2026-09-19) — 추천 이유 문장(BE #662)이 이 이름을 그대로 읽는다. "소금빵 3개 | 쿠키 | 양갱 | 빙수"
# 처럼 수량·세트 표기와 빵이 아닌 메뉴가 섞여 모델이 "소금빵 3개와 쿠키를…" 이라고 썼다.
# 음료·빵이 아닌지는 이름의 끝말로 본다 — 한국어 메뉴명은 끝말이 그 메뉴가 무엇인지다("피자 식빵"은 식빵, "요거트 크림번"은 크림번).
# 09-18 까지는 음료 단어가 이름 어디에든 있으면 뺐다 — "솔티드 카라멜 휘낭시에"(티) · "말차 쿠키"(차) · "우유 식빵"(우유) ·
# "커피번"(커피)이 빠지고 있었다(빵집 recommend 이름 중 음료 단어가 끝이 아닌 것: 티 2,144 · 차 1,707 · 우유 938 · 커피 576).
# 떡은 거르지 않는다 — 버터떡처럼 빵집이 굽는 떡이 대표 메뉴로 많다. 샌드위치도 빵집 메뉴라 둔다.
NON_BREAD_HEADS = ("빙수", "아이스크림", "젤라또", "젤라토", "소프트콘", "파르페", "요거트", "식혜", "수정과", "양갱",
                   "파스타", "스파게티", "샐러드", "피자", "김밥", "수프", "스프", "리조또", "덮밥", "도시락", "라면", "떡볶이", "주먹밥")
NOT_MENU_WORDS = ("선물", "모둠", "기프트", "답례", "할인", "이벤트", "주문", "예약", "안내", "쿠폰", "증정", "문의")   # 묶음 상품·공지
MENU_ENUM = re.compile(r"^\s*\d+\s*[.)]\s*")                                  # "2. 파네크림파스타"
MENU_BRACKETS = re.compile(r"[\[(（【<〈][^\])）】>〉]*[\])）】>〉]")            # "(3개)" "[NEW]" "( 시그니처 )"
MENU_SYMBOLS = re.compile(r"[^0-9A-Za-z가-힣぀-ヿ一-鿿\s&+/%\-]")  # 이모지·장식 기호(★ ~ * ! " 등). 한자·가나는 둔다
MENU_QUANTITY = re.compile(r"\d+(?:[.,]\d+)?\s*(?:개입|개|입|구|종|pcs|pc|ea|p|조각|박스|box|팩|봉|알|인치|인분|인|호|cm|ml|kg|g|l|리터|원)", re.I)
MENU_SET_TAIL = re.compile(r"\s*(?:세트|set|박스|box|패키지)$", re.I)
MENU_NOISE_WORDS = {"new", "best", "hot", "신메뉴", "시그니처", "시그니쳐", "대표", "대표메뉴", "인기", "추천", "한정", "시즌", "베스트"}
# 리뷰 언급 라벨은 리뷰 글에서 뽑은 말이라 재료·음료·식사가 섞인다(빵집 라벨 중 안심 3,750 · 잡채 3,700 · 와인 3,031 · 새우 2,072 …).
# 점주가 고른 recommend 이름과 달리 "빵집 메뉴다"는 근거가 있을 때만 받는다: 빵 종류(menu_kind_axis)로 풀리거나 아래 끝말로 끝난다.
# 끝말은 종류 목록의 표기 변형(크로와상·도너츠·프레즐·만주)과 종류 목록에 없는 빵집 메뉴(○○빵·구움과자·꽈배기·슈톨렌…)다.
BAKERY_LABEL_HEADS = ("빵", "브레드", "크로와상", "크로아상", "도너츠", "도나스", "과자", "꽈배기", "슈톨렌", "뺑오쇼콜라", "프레즐",
                      "카롱", "만주", "파니니", "잠봉뵈르", "빨미까레")
GENERIC_LABELS = {"빵", "브레드", "디저트", "베이커리", "과자"}   # 무엇인지 말해 주지 않는 말
DRINK_LABELS = {"커피", "아메리카노", "라떼", "카페라떼", "바닐라라떼", "에이드", "밀크티", "스무디", "우유", "쥬스", "주스", "녹차", "얼그레이",
                "자몽에이드", "딸기라떼", "크림라떼", "말차라떼", "카푸치노", "콜드브루", "차", "티", "음료", "레몬에이드", "청포도에이드"}


def load_axis_table():
    rows = local_csv("SELECT kind, axis_taste, axis_texture, axis_filling, axis_vibe, is_bread FROM menu_kind_axis WHERE axis_taste IS NOT NULL;")
    table = {r["kind"]: {a: int(r[f"axis_{a}"]) for a in AXES} for r in rows if r["is_bread"] in ("t", "true", "True")}
    return table, sorted(table, key=len, reverse=True)


def kind_of_label(label, kinds_by_len):
    """One canonical classifier for review labels and representative menu names."""
    return canonical_kind(label, allowed_kinds=kinds_by_len)


def weighted(vectors):
    total = sum(w for _, w in vectors)
    if total <= 0:
        return None
    return {a: sum(v[a] * w for v, w in vectors) / total for a in AXES}


def combine(menu_axis, review_axis):
    if menu_axis and review_axis:
        return {a: 0.6 * menu_axis[a] + 0.4 * review_axis[a] for a in AXES}
    return menu_axis or review_axis


# ④분위기 축은 '가게 분위기'가 아니라 '신상빵 vs 고전 빵'이다 (2026-09-10 확인).
# 09-09 에 리뷰 votedKeyword(interior_cool·photo_good·concept_unique ↔ cozy·atmosphere_calm)로
# 갈아끼웠다가 되돌렸다. 그건 가게 인테리어·분위기라 층이 다르다 — 축은 빵의 성질이어야 한다
# (맛·식감·속이 전부 빵의 성질인데 넷째만 가게 성질이면 자가 안 맞는다).
# 그때 "외부 기준(데이트·카페형 태그)과 상관 0.00 이라 죽은 축" 이라고 읽었는데 검산 자체가 틀렸다.
# 빵 성질 축을 가게 분위기 기준으로 잰 것이라 0 이 나오는 게 당연했다.
# 가게 분위기는 이미 tags 가 쓴다 — 공간사진이 interior_cool·photo_good·view_good·concept_unique 를 소비한다(cozy·atmosphere_calm 을
# 쓰던 노포는 09-19 에 빠졌다). 축에 또 넣으면 같은 신호를 두 번 세게 된다.


# v5-20260919: 태그 사전 교체(방문자 투표 기반 8개) · signature_kinds 추가 (dataops #11). 축 산식은 v3 와 같다. 재계산 대상을 이걸로 고른다
# v4-20260919: 태그 12 → 8(노포·신상·아침·저녁 제거) · 대표 메뉴 정제
BATCH_VERSION = "v7-20260925"
CONVENIENCE_KEYS = {"주차": "parking", "예약": "reservation", "반려동물 동반": "pet", "무선 인터넷": "wifi",
                    "포장": "takeout", "배달": "delivery", "단체 이용 가능": "group", "유아시설": "kids",
                    "노키즈존": "noKids", "대기공간": "waitingArea"}


def axis_source(menu_axis, review_axis):
    """축을 어느 재료로 만들었는지 — 신뢰도 구분용. 둘 다 없으면 NULL."""
    if menu_axis and review_axis:
        return "BOTH"
    if menu_axis:
        return "MENU"
    return "REVIEW" if review_axis else None


def attrs_of(signal):
    """운영 store_profile.attrs — 편의시설 10종 + 신규 개업 표시. 점수에는 안 들어간다(설계 §2).

    있다/없다가 갈리고 형태가 자주 바뀌는 것만 JSON 에 둔다. 리뷰 수·별점은 2026-09-13 부터 컬럼(review_fields),
    category·categoryCode 는 store.category(BE #730)로 나갔다 — 두 곳에 있으면 어느 쪽이 참인지 모르게 된다."""
    conv = signal.get("conveniences") or []
    out = {k: True for label, k in CONVENIENCE_KEYS.items() if label in conv}
    if signal.get("newOpening"):
        out["newOpening"] = True
    return out


def review_fields(signal):
    """운영 store_profile 의 review_count · rating_count · review_score · description (BE #731).

    별점은 1 미만이면 결측이다. 네이버는 별점 표본이 적으면 평균을 감추는데 그때 null 이 아니라 0 을 준다 —
    0점 5,071곳 전부 리뷰는 있다(평균 55.5개, 최대 3,154개). 리뷰가 없는 게 아니라 별점 매긴 사람이 평균 12.7명뿐이라
    감춘 것이다(이전 주석의 "리뷰 없음이 0 으로 들어온 것" 은 틀렸다). 0.5·0.75 도 47곳 온다 — 1~5점 평균이 1 아래로
    내려갈 수 없으니 실제 평점이 아니다. 운영 CHECK(review_score BETWEEN 1 AND 5)는 이 규칙을 전제한다.
    rating_count 는 별점을 매긴 사람 수(파서 v2) — 리뷰 수의 28% 라 평점 신뢰도의 분모로 따로 둔다."""
    def count(key):
        v = signal.get(key)
        return int(v) if v is not None and int(v) >= 0 else None
    score = signal.get("visitorReviewsScore")
    score = round(float(score), 2) if score is not None and float(score) >= 1 else None
    description = (signal.get("description") or "").strip() or None
    return {"review_count": count("visitorReviewsTotal"), "rating_count": count("ratingReviewsTotal"),
            "review_score": score, "description": description}


def type_of(axis):
    """축 4개의 부호로 4글자 타입 코드(SFRO~PCLN). 약한 축은 라벨에 '?' 로 표시하되 부호는 그대로 따른다.

    2026-09-10 이전에는 `code += bit` 가 else 안에 있어서, |축| < NEUTRAL 이면 부호와 무관하게
    bit 가 안 켜졌다. code=1(지금의 SFRO) 은 bit 가 하나도 안 켜진 상태(달콤·부드·풍부·고전)라 애매한 가게가
    전부 거기로 굴러떨어졌다 — 운영 36,904곳 중 6,272곳(17.0%)이 잘못된 타입을 받았고 1번이 40.2%였다.
    통밀·식빵·바게트를 파는 가게가 '달콤·부드·풍부' 로 나가는 식이다.

    뿌리는 임계값이 틀린 원점을 기준으로 잡혀 있던 것이다. 0 은 데이터의 중심이 아니다 —
    taste 평균이 -0.374(표준편차 0.652)라 값 0 은 평균에서 +0.57 표준편차, 담백 쪽으로 뚜렷하게
    치우친 가게다. '애매하니 단정하지 말자'고 만든 구간이 실제로는 평균보다 담백한 가게들이었다.

    그래서 두 정보를 갈라 놓는다. 부호(어느 쪽인가)는 코드가 쓰고, 세기(얼마나 확실한가)는 '?' 가 쓴다.
    축 값 자체는 건드리지 않는다.

    2026-09-13 (BE #731): 표현을 숫자 1~16 에서 4글자로 바꿨다. 규칙은 같다 — 자리마다 축 하나, 0 보다 크면
    +극 글자, 0 은 −극. BE TasteAxis.typeCode() 와 글자·순서가 같아야 한다(운영 CHECK ^[SP][FC][RL][ON]$)."""
    if axis is None:
        return None, None
    code, labels = "", []
    for a in AXES:
        v = axis[a]
        code += POLE_CODE[a][v > 0]
        labels.append(POLE_LABEL[a][v > 0] + ("?" if abs(v) < NEUTRAL else ""))
    return code, f"{code} {TYPE_NAMES[code]} ({'·'.join(labels)})"


def type_code_of_number(n):
    """옛 숫자 타입 1~16 → 4글자 코드. 숫자 규칙이 code = 1 + 8·(맛>0) + 4·(식감>0) + 2·(속>0) + (신상>0) 이라
    n-1 의 비트 8·4·2·1 이 순서대로 네 자리의 +극 여부다. BE 마이그레이션 V20260911142251 의 USING 식과 같다."""
    return None if n in (None, "") else TYPE_CODES[int(n) - 1]


def bakery_score(category, kinds, signal):
    """빵집다움 0~1 — 후보 풀에서 분식·치킨·젤라또처럼 빵을 곁들여 파는 가게를 삭제 대신 점수로 내린다"""
    cat = 0.5 if category in BAKERY_CATEGORIES else (0.0 if any(h in category for h in NON_BAKERY_HINTS) else 0.25)
    bread_kinds = [k for k in kinds if k not in ("기타", "요거트", "아이스크림", "빙수", "떡")]
    share = 0.3 * (len(bread_kinds) / len(kinds)) if kinds else 0.0
    votes = {v["code"]: v["count"] for v in signal.get("votedKeywords") or []}
    total = sum(votes.values())
    bread_good = 0.2 * min(1.0, 3 * votes.get("bread_good", 0) / total) if total else 0.0
    score = cat + share + bread_good
    if any(h in category for h in NON_BAKERY_HINTS) and category not in BAKERY_CATEGORIES:
        score = min(score, 0.35)
    return round(min(1.0, score), 3)


def is_drink(name):
    n = (name or "").replace(" ", "").lower()
    return any(n.endswith(w) for w in DRINK_WORDS)


def is_non_bread(name):
    n = (name or "").replace(" ", "")
    return any(n.endswith(h) for h in NON_BREAD_HEADS)


def is_bakery_label(label, kinds_by_len):
    """리뷰 언급 라벨이 빵집 메뉴라는 근거가 있나 — 빵 종류로 풀리거나 빵집 메뉴 끝말로 끝난다."""
    n = (label or "").replace(" ", "")
    if not n or n in GENERIC_LABELS:
        return False
    return kind_of_label(n, kinds_by_len) is not None or any(n.endswith(h) for h in BAKERY_LABEL_HEADS)


def clean_menu_name(name):
    """recommend 메뉴 이름 → 메뉴 이름만. 번호·괄호·기호·수량·가격·홍보 문구·세트 꼬리를 뗀다.
    선물세트·모둠 박스·할인 공지처럼 메뉴가 아닌 것이면 None."""
    n = MENU_ENUM.sub("", name or "")
    n = MENU_BRACKETS.sub(" ", n)
    n = MENU_SYMBOLS.sub(" ", n)
    n = MENU_QUANTITY.sub(" ", n)
    n = " ".join(w for w in n.split() if w.lower() not in MENU_NOISE_WORDS)
    n = MENU_SET_TAIL.sub("", n).strip()
    if not n or any(w in n for w in NOT_MENU_WORDS):
        return None
    return n


def signature(signal, kinds_by_len, gone_names=frozenset(), gone_kinds=frozenset(), *, evidence=None):
    """대표 메뉴: recommend 이름(정제, 음료·비-빵 제외, 최대 3) + 리뷰 언급 라벨(빵집 메뉴라는 근거가 있는 것) 로 최대 5.

    gone_names 는 **이름** 으로 뺀다 — recommendMenus 가 실제 메뉴 이름이라 여기서 걸린다.
    gone_kinds 는 **종류** 로 뺀다 — reviewMenus 라벨은 종류 이름(`소금빵`)이라 이름 완전일치로는 안 걸린다.
      사라진 메뉴가 `자연도 소금빵` 이면 정규화해도 `소금빵` 과 같지 않아서, 09-22 이전에는 리뷰 쪽이
      사실상 무방비였다(실측: 그 종류의 메뉴가 있었다가 사라져 지금 0인 가게 568곳 · 상권 안 86곳).
      이건 조용한 오류가 아니다 — signature_kinds 가 "{종류} 취향" 배지(BE #762)의 재료이고,
      이유 문장이 근거 기반 템플릿으로 바뀌면 "밤식빵이 대표 빵인 곳이에요" 같은 **단정문**으로 나간다.

    2026-09-25: 이름을 분류한 결과도 같은 기준으로 뺀다. `밤식빵` 은 gone_kinds 에 `식빵` 이 있어도
      이름이 달라 통과했는데, 분류하면 `식빵` 이다. 남아 있던 191건(가게 183)의 근거를 재 보니
      **182건이 recommendMenus 가 비어 리뷰 언급뿐**이었다 — 가게가 대표 메뉴 칸도 안 채운 상태에서
      그 종류 메뉴가 전부 두 번 연속 사라졌다면 근거가 리뷰 하나뿐이라 단정문을 내보낼 수 없다.
    """
    def kind_gone(name):
        """이름을 종류로 풀었을 때 '있었다가 사라져 지금 0인 종류' 인가 — signature_kinds 와 같은 분류를 쓴다"""
        if not gone_kinds:
            return False
        kind, _, word = classify(name)
        return kind is not None and kind in gone_kinds and not doubt(name, kind, word)

    def allowed(name):
        if evidence is None:
            return True
        kind = canonical_kind(name)
        return evidence.allows_name(name, consumer="signature") and (
            kind is None or evidence.allows_kind(kind, consumer="signature"))

    # Once a verified observation exists, mutable/older signal recommendations
    # cannot fill gaps in it. An empty partial observation means unknown: withhold
    # direct recommendations, without turning that empty list into negative proof.
    if evidence is not None and evidence.has_observations:
        recommend_names = evidence.representative_names
    else:
        recommend_names = [m.get("name") for m in signal.get("recommendMenus") or []]
    names = []
    for raw_name in recommend_names:
        n = clean_menu_name(raw_name)
        if (n and n not in names and not is_drink(n) and not is_non_bread(n)
                and normalize_menu_name(raw_name) not in gone_names and normalize_menu_name(n) not in gone_names
                and not kind_gone(raw_name) and not kind_gone(n) and allowed(raw_name) and allowed(n)):
            names.append(n)
        if len(names) >= 3:
            break
    for m in sorted(signal.get("reviewMenus") or [], key=lambda x: -x["count"]):
        label = m["label"]
        if (label in DRINK_LABELS or label in INGREDIENT_LABELS or is_drink(label) or is_non_bread(label)
                or not is_bakery_label(label, kinds_by_len) or any(label in n for n in names)
                or normalize_menu_name(label) in gone_names or label in gone_kinds or kind_gone(label)
                or not allowed(label)):
            continue
        names.append(label)
        if len(names) >= 5:
            break
    return names[:5]


def normalize_menu_name(name):
    """소멸 제안 이름과 맞대려고 쓰는 정규화 — 로컬 SQL 의 regexp_replace 와 같은 규칙"""
    return re.sub(r"[^0-9A-Za-z가-힣]", "", name or "").lower()


def signature_kinds(names):
    """대표 메뉴 이름들 → 종류(대표 메뉴 순서, 중복 없음). 운영 메뉴를 분류하는 규칙(menu_classifier = MenuCategoryClassifier)을
    그대로 써서 회원 top_kinds(menu_category.name)와 같은 이름이 나온다 — 찹쌀꽈배기 → 도넛, 초코크로와상 → 크루아상.
    일반 종류(빵·기타·요거트)와 믿기 어려운 판정(키슈 → 슈, 인절미빵 → 떡 …, menu_classifier.doubt)은 버린다."""
    out = []
    for name in names:
        kind = canonical_kind(name)
        if kind is None or kind in GENERIC_KINDS or kind in out:
            continue
        out.append(kind)
    return out


def review_kind_hits(signal, allowed_kinds, evidence, consumer="score"):
    """Shared input selection for profile axes/kinds and all nine feature labels.

    Review timestamps are intentionally never passed as PRESENT observations.
    """
    hits = []
    for review in signal.get("reviewMenus") or []:
        kind = canonical_kind(review.get("label"), allowed_kinds=allowed_kinds)
        count = int(review.get("count") or 0)
        if (kind and count > 0 and evidence.allows_kind(kind, consumer=consumer)
                and evidence.allows_name(review.get("label"), consumer=consumer)):
            hits.append((kind, count))
    return hits


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--testset", default=os.path.join(REPO_ROOT, "matching", "course_testset_20260904.tsv"))
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--keep-vanished", action="store_true",
                        help="사라진 메뉴를 빼지 않는다 — 규칙 효과를 따로 재려고 같은 코호트로 두 판을 만들 때")
    parser.add_argument("--version", type=int, default=1)
    parser.add_argument("--evidence-min-hours", type=float, default=6,
                        help="독립 부재 관측 최소 간격; 검증 전 제안 기본값 6시간")
    parser.add_argument("--evidence-max-days", type=float, default=30,
                        help="미확정 연속 부재 재시작 간격; 제안 기본값 30일")
    args = parser.parse_args()
    if args.keep_vanished:
        parser.error("--keep-vanished는 기존 검수표를 덮을 수 있어 중단했습니다. 격리된 fixture/shadow로 비교하세요")
    evidence_policy = EvidencePolicy(min_interval=dt.timedelta(hours=args.evidence_min_hours),
                                     max_interval=dt.timedelta(days=args.evidence_max_days))

    if args.all:
        ids = [r["store_id"] for r in local_csv("SELECT store_id FROM store_signal ORDER BY store_id;")]
    else:
        ids = [r["store_id"] for r in csv.DictReader(open(args.testset, encoding="utf-8"), delimiter="\t")]
    if not ids:
        raise SystemExit("계산 대상이 없습니다")
    ids = sorted({str(int(sid)) for sid in ids}, key=int)
    id_list = ",".join(ids)

    axis_table, kinds_by_len = load_axis_table()
    signals = {r["store_id"]: r for r in local_csv(f"SELECT store_id, snapshot_id, category, signals::text AS signals FROM store_signal WHERE store_id IN ({id_list});")}
    if set(ids) != set(signals):
        raise ValueError("signal missing for requested stores; no profile drafts were replaced")
    menus = prod_csv(f"SELECT m.id, m.store_id, m.name, mc.name AS kind, COALESCE(m.like_count,0) AS likes FROM menu m LEFT JOIN menu_category mc ON mc.id = m.menu_category_id "
                     f"WHERE m.deleted_at IS NULL AND m.store_id IN ({id_list});", args.env)
    raw_menus = group_menus(menus)
    evidence_by_store, evidence_cutoff = load_store_evidence(ids, menus, local_csv, evidence_policy)
    blocked = {sid: state.publication_blockers for sid, state in evidence_by_store.items()
               if state.publication_status == "NEEDS_REVIEW"}
    print(f"legacy 검증 대기 {len(blocked):,}곳: 초안 비교만 가능하며 기존 serving 값은 유지합니다", flush=True)
    if blocked:
        print(json.dumps({"status": "NEEDS_REVIEW", "store_count": len(blocked), "sample_limit": 20,
                          "sample": dict(list(blocked.items())[:20]),
                          "full_reasons": "store_profile_draft.kind_evidence.publication_blockers"},
                         ensure_ascii=False), flush=True)
    before = len(menus)
    menus = [m for sid, items in raw_menus.items() for m in evidence_by_store[sid].filter_menus(items)]
    print(f"공통 종류 근거로 메뉴 {before:,}건 중 {before - len(menus):,}건 제외 (cutoff={evidence_cutoff})", flush=True)
    store_rows = prod_csv(f"SELECT id AS store_id, name, status FROM store WHERE id IN ({id_list});", args.env)
    names = {r["store_id"]: r["name"] for r in store_rows}
    status = {r["store_id"]: r["status"] for r in store_rows}
    menu_kinds_all = {}
    for m in menus:
        if m["kind"]:
            menu_kinds_all.setdefault(m["store_id"], []).append(m["kind"])
    scale = vote_scale()   # 투표 태그의 사전 비율·백분위 기준 — 직전 검수표의 빵집다움으로 모집단을 고르므로 upsert 전에 만든다

    menu_vectors = {}
    for m in menus:
        ax = axis_table.get(m["kind"])
        if ax:
            menu_vectors.setdefault(m["store_id"], []).append((ax, 1 + int(m["likes"])))

    rows, missing = [], []
    for sid in ids:
        sig_row = signals.get(sid)
        if not sig_row:
            missing.append(sid)
            continue
        signal = json.loads(sig_row["signals"])
        evidence_state = evidence_by_store[sid]
        mv = menu_vectors.get(sid, [])
        menu_axis = weighted(mv) if len(mv) >= MIN_MENUS else None
        review_hits = review_kind_hits(signal, kinds_by_len, evidence_state)
        rv = [(axis_table[kind], count) for kind, count in review_hits]
        rv_kinds = [kind for kind, _ in review_hits]
        review_axis = weighted(rv)
        if menu_axis is None and review_axis is not None and (len(rv) < MIN_REVIEW_LABELS and sum(c for _, c in rv) < MIN_REVIEW_MENTIONS):
            review_axis = None  # 리뷰 라벨 한둘로 축을 세우지 않는다
        axis = combine(menu_axis, review_axis)
        bakery = bakery_score(sig_row["category"] or "", menu_kinds_all.get(sid, []), signal)
        type_code, type_label = type_of(axis)
        tags, evidence = tag_score(sig_row, scale)
        sig_names = signature(signal, kinds_by_len, evidence=evidence_state)
        sig_kinds = signature_kinds(sig_names)
        frozen, frozen_fingerprint = freeze_context(evidence_state, signal, raw_menus[sid],
                                                    sig_row["snapshot_id"], evidence_cutoff)
        rows.append({
            "axis_source": axis_source(menu_axis, review_axis),
            # Representative-only aliases are added only after shared evidence validation.
            "kinds": sorted(set(menu_kinds_all.get(sid, [])) | set(rv_kinds) | set(sig_kinds)),
            "attrs": attrs_of(signal), "review": review_fields(signal),
            "store_id": int(sid), "axis": axis, "type": type_code, "type_label": type_label,
            "menu_n": len(mv), "review_n": len(rv), "tags": {t: round(tags[t], 3) for t in TAGS}, "evidence": evidence,
            "signature": sig_names, "signature_kinds": sig_kinds, "snapshot_id": int(sig_row["snapshot_id"]),
            "kind_evidence": frozen, "kind_evidence_fingerprint": frozen_fingerprint,
            "name": names.get(sid, sid), "bakery": bakery, "status": status.get(sid, "?"),
        })


    # upsert (로컬)
    sql_lines = []
    for r in rows:
        ax = r["axis"] or {}
        def num(a):
            return "NULL" if r["axis"] is None else f"{ax[a]:.3f}"
        tags_json = json.dumps(r["tags"], ensure_ascii=False).replace("'", "''")
        ev_json = json.dumps(r["evidence"], ensure_ascii=False).replace("'", "''")
        sig = "ARRAY[" + ",".join("'" + s.replace("'", "''") + "'" for s in r["signature"]) + "]::text[]" if r["signature"] else "'{}'::text[]"
        label = "NULL" if r["type_label"] is None else "'" + r["type_label"].replace("'", "''") + "'"
        src = "NULL" if r["axis_source"] is None else "'" + r["axis_source"] + "'"
        kinds_sql = ("ARRAY[" + ",".join("'" + k.replace("'", "''") + "'" for k in r["kinds"]) + "]::text[]") if r["kinds"] else "'{}'::text[]"
        sig_kinds_sql = ("ARRAY[" + ",".join("'" + k.replace("'", "''") + "'" for k in r["signature_kinds"]) + "]::text[]") if r["signature_kinds"] else "'{}'::text[]"
        attrs_json = json.dumps(r["attrs"], ensure_ascii=False).replace("'", "''")
        kind_evidence_json = json.dumps(r["kind_evidence"], ensure_ascii=False).replace("'", "''")
        rv = r["review"]

        def opt(v):
            return "NULL" if v is None else str(v)
        desc = "NULL" if rv["description"] is None else "'" + rv["description"].replace("'", "''") + "'"
        type_sql = "NULL" if r["type"] is None else "'" + r["type"] + "'"
        sql_lines.append(
            f"INSERT INTO store_profile_draft (store_id, axis_taste, axis_texture, axis_filling, axis_vibe, bbti_type, type_label, axis_menu_count, axis_review_count, tags, tag_evidence, signature_menus, signature_kinds, signal_snapshot_id, profile_version, computed_at, bakery_score, store_status, axis_source, kinds, attrs, batch_version, review_count, rating_count, review_score, description, kind_evidence, kind_evidence_fingerprint) "
            f"VALUES ({r['store_id']}, {num('taste')}, {num('texture')}, {num('filling')}, {num('vibe')}, {type_sql}, {label}, {r['menu_n']}, {r['review_n']}, "
            f"'{tags_json}'::jsonb, '{ev_json}'::jsonb, {sig}, {sig_kinds_sql}, {r['snapshot_id']}, {args.version}, now(), {r['bakery']}, '{r['status']}', "
            f"{src}, {kinds_sql}, '{attrs_json}'::jsonb, '{BATCH_VERSION}', {opt(rv['review_count'])}, {opt(rv['rating_count'])}, {opt(rv['review_score'])}, {desc}, '{kind_evidence_json}'::jsonb, '{r['kind_evidence_fingerprint']}') "
            f"ON CONFLICT (store_id) DO UPDATE SET axis_taste=EXCLUDED.axis_taste, axis_texture=EXCLUDED.axis_texture, axis_filling=EXCLUDED.axis_filling, axis_vibe=EXCLUDED.axis_vibe, "
            f"axis_source=EXCLUDED.axis_source, kinds=EXCLUDED.kinds, attrs=EXCLUDED.attrs, batch_version=EXCLUDED.batch_version, "
            f"review_count=EXCLUDED.review_count, rating_count=EXCLUDED.rating_count, review_score=EXCLUDED.review_score, description=EXCLUDED.description, "
            f"bbti_type=EXCLUDED.bbti_type, type_label=EXCLUDED.type_label, axis_menu_count=EXCLUDED.axis_menu_count, axis_review_count=EXCLUDED.axis_review_count, tags=EXCLUDED.tags, "
            f"tag_evidence=EXCLUDED.tag_evidence, signature_menus=EXCLUDED.signature_menus, signature_kinds=EXCLUDED.signature_kinds, signal_snapshot_id=EXCLUDED.signal_snapshot_id, profile_version=EXCLUDED.profile_version, computed_at=now(), bakery_score=EXCLUDED.bakery_score, store_status=EXCLUDED.store_status, kind_evidence=EXCLUDED.kind_evidence, kind_evidence_fingerprint=EXCLUDED.kind_evidence_fingerprint;")
    if sql_lines:
        r = subprocess.run(["docker", "exec", "-i", "bbanggut-dataops-db", "psql", "-U", "dataops", "-d", "dataops", "-q", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                           input="BEGIN;\n" + "\n".join(sql_lines) + "\nCOMMIT;\n", capture_output=True, text=True, encoding="utf-8")
        if r.returncode:
            raise SystemExit("upsert 실패: " + r.stderr.strip()[:600])

    print(f"store_profile_draft upsert {len(rows)}곳 (신호 없음 {len(missing)}) — 축 있음 {sum(1 for r in rows if r['axis'])}, 메뉴 3개↑ {sum(1 for r in rows if r['menu_n'] >= MIN_MENUS)}, 리뷰 축만 {sum(1 for r in rows if r['axis'] and r['menu_n'] < MIN_MENUS)}\n")
    print(f"  ACTIVE 아님 {sum(1 for r in rows if r['status'] != 'ACTIVE')}곳 (store_status 로 표시, 후보에서 제외) · 빵집다움 < 0.5 {sum(1 for r in rows if r['bakery'] < 0.5)}곳")
    if not args.all:
        for r in rows:
            ax = r["axis"]
            axis_txt = " ".join(f"{ax[a]:+.2f}" for a in AXES) if ax else "—"
            on = [f"{t} {v:.2f}" for t, v in r["tags"].items() if v >= 0.5]
            print(f"■ {r['name'][:18]:18} 축 {axis_txt:26} (메뉴 {r['menu_n']:>2}·리뷰 {r['review_n']:>2}) {r['type_label'] or '— 축 없음'}")
            print(f"   태그 {', '.join(on) if on else '(없음)'}")
            print(f"   대표 {' · '.join(r['signature']) if r['signature'] else '(없음)'}  → 종류 {', '.join(r['signature_kinds']) or '(없음)'}")
    else:
        from collections import Counter
        c = Counter(r["type"] for r in rows if r["type"])
        print("타입 분포:", ", ".join(f"{t} {TYPE_NAMES[t]} {n}" for t, n in sorted(c.items())))


if __name__ == "__main__":
    main()
