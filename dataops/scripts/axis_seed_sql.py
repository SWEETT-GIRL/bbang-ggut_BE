# -*- coding: utf-8 -*-
"""로컬 menu_kind_axis → 본 앱 menu_category.axis_* 시드 SQL + 검수 TSV 생성 (BE Flyway 인계용).

운영 menu_category 에 이미 axis_* 컬럼이 있으므로(BE 09-08 배포) UPDATE 만 낸다.
운영에 없는 종류는 건너뛰고, 운영에만 있는 종류는 경고로 남긴다.
사용: python scripts/axis_seed_sql.py --env ../bbang-ggut_dataops_BE-localdata/.env --out <경로.sql> --tsv <경로.tsv>
"""
import argparse
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from store_profile_batch import POLE_LABEL, TYPE_NAMES, type_code_of_number  # noqa: E402  — 타입 이름·극 이름은 배치와 같은 정본을 쓴다
from store_tags import local_csv, prod_csv  # noqa: E402

TYPE_LABEL = TYPE_NAMES
POLES = (POLE_LABEL["taste"], POLE_LABEL["texture"], POLE_LABEL["filling"], POLE_LABEL["vibe"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tsv")
    args = ap.parse_args()

    rows = local_csv("SELECT kind, kind_group, axis_taste, axis_texture, axis_filling, axis_vibe, bbti_type, confidence, is_bread, memo "
                     "FROM menu_kind_axis ORDER BY (axis_taste IS NULL), confidence DESC, kind;")
    prod_kinds = {r["name"] for r in prod_csv("SELECT name FROM menu_category;", args.env)}
    seeded = [r for r in rows if r["axis_taste"] != "" and r["kind"] in prod_kinds]
    missing_prod = [r["kind"] for r in rows if r["kind"] not in prod_kinds]
    only_prod = sorted(prod_kinds - {r["kind"] for r in rows})

    lines = [
        "-- 본 앱 menu_category 빵BTI 4축 시드 (값: dataops, 마이그레이션: BE) — 설계 v2 §4",
        "-- 규약: -1 = A극(달콤·부드·풍부·고전) / +1 = B극(담백·바삭·심플·신상) / NULL = 축 없음(빵·기타·요거트)",
        f"-- 대상 {len(seeded)}종 (매핑표 draft v2, 2026-09-09). 컬럼은 V20260908162421 에서 이미 추가됨 — UPDATE 만 한다.",
        "-- 확신도 '낮음' 행은 팀 검수 대상: 같은 SQL 을 다시 돌리면 되므로 값 수정 후 재배포 가능.",
        "",
    ]
    for r in seeded:
        axes = [int(r["axis_taste"]), int(r["axis_texture"]), int(r["axis_filling"]), int(r["axis_vibe"])]
        words = "·".join(POLES[i][0] if v < 0 else POLES[i][1] for i, v in enumerate(axes))
        t = type_code_of_number(r["bbti_type"])   # menu_kind_axis 는 아직 숫자 — 표시만 4글자로
        note = f"{words} → {t} {TYPE_LABEL.get(t, '')}".strip() + f" · {r['confidence']}" + ("" if r["is_bread"] == "t" else " · 빵 아님")
        lines.append(f"UPDATE menu_category SET axis_taste = {axes[0]:+d}, axis_texture = {axes[1]:+d}, "
                     f"axis_filling = {axes[2]:+d}, axis_vibe = {axes[3]:+d} WHERE name = '{r['kind']}';  -- {note}")
    lines.append("")
    if missing_prod:
        lines.append("-- 운영 menu_category 에 없는 매핑표 종류(건너뜀): " + ", ".join(missing_prod))
    if only_prod:
        lines.append("-- 매핑표에 없는 운영 종류(축 NULL 유지): " + ", ".join(only_prod))
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    open(args.out, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print(f"[axis-seed] SQL {args.out} — UPDATE {len(seeded)}행 · 운영에 없는 종류 {len(missing_prod)} · 매핑 없는 운영 종류 {len(only_prod)}")
    if only_prod:
        print("  매핑 없는 운영 종류:", ", ".join(only_prod))

    if args.tsv:
        with open(args.tsv, "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f, delimiter="\t")
            w.writerow(["kind", "kind_group", "taste", "texture", "filling", "vibe", "축(말)", "type", "type_label", "confidence", "is_bread", "memo"])
            for r in rows:
                if r["axis_taste"] == "":
                    w.writerow([r["kind"], r["kind_group"], "", "", "", "", "축 없음", "", "", r["confidence"], r["is_bread"], r["memo"]])
                    continue
                axes = [int(r["axis_taste"]), int(r["axis_texture"]), int(r["axis_filling"]), int(r["axis_vibe"])]
                t = type_code_of_number(r["bbti_type"])
                w.writerow([r["kind"], r["kind_group"], *axes, "·".join(POLES[i][0] if v < 0 else POLES[i][1] for i, v in enumerate(axes)),
                            t or "", TYPE_LABEL.get(t, ""), r["confidence"], r["is_bread"], r["memo"]])
        print(f"[axis-seed] 검수 TSV {args.tsv} — {len(rows)}행")


if __name__ == "__main__":
    main()
