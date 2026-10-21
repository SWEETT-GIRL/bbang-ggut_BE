"""타입 지형 시뮬레이션 — enum 후보(scripts/enum_candidates.CANDIDATES)를 제안 축으로 넣고 2차 재분류가 끝났다고 가정해
가게 축·타입을 다시 계산하고, 타입별 "지금 → 추가 후" 가게 수와 그 타입에 분포하는 메뉴 종류(대표 빵 후보)를 뽑는다. 읽기 전용.

계산은 store_profile_batch 와 같다(축 = 0.6·운영 분류 메뉴 + 0.4·리뷰 언급 메뉴, 메뉴 3개 미만이면 리뷰만). 모집단 = store_signal 이 있는 가게.
- 지금: menu_kind_axis(64종) 기준
- 추가 후: 후보 종류를 축 표에 더하고, '빵'·'기타'에 남은 메뉴 이름이 후보 끝단어에 맞으면 그 종류로 간주(2차 재분류), 리뷰 언급 라벨도 후보로 풀림

사용
    python scripts/type_projection.py --env ..\bbang-ggut_dataops_BE-localdata\.env
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from enum_candidates import CANDIDATES, match_candidate  # noqa: E402
from menu_classifier import classify  # noqa: E402  (2026-09-19 menu_reclassify 에서 옮겨 옴)
from store_profile_batch import AXES, MIN_MENUS, TYPE_CODES, TYPE_NAMES, combine, kind_of_label, load_axis_table, type_of, weighted  # noqa: E402
from store_tags import local_csv, prod_csv  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def type_code(axes):
    """±1 벡터(종류 하나)의 4글자 코드 — 가게 축과 같은 규칙"""
    return type_of(axes)[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--out", default=os.path.join(REPO_ROOT, "docs", "course-reco", "type_projection.md"))
    parser.add_argument("--override", action="append", default=[], help="종류=축값(예: 베이글=+1,-1,+1,+1) — 매핑 변경 시나리오")
    args = parser.parse_args()
    overrides = {}
    for o in args.override:
        kind, vals = o.split("=")
        overrides[kind] = dict(zip(AXES, (int(v) for v in vals.split(","))))

    axis_now, _ = load_axis_table()
    axis_proj = dict(axis_now)
    for kind, _aliases, axes, *_ in CANDIDATES:
        axis_proj[kind] = dict(zip(AXES, axes))
    axis_proj.update(overrides)
    if overrides:
        print("매핑 변경 시나리오:", overrides)
    kinds_now = sorted(axis_now, key=len, reverse=True)
    kinds_proj = sorted(axis_proj, key=len, reverse=True)

    ids = [r["store_id"] for r in local_csv("SELECT store_id FROM store_signal;")]
    id_set = set(ids)
    reviews = {r["store_id"]: json.loads(r["rm"]) for r in local_csv("SELECT store_id, (signals->'reviewMenus')::text AS rm FROM store_signal;")}
    menus = prod_csv("SELECT m.store_id, m.name, mc.name AS kind, COALESCE(m.like_count,0) AS likes FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
                     "JOIN store s ON s.id = m.store_id WHERE m.deleted_at IS NULL AND s.status = 'ACTIVE';", args.env)
    print(f"모집단 {len(ids):,}곳 · 운영 메뉴 {len(menus):,}건")

    mv_now, mv_proj = defaultdict(list), defaultdict(list)
    kind_menu_now, kind_menu_proj = Counter(), Counter()
    added = Counter()
    for m in menus:
        sid = m["store_id"]
        if sid not in id_set:
            continue
        w = 1 + int(m["likes"])
        kind = m["kind"]
        if kind in axis_now:
            mv_now[sid].append((axis_now[kind], w))
            mv_proj[sid].append((axis_proj[kind], w))
            kind_menu_now[kind] += 1
            kind_menu_proj[kind] += 1
        elif kind in ("빵", "기타"):
            cur, _, _ = classify(m["name"])
            if cur not in (None, "빵"):
                continue  # 1차 재분류 대상(이미 반영됐거나 사용자 등록·음료 제외분)
            cand = match_candidate(m["name"])
            if cand:
                mv_proj[sid].append((axis_proj[cand], w))
                kind_menu_proj[cand] += 1
                added[cand] += 1

    def distribution(mv, axis_table, kinds_by_len):
        types = Counter()
        for sid in ids:
            menu_axis = weighted(mv.get(sid, [])) if len(mv.get(sid, [])) >= MIN_MENUS else None
            rv = []
            for m in reviews.get(sid) or []:
                k = kind_of_label(m["label"], kinds_by_len)
                if k:
                    rv.append((axis_table[k], int(m["count"])))
            axis = combine(menu_axis, weighted(rv))
            code, _ = type_of(axis)
            types[code] += 1
        return types

    dist_now = distribution(mv_now, axis_now, kinds_now)
    dist_proj = distribution(mv_proj, axis_proj, kinds_proj)
    with_axis_now = sum(v for k, v in dist_now.items() if k)
    with_axis_proj = sum(v for k, v in dist_proj.items() if k)

    by_type_now, by_type_proj = defaultdict(list), defaultdict(list)
    for kind, ax in axis_now.items():
        by_type_now[type_code(ax)].append(kind)
    for kind, ax in axis_proj.items():
        by_type_proj[type_code(ax)].append(kind)

    lines = ["# 타입 지형 시뮬레이션 — enum 후보 반영 전/후", "",
             f"모집단: store_signal 보유 {len(ids):,}곳. 축 있는 가게 {with_axis_now:,} → {with_axis_proj:,}. "
             f"2차 재분류 가정 메뉴 {sum(added.values()):,}건 ({', '.join(f'{k} {n:,}' for k, n in added.most_common())}).", "",
             "| 타입 | 지금 | 추가 후 | 변화 | 지금 종류(메뉴 수 순) | 추가되는 종류 |", "|---|---:|---:|---:|---|---|"]
    print(f"\n{'타입':22} {'지금':>6} {'추가후':>6} {'변화':>6}  추가 종류")
    for t in TYPE_CODES:   # 옛 번호 1~16 순서
        now, proj = dist_now.get(t, 0), dist_proj.get(t, 0)
        kinds_sorted = sorted(by_type_now[t], key=lambda k: -kind_menu_proj[k])
        added_kinds = sorted([k for k in by_type_proj[t] if k not in axis_now], key=lambda k: -kind_menu_proj[k])
        now_txt = ", ".join(f"{k}({kind_menu_proj[k]:,})" for k in kinds_sorted[:6]) + (" …" if len(kinds_sorted) > 6 else "")
        add_txt = ", ".join(f"{k}(+{added[k]:,})" for k in added_kinds) or "—"
        lines.append(f"| {t} {TYPE_NAMES[t]} | {now:,} | {proj:,} | {proj - now:+,} | {now_txt or '—'} | {add_txt} |")
        print(f"{t:>4} {TYPE_NAMES[t]:14} {now:>6,} {proj:>6,} {proj - now:>+6,}  {add_txt}")
    lines.append(f"| 축 없음 | {dist_now.get(None, 0):,} | {dist_proj.get(None, 0):,} | {dist_proj.get(None, 0) - dist_now.get(None, 0):+,} | | |")
    print(f"{'축 없음':17} {dist_now.get(None, 0):>6,} {dist_proj.get(None, 0):>6,}")
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\n→ {args.out}")


if __name__ == "__main__":
    main()
