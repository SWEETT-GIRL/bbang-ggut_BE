"""신규 유입 빵집 관문 G1 — LOCALDATA 신규 후보를 네이버 카테고리 잎과 메뉴 몫으로 가른다.

왜 (2026-09-19 · 가게 검토 세션 분석, localdata_low_bakery_review.py 에서 옮김)
- 08-21/28 신규 등록 7,409곳 중 빵집이 아닌 곳이 대량으로 새어 들었다. 새던 곳 둘:
  1) 카테고리 판정(localdata_new_verify.BAKERY_PAT)이 경로 전체에 부분일치 — '카페,디저트' 대분류의 '디저트'가 모든 카페·아이스크림에,
     '떡'이 호떡·떡볶이에, '빵'이 찐빵 잎에 걸렸다
  2) 메뉴 규칙(localdata_bread_check.BREAD_PAT)이 '빵' 한 글자·찐빵·꽈배기·에그타르트 한 줄로도 통과시켰다 — 만두집·피자집·버거 체인
- 일반·휴게음식점 등록 4,391곳에 돌리면 차단 712 · 사람 검수 236 · 통과 3,443, 유지 판정 빵집 차단 0 (09-19 재측정)

G1 (모든 인허가)
- 잎이 빵집 잎 · 하위 분류 없는 '카페,디저트' · 허용 잎(아이스크림·빙수)이면 카테고리만으로 PASS
- 비빵 잎(분식·호떡·떡·피자·버거·한식·술집…)은 제품 빵 3개 이상이면서 음식 메뉴의 10% 이상이거나 샌드위치 가게면 HOLD(사람 검수),
  아니면 BLOCK — 몫 조건이 없으면 메뉴 100개 넘는 체인·대형 식당이 곁들이 빵 3개(에그타르트·쿠키·치즈볼)로 HOLD 가 됐다
  (09-20 검토 세션 측정: 비빵 잎 HOLD 중 몫 10% 미만 38곳 전부 hide 판정)
- 비빵 체인(파파이스·롯데리아·피자 체인…)은 메뉴와 상관없이 BLOCK
- 나머지 잎(카페·찐빵·초콜릿…)은 메뉴 몫 조건(제품 빵 1개 이상이 음식 메뉴의 30% 이상, 또는 3개 이상이 15% 이상)을 채우면 PASS,
  메뉴가 없거나 제품 빵이 조금 있거나 샌드위치·샐러드면 HOLD, 메뉴가 있는데 제품 빵이 0이면 BLOCK
- 빵 증거는 두 층: 제품 빵(베이글·소금빵·케이크·휘낭시에·호두과자…)만 센다. 길거리 간식(호떡·붕어빵·찐빵·꽈배기·찹쌀도넛·츄러스…)은
  분식 노점의 곁들이라 빵집 증거로 치지 않는다. 샌드위치·토스트·버거·피자는 요리라 어느 쪽에도 넣지 않는다

사용자 결정 (09-19)
- 아이스크림·빙수(젤라또 포함) 가게는 DB 에 둔다 → 통과
- 샌드위치 가게는 살린다 → 막지 않고 사람 검수
- 호두과자·찐빵·길거리 간식 전문점을 빵집 범위에 넣을지는 미정 — 호두과자·경주빵 잎은 통과, 찐빵 잎은 메뉴로 가린다(대개 HOLD·BLOCK)
"""
import re

# 네이버 카테고리 경로 앞 그룹 — 잎(마지막 분류)을 얻으려고 뗀다
CATEGORY_GROUPS = ("카페,디저트", "음식점", "분식", "전통식품", "양식", "제조업", "직업,기술교육", "생활,편의", "도시락,컵밥", "한식",
                   "중식", "일식", "술집", "팝업스토어", "쇼핑,유통", "이탈리아음식", "서비스,산업", "사회,복지", "여행,명소",
                   "가구,인테리어", "교통,운수")
# 메뉴 증거 없이 통과하는 잎 — bakery_score.BAKERY_CATEGORIES 에서 범용 '카페,디저트'·'디저트'를 뺀 것 + 호두과자·경주빵
BAKERY_LEAVES = {"베이커리", "빵류제조", "제과,제빵", "제과점", "베이커리카페", "케이크전문", "베이글", "도넛", "샌드위치", "브런치카페",
                 "디저트카페", "크레페", "와플", "타르트", "마카롱", "쿠키", "스콘", "구움과자", "커피번", "호두과자", "경주빵", "과자류제조"}
