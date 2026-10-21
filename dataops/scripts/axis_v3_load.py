# -*- coding: utf-8 -*-
"""축 v3 라벨 정본(axis_v3_labels.LABELS) → 로컬 menu_kind_label 적재 + 검수표(TSV) 재생성.

검수를 돌 때마다 손으로 SQL 을 쓰면 표와 정본이 어긋난다 (실제로 한 번 어긋났다).
정본은 파이썬 파일 하나이고, 표와 로컬 테이블은 여기서만 만든다.

사용: python scripts/axis_v3_load.py --env ../bbang-ggut_dataops_BE-localdata/.env
      python scripts/axis_v3_load.py --no-prod      # 운영 조회 없이 적재만 (터널 없을 때)
"""
import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axis_v3_labels import LABELS, labels_of, summary_axes  # noqa: E402
from store_tags import local_csv, prod_csv  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TSV = os.path.join(ROOT, "matching", "menu_kind_label_v3_draft.tsv")
POLE = {"filling": {-1: "풍부", 1: "심플", 0: "중간"}, "vibe": {-1: "클래식", 1: "트렌디", 0: "중간"},
        "taste": {-1: "달콤", 1: "담백", 0: "중간"}, "texture": {-1: "부드", 1: "바삭", 0: "중간"}}


def load_local():
    rows = []
    for kind, v in LABELS.items():
        sweet, plain, salty, aroma, soft, crisp, chewy, filling, vibe, conf, memo = v
        st, sx, _, _ = summary_axes(v)
        rows.append("({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})".format(
            "'" + kind.replace("'", "''") + "'", sweet, plain, salty, aroma, soft, crisp, chewy,
            filling, vibe, st, sx, "'" + conf + "'", "'" + memo.replace("'", "''") + "'"))
    sql = ("BEGIN;\nDELETE FROM menu_kind_label;\n"
           "INSERT INTO menu_kind_label (kind, taste_sweet, taste_plain, taste_salty, taste_aroma,"
           " tex_soft, tex_crisp, tex_chewy, axis_filling, axis_vibe, sum_taste, sum_texture, confidence, memo)\n"
           "VALUES\n  " + ",\n  ".join(rows) + ";\nCOMMIT;\n")
    r = subprocess.run(["docker", "exec", "-i", "bbanggut-dataops-db", "psql", "-U", "dataops", "-d", "dataops",
                        "-v", "ON_ERROR_STOP=1", "-At"], capture_output=True, text=True, input=sql)
    if r.returncode != 0:
        raise SystemExit("적재 실패: " + r.stderr.strip()[:400])
    print(f"menu_kind_label 재적재 {len(rows)}종")


def write_tsv(env):
    meta = {r["kind"]: r for r in local_csv(
        "SELECT kind, kind_group, is_bread, axis_taste, axis_texture, axis_filling, axis_vibe FROM menu_kind_axis;")}
    counts, samples = {}, {}
    if env:
        for r in prod_csv("SELECT mc.name AS kind, COUNT(*) AS c FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
                          "WHERE m.deleted_at IS NULL GROUP BY 1;", env):
            counts[r["kind"]] = int(r["c"])
        # 대표 메뉴 — 종류마다 실제로 무엇이 들어 있는지 눈으로 보라고 (분류 오류도 여기서 잡힌다)
        for r in prod_csv("SELECT kind, name, c FROM (SELECT mc.name AS kind, m.name, COUNT(*) AS c, "
                          "ROW_NUMBER() OVER (PARTITION BY mc.name ORDER BY COUNT(*) DESC) rn "
                          "FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
                          "WHERE m.deleted_at IS NULL GROUP BY 1, 2) t WHERE rn <= 5 ORDER BY kind, c DESC;", env):
            samples.setdefault(r["kind"], []).append(f"{r['name']}({r['c']})")
    head = ["kind", "메뉴수", "대표 메뉴", "맛", "식감", "속", "분위기", "확신도", "요약맛", "요약식감",
            "기존4축", "바뀜", "대분류", "빵", "메모"]
    lines = ["\t".join(head)]
    changed = 0
    for kind, v in sorted(LABELS.items(), key=lambda kv: -counts.get(kv[0], 0)):
        taste, tex = labels_of(v)
        st, sx, fill, vibe = summary_axes(v)
        m = meta.get(kind, {})
        old = ""
        if m.get("axis_taste") not in (None, ""):
            old = "·".join([POLE["taste"][int(m["axis_taste"])], POLE["texture"][int(m["axis_texture"])],
                            POLE["filling"][int(m["axis_filling"])], POLE["vibe"][int(m["axis_vibe"])]])
        new = "·".join([POLE["taste"][st], POLE["texture"][sx], POLE["filling"][fill], POLE["vibe"][vibe]])
        diff = "예" if old and old != new else ""
        changed += diff == "예"
        lines.append("\t".join([kind, str(counts.get(kind, "")), " | ".join(samples.get(kind, [])),
                                taste, tex, POLE["filling"][fill], POLE["vibe"][vibe], v[9],
                                POLE["taste"][st], POLE["texture"][sx], old, diff,
                                m.get("kind_group", ""), "" if m.get("is_bread") == "t" else "아님", v[10]]))
    open(TSV, "w", encoding="utf-8-sig", newline="\n").write("\n".join(lines) + "\n")
    print(f"검수표 {os.path.relpath(TSV, ROOT)} — {len(LABELS)}종 · 요약축 바뀜 {changed}종")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default="../bbang-ggut_dataops_BE-localdata/.env")
    ap.add_argument("--no-prod", action="store_true", help="운영 조회 생략 (메뉴수·대표 메뉴 빈칸)")
    a = ap.parse_args()
    load_local()
    write_tsv(None if a.no_prod else a.env)


if __name__ == "__main__":
    main()
