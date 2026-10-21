"""BeverageFilter.java 의 파이썬 판 — 서비스에 올리지 않는 메뉴(음료·식사·비식품) 판정. 어휘는 Java 파일에서 직접 읽는다.

Java 필터는 새 메뉴를 넣을 때(ReconcileService → menu.add 제안) 쓰인다. 필터가 생기기(2026-08-12~14) 전에 들어온 메뉴는
걸러지지 않은 채 남아 있어, 같은 판정으로 찾아 정리할 때 쓴다(scripts/menu_exclude_cleanup.py). 판정 순서·어휘는 Java 와 같다.
  1. 배민 그룹명 — 여기서는 쓰지 않는다(운영 menu 에 그룹명이 없다)
  2. 용량·테이크아웃 표기 → 제외
  3. 괄호를 뗀 이름에서 마지막 제외어(음료·식사·비식품) vs 디저트어 — 끝 위치가 뒤이거나 같으면 제외
  4. 끝단어 전용 음료·식사 토큰 — 디저트어로 끝나면 통과가 우선, 수량 괄호는 층별로 벗기며 검사
"""
import os
import re

JAVA_FILTER = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "src", "main", "java", "sweetgirl", "dataops", "reconcile", "policy", "BeverageFilter.java")


def _load_lists(path=JAVA_FILTER):
    src = open(path, encoding="utf-8").read()
    lists = {}
    for name in ("BEVERAGE_TYPE", "NON_DESSERT_TYPE", "DESSERT_TYPE", "BEVERAGE_SUFFIX_ONLY", "NON_DESSERT_SUFFIX_ONLY"):
        m = re.search(r"(?<![A-Z_])" + name + r"\s*=\s*List\.of\((.*?)\);", src, re.S)   # DESSERT_TYPE 이 NON_DESSERT_TYPE 에 걸리지 않게
        if not m:
            raise SystemExit(f"{path}: {name} 를 못 읽었다 — Java 모양이 바뀌었는지 확인할 것")
        body = re.sub(r"//[^\n]*", "", m.group(1))   # 줄 주석 안의 따옴표를 어휘로 읽지 않게
        lists[name] = re.findall(r'"([^"]*)"', body)
    return lists


_L = _load_lists()
BEVERAGE_TYPE, NON_DESSERT_TYPE = _L["BEVERAGE_TYPE"], _L["NON_DESSERT_TYPE"]
DESSERT_TYPE, BEVERAGE_SUFFIX_ONLY = _L["DESSERT_TYPE"], _L["BEVERAGE_SUFFIX_ONLY"]
NON_DESSERT_SUFFIX_ONLY = _L["NON_DESSERT_SUFFIX_ONLY"]   # 스프·수프 — 이름 끝에서만 (2026-09-19)
TRAILING_NOISE = " \t.,-_~!/&+*·]"
VOLUME_OR_TAKEOUT = re.compile(r"\d+(\.\d+)?\s*(ml|리터|l\b)|take\s*-?\s*out|테이크\s*아웃")
PAREN_GROUP = re.compile(r"\([^()]*\)")


def _last_end(text, words):
    last = -1
    for w in words:
        i = text.rfind(w)
        if i >= 0 and i + len(w) > last:
            last = i + len(w)
    return last


def _strip_parens(text):
    while "(" in text:
        nxt = PAREN_GROUP.sub(" ", text)
        if nxt == text:
            break
        text = nxt
    return text


def exclusion(menu_name):
    """제외 이유('음료' · '식사·비식품' · '용량·테이크아웃' · '음료(끝단어)' · '식사(끝단어)' · '음료(괄호 안)') 또는 '' —
    제외 여부는 Java isExcluded(name, null) 과 같다(이유 이름만 나눠 둔다)"""
    if menu_name is None:
        return ""
    name = menu_name.lower()
    if VOLUME_OR_TAKEOUT.search(name):
        return "용량·테이크아웃"
    flat = _strip_parens(name)
    beverage, non_dessert = _last_end(flat, BEVERAGE_TYPE), _last_end(flat, NON_DESSERT_TYPE)
    last_excluded = max(beverage, non_dessert)
    if last_excluded >= 0 and last_excluded >= _last_end(flat, DESSERT_TYPE):
        return "식사·비식품" if non_dessert > beverage else "음료"
    remaining = name.strip()
    while remaining:
        tail = remaining.rstrip(TRAILING_NOISE)
        if any(tail.endswith(w) for w in DESSERT_TYPE):
            return ""
        if any(tail.endswith(w) for w in BEVERAGE_SUFFIX_ONLY):
            return "음료(끝단어)"
        if any(tail.endswith(w) for w in NON_DESSERT_SUFFIX_ONLY):
            return "식사(끝단어)"
        if tail.endswith(")"):
            open_at = tail.rfind("(")
            if open_at < 0:
                break
            inner = tail[open_at + 1:-1].strip()
            if not any(inner.endswith(w) for w in DESSERT_TYPE) and any(inner.endswith(w) for w in BEVERAGE_SUFFIX_ONLY):
                return "음료(괄호 안)"   # "(슬림캔)" 같은 단위 — "샌드 카스테라(녹차)" 처럼 맛 괄호도 걸리므로 이유를 따로 둔다
            remaining = tail[:open_at]
            continue
        break
    return ""
