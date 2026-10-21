# -*- coding: utf-8 -*-
"""축 v3(라벨 벡터) vs 현행 4축 — 같은 페르소나·상권에서 후보 상위 4곳이 실제로 좋아지는지 잰다.

가게 벡터는 두 방식 모두 '운영 분류 메뉴 × (1+like_count) 가중 평균'으로 만든다 (store_profile_batch 와 같은 규칙).
  · 현행: menu_kind_axis 의 4축
  · v3  : menu_kind_label 의 맛 4라벨 + 식감 3라벨 + 속·분위기 2축 = 9차원
사용자 벡터는 페르소나 정의를 두 표현으로 각각 적어 둔다 (아래 PERSONAS).
판정은 사람이 한다 — 이 스크립트는 "무엇이 어떻게 달라지는지"만 보여준다.

사용: python scripts/axis_v3_compare.py --env ../bbang-ggut_dataops_BE-localdata/.env --area 성수동 --persona 소금빵러버
"""
import argparse
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_tags import local_csv, prod_csv  # noqa: E402

# 현행 4축 표현 / v3 라벨 표현 — 같은 취향을 두 언어로 적은 것
PERSONAS = {
    "소금빵러버": {
        "old": {"taste": 0.8, "texture": 0.8, "filling": 0.7, "vibe": 0.5},
        "new": {"sweet": 0, "plain": 0.4, "salty": 1.0, "aroma": 0.6, "soft": 0.2, "crisp": 0.8, "chewy": 0.3, "filling": 0.7, "vibe": 0.5},
        "kinds": {"소금빵": 1.0, "치아바타": 0.5, "베이글": 0.5, "바게트": 0.4},
    },
    "케이크러버": {
        "old": {"taste": -0.8, "texture": -0.7, "filling": -0.6, "vibe": -0.2},
        "new": {"sweet": 1.0, "plain": 0, "salty": 0, "aroma": 0.3, "soft": 0.9, "crisp": 0.2, "chewy": 0, "filling": -0.6, "vibe": -0.2},
        "kinds": {"케이크": 1.0, "티라미수": 0.5, "타르트": 0.4},
    },
    "쫄깃러버": {   # v3 에서만 표현 가능한 취향 — 현행 4축으로는 적을 수가 없다
        "old": {"taste": 0.3, "texture": 0.0, "filling": 0.5, "vibe": 0.3},
        "new": {"sweet": 0.2, "plain": 0.6, "salty": 0.2, "aroma": 0.3, "soft": 0.1, "crisp": 0.1, "chewy": 1.0, "filling": 0.5, "vibe": 0.3},
        "kinds": {"베이글": 1.0, "쫀득빵": 0.8, "떡": 0.5, "도넛": 0.4},
    },
    "버터풍미러버": {  # 크루아상·퀸아망·까눌레 — 현행에서는 '담백+바삭'과 '달콤+바삭'으로 흩어진다
        "old": {"taste": 0.2, "texture": 0.9, "filling": 0.6, "vibe": 0.0},
        "new": {"sweet": 0.3, "plain": 0.3, "salty": 0.1, "aroma": 1.0, "soft": 0.1, "crisp": 0.9, "chewy": 0.1, "filling": 0.6, "vibe": 0.0},
        "kinds": {"크루아상": 1.0, "퀸아망": 0.8, "까눌레": 0.6, "페이스트리": 0.5},
    },
}
OLD_AXES = ("taste", "texture", "filling", "vibe")
NEW_DIMS = ("sweet", "plain", "salty", "aroma", "soft", "crisp", "chewy", "filling", "vibe")


def cos(a, b, keys):
    num = sum(a.get(k, 0) * b.get(k, 0) for k in keys)
    na = math.sqrt(sum(a.get(k, 0) ** 2 for k in keys))
    nb = math.sqrt(sum(b.get(k, 0) ** 2 for k in keys))
    return num / (na * nb) if na and nb else 0.0


