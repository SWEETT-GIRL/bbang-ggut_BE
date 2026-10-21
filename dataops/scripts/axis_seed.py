"""빵BTI 4축 시드 — 매핑 초안(메뉴 종류 67개 → 4축)에서 시드 SQL·검수 TSV를 만들고, 테스트셋 가게의 축 벡터를 눈검사한다.

입력: 매핑 초안 아티팩트 본문을 텍스트로 뽑은 파일(--mapping-text). 표 행 형식:
    N | 메뉴 종류 | 대분류 | ① 맛 | ② 식감 | ③ 속 | ④ 분위기 | 타입 | 확신도 | 메모 |
축 값: A극 −1 / B극 +1 — ①맛 달콤(−1)/담백(+1) ②식감 부드(−1)/바삭(+1) ③속 풍부(−1)/심플(+1) ④분위기 클래식(−1)/트렌디(+1)
타입 번호 = 1 + 8·[담백] + 4·[바삭] + 2·[심플] + 1·[트렌디] (초안 표와 일치)

산출물
- batch/src/main/resources/db/dataops-migration/V20260904100100__create_menu_kind_axis.sql  (dataops 스키마, 프로필 배치가 조인)
- docs/course-reco/menu_category_axis_seed.sql                                 (앱 BE Flyway 인계: menu_category.axis_* 컬럼 + 67행 UPDATE)
- matching/menu_kind_axis_draft_v1.tsv                                        (팀 검수용)
- 표준 출력: 테스트셋 가게별 축 벡터·타입 (운영 메뉴 기준 / 리뷰 언급 메뉴 기준)

사용
    python scripts/axis_seed.py --mapping-text <초안 텍스트> [--env <reader .env>] [--testset matching/course_testset_20260904.tsv]
"""
import argparse
import csv
import io
import math
import os
import re
import subprocess

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AXES = ("taste", "texture", "filling", "vibe")
POLE = {  # 표기 → 값
    "달콤": -1, "담백": +1, "부드": -1, "바삭": +1, "풍부": -1, "심플": +1, "클래식": -1, "트렌디": +1,
}
POLE_LABEL = {"taste": ("달콤", "담백"), "texture": ("부드", "바삭"), "filling": ("풍부", "심플"), "vibe": ("클래식", "트렌디")}
NON_BREAD = {"빙수", "아이스크림", "떡", "요거트"}  # 초안 §5 "같이 정할 것" — 축 계산 제외 권고
TYPE_NAMES = {
    1: "따뜻한 포옹", 2: "화려한 몽상가", 3: "조용한 감성파", 4: "가벼운 구름", 5: "완벽주의 미식가", 6: "트렌드세터",
    7: "우아한 고집", 8: "실험적 예술가", 9: "든든한 현실주의자", 10: "경계를 허무는 자", 11: "수수한 일상", 12: "미니멀리스트",
    13: "날카로운 감각", 14: "세련된 반전", 15: "고독한 철학자", 16: "뉴웨이브 미니멀",
}
NEUTRAL = 0.15  # 절댓값 미만이면 "중간"