GENERIC_LEAVES = {"카페,디저트", "디저트", ""}   # 네이버가 하위 분류를 안 준 가게 — 카테고리만으로는 모른다(G1 은 통과시킨다)
ALLOWED_LEAVES = {"아이스크림", "빙수"}          # 09-19 사용자 결정
SOFT_LEAVES = ("샐러드",)                        # 막지 않고 사람 검수로
RICE_CAKE_LEAVES = {"떡,한과", "떡류제조", "떡카페"}
# 비빵 잎 — bakery_score.NON_BAKERY_HINTS 에서 '카페'를 뺀 것(카페는 메뉴로 가린다) + 눈검사에서 새던 업종
HARD_NON_BAKERY = ("분식", "치킨", "한식", "중식", "일식", "양식", "이탈리아", "피자", "햄버거", "호떡",
                   "술집", "주점", "떡", "한과", "공방", "만두", "김밥", "도시락", "꽃집", "호프")
# 잎만 보면 앞 그룹이 떨어져 '한식,육류,고기요리' · '이탈리아음식,스파게티,파스타전문' · '사회,복지,노인복지' 가 비빵으로 안 잡혔다
# (09-20 시뮬레이션에서 숨김 권고 3곳이 메뉴 몫으로 통과). 이 그룹은 잎과 상관없이 비빵으로 본다
HARD_GROUPS = {"한식", "중식", "일식", "이탈리아음식", "술집", "사회,복지", "서비스,산업", "여행,명소", "가구,인테리어", "교통,운수"}

STREET = re.compile(r"호떡|붕어빵|붕어|잉어빵|국화빵|풀빵|계란빵|십원빵|땅콩빵|찐빵|호빵|술빵|옥수수빵|공갈빵|꽈배기"
                    r"|찹쌀\s*팥?\s*도(?:넛|너츠|나스)|팥\s*도(?:넛|너츠)|핫도그|츄러스|델리만쥬")
NOT_BREAD = re.compile(r"점빵|빵가루|빵게|빵빵김밥|파파이스|맥파이|떡\s*케이?크|떡케익|앙금\s*(?:플라워)?\s*케이크|설기|아이스크림\s*케이크"
                       r"|쿠키\s*(?:앤|&|n)\s*크림|쿠앤크|케이크\s*페인팅|클래스|체험|원데이")
PRODUCT = re.compile(
    r"베이글|크루아상|크로와상|크로아상|소금빵|스콘|바게트|바게뜨|치아바타|캄파뉴|캉파뉴|깜빠뉴|깜파뉴|프레첼|브레첼|프렛젤"
    r"|케이크|케익|타르트|마카롱|휘낭시에|피낭시에|마들렌|카스테라|카스텔라|머핀|쿠키|두쫀쿠|브라우니|페이스트리|페스츄리|패스츄리"
    r"|식빵|모닝빵|크로플|롤케|까눌레|카눌레|다쿠아즈|에끌레어|에클레어|슈크림|파운드|브리오슈|포카치아|사워도우|소보로|소보루"
    r"|단팥빵|크림빵|고로케|크로켓|슈톨렌|파네토네|시나몬롤|와플|호두과자|만쥬|경주빵|황남빵|꿀빵|마늘빵|티라미수|크레페|구움과자"
    r"|모카번|커피번|버터번|소금번|브레드|(?<!스)파이(?![어시브널프스])|도넛|도너츠|빵"
    r"|러스크|몽블랑|산도|버터바|버터떡|앙버터|퀸아망|크룽지|티그레|프레지에|수플레|브륄레|비스킷|모나카|화과자|약과|주악|맘모스|소라빵"
    r"|croissant|bagel|cake|scone|bread|tart|cookie|brioche|baguette|financier|madeleine|canel|pastry|muffin|brownie|waffle|croffle"
    r"|macaron|donut|doughnut|chausson|pain",
    re.I,
)
DISH = re.compile(r"김밥|라면|우동|국수|떡볶이|순대|튀김|치킨|닭|피자|버거|돈가스|돈까스|덮밥|찌개|탕|전골|볶음밥|만두")
DRINK = re.compile(r"라떼|라테|아메리카노|커피|에이드|주스|쥬스|스무디|밀크티|아이스티|콜드브루|에스프레소|아인슈페너|프라페|모히또"
                   r"|음료|생수|콜라|사이다|카푸치노|마키아또|녹차|홍차|얼그레이|캐모마일|쉐이크|요거트|요구르트|식혜|수정과|차$|티$")