def store_vectors(env, area):
    """상권 안 ACTIVE 가게의 운영 분류 메뉴로 두 벡터를 만든다 → {store_id: (name, old_vec, new_vec, kinds, menu_count)}"""
    stores = prod_csv(f"SELECT id, name FROM store WHERE status = 'ACTIVE' AND (region_3rd ~ '^{area}' OR region_2nd = '{area}');", env)
    if not stores:
        return {}
    ids = ",".join(r["id"] for r in stores)
    menus = prod_csv(f"SELECT m.store_id, mc.name AS kind, COALESCE(m.like_count, 0) AS likes FROM menu m "
                     f"JOIN menu_category mc ON mc.id = m.menu_category_id "
                     f"WHERE m.store_id IN ({ids}) AND m.deleted_at IS NULL AND mc.name NOT IN ('빵','기타','요거트');", env)
    # 빵 아닌 종류(빙수·아이스크림·떡·요거트·초콜릿)는 가게 벡터에서 뺀다 — store_profile_batch 와 같은 규칙.
    # 안 빼면 빙수 가게가 '달콤·부드·풍부'로 케이크러버 1위가 된다(2026-09-09 실측).
    old_tab = {r["kind"]: {a: int(r["axis_" + a]) for a in OLD_AXES} for r in
               local_csv("SELECT kind, axis_taste, axis_texture, axis_filling, axis_vibe FROM menu_kind_axis WHERE axis_taste IS NOT NULL AND is_bread;")}
    # 중간 변형: 현행 4축이되 속(filling)만 v3 재채점 값으로 — 속 재채점의 기여와 라벨화의 기여를 가르기 위해
    fixed_fill = {r["kind"]: int(r["axis_filling"]) for r in local_csv("SELECT kind, axis_filling FROM menu_kind_label;")}
    mid_tab = {k: {**v, "filling": fixed_fill.get(k, v["filling"])} for k, v in old_tab.items()}
    new_tab = {}
    for r in local_csv("SELECT kind, taste_sweet, taste_plain, taste_salty, taste_aroma, tex_soft, tex_crisp, tex_chewy, axis_filling, axis_vibe FROM menu_kind_label;"):
        new_tab[r["kind"]] = dict(zip(NEW_DIMS, [float(r[c]) for c in
                                 ("taste_sweet", "taste_plain", "taste_salty", "taste_aroma", "tex_soft", "tex_crisp", "tex_chewy", "axis_filling", "axis_vibe")]))
    acc = defaultdict(lambda: {"old": defaultdict(float), "mid": defaultdict(float), "new": defaultdict(float), "w": 0.0, "kinds": defaultdict(float)})
    for m in menus:
        k = m["kind"]
        if k not in old_tab or k not in new_tab:
            continue
        w = 1.0 + int(m["likes"])
        a = acc[m["store_id"]]
        a["w"] += w
        a["kinds"][k] += w
        for ax in OLD_AXES:
            a["old"][ax] += old_tab[k][ax] * w
            a["mid"][ax] += mid_tab[k][ax] * w
        for d in NEW_DIMS:
            a["new"][d] += new_tab[k][d] * w
    names = {r["id"]: r["name"] for r in stores}
    out = {}
    for sid, a in acc.items():
        if a["w"] <= 0:
            continue
        n_menu = len(a["kinds"])
        out[sid] = (names.get(sid, sid), {k: v / a["w"] for k, v in a["old"].items()},
                    {k: v / a["w"] for k, v in a["new"].items()}, dict(a["kinds"]), n_menu,
                    {k: v / a["w"] for k, v in a["mid"].items()})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    ap.add_argument("--area", required=True)
    ap.add_argument("--persona", required=True, choices=list(PERSONAS))
    ap.add_argument("--min-menus", type=int, default=3)
    ap.add_argument("--top", type=int, default=8)
    ap.add_argument("--derive", action="store_true",
                    help="페르소나 벡터를 손으로 쓴 값 대신 '좋아하는 종류'의 가중 평균으로 만든다 — 손으로 쓰면 한쪽에 유리하게 적을 수 있어서")
    args = ap.parse_args()
    p = dict(PERSONAS[args.persona])
    vecs = store_vectors(args.env, args.area)
    if args.derive:
        # 같은 취향 종류 집합에서 두 표현의 사용자 벡터를 각각 만든다 (사람 손이 안 들어감)
        ot = {r["kind"]: {a: int(r["axis_" + a]) for a in OLD_AXES} for r in
              local_csv("SELECT kind, axis_taste, axis_texture, axis_filling, axis_vibe FROM menu_kind_axis WHERE axis_taste IS NOT NULL;")}
        nt = {r["kind"]: dict(zip(NEW_DIMS, [float(r[c]) for c in ("taste_sweet","taste_plain","taste_salty","taste_aroma","tex_soft","tex_crisp","tex_chewy","axis_filling","axis_vibe")]))
              for r in local_csv("SELECT kind, taste_sweet, taste_plain, taste_salty, taste_aroma, tex_soft, tex_crisp, tex_chewy, axis_filling, axis_vibe FROM menu_kind_label;")}
        tot = sum(w for k, w in p["kinds"].items() if k in ot and k in nt)
        p["old"] = {a: sum(ot[k][a] * w for k, w in p["kinds"].items() if k in ot) / tot for a in OLD_AXES}
        p["new"] = {d: sum(nt[k][d] * w for k, w in p["kinds"].items() if k in nt) / tot for d in NEW_DIMS}
        print(f"[derive] 취향 종류 {list(p['kinds'])} 에서 벡터 생성")
    rows = []
    for sid, (name, ov, nv, kinds, n, mv) in vecs.items():
        if n < args.min_menus:
            continue
        top_kinds = ", ".join(k for k, _ in sorted(kinds.items(), key=lambda x: -x[1])[:3])
        rows.append({"id": sid, "name": name, "old": cos(p["old"], ov, OLD_AXES), "mid": cos(p["old"], mv, OLD_AXES), "new": cos(p["new"], nv, NEW_DIMS),
                     "kinds": top_kinds, "overlap": sum(w for k, w in p["kinds"].items() if k in kinds) / sum(p["kinds"].values())})
    if not rows:
        print(f"{args.area}: 후보 없음")
        return
    old_rank = sorted(rows, key=lambda r: -r["old"])[: args.top]
    new_rank = sorted(rows, key=lambda r: -r["new"])[: args.top]
    print(f"\n{args.area} · {args.persona} · 후보 {len(rows)}곳 (분류 메뉴 {args.min_menus}개↑)")
    print(f"{'#':<3}{'현행 4축':<34}{'cos':>6}{'겹침':>6}   {'v3 라벨':<34}{'cos':>6}{'겹침':>6}")
    for i in range(args.top):
        a = old_rank[i] if i < len(old_rank) else None
        b = new_rank[i] if i < len(new_rank) else None
        la = f"{a['name'][:16]:<18}{a['kinds'][:14]:<16}" if a else " " * 34
        lb = f"{b['name'][:16]:<18}{b['kinds'][:14]:<16}" if b else " " * 34
        print(f"{i+1:<3}{la}{a['old']:>+6.2f}{a['overlap']:>6.2f}   {lb}{b['new']:>+6.2f}{b['overlap']:>6.2f}")
    mid_rank = sorted(rows, key=lambda r: -r["mid"])[: args.top]
    o4, n4 = {r["id"] for r in old_rank[:4]}, {r["id"] for r in new_rank[:4]}
    ov4 = sum(r["overlap"] for r in old_rank[:4]) / 4
    mv4 = sum(r["overlap"] for r in mid_rank[:4]) / 4
    nv4 = sum(r["overlap"] for r in new_rank[:4]) / 4
    print(f"상위 4 교체 {len(n4 - o4)}곳 · 상위 4 평균 종류겹침  현행 {ov4:.2f} → 속만 재채점 {mv4:.2f} → v3 라벨 {nv4:.2f}"
          f"  (속 기여 {mv4 - ov4:+.2f} · 라벨 기여 {nv4 - mv4:+.2f})")


if __name__ == "__main__":
    main()
