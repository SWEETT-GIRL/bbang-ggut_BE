"""MenuKind enum 추가 후보 — '빵'·'기타' 버킷에 남은 메뉴 이름을 후보 끝단어로 세어, 본 앱(bbang-ggut_BE) enum 결정용 문서를 만든다. 읽기 전용.

후보마다: 회복 건수(운영 메뉴, ACTIVE 가게), 가게 수, 이름 변형 상위, 분류기에 넣을 별칭, 제안 축(빵BTI 4축·타입·확신도).
2차 재분류 규모 = 후보 합. 축 제안은 매핑 초안 v1 의 관행(달콤/부드/풍부/고전 = −1)을 따른 초안이며 팀 검수 대상.

사용
    python scripts/enum_candidates.py --env ..\bbang-ggut_dataops_BE-localdata\.env
"""
import argparse
import csv
import datetime as dt
import io
import os
import subprocess
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from menu_classifier import classify  # noqa: E402  (같은 끝단어 규칙 — 2026-09-19 menu_reclassify 에서 옮겨 옴)
from menu_reclassify import prod_csv  # noqa: E402  (운영 읽기)
from store_profile_batch import TYPE_NAMES, type_code_of_number  # noqa: E402  (타입 이름은 배치 정본, 4글자 코드)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (후보 kind, 끝단어 별칭, 제안 축 [맛, 식감, 속, 분위기], 확신도, 메모)  — 축: −1 = 달콤/부드/풍부/고전, +1 = 담백/바삭/심플/신상
CANDIDATES = [
    ("단팥빵", ["단팥빵", "팥빵", "앙금빵", "앙금브레드"], (-1, -1, -1, -1), "높음", "통팥·씨앗통·완두앙금 변형 포함. 1번 대표 빵"),
    ("카스테라", ["카스테라", "카스텔라"], (-1, -1, +1, -1), "높음", "밀봉·정통 카스테라. 3번(휘낭시에·마들렌과 같은 자리)"),
    ("모닝빵", ["모닝빵"], (+1, -1, +1, -1), "중간", "11번(식빵과 같은 자리). 크림가득모닝빵은 풍부(9) — 변형이 많아 낮음 가능"),
    ("핫도그", ["핫도그", "콘도그"], (+1, +1, -1, +1), "낮음", "찹쌀핫도그·치즈핫도그. 튀김이라 바삭·소시지라 풍부. 14번(잠봉뵈르와 같은 자리)"),
    ("호두과자", ["호두과자", "호두빵"], (-1, -1, -1, -1), "중간", "팥 앙금 기준 1번. 답례품 프랜차이즈 메뉴가 많아 가게 단위 축엔 약하게"),
    ("피자빵", ["피자빵"], (+1, -1, -1, -1), "높음", "9번(샌드위치·소세지빵과 같은 조리빵 자리)"),
    ("경주빵", ["경주빵", "황남빵"], (-1, -1, -1, -1), "높음", "팥 앙금 1번. 왕경주빵 포함"),
    ("잠봉뵈르", ["잠봉뵈르", "잠봉버터", "잠봉"], (+1, +1, -1, +1), "중간", "매핑 초안이 14번 후보로 지목. 지금은 '빵'/'기타'에 흩어짐"),
    ("크룽지", ["크룽지"], (-1, +1, +1, +1), "중간", "크루아상 와플 — 8번(크로플·와플과 같은 자리)"),
    ("꿀빵", ["꿀빵"], (-1, -1, -1, -1), "중간", "통영 꿀빵(팥+시럽) 1번"),
    ("메론빵", ["메론빵", "멜론빵"], (-1, +1, +1, -1), "낮음", "겉 쿠키 크러스트 바삭, 속 없음 → SCLO. 신상(SCLN)으로 봐도 됨"),
    ("감자빵", ["감자빵"], (+1, -1, -1, +1), "낮음", "춘천 감자빵 — 10번(버터떡뿐인 자리를 채움)"),
    ("초코소라빵", ["소라빵"], (-1, +1, -1, -1), "중간", "매핑 초안 검토표 그대로 5번"),
    ("옥수수빵", ["옥수수빵"], (-1, -1, -1, -1), "낮음", "옥수수 크림 기준 1번"),
    ("보스톤", ["보스톤", "보스턴"], (-1, -1, -1, -1), "낮음", "보스턴크림(도넛/파이). 프랜차이즈 편중"),
    ("상투과자", ["상투과자"], (-1, +1, -1, -1), "낮음", "빈도 낮아 8-14 보류했던 항목. 5번"),
]
POLE = (("달콤", "담백"), ("부드", "바삭"), ("풍부", "심플"), ("고전", "신상"))


def type_no(axes):
    return 1 + 8 * (axes[0] > 0) + 4 * (axes[1] > 0) + 2 * (axes[2] > 0) + (axes[3] > 0)