# 음료가 머리(끝말)인 메뉴 — 빵 단어가 수식어로 붙어도 음료다 ('도넛크림라떼' · '파사노 브륄레 라떼' · '쿠키치즈폼프레치노').
# DRINK 는 어디에 있든 걸려서 빵 판정에 그대로 쓰면 '커피번' 이 빵에서 빠진다 — 여기서는 끝말만 본다.
# 2026-09-26 카페 잎 표본: 음료 이름 한 줄이 제품 빵 1개·몫 100% 로 자동 통과를 만들었다
DRINK_HEAD = re.compile(r"(?:라떼|라테|아메리카노|커피|에이드|주스|쥬스|스무디|밀크티|아이스티|티|콜드브루|에스프레소|아인슈페너"
                        r"|프라페|프라푸치노|프레치노|모히또|음료|카푸치노|마키아또|마끼아또|쉐이크|셰이크|요거트|요구르트|라씨|소다"
                        r"|블렌디드|핫초코|아이스초코|코코아|latte|coffee|ade|smoothie|shake|frappe|frappuccino|tea)$", re.I)
# 끝말을 가리는 꾸밈 — 괄호·대괄호 옵션, 온도·크기 표기
_BRACKETS = re.compile(r"\(.*?\)|\[.*?\]|<.*?>|【.*?】")
_TRAILING_OPTION = re.compile(r"(?:hot|ice|iced|아이스|핫|레귤러|라지|톨|그란데|벤티|onlyhot|onlyice|\d+(?:ml|oz|잔))+$", re.I)
# 빵과 음료를 묶은 세트 — 끝말이 음료여도 빵이 들어 있다 ('소금빵+아메리카노', '스콘&라떼')
_BUNDLE = re.compile(r"[+&＋]|세트|set", re.I)
SANDWICH = re.compile(r"샌드위치|파니니|sandwich", re.I)
# 비빵 체인 — 확정 규칙의 FRANCHISE_BRANDS 밖에서 메뉴 곁들이(에그타르트·꽈배기)로 새어 든 것 (08-21/28 등록 눈검사)
NON_BAKERY_CHAINS = ["파파이스", "롯데리아", "피자스쿨", "빵위에치즈", "선명희피자", "피자마루", "맘스터치"]


def leaf_of(path):
    path = path or ""
    for g in CATEGORY_GROUPS:
        if path.startswith(g + ","):
            return path[len(g) + 1:]
    return "" if path == "카페,디저트" else path


def group_of(path):
    path = path or ""
    for g in CATEGORY_GROUPS:
        if path == g or path.startswith(g + ","):
            return g
    return ""


def category_pass(leaf):
    """카테고리만으로 G1 통과 — 메뉴를 긁을 필요가 없다"""
    return leaf in BAKERY_LEAVES or leaf in GENERIC_LEAVES or leaf in ALLOWED_LEAVES


def menu_key(name):
    """같은 메뉴 판별 — 공백·대소문자만 뗀 이름. 괄호는 남긴다: '샌드위치 (리치카라멜)' 과 '(스트로베리)' 는 다른 제품이다"""
    return (name or "").replace(" ", "").lower()


def is_drink_name(name):
    """끝말이 음료인가 — '도넛크림라떼'·'쿠키치즈폼프레치노(ICE)' 는 음료, '커피번'·'말차 쿠키'·'우유 식빵' 은 아니다"""
    key = menu_key(name)
    if _BUNDLE.search(key):
        return False
    return bool(DRINK_HEAD.search(_TRAILING_OPTION.sub("", _BRACKETS.sub("", key))))


