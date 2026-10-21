"""페르소나 × 상권 후보 비교 — 설계 §5.3 후보 점수(코사인 + 0.5·태그 + 0.3·prior)에 '종류 겹침' 항을 더했을 때 상위 15곳이 어떻게 바뀌는지.

- 가게: store_profile_draft(축·태그) + 운영 분류 메뉴 종류 + store_signal 리뷰 언급 메뉴 (로컬·운영 reader)
- 페르소나: 축 벡터 + 태그 + 좋아하는 종류(가중)
- 겹침 = Σ 사용자 종류 가중 × [그 종류가 가게 분류 메뉴 또는 리뷰 언급 메뉴에 있음] / Σ 가중  (0~1)
- prior 는 아직 없으므로 설계의 COALESCE 기본값 0.1 로 고정 (순위에 영향 없음)

사용
    python scripts/persona_check.py --env ..\bbang-ggut_dataops_BE-localdata\.env --area 성수동 --persona 소금빵러버
"""
import argparse
import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_profile_batch import AXES, kind_of_label, load_axis_table  # noqa: E402
from store_tags import local_csv, prod_csv  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PERSONAS = {
    "소금빵러버": {"axis": {"taste": 0.8, "texture": 0.8, "filling": 0.7, "vibe": 0.5}, "tags": {"테이크아웃": 0.6, "동네빵집": 0.6},
               "kinds": {"소금빵": 1.0, "치아바타": 0.5, "베이글": 0.5, "바게트": 0.4}},
    "케이크러버": {"axis": {"taste": -0.8, "texture": -0.7, "filling": -0.6, "vibe": -0.2}, "tags": {"카페형": 0.6, "선물": 0.5},
               "kinds": {"케이크": 1.0, "티라미수": 0.5, "타르트": 0.4}},
    "구움과자": {"axis": {"taste": -0.6, "texture": 0.2, "filling": 0.6, "vibe": -0.5}, "tags": {"선물": 0.6, "테이크아웃": 0.5},
             "kinds": {"휘낭시에": 1.0, "마들렌": 0.8, "까눌레": 0.6, "스콘": 0.5}},
}
SPOT_PREFIX_UNUSED = {"성수동": "^서울", "연남동": "^서울", "망원동": "^서울", "서교동": "^서울", "한남동": "^서울", "공덕동": "^서울",
               "도담동": "^세종", "조치원읍": "^세종", "강릉시": "^강원", "춘천시": "^강원", "속초시": "^강원", "전주시": "^(전라북도|전북)", "군산시": "^(전라북도|전북)"}