def match_candidate(name):
    """남은 이름에 후보 끝단어 적용 — 가장 뒤에서 끝나는 후보 (동률이면 긴 별칭)"""
    n = name.lower()
    best, best_end, best_len = None, -1, -1
    for kind, aliases, *_ in CANDIDATES:
        for a in aliases:
            i = n.rfind(a)
            if i < 0:
                continue
            end = i + len(a)
            if end > best_end or (end == best_end and len(a) > best_len):
                best, best_end, best_len = kind, end, len(a)
    return best


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "docs", "course-reco", "menu_kind_enum_candidates.md"))
    args = parser.parse_args()
    rows = prod_csv(
        "SELECT m.name, m.store_id, mc.name AS bucket FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
        "JOIN store s ON s.id = m.store_id WHERE m.deleted_at IS NULL AND s.status = 'ACTIVE' AND mc.name IN ('빵','기타');", args.env)
    count, stores, variants = Counter(), defaultdict(set), defaultdict(Counter)
    for r in rows:
        kind, _, _ = classify(r["name"])
        if kind not in (None, "빵"):
            continue  # 지금 분류기로 이미 잡히는 건 1차 재분류에서 처리됨
        cand = match_candidate(r["name"])
        if cand:
            count[cand] += 1
            stores[cand].add(r["store_id"])
            variants[cand][r["name"].strip()] += 1
    total = sum(count.values())
    lines = [
        "# MenuKind enum 추가 후보 (본 앱 결정 요청)",
        "",
        f"기준: 2026-09-04 운영 메뉴, ACTIVE 가게의 '빵'·'기타' 버킷 중 **1차 재분류(26,485건) 후에도 남는 이름**을 후보 끝단어로 센 것. "
        f"후보 {len(CANDIDATES)}종 합계 **{total:,}건** — enum 추가·`menu_category` 행 시딩·분류기 별칭 추가 후 2차 재분류로 회복된다.",
        "",
        "축 제안은 매핑 초안 v1 관행(A극 −1 = 달콤·부드·풍부·고전 / B극 +1 = 담백·바삭·심플·신상)을 따른 초안이며 팀 검수 대상. "
        "빵BTI 타입에서 얇은 자리(10 버터떡뿐, 11 식빵·떡뿐, 14 샌드베이글뿐)를 감자빵·모닝빵·핫도그/잠봉뵈르가 채운다.",
        "",
        "| 후보 kind | 메뉴 | 가게 | 분류기 별칭(끝단어) | 제안 축 (맛·식감·속·분위기) | 타입 | 확신도 | 메모 | 이름 변형 상위 |",
        "|---|---:|---:|---|---|---|---|---|---|",
    ]
    print(f"{'kind':8} {'메뉴':>7} {'가게':>6}  타입  변형 상위")
    for kind, aliases, axes, conf, memo in sorted(CANDIDATES, key=lambda c: -count[c[0]]):
        n, s = count[kind], len(stores[kind])
        t = type_code_of_number(type_no(axes))
        axis_label = "·".join(POLE[i][axes[i] > 0] for i in range(4))
        top = ", ".join(f"{v} {c:,}" for v, c in variants[kind].most_common(4))
        lines.append(f"| {kind} | {n:,} | {s:,} | {' · '.join(aliases)} | {axis_label} | {t} {TYPE_NAMES[t]} | {conf} | {memo} | {top} |")
        print(f"{kind:8} {n:>7,} {s:>6,}  {t:>4}  {top[:70]}")
    lines += [
        "",
        "## 적용 순서",
        "",
        "1. 본 앱 `MenuKind` enum + `menu_category` 행 추가 (배포는 새벽 저트래픽, 롤링 중 구서버 valueOf 방지)",
        "2. dataops `MenuCategoryClassifier` 별칭 추가 (위 표) + `menu_kind_axis` 행 추가 (제안 축, 검수 후)",
        "3. `scripts/menu_reclassify.py` 드라이런 → 2차 재분류 (apply_log 건별 기록)",
        "4. 앱 BE `menu_category.axis_*` 인계 SQL 에 새 행 반영",
        "",
        "## 같이 결정할 것",
        "",
        "- 호두과자·보스톤은 답례품·프랜차이즈 메뉴가 많다 — kind 는 추가하되 가게 축 계산에서 가중을 낮출지",
        "- 모닝빵 변형(땅콩크림가득모닝빵·생크림가득모닝빵 ≈400)은 속이 풍부(9)라 별칭을 '크림모닝빵' 으로 갈라 잡을지",
        "- 상투과자·옥수수빵·보스톤은 빈도가 낮다 — 이번에 넣을지 보류할지",
        "",
        f"_생성: `scripts/enum_candidates.py`, {dt.date.today()}_",
    ]
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\n합계 {total:,}건 → {args.out}")


if __name__ == "__main__":
    main()