def bread_evidence(names):
    """메뉴 이름들 → (제품 빵 이름들, 길거리 빵 이름들, 음료를 뺀 음식 메뉴 수)

    같은 메뉴(menu_key)는 한 번만 센다 — 같은 이름을 여러 번 넘겨도 제품 빵이 불어나지 않는다.
    끝말이 음료인 메뉴는 빵 단어가 있어도 제품 빵·음식이 아니다 (2026-09-26).
    """
    product, street, food = [], [], 0
    seen = set()
    for raw in names:
        n = (raw or "").strip()
        if not n:
            continue
        key = menu_key(n)
        if not key or key in seen:
            continue
        seen.add(key)
        flat = n.replace(" ", "")
        drink = is_drink_name(n)
        masked = NOT_BREAD.sub("", flat)
        is_street = bool(STREET.search(masked))
        rest = STREET.sub("", masked)
        m = PRODUCT.search(rest)
        if m and (DISH.search(rest) or is_street or drink):
            m = None  # '빵빵김밥'·'쿠키네 카레덮밥' 은 요리, '슈크림 붕어빵'·'앙버터 츄러스' 는 길거리 간식, '도넛크림라떼' 는 음료
        if m:
            product.append(n)
        elif is_street and not drink:
            street.append(n)
        if m or (is_street and not drink) or not (DRINK.search(flat) or drink):
            food += 1
    return product, street, food


def is_sandwich_shop(name, names, food):
    """상호에 샌드위치가 있거나, 샌드위치·파니니 메뉴가 2개 이상이면서 음식 메뉴의 30% 이상"""
    if SANDWICH.search(name or ""):
        return True
    n = sum(1 for x in {menu_key(x) for x in names if x} if SANDWICH.search(x))   # food 와 같은 기준 — 같은 메뉴는 한 번
    return n >= 2 and n / max(food, 1) >= 0.3


def gate_products(leaf, product):
    """떡집 잎의 '보자기·숫자·2단 케이크'는 떡케이크라 빵 증거에서 뺀다"""
    if leaf in RICE_CAKE_LEAVES:
        return [x for x in product if not re.search(r"케이?크|케익", x)]
    return product


def menu_evidence(leaf, product, food):
    """제품 빵이 메뉴에서 차지하는 몫 — 1개라도 음식 메뉴의 30% 이상이거나, 3개 이상이면서 15% 이상"""
    p = len(gate_products(leaf, product))
    share = p / food if food else 0.0
    return (p >= 1 and share >= 0.3) or (p >= 3 and share >= 0.15)


def gate_g1(leaf, n_menu, product, food, sandwich, group=""):
    """반환: PASS | BLOCK | HOLD(사람 검수)"""
    if category_pass(leaf):
        return "PASS"
    p = len(gate_products(leaf, product))
    if any(h in leaf for h in HARD_NON_BAKERY) or group in HARD_GROUPS:
        return "HOLD" if (p >= 3 and p / max(food, 1) >= 0.1) or sandwich else "BLOCK"
    if menu_evidence(leaf, product, food):
        return "PASS"
    soft = sandwich or any(h in leaf for h in SOFT_LEAVES)
    return "HOLD" if n_menu == 0 or p >= 1 or soft else "BLOCK"


def gate(category_path, name, menus):
    """후보 하나 → (판정, 이유). menus 가 None 이면 메뉴를 아직 안 긁은 것 — 카테고리로 못 가르면 NEED_MENU."""
    leaf = leaf_of(category_path)
    chain = next((c for c in NON_BAKERY_CHAINS if c in (name or "").replace(" ", "")), None)
    if chain:
        return "BLOCK", f"G1 BLOCK — 비빵 체인 '{chain}'"
    if category_pass(leaf):
        return "PASS", f"G1 잎 '{leaf or '카페,디저트'}'"
    if menus is None:
        return "NEED_MENU", f"잎 '{leaf}' — 메뉴 확인 필요(localdata_bread_check)"
    product, street, food = bread_evidence(menus)
    sandwich = is_sandwich_shop(name, menus, food)
    verdict = gate_g1(leaf, len(menus), product, food, sandwich, group_of(category_path))
    evidence = f"잎 '{leaf}' · 메뉴 {len(menus)} · 음식 {food} · 제품빵 {len(gate_products(leaf, product))} · 길거리 {len(street)}" \
        + (" · 샌드위치" if sandwich else "")
    return verdict, f"G1 {verdict} — {evidence}"