def cosine(a, b):
    dot = sum(a[x] * b[x] for x in AXES)
    na, nb = math.sqrt(sum(a[x] ** 2 for x in AXES)), math.sqrt(sum(b[x] ** 2 for x in AXES))
    return dot / (na * nb) if na and nb else 0.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--area", default="성수동")
    parser.add_argument("--persona", default="소금빵러버", choices=list(PERSONAS))
    parser.add_argument("--overlap-weight", type=float, default=0.4)
    parser.add_argument("--min-bakery", type=float, default=0.0, help="빵집다움 점수 하한 (0 = 필터 없음)")
    args = parser.parse_args()
    persona = PERSONAS[args.persona]

    # 상권 = 운영 store.region_3rd(동) 또는 region_2nd(시군구) — 전체 가게 기준 (scratch 캐시는 09-04 스냅샷 18,938곳뿐)
    area_rows = prod_csv(f"SELECT id AS store_id, name FROM store WHERE status = 'ACTIVE' AND (region_3rd ~ '^{args.area}' OR region_2nd = '{args.area}');", args.env)
    names = {r["store_id"]: r["name"] for r in area_rows}
    ids = list(names)
    stores = local_csv(
        f"SELECT p.store_id, p.axis_taste, p.axis_texture, p.axis_filling, p.axis_vibe, p.bbti_type, p.tags::text AS tags, p.signature_menus::text AS sig, p.bakery_score "
        f"FROM store_profile_draft p WHERE p.store_id IN ({','.join(ids)}) AND p.store_status = 'ACTIVE';")
    ids = [s["store_id"] for s in stores]
    menu_kinds = defaultdict(set)
    for r in prod_csv(f"SELECT DISTINCT m.store_id, mc.name AS kind FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
                      f"WHERE m.deleted_at IS NULL AND m.store_id IN ({','.join(ids)});", args.env):
        menu_kinds[r["store_id"]].add(r["kind"])
    _, kinds_by_len = load_axis_table()
    review_kinds = defaultdict(set)
    for r in local_csv(f"SELECT store_id, (signals->'reviewMenus')::text AS rm FROM store_signal WHERE store_id IN ({','.join(ids)});"):
        for m in json.loads(r["rm"]) or []:
            k = kind_of_label(m["label"], kinds_by_len)
            if k:
                review_kinds[r["store_id"]].add(k)

    rows = []
    for s in stores:
        if s["axis_taste"] == "":
            continue
        axis = {a: float(s[f"axis_{a}"]) for a in AXES}
        tags = json.loads(s["tags"])
        cos = cosine(persona["axis"], axis)
        tag_dot = sum(w * tags.get(t, 0.0) for t, w in persona["tags"].items()) / sum(persona["tags"].values())
        kinds_here = menu_kinds[s["store_id"]] | review_kinds[s["store_id"]]
        overlap = sum(w for k, w in persona["kinds"].items() if k in kinds_here) / sum(persona["kinds"].values())
        matched = [k for k in persona["kinds"] if k in kinds_here]
        bakery = float(s["bakery_score"]) if s["bakery_score"] not in ("", None) else 0.5
        if bakery < args.min_bakery:
            continue  # 빵집다움 필터 (설계 §5.3 수정안 ①)
        base = cos + 0.5 * tag_dot + 0.3 * 0.1
        rows.append({"id": s["store_id"], "name": names.get(s["store_id"], s["store_id"]), "type": s["bbti_type"], "cos": cos, "tag": tag_dot, "bakery": bakery,
                     "overlap": overlap, "matched": matched, "base": base, "plus": base + args.overlap_weight * overlap,
                     "sig": (s["sig"] or "").strip("{}").replace('"', "")[:60]})
    print(f"{args.area} · 후보 {len(rows)}곳 (ACTIVE·축 있음·빵집다움≥{args.min_bakery}) · 페르소나 {args.persona} (축 {persona['axis']}, 종류 {persona['kinds']})\n")
    rank_a = sorted(rows, key=lambda r: -r["base"])[:15]
    rank_b = sorted(rows, key=lambda r: -r["plus"])[:15]
    pos_a = {r["id"]: i + 1 for i, r in enumerate(sorted(rows, key=lambda r: -r["base"]))}
    print(f"{'#':>2} {'A 설계 그대로 (코사인+태그+prior)':46} {'타입':>4} {'cos':>5} | {'#':>2} {'B +0.4·종류 겹침':46} {'타입':>4} {'cos':>5} {'겹침':>4} 겹치는 종류 / A순위")
    for i in range(15):
        a = rank_a[i] if i < len(rank_a) else None
        b = rank_b[i] if i < len(rank_b) else None
        left = f"{i+1:>2} {a['name'][:22]:24} {a['type'] or '-':>4} {a['cos']:+.2f}" if a else ""
        right = f"{i+1:>2} {b['name'][:22]:24} {b['type'] or '-':>4} {b['cos']:+.2f} {b['overlap']:.2f} {','.join(b['matched']) or '-'} / A{pos_a[b['id']]}" if b else ""
        mark = " ◀ 상위4" if i < 4 else ""
        print(f"{left:60} | {right}{mark}")
    top4_a, top4_b = {r["id"] for r in rank_a[:4]}, {r["id"] for r in rank_b[:4]}
    print(f"\n상위 4곳 변화: A {[r['name'][:12] for r in rank_a[:4]]} → B {[r['name'][:12] for r in rank_b[:4]]} (교체 {len(top4_b - top4_a)}곳)")
    print("A 상위 4의 겹침:", [f"{r['name'][:10]} {r['overlap']:.2f}" for r in rank_a[:4]], "| B 상위 4의 겹침:", [f"{r['name'][:10]} {r['overlap']:.2f}" for r in rank_b[:4]])
    print("이 상권에서 페르소나 종류를 하나라도 파는 가게:", sum(1 for r in rows if r["overlap"] > 0), "/", len(rows))


if __name__ == "__main__":
    main()
