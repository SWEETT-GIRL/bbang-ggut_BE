"""MenuCategoryClassifier.java 의 파이썬 판 — 메뉴 이름 → 종류(menu_category.name). 사전은 Java 파일에서 직접 읽는다.

2026-09-19 (dataops #11): menu_reclassify.py 가 사전을 손으로 베껴 두고 있었는데, 09-07·09-08 에 Java 에 더한 24종(단팥빵·카스테라·
한과·초콜릿 …)과 별칭 넷(시루·설빙·샌드베이들·맘모스롤)이 빠져 있었다. 베낀 사본은 다시 어긋나므로 사본을 없애고 Java 를 읽는다.
판정 규칙도 Java 와 같다 — 소문자화 · 괄호 안 제거 · 끝나는 위치가 가장 뒤인 단어, 같으면 긴 단어(에그타르트 > 타르트).
매핑이 없으면 None 이다(Java 는 09-08 에 '빵' 그물을 없앴다 — "버섯 크림 리조또" 까지 빵에 갇혔다).
판정을 믿기 어려운 경우(doubt)도 Java 가 같은 규칙으로 옮겨 받았다(2026-09-19) — Java classify 는 doubt 가 걸리면 null 을 주고
메뉴는 미분류 큐로 간다. 한쪽을 고치면 다른 쪽(MenuCategoryClassifier.doubt)도 고칠 것.
"""
import os
import re

JAVA_CLASSIFIER = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "..", "batch",
                               "src", "main", "java", "sweetgirl", "dataops", "reconcile", "policy", "MenuCategoryClassifier.java")
PAREN = re.compile(r"\([^()]*\)")
GENERIC_KINDS = {"빵", "기타", "요거트"}   # 축 시드 규약의 "축 없음" — 무엇인지 말해 주지 않는 종류 (BE top_kinds 도 뺀다)


def load_category_end_words(path=JAVA_CLASSIFIER):
    """Java buildMapping() 의 mapping.put("종류", List.of("단어", …)) 를 순서대로 읽는다."""
    src = open(path, encoding="utf-8").read()
    pairs = [(kind, re.findall(r'"([^"]+)"', words))
             for kind, words in re.findall(r'mapping\.put\("([^"]+)",\s*List\.of\(([^)]*)\)\)', src)]
    if len(pairs) < 50:
        raise SystemExit(f"{path}: 사전을 {len(pairs)}종밖에 못 읽었다 — Java 모양이 바뀌었는지 확인할 것")
    return pairs


CATEGORY_END_WORDS = load_category_end_words()


def classify(menu_name):
    """MenuCategoryClassifier.match 와 같은 판정 — (종류 또는 None, 종류 단어가 끝나는 위치, 걸린 단어).
    Java classify 는 여기에 doubt 가 걸리면 null 이다 — 같은 값을 보려면 doubt(...) 이 비었는지도 볼 것."""
    if menu_name is None:
        return None, -1, None
    name = PAREN.sub(" ", menu_name.lower())
    best, best_end, best_word = None, -1, None
    for kind, words in CATEGORY_END_WORDS:
        for word in words:
            index = name.rfind(word)
            if index < 0:
                continue
            end = index + len(word)
            if end > best_end or (end == best_end and len(word) > len(best_word)):
                best, best_end, best_word = kind, end, word
    return best, best_end, best_word


def weak_match(word):
    """걸린 단어가 다른 말의 일부이기 쉬운가 — 한 글자(슈·번·롤·떡)와 '샌드'. 끝단어만 보면 이렇게 틀린다:
    키슈→슈 · 1번 세트→번 · 캐롤→롤 · 떡갈비→떡 · 버터샌드→샌드위치."""
    return word is not None and (len(word) == 1 or word == "샌드")


# 끝단어 판정을 믿기 어려운 경우 — 2차 재분류 plan 표본(2026-09-19)에서 나온 것. Java(MenuCategoryClassifier)에도 같은 식이 있다.
HOT_ICE_CHOCOLATE = re.compile(r"(핫|아이스|hot|ice)\s*초(코|콜)", re.I)       # 핫초콜릿·아이스 초콜렛 — 음료
MEAL_OMELETTE = re.compile(r"브런치|플레이트|플래터|베이컨|그레이비|라이스|밥|샐러드")   # 달걀 요리 오믈렛 (빵집 오믈렛은 딸기오믈렛 같은 과자)
QUANTITY_TAIL = re.compile(r"[\s\d]*(개입|개|입|구|pcs|ea|p|조각|마리|알|봉|세트|set)?\s*$", re.I)
FILLING_KINDS = {"떡", "슈", "파이", "한과", "초콜릿"}   # 빵 이름 앞에 오면 그 종류가 아니라 속재료·맛이다(인절미빵·슈크림빵·파이앙꼬빵)


def doubt(menu_name, kind, word):
    """분류 결과를 믿기 어려운 이유(없으면 ''). 재분류는 검토로 돌리고, 대표 메뉴 종류 풀이는 버린다."""
    if kind is None:
        return ""
    if weak_match(word):
        return f"짧은 단어 '{word}'"
    name = PAREN.sub(" ", (menu_name or "").lower())
    if kind == "한과" and "닭강정" in name:
        return "닭강정"
    if kind == "초콜릿" and HOT_ICE_CHOCOLATE.search(name):
        return "초콜릿 음료"
    if kind == "오믈렛" and MEAL_OMELETTE.search(name):
        return "식사 오믈렛"
    # "인절미빵"·"슈크림찐빵"·"백설기빵": 끝말이 ○○빵인데 종류 단어가 그 앞(속재료)에서 끝난다. 09-08 에 '빵' 그물을 없앤 뒤
    # 종류 목록에 없는 빵은 속재료 단어가 이긴다 — 인절미빵이 떡, 슈크림찐빵이 슈가 된다. 띄어쓰기만 지워도 판정이 바뀌는 것
    # ("바닐라 슈크림 빵" 은 슈, "바닐라슈크림빵" 은 크림빵)도 같은 이유로 믿지 않는다.
    # 단, 종류 단어 바로 뒤에 빵이 붙은 것(초코소보로빵·카라멜데니시빵)은 그 종류다 — 속재료 종류(FILLING_KINDS)만 빼고.
    core = QUANTITY_TAIL.sub("", name).replace(" ", "")
    if core.endswith("빵"):
        core_kind, core_end, _ = classify(core)
        kind_then_bread = core_end == len(core) - 1 and core_kind == kind and kind not in FILLING_KINDS
        if not kind_then_bread and (core_end < len(core) or core_kind != kind):
            return "끝말이 ○○빵(종류 단어는 속재료)"
    return ""