def parse_mapping(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 6 or not cells[0].isdigit():
            continue
        no, kind, group = int(cells[0]), cells[1], cells[2]
        if cells[3] == "축 없음":
            rows.append({"no": no, "kind": kind, "group": None, "axes": None, "type": None, "confidence": "제외", "memo": ""})
            continue
        axes = {a: POLE[cells[3 + i]] for i, a in enumerate(AXES)}
        type_no = int(cells[7])
        expected = 1 + 8 * (axes["taste"] > 0) + 4 * (axes["texture"] > 0) + 2 * (axes["filling"] > 0) + (axes["vibe"] > 0)
        assert type_no == expected, f"{kind}: 표 타입 {type_no} != 축으로 계산한 {expected}"
        rows.append({"no": no, "kind": kind, "group": None if group == "—" else group, "axes": axes, "type": type_no,
                     "confidence": cells[8], "memo": cells[9] if len(cells) > 9 else ""})
    assert len(rows) == 67, f"67행이어야 하는데 {len(rows)}행"
    return rows


def sql_str(value):
    return "NULL" if value is None else "'" + value.replace("'", "''") + "'"


def sql_num(value):
    return "NULL" if value is None else str(value)


def write_dataops_migration(rows, path):
    lines = [
        "-- 메뉴 종류 → 빵BTI 4축 (설계 §2 축 채점 규칙, 매핑 초안 2026-09-04 v1 — 팀 검수 전 초안값)",
        "-- A극 -1 / B극 +1: 맛 달콤(-1)/담백(+1) · 식감 부드(-1)/바삭(+1) · 속 풍부(-1)/심플(+1) · 분위기 클래식(-1)/트렌디(+1)",
        "-- 빵·기타·요거트는 축 없음(NULL). is_bread=false(빙수·아이스크림·떡·요거트)는 축 계산 제외 권고. 확정본은 draft_version 을 올려 UPDATE.",
        "CREATE TABLE menu_kind_axis (",
        "    kind          VARCHAR(30) PRIMARY KEY,",
        "    kind_group    VARCHAR(30),",
        "    axis_taste    SMALLINT,",
        "    axis_texture  SMALLINT,",
        "    axis_filling  SMALLINT,",
        "    axis_vibe     SMALLINT,",
        "    bbti_type     SMALLINT,",
        "    confidence    VARCHAR(10) NOT NULL,",
        "    is_bread      BOOLEAN     NOT NULL DEFAULT TRUE,",
        "    memo          VARCHAR(200),",
        "    draft_version INTEGER     NOT NULL DEFAULT 1",
        ");",
        "INSERT INTO menu_kind_axis (kind, kind_group, axis_taste, axis_texture, axis_filling, axis_vibe, bbti_type, confidence, is_bread, memo) VALUES",
    ]
    values = []
    for r in rows:
        a = r["axes"] or {}
        values.append("    ({}, {}, {}, {}, {}, {}, {}, {}, {}, {})".format(
            sql_str(r["kind"]), sql_str(r["group"]), sql_num(a.get("taste")), sql_num(a.get("texture")),
            sql_num(a.get("filling")), sql_num(a.get("vibe")), sql_num(r["type"]), sql_str(r["confidence"]),
            "FALSE" if r["kind"] in NON_BREAD else "TRUE", sql_str(r["memo"] or None)))
    lines.append(",\n".join(values) + ";")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def write_be_seed(rows, path):
    lines = [
        "-- 앱 BE(bbang-ggut_BE) Flyway 인계용 — menu_category 에 빵BTI 4축 컬럼 + 67행 시드 (설계 §4, 매핑 초안 v1 · 팀 검수 전)",
        "-- 값: -1 = A극(달콤·부드·풍부·클래식) / +1 = B극(담백·바삭·심플·트렌디) / NULL = 축 없음(빵·기타·요거트)",
        "ALTER TABLE menu_category",
        "    ADD COLUMN axis_taste SMALLINT, ADD COLUMN axis_texture SMALLINT,",
        "    ADD COLUMN axis_filling SMALLINT, ADD COLUMN axis_vibe SMALLINT;",
    ]
    for r in rows:
        if not r["axes"]:
            continue
        a = r["axes"]
        lines.append("UPDATE menu_category SET axis_taste = {:+d}, axis_texture = {:+d}, axis_filling = {:+d}, axis_vibe = {:+d} WHERE name = {};  -- 타입 {} {}".format(
            a["taste"], a["texture"], a["filling"], a["vibe"], sql_str(r["kind"]), r["type"], r["confidence"]))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def write_review_tsv(rows, path):
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(["no", "kind", "group", "taste", "texture", "filling", "vibe", "type", "type_name", "confidence", "is_bread", "memo", "team_verdict"])
        for r in rows:
            a = r["axes"] or {}
            w.writerow([r["no"], r["kind"], r["group"] or "", *(POLE_LABEL[x][a[x] > 0] if a else "" for x in AXES),
                        r["type"] or "", TYPE_NAMES.get(r["type"], "") if r["type"] else "", r["confidence"],
                        "N" if r["kind"] in NON_BREAD else ("Y" if a else ""), r["memo"], ""])


# ---------- 눈검사 ----------

def read_env(path, key):
    for line in open(path, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{path}: {key} 없음")


def psql_csv(args, sql, env=None):
    env_flags = [x for k, v in (env or {}).items() for x in ("-e", f"{k}={v}")]
    r = subprocess.run(["docker", "exec", "-i", *env_flags, "bbanggut-dataops-db", "psql", *args, "--csv", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                       input=sql, capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        raise SystemExit("psql 실패: " + r.stderr.strip()[:500])
    return list(csv.DictReader(io.StringIO(r.stdout)))


def vector(weighted):
    """[(axes dict, weight)] → 축별 가중 평균 (없으면 None)"""
    total = sum(w for _, w in weighted)
    if total <= 0:
        return None
    return {a: sum(ax[a] * w for ax, w in weighted) / total for a in AXES}


def type_of(vec):
    if vec is None:
        return "—", "—"
    code = 1
    labels = []
    for bit, a in zip((8, 4, 2, 1), AXES):
        v = vec[a]
        if abs(v) < NEUTRAL:
            labels.append("중간")
        else:
            labels.append(POLE_LABEL[a][v > 0])
            code += bit if v > 0 else 0
    return f"{code} {TYPE_NAMES[code]}", "·".join(labels)


def fmt(vec):
    return "—" if vec is None else " ".join(f"{vec[a]:+.2f}" for a in AXES)


def kind_of_label(label, kinds_by_len):
    """리뷰 언급 메뉴 라벨 → 종류 (정확 일치 → 끝단어 일치, 긴 이름 우선). 못 찾으면 None"""
    label = label.replace(" ", "")
    for kind in kinds_by_len:
        if label == kind or label.endswith(kind):
            return kind
    return None


def eyeball(rows, env_path, testset_path):
    axis_by_kind = {r["kind"]: r["axes"] for r in rows if r["axes"] and r["kind"] not in NON_BREAD}
    kinds_by_len = sorted(axis_by_kind, key=len, reverse=True)
    stores = list(csv.DictReader(open(testset_path, encoding="utf-8"), delimiter="\t"))
    ids = [s["store_id"] for s in stores]
    menus = psql_csv(["-h", "host.docker.internal", "-p", "15432", "-U", read_env(env_path, "PROD_DB_USERNAME"), "-d", "soboro"],
                     f"SELECT m.store_id, mc.name AS kind, COALESCE(m.like_count,0) AS likes FROM menu m JOIN menu_category mc ON mc.id = m.menu_category_id "
                     f"WHERE m.deleted_at IS NULL AND m.store_id IN ({','.join(ids)});",
                     env={"PGPASSWORD": read_env(env_path, "PROD_DB_PASSWORD"), "PGSSLMODE": "require", "PGOPTIONS": "-c default_transaction_read_only=on"})
    reviews = psql_csv(["-U", "dataops", "-d", "dataops"],
                       f"SELECT store_id, m->>'label' AS label, (m->>'count')::int AS cnt FROM store_signal, jsonb_array_elements(signals->'reviewMenus') m "
                       f"WHERE store_id IN ({','.join(ids)});")
    by_store_menu, by_store_review = {}, {}
    for m in menus:
        ax = axis_by_kind.get(m["kind"])
        if ax:
            by_store_menu.setdefault(m["store_id"], []).append((ax, 1 + int(m["likes"])))
    for r in reviews:
        kind = kind_of_label(r["label"], kinds_by_len)
        if kind:
            by_store_review.setdefault(r["store_id"], []).append((axis_by_kind[kind], int(r["cnt"])))
    print(f"\n{'상권':5} {'가게':18} {'분류':>3} {'축(운영 메뉴, 맛·식감·속·분위기)':34} {'타입':16} | {'리뷰n':>4} {'축(리뷰 언급 메뉴)':26} {'타입':16}")
    for s in stores:
        sid = s["store_id"]
        v1, v2 = vector(by_store_menu.get(sid, [])), vector(by_store_review.get(sid, []))
        n1 = len(by_store_menu.get(sid, []))
        if n1 < 3:
            v1 = None  # 설계: 분류 메뉴 3개 미만이면 축 없음
        t1, l1 = type_of(v1)
        t2, l2 = type_of(v2)
        print(f"{s['spot']:5} {s['prod_name'][:16]:18} {n1:>3} {fmt(v1):34} {t1:16} | {len(by_store_review.get(sid, [])):>4} {fmt(v2):26} {t2:16}")
        print(f"{'':5} {'':18} {'':>3} {l1:34} {'':16} | {'':>4} {l2:26}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mapping-text", required=True)
    parser.add_argument("--env", default=os.path.join(REPO_ROOT, ".env"))
    parser.add_argument("--testset", default=os.path.join(REPO_ROOT, "matching", "course_testset_20260904.tsv"))
    parser.add_argument("--no-eyeball", action="store_true")
    args = parser.parse_args()

    rows = parse_mapping(args.mapping_text)
    with_axes = [r for r in rows if r["axes"]]
    print(f"매핑 {len(rows)}행 — 축 있음 {len(with_axes)}, 제외 {len(rows) - len(with_axes)}, 확신도: " +
          ", ".join(f"{c} {sum(1 for r in with_axes if r['confidence'] == c)}" for c in ("높음", "중간", "낮음")))
    os.makedirs(os.path.join(REPO_ROOT, "docs", "course-reco"), exist_ok=True)
    write_dataops_migration(rows, os.path.join(REPO_ROOT, "src", "main", "resources", "db", "migration", "V20260904100100__create_menu_kind_axis.sql"))
    write_be_seed(rows, os.path.join(REPO_ROOT, "docs", "course-reco", "menu_category_axis_seed.sql"))
    write_review_tsv(rows, os.path.join(REPO_ROOT, "matching", "menu_kind_axis_draft_v1.tsv"))
    print("산출: migration V20260904100100 · docs/course-reco/menu_category_axis_seed.sql · matching/menu_kind_axis_draft_v1.tsv")
    if not args.no_eyeball:
        eyeball(rows, args.env, args.testset)


if __name__ == "__main__":
    main()
